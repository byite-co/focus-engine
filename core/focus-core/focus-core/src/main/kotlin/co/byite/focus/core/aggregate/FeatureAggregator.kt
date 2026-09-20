package co.byite.focus.core.aggregate

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.PowerState
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.V0bRawRecord
import co.byite.focus.core.model.ZoneStatus
import co.byite.focus.core.util.Stats
import kotlin.math.sqrt

/** One closed bucket: the schema-0.2.1 [SecondRecord] plus the paired [V0bRawRecord]. */
data class AggregatedSecond(val second: SecondRecord, val raw: V0bRawRecord)

/**
 * 1 Hz aggregation of frame and sensor scalars into per-second records (v0-plan 2장 FeatureAggregator,
 * V0-A/B). Pure logic, deterministic: no clock, no threads — the device layer feeds samples and
 * calls [closeBuckets] with its own monotonic "now".
 *
 * Buckets are `[t_start + k·1000, t_start + (k+1)·1000)` aligned to the session start
 * (v0.2.1 판정 1). A bucket closes once its end plus [closeDelayMs] has passed, so late capture
 * results and analyzer callbacks for frames captured inside it have arrived. Every bucket up to
 * the last closable one is emitted, empty ones included (V0-A 통과 기준 "초당 레코드 누락 0").
 * Samples that arrive for an already closed bucket are counted in [lateInputs] and dropped;
 * samples before the session start are counted in [inputsBeforeStart].
 *
 * V0-B has no calibration and no gates, so the fields that depend on them are left empty:
 * `raw_state`, `final_state`, `invalid_reason`, `candidate_*`, `events`, `torso_*_ratio`,
 * `zone_id`, `bg_tile_texture_ratio` are null/empty. The non-nullable schema fields that cannot be
 * emptied are filled with the documented placeholders: `zone_status = no_head_pose` (no zones
 * registered), `imu_state = UNKNOWN` (no dock posture to classify against), `power_state = P0`
 * (no power state machine yet).
 */
