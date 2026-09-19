package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * v0 per-second state vocabulary: seven states including PAUSED (v0.2.1 판정 4; v0-plan 1장의
 * "여섯 개" 는 오기). PRESENT stands in for the spec's DROWSY/FOCUS. Declaration order follows the
 * directive; priority is [PRIORITY].
 */
@Serializable
enum class State {
    PHONE, INVALID, ABSENT, PRONE, AWAY, PRESENT, PAUSED;

    /** 0 = highest priority. */
    val priorityRank: Int get() = PRIORITY.indexOf(this)

    fun outranks(other: State): Boolean = priorityRank < other.priorityRank

    companion object {
        /** v0.2.1 판정 4: PHONE > PAUSED > INVALID(환경) > ABSENT > PRONE > AWAY > PRESENT. */
        val PRIORITY: List<State> = listOf(PHONE, PAUSED, INVALID, ABSENT, PRONE, AWAY, PRESENT)

        /** States whose confirmation is backdated to the candidate start (spec 9장 소급 표). */
        val BACKDATABLE: Set<State> = setOf(ABSENT, PRONE, PHONE)

        /** States a lifecycle-gap [IntervalRecord] may carry (spec 9장 lifecycle gap). */
        val INTERVAL_STATES: Set<State> = setOf(PHONE, PAUSED)

        /** Excluded from state-ratio denominators; their seconds are reported separately (v0.2.1 판정 4·9). */
        val RATIO_EXCLUDED: Set<State> = setOf(INVALID, PAUSED)
    }
}

/** Why a second is INVALID (schema 0.2.1). Null whenever `raw_state != INVALID`. */
@Serializable
enum class InvalidReason {
    @SerialName("low_light") LOW_LIGHT,
    @SerialName("camera_occluded") CAMERA_OCCLUDED,
    @SerialName("fps_low") FPS_LOW,
    @SerialName("quality_proxy") QUALITY_PROXY,
    @SerialName("phone_shake") PHONE_SHAKE,
    @SerialName("redock_pending") REDOCK_PENDING,
    @SerialName("recalibration") RECALIBRATION,
    @SerialName("face_missing_unconfirmed") FACE_MISSING_UNCONFIRMED,
    @SerialName("head_missing") HEAD_MISSING,
    @SerialName("face_unstable") FACE_UNSTABLE;

    /** Environmental invalidity (spec 3장 판정 순서 2번). Never overwritten by backdating; resets camera candidates (v0.2.1 판정 3). */
    val isEnvironmental: Boolean get() = this in ENVIRONMENTAL

    companion object {
        val ENVIRONMENTAL: Set<InvalidReason> = setOf(LOW_LIGHT, CAMERA_OCCLUDED, FPS_LOW, QUALITY_PROXY, REDOCK_PENDING, RECALIBRATION)

        /** Camera-side "not yet confirmed" invalidity that ABSENT/PRONE/PHONE backdating may overwrite. */
        val CAMERA_UNCONFIRMED: Set<InvalidReason> = setOf(FACE_MISSING_UNCONFIRMED, HEAD_MISSING, FACE_UNSTABLE)
    }
}

/** Session task setting (spec 9장 header `task_mode`). */
@Serializable
enum class TaskMode { VISUAL, LISTENING, SPEAKING }

/**
 * Per-second IMU summary (spec 3장 폰 사용 게이트). Raw observation only; bucket counting
 * (3 buckets for pickup, 60 s re-dock wait, 2 s stationary confirm) is [co.byite.focus.core.engine.PhoneGateTracker]'s.
 *
 * - DOCKED: at rest, gravity within ±10° of the calibrated mount posture.
 * - RESTING_OFF_DOCK: at rest, but outside the mount posture (placed on a flat surface).
 * - MOVING: acceleration variance above the pickup ratio, tilt change within 15° (desk bump, shake).
 * - LIFTED: tilt change above 15° or hand-held level variance.
 * - UNKNOWN: no IMU samples in this bucket.
 */
@Serializable
enum class ImuState { DOCKED, RESTING_OFF_DOCK, MOVING, LIFTED, UNKNOWN }

/** Screen state (schema 0.2.1). ON_LOCKED covers a notification lighting the keyguard (T9b). */
@Serializable
enum class ScreenState { ON_UNLOCKED, ON_LOCKED, OFF }

/** Our app's UI visibility (schema 0.2.1). PHONE by app use = `screen_state == ON_UNLOCKED && app_state == BACKGROUND`. */
@Serializable
enum class AppState { FOREGROUND, BACKGROUND }

/** Head pose versus the registered work zones (schema 0.2.1). `zone_id` is set only for IN_ZONE. */
@Serializable
enum class ZoneStatus {
    @SerialName("in_zone") IN_ZONE,
    @SerialName("outside") OUTSIDE,
    @SerialName("no_head_pose") NO_HEAD_POSE,
}

/** Power state machine position (spec 7장). P0_PRIME/P1_PRIME are the spec's P0′/P1′. */
@Serializable
enum class PowerState { P0, P0_PRIME, P1_PRIME, P1, P2, P3, P4, P5 }

/**
 * Why a lifecycle gap happened (spec 9장). [state] fixes the interval state; PROCESS_DEATH makes no
 * interval and closes the session at the last record time (v0.2.1 판정 10). Android keeps measuring
 * with the screen off or another app in front, so it never emits APP_SWITCH/SCREEN_LOCK gaps.
 */
@Serializable
enum class GapReason(val state: State?) {
    APP_SWITCH(State.PHONE),
    SCREEN_LOCK(State.PAUSED),
    PROCESS_DEATH(null),
}

/** How a session ended (v0-plan V0-G "세션 종료 사유"). */
@Serializable
enum class SessionEndReason {
    /** Ended by the user. */
    USER,

    /** Lifecycle gap strictly longer than the limit; session closed at the background-entry time (spec 9장). */
    LIFECYCLE_GAP_TIMEOUT,

    /** Process died; closed at the last persisted record time on next launch (spec 9장, T10). */
    PROCESS_DEATH_RECOVERED,

    /** The log carried no end marker; replay closed the session at the last record. */
    UNKNOWN,
}
