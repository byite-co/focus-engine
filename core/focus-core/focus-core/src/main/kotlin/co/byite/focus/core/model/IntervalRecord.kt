package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle-gap interval (spec 9장 앱 lifecycle gap). Materialised on return to the foreground for
 * the span in which no per-second record was produced. Not subject to the 30 s backdate limit.
 */
@Serializable
data class IntervalRecord(
    @SerialName("t_start_mono_ms") val tStartMonoMs: Long,
    @SerialName("t_end_mono_ms") val tEndMonoMs: Long,
    /** Display only. */
    @SerialName("t_start_utc_ms") val tStartUtcMs: Long,
    @SerialName("t_end_utc_ms") val tEndUtcMs: Long,
    /** PHONE (app switch) or PAUSED (screen lock). */
    val state: State,
    val reason: GapReason,
) {
    init {
        require(tEndMonoMs >= tStartMonoMs) { "interval end $tEndMonoMs before start $tStartMonoMs" }
        require(state in State.INTERVAL_STATES) { "interval state must be PHONE or PAUSED, got $state" }
    }

    val durationMs: Long get() = tEndMonoMs - tStartMonoMs
}
