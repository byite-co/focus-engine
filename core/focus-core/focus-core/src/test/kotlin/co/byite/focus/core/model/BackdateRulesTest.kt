package co.byite.focus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v0.2.1 판정 3 overwrite table and candidate reset. */
class BackdateRulesTest {
    private val cameraUnconfirmed = listOf(InvalidReason.FACE_MISSING_UNCONFIRMED, InvalidReason.HEAD_MISSING, InvalidReason.FACE_UNSTABLE)
    private val environmental = listOf(InvalidReason.LOW_LIGHT, InvalidReason.CAMERA_OCCLUDED, InvalidReason.FPS_LOW, InvalidReason.QUALITY_PROXY, InvalidReason.REDOCK_PENDING, InvalidReason.RECALIBRATION)

    @Test
    fun absentAndProneOverwritePresentAwayAndCameraUnconfirmedOnly() {
        for (target in listOf(State.ABSENT, State.PRONE)) {
            assertTrue(BackdateRules.canOverwrite(target, State.PRESENT, null), "$target over PRESENT")
            assertTrue(BackdateRules.canOverwrite(target, State.AWAY, null), "$target over AWAY")
            for (r in cameraUnconfirmed) assertTrue(BackdateRules.canOverwrite(target, State.INVALID, r), "$target over INVALID($r)")
            assertFalse(BackdateRules.canOverwrite(target, State.INVALID, InvalidReason.PHONE_SHAKE), "$target over phone_shake")
            for (r in environmental) assertFalse(BackdateRules.canOverwrite(target, State.INVALID, r), "$target over INVALID($r)")
            assertFalse(BackdateRules.canOverwrite(target, State.PHONE, null))
            assertFalse(BackdateRules.canOverwrite(target, State.PAUSED, null))
            assertFalse(BackdateRules.canOverwrite(target, State.ABSENT, null))
            assertFalse(BackdateRules.canOverwrite(target, State.PRONE, null))
        }
    }

    @Test
    fun phoneAdditionallyOverwritesPhoneShake() {
        assertTrue(BackdateRules.canOverwrite(State.PHONE, State.INVALID, InvalidReason.PHONE_SHAKE))
        assertTrue(BackdateRules.canOverwrite(State.PHONE, State.PRESENT, null))
        assertTrue(BackdateRules.canOverwrite(State.PHONE, State.AWAY, null))
        for (r in cameraUnconfirmed) assertTrue(BackdateRules.canOverwrite(State.PHONE, State.INVALID, r))
        for (r in environmental) assertFalse(BackdateRules.canOverwrite(State.PHONE, State.INVALID, r), "PHONE over INVALID($r)")
        assertFalse(BackdateRules.canOverwrite(State.PHONE, State.PHONE, null))
        assertFalse(BackdateRules.canOverwrite(State.PHONE, State.PAUSED, null))
    }

    @Test
    fun nonBackdatableTargetsNeverOverwrite() {
        for (target in listOf(State.AWAY, State.PAUSED, State.INVALID, State.PRESENT)) {
            for (raw in State.entries) assertFalse(BackdateRules.canOverwrite(target, raw, null), "$target over $raw")
        }
    }

    @Test
    fun onlyEnvironmentalInvalidResetsCameraCandidates() {
        for (r in environmental) assertTrue(BackdateRules.resetsCameraCandidates(State.INVALID, r), "$r")
        for (r in cameraUnconfirmed) assertFalse(BackdateRules.resetsCameraCandidates(State.INVALID, r), "$r")
        assertFalse(BackdateRules.resetsCameraCandidates(State.INVALID, InvalidReason.PHONE_SHAKE))
        assertFalse(BackdateRules.resetsCameraCandidates(State.PRESENT, null))
        for (r in InvalidReason.entries) assertFalse(BackdateRules.resetsImuCandidate(State.INVALID, r))
        assertEquals(InvalidReason.ENVIRONMENTAL, InvalidReason.entries.filter { it.isEnvironmental }.toSet())
    }
}
