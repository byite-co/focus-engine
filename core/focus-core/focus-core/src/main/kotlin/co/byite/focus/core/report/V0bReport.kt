package co.byite.focus.core.report

import co.byite.focus.core.aggregate.CounterTotals
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.V0bRawRecord
import co.byite.focus.core.util.Stats
import kotlin.math.abs

/** Whole-session part of the V0-B summary (directive C "요약 · 전체", directive D counters). */
data class V0bOverall(
    val sessionId: String,
    val device: String,
    val algorithmVersion: String,
    /** "1280x720 (16:9) @ 24fps" — the resolution CameraX actually chose, never the requested one. */
    val camera: String,
    /** "id 1 (FRONT)" from the header; empty in older logs. */
    val cameraIdLine: String,
    /** Fold state of a foldable over the session ("펼침 100%", "폴더블 아님", "접힘 상태 미상"). */
    val foldLine: String,
    val preset: String,
    val gapThresholdMs: Int,
    val longGapThresholdMs: Int,
    val endReason: String,
    /** Session length in seconds: `session_end.t_mono_ms − header.t_start_mono_ms`, or up to the last record when there is no end line. */
    val sessionLengthS: Double,
    val records: Int,
    /** Complete buckets between the session start and the end that have no `second` line. */
    val missingRecords: Long,
    /** Records with `frames_processed == 0`. */
    val zeroFrameRecords: Int,
    val frames: FrameCounts,
    /** Processed frames ÷ recorded seconds. */
    val processedFps: Double?,
    val gapsOver80Ms: Long,
    val gapsOverThreshold: Long,
    val gapsOverLongThreshold: Long,
    val maxFrameGapMs: Long?,
    /** Face Landmarker ms: frame-weighted mean, nearest-rank p95 of the per-second means, max of the per-second max. */
    val faceInferMsMean: Double?,
    val faceInferMsP95OfSecondMeans: Double?,
    val faceInferMsMax: Double?,
    val pose: PoseCounts,
    val poseInferMsMean: Double?,
    val poseInferMsMax: Double?,
    val maxThermalStatus: Int?,
    val batteryStartPct: Int?,
    val batteryEndPct: Int?,
    /** Mean of the raw `battery_current_ua` values (sign as reported by the device). */
    val meanCurrentUa: Double?,
    /** Mean over seconds of |current_ua| × voltage_mv ÷ 10⁶ (mW). */
    val meanPowerMw: Double?,
    val screenOffSeconds: Int,
    val idleSeconds: Int,
    val timebaseLines: Int,
) {
    val missingSeconds: Long get() = missingRecords + zeroFrameRecords
    val framesRequested: Long get() = frames.requested
    val framesProcessed: Long get() = frames.processed
    val framesDropped: Long get() = frames.dropped
    val dropRatio: Double? get() = frames.dropRatio
}

/** Frame counters summed over a set of seconds (schema 0.2.3 definitions, CHANGELOG v0.2.3). */
data class FrameCounts(
    val requested: Long,
    val analyzerReceived: Long,
    val skippedIntentional: Long,
    val processed: Long,
    val sampleApplied: Long,
    val sampleLateDropped: Long,
    val backpressureDrops: Long,
    val unprocessedUnexpected: Long,
    val postFaceFailed: Long,
    val dropped: Long,
    val faceInferenceErrors: Long,
    val preFaceErrors: Long,
) {
    /** Frames meant to be processed: `requested − skipped_intentional`. */
    val targeted: Long get() = requested - skippedIntentional

    /** `dropped ÷ targeted`; null without targeted frames. */
    val dropRatio: Double? get() = if (targeted > 0) dropped.toDouble() / targeted else null

    companion object {
        val ZERO = FrameCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        fun of(rows: List<Pair<SecondRecord, V0bRawRecord?>>): FrameCounts {
            var req = 0L; var recv = 0L; var skip = 0L; var proc = 0L; var applied = 0L; var late = 0L
            var bp = 0L; var unexp = 0L; var post = 0L; var dropped = 0L; var faceErr = 0L; var preErr = 0L
            for ((s, r) in rows) {
                req += s.framesRequested; recv += s.framesAnalyzerReceived; skip += s.framesSkippedIntentional
                proc += s.framesProcessed; applied += s.framesSampleApplied; late += s.framesSampleLateDropped
                bp += s.backpressureDrops; unexp += s.framesUnprocessedUnexpected; post += s.framesPostFaceFailed; dropped += s.framesDropped
                faceErr += r?.faceInferenceErrors ?: 0; preErr += r?.preFaceErrors ?: 0
            }
            return FrameCounts(req, recv, skip, proc, applied, late, bp, unexp, post, dropped, faceErr, preErr)
        }
    }
}

/** Pose worker counters summed over a set of seconds. */
data class PoseCounts(
    val requested: Long,
    val superseded: Long,
    val completed: Long,
    val applied: Long,
    val lateDropped: Long,
    val errors: Long,
) {
    companion object {
        val ZERO = PoseCounts(0, 0, 0, 0, 0, 0)

        fun of(raws: List<V0bRawRecord>): PoseCounts = PoseCounts(
            requested = raws.sumOf { it.poseRequested.toLong() },
            superseded = raws.sumOf { it.poseSuperseded.toLong() },
            completed = raws.sumOf { it.poseCompleted.toLong() },
            applied = raws.sumOf { it.poseApplied.toLong() },
            lateDropped = raws.sumOf { it.poseLateDropped.toLong() },
            errors = raws.sumOf { it.poseErrors.toLong() },
        )
    }
}

