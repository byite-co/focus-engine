package co.byite.focus.core.gt

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GT scoring rules and v0 pass thresholds (v0-plan 5장, 7장). Values are the proposed initial ones
 * (초기값); the set in use is echoed in every report as `gt_rules`.
 */
@Serializable
data class GtRules(
    /** Seconds after every cue excluded from scoring (반응 허용 구간). After a PAUSED interval the exclusion also adds `auto_resume_face_ms` (v0.2.1 판정 14). */
    @SerialName("reaction_window_ms") val reactionWindowMs: Long = 3_000,
    @SerialName("absent_precision_min") val absentPrecisionMin: Double = 0.95,
    @SerialName("absent_recall_min") val absentRecallMin: Double = 0.95,
    @SerialName("prone_recall_min") val proneRecallMin: Double = 0.90,
    @SerialName("phone_precision_min") val phonePrecisionMin: Double = 0.95,
    @SerialName("phone_recall_min") val phoneRecallMin: Double = 0.95,
    @SerialName("away_recall_min") val awayRecallMin: Double = 0.90,
    @SerialName("false_away_max") val falseAwayMax: Double = 0.01,
    @SerialName("false_invalid_max") val falseInvalidMax: Double = 0.05,
    @SerialName("flapping_per_10min_max") val flappingPer10MinMax: Double = 1.0,
    @SerialName("latency_absent_p95_max_ms") val latencyAbsentP95MaxMs: Long = 5_000,
    @SerialName("latency_phone_p95_max_ms") val latencyPhoneP95MaxMs: Long = 5_000,
    @SerialName("latency_away_p95_max_ms") val latencyAwayP95MaxMs: Long = 6_000,
    @SerialName("ratio_error_max_pp") val ratioErrorMaxPp: Double = 3.0,
    @SerialName("pause_tolerance_ms") val pauseToleranceMs: Long = 3_000,
    /** 초기값 7000 = 반응 3 s + 얼굴 재검출 3 s + 1 s (v0.2.1 판정 14; v0-plan 7장의 5초는 반응 허용을 빠뜨린 오기). */
    @SerialName("resume_within_ms") val resumeWithinMs: Long = 7_000,
    @SerialName("process_death_max_loss_ms") val processDeathMaxLossMs: Long = 60_000,
) {
    companion object {
        val DEFAULT: GtRules = GtRules()
    }
}

/** Why a second is not scored. */
enum class Exclusion { BEFORE_START, NO_INTERVAL, VOID, UNSCORED_BEHAVIOR, REACTION_WINDOW }

/** Expected state at one relative time. */
data class ExpectedPoint(
    val tRelMs: Long,
    val intervalIndex: Int?,
    val behavior: String?,
    val expected: State?,
    val exclusion: Exclusion?,
) {
    val scored: Boolean get() = exclusion == null && expected != null
}

/** A GT interval with its resolved rule. */
data class ResolvedInterval(
    val index: Int,
    val interval: GtInterval,
    val rule: ExpectedRule,
    /** State the interval settles into; null for unscored behaviours. */
    val terminalState: State?,
    /** Seconds after this interval's cue excluded from scoring: reaction window, plus the auto-resume time when the previous interval ended in PAUSED. */
    val leadExclusionMs: Long,
)

/** A parsed GT with every behaviour resolved to a rule. */
class ResolvedGt(
    val file: GtFile,
    val intervals: List<ResolvedInterval>,
    val warnings: List<String>,
) {
    /** End of the last interval, relative ms. */
    val endMs: Long = intervals.maxOf { it.interval.tEndMs }

    fun intervalAt(tRelMs: Long): ResolvedInterval? {
        var lo = 0
        var hi = intervals.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val iv = intervals[mid].interval
            when {
                tRelMs < iv.tStartMs -> hi = mid - 1
                tRelMs >= iv.tEndMs -> lo = mid + 1
                else -> return intervals[mid]
            }
        }
        return null
    }

    fun inVoid(tRelMs: Long): Boolean = file.void.any { tRelMs >= it.tStartMs && tRelMs < it.tEndMs }

    fun intervalsWithBehavior(names: Set<String>): List<ResolvedInterval> = intervals.filter { it.interval.behavior in names }
}

