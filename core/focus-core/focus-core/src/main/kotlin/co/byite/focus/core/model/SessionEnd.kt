package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Session close marker (v0-plan V0-G "세션 종료 사유", T10). `session_end` line of a session JSONL (schema 0.2.1).
 * At a normal stop `t_mono_ms` is the accept fence (`stopFenceMonoNs`) and `t_utc_ms = t_start_utc + (fence − t_start_mono)`,
 * never the time the summary was written (directive E 5장; PR #7 StopFinalizer contract).
 *
 * Schema 0.2.4 adds the stop diagnostics of directive E 2·5장 (E2 2.2·2.3), null in older logs and in a recovered
 * (process-death) end: capture results stamped before the raw session start, at or after the raw stop fence, after the
 * slot scheduler's CLOSE, and out of order; whether the capture-result callbacks and the aggregation queue were drained;
 * and the verdict `stop_integrity_failed = after_close > 0 OR !capture_result_drain_complete OR !aggregation_queue_drained`
 * ([co.byite.focus.core.aggregate.StopIntegrity]).
 */
@Serializable
data class SessionEnd(
    @SerialName("t_mono_ms") val tMonoMs: Long,
    @SerialName("t_utc_ms") val tUtcMs: Long,
    val reason: SessionEndReason,
    @SerialName("capture_results_before_start") val captureResultsBeforeStart: Long? = null,
    @SerialName("capture_results_after_fence") val captureResultsAfterFence: Long? = null,
    @SerialName("capture_results_after_close") val captureResultsAfterClose: Long? = null,
    /** In-window capture results that trailed a newer evaluated timestamp (diagnostic; in slot mode any makes the session not comparable). */
    @SerialName("capture_results_out_of_order") val captureResultsOutOfOrder: Long? = null,
    /** The Camera2 capture-result callbacks were drained before CLOSE (camera CLOSED and quiet). */
    @SerialName("capture_result_drain_complete") val captureResultDrainComplete: Boolean? = null,
    /** The aggregation-queue barrier came back within its bound before finish. */
    @SerialName("aggregation_queue_drained") val aggregationQueueDrained: Boolean? = null,
    /** `after_close > 0 OR !capture_result_drain_complete OR !aggregation_queue_drained` at a normal stop; null when unknown (older log, recovery). */
    @SerialName("stop_integrity_failed") val stopIntegrityFailed: Boolean? = null,
) {
    init {
        captureResultsBeforeStart?.let { require(it >= 0) { "capture_results_before_start must not be negative" } }
        captureResultsAfterFence?.let { require(it >= 0) { "capture_results_after_fence must not be negative" } }
        captureResultsAfterClose?.let { require(it >= 0) { "capture_results_after_close must not be negative" } }
        captureResultsOutOfOrder?.let { require(it >= 0) { "capture_results_out_of_order must not be negative" } }
    }
}