/** Weighted mean, nearest-rank p95 of the per-second means, and max of the per-second max of one timed stage. */
data class StageStat(val mean: Double?, val p95OfSecondMeans: Double?, val max: Double?) {
    companion object {
        val EMPTY = StageStat(null, null, null)
    }
}

/**
 * Comparison statistics of one class of seconds (directive D 정정 1 3번, 정정 2 4번, 정정 3 5번): the screen-off row,
 * the screen-on row, the warm-up line (first 60 s) and the transition line (first 5 s after a screen-state change).
 */
data class V0bRow(
    val name: String,
    val seconds: Int,
    val frames: FrameCounts,
    val processedFps: Double?,
    val gapsOverThreshold: Long,
    /** `gaps_over_threshold ÷ frames_processed` (every processed frame after the first begins one interval). */
    val gapRatio: Double?,
    val gapsOverLongThreshold: Long,
    val maxFrameGapMs: Long?,
    /** Cause → count of the gaps over the threshold, in [V0bRawRecord.GAP_CAUSE_ORDER] order. */
    val gapCauses: Map<String, Long>,
    val faceInfer: StageStat,
    val frameTotal: StageStat,
    val wrap: StageStat,
    val facePost: StageStat,
    val scene: StageStat,
    val enqueue: StageStat,
    val poseFrameCopy: StageStat,
    val poseInfer: StageStat,
    val poseWaitMsMean: Double?,
    val pose: PoseCounts,
    val meanCurrentUa: Double?,
    val meanPowerMw: Double?,
    val maxThermalStatus: Int?,
) {
    val faceInferMsMean: Double? get() = faceInfer.mean
    val faceInferMsP95OfSecondMeans: Double? get() = faceInfer.p95OfSecondMeans
    val faceInferMsMax: Double? get() = faceInfer.max
    val frameTotalMsMean: Double? get() = frameTotal.mean
    val stageWrapMsMean: Double? get() = wrap.mean
    val poseFrameCopyMsMean: Double? get() = poseFrameCopy.mean
    val poseInferMsMean: Double? get() = poseInfer.mean
    val poseInferMsMax: Double? get() = poseInfer.max

    /** Gap causes sorted by count, zeros dropped: the "상위 원인" of the summary. */
    val topGapCauses: List<Pair<String, Long>> get() = gapCauses.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
}

/** Preset pass mark on the screen-off row (정정 1 3번, 정정 2 4번). [pass] is null when the off row is empty. */
data class V0bPass(
    val preset: String,
    val offSeconds: Int,
    val fpsMin: Double,
    val processedFps: Double?,
    val gapRatio: Double?,
    val dropRatio: Double?,
    /** Gaps over the long threshold (200 ms; E 417 ms) on the off row; must be 0 (원래 지시문 D 1번). */
    val longGaps: Long,
    val longGapThresholdMs: Int,
    val offMeanPowerMw: Double?,
    val offMaxThermalStatus: Int?,
) {
    val fpsOk: Boolean? get() = processedFps?.let { it >= fpsMin }
    val gapOk: Boolean? get() = gapRatio?.let { it < GAP_RATIO_MAX } ?: if (offSeconds > 0) true else null
    val dropOk: Boolean? get() = dropRatio?.let { it < DROP_RATIO_MAX }
    val longGapOk: Boolean? get() = if (offSeconds == 0) null else longGaps == 0L
    val pass: Boolean? get() = if (offSeconds == 0) null else (fpsOk == true && gapOk == true && dropOk == true && longGapOk == true)

    companion object {
        const val FPS_MIN_EVERY_FRAME: Double = 23.5
        const val GAP_RATIO_MAX: Double = 0.01
        const val DROP_RATIO_MAX: Double = 0.01
    }
}

/** One maximal run of seconds with the same screen state (정정 2 4번). Times are seconds since the session start. */
data class ScreenSegment(
    val startS: Long,
    val endS: Long,
    val screenOn: Boolean,
    val seconds: Int,
    /** Seconds left out of the on/off rows: warm-up and the 5 s after the change. */
    val excludedSeconds: Int,
    val faceInferMsMean: Double?,
    val faceInferMsP95OfSecondMeans: Double?,
    val dropRatio: Double?,
    val meanPowerMw: Double?,
)

/**
 * One window of the trend table (원래 지시문 D 4번 "10초 단위 추이 표": yaw·pitch·roll 평균, face 비율, 처리 fps; at most
 * [V0bReport.TREND_MAX_ROWS] rows, so the window grows in 10 s steps for long sessions).
 */
data class TrendRow(
    val startS: Long,
    val seconds: Int,
    val processedFps: Double?,
    /** Applied-sample-weighted face_detect_ratio. */
    val faceDetectRatio: Double?,
    val yawMean: Double?,
    val pitchMean: Double?,
    val rollMean: Double?,
    val dropRatio: Double?,
    val gapsOverThreshold: Long,
    val faceInferMsMean: Double?,
    val frameTotalMsMean: Double?,
    val poseInferMsMean: Double?,
    val maxThermalStatus: Int?,
    val meanCurrentUa: Double?,
    val screenOnSeconds: Int,
)

