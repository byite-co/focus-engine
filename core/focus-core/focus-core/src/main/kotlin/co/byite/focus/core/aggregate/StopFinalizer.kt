package co.byite.focus.core.aggregate

import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason

/** What the stop path hands to the summary: every record of the session, the totals and the end marker stamped at the fence. */
data class FinishOutcome(val records: List<AggregatedSecond>, val totals: CounterTotals, val end: SessionEnd)

/**
 * Stop step 5 as pure logic shared by `CaptureService` and the tests (code review of PR #7, item 1): the
 * session ends at the accept fence, never at the wall clock of whoever runs the step. Buckets that end after
 * the fence are never emitted, however much time passed since the fence, and `session_end` carries the fence
 * (`t_utc = t_start_utc + (fence − t_start_mono)`), so the summary's session length, fps denominators,
 * screen-state segments and power means all stop at the fence.
 */
object StopFinalizer {
    /** The end marker at the fence: `t_utc = t_start_utc + (fence − t_start_mono)`. */
    fun endAt(fenceMonoMs: Long, tStartMonoMs: Long, tStartUtcMs: Long, reason: SessionEndReason): SessionEnd =
        SessionEnd(tMonoMs = fenceMonoMs, tUtcMs = tStartUtcMs + (fenceMonoMs - tStartMonoMs), reason = reason)

    fun finish(
        aggregator: FeatureAggregator,
        fenceMonoMs: Long,
        tStartMonoMs: Long,
        tStartUtcMs: Long,
        device: DeviceSample,
        reason: SessionEndReason,
        /** Records closed earlier in the session (the aggregation thread's list); the ones closed here are appended. */
        earlier: List<AggregatedSecond> = emptyList(),
    ): FinishOutcome {
        require(fenceMonoMs >= tStartMonoMs) { "the fence cannot precede the session start" }
        aggregator.stopInputs(fenceMonoMs)
        val closed = aggregator.finish(fenceMonoMs, device)
        return FinishOutcome(earlier + closed, aggregator.totals, endAt(fenceMonoMs, tStartMonoMs, tStartUtcMs, reason))
    }
}