class FeatureAggregator(
    private val tStartMonoMs: Long,
    private val tStartUtcMs: Long,
    /** Grace after a bucket's end before it is closed. */
    private val closeDelayMs: Long = DEFAULT_CLOSE_DELAY_MS,
    /** Frame gap threshold counted in `gaps_over_80ms`. */
    private val gapThresholdNs: Long = 80_000_000L,
) {
    init {
        require(closeDelayMs >= 0) { "closeDelayMs must not be negative" }
    }

    private class Bucket {
        var requested = 0
        var processed = 0
        var faceFrames = 0
        var yawSum = 0.0
        var pitchSum = 0.0
        var rollSum = 0.0
        var poseAngleN = 0
        var widthSum = 0.0
        var widthN = 0
        var jitterSum = 0.0
        var jitterN = 0
        val faceInferMs = ArrayList<Double>()
        var latencySumNs = 0L
        var maxGapNs = 0L
        var gapCount = 0
        var gapsOverThreshold = 0
        val poses = ArrayList<PoseSample>()
        val scenes = ArrayList<SceneSample>()
        var imuN = 0
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sxx = 0.0
        var syy = 0.0
        var szz = 0.0
    }

    private val open = HashMap<Long, Bucket>()
    private var nextToClose = 0L
    private var lastProcessedNs = Long.MIN_VALUE
    private var captureResultsSeen = false
    private var lastSceneLuma: Double? = null
    private val recentPoses = ArrayList<PoseSample>()
    private val labelChanges = ArrayList<Pair<Long, String?>>()

    /** Inputs that arrived after their bucket had been closed (dropped). */
    var lateInputs: Long = 0L
        private set

    /** Inputs stamped before the session start (dropped). */
    var inputsBeforeStart: Long = 0L
        private set

    /** Processed frames whose capture timestamp did not increase (still counted as processed, no gap). */
    var nonMonotonicFrames: Long = 0L
        private set

    /** Number of buckets closed so far. */
    val closedBuckets: Long get() = nextToClose

    // ---- inputs

    /** A frame the camera produced (Camera2 capture result), by capture time. Defines `frames_requested`. */
    fun onFrameRequested(captureMonoNs: Long) {
        captureResultsSeen = true
        bucket(captureMonoNs / NS_PER_MS)?.let { it.requested++ }
    }

    /** A frame the analysis thread actually ran through the face pipeline. */
    fun onFrame(sample: FrameSample) {
        val b = bucket(sample.captureMonoNs / NS_PER_MS) ?: return
        b.processed++
        b.latencySumNs += sample.latencyNs
        b.faceInferMs.add(sample.faceInferMs)
        if (sample.faceDetected) {
            b.faceFrames++
            if (sample.yawDeg != null && sample.pitchDeg != null && sample.rollDeg != null) {
                b.yawSum += sample.yawDeg
                b.pitchSum += sample.pitchDeg
                b.rollSum += sample.rollDeg
                b.poseAngleN++
            }
            sample.faceWidthPx?.let { b.widthSum += it; b.widthN++ }
            sample.jitterJ?.let { b.jitterSum += it; b.jitterN++ }
        }
        if (lastProcessedNs != Long.MIN_VALUE) {
            val gap = sample.captureMonoNs - lastProcessedNs
            if (gap <= 0L) {
                nonMonotonicFrames++
                return
            }
            b.gapCount++
            if (gap > b.maxGapNs) b.maxGapNs = gap
            if (gap > gapThresholdNs) b.gapsOverThreshold++
        }
        lastProcessedNs = sample.captureMonoNs
    }

    fun onPose(sample: PoseSample) {
        val b = bucket(sample.captureMonoNs / NS_PER_MS) ?: return
        b.poses.add(sample)
        if (sample.hasShoulders) {
            recentPoses.add(sample)
            val cutoff = sample.captureMonoNs - POSE_REFERENCE_MAX_AGE_NS
            recentPoses.removeAll { it.captureMonoNs < cutoff }
        }
    }

    fun onScene(sample: SceneSample) {
        val b = bucket(sample.captureMonoNs / NS_PER_MS) ?: return
        b.scenes.add(sample)
    }

    fun onImu(sample: ImuSample) {
        val b = bucket(sample.tMonoNs / NS_PER_MS) ?: return
        b.imuN++
        b.sx += sample.ax
        b.sy += sample.ay
        b.sz += sample.az
        b.sxx += sample.ax * sample.ax
        b.syy += sample.ay * sample.ay
        b.szz += sample.az * sample.az
    }

    /** Dev-app segment marker from [tMonoMs] on. A bucket carries the label active at its start. */
    fun setSegmentLabel(label: String?, tMonoMs: Long) {
        labelChanges.add(tMonoMs to label)
        labelChanges.sortBy { it.first }
    }

    // ---- closing

    /** Close every bucket whose end + [closeDelayMs] ≤ [nowMonoMs], oldest first. */
    fun closeBuckets(nowMonoMs: Long, device: DeviceSample): List<AggregatedSecond> = closeWhile(device) { end -> end + closeDelayMs <= nowMonoMs }

    /** Session end: close every *complete* bucket (end ≤ [nowMonoMs]) without the grace; the partial last bucket is dropped. */
    fun finish(nowMonoMs: Long, device: DeviceSample): List<AggregatedSecond> = closeWhile(device) { end -> end <= nowMonoMs }

    private inline fun closeWhile(device: DeviceSample, closable: (endMs: Long) -> Boolean): List<AggregatedSecond> {
        val out = ArrayList<AggregatedSecond>()
        while (closable(bucketEnd(nextToClose))) {
            val k = nextToClose
            val b = open.remove(k) ?: Bucket()
            out.add(emit(k, b, device))
            nextToClose = k + 1
            pruneLabels(bucketStart(nextToClose))
        }
        return out
    }

    private fun emit(k: Long, b: Bucket, device: DeviceSample): AggregatedSecond {
        val start = bucketStart(k)
        val lastPose = b.poses.lastOrNull { it.hasShoulders }
        val headPresent = lastPose?.headLandmarkPresent ?: false
        val luma = if (b.scenes.isNotEmpty()) b.scenes.sumOf { it.lumaMean } / b.scenes.size else lastSceneLuma
        if (luma != null) lastSceneLuma = luma
        val requested = if (captureResultsSeen) b.requested else b.processed
        val second = SecondRecord(
            tMonoMs = start,
            tUtcMs = tStartUtcMs + (start - tStartMonoMs),
            faceDetectRatio = if (b.processed > 0) b.faceFrames.toDouble() / b.processed else 0.0,
            shoulderVisibilityMin = lastPose?.shoulderVisibilityMin,
            torsoCenterOffsetRatio = null,
            torsoWidthRatio = null,
            headLandmarkPresent = headPresent,
            headOffsetBelowShoulderRatio = if (headPresent) lastPose?.headOffsetBelowShoulderRatio else null,
            yawMean = if (b.poseAngleN > 0) b.yawSum / b.poseAngleN else null,
            pitchMean = if (b.poseAngleN > 0) b.pitchSum / b.poseAngleN else null,
            rollMean = if (b.poseAngleN > 0) b.rollSum / b.poseAngleN else null,
            zoneStatus = ZoneStatus.NO_HEAD_POSE,
            zoneId = null,
            poseMotion = lastPose?.let { poseMotion(it) },
            sceneLuma = luma ?: 0.0,
            bgTileTextureRatio = null,
            jitterJ = if (b.jitterN > 0) b.jitterSum / b.jitterN else null,
            faceWidthPx = if (b.widthN > 0) b.widthSum / b.widthN else null,
            imuState = ImuState.UNKNOWN,
            screenState = device.screenState,
            appState = device.appState,
            framesRequested = requested,
            framesProcessed = b.processed,
            framesDropped = (requested - b.processed).coerceAtLeast(0),
            maxFrameGapMs = if (b.gapCount > 0) (b.maxGapNs + NS_PER_MS / 2) / NS_PER_MS else null,
            gapsOver80Ms = b.gapsOverThreshold,
            powerState = PowerState.P0,
        )
        val poseInfer = b.poses.map { it.poseInferMs }
        val raw = V0bRawRecord(
            tMonoMs = start,
            segmentLabel = labelAt(start),
            shoulderCenterX = lastPose?.let { it.shoulderCenterXPx!! / it.frameWidthPx },
            shoulderCenterY = lastPose?.let { it.shoulderCenterYPx!! / it.frameHeightPx },
            shoulderWidth = lastPose?.let { it.shoulderWidthPx!! / it.frameWidthPx },
            poseSamples = b.poses.size,
            tileTextureMin = b.scenes.minOfOrNull { it.tileTextureMin },
            tileTextureMedian = Stats.meanOf(b.scenes.map { it.tileTextureMedian }),
            sceneSamples = b.scenes.size,
            faceInferMsMean = Stats.meanOf(b.faceInferMs),
            faceInferMsP95 = Stats.percentileNearestRankOf(b.faceInferMs, 0.95),
            faceInferMsMax = b.faceInferMs.maxOrNull(),
            poseInferMsMean = Stats.meanOf(poseInfer),
            poseInferMsMax = poseInfer.maxOrNull(),
            frameLatencyMsMean = if (b.processed > 0) b.latencySumNs.toDouble() / b.processed / NS_PER_MS else null,
            imuSamples = b.imuN,
            accelXMean = if (b.imuN > 0) b.sx / b.imuN else null,
            accelYMean = if (b.imuN > 0) b.sy / b.imuN else null,
            accelZMean = if (b.imuN > 0) b.sz / b.imuN else null,
            accelVariance = if (b.imuN > 0) variance(b.sx, b.sxx, b.imuN) + variance(b.sy, b.syy, b.imuN) + variance(b.sz, b.szz, b.imuN) else null,
            thermalStatus = device.thermalStatus,
            batteryPct = device.batteryPct,
            batteryCurrentUa = device.batteryCurrentUa,
            batteryVoltageMv = device.batteryVoltageMv,
            isInteractive = device.isInteractive,
            isDeviceIdle = device.isDeviceIdle,
        )
        return AggregatedSecond(second, raw)
    }

    /**
     * Shoulder-centre displacement over ~1 s ÷ shoulder width (schema `pose_motion`). The reference
     * is the newest earlier pose sample at least [POSE_REFERENCE_MIN_AGE_NS] older; null when none
     * exists within [POSE_REFERENCE_MAX_AGE_NS].
     */
    private fun poseMotion(cur: PoseSample): Double? {
        val ref = recentPoses.lastOrNull { it !== cur && cur.captureMonoNs - it.captureMonoNs >= POSE_REFERENCE_MIN_AGE_NS } ?: return null
        if (cur.captureMonoNs - ref.captureMonoNs > POSE_REFERENCE_MAX_AGE_NS) return null
        val dx = cur.shoulderCenterXPx!! - ref.shoulderCenterXPx!!
        val dy = cur.shoulderCenterYPx!! - ref.shoulderCenterYPx!!
        return sqrt(dx * dx + dy * dy) / cur.shoulderWidthPx!!
    }

    private fun variance(sum: Double, sumSq: Double, n: Int): Double {
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }

    // ---- bucket bookkeeping

    private fun bucketStart(k: Long): Long = tStartMonoMs + k * FocusSchema.RECORD_PERIOD_MS
    private fun bucketEnd(k: Long): Long = bucketStart(k + 1)

    /** The open bucket for [tMonoMs], or null (and a counter bump) when it is before the start or already closed. */
    private fun bucket(tMonoMs: Long): Bucket? {
        val d = tMonoMs - tStartMonoMs
        if (d < 0) {
            inputsBeforeStart++
            return null
        }
        val k = d / FocusSchema.RECORD_PERIOD_MS
        if (k < nextToClose) {
            lateInputs++
            return null
        }
        return open.getOrPut(k) { Bucket() }
    }

    private fun labelAt(tMonoMs: Long): String? {
        var label: String? = null
        for ((t, l) in labelChanges) {
            if (t <= tMonoMs) label = l else break
        }
        return label
    }

    /** Keep only the latest change at or before [tMonoMs] plus every later one. */
    private fun pruneLabels(tMonoMs: Long) {
        var keepFrom = -1
        for (i in labelChanges.indices) if (labelChanges[i].first <= tMonoMs) keepFrom = i
        if (keepFrom > 0) {
            val kept = labelChanges.subList(keepFrom, labelChanges.size).toMutableList()
            labelChanges.clear()
            labelChanges.addAll(kept)
        }
    }

    companion object {
        const val DEFAULT_CLOSE_DELAY_MS: Long = 300L
        const val NS_PER_MS: Long = 1_000_000L

        /** `pose_motion` reference window: newest sample at least 0.9 s older, at most 3 s older. */
        const val POSE_REFERENCE_MIN_AGE_NS: Long = 900_000_000L
        const val POSE_REFERENCE_MAX_AGE_NS: Long = 3_000_000_000L
    }
}
