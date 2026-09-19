package co.byite.focus.core.model

/**
 * v0.2.1 판정 3: what a backdate may overwrite and which INVALID buckets reset candidates.
 *
 * | backdated state | overwrites raw_state |
 * |---|---|
 * | ABSENT, PRONE | PRESENT, AWAY, INVALID(face_missing_unconfirmed, head_missing, face_unstable) |
 * | PHONE | the above plus INVALID(phone_shake) |
 *
 * Nothing overwrites PHONE, PAUSED or environmental INVALID (low_light, camera_occluded, fps_low,
 * quality_proxy, redock_pending, recalibration). Environmental INVALID buckets reset the camera
 * candidates (ABSENT, PRONE, AWAY grace, head-missing low-motion counter) but not the IMU pickup
 * candidate.
 */
object BackdateRules {
    fun canOverwrite(target: State, raw: State?, reason: InvalidReason?): Boolean = when (target) {
        State.ABSENT, State.PRONE -> raw == State.PRESENT || raw == State.AWAY ||
            (raw == State.INVALID && reason in InvalidReason.CAMERA_UNCONFIRMED)
        State.PHONE -> raw == State.PRESENT || raw == State.AWAY ||
            (raw == State.INVALID && (reason in InvalidReason.CAMERA_UNCONFIRMED || reason == InvalidReason.PHONE_SHAKE))
        else -> false
    }

    /** True when a bucket with this INVALID reason resets ABSENT/PRONE/AWAY-grace/head-missing candidates. */
    fun resetsCameraCandidates(raw: State?, reason: InvalidReason?): Boolean =
        raw == State.INVALID && reason != null && reason.isEnvironmental

    /** The IMU pickup candidate survives environmental INVALID buckets. */
    fun resetsImuCandidate(raw: State?, reason: InvalidReason?): Boolean = false
}
