package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Event kinds recorded in `SecondRecord.events` (schema 0.2.1).
 *
 * Input events come from the user or the device layer and are the only events replay feeds back
 * into the engine; output events are produced by the gates and are recomputed on replay, then
 * compared with the logged ones (v0.2.1 판정 5, 2차).
 */
@Serializable
enum class EventType(val isInput: Boolean) {
    // ---- input (user / device)
    @SerialName("user_redock_tap") USER_REDOCK_TAP(true),
    @SerialName("zone_added") ZONE_ADDED(true),

    // ---- output (gates)
    @SerialName("pickup_candidate") PICKUP_CANDIDATE(false),
    @SerialName("pickup_confirmed") PICKUP_CONFIRMED(false),
    @SerialName("shake") SHAKE(false),
    @SerialName("redock_confirmed") REDOCK_CONFIRMED(false),
    @SerialName("redock_pending_timeout") REDOCK_PENDING_TIMEOUT(false),
    @SerialName("notify_reposition") NOTIFY_REPOSITION(false),
    @SerialName("auto_pause_start") AUTO_PAUSE_START(false),
    @SerialName("auto_resume") AUTO_RESUME(false),
    @SerialName("recalibration_start") RECALIBRATION_START(false),
    @SerialName("recalibration_end") RECALIBRATION_END(false),
    /** Recalibration interrupted by motion or an off-dock rest; always pairs a `recalibration_start` with `recalibration_end` or this. */
    @SerialName("recalibration_aborted") RECALIBRATION_ABORTED(false);

    val isOutput: Boolean get() = !isInput

    companion object {
        val INPUT: Set<EventType> = entries.filter { it.isInput }.toSet()
        val OUTPUT: Set<EventType> = entries.filter { it.isOutput }.toSet()
    }
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

    val isInput: Boolean get() = type.isInput
    val isOutput: Boolean get() = type.isOutput

    /** Identity used when replayed output events are compared with logged ones: kind and time only. */
    val key: Pair<EventType, Long> get() = type to tMonoMs

    companion object {
        fun redockConfirmed(tMonoMs: Long, by: RedockBy): Event = Event(EventType.REDOCK_CONFIRMED, tMonoMs, by = by)
        fun zoneAdded(tMonoMs: Long, zoneId: Int): Event = Event(EventType.ZONE_ADDED, tMonoMs, zoneId = zoneId)
        fun userRedockTap(tMonoMs: Long): Event = Event(EventType.USER_REDOCK_TAP, tMonoMs)
    }
}
