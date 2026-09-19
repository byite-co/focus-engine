package co.byite.focus.core.finalizer

import co.byite.focus.core.engine.GateDecision
import co.byite.focus.core.model.BackdateRules
import co.byite.focus.core.model.GapReason
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.State

/** Result of returning to the foreground after a lifecycle gap. */
sealed interface LifecycleOutcome {
    /** The gap is materialised as an interval record (no 30 s limit). */
    data class Gap(val interval: IntervalRecord) : LifecycleOutcome

    /** The session ended: at the background-entry time (gap over the limit) or at the last record (process death). */
    data class SessionEnded(val end: SessionEnd) : LifecycleOutcome
}

/** Result of [StateFinalizer.endSession]. */
data class SessionClose(val end: SessionEnd, val flushed: List<SecondRecord>)

/**
 * raw_state / final_state separation, 30 s confirmation buffer, backdating and lifecycle gaps
 * (spec 9장 구현 계약; v0-plan V0-G; CHANGELOG v0.2.1).
 *
 * Contract:
 * - [push] takes each judged bucket in strictly increasing `t_mono_ms`. The record enters a pending
 *   buffer with `final_state = raw_state` plus the decision's `invalid_reason`, candidate fields and
 *   events. A pending record is finalised once a record `finalize_delay_ms` or more newer arrives;
 *   finalised records are returned and never touched again.
 * - When the decision confirms ABSENT, PRONE or PHONE ([GateDecision.confirmsBackdate]), pending
 *   records in `[max(candidate_start, now - max_backdate_ms), now)` get that `final_state` if
 *   [BackdateRules.canOverwrite] allows it for their `raw_state`/`invalid_reason` and their current
 *   `final_state` is not PHONE or PAUSED. AWAY and PAUSED never backdate.
 * - [flushNow] / [onBackground] finalise all pending records immediately with the information at
 *   hand (spec 9장: 백그라운드에 들어가는 순간 즉시 확정).
 * - [onForeground] materialises the gap as an [IntervalRecord] (APP_SWITCH → PHONE, SCREEN_LOCK →
 *   PAUSED), ends the session at the entry time when the gap is strictly longer than
 *   `lifecycle_gap_session_end_ms`, or, for PROCESS_DEATH, ends it at the last record time with no interval.
 *
 * Deterministic: no clocks, no randomness; output depends only on the call sequence.
 */
class StateFinalizer(private val params: ParameterSet) {
    private val pending = ArrayDeque<SecondRecord>()
    private var lastMonoMs: Long? = null
    private var lastUtcMs: Long? = null
    private var backgroundEntry: Entry? = null
    private var ended: SessionEnd? = null

    private data class Entry(val tMonoMs: Long, val tUtcMs: Long)

    /** Records not yet finalised. */
    val pendingCount: Int get() = pending.size

    /** `t_mono_ms` of the last pushed record, for process-death recovery (spec 9장). */
    val lastRecordMonoMs: Long? get() = lastMonoMs

    /** `t_utc_ms` of the last pushed record. */
    val lastRecordUtcMs: Long? get() = lastUtcMs

    val isInBackground: Boolean get() = backgroundEntry != null

    val sessionEnd: SessionEnd? get() = ended

    /**
     * Accept one judged bucket. Returns the records finalised by this push, oldest first.
     * @throws IllegalArgumentException if `t_mono_ms` does not increase.
     * @throws IllegalStateException if called while in background or after the session ended.
     */
    fun push(record: SecondRecord, decision: GateDecision): List<SecondRecord> {
        check(ended == null) { "session already ended at ${ended?.tMonoMs}" }
        check(backgroundEntry == null) { "record at ${record.tMonoMs} arrived while in background; call onForeground first" }
        val now = record.tMonoMs
        lastMonoMs?.let { require(now > it) { "t_mono_ms must increase: $now after $it" } }
        lastMonoMs = now
        lastUtcMs = record.tUtcMs

        applyBackdate(decision, now)
        pending.addLast(
            record.copy(
                rawState = decision.rawState,
                finalState = decision.rawState,
                invalidReason = decision.invalidReason,
                candidateState = decision.candidateState,
                candidateStartMonoMs = decision.candidateStartMonoMs,
                events = if (decision.events.isEmpty()) record.events else record.events + decision.events,
            ),
        )
        return finalizeOlderThan(now)
    }

