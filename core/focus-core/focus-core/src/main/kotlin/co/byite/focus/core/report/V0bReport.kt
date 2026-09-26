package co.byite.focus.core.report

import co.byite.focus.core.aggregate.CounterTotals
import co.byite.focus.core.aggregate.FeatureAggregator
import co.byite.focus.core.aggregate.GapThresholds
import co.byite.focus.core.aggregate.StopIntegrity
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.FaceSchedule
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
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
    /** Hinge-sensor line: "힌지 센서 감지: 펼침 100% …", "힌지 센서 없음(접힘 상태 미상)" or "-" for older logs. */
    val foldLine: String,
    val preset: String,
    /** Rounded ms thresholds of the header (kept for older logs; null for fps unset); [thresholds] carries the exact values. */
    val gapThresholdMs: Int?,
    val longGapThresholdMs: Int?,
    /** Exact gap thresholds and whether they apply (only for a fixed-cadence request). */
    val thresholds: GapThresholdLine,
    /** Requested / supported camera fps ranges and the measured cadence (directive E 1장). */
    val cadence: CadenceStats,
    /** Processing-slot counters summed over the records (schema 0.2.4). */
    val slots: SlotCounts,
    /** Capture-result diagnostics of the stop (live totals or the `session_end` line); nulls when unknown. */
    val diagnostics: StopDiagnostics,
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
    /** Lowest `battery_pct` seen; the summary warns below [V0bReport.BATTERY_WARN_PCT]. */
    val batteryMinPct: Int? = null,
) {
    val missingSeconds: Long get() = missingRecords + zeroFrameRecords
    /** Mean current in mA (|µA| ÷ 1000, sign as reported). */
    val meanCurrentMa: Double? get() = meanCurrentUa?.let { it / 1000.0 }
    val batteryLow: Boolean get() = batteryMinPct?.let { it < V0bReport.BATTERY_WARN_PCT } ?: false
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

/** Processing-slot counters (schema 0.2.4) summed over a set of seconds: three independent counters, the ratio derived from `missed` only. */
data class SlotCounts(val expected: Long, val filled: Long, val missed: Long) {
    /** `missed ÷ expected` (never `expected − filled`); null without expected slots. */
    val missRatio: Double? get() = if (expected > 0) missed.toDouble() / expected else null

    companion object {
        val ZERO = SlotCounts(0, 0, 0)

        fun of(raws: List<V0bRawRecord>): SlotCounts = SlotCounts(
            expected = raws.sumOf { it.processingSlotsExpected.toLong() },
            filled = raws.sumOf { it.processingSlotsFilled.toLong() },
            missed = raws.sumOf { it.processingSlotsMissed.toLong() },
        )

        fun of(t: CounterTotals): SlotCounts = SlotCounts(t.processingSlotsExpected, t.processingSlotsFilled, t.processingSlotsMissed)
    }
}

/**
 * Capture-result cadence over a set of seconds: mean fps = Σ `frames_requested` ÷ seconds; the interval median is the
 * median of the per-second medians, the p95 the nearest-rank p95 of the per-second p95 values, the max the max of the
 * per-second max (per-second records carry no arrays, so this is the same convention as the stage timings).
 */
data class CadenceStats(
    /** "[24,24]", "[7,15]", "fps unset(가변)" or "unset" (older log). */
    val requestLabel: String,
    /** True fixed, false variable, null when nothing was requested / older log. Only `true` is judged. */
    val requestFixed: Boolean?,
    /** True for a 0.2.4 session that requested no AE range (neither [24,24] nor [30,30] offered): not comparable (E2 1장). */
    val requestUnset: Boolean = false,
    /** True for the 3장 slot schedule (E, E15): an out-of-order capture result then makes the session not comparable (E2 2.3). */
    val slotMode: Boolean = false,
    /** Requested cadence (the range's upper bound) when fixed. */
    val requestedFps: Int?,
    val rangesSupported: String?,
    /** Σ frames_requested ÷ seconds. */
    val measuredFps: Double?,
    val intervalMedianMs: Double?,
    val intervalP95Ms: Double?,
    val intervalMaxMs: Double?,
    /** Σ frames_analyzer_received ÷ seconds. */
    val receivedFps: Double?,
    /** Σ frames_processed ÷ seconds (Face processing fps). */
    val processedFps: Double?,
    /** Expected Face rate from the header's `face_process_period_ns`; null in older logs. */
    val expectedFaceRateHz: Double?,
) {
    /** Relative difference of the measured cadence from a fixed request; null when not applicable. */
    val deviation: Double? get() = if (requestFixed == true && requestedFps != null && requestedFps > 0 && measuredFps != null) (measuredFps - requestedFps) / requestedFps else null

    /** "cadence 불일치": a fixed request whose measured cadence differs by more than 3 % (directive E 1장). Never for a variable range. */
    val mismatch: Boolean? get() = deviation?.let { abs(it) > V0bReport.CADENCE_TOLERANCE }
}

/**
 * Gap thresholds (ms) as the aggregator counted them, and whether the pass mark may use them. [applicable] is true only
 * for a fixed AE request (`camera_fps_request_fixed == true`, E2 1장): a variable range (Hvar) and an unset request are
 * shown as n/a and never judged. For fps unset the values are the warm-up diagnostic (median capture-result interval of
 * the first 60 s × 1.5 / 4.5, [learnedFromWarmup]); null when that warm-up did not complete.
 */
data class GapThresholdLine(
    val gapMs: Double?,
    val longGapMs: Double?,
    /** Expected processing interval (ms) the formula was applied to; null in older logs or before the warm-up taught it. */
    val expectedIntervalMs: Double?,
    /** True only for a fixed-cadence request: the pass mark uses the thresholds. */
    val applicable: Boolean,
    /** True when the header carries the exact ns values (0.2.4); false when only the rounded ms fields exist. */
    val exact: Boolean,
    /** fps unset: the values come from the warm-up capture intervals, not from the header. */
    val learnedFromWarmup: Boolean = false,
) {
    private fun ms(x: Double?): String = if (x == null) "-" else if (exact || learnedFromWarmup) "${Stats.fmt(x, 1)}ms" else "${x.toLong()}ms"
    val gapLabel: String get() = if (!applicable) "n/a" else ms(gapMs)
    val longGapLabel: String get() = if (!applicable) "n/a" else ms(longGapMs)
    /** Label of the threshold the counters were computed against, whether or not it is judged (diagnostic value for Hvar / fps unset; "-" while unknown). */
    val gapCountedLabel: String get() = ms(gapMs)
    val longGapCountedLabel: String get() = ms(longGapMs)
}

/**
 * Capture-result diagnostics of the stop (directive E 2장, E2 2.2·2.3): the `session_end` line first, the live stop
 * information where the line has none; nulls when the log predates them or the stop was not normal. The integrity
 * verdict keeps its three causes apart ([integrity]); [integrityReasons] names the ones that failed.
 */
data class StopDiagnostics(
    val captureResultsBeforeStart: Long?,
    val captureResultsAfterFence: Long?,
    val captureResultsAfterClose: Long?,
    val captureResultsOutOfOrder: Long?,
    val captureResultDrainComplete: Boolean?,
    val aggregationQueueDrained: Boolean?,
    val stopIntegrityFailed: Boolean?,
) {
    val integrity: StopIntegrity get() = StopIntegrity(captureResultsAfterClose, captureResultDrainComplete, aggregationQueueDrained)

    /** Each failed cause; a recorded verdict without recorded causes gives one generic reason. */
    val integrityReasons: List<String>
        get() = integrity.reasons.ifEmpty { if (stopIntegrityFailed == true) listOf(V0bReport.STOP_INTEGRITY_CAUSE_UNKNOWN) else emptyList() }

    companion object {
        val UNKNOWN = StopDiagnostics(null, null, null, null, null, null, null)

        fun of(end: SessionEnd?, stop: StopSummary = StopSummary.RECOVERED): StopDiagnostics {
            val t = stop.totals
            val afterClose = end?.captureResultsAfterClose ?: t?.captureResultsAfterClose
            val drain = end?.captureResultDrainComplete ?: stop.captureResultDrainComplete
            val queue = end?.aggregationQueueDrained ?: stop.aggregationQueueDrained
            return StopDiagnostics(
                captureResultsBeforeStart = end?.captureResultsBeforeStart ?: t?.captureResultsBeforeStart,
                captureResultsAfterFence = end?.captureResultsAfterFence ?: t?.captureResultsAfterFence,
                captureResultsAfterClose = afterClose,
                captureResultsOutOfOrder = end?.captureResultsOutOfOrder ?: t?.captureResultsOutOfOrder,
                captureResultDrainComplete = drain,
                aggregationQueueDrained = queue,
                stopIntegrityFailed = end?.stopIntegrityFailed ?: StopIntegrity(afterClose, drain, queue).failed,
            )
        }
    }
}

/** The three states of the summary's first line (E2 3장). */
enum class ComparisonState {
    /** Every condition holds: the session may enter a pair comparison (H12 ↔ E, H15 ↔ E15, A ↔ A …). */
    COMPARABLE,
    /** At least one condition fails; [Comparability.reasons] lists every failing one. */
    NOT_COMPARABLE,
    /** Hvar: a variable-cadence diagnostic session by design — not an error, no pair verdict. */
    NOT_APPLICABLE,
}

/**
 * Whether a session may enter a pair comparison (E2 3장):
 * `comparable = counter_consistency_ok AND !stop_integrity_failed AND (cadence_ok OR cadence n/a) AND !(slot mode AND
 * out_of_order > 0) AND fixed_ae_request_available`. Hvar is [ComparisonState.NOT_APPLICABLE] whatever else holds; any
 * failing condition is still listed in [reasons] so a broken Hvar session is not silently clean.
 */
data class Comparability(val state: ComparisonState, val reasons: List<String>) {
    val comparable: Boolean get() = state == ComparisonState.COMPARABLE

    /** The summary's first line. */
    val line: String
        get() = when (state) {
            ComparisonState.COMPARABLE -> V0bReport.COMPARABLE_LINE
            ComparisonState.NOT_COMPARABLE -> V0bReport.NOT_COMPARABLE_PREFIX + reasons.joinToString("; ")
            ComparisonState.NOT_APPLICABLE -> V0bReport.NOT_APPLICABLE_LINE + if (reasons.isEmpty()) "" else " · 이상: " + reasons.joinToString("; ")
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
    /** Processing slots of these seconds (schema 0.2.4). */
    val slots: SlotCounts = SlotCounts.ZERO,
    /** Σ frames_requested ÷ seconds (capture-result cadence). */
    val requestedFps: Double? = null,
    /** Σ frames_analyzer_received ÷ seconds. */
    val receivedFps: Double? = null,
    /** Capture-result interval statistics of these seconds (median of medians, p95 of p95, max of max; ms). */
    val captureIntervalMedianMs: Double? = null,
    val captureIntervalP95Ms: Double? = null,
    val captureIntervalMaxMs: Double? = null,
    /** Gaps over the fixed 80 ms (v0-plan V0-A), whatever the preset threshold. */
    val gapsOver80Ms: Long = 0L,
) {
    val meanCurrentMa: Double? get() = meanCurrentUa?.let { it / 1000.0 }
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

/**
 * Preset pass mark on the screen-off row (정정 1 3번, 정정 2 4번; directive E 2장). [pass] is null when the off row is
 * empty or when the request is a variable cadence (Hvar): then nothing is judged and only the distributions are
 * reported. The "드롭" item is `slot_miss_ratio < 1 %` on fixed-cadence presets; the raw drop ratio stays a diagnostic
 * beside it. A log without slot counters (schema ≤ 0.2.3) falls back to the raw drop ratio.
 */
data class V0bPass(
    val preset: String,
    val offSeconds: Int,
    /** False unless the request was a fixed AE range: a variable range (Hvar) or no request (fps unset / older log) gets no verdict. */
    val judged: Boolean,
    /** Why nothing is judged ("가변 cadence [7,15]", "fps unset(가변)", …); empty when [judged]. */
    val notJudgedReason: String = "",
    /** Expected Face rate × 23.5 / 24 (A 23.5, E·H12 11.75, E15·H15 14.69); `23.5 ÷ divisor` for older logs. */
    val fpsMin: Double,
    val processedFps: Double?,
    val gapRatio: Double?,
    /** `missed ÷ expected` on the off row (the counted `missed`). */
    val slotMissRatio: Double?,
    val slotsExpected: Long,
    /** `frames_dropped ÷ (requested − skipped)` on the off row: diagnostic beside the slot criterion, the criterion itself without slot counters. */
    val rawDropRatio: Double?,
    /** Gaps over the long threshold on the off row; must be 0 (원래 지시문 D 1번). */
    val longGaps: Long,
    val longGapThresholdLabel: String,
    val offMeanCurrentMa: Double?,
    val offMeanPowerMw: Double?,
    val offMaxThermalStatus: Int?,
) {
    /** The drop criterion actually applied: slot_miss_ratio when the log has slot counters, else the raw drop ratio. */
    val dropRatioJudged: Double? get() = if (slotsExpected > 0) slotMissRatio else rawDropRatio
    val dropCriterion: String get() = if (slotsExpected > 0) "slot_miss_ratio" else "raw 드롭"
    val fpsOk: Boolean? get() = if (!judged) null else processedFps?.let { it >= fpsMin }
    val gapOk: Boolean? get() = if (!judged) null else gapRatio?.let { it < GAP_RATIO_MAX } ?: if (offSeconds > 0) true else null
    val dropOk: Boolean? get() = if (!judged) null else dropRatioJudged?.let { it < DROP_RATIO_MAX }
    val longGapOk: Boolean? get() = if (!judged || offSeconds == 0) null else longGaps == 0L
    val pass: Boolean? get() = if (!judged || offSeconds == 0) null else (fpsOk == true && gapOk == true && dropOk == true && longGapOk == true)

    companion object {
        const val FPS_MIN_EVERY_FRAME: Double = 23.5
        /** fps pass line as a fraction of the expected rate: 23.5 / 24. */
        const val FPS_MIN_FRACTION: Double = FPS_MIN_EVERY_FRAME / 24.0
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
    /** The two drain flags of [StopIntegrity] from the live stop; the `session_end` line carries them too. */
    val captureResultDrainComplete: Boolean? = null,
    val aggregationQueueDrained: Boolean? = null,
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
    /** The first line of the summary (E2 3장). */
    val comparability: Comparability,
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
    /** 정정 2: comparison statistics exclude the first 60 s of the session ([FocusSchema.WARMUP_MS]). */
    const val WARMUP_MS: Long = FocusSchema.WARMUP_MS
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
    /** Directive E 1장: a fixed request whose measured cadence differs by more than this is "cadence 불일치". */
    const val CADENCE_TOLERANCE: Double = 0.03
    const val CADENCE_MISMATCH_PREFIX = "cadence 불일치: "
    const val STOP_INTEGRITY_FAILED_PREFIX = "정상 종료 무결성 실패(stop_integrity_failed): "
    const val STOP_INTEGRITY_CAUSE_UNKNOWN = "원인 미기록"
    const val OUT_OF_ORDER_PREFIX = "슬롯 모드 순서 역전 CaptureResult: "
    const val FPS_UNSET_PREFIX = "fps unset(가변): "
    // E2 3장: the three states of the first line
    const val COMPARABLE_LINE = "비교 가능"
    const val NOT_COMPARABLE_PREFIX = "비교 불가: "
    const val NOT_APPLICABLE_LINE = "짝 비교 판정 비적용: Hvar 가변 cadence"
    const val REASON_COUNTER_MISMATCH = "계수 불일치"
    const val REASON_CADENCE = "cadence 불일치"
    const val REASON_OUT_OF_ORDER = "슬롯 모드 순서 역전 CaptureResult"
    const val REASON_FPS_UNSET = "fps unset(가변): 고정 AE range 요청 없음"
    const val REASON_FPS_NOT_RECORDED = "카메라 fps 요청 기록 없음(schema < 0.2.4)"
    const val BATTERY_WARN_PCT: Int = 20
    const val BATTERY_WARNING_PREFIX = "경고: 배터리 20% 미만"

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
        var batteryMin: Int? = null
        for (r in raws) r.batteryPct?.let { if (batteryStart == null) batteryStart = it; batteryEnd = it; batteryMin = minOf(batteryMin ?: it, it) }
        val off = rowStats(ROW_OFF, rows.filter { !it.warmup && !it.transition && it.screenOn == false })
        val on = rowStats(ROW_ON, rows.filter { !it.warmup && !it.transition && it.screenOn == true })
        val warmup = rowStats(ROW_WARMUP, rows.filter { it.warmup })
        val transition = rowStats(ROW_TRANSITION, rows.filter { !it.warmup && it.transition })
        // fps unset: the diagnostic interval is the warm-up's capture-interval median, known only once the warm-up completed (the aggregator learns it at the same point)
        val thresholds = thresholdLine(h, warmupIntervalMedianMs = if (lengthMs >= WARMUP_MS) warmup.captureIntervalMedianMs else null)
        val cadence = CadenceStats(
            requestLabel = h.cameraFpsRequestLabel,
            requestFixed = h.cameraFpsRequestFixed,
            requestUnset = h.cameraFpsUnset,
            slotMode = h.faceSchedule == FaceSchedule.SLOT,
            requestedFps = if (h.cameraFpsRequestFixed == true) h.cameraFpsRequestUpper else null,
            rangesSupported = h.cameraFpsRangesSupported,
            measuredFps = all.requestedFps,
            intervalMedianMs = all.captureIntervalMedianMs,
            intervalP95Ms = all.captureIntervalP95Ms,
            intervalMaxMs = all.captureIntervalMaxMs,
            receivedFps = all.receivedFps,
            processedFps = all.processedFps,
            expectedFaceRateHz = h.expectedFaceRateHz,
        )
        val diagnostics = StopDiagnostics.of(end, stop)
        val outOfOrder = diagnostics.captureResultsOutOfOrder ?: 0L
        val reasons = ArrayList<String>()
        if (!stop.checked) reasons.add(COUNTER_CHECK_SKIPPED) else if (stop.mismatches.isNotEmpty()) reasons.add("$REASON_COUNTER_MISMATCH ${stop.mismatches.size}건")
        if (diagnostics.stopIntegrityFailed == true) for (r in diagnostics.integrityReasons) reasons.add("$r(stop_integrity_failed)")
        if (cadence.mismatch == true) reasons.add("$REASON_CADENCE(요청 ${cadence.requestLabel}, 실측 ${Stats.fmt(cadence.measuredFps, 2)}fps)")
        if (cadence.slotMode && outOfOrder > 0) reasons.add("$REASON_OUT_OF_ORDER ${outOfOrder}건")
        if (h.cameraFpsRequestFixed == null) reasons.add(if (h.cameraFpsUnset) REASON_FPS_UNSET else REASON_FPS_NOT_RECORDED)
        val comparability = Comparability(
            state = when {
                h.cameraFpsRequestFixed == false -> ComparisonState.NOT_APPLICABLE
                reasons.isEmpty() -> ComparisonState.COMPARABLE
                else -> ComparisonState.NOT_COMPARABLE
            },
            reasons = reasons,
        )

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
            thresholds = thresholds,
            cadence = cadence,
            slots = all.slots,
            diagnostics = diagnostics,
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
            batteryMinPct = batteryMin,
        )

        val pass = V0bPass(
            preset = h.capturePreset ?: "(없음)",
            offSeconds = off.seconds,
            judged = thresholds.applicable,
            notJudgedReason = when {
                thresholds.applicable -> ""
                h.cameraFpsRequestFixed == false -> "가변 cadence ${h.cameraFpsRequestLabel}"
                h.cameraFpsUnset -> SessionHeader.FPS_UNSET_LABEL
                else -> REASON_FPS_NOT_RECORDED
            },
            fpsMin = h.expectedFaceRateHz?.let { it * V0bPass.FPS_MIN_FRACTION } ?: (V0bPass.FPS_MIN_EVERY_FRAME / h.frameProcessDivisor),
            processedFps = off.processedFps,
            gapRatio = off.gapRatio,
            slotMissRatio = off.slots.missRatio,
            slotsExpected = off.slots.expected,
            rawDropRatio = off.frames.dropRatio,
            longGaps = off.gapsOverLongThreshold,
            longGapThresholdLabel = thresholds.longGapLabel,
            offMeanCurrentMa = off.meanCurrentMa,
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
            comparability = comparability,
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
        val intervalMedians = ArrayList<Double>(); val intervalP95s = ArrayList<Double>(); var intervalMax: Double? = null
        for (row in rows) {
            val s = row.s
            val r = row.r ?: continue
            r.captureIntervalMsMedian?.let { intervalMedians.add(it) }
            r.captureIntervalMsP95?.let { intervalP95s.add(it) }
            r.captureIntervalMsMax?.let { intervalMax = maxOf(intervalMax ?: it, it) }
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
            slots = SlotCounts.of(raws),
            requestedFps = if (secs.isNotEmpty()) frames.requested.toDouble() / secs.size else null,
            receivedFps = if (secs.isNotEmpty()) frames.analyzerReceived.toDouble() / secs.size else null,
            captureIntervalMedianMs = Stats.medianOf(intervalMedians),
            captureIntervalP95Ms = Stats.percentileNearestRankOf(intervalP95s, 0.95),
            captureIntervalMaxMs = intervalMax,
            gapsOver80Ms = secs.sumOf { it.gapsOver80Ms.toLong() },
        )
    }

    /**
     * Exact thresholds from the ns header fields (0.2.4), else the rounded ms fields; applicable only to a fixed AE request
     * (E2 1장: null is not judged). A header without an expected interval (fps unset, every-frame preset) gets the warm-up
     * diagnostic from [warmupIntervalMedianMs] (the warm-up row's capture-interval median once the warm-up completed).
     */
    fun thresholdLine(h: SessionHeader, warmupIntervalMedianMs: Double? = null): GapThresholdLine {
        val gapNs = h.frameGapThresholdNs
        val longNs = h.frameLongGapThresholdNs
        val exact = gapNs != null && longNs != null
        if (h.cameraFpsUnset && h.faceProcessPeriodNs == null) {
            return GapThresholdLine(
                gapMs = warmupIntervalMedianMs?.let { it * GapThresholds.GAP_FACTOR },
                longGapMs = warmupIntervalMedianMs?.let { it * GapThresholds.LONG_GAP_FACTOR },
                expectedIntervalMs = warmupIntervalMedianMs,
                applicable = false,
                exact = false,
                learnedFromWarmup = true,
            )
        }
        return GapThresholdLine(
            gapMs = if (gapNs != null) gapNs.toDouble() / FeatureAggregator.NS_PER_MS else h.frameGapThresholdMs?.toDouble(),
            longGapMs = if (longNs != null) longNs.toDouble() / FeatureAggregator.NS_PER_MS else h.frameLongGapThresholdMs?.toDouble(),
            expectedIntervalMs = h.faceProcessPeriodNs?.let { it.toDouble() / FeatureAggregator.NS_PER_MS },
            applicable = h.cameraFpsRequestFixed == true,
            exact = exact,
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

    /**
     * Fold state over the session from the per-second hinge angle (원래 지시문 D 4번). A device without a hinge
     * sensor is not called "not foldable": the sensor is simply absent and the fold state unknown.
     */
    fun foldLine(h: SessionHeader, raws: List<V0bRawRecord>): String {
        if (h.hingeSensor == false) return "힌지 센서 없음(접힘 상태 미상)"
        val angles = raws.mapNotNull { it.hingeAngleDeg }
        if (angles.isEmpty()) return if (h.hingeSensor == true) "힌지 센서 감지: 접힘 상태 미상 (값 없음)" else "-"
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
        return "힌지 센서 감지: " + parts.joinToString(", ") + " (hinge 평균 ${Stats.fmt(Stats.meanOf(angles), 0)}°)"
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
        return "${h.cameraResolution}$ar @ ${if (h.cameraFpsUnset) SessionHeader.FPS_UNSET_LABEL else "${h.nominalFps}fps"}"
    }

    fun presetLine(h: SessionHeader): String {
        val parts = ArrayList<String>()
        parts.add("Face ${h.faceDelegate ?: "?"}")
        parts.add("blendshape ${when (h.faceBlendshapes) { true -> "on"; false -> "off"; null -> "?" }}")
        val rate = h.expectedFaceRateHz
        parts.add(
            when {
                h.faceSchedule == FaceSchedule.SLOT && rate != null -> "3장 슬롯 Face ${Stats.fmt(rate, 0)}Hz(주기 ${Stats.fmt(1000.0 / rate, 1)}ms)"
                h.faceSchedule == FaceSchedule.EVERY_FRAME && rate != null -> "매 프레임(기대 ${Stats.fmt(rate, 0)}Hz)"
                h.frameProcessDivisor == 1 -> "매 프레임"
                else -> "${h.frameProcessDivisor}프레임마다 1회"
            },
        )
        if (h.cameraFpsRequestLower != null) parts.add("카메라 ${h.cameraFpsRequestLabel}${if (h.cameraFpsRequestFixed == false) " 가변" else ""}")
        else if (h.cameraFpsUnset) parts.add("카메라 ${SessionHeader.FPS_UNSET_LABEL}")
        val th = thresholdLine(h)
        parts.add(if (th.applicable) "갭 임계 ${th.gapLabel}" else "갭 임계 n/a")
        h.perfHintTargetMs?.let { parts.add("perf hint ${it}ms") }
        return "${h.capturePreset ?: "(없음)"} (${parts.joinToString(", ")})"
    }

    fun render(s: V0bSummary): String {
        val o = s.overall
        fun f(x: Double?, d: Int = 1): String = Stats.fmt(x, d)
        fun deg(m: Double?, sd: Double?): String = "${f(m)}° ± ${f(sd)}°"
        fun ok(b: Boolean?): String = when (b) { true -> "OK"; false -> "미달"; null -> "-" }
        fun mA(ua: Double?): String = if (ua == null) "-" else f(ua / 1000.0, 0)
        val cad = o.cadence
        val th = o.thresholds
        val dg = o.diagnostics
        fun n(x: Long?): String = x?.toString() ?: "-"
        fun yn(b: Boolean?): String = when (b) { true -> "예"; false -> "아니오"; null -> "-" }
        return buildString {
            // E2 3장: the first line is one of three states; the detail lines below name each condition
            appendLine(s.comparability.line)
            if (!s.stop.checked) {
                appendLine(COUNTER_CHECK_SKIPPED)
            } else if (s.stop.mismatches.isEmpty()) {
                appendLine(COUNTER_OK)
            } else {
                for (m in s.stop.mismatches) appendLine(COUNTER_MISMATCH_PREFIX + m)
            }
            if (cad.mismatch == true) {
                appendLine("${CADENCE_MISMATCH_PREFIX}요청 ${cad.requestLabel} 고정, CaptureResult 실측 ${f(cad.measuredFps, 2)}fps (${Stats.pct(cad.deviation, 1)} 차이, 허용 ±${Stats.pct(CADENCE_TOLERANCE, 0)})")
            }
            if (dg.stopIntegrityFailed == true) {
                appendLine(STOP_INTEGRITY_FAILED_PREFIX + dg.integrityReasons.joinToString("; "))
            }
            if (cad.slotMode && (dg.captureResultsOutOfOrder ?: 0L) > 0L) {
                appendLine("${OUT_OF_ORDER_PREFIX}${n(dg.captureResultsOutOfOrder)}건 — 슬롯 규칙이 이미 지나간 CaptureResult(손실은 갭으로만 드러난다)")
            }
            if (cad.requestUnset) {
                appendLine(
                    FPS_UNSET_PREFIX + "고정 AE range([24,24]/[30,30]) 없음, 기대 간격을 가정하지 않음; 슬롯·갭 합격 판정 없음. " +
                        if (th.learnedFromWarmup) {
                            th.expectedIntervalMs?.let { "진단용 기대 간격 = 워밍업 60초 CaptureResult 간격 중앙값 ${f(it, 1)}ms (갭 임계 진단값 ${th.gapCountedLabel} / ${th.longGapCountedLabel})" }
                                ?: "워밍업 60초 미완료: 진단용 기대 간격 없음(gaps_over_threshold 는 세지 않았다)"
                        } else {
                            "기대 간격 = 슬롯 주기 ${f(th.expectedIntervalMs, 1)}ms (진단값)"
                        },
                )
            }
            if (o.batteryLow) appendLine("${BATTERY_WARNING_PREFIX} (최저 ${o.batteryMinPct}%; 실측 절차는 50% 이상 충전에서 시작한다)")
            appendLine("프리셋 ${o.preset}  focus-engine V0-A/B 요약  세션 ${o.sessionId}")
            appendLine("기기: ${o.device}  엔진 ${o.algorithmVersion}  카메라 ${if (o.cameraIdLine.isNotEmpty()) o.cameraIdLine + " " else ""}${o.camera}(CameraX 실제 선택)  ${o.foldLine}")
            appendLine(
                "카메라 cadence: 요청 AE range ${cad.requestLabel}${when (cad.requestFixed) { true -> " (고정)"; false -> " (가변)"; null -> "" }}, 지원 ${cad.rangesSupported ?: "-"}; " +
                    "CaptureResult 실측 ${f(cad.measuredFps, 2)}fps (간격 중앙값 ${f(cad.intervalMedianMs, 1)}ms, p95 ${f(cad.intervalP95Ms, 1)}ms, 최대 ${f(cad.intervalMaxMs, 1)}ms), " +
                    "분석기 수신 ${f(cad.receivedFps, 2)}fps, Face 처리 ${f(cad.processedFps, 2)}fps${cad.expectedFaceRateHz?.let { " (기대 ${f(it, 2)}Hz)" } ?: ""}" +
                    when (cad.mismatch) { true -> " · cadence 불일치"; false -> " · cadence 일치(±3%)"; null -> "" },
            )
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
            val sl = o.slots
            appendLine(
                "처리 슬롯(전체): expected ${sl.expected}, filled ${sl.filled}, missed ${sl.missed}, slot_miss_ratio ${Stats.pct(sl.missRatio, 2)}" +
                    (s.stop.totals?.let { t -> " (세션 총계 fence 까지: ${t.processingSlotsExpected}/${t.processingSlotsFilled}/${t.processingSlotsMissed})" } ?: "") +
                    "; 진단 계수: capture_results_before_start ${n(dg.captureResultsBeforeStart)}, after_fence ${n(dg.captureResultsAfterFence)}, after_close ${n(dg.captureResultsAfterClose)}, out_of_order ${n(dg.captureResultsOutOfOrder)}; " +
                    "capture_result_drain_complete ${yn(dg.captureResultDrainComplete)}, aggregation_queue_drained ${yn(dg.aggregationQueueDrained)}; " +
                    "stop_integrity_failed: ${yn(dg.stopIntegrityFailed)}",
            )
            appendLine(
                "갭 임계: ${th.gapLabel} / 긴 갭 ${th.longGapLabel}" +
                    (th.expectedIntervalMs?.let { " (기대 처리 간격 ${f(it, 1)}ms × 1.5 / 4.5${if (th.learnedFromWarmup) ", 워밍업 실측" else ""})" } ?: "") +
                    when {
                        th.applicable -> ""
                        cad.requestFixed == false -> " — 가변 cadence: 합격 판정에 쓰지 않음; 기록된 gaps_over_threshold 는 상한 기준 ${th.gapCountedLabel}/${th.longGapCountedLabel} 진단값"
                        cad.requestUnset -> " — ${SessionHeader.FPS_UNSET_LABEL}: 합격 판정에 쓰지 않음; 기록된 gaps_over_threshold 는 ${th.gapCountedLabel}/${th.longGapCountedLabel} 진단값"
                        else -> " — 고정 AE range 요청이 기록되지 않은 로그: 합격 판정에 쓰지 않음"
                    },
            )
            appendLine("갭 > ${th.gapCountedLabel}: ${o.gapsOverThreshold} (80ms 초과 ${o.gapsOver80Ms}), 갭 > ${th.longGapCountedLabel}: ${o.gapsOverLongThreshold}, 최대 갭 ${o.maxFrameGapMs?.let { "$it ms" } ?: "-"}")
            appendLine("누락된 초: ${o.missingSeconds} (레코드 없는 초 ${o.missingRecords} + 프레임 0인 초 ${o.zeroFrameRecords})")
            appendLine("Face 추론 ms: 평균 ${f(o.faceInferMsMean)}, p95 ${f(o.faceInferMsP95OfSecondMeans)} (초당 평균 기준), 최대 ${f(o.faceInferMsMax)}")
            val p = o.pose
            appendLine(
                "Pose: 요청 ${p.requested}, 대기 중 교체 ${p.superseded}, 완료 ${p.completed} (반영 ${p.applied}, 늦어 폐기 ${p.lateDropped}), 오류 ${p.errors}, " +
                    "종료 시 취소 ${if (s.stop.checked) s.stop.poseCancelledAtStop.toString() else "-"}; 추론 ms 평균 ${f(o.poseInferMsMean)}, 최대 ${f(o.poseInferMsMax)}",
            )
            appendLine("최고 thermal status: ${o.maxThermalStatus?.let { "$it (${thermalName(it)})" } ?: "-"}")
            appendLine("배터리: ${o.batteryStartPct ?: "?"}% → ${o.batteryEndPct ?: "?"}%, 평균 전류 ${f(o.meanCurrentMa, 0)} mA (${f(o.meanCurrentUa, 0)} µA 원값), 추정 평균 전력 ${f(o.meanPowerMw)} mW")
            appendLine("화면 off 행 ${o.screenOffSeconds}, idle 행 ${o.idleSeconds}, timebase 줄 ${o.timebaseLines}")

            fun st(x: StageStat, d: Int = 1): String = "${f(x.mean, d)}/${f(x.p95OfSecondMeans, d)}/${f(x.max, d)}"
            appendLine("[비교 통계] 워밍업 ${WARMUP_MS / 1000}s 와 화면 상태 전환 뒤 ${TRANSITION_MS / 1000}s 는 on/off 행에서 제외; ms 는 평균/p95(초당 평균 기준)/최대")
            appendLine("행 | 초 | fps | slot miss%(missed/expected) | 드롭%(백프레셔/미처리/Face후/늦음) | 갭>${th.gapCountedLabel} %(수) | 갭>${th.longGapCountedLabel} | 최대 갭 | Face ms | 사이클 ms | mA | mW | thermal | 카메라 fps(간격 중앙값/p95/최대 ms)")
            for (r in s.rows) {
                val rf = r.frames
                appendLine(
                    "${r.name} | ${r.seconds} | ${f(r.processedFps, 2)} | ${Stats.pct(r.slots.missRatio, 2)} (${r.slots.missed}/${r.slots.expected}) | ${Stats.pct(rf.dropRatio)} (${rf.backpressureDrops}/${rf.unprocessedUnexpected}/${rf.postFaceFailed}/${rf.sampleLateDropped}) | " +
                        "${Stats.pct(r.gapRatio, 2)} (${r.gapsOverThreshold}) | ${r.gapsOverLongThreshold} | ${r.maxFrameGapMs?.let { "$it ms" } ?: "-"} | ${st(r.faceInfer)} | ${st(r.frameTotal)} | " +
                        "${mA(r.meanCurrentUa)} | ${f(r.meanPowerMw, 0)} | ${r.maxThermalStatus ?: "-"} | ${f(r.requestedFps, 2)} (${f(r.captureIntervalMedianMs, 1)}/${f(r.captureIntervalP95Ms, 1)}/${f(r.captureIntervalMaxMs, 1)})",
                )
                appendLine(
                    "    단계 ms: 변환·전처리 ${st(r.wrap, 2)}  face_post ${st(r.facePost, 2)}  scene ${st(r.scene, 2)}  큐 적재 ${st(r.enqueue, 3)}  Pose 복사 ${st(r.poseFrameCopy, 2)}  " +
                        "Pose 추론 ${st(r.poseInfer)} 대기 ${f(r.poseWaitMsMean)}",
                )
                val causes = r.topGapCauses
                appendLine("    갭>${th.gapCountedLabel} 원인: " + if (causes.isEmpty()) "없음" else causes.joinToString(", ") { (k, v) -> "$k $v" })
            }
            val ps = s.pass
            val offPower = "화면 off 평균 전류 ${f(ps.offMeanCurrentMa, 0)} mA, 전력 ${f(ps.offMeanPowerMw, 0)} mW, 최고 thermal ${ps.offMaxThermalStatus?.let { "$it (${thermalName(it)})" } ?: "-"}"
            if (!ps.judged) {
                val off = s.offRow
                appendLine(
                    "합격 판정 안 함(프리셋 ${ps.preset}, ${ps.notJudgedReason}): 화면 off 행 처리 fps ${f(ps.processedFps, 2)}, slot_miss_ratio ${Stats.pct(ps.slotMissRatio, 2)} (${off.slots.missed}/${off.slots.expected}, 진단값), " +
                        "raw 드롭 ${Stats.pct(ps.rawDropRatio, 2)}, CaptureResult ${f(off.requestedFps, 2)}fps 간격 중앙값/p95/최대 ${f(off.captureIntervalMedianMs, 1)}/${f(off.captureIntervalP95Ms, 1)}/${f(off.captureIntervalMaxMs, 1)}ms, " +
                        "처리 프레임 최대 갭 ${off.maxFrameGapMs?.let { "$it ms" } ?: "-"}, 80ms 초과 ${off.gapsOver80Ms}, ${th.gapCountedLabel} 초과 ${off.gapsOverThreshold}, ${th.longGapCountedLabel} 초과 ${off.gapsOverLongThreshold} (진단값) · $offPower",
                )
            } else {
                val verdict = when (ps.pass) { true -> "합격"; false -> "불합격"; null -> "판정 불가(화면 off 행 없음)" }
                appendLine(
                    "합격(프리셋 ${ps.preset}, 화면 off 행 기준): $verdict — fps ${f(ps.processedFps, 2)} ≥ ${f(ps.fpsMin, 2)} ${ok(ps.fpsOk)}, " +
                        "갭 초과 ${Stats.pct(ps.gapRatio, 2)} < 1% ${ok(ps.gapOk)}, 드롭(${ps.dropCriterion}) ${Stats.pct(ps.dropRatioJudged, 2)} < 1% ${ok(ps.dropOk)}" +
                        (if (ps.slotsExpected > 0) " (raw 드롭 ${Stats.pct(ps.rawDropRatio, 2)} 진단)" else "") + ", " +
                        "긴 갭(${ps.longGapThresholdLabel} 초과) ${ps.longGaps} = 0 ${ok(ps.longGapOk)} · $offPower",
                )
            }

            appendLine("[화면 상태 구간] 시작s-끝s | 상태 | 초(제외) | Face ms 평균/p95 | 드롭% | mW")
            for (g in s.screenSegments) {
                appendLine("${g.startS}-${g.endS} | ${if (g.screenOn) "on" else "off"} | ${g.seconds}(${g.excludedSeconds}) | ${f(g.faceInferMsMean)}/${f(g.faceInferMsP95OfSecondMeans)} | ${Stats.pct(g.dropRatio)} | ${f(g.meanPowerMw, 0)}")
            }

            appendLine("[${s.trendWindowS}초 추이] t(s) | 초 | fps | face% | yaw/pitch/roll° | 드롭% | 갭>${th.gapCountedLabel} | Face ms | 사이클 ms | Pose ms | thermal | mA | 화면 on 초")
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
