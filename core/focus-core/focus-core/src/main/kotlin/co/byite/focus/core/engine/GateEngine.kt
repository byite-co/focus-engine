package co.byite.focus.core.engine

import co.byite.focus.core.model.Event
import co.byite.focus.core.model.InvalidReason
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.State

/**
 * One bucket's verdict from a [GateEngine].
 *
 * @property rawState the state the app knows at this second (spec 9장 raw_state).
 * @property invalidReason set exactly when [rawState] is INVALID.
 * @property candidateState the candidate being accumulated (ABSENT, PRONE, PHONE pickup, AWAY grace,
 * PAUSED head-missing counter). When a backdatable state is confirmed, [candidateState] equals
 * [rawState] and [candidateStartMonoMs] is the first bucket of the run; the finalizer backdates
 * `final_state` to it (spec 9장 소급 표, v0.2.1 판정 2·3).
 * @property events gate events raised in this bucket; the finalizer appends them to the record.
 */
data class GateDecision(
    val rawState: State,
    val invalidReason: InvalidReason? = null,
    val candidateState: State? = null,
    val candidateStartMonoMs: Long? = null,
    val events: List<Event> = emptyList(),
) {
    init {
        require((rawState == State.INVALID) == (invalidReason != null)) { "invalidReason is set exactly when rawState == INVALID" }
        require((candidateState == null) == (candidateStartMonoMs == null)) { "candidateState and candidateStartMonoMs go together" }
    }

    /** True when this decision confirms a backdatable state whose run started at [candidateStartMonoMs]. */
    val confirmsBackdate: Boolean
        get() = rawState in State.BACKDATABLE && candidateState == rawState && candidateStartMonoMs != null
}

/**
 * Judgement order + G1 + G2 + phone gate (v0-plan 2장 GateEngine). Pure function of the record
 * stream: called once per record in `t_mono_ms` order; the live path and the replay path go
 * through the same instance type.
 *
 * Contract (v0.2.1 판정):
 * - "N초 연속" = N consecutive buckets. Backdatable states (ABSENT, PRONE, PHONE) are confirmed at
 *   the N-th bucket with the candidate start = the first bucket. Non-backdating states (AWAY,
 *   PAUSED) keep the previous classification for N buckets and switch from bucket N+1 (판정 2).
 * - An environmental INVALID bucket ([InvalidReason.isEnvironmental]) resets the camera candidates
 *   (ABSENT, PRONE, AWAY grace, head-missing counter) but not the IMU pickup candidate (판정 3);
 *   see [co.byite.focus.core.model.BackdateRules].
 * - Face bands come from [FaceBand] (판정 12); the phone gate from [PhoneGateTracker] (판정 6·7).
 */
interface GateEngine {
    /** Stable id written to reports (e.g. "naive-baseline"). */
    val engineId: String

    /** Drop all internal state; the next [judge] starts a new session. */
    fun reset()

    /** Judge one bucket. Records arrive strictly increasing in `t_mono_ms`. */
    fun judge(record: SecondRecord): GateDecision
}