/** Per-marker part of the V0-B summary (directive C "요약 · 구간(마커)별"). */
data class V0bSegment(
    val label: String,
    val seconds: Int,
    /** Frame-weighted: Σ(face_detect_ratio × frames_sample_applied) ÷ Σ frames_sample_applied. */
    val faceDetectRatio: Double?,
    val yawMean: Double?,
    val yawSd: Double?,
    val pitchMean: Double?,
    val pitchSd: Double?,
    val rollMean: Double?,
    val rollSd: Double?,
    val faceWidthMedianPx: Double?,
    val jitterMedian: Double?,
    val jitterP95: Double?,
    /** Seconds with `shoulder_visibility_min ≥ 0.6` ÷ seconds. */
    val shoulderVisibleRatio: Double,
    /** Seconds with `head_landmark_present` ÷ seconds. */
    val headLandmarkRatio: Double,
    val headOffsetMedian: Double?,
    val lumaMean: Double?,
)

/**
 * What the live stop path knows beyond the log (정정 4·5): whether the conservation check ran, its result, and
 * the requests cancelled at stop. A recovered summary passes [checked] = false.
 */
data class StopSummary(
    val checked: Boolean,
    val mismatches: List<String> = emptyList(),
    val framesCancelledAtStop: Long = 0,
    val poseCancelledAtStop: Long = 0,
    val totals: CounterTotals? = null,
) {
    companion object {
        val RECOVERED = StopSummary(checked = false)
    }
}

data class V0bSummary(
    val overall: V0bOverall,
    val rows: List<V0bRow>,
    val pass: V0bPass,
    val screenSegments: List<ScreenSegment>,
    /** Trend window length in seconds (10, or a multiple of 10 that keeps the table within [V0bReport.TREND_MAX_ROWS]). */
    val trendWindowS: Long,
    val trend: List<TrendRow>,
    val segments: List<V0bSegment>,
    val notes: List<String>,
    val stop: StopSummary,
) {
    fun render(): String = V0bReport.render(this)
    val offRow: V0bRow get() = rows.first { it.name == V0bReport.ROW_OFF }
    val onRow: V0bRow get() = rows.first { it.name == V0bReport.ROW_ON }
    val warmupRow: V0bRow get() = rows.first { it.name == V0bReport.ROW_WARMUP }
    val transitionRow: V0bRow get() = rows.first { it.name == V0bReport.ROW_TRANSITION }
}

/**
 * V0-B session summary from a [SessionLog] (schema 0.2.3 `second` + `v0b_raw` lines; older logs decode with
 * defaults). The live path and the crash-recovery path build the same summary from the same records, so a
 * recovered summary differs from a live one only by the rows lost before the last flush and by the stop
 * information the live path adds. Deterministic; no clock.
 */
object V0bReport {
    const val SHOULDER_VISIBILITY_MIN: Double = 0.6
    const val NO_LABEL: String = "(마커 없음)"
    const val ROW_OFF = "화면 off"
    const val ROW_ON = "화면 on"
    const val ROW_WARMUP = "워밍업"
    const val ROW_TRANSITION = "전환"
    /** 정정 2: comparison statistics exclude the first 60 s of the session. */
    const val WARMUP_MS: Long = 60_000L
    /** 정정 3: the first 5 s after a screen-state change are a transition, excluded from the on/off rows. */
    const val TRANSITION_MS: Long = 5_000L
    const val TREND_WINDOW_MS: Long = 10_000L
    /** 원래 지시문 D 4번: 최대 60행, 넘으면 간격을 늘린다. */
    const val TREND_MAX_ROWS: Int = 60
    /** Fold-state bands of the hinge angle (degrees): closed below 30, half-opened below 150, flat otherwise. */
    const val HINGE_CLOSED_MAX_DEG: Double = 30.0
    const val HINGE_HALF_MAX_DEG: Double = 150.0
    const val COUNTER_CHECK_SKIPPED = "계수 검증 생략(비정상 종료)"
    const val COUNTER_MISMATCH_PREFIX = "계수 불일치: "
    const val COUNTER_OK = "계수 보존식: 이상 없음"

    private class Row(val s: SecondRecord, val r: V0bRawRecord?) {
        var warmup = false
        var transition = false
        val screenOn: Boolean? get() = r?.isInteractive
    }

