package co.byite.focus.core.gt

import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.State

/** How a scripted behaviour maps to the expected per-second state (v0-plan 5장 시간 규칙, 6장 표). */
sealed interface ExpectedRule {
    /** The same state for the whole interval. */
    data class Constant(val state: State) : ExpectedRule

    /** ABSENT when the interval lasts at least `absent_confirm_ms`; otherwise the no-face seconds stay INVALID (spec 3장 G1: 확정되지 않은 얼굴 미검출은 INVALID). */
    data object LeaveSeat : ExpectedRule

    /** PRESENT for the first `away_grace_ms` (spec 3장 G2 4초 유예), then AWAY. */
    data object GracedAway : ExpectedRule

    /** Head landmark missing with low motion: INVALID until `auto_pause_ms`, then PAUSED (spec 3장 G1, T4b). */
    data object HeadHiddenLowMotion : ExpectedRule

    /** Phone picked up and used on a flat surface: INVALID until `redock_pending_invalid_ms`, then PHONE (spec 3장 폰 사용, T5b). */
    data object PhoneOnFlatSurface : ExpectedRule

    /** Not scored per second (T10 process kill). */
    data object Unscored : ExpectedRule

    /** Expected state at [offsetMs] into an interval of [durationMs]; null when the rule does not score. */
    fun stateAt(offsetMs: Long, durationMs: Long, params: ParameterSet): State? = when (this) {
        is Constant -> state
        LeaveSeat -> if (durationMs >= params.absentConfirmMs) State.ABSENT else State.INVALID
        GracedAway -> if (offsetMs < params.awayGraceMs) State.PRESENT else State.AWAY
        HeadHiddenLowMotion -> if (offsetMs < params.autoPauseMs) State.INVALID else State.PAUSED
        PhoneOnFlatSurface -> if (offsetMs < params.redockPendingInvalidMs) State.INVALID else State.PHONE
        Unscored -> null
    }

    /** The state the interval settles into (its last second). */
    fun terminalState(durationMs: Long, params: ParameterSet): State? = stateAt(durationMs - 1, durationMs, params)
}

/**
 * Behaviour vocabulary for the T1–T11 scripts (v0-plan 6장). Names are the `behavior` strings in
 * GT files. Unknown behaviours are accepted only when the interval carries an explicit
 * `expected_state`.
 */
object BehaviorCatalog {
    const val STUDY_IN_ZONE = "study_in_zone"
    const val LEAVE_SEAT = "leave_seat"
    const val LEAVE_SEAT_PLAIN_BACKGROUND = "leave_seat_plain_background"
    const val LEAVE_SEAT_JACKET_ON_CHAIR = "leave_seat_jacket_on_chair"
    const val DEEP_BOW_WRITING = "deep_bow_writing"
    const val PRONE_HEAD_VISIBLE = "prone_head_visible"
    const val PRONE_HEAD_OUT_OF_FRAME = "prone_head_out_of_frame"
    const val PHONE_PICKUP_USE = "phone_pickup_use"
    const val PHONE_REDOCK_RECALIBRATING = "phone_redock_recalibrating"
    const val PHONE_USE_ON_FLAT_SURFACE = "phone_use_on_flat_surface"
    const val DESK_BUMP = "desk_bump"
    const val LOOK_AWAY_UNREGISTERED = "look_away_unregistered"
    const val LOOK_AT_THIRD_ZONE = "look_at_third_zone"
    const val EYES_ONLY_AWAY = "eyes_only_away"
    const val LIGHTS_OFF = "lights_off"
    const val CAMERA_COVERED = "camera_covered"
    const val OTHER_APP_UNLOCKED = "other_app_unlocked"
    const val NOTIFICATION_SCREEN_ON = "notification_screen_on"
    const val TOUCH_SCREEN_OTHER_APP = "touch_screen_other_app"
    const val APP_KILLED = "app_killed"

    val LEAVE_SEAT_BEHAVIORS: Set<String> = setOf(LEAVE_SEAT, LEAVE_SEAT_PLAIN_BACKGROUND, LEAVE_SEAT_JACKET_ON_CHAIR)

    /** behavior → rule, in scenario order. */
    val rules: Map<String, ExpectedRule> = linkedMapOf(
        // T1
        STUDY_IN_ZONE to ExpectedRule.Constant(State.PRESENT),
        // T2, T7c, T8
        LEAVE_SEAT to ExpectedRule.LeaveSeat,
        LEAVE_SEAT_PLAIN_BACKGROUND to ExpectedRule.LeaveSeat,
        LEAVE_SEAT_JACKET_ON_CHAIR to ExpectedRule.LeaveSeat,
        // T3
        DEEP_BOW_WRITING to ExpectedRule.Constant(State.INVALID),
        // T4
        PRONE_HEAD_VISIBLE to ExpectedRule.Constant(State.PRONE),
        PRONE_HEAD_OUT_OF_FRAME to ExpectedRule.HeadHiddenLowMotion,
        // T5
        PHONE_PICKUP_USE to ExpectedRule.Constant(State.PHONE),
        PHONE_REDOCK_RECALIBRATING to ExpectedRule.Constant(State.INVALID),
        PHONE_USE_ON_FLAT_SURFACE to ExpectedRule.PhoneOnFlatSurface,
        DESK_BUMP to ExpectedRule.Constant(State.INVALID),
        // T6
        LOOK_AWAY_UNREGISTERED to ExpectedRule.GracedAway,
        LOOK_AT_THIRD_ZONE to ExpectedRule.Constant(State.PRESENT),
        EYES_ONLY_AWAY to ExpectedRule.Constant(State.PRESENT),
        // T7
        LIGHTS_OFF to ExpectedRule.Constant(State.INVALID),
        CAMERA_COVERED to ExpectedRule.Constant(State.INVALID),
        // T9
        OTHER_APP_UNLOCKED to ExpectedRule.Constant(State.PHONE),
        NOTIFICATION_SCREEN_ON to ExpectedRule.Constant(State.PRESENT),
        TOUCH_SCREEN_OTHER_APP to ExpectedRule.Constant(State.PHONE),
        // T10
        APP_KILLED to ExpectedRule.Unscored,
    )

    fun ruleFor(behavior: String): ExpectedRule? = rules[behavior]
}