/**
 * Derives `expected_state` per second from scripted behaviours (v0-plan 5장: 도구가 생성한다).
 * Rules: away behaviours are PRESENT for the first `away_grace_ms` (4 buckets) and AWAY from the
 * 5th; head-hidden low motion is INVALID for `auto_pause_ms` (120 buckets) and PAUSED from the
 * 121st; the `reaction_window_ms` after every cue (interval start) and every void span are
 * excluded; after an interval that ended in PAUSED the exclusion is `reaction_window_ms +
 * auto_resume_face_ms` (6 s, v0.2.1 판정 14).
 */
class ExpectedStateGenerator(
    private val params: ParameterSet,
    private val rules: GtRules = GtRules.DEFAULT,
) {
    fun resolve(gt: GtFile): ResolvedGt {
        GtParser.validate(gt)
        val warnings = ArrayList<String>()
        warnings.addAll(GtParser.lint(gt, params))
        var prevTerminal: State? = null
        val resolved = gt.intervals.mapIndexed { i, iv ->
            val catalogRule = BehaviorCatalog.ruleFor(iv.behavior)
            val rule = when {
                catalogRule != null -> catalogRule
                iv.expectedState != null -> {
                    warnings.add("interval $i: unknown behavior '${iv.behavior}'; using explicit expected_state ${iv.expectedState}")
                    ExpectedRule.Constant(iv.expectedState)
                }
                else -> throw GtFormatException("interval $i: unknown behavior '${iv.behavior}' and no expected_state")
            }
            val terminal = rule.terminalState(iv.durationMs, params)
            if (catalogRule != null && iv.expectedState != null && iv.expectedState != terminal) {
                warnings.add(
                    "interval $i (${iv.behavior}): explicit expected_state ${iv.expectedState} differs from generated ${terminal ?: "unscored"}; generated value is used",
                )
            }
            val lead = rules.reactionWindowMs + if (prevTerminal == State.PAUSED) params.autoResumeFaceMs else 0L
            if (terminal != null) prevTerminal = terminal
            ResolvedInterval(i, iv, rule, terminal, lead)
        }
        return ResolvedGt(gt, resolved, warnings)
    }

    /** Expected state and scoring status at [tRelMs] (relative to the start cue). */
    fun expectedAt(resolved: ResolvedGt, tRelMs: Long): ExpectedPoint {
        if (tRelMs < 0) return ExpectedPoint(tRelMs, null, null, null, Exclusion.BEFORE_START)
        val ri = resolved.intervalAt(tRelMs) ?: return ExpectedPoint(tRelMs, null, null, null, Exclusion.NO_INTERVAL)
        val iv = ri.interval
        val offset = tRelMs - iv.tStartMs
        val state = ri.rule.stateAt(offset, iv.durationMs, params)
        val exclusion = when {
            resolved.inVoid(tRelMs) -> Exclusion.VOID
            state == null -> Exclusion.UNSCORED_BEHAVIOR
            offset < ri.leadExclusionMs -> Exclusion.REACTION_WINDOW
            else -> null
        }
        return ExpectedPoint(tRelMs, ri.index, iv.behavior, state, exclusion)
    }

    /** 1 Hz grid `0, 1000, … < endMs`. */
    fun timeline(resolved: ResolvedGt): List<ExpectedPoint> {
        val out = ArrayList<ExpectedPoint>()
        var t = 0L
        while (t < resolved.endMs) {
            out.add(expectedAt(resolved, t))
            t += FocusSchema.RECORD_PERIOD_MS
        }
        return out
    }
}
