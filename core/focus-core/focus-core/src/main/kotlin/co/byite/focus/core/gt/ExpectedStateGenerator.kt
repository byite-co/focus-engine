package co.byite.focus.core.gt

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.State

/** GT scoring rules and v0 pass thresholds (v0-plan 5장, 7장). Values are the proposed initial ones. */
data class GtRules(
    /** Seconds after every cue excluded from scoring (반응 허용 구간). */
    val reactionWindowMs: Long = 3_000,
    val absentPrecisionMin: Double = 0.95,
    val absentRecallMin: Double = 0.95,
    val proneRecallMin: Double = 0.90,
    val phonePrecisionMin: Double = 0.95,
    val phoneRecallMin: Double = 0.95,
    val awayRecallMin: Double = 0.90,
    val falseAwayMax: Double = 0.01,
    val falseInvalidMax: Double = 0.05,
    val flappingPer10MinMax: Double = 1.0,
    val latencyAbsentP95MaxMs: Long = 5_000,
    val latencyPhoneP95MaxMs: Long = 5_000,
    val latencyAwayP95MaxMs: Long = 6_000,
    val ratioErrorMaxPp: Double = 3.0,
    val pauseToleranceMs: Long = 3_000,
    val resumeWithinMs: Long = 5_000,
    val processDeathMaxLossMs: Long = 60_000,
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
 * Rules: away behaviours are PRESENT for the first `away_grace_ms`; head-hidden low motion is
 * PAUSED from `auto_pause_ms`; the `reaction_window_ms` after every cue (interval start) and every
 * void span are excluded.
 */
class ExpectedStateGenerator(
    private val params: ParameterSet,
    private val rules: GtRules = GtRules.DEFAULT,
) {
    fun resolve(gt: GtFile): ResolvedGt {
        GtParser.validate(gt)
        val warnings = ArrayList<String>()
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
            ResolvedInterval(i, iv, rule, terminal)
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
            offset < rules.reactionWindowMs -> Exclusion.REACTION_WINDOW
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