    fun build(log: SessionLog, notes: List<String> = emptyList(), stop: StopSummary = StopSummary.RECOVERED): V0bSummary {
        val h = log.header
        val records = log.records.sortedBy { it.tMonoMs }
        val rawByT = log.v0bRaw.associateBy { it.tMonoMs }
        val rows = records.map { Row(it, rawByT[it.tMonoMs]) }
        classify(rows, h.tStartMonoMs)

        val end = log.sessionEnd
        val endMono = end?.tMonoMs ?: records.lastOrNull()?.let { it.tMonoMs + FocusSchema.RECORD_PERIOD_MS } ?: h.tStartMonoMs
        val lengthMs = (endMono - h.tStartMonoMs).coerceAtLeast(0L)
        val expected = lengthMs / FocusSchema.RECORD_PERIOD_MS
        val all = rowStats("전체", rows)
        val raws = rows.mapNotNull { it.r }
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        for (r in raws) r.batteryPct?.let { if (batteryStart == null) batteryStart = it; batteryEnd = it }

        val overall = V0bOverall(
            sessionId = h.sessionId,
            device = "${h.deviceModel}, Android ${h.osVersion}",
            algorithmVersion = h.algorithmVersion,
            camera = cameraLine(h),
            cameraIdLine = h.cameraId?.let { id -> "id $id (${h.lensFacing ?: "?"})" } ?: "",
            foldLine = foldLine(h, raws),
            preset = presetLine(h),
            gapThresholdMs = h.frameGapThresholdMs,
            longGapThresholdMs = h.frameLongGapThresholdMs,
            endReason = end?.reason?.name ?: "없음 (session_end 줄 없음)",
            sessionLengthS = lengthMs / 1000.0,
            records = records.size,
            missingRecords = (expected - records.size).coerceAtLeast(0L),
            zeroFrameRecords = records.count { it.framesProcessed == 0 },
            frames = all.frames,
            processedFps = all.processedFps,
            gapsOver80Ms = records.sumOf { it.gapsOver80Ms.toLong() },
            gapsOverThreshold = all.gapsOverThreshold,
            gapsOverLongThreshold = all.gapsOverLongThreshold,
            maxFrameGapMs = all.maxFrameGapMs,
            faceInferMsMean = all.faceInferMsMean,
            faceInferMsP95OfSecondMeans = all.faceInferMsP95OfSecondMeans,
            faceInferMsMax = all.faceInferMsMax,
            pose = all.pose,
            poseInferMsMean = all.poseInferMsMean,
            poseInferMsMax = all.poseInferMsMax,
            maxThermalStatus = all.maxThermalStatus,
            batteryStartPct = batteryStart,
            batteryEndPct = batteryEnd,
            meanCurrentUa = all.meanCurrentUa,
            meanPowerMw = all.meanPowerMw,
            screenOffSeconds = raws.count { !it.isInteractive },
            idleSeconds = raws.count { it.isDeviceIdle },
            timebaseLines = log.timebase.size,
        )

        val off = rowStats(ROW_OFF, rows.filter { !it.warmup && !it.transition && it.screenOn == false })
        val on = rowStats(ROW_ON, rows.filter { !it.warmup && !it.transition && it.screenOn == true })
        val warmup = rowStats(ROW_WARMUP, rows.filter { it.warmup })
        val transition = rowStats(ROW_TRANSITION, rows.filter { !it.warmup && it.transition })
        val pass = V0bPass(
            preset = h.capturePreset ?: "(없음)",
            offSeconds = off.seconds,
            fpsMin = V0bPass.FPS_MIN_EVERY_FRAME / h.frameProcessDivisor,
            processedFps = off.processedFps,
            gapRatio = off.gapRatio,
            dropRatio = off.frames.dropRatio,
            longGaps = off.gapsOverLongThreshold,
            longGapThresholdMs = h.frameLongGapThresholdMs,
            offMeanPowerMw = off.meanPowerMw,
            offMaxThermalStatus = off.maxThermalStatus,
        )

        val order = ArrayList<String>()
        val groups = HashMap<String, ArrayList<Pair<SecondRecord, V0bRawRecord?>>>()
        for (row in rows) {
            val label = row.r?.segmentLabel ?: NO_LABEL
            if (label !in groups) {
                order.add(label)
                groups[label] = ArrayList()
            }
            groups.getValue(label).add(row.s to row.r)
        }
        val segments = order.map { label -> segment(label, groups.getValue(label)) }
        val trendWindowMs = trendWindowMs(rows, h.tStartMonoMs)
        return V0bSummary(
            overall = overall,
            rows = listOf(off, on, warmup, transition),
            pass = pass,
            screenSegments = screenSegments(rows, h.tStartMonoMs),
            trendWindowS = trendWindowMs / 1000L,
            trend = trend(rows, h.tStartMonoMs, trendWindowMs),
            segments = segments,
            notes = notes,
            stop = stop,
        )
    }

    /** Warm-up = first 60 s; transition = first 5 s of every screen-state run after the first one. */
    private fun classify(rows: List<Row>, tStart: Long) {
        var runStart = -1L
        var prevOn: Boolean? = null
        var first = true
        for (row in rows) {
            val t = row.s.tMonoMs
            row.warmup = t - tStart < WARMUP_MS
            val on = row.screenOn
            if (on != prevOn) {
                runStart = t
                if (prevOn != null) first = false
                prevOn = on
            }
            row.transition = !first && on != null && t - runStart < TRANSITION_MS
        }
    }

    /** Weighted mean / p95 of per-second means / max of per-second max, accumulated over rows. */
    private class StageAcc {
        val means = ArrayList<Double>()
        var weighted = 0.0
        var n = 0L
        var max: Double? = null
        fun add(mean: Double?, max: Double?, weight: Int) {
            if (mean != null && weight > 0) {
                means.add(mean)
                weighted += mean * weight
                n += weight
            }
            max?.let { this.max = maxOf(this.max ?: it, it) }
        }
        fun stat(): StageStat = StageStat(if (n > 0) weighted / n else null, Stats.percentileNearestRankOf(means, 0.95), max)
    }

