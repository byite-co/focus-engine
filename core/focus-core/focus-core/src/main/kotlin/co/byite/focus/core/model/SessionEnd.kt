package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session close marker (v0-plan V0-G "세션 종료 사유", T10). `session_end` line of a session JSONL (schema 0.2.1). */
@Serializable
data class SessionEnd(
    @SerialName("t_mono_ms") val tMonoMs: Long,
    @SerialName("t_utc_ms") val tUtcMs: Long,
    val reason: SessionEndReason,
)
