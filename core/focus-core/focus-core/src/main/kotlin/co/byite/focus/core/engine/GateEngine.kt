package co.byite.focus.core.engine

import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.State

/**
 * One second's verdict from a [GateEngine].
 *
 * @property rawState the state the app knows at this second (spec 9장 raw_state).
 * @property candidateStartMonoMs when a backdatable gate (ABSENT, PRONE, PHONE pickup) is confirmed,
 * the monotonic time its candidate condition began. The finalizer backdates `final_state` to it
 * (spec 9장 소급 표). Ignored for states that do not backdate. May be repeated on later seconds.
 */
data class GateDecision(
    val rawState: State,
    val candidateStartMonoMs: Long? = null,
)

/**
 * Judgement order + G1 + G2 + phone gate (v0-plan 2장 GateEngine). Pure function of the record
 * stream: called once per record in `t_mono_ms` order; the live path and the replay path go
 * through the same instance type.
 */
interface GateEngine {
    /** Stable id written to reports (e.g. "naive-baseline"). */
    val engineId: String

    /** Drop all internal state; the next [judge] starts a new session. */
    fun reset()

    /** Judge one second. Records arrive strictly increasing in `t_mono_ms`. */
    fun judge(record: SecondRecord): GateDecision
}