    /** Finalise every pending record now. */
    fun flushNow(): List<SecondRecord> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    /**
     * App goes to the background at [tMonoMs]. Flushes the pending buffer (returned) and remembers
     * the entry time. The caller persists the entry time itself (spec 9장: 영속 저장).
     */
    fun onBackground(tMonoMs: Long, tUtcMs: Long): List<SecondRecord> {
        check(ended == null) { "session already ended" }
        check(backgroundEntry == null) { "already in background since ${backgroundEntry?.tMonoMs}" }
        lastMonoMs?.let { require(tMonoMs >= it) { "background entry $tMonoMs before last record $it" } }
        backgroundEntry = Entry(tMonoMs, tUtcMs)
        return flushNow()
    }

    /**
     * App returns to the foreground at [tMonoMs]. [reason] decides the outcome: APP_SWITCH → PHONE
     * interval, SCREEN_LOCK → PAUSED interval (no 30 s limit), PROCESS_DEATH → session closed at the
     * last record time with no interval. A gap strictly longer than the limit closes the session at
     * the entry time.
     */
    fun onForeground(tMonoMs: Long, tUtcMs: Long, reason: GapReason): LifecycleOutcome {
        val entry = checkNotNull(backgroundEntry) { "onForeground without a preceding onBackground" }
        require(tMonoMs >= entry.tMonoMs) { "foreground return $tMonoMs before background entry ${entry.tMonoMs}" }
        backgroundEntry = null
        if (reason == GapReason.PROCESS_DEATH) {
            val end = SessionEnd(lastMonoMs ?: entry.tMonoMs, lastUtcMs ?: entry.tUtcMs, SessionEndReason.PROCESS_DEATH_RECOVERED)
            ended = end
            return LifecycleOutcome.SessionEnded(end)
        }
        val gapMs = tMonoMs - entry.tMonoMs
        if (gapMs > params.lifecycleGapSessionEndMs) {
            val end = SessionEnd(entry.tMonoMs, entry.tUtcMs, SessionEndReason.LIFECYCLE_GAP_TIMEOUT)
            ended = end
            return LifecycleOutcome.SessionEnded(end)
        }
        val state = checkNotNull(reason.state) { "gap reason $reason has no interval state" }
        return LifecycleOutcome.Gap(
            IntervalRecord(
                tStartMonoMs = entry.tMonoMs,
                tEndMonoMs = tMonoMs,
                tStartUtcMs = entry.tUtcMs,
                tEndUtcMs = tUtcMs,
                state = state,
                reason = reason,
            ),
        )
    }

    /**
     * Close the session at [tMonoMs] (user stop, or process-death recovery at the last record time).
     * Flushes whatever is pending.
     */
    fun endSession(tMonoMs: Long, tUtcMs: Long, reason: SessionEndReason): SessionClose {
        check(ended == null) { "session already ended" }
        lastMonoMs?.let { require(tMonoMs >= it) { "session end $tMonoMs before last record $it" } }
        val end = SessionEnd(tMonoMs, tUtcMs, reason)
        ended = end
        backgroundEntry = null
        return SessionClose(end, flushNow())
    }

    private fun applyBackdate(decision: GateDecision, now: Long) {
        if (!decision.confirmsBackdate) return
        val state = decision.rawState
        val from = maxOf(decision.candidateStartMonoMs!!, now - params.maxBackdateMs)
        if (from >= now) return
        for (i in pending.indices) {
            val r = pending[i]
            if (r.tMonoMs !in from until now || r.finalState == state) continue
            if (r.finalState == State.PHONE || r.finalState == State.PAUSED) continue
            if (!BackdateRules.canOverwrite(state, r.rawState, r.invalidReason)) continue
            pending[i] = r.copy(finalState = state)
        }
    }

    private fun finalizeOlderThan(now: Long): List<SecondRecord> {
        val out = ArrayList<SecondRecord>()
        while (pending.isNotEmpty() && now - pending.first().tMonoMs >= params.finalizeDelayMs) {
            out.add(pending.removeFirst())
        }
        return out
    }
}
