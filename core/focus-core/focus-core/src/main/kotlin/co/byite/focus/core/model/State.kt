package co.byite.focus.core.model

import kotlinx.serialization.Serializable

/**
 * v0 per-second state vocabulary (spec 3장 상태, v0-plan 1장). PRESENT stands in for the
 * spec's DROWSY/FOCUS. PAUSED covers auto-pause (spec 3장 G1) and the screen-lock lifecycle gap
 * (spec 9장). Declaration order follows the directive.
 */
@Serializable
enum class State {
    PHONE, INVALID, ABSENT, PRONE, AWAY, PRESENT, PAUSED;

    companion object {
        /** States whose confirmation is backdated to the candidate start (spec 9장 소급 표). */
        val BACKDATABLE: Set<State> = setOf(ABSENT, PRONE, PHONE)

        /** States a lifecycle-gap [IntervalRecord] may carry (spec 9장 lifecycle gap). */
        val INTERVAL_STATES: Set<State> = setOf(PHONE, PAUSED)
    }
}

/** Session task setting (spec 9장 header `task_mode`). */
@Serializable
enum class TaskMode { VISUAL, LISTENING, SPEAKING }

/**
 * Per-second IMU summary (spec 3장 폰 사용 게이트). Raw observation only; durations
 * (3초 지속, 60초 재거치 대기) are the gate engine's business.
 *
 * - DOCKED: at rest, gravity within ±10° of the calibrated mount posture.
 * - RESTING_OFF_DOCK: at rest, but outside the mount posture (placed on a flat surface).
 * - MOVING: acceleration variance above the pickup ratio, tilt change within 15° (desk bump, shake).
 * - LIFTED: tilt change above 15° or hand-held level variance.
 * - UNKNOWN: no IMU samples in this second.
 */
@Serializable
enum class ImuState { DOCKED, RESTING_OFF_DOCK, MOVING, LIFTED, UNKNOWN }

/**
 * Per-second app/screen summary (spec 3장 폰 사용 게이트, T9).
 *
 * - FOREGROUND: our UI visible, screen on.
 * - SCREEN_OFF: screen off (normal Android measurement posture).
 * - LOCKED: screen on behind the keyguard (e.g. a notification lit the screen). Not phone use.
 * - OTHER_APP: device unlocked and our app not in the foreground. Phone use.
 */
@Serializable
enum class AppState { FOREGROUND, SCREEN_OFF, LOCKED, OTHER_APP }

/** Power state machine position (spec 7장). P0_PRIME/P1_PRIME are the spec's P0′/P1′. */
@Serializable
enum class PowerState { P0, P0_PRIME, P1_PRIME, P1, P2, P3, P4, P5 }

/** Why a lifecycle gap happened; fixes the interval state (spec 9장: PHONE for app switch, PAUSED for screen lock). */
@Serializable
enum class GapReason(val state: State) {
    APP_SWITCH(State.PHONE),
    SCREEN_LOCK(State.PAUSED),
}

/** How a session ended (v0-plan V0-G "세션 종료 사유"). */
@Serializable
enum class SessionEndReason {
    /** Ended by the user. */
    USER,

    /** Lifecycle gap longer than the limit; session closed at the background-entry time (spec 9장). */
    LIFECYCLE_GAP_TIMEOUT,

    /** Process died; closed at the last persisted record time on next launch (spec 9장, T10). */
    PROCESS_DEATH_RECOVERED,

    /** The log carried no end marker; replay closed the session at the last record. */
    UNKNOWN,
}
