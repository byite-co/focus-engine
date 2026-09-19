package co.byite.focus.core.gt

import co.byite.focus.core.model.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session-level GT comparison report (v0-plan 7장 리포트 항목). Serialised to JSON by the CLI. */
@Serializable
data class GtReport(
    @SerialName("session_id") val sessionId: String,
    @SerialName("scenario_id") val scenarioId: String,
    @SerialName("gt_type") val gtType: String,
    @SerialName("participant_id") val participantId: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("engine_id") val engineId: String,
    @SerialName("parameter_set_id") val parameterSetId: String,
    @SerialName("spec_version") val specVersion: String,
    @SerialName("feature_schema_version") val featureSchemaVersion: String,
    /** GT scoring rules and pass thresholds this report was computed with. */
    @SerialName("gt_rules") val gtRules: GtRules,
    val seconds: SecondsSummary,
    /** `confusion[expected][final]` in seconds, over scored seconds. */
    val confusion: Map<State, Map<State, Int>>,
    /** Ratios exclude INVALID and PAUSED from the denominator (v0.2.1 판정 4·9). */
    @SerialName("per_state") val perState: Map<State, StateMetrics>,
    /** Share of INVALID and PAUSED seconds among scored seconds, expected vs measured (reported separately from the ratios). */
    @SerialName("excluded_shares") val excludedShares: Map<State, ShareStat>,
    /** Cue → first `raw_state` transition, per target state. */
    @SerialName("detection_latency") val detectionLatency: Map<State, LatencyStats>,
    val flapping: FlappingStats,
    @SerialName("false_invalid") val falseInvalid: RatioStat,
    @SerialName("false_away") val falseAway: RatioStat,
    /** INVALID seconds ÷ seconds per expected state (T11 자료). */
    @SerialName("invalid_ratio_by_expected") val invalidRatioByExpected: Map<State, RatioStat>,
    @SerialName("by_behavior") val byBehavior: Map<String, BehaviorSummary>,
    val reproducibility: Reproducibility,
    @SerialName("baseline_comparison") val baselineComparison: BaselineComparison?,
    val pass: PassSummary,
    val warnings: List<String>,
)

@Serializable
data class SecondsSummary(
    val records: Int,
    val scored: Int,
    @SerialName("excluded_reaction_window") val excludedReactionWindow: Int,
    @SerialName("excluded_void") val excludedVoid: Int,
    @SerialName("excluded_unscored_behavior") val excludedUnscoredBehavior: Int,
    /** Records before the start cue or after the last GT interval. */
    @SerialName("outside_gt") val outsideGt: Int,
    /** Scored GT grid seconds with no record. */
    @SerialName("missing_records") val missingRecords: Int,
    @SerialName("gt_span_ms") val gtSpanMs: Long,
    @SerialName("interval_records") val intervalRecords: Int,
)

@Serializable
data class ShareStat(
    @SerialName("expected_s") val expectedS: Int,
    @SerialName("measured_s") val measuredS: Int,
    @SerialName("expected_share") val expectedShare: Double?,
    @SerialName("measured_share") val measuredShare: Double?,
    /** (measured − expected) × 100. */
    @SerialName("diff_pp") val diffPp: Double?,
)

@Serializable
data class StateMetrics(
    @SerialName("expected_s") val expectedS: Int,
    @SerialName("measured_s") val measuredS: Int,
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double?,
    val recall: Double?,
    /** expected seconds ÷ expected seconds outside INVALID/PAUSED; null for INVALID and PAUSED themselves. */
    @SerialName("expected_ratio") val expectedRatio: Double?,
    /** measured seconds ÷ measured seconds outside INVALID/PAUSED; null for INVALID and PAUSED themselves. */
    @SerialName("measured_ratio") val measuredRatio: Double?,
    /** (measured − expected) × 100. */
    @SerialName("ratio_error_pp") val ratioErrorPp: Double?,
)

@Serializable
data class LatencySample(
    @SerialName("interval_index") val intervalIndex: Int,
    val behavior: String,
    @SerialName("cue_rel_ms") val cueRelMs: Long,
    /** Null when `raw_state` never reached the target inside the interval. */
    @SerialName("latency_ms") val latencyMs: Long?,
)

@Serializable
data class LatencyStats(
    val n: Int,
    val missed: Int,
    @SerialName("median_ms") val medianMs: Double?,
    @SerialName("p95_ms") val p95Ms: Long?,
    val samples: List<LatencySample>,
)

@Serializable
data class FlapRun(
    val expected: State,
    @SerialName("t_start_rel_ms") val tStartRelMs: Long,
    @SerialName("t_end_rel_ms") val tEndRelMs: Long,
    @SerialName("scored_s") val scoredS: Int,
    val changes: Int,
)

@Serializable
data class FlappingStats(
    val changes: Int,
    @SerialName("scored_s") val scoredS: Int,
    @SerialName("per_10min") val per10Min: Double?,
    val runs: List<FlapRun>,
)

@Serializable
data class RatioStat(val numerator: Int, val denominator: Int, val ratio: Double?)

@Serializable
data class BehaviorSummary(
    @SerialName("scored_s") val scoredS: Int,
    @SerialName("final_states") val finalStates: Map<State, Int>,
)

@Serializable
data class Reproducibility(
    /** Two replays of the same log produced identical outcomes. Null when replay was not run. */
    @SerialName("replay_twice_identical") val replayTwiceIdentical: Boolean?,
    /** Seconds where the log carried a `final_state` to compare against. */
    @SerialName("logged_final_compared_s") val loggedFinalComparedS: Int?,
    @SerialName("logged_final_mismatch_s") val loggedFinalMismatchS: Int?,
    @SerialName("logged_final_match_ratio") val loggedFinalMatchRatio: Double?,
)

@Serializable
data class BaselineMetrics(
    @SerialName("engine_id") val engineId: String,
    @SerialName("false_absent_s") val falseAbsentS: Int,
    @SerialName("false_invalid_s") val falseInvalidS: Int,
    @SerialName("missed_absent_s") val missedAbsentS: Int,
)

@Serializable
data class BaselineComparison(val primary: BaselineMetrics, val baseline: BaselineMetrics)

@Serializable
data class PassItem(
    val id: String,
    val description: String,
    val applicable: Boolean,
    /** Null = applicable but not evaluable from this session. */
    val passed: Boolean?,
    val measured: String?,
    val threshold: String,
    val note: String? = null,
)

@Serializable
data class PassSummary(
    /** true = every applicable item passed; false = at least one failed; null = nothing failed but something was not evaluable. */
    val overall: Boolean?,
    val items: List<PassItem>,
)
