package co.byite.focus.core.engine

import co.byite.focus.core.Synth
import co.byite.focus.core.model.EventType
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.InvalidReason
import co.byite.focus.core.model.RedockBy
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.2.1 판정 6·7: pickup after three moving buckets, re-dock wait, stationary confirmation, recalibration. */
class PhoneGateTrackerTest {
    private fun tracker() = PhoneGateTracker(Synth.params)

    private fun run(t: PhoneGateTracker, startSec: Long, vararg imu: ImuState): List<PhoneGateVerdict> =
        imu.mapIndexed { i, s -> t.judge(Synth.mono(startSec + i), s) }

    private fun PhoneGateVerdict.types() = events.map { it.type }

    @Test
    fun offDockRestWithoutAnyPickupIsPendingForSixtyBucketsThenPhone() {
        val t = tracker()
        val v = run(t, 0, *Array(62) { ImuState.RESTING_OFF_DOCK })
        for (i in 0 until 60) {
            assertEquals(State.INVALID, v[i].state, "bucket $i")
            assertEquals(InvalidReason.REDOCK_PENDING, v[i].invalidReason, "bucket $i")
            assertTrue(v[i].events.isEmpty(), "bucket $i")
        }
        assertEquals(State.PHONE, v[60].state)
        assertEquals(listOf(EventType.REDOCK_PENDING_TIMEOUT), v[60].types())
        assertEquals(State.PHONE, v[61].state)
        assertTrue(v[61].events.isEmpty())
        assertNull(v[60].candidateState) // no backdate for the re-dock timeout
    }

    @Test
    fun threeMovingBucketsConfirmPickupAtTheThirdWithCandidateStartAtTheFirst() {
        val t = tracker()
        val v = run(t, 10, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING)
        assertEquals(State.INVALID, v[0].state)
        assertEquals(InvalidReason.PHONE_SHAKE, v[0].invalidReason)
        assertEquals(listOf(EventType.PICKUP_CANDIDATE), v[0].types())
        assertEquals(State.INVALID, v[1].state)
        assertEquals(InvalidReason.PHONE_SHAKE, v[1].invalidReason)
        assertEquals(State.PHONE, v[2].state)
        assertEquals(listOf(EventType.PICKUP_CONFIRMED), v[2].types())
        for (i in 0..2) {
            assertEquals(State.PHONE, v[i].candidateState, "bucket $i")
            assertEquals(Synth.mono(10), v[i].candidateStartMonoMs, "bucket $i")
        }
        assertEquals(State.PHONE, v[3].state)
        assertTrue(v[3].events.isEmpty())
        assertTrue(t.hasPickupCandidate)
    }

    @Test
    fun tiltAndVarianceBranchesBothNeedThreeBuckets() {
        val v = run(tracker(), 0, ImuState.LIFTED, ImuState.MOVING, ImuState.LIFTED)
        assertEquals(listOf(State.INVALID, State.INVALID, State.PHONE), v.map { it.state })
        val w = run(tracker(), 0, ImuState.LIFTED, ImuState.LIFTED, ImuState.DOCKED)
        assertEquals(listOf(State.INVALID, State.INVALID, null), w.map { it.state })
    }