    private fun rowStats(name: String, rows: List<Row>): V0bRow {
        val secs = rows.map { it.s }
        val raws = rows.mapNotNull { it.r }
        val frames = FrameCounts.of(rows.map { it.s to it.r })
        val face = StageAcc(); val total = StageAcc(); val wrap = StageAcc(); val post = StageAcc(); val scene = StageAcc()
        val enqueue = StageAcc(); val copy = StageAcc(); val pose = StageAcc()
        var waitW = 0.0; var waitN = 0L
        val currents = ArrayList<Double>(); val powers = ArrayList<Double>()
        var thermal: Int? = null
        val causes = LongArray(V0bRawRecord.GAP_CAUSE_ORDER.size)
        for (row in rows) {
            val s = row.s
            val r = row.r ?: continue
            face.add(r.faceInferMsMean, r.faceInferMsMax, s.framesProcessed)
            total.add(r.frameTotalMsMean, r.frameTotalMsMax, s.framesSampleApplied)
            wrap.add(r.stageWrapMsMean, r.stageWrapMsMax, s.framesSampleApplied)
            post.add(r.stageFacePostMsMean, r.stageFacePostMsMax, s.framesSampleApplied)
            scene.add(r.stageSceneMsMean, r.stageSceneMsMax, r.sceneSamples)
            enqueue.add(r.stageEnqueueMsMean, r.stageEnqueueMsMax, s.framesSampleApplied)
            copy.add(r.poseFrameCopyMsMean, r.poseFrameCopyMsMax, r.poseRequested)
            pose.add(r.poseInferMsMean, r.poseInferMsMax, r.poseSamples)
            r.poseWaitMsMean?.let { waitW += it * r.poseSamples; waitN += r.poseSamples }
            r.batteryCurrentUa?.let { i ->
                currents.add(i.toDouble())
                r.batteryVoltageMv?.let { v -> powers.add(abs(i.toDouble()) * v / 1_000_000.0) }
            }
            thermal = maxOf(thermal ?: r.thermalStatus, r.thermalStatus)
            var i = 0
            for ((_, c) in r.gapCauses) causes[i++] += c
        }
        val gaps = secs.sumOf { it.gapsOverThreshold.toLong() }
        val causeMap = LinkedHashMap<String, Long>()
        V0bRawRecord.GAP_CAUSE_ORDER.forEachIndexed { i, k -> causeMap[k] = causes[i] }
        return V0bRow(
            name = name,
            seconds = secs.size,
            frames = frames,
            processedFps = if (secs.isNotEmpty()) frames.processed.toDouble() / secs.size else null,
            gapsOverThreshold = gaps,
            gapRatio = if (frames.processed > 0) gaps.toDouble() / frames.processed else null,
            gapsOverLongThreshold = secs.sumOf { it.gapsOverLongThreshold.toLong() },
            maxFrameGapMs = secs.mapNotNull { it.maxFrameGapMs }.maxOrNull(),
            gapCauses = causeMap,
            faceInfer = face.stat(),
            frameTotal = total.stat(),
            wrap = wrap.stat(),
            facePost = post.stat(),
            scene = scene.stat(),
            enqueue = enqueue.stat(),
            poseFrameCopy = copy.stat(),
            poseInfer = pose.stat(),
            poseWaitMsMean = if (waitN > 0) waitW / waitN else null,
            pose = PoseCounts.of(raws),
            meanCurrentUa = Stats.meanOf(currents),
            meanPowerMw = Stats.meanOf(powers),
            maxThermalStatus = thermal,
        )
    }

    private fun screenSegments(rows: List<Row>, tStart: Long): List<ScreenSegment> {
        val out = ArrayList<ScreenSegment>()
        var i = 0
        while (i < rows.size) {
            val on = rows[i].screenOn
            var j = i
            while (j < rows.size && rows[j].screenOn == on) j++
            val run = rows.subList(i, j)
            if (on != null) {
                val st = rowStats("run", run)
                out.add(
                    ScreenSegment(
                        startS = (run.first().s.tMonoMs - tStart) / 1000L,
                        endS = (run.last().s.tMonoMs - tStart) / 1000L + 1,
                        screenOn = on,
                        seconds = run.size,
                        excludedSeconds = run.count { it.warmup || it.transition },
                        faceInferMsMean = st.faceInferMsMean,
                        faceInferMsP95OfSecondMeans = st.faceInferMsP95OfSecondMeans,
                        dropRatio = st.frames.dropRatio,
                        meanPowerMw = st.meanPowerMw,
                    ),
                )
            }
            i = j
        }
        return out
    }

    /** 10 s, or the smallest multiple of 10 s that keeps the session within [TREND_MAX_ROWS] rows. */
    private fun trendWindowMs(rows: List<Row>, tStart: Long): Long {
        val last = rows.lastOrNull()?.s?.tMonoMs ?: return TREND_WINDOW_MS
        val spanMs = last - tStart + FocusSchema.RECORD_PERIOD_MS
        val windows = (spanMs + TREND_WINDOW_MS - 1) / TREND_WINDOW_MS
        val factor = (windows + TREND_MAX_ROWS - 1) / TREND_MAX_ROWS
        return TREND_WINDOW_MS * factor.coerceAtLeast(1L)
    }

