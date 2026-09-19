package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Event kinds recorded in `SecondRecord.events` (schema 0.2.1). */
@Serializable
enum class EventType {
    @SerialName("pickup_candidate") PICKUP_CANDIDATE,
    @SerialName("pickup_confirmed") PICKUP_CONFIRMED,
    @SerialName("shake") SHAKE,
    @SerialName("redock_confirmed") REDOCK_CONFIRMED,
    @SerialName("redock_pending_timeout") REDOCK_PENDING_TIMEOUT,
    @SerialName("notify_reposition") NOTIFY_REPOSITION,
    @SerialName("auto_pause_start") AUTO_PAUSE_START,
    @SerialName("auto_resume") AUTO_RESUME,
    @SerialName("recalibration_start") RECALIBRATION_START,
    @SerialName("recalibration_end") RECALIBRATION_END,
    @SerialName("zone_added") ZONE_ADDED,
}

/** How a re-dock was confirmed (spec 3장: 거치 자세 ±10° 복귀 또는 '재거치 완료' 탭). */
@Serializable
enum class RedockBy {
    @SerialName("orientation") ORIENTATION,
    @SerialName("tap") TAP,
}

/**
 * One event with its monotonic time. `by` is set only for REDOCK_CONFIRMED, `zone_id` only for
 * ZONE_ADDED. Scalars only (spec 9장 데이터 경계).
 */
@Serializable
data class Event(
    val type: EventType,
    @SerialName("t_mono_ms") val tMonoMs: Long,
    val by: RedockBy? = null,
    @SerialName("zone_id") val zoneId: Int? = null,
) {
    init {
        require((by != null) == (type == EventType.REDOCK_CONFIRMED)) { "'by' is set exactly for redock_confirmed, got $type" }
        require((zoneId != null) == (type == EventType.ZONE_ADDED)) { "'zone_id' is set exactly for zone_added, got $type" }
    }

    companion object {
        fun redockConfirmed(tMonoMs: Long, by: RedockBy): Event = Event(EventType.REDOCK_CONFIRMED, tMonoMs, by = by)
        fun zoneAdded(tMonoMs: Long, zoneId: Int): Event = Event(EventType.ZONE_ADDED, tMonoMs, zoneId = zoneId)
    }
}
