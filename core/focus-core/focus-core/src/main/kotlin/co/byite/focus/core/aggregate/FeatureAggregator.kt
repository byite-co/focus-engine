package co.byite.focus.core.aggregate

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.PowerState
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.V0bRawRecord
import co.byite.focus.core.model.ZoneStatus
import co.byite.focus.core.util.Stats
import kotlin.math.sqrt

/** One closed bucket: the schema-0.2.3 [SecondRecord] plus the paired [V0bRawRecord]. */
data class AggregatedSecond(val second: SecondRecord, val raw: V0bRawRecord)

/**
 * One frame on which Face inference succeeded (directive D 정정 3·4·5). Posted to the aggregation queue as a
 * single message right after the analyzer finished with the frame: `frames_processed` is fixed by the Face
 * result alone, [sample] carries the scalars when the stages after Face also succeeded and is null when they
 * failed (`frames_post_face_failed`).
 */
data class ProcessedFrame(
    val captureMonoNs: Long,
    /** Face Landmarker wall time (ms). */
    val faceInferMs: Double,
    val sample: FrameSample? = null,
) {
    init {
        require(faceInferMs >= 0.0) { "faceInferMs must not be negative (t=$captureMonoNs)" }
        sample?.let { require(it.captureMonoNs == captureMonoNs) { "sample timestamp must match (t=$captureMonoNs)" } }
    }
}

/**
 * 1 Hz aggregation of frame and sensor scalars into per-second records (v0-plan 2장 FeatureAggregator,
 * V0-A/B). Pure logic, deterministic: no clock, no threads. **Single-thread ownership** (directive D 정정 3):
 * one aggregation queue owns an instance; the camera / Face / Scene / Pose / IMU pipelines post scalar
 * samples to that queue and never call the aggregator themselves.
 *
 * Buckets are `[t_start + k·1000, t_start + (k+1)·1000)` aligned to the session start
 * (v0.2.1 판정 1). A bucket closes once its end plus [closeDelayMs] has passed, so late capture
 * results and analyzer callbacks for frames captured inside it have arrived. Every bucket up to
 * the last closable one is emitted, empty ones included (V0-A 통과 기준 "초당 레코드 누락 0").
 *
 * Session window (정정 5, 명확화): only inputs with `sessionStartCaptureTs ≤ captureTs < stopFenceCaptureTs`
 * are counted; earlier ones go to [CounterTotals.inputsBeforeStart], later ones (after [stopInputs]) to
 * [CounterTotals.inputsAfterFence]. Counters that reach the aggregator after their bucket closed are attributed
 * to the oldest open bucket so that the session totals ([totals]) stay conserved; feature samples that arrive
 * late are discarded and counted (`frames_sample_late_dropped`, `pose_late_dropped`, [CounterTotals.otherLateInputs]).
 *
 * V0-B has no calibration and no gates, so the fields that depend on them are left empty:
 * `raw_state`, `final_state`, `invalid_reason`, `candidate_*`, `events`, `torso_*_ratio`,
 * `zone_id`, `bg_tile_texture_ratio` are null/empty. The non-nullable schema fields are filled as
 * decided for v0.2.2: `zone_status = uncalibrated` (no zones exist yet), `imu_state = UNKNOWN` (no dock
 * posture to classify against), `power_state = P0` (no power state machine yet). `scene_luma` is always
 * a measured value: the bucket's samples, or the last measured value when the bucket has none.
 */