    private fun trend(rows: List<Row>, tStart: Long, windowMs: Long): List<TrendRow> {
        val byWindow = LinkedHashMap<Long, ArrayList<Row>>()
        for (row in rows) byWindow.getOrPut((row.s.tMonoMs - tStart) / windowMs) { ArrayList() }.add(row)
        return byWindow.entries.sortedBy { it.key }.map { (w, list) ->
            val st = rowStats("w", list)
            val secs = list.map { it.s }
            val applied = secs.sumOf { it.framesSampleApplied.toLong() }
            TrendRow(
                startS = w * windowMs / 1000L,
                seconds = list.size,
                processedFps = st.processedFps,
                faceDetectRatio = if (applied > 0) secs.sumOf { it.faceDetectRatio * it.framesSampleApplied } / applied else null,
                yawMean = Stats.meanOf(secs.mapNotNull { it.yawMean }),
                pitchMean = Stats.meanOf(secs.mapNotNull { it.pitchMean }),
                rollMean = Stats.meanOf(secs.mapNotNull { it.rollMean }),
                dropRatio = st.frames.dropRatio,
                gapsOverThreshold = st.gapsOverThreshold,
                faceInferMsMean = st.faceInferMsMean,
                frameTotalMsMean = st.frameTotalMsMean,
                poseInferMsMean = st.poseInferMsMean,
                maxThermalStatus = st.maxThermalStatus,
                meanCurrentUa = st.meanCurrentUa,
                screenOnSeconds = list.count { it.screenOn == true },
            )
        }
    }

    /** Fold state over the session from the per-second hinge angle (원래 지시문 D 4번). */
    fun foldLine(h: SessionHeader, raws: List<V0bRawRecord>): String {
        if (h.foldable == false) return "폴더블 아님"
        val angles = raws.mapNotNull { it.hingeAngleDeg }
        if (angles.isEmpty()) return if (h.foldable == true) "접힘 상태 미상 (hinge 값 없음)" else "-"
        var flat = 0; var half = 0; var closed = 0
        for (a in angles) when {
            a < HINGE_CLOSED_MAX_DEG -> closed++
            a < HINGE_HALF_MAX_DEG -> half++
            else -> flat++
        }
        val n = angles.size.toDouble()
        val parts = ArrayList<String>()
        if (flat > 0) parts.add("펼침 ${Stats.pct(flat / n, 0)}")
        if (half > 0) parts.add("반접힘 ${Stats.pct(half / n, 0)}")
        if (closed > 0) parts.add("접힘 ${Stats.pct(closed / n, 0)}")
        return parts.joinToString(", ") + " (hinge 평균 ${Stats.fmt(Stats.meanOf(angles), 0)}°)"
    }

    private fun segment(label: String, rows: List<Pair<SecondRecord, V0bRawRecord?>>): V0bSegment {
        val secs = rows.map { it.first }
        val applied = secs.sumOf { it.framesSampleApplied.toLong() }
        val faceFrames = secs.sumOf { it.faceDetectRatio * it.framesSampleApplied }
        val yaw = secs.mapNotNull { it.yawMean }
        val pitch = secs.mapNotNull { it.pitchMean }
        val roll = secs.mapNotNull { it.rollMean }
        val jitter = secs.mapNotNull { it.jitterJ }
        return V0bSegment(
            label = label,
            seconds = secs.size,
            faceDetectRatio = if (applied > 0) faceFrames / applied else null,
            yawMean = Stats.meanOf(yaw),
            yawSd = Stats.stddevOf(yaw),
            pitchMean = Stats.meanOf(pitch),
            pitchSd = Stats.stddevOf(pitch),
            rollMean = Stats.meanOf(roll),
            rollSd = Stats.stddevOf(roll),
            faceWidthMedianPx = Stats.medianOf(secs.mapNotNull { it.faceWidthPx }),
            jitterMedian = Stats.medianOf(jitter),
            jitterP95 = Stats.percentileNearestRankOf(jitter, 0.95),
            shoulderVisibleRatio = secs.count { (it.shoulderVisibilityMin ?: -1.0) >= SHOULDER_VISIBILITY_MIN }.toDouble() / secs.size,
            headLandmarkRatio = secs.count { it.headLandmarkPresent }.toDouble() / secs.size,
            headOffsetMedian = Stats.medianOf(secs.mapNotNull { it.headOffsetBelowShoulderRatio }),
            lumaMean = Stats.meanOf(secs.map { it.sceneLuma }),
        )
    }

    fun cameraLine(h: SessionHeader): String {
        val ar = h.cameraAspectRatio?.let { " ($it)" } ?: ""
        return "${h.cameraResolution}$ar @ ${h.nominalFps}fps"
    }

