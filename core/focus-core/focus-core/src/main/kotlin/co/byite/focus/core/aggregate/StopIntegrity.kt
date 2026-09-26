package co.byite.focus.core.aggregate

import co.byite.focus.core.model.SessionEnd

/**
 * Stop-integrity verdict of a normal stop (directive E 2·5장, E2 2.2). The stop was clean only when all three hold:
 * no pre-fence capture result reached the scheduler after its CLOSE (`capture_results_after_close = 0`), the Camera2
 * capture-result callbacks were drained before CLOSE (`capture_result_drain_complete`: camera CLOSED and no result
 * for a few frame intervals), and the aggregation-queue barrier came back within its bound (`aggregation_queue_drained`).
 * `stop_integrity_failed = after_close > 0 OR !drain_complete OR !queue_drained`. Each cause is its own field, so the
 * summary names the ones that actually failed; a null cause is unknown (older log, recovered summary) and never counts
 * as failed on its own. Written to `session_end` and read back by the summary.
 */
data class StopIntegrity(
    val captureResultsAfterClose: Long?,
    val captureResultDrainComplete: Boolean?,
    val aggregationQueueDrained: Boolean?,
) {
    val afterCloseFailed: Boolean get() = (captureResultsAfterClose ?: 0L) > 0L
    val drainFailed: Boolean get() = captureResultDrainComplete == false
    val queueFailed: Boolean get() = aggregationQueueDrained == false

    /** True when any known cause failed; null when every cause is unknown. */
    val failed: Boolean?
        get() = if (captureResultsAfterClose == null && captureResultDrainComplete == null && aggregationQueueDrained == null) null
        else afterCloseFailed || drainFailed || queueFailed

    /** The causes that failed, in a fixed order, one summary phrase each (empty when the stop was clean or nothing is known). */
    val reasons: List<String>
        get() = buildList {
            if (afterCloseFailed) add("$REASON_AFTER_CLOSE ${captureResultsAfterClose}건")
            if (drainFailed) add(REASON_DRAIN)
            if (queueFailed) add(REASON_QUEUE)
        }

    companion object {
        val UNKNOWN = StopIntegrity(null, null, null)
        const val REASON_AFTER_CLOSE = "CLOSE 뒤 도착한 fence 전 CaptureResult(capture_results_after_close)"
        const val REASON_DRAIN = "CaptureResult 콜백 drain 미완료(capture_result_drain_complete=false)"
        const val REASON_QUEUE = "aggregation 큐 drain 미완료(aggregation_queue_drained=false)"

        fun of(totals: CounterTotals, captureResultDrainComplete: Boolean?, aggregationQueueDrained: Boolean?): StopIntegrity =
            StopIntegrity(totals.captureResultsAfterClose, captureResultDrainComplete, aggregationQueueDrained)

        fun of(end: SessionEnd?): StopIntegrity =
            if (end == null) UNKNOWN else StopIntegrity(end.captureResultsAfterClose, end.captureResultDrainComplete, end.aggregationQueueDrained)
    }
}
