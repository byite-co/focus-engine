package co.byite.focus.core.replay

import co.byite.focus.core.engine.GateEngine
import co.byite.focus.core.finalizer.LifecycleOutcome
import co.byite.focus.core.finalizer.StateFinalizer
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.SessionHeader

/** Output of one replay: finalised records (raw + final set), materialised intervals, session end. */
data class ReplayResult(
    val header: SessionHeader,
    val engineId: String,
    val parameterSetId: String,
    val records: List<SecondRecord>,
    val intervals: List<IntervalRecord>,
    val sessionEnd: SessionEnd?,
    /** Records the log carried after the session had already ended (lifecycle-gap timeout). */
    val droppedAfterSessionEnd: Int,
    val warnings: List<String>,
) {
    /** Equality of the state outcome only (records, intervals, end), for reproducibility checks. */
    fun sameOutcomeAs(other: ReplayResult): Boolean =
        records == other.records && intervals == other.intervals && sessionEnd == other.sessionEnd
}

/**
 * Re-runs a [GateEngine] and a [StateFinalizer] over a recorded session (v0-plan V0-G 재생 러너).
 * The logged `raw_state` / `final_state` / `invalid_reason` / candidate fields and output events are
 * ignored as input and recomputed; input events (`user_redock_tap`, `zone_added`) are fed to the
 * engine (v0.2.1 판정 5). Lifecycle-gap intervals in the log drive
 * [StateFinalizer.onBackground] / [StateFinalizer.onForeground] so the flush and the 10-minute rule
 * are reproduced too. Calibration and timebase lines are carried through untouched.
 */
class ReplayRunner(
    private val engine: GateEngine,
    private val params: ParameterSet,
) {

    fun run(log: SessionLog): ReplayResult {
        val warnings = ArrayList<String>()
        if (log.header.parameterSetId != params.parameterSetId) {
            warnings.add(
                "parameter_set_id mismatch: log header '${log.header.parameterSetId}' vs replay '${params.parameterSetId}'",
            )
        }
        engine.reset()
        val finalizer = StateFinalizer(params)
        val records = log.records.sortedBy { it.tMonoMs }
        val intervals = log.intervals.sortedBy { it.tStartMonoMs }

        val outRecords = ArrayList<SecondRecord>(records.size)
        val outIntervals = ArrayList<IntervalRecord>()
        var end: SessionEnd? = null
        var dropped = 0
        var ii = 0

        fun applyGap(iv: IntervalRecord) {
            outRecords.addAll(finalizer.onBackground(iv.tStartMonoMs, iv.tStartUtcMs))
            when (val outcome = finalizer.onForeground(iv.tEndMonoMs, iv.tEndUtcMs, iv.reason)) {
                is LifecycleOutcome.Gap -> outIntervals.add(outcome.interval)
                is LifecycleOutcome.SessionEnded -> end = outcome.end
            }
        }

        for (r in records) {
            while (end == null && ii < intervals.size && intervals[ii].tStartMonoMs < r.tMonoMs) {
                val iv = intervals[ii++]
                if (iv.tEndMonoMs > r.tMonoMs) {
                    throw IllegalArgumentException(
                        "record at ${r.tMonoMs} lies inside lifecycle gap [${iv.tStartMonoMs}, ${iv.tEndMonoMs})",
                    )
                }
                applyGap(iv)
            }
            if (end != null) {
                dropped++
                continue
            }
            val input = r.copy(events = r.inputEvents)
            outRecords.addAll(finalizer.push(input, engine.judge(input)))
        }
        while (end == null && ii < intervals.size) applyGap(intervals[ii++])

        if (end == null) {
            val logged = log.sessionEnd
            val endAt = logged?.tMonoMs ?: finalizer.lastRecordMonoMs
            val endUtc = logged?.tUtcMs ?: finalizer.lastRecordUtcMs
            if (endAt != null && endUtc != null) {
                val close = finalizer.endSession(endAt, endUtc, logged?.reason ?: SessionEndReason.UNKNOWN)
                outRecords.addAll(close.flushed)
                end = close.end
            }
        }
        if (dropped > 0) warnings.add("$dropped record(s) after session end were dropped")

        return ReplayResult(
            header = log.header,
            engineId = engine.engineId,
            parameterSetId = params.parameterSetId,
            records = outRecords,
            intervals = outIntervals,
            sessionEnd = end,
            droppedAfterSessionEnd = dropped,
            warnings = warnings,
        )
    }
}