    fun presetLine(h: SessionHeader): String {
        val parts = ArrayList<String>()
        parts.add("Face ${h.faceDelegate ?: "?"}")
        parts.add("blendshape ${when (h.faceBlendshapes) { true -> "on"; false -> "off"; null -> "?" }}")
        parts.add(if (h.frameProcessDivisor == 1) "매 프레임" else "${h.frameProcessDivisor}프레임마다 1회")
        parts.add("갭 임계 ${h.frameGapThresholdMs}ms")
        h.perfHintTargetMs?.let { parts.add("perf hint ${it}ms") }
        return "${h.capturePreset ?: "(없음)"} (${parts.joinToString(", ")})"
    }

    fun render(s: V0bSummary): String {
        val o = s.overall
        fun f(x: Double?, d: Int = 1): String = Stats.fmt(x, d)
        fun deg(m: Double?, sd: Double?): String = "${f(m)}° ± ${f(sd)}°"
        fun ok(b: Boolean?): String = when (b) { true -> "OK"; false -> "미달"; null -> "-" }
        fun mA(ua: Double?): String = if (ua == null) "-" else f(ua / 1000.0, 0)
        return buildString {
            if (!s.stop.checked) {
                appendLine(COUNTER_CHECK_SKIPPED)
            } else if (s.stop.mismatches.isEmpty()) {
                appendLine(COUNTER_OK)
            } else {
                for (m in s.stop.mismatches) appendLine(COUNTER_MISMATCH_PREFIX + m)
            }
            appendLine("focus-engine V0-A/B 요약  세션 ${o.sessionId}  프리셋 ${o.preset}")
            appendLine("기기: ${o.device}  엔진 ${o.algorithmVersion}  카메라 ${if (o.cameraIdLine.isNotEmpty()) o.cameraIdLine + " " else ""}${o.camera}(CameraX 실제 선택)  접힘 상태: ${o.foldLine}")
            appendLine("종료: ${o.endReason}, 세션 길이 ${f(o.sessionLengthS)}s, 초당 레코드 ${o.records}")
            val fr = o.frames
            appendLine(
                "프레임(전체): 요청 ${fr.requested}, 분석기 수신 ${fr.analyzerReceived}, 의도적 건너뜀 ${fr.skippedIntentional}, 처리 ${fr.processed}, " +
                    "표본 반영 ${fr.sampleApplied}, 표본 늦어 폐기 ${fr.sampleLateDropped}",
            )
            appendLine(
                "드롭 ${fr.dropped} = 백프레셔 ${fr.backpressureDrops} + 미처리(예상 밖) ${fr.unprocessedUnexpected} (Face 추론 오류 ${fr.faceInferenceErrors}, Face 이전 오류 ${fr.preFaceErrors}) " +
                    "+ Face 이후 실패 ${fr.postFaceFailed} + 표본 늦어 폐기 ${fr.sampleLateDropped}; 드롭 비율 ${Stats.pct(fr.dropRatio)} (÷ 요청−건너뜀 ${fr.targeted}), 처리 fps ${f(o.processedFps, 2)}",
            )
            appendLine("갭 > ${o.gapThresholdMs}ms: ${o.gapsOverThreshold} (80ms 초과 ${o.gapsOver80Ms}), 갭 > ${o.longGapThresholdMs}ms: ${o.gapsOverLongThreshold}, 최대 갭 ${o.maxFrameGapMs?.let { "$it ms" } ?: "-"}")
            appendLine("누락된 초: ${o.missingSeconds} (레코드 없는 초 ${o.missingRecords} + 프레임 0인 초 ${o.zeroFrameRecords})")
            appendLine("Face 추론 ms: 평균 ${f(o.faceInferMsMean)}, p95 ${f(o.faceInferMsP95OfSecondMeans)} (초당 평균 기준), 최대 ${f(o.faceInferMsMax)}")
            val p = o.pose
            appendLine(
                "Pose: 요청 ${p.requested}, 대기 중 교체 ${p.superseded}, 완료 ${p.completed} (반영 ${p.applied}, 늦어 폐기 ${p.lateDropped}), 오류 ${p.errors}, " +
                    "종료 시 취소 ${if (s.stop.checked) s.stop.poseCancelledAtStop.toString() else "-"}; 추론 ms 평균 ${f(o.poseInferMsMean)}, 최대 ${f(o.poseInferMsMax)}",
            )
            appendLine("최고 thermal status: ${o.maxThermalStatus?.let { "$it (${thermalName(it)})" } ?: "-"}")
            appendLine("배터리: ${o.batteryStartPct ?: "?"}% → ${o.batteryEndPct ?: "?"}%, 평균 전류 ${f(o.meanCurrentUa, 0)} µA, 추정 평균 전력 ${f(o.meanPowerMw)} mW")
            appendLine("화면 off 행 ${o.screenOffSeconds}, idle 행 ${o.idleSeconds}, timebase 줄 ${o.timebaseLines}")

            fun st(x: StageStat, d: Int = 1): String = "${f(x.mean, d)}/${f(x.p95OfSecondMeans, d)}/${f(x.max, d)}"
            appendLine("[비교 통계] 워밍업 ${WARMUP_MS / 1000}s 와 화면 상태 전환 뒤 ${TRANSITION_MS / 1000}s 는 on/off 행에서 제외; ms 는 평균/p95(초당 평균 기준)/최대")
            appendLine("행 | 초 | fps | 드롭%(백프레셔/미처리/Face후/늦음) | 갭>${o.gapThresholdMs}ms %(수) | 갭>${o.longGapThresholdMs}ms | 최대 갭 | Face ms | 사이클 ms | mA | mW | thermal")
            for (r in s.rows) {
                val rf = r.frames
                appendLine(
                    "${r.name} | ${r.seconds} | ${f(r.processedFps, 2)} | ${Stats.pct(rf.dropRatio)} (${rf.backpressureDrops}/${rf.unprocessedUnexpected}/${rf.postFaceFailed}/${rf.sampleLateDropped}) | " +
                        "${Stats.pct(r.gapRatio, 2)} (${r.gapsOverThreshold}) | ${r.gapsOverLongThreshold} | ${r.maxFrameGapMs?.let { "$it ms" } ?: "-"} | ${st(r.faceInfer)} | ${st(r.frameTotal)} | " +
                        "${mA(r.meanCurrentUa)} | ${f(r.meanPowerMw, 0)} | ${r.maxThermalStatus ?: "-"}",
                )
                appendLine(
                    "    단계 ms: 변환·전처리 ${st(r.wrap, 2)}  face_post ${st(r.facePost, 2)}  scene ${st(r.scene, 2)}  큐 적재 ${st(r.enqueue, 3)}  Pose 복사 ${st(r.poseFrameCopy, 2)}  " +
                        "Pose 추론 ${st(r.poseInfer)} 대기 ${f(r.poseWaitMsMean)}",
                )
                val causes = r.topGapCauses
                appendLine("    갭>${o.gapThresholdMs}ms 원인: " + if (causes.isEmpty()) "없음" else causes.joinToString(", ") { (k, v) -> "$k $v" })
            }
            val ps = s.pass
            val verdict = when (ps.pass) { true -> "합격"; false -> "불합격"; null -> "판정 불가(화면 off 행 없음)" }
            appendLine(
                "합격(프리셋 ${ps.preset}, 화면 off 행 기준): $verdict — fps ${f(ps.processedFps, 2)} ≥ ${f(ps.fpsMin, 2)} ${ok(ps.fpsOk)}, " +
                    "갭 초과 ${Stats.pct(ps.gapRatio, 2)} < 1% ${ok(ps.gapOk)}, 드롭 ${Stats.pct(ps.dropRatio, 2)} < 1% ${ok(ps.dropOk)}, " +
                    "${ps.longGapThresholdMs}ms 초과 갭 ${ps.longGaps} = 0 ${ok(ps.longGapOk)} · " +
                    "화면 off 평균 전력 ${f(ps.offMeanPowerMw, 0)} mW, 최고 thermal ${ps.offMaxThermalStatus?.let { "$it (${thermalName(it)})" } ?: "-"}",
            )

            appendLine("[화면 상태 구간] 시작s-끝s | 상태 | 초(제외) | Face ms 평균/p95 | 드롭% | mW")
            for (g in s.screenSegments) {
                appendLine("${g.startS}-${g.endS} | ${if (g.screenOn) "on" else "off"} | ${g.seconds}(${g.excludedSeconds}) | ${f(g.faceInferMsMean)}/${f(g.faceInferMsP95OfSecondMeans)} | ${Stats.pct(g.dropRatio)} | ${f(g.meanPowerMw, 0)}")
            }

            appendLine("[${s.trendWindowS}초 추이] t(s) | 초 | fps | face% | yaw/pitch/roll° | 드롭% | 갭>${o.gapThresholdMs}ms | Face ms | 사이클 ms | Pose ms | thermal | mA | 화면 on 초")
            for (t in s.trend) {
                appendLine(
                    "${t.startS} | ${t.seconds} | ${f(t.processedFps, 1)} | ${Stats.pct(t.faceDetectRatio, 0)} | ${f(t.yawMean)}/${f(t.pitchMean)}/${f(t.rollMean)} | ${Stats.pct(t.dropRatio)} | ${t.gapsOverThreshold} | " +
                        "${f(t.faceInferMsMean)} | ${f(t.frameTotalMsMean)} | ${f(t.poseInferMsMean)} | ${t.maxThermalStatus ?: "-"} | ${mA(t.meanCurrentUa)} | ${t.screenOnSeconds}",
                )
            }

            for (g in s.segments) {
                appendLine("[${g.label}] ${g.seconds}s  face ${Stats.pct(g.faceDetectRatio)}  yaw ${deg(g.yawMean, g.yawSd)}  pitch ${deg(g.pitchMean, g.pitchSd)}  roll ${deg(g.rollMean, g.rollSd)}")
                appendLine("    얼굴 폭 중앙값 ${f(g.faceWidthMedianPx, 0)}px  j 중앙값 ${f(g.jitterMedian, 4)} p95 ${f(g.jitterP95, 4)}  어깨 vis≥0.6 ${Stats.pct(g.shoulderVisibleRatio)}  머리 landmark ${Stats.pct(g.headLandmarkRatio)}  head_offset 중앙값 ${f(g.headOffsetMedian, 2)}  휘도 ${f(g.lumaMean, 0)}")
            }
            for (n in s.notes) appendLine("※ $n")
        }.trimEnd()
    }

    /** Android `PowerManager` thermal status names, same order as the constants. */
    fun thermalName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