    @Test
    fun shorterMotionIsShakeAndNeverPhone() {
        val t = tracker()
        val v = run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.DOCKED, ImuState.DOCKED)
        assertEquals(InvalidReason.PHONE_SHAKE, v[0].invalidReason)
        assertEquals(InvalidReason.PHONE_SHAKE, v[1].invalidReason)
        assertNull(v[2].state)
        assertEquals(listOf(EventType.SHAKE), v[2].types())
        assertNull(v[3].state)
        assertTrue(v[3].events.isEmpty())
        assertTrue(!t.hasPickupCandidate)
    }

    @Test
    fun unknownImuHoldsTheCountWithoutAdvancingOrEndingIt() {
        val v = run(tracker(), 0, ImuState.MOVING, ImuState.MOVING, ImuState.UNKNOWN, ImuState.MOVING)
        assertEquals(listOf(State.INVALID, State.INVALID, State.INVALID, State.PHONE), v.map { it.state })
        assertEquals(InvalidReason.PHONE_SHAKE, v[2].invalidReason)
    }

    @Test
    fun pendingRestartsAtTheNextRestAfterMotion() {
        val t = tracker()
        val first = run(t, 0, *Array(10) { ImuState.RESTING_OFF_DOCK })
        assertTrue(first.all { it.invalidReason == InvalidReason.REDOCK_PENDING })
        val motion = t.judge(Synth.mono(10), ImuState.MOVING)
        assertEquals(InvalidReason.PHONE_SHAKE, motion.invalidReason)
        assertEquals(listOf(EventType.PICKUP_CANDIDATE), motion.types())
        val rest = run(t, 11, *Array(61) { ImuState.RESTING_OFF_DOCK })
        assertEquals(listOf(EventType.SHAKE), rest[0].types())
        for (i in 0 until 60) assertEquals(InvalidReason.REDOCK_PENDING, rest[i].invalidReason, "bucket ${11 + i}")
        assertEquals(State.PHONE, rest[60].state)
        assertEquals(listOf(EventType.REDOCK_PENDING_TIMEOUT), rest[60].types())
    }

    @Test
    fun pickedUpThenRestingOffDockWaitsSixtyBuckets() {
        val t = tracker()
        run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING)
        val v = run(t, 3, *Array(61) { ImuState.RESTING_OFF_DOCK })
        for (i in 0 until 60) assertEquals(InvalidReason.REDOCK_PENDING, v[i].invalidReason, "bucket $i")
        assertEquals(State.PHONE, v[60].state)
    }

    @Test
    fun twoDockedBucketsConfirmRedockThenTwentyBucketsRecalibration() {
        val t = tracker()
        run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING)
        val v = run(t, 3, *Array(23) { ImuState.DOCKED })
        assertEquals(InvalidReason.REDOCK_PENDING, v[0].invalidReason) // waiting for the stationary confirmation
        assertTrue(v[0].events.isEmpty())
        assertEquals(InvalidReason.RECALIBRATION, v[1].invalidReason)
        assertEquals(listOf(EventType.REDOCK_CONFIRMED, EventType.RECALIBRATION_START), v[1].types())
        assertEquals(RedockBy.ORIENTATION, v[1].events[0].by)
        for (i in 1 until 21) assertEquals(InvalidReason.RECALIBRATION, v[i].invalidReason, "bucket ${3 + i}")
        assertNull(v[21].state)
        assertEquals(listOf(EventType.RECALIBRATION_END), v[21].types())
        assertNull(v[22].state)
        assertTrue(v[22].events.isEmpty())
    }

    @Test
    fun tapConfirmsImmediatelyFromPickedUpOrPending() {
        val t = tracker()
        run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING)
        val tap = t.judge(Synth.mono(3), ImuState.RESTING_OFF_DOCK, redockTap = true)
        assertEquals(InvalidReason.RECALIBRATION, tap.invalidReason)
        assertEquals(RedockBy.TAP, tap.events.first { it.type == EventType.REDOCK_CONFIRMED }.by)

        val u = tracker()
        run(u, 0, *Array(5) { ImuState.RESTING_OFF_DOCK })
        val tap2 = u.judge(Synth.mono(5), ImuState.RESTING_OFF_DOCK, redockTap = true)
        assertEquals(RedockBy.TAP, tap2.events.first { it.type == EventType.REDOCK_CONFIRMED }.by)
        assertNull(u.judge(Synth.mono(25), ImuState.DOCKED).state) // 20 buckets later the recalibration is over
    }

    @Test
    fun motionDuringRecalibrationRestartsThePickupRule() {
        val t = tracker()
        run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING, ImuState.DOCKED, ImuState.DOCKED)
        val v = t.judge(Synth.mono(5), ImuState.LIFTED)
        assertEquals(InvalidReason.PHONE_SHAKE, v.invalidReason)
        assertEquals(listOf(EventType.PICKUP_CANDIDATE), v.types())
        assertEquals(Synth.mono(5), v.candidateStartMonoMs)
    }

    @Test
    fun resetReturnsToIdle() {
        val t = tracker()
        run(t, 0, ImuState.MOVING, ImuState.MOVING, ImuState.MOVING)
        t.reset()
        assertNull(t.judge(Synth.mono(3), ImuState.DOCKED).state)
        assertTrue(!t.hasPickupCandidate)
    }
}