class FeatureAggregator(
    private val tStartMonoMs: Long,
    private val tStartUtcMs: Long,
    /** Grace after a bucket's end before it is closed. */
    private val closeDelayMs: Long = DEFAULT_CLOSE_DELAY_MS,
    /** Preset gap threshold counted in `gaps_over_threshold` (80 ms for every-frame presets, 2 × expected interval otherwise). */
    private val gapThresholdNs: Long = DEFAULT_GAP_THRESHOLD_NS,
    /** Preset long-gap threshold counted in `gaps_over_long_threshold` (200 ms for every-frame presets, 5 × expected interval otherwise). */
    private val longGapThresholdNs: Long = DEFAULT_LONG_GAP_THRESHOLD_NS,
) {
    init {
        require(closeDelayMs >= 0) { "closeDelayMs must not be negative" }
        require(gapThresholdNs > 0) { "gapThresholdNs must be positive" }
        require(longGapThresholdNs >= gapThresholdNs) { "longGapThresholdNs must not be below gapThresholdNs" }
    }

    /** Stage times of the last processed frame, kept to name the cause of the next over-threshold gap. */
    private class PrevCycle(val faceInferMs: Double, val sample: FrameSample?)

    private class Bucket {
        var requested = 0
        var received = 0
        var skipped = 0
        var faceErrors = 0
        var preFaceErrors = 0
        var processed = 0
        var sampleApplied = 0
        var sampleLateDropped = 0
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
        val wrapMs = ArrayList<Double>()
        val facePostMs = ArrayList<Double>()
        val enqueueMs = ArrayList<Double>()
        val totalMs = ArrayList<Double>()
        var maxGapNs = 0L
        var gapCount = 0
        var gapsOver80 = 0
        var gapsOverThreshold = 0
        var gapsOverLong = 0
        val gapCauses = IntArray(6)
        val poses = ArrayList<PoseSample>()
        var poseRequested = 0
        var poseSuperseded = 0
        var poseCompleted = 0
        var poseLateDropped = 0
        var poseErrors = 0
        val poseCopyMs = ArrayList<Double>()
        var poseWaitSum = 0.0
        val scenes = ArrayList<SceneSample>()
        var imuN = 0
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sxx = 0.0
        var syy = 0.0
        var szz = 0.0
    }

    private val startNs = tStartMonoMs * NS_PER_MS
    private var fenceNs: Long? = null
    private var finished = false
    private val open = HashMap<Long, Bucket>()
    private var nextToClose = 0L
    private var lastProcessedNs = Long.MIN_VALUE
    private var prevCycle: PrevCycle? = null
    private var captureResultsSeen = false
    private var lastSceneLuma: Double? = null
    private val recentPoses = ArrayList<PoseSample>()
    private val labelChanges = ArrayList<Pair<Long, String?>>()

    // session totals (see CounterTotals)
    private var tRequested = 0L
    private var tReceived = 0L
    private var tSkipped = 0L
    private var tFaceErrors = 0L
    private var tPreFaceErrors = 0L
    private var tProcessed = 0L
    private var tSampleApplied = 0L
    private var tSampleLate = 0L
    private var tPoseRequested = 0L
    private var tPoseSuperseded = 0L
    private var tPoseCompleted = 0L
    private var tPoseApplied = 0L
    private var tPoseLate = 0L
    private var tPoseErrors = 0L
    private var tOtherLate = 0L
    private var tBeforeStart = 0L
    private var tAfterFence = 0L

    /** Session-wide counters for the conservation check at a normal stop. */
    val totals: CounterTotals
        get() = CounterTotals(
            framesRequested = tRequested, framesAnalyzerReceived = tReceived, framesSkippedIntentional = tSkipped,
            faceInferenceErrors = tFaceErrors, preFaceErrors = tPreFaceErrors, framesProcessed = tProcessed,
            framesSampleApplied = tSampleApplied, framesSampleLateDropped = tSampleLate,
            poseRequested = tPoseRequested, poseSuperseded = tPoseSuperseded, poseCompleted = tPoseCompleted,
            poseApplied = tPoseApplied, poseLateDropped = tPoseLate, poseErrors = tPoseErrors,
            otherLateInputs = tOtherLate, inputsBeforeStart = tBeforeStart, inputsAfterFence = tAfterFence,
        )

    /** Scene / IMU inputs that arrived after their bucket had been closed (dropped). */
    val lateInputs: Long get() = tOtherLate

    /** Inputs stamped before the session start (dropped). */
    val inputsBeforeStart: Long get() = tBeforeStart

    /** Inputs stamped at or after the stop fence, or after [finish] (dropped). */
    val inputsAfterFence: Long get() = tAfterFence

    /** Processed frames whose capture timestamp did not increase (still counted as processed, no gap). */
    var nonMonotonicFrames: Long = 0L
        private set

    /** Number of buckets closed so far. */
    val closedBuckets: Long get() = nextToClose

    /** Stop fence (ms, monotonic) once [stopInputs] was called. */
    val stopFenceMonoMs: Long? get() = fenceNs?.let { it / NS_PER_MS }

    // ---- inputs: frame counters (analysis thread → queue)

    /** A frame the camera produced (Camera2 capture result), by capture time. Defines `frames_requested`. */
    fun onFrameRequested(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        captureResultsSeen = true
        b.requested++
        tRequested++
    }

    /** The ImageAnalysis callback received a frame (before any skip / processing decision). */
    fun onFrameReceived(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.received++
        tReceived++
    }

    /** A received frame skipped on purpose (every-other-frame preset, power-state skip). */
    fun onFrameSkipped(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.skipped++
        tSkipped++
    }

    /** The Face Landmarker threw on this frame. */
    fun onFaceInferenceError(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.faceErrors++
        tFaceErrors++
    }

    /** Another error before Face inference (non-monotonic timestamp, wrap failure, pipelines not ready). */
    fun onPreFaceError(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.preFaceErrors++
        tPreFaceErrors++
    }

    /**
     * Face inference succeeded on a frame. Counts `frames_processed` and the Face time, measures the gap to the
     * previous processed frame, and applies the scalars of [ProcessedFrame.sample] when its bucket is still open
     * (`frames_sample_applied`); a sample for a closed bucket is discarded and counted (`frames_sample_late_dropped`).
     */
    fun onFrameProcessed(frame: ProcessedFrame) {
        val k = bucketIndex(frame.captureMonoNs) ?: return
        val late = k < nextToClose
        val b = open.getOrPut(if (late) nextToClose else k) { Bucket() }
        b.processed++
        tProcessed++
        b.faceInferMs.add(frame.faceInferMs)
        if (lastProcessedNs != Long.MIN_VALUE) {
            val gap = frame.captureMonoNs - lastProcessedNs
            if (gap <= 0L) {
                nonMonotonicFrames++
            } else {
                b.gapCount++
                if (gap > b.maxGapNs) b.maxGapNs = gap
                if (gap > GAP_80MS_NS) b.gapsOver80++
                if (gap > gapThresholdNs) {
                    b.gapsOverThreshold++
                    b.gapCauses[gapCause(prevCycle)]++
                }
                if (gap > longGapThresholdNs) b.gapsOverLong++
            }
        }
        if (frame.captureMonoNs > lastProcessedNs) {
            lastProcessedNs = frame.captureMonoNs
            prevCycle = PrevCycle(frame.faceInferMs, frame.sample)
        }
        val sample = frame.sample ?: return
        if (late) {
            b.sampleLateDropped++
            tSampleLate++
            return
        }
        b.sampleApplied++
        tSampleApplied++
        b.latencySumNs += sample.latencyNs
        b.wrapMs.add(sample.wrapMs)
        b.facePostMs.add(sample.facePostMs)
        b.enqueueMs.add(sample.enqueueMs)
        b.totalMs.add(sample.totalMs)
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
    }

    /**
     * Which stage of the previous processed frame's cycle explains a gap over the threshold (원래 지시문 D 2번):
     * the longest of wrap / Face (inference + post) / scene / pose copy / enqueue when that cycle took at least the
     * expected frame interval (threshold ÷ 2), i.e. the analyzer was the bottleneck; otherwise "other" (no sample,
     * or a cycle short enough that the camera or the system must have stalled). Index into [V0bRawRecord.GAP_CAUSE_ORDER].
     */
    private fun gapCause(prev: PrevCycle?): Int {
        val sample = prev?.sample ?: return GAP_CAUSE_OTHER
        val expectedIntervalMs = gapThresholdNs / 2.0 / NS_PER_MS
        if (sample.totalMs < expectedIntervalMs) return GAP_CAUSE_OTHER
        val stages = doubleArrayOf(sample.wrapMs, prev.faceInferMs + sample.facePostMs, sample.sceneMs ?: 0.0, sample.poseCopyMs ?: 0.0, sample.enqueueMs)
        var best = 0
        for (i in 1 until stages.size) if (stages[i] > stages[best]) best = i
        return best
    }

    // ---- inputs: pose worker (analysis thread / worker → queue)

    /** A frame was deep-copied and handed to the Pose worker; [copyMs] is the copy time on the analysis thread. */
    fun onPoseRequested(captureMonoNs: Long, copyMs: Double) {
        require(copyMs >= 0.0) { "copyMs must not be negative" }
        val b = counterBucket(captureMonoNs) ?: return
        b.poseRequested++
        b.poseCopyMs.add(copyMs)
        tPoseRequested++
    }

    /** A waiting request was replaced by a newer frame before the worker took it. */
    fun onPoseSuperseded(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.poseSuperseded++
        tPoseSuperseded++
    }

    /** The Pose Landmarker threw on this request. */
    fun onPoseError(captureMonoNs: Long) {
        val b = counterBucket(captureMonoNs) ?: return
        b.poseErrors++
        tPoseErrors++
    }

    /** A finished Pose run. Returns true when it was applied to its bucket, false when the bucket had closed (counted as late). */
    fun onPose(sample: PoseSample): Boolean {
        val k = bucketIndex(sample.captureMonoNs) ?: return false
        val late = k < nextToClose
        val b = open.getOrPut(if (late) nextToClose else k) { Bucket() }
        b.poseCompleted++
        tPoseCompleted++
        if (late) {
            b.poseLateDropped++
            tPoseLate++
            return false
        }
        b.poses.add(sample)
        b.poseWaitSum += sample.waitMs
        tPoseApplied++
        if (sample.hasShoulders) {
            recentPoses.add(sample)
            val cutoff = sample.captureMonoNs - POSE_REFERENCE_MAX_AGE_NS
            recentPoses.removeAll { it.captureMonoNs < cutoff }
        }
        return true
    }

    // ---- inputs: scene, IMU

    fun onScene(sample: SceneSample) {
        val b = featureBucket(sample.captureMonoNs) ?: return
        b.scenes.add(sample)
    }

    fun onImu(sample: ImuSample) {
        val b = featureBucket(sample.tMonoNs) ?: return
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

    // ---- stop / closing

    /**
     * Accept fence (정정 5 · 명확화): from now on inputs stamped at or after [fenceMonoMs] are not counted.
     * Inputs captured before the fence that are still in flight are accepted until [finish].
     */
    fun stopInputs(fenceMonoMs: Long) {
        require(fenceMonoMs >= tStartMonoMs) { "the fence cannot precede the session start" }
        if (fenceNs == null) fenceNs = fenceMonoMs * NS_PER_MS
    }

    /** Close every bucket whose end + [closeDelayMs] ≤ [nowMonoMs], oldest first. */
    fun closeBuckets(nowMonoMs: Long, device: DeviceSample): List<AggregatedSecond> = closeWhile(device) { end -> end + closeDelayMs <= nowMonoMs }

    /**
     * Session end: close every *complete* bucket (end ≤ [nowMonoMs]) without the grace; the partial last bucket is
     * dropped. Afterwards every input is rejected (counted in [inputsAfterFence]). The session totals are unaffected.
     */
    fun finish(nowMonoMs: Long, device: DeviceSample): List<AggregatedSecond> {
        val out = closeWhile(device) { end -> end <= nowMonoMs }
        finished = true
        return out
    }

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
        // scene_luma is non-nullable and must be a measurement: the device layer starts the session at its
        // first processed frame, which always carries a scene sample, so this is only reachable when that
        // contract is broken. Fail loudly rather than write a constant.
        checkNotNull(luma) { "bucket $k closed before any scene sample; the session must start at the first processed frame" }
        val requested = if (captureResultsSeen) b.requested else b.received
        val backpressure = (requested - b.received).coerceAtLeast(0)
        val unprocessed = (b.received - b.skipped - b.processed).coerceAtLeast(0)
        val postFaceFailed = (b.processed - b.sampleApplied - b.sampleLateDropped).coerceAtLeast(0)
        val second = SecondRecord(
            tMonoMs = start,
            tUtcMs = tStartUtcMs + (start - tStartMonoMs),
            faceDetectRatio = if (b.sampleApplied > 0) b.faceFrames.toDouble() / b.sampleApplied else 0.0,
            shoulderVisibilityMin = lastPose?.shoulderVisibilityMin,
            torsoCenterOffsetRatio = null,
            torsoWidthRatio = null,
            headLandmarkPresent = headPresent,
            headOffsetBelowShoulderRatio = if (headPresent) lastPose?.headOffsetBelowShoulderRatio else null,
            yawMean = if (b.poseAngleN > 0) b.yawSum / b.poseAngleN else null,
            pitchMean = if (b.poseAngleN > 0) b.pitchSum / b.poseAngleN else null,
            rollMean = if (b.poseAngleN > 0) b.rollSum / b.poseAngleN else null,
            zoneStatus = ZoneStatus.UNCALIBRATED,
            zoneId = null,
            poseMotion = lastPose?.let { poseMotion(it) },
            sceneLuma = luma,
            bgTileTextureRatio = null,
            jitterJ = if (b.jitterN > 0) b.jitterSum / b.jitterN else null,
            faceWidthPx = if (b.widthN > 0) b.widthSum / b.widthN else null,
            imuState = ImuState.UNKNOWN,
            screenState = device.screenState,
            appState = device.appState,
            framesRequested = requested,
            framesProcessed = b.processed,
            framesAnalyzerReceived = b.received,
            framesSkippedIntentional = b.skipped,
            framesSampleApplied = b.sampleApplied,
            framesSampleLateDropped = b.sampleLateDropped,
            framesDropped = backpressure + unprocessed + postFaceFailed + b.sampleLateDropped,
            maxFrameGapMs = if (b.gapCount > 0) (b.maxGapNs + NS_PER_MS / 2) / NS_PER_MS else null,
            gapsOver80Ms = b.gapsOver80,
            gapsOverThreshold = b.gapsOverThreshold,
            gapsOverLongThreshold = b.gapsOverLong,
            powerState = PowerState.P0,
        )
        val poseInfer = b.poses.map { it.poseInferMs }
        val sceneMs = b.scenes.map { it.computeMs }
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
            frameLatencyMsMean = if (b.sampleApplied > 0) b.latencySumNs.toDouble() / b.sampleApplied / NS_PER_MS else null,
            stageWrapMsMean = Stats.meanOf(b.wrapMs),
            stageWrapMsP95 = Stats.percentileNearestRankOf(b.wrapMs, 0.95),
            stageWrapMsMax = b.wrapMs.maxOrNull(),
            stageFacePostMsMean = Stats.meanOf(b.facePostMs),
            stageFacePostMsP95 = Stats.percentileNearestRankOf(b.facePostMs, 0.95),
            stageFacePostMsMax = b.facePostMs.maxOrNull(),
            stageSceneMsMean = Stats.meanOf(sceneMs),
            stageSceneMsP95 = Stats.percentileNearestRankOf(sceneMs, 0.95),
            stageSceneMsMax = sceneMs.maxOrNull(),
            stageEnqueueMsMean = Stats.meanOf(b.enqueueMs),
            stageEnqueueMsP95 = Stats.percentileNearestRankOf(b.enqueueMs, 0.95),
            stageEnqueueMsMax = b.enqueueMs.maxOrNull(),
            poseFrameCopyMsMean = Stats.meanOf(b.poseCopyMs),
            poseFrameCopyMsP95 = Stats.percentileNearestRankOf(b.poseCopyMs, 0.95),
            poseFrameCopyMsMax = b.poseCopyMs.maxOrNull(),
            frameTotalMsMean = Stats.meanOf(b.totalMs),
            frameTotalMsP95 = Stats.percentileNearestRankOf(b.totalMs, 0.95),
            frameTotalMsMax = b.totalMs.maxOrNull(),
            poseWaitMsMean = if (b.poses.isNotEmpty()) b.poseWaitSum / b.poses.size else null,
            poseInferMsP95 = Stats.percentileNearestRankOf(poseInfer, 0.95),
            gapCauseWrap = b.gapCauses[GAP_CAUSE_WRAP],
            gapCauseFace = b.gapCauses[GAP_CAUSE_FACE],
            gapCauseScene = b.gapCauses[GAP_CAUSE_SCENE],
            gapCausePoseCopy = b.gapCauses[GAP_CAUSE_POSE_COPY],
            gapCauseEnqueue = b.gapCauses[GAP_CAUSE_ENQUEUE],
            gapCauseOther = b.gapCauses[GAP_CAUSE_OTHER],
            poseRequested = b.poseRequested,
            poseCompleted = b.poseCompleted,
            poseApplied = b.poses.size,
            poseSuperseded = b.poseSuperseded,
            poseLateDropped = b.poseLateDropped,
            poseErrors = b.poseErrors,
            faceInferenceErrors = b.faceErrors,
            preFaceErrors = b.preFaceErrors,
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
            hingeAngleDeg = device.hingeAngleDeg,
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

    /** Bucket index of [captureNs] inside the session window, or null (and a counter bump) when it is outside. */
    private fun bucketIndex(captureNs: Long): Long? {
        if (captureNs < startNs) {
            tBeforeStart++
            return null
        }
        val fence = fenceNs
        if (finished || (fence != null && captureNs >= fence)) {
            tAfterFence++
            return null
        }
        return (captureNs - startNs) / (FocusSchema.RECORD_PERIOD_MS * NS_PER_MS)
    }

    /** Bucket for a counter: its own bucket, or the oldest open bucket when its own has closed (the count is never lost). */
    private fun counterBucket(captureNs: Long): Bucket? {
        val k = bucketIndex(captureNs) ?: return null
        return open.getOrPut(if (k < nextToClose) nextToClose else k) { Bucket() }
    }

    /** Bucket for a feature sample: its own open bucket, or null (counted late) when it has closed. */
    private fun featureBucket(captureNs: Long): Bucket? {
        val k = bucketIndex(captureNs) ?: return null
        if (k < nextToClose) {
            tOtherLate++
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

        /** Threshold of `gaps_over_80ms` (v0-plan V0-A). */
        const val GAP_80MS_NS: Long = 80_000_000L

        /** Default preset threshold (every-frame presets). */
        const val DEFAULT_GAP_THRESHOLD_NS: Long = GAP_80MS_NS

        /** Default long-gap threshold (every-frame presets): 200 ms, must be 0 for the pass mark. */
        const val DEFAULT_LONG_GAP_THRESHOLD_NS: Long = 200_000_000L

        // gap-cause indices, same order as V0bRawRecord.GAP_CAUSE_ORDER
        private const val GAP_CAUSE_WRAP = 0
        private const val GAP_CAUSE_FACE = 1
        private const val GAP_CAUSE_SCENE = 2
        private const val GAP_CAUSE_POSE_COPY = 3
        private const val GAP_CAUSE_ENQUEUE = 4
        private const val GAP_CAUSE_OTHER = 5

        /** `pose_motion` reference window: newest sample at least 0.9 s older, at most 3 s older. */
        const val POSE_REFERENCE_MIN_AGE_NS: Long = 900_000_000L
        const val POSE_REFERENCE_MAX_AGE_NS: Long = 3_000_000_000L
    }
}
