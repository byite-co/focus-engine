package co.byite.focus.core.model

import co.byite.focus.core.Synth
import co.byite.focus.core.log.FocusJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun intervalRecordRejectsNonGapStatesAndMismatchedReasons() {
        assertFailsWith<IllegalArgumentException> { IntervalRecord(0, 10, 0, 10, State.ABSENT, GapReason.APP_SWITCH) }
        assertFailsWith<IllegalArgumentException> { IntervalRecord(10, 0, 10, 0, State.PHONE, GapReason.APP_SWITCH) }
        assertFailsWith<IllegalArgumentException> { IntervalRecord(0, 10, 0, 10, State.PAUSED, GapReason.APP_SWITCH) }
        assertFailsWith<IllegalArgumentException> { IntervalRecord(0, 10, 0, 10, State.PHONE, GapReason.PROCESS_DEATH) }
    }

    @Test
    fun gapReasonFixesTheIntervalState() {
        assertEquals(State.PHONE, GapReason.APP_SWITCH.state)
        assertEquals(State.PAUSED, GapReason.SCREEN_LOCK.state)
        assertNull(GapReason.PROCESS_DEATH.state)
    }

    @Test
    fun parameterSetRoundTripsAndKeepsId() {
        val json = FocusJson.compact.encodeToString(ParameterSet.serializer(), ParameterSet.DEFAULT)
        assertTrue(json.contains("\"parameter_set_id\":\"ps-v0.2.1-default\""))
        assertEquals(ParameterSet.DEFAULT, FocusJson.compact.decodeFromString(ParameterSet.serializer(), json))
        val partial = FocusJson.compact.decodeFromString(ParameterSet.serializer(), """{"parameter_set_id":"x","absent_confirm_ms":4000}""")
        assertEquals(4000, partial.absentConfirmMs)
        assertEquals(30_000, partial.finalizeDelayMs)
        assertEquals(0.2, ParameterSet.DEFAULT.faceMissingMaxRatio)
        assertEquals(0.5, ParameterSet.DEFAULT.facePresentMinRatio)
        assertEquals(2_000, ParameterSet.DEFAULT.redockStationaryConfirmMs)
        assertEquals(20_000, ParameterSet.DEFAULT.recalibrationMs)
    }

    @Test
    fun parameterSetValidatesAndCountsBuckets() {
        assertFailsWith<IllegalArgumentException> { ParameterSet("bad", finalizeDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { ParameterSet("bad", facePresentMinRatio = 1.5) }
        assertFailsWith<IllegalArgumentException> { ParameterSet("bad", faceMissingMaxRatio = 0.6, facePresentMinRatio = 0.5) }
        val p = ParameterSet.DEFAULT
        assertEquals(3, p.absentConfirmBuckets)
        assertEquals(30, p.proneConfirmBuckets)
        assertEquals(3, p.pickupConfirmBuckets)
        assertEquals(4, p.awayGraceBuckets)
        assertEquals(120, p.autoPauseBuckets)
        assertEquals(60, p.redockPendingBuckets)
        assertEquals(2, p.redockStationaryConfirmBuckets)
        assertEquals(20, p.recalibrationBuckets)
        assertEquals(3, FocusSchema.buckets(2_500))
    }

    @Test
    fun statePriorityAndSets() {
        assertEquals(listOf(State.PHONE, State.PAUSED, State.INVALID, State.ABSENT, State.PRONE, State.AWAY, State.PRESENT), State.PRIORITY)
        assertTrue(State.PHONE.outranks(State.PAUSED))
        assertTrue(State.PAUSED.outranks(State.INVALID))
        assertTrue(State.INVALID.outranks(State.ABSENT))
        assertTrue(State.ABSENT.outranks(State.PRONE))
        assertTrue(State.PRONE.outranks(State.AWAY))
        assertTrue(State.AWAY.outranks(State.PRESENT))
        assertEquals(7, State.entries.size)
        assertEquals(setOf(State.ABSENT, State.PRONE, State.PHONE), State.BACKDATABLE)
        assertEquals(setOf(State.PHONE, State.PAUSED), State.INTERVAL_STATES)
        assertEquals(setOf(State.INVALID, State.PAUSED), State.RATIO_EXCLUDED)
    }

    @Test
    fun invalidReasonGroups() {
        assertEquals(
            setOf(InvalidReason.LOW_LIGHT, InvalidReason.CAMERA_OCCLUDED, InvalidReason.FPS_LOW, InvalidReason.QUALITY_PROXY, InvalidReason.REDOCK_PENDING, InvalidReason.RECALIBRATION),
            InvalidReason.ENVIRONMENTAL,
        )
        assertEquals(setOf(InvalidReason.FACE_MISSING_UNCONFIRMED, InvalidReason.HEAD_MISSING, InvalidReason.FACE_UNSTABLE), InvalidReason.CAMERA_UNCONFIRMED)
        assertEquals(InvalidReason.entries.toSet(), InvalidReason.ENVIRONMENTAL + InvalidReason.CAMERA_UNCONFIRMED + InvalidReason.PHONE_SHAKE)
    }

    @Test
    fun secondRecordInvariants() {
        val ok = Synth.record(0)
        assertFailsWith<IllegalArgumentException> { ok.copy(rawState = State.INVALID) }
        assertFailsWith<IllegalArgumentException> { ok.copy(rawState = State.PRESENT, invalidReason = InvalidReason.LOW_LIGHT) }
        assertFailsWith<IllegalArgumentException> { ok.copy(zoneStatus = ZoneStatus.OUTSIDE) }
        assertFailsWith<IllegalArgumentException> { ok.copy(zoneStatus = ZoneStatus.IN_ZONE, zoneId = null) }
        assertFailsWith<IllegalArgumentException> { ok.copy(candidateState = State.ABSENT) }
        assertFailsWith<IllegalArgumentException> { ok.copy(headLandmarkPresent = false) }
        assertFailsWith<IllegalArgumentException> { ok.copy(framesDropped = -1) }
        assertEquals(24.0, ok.fpsActual)
        val json = FocusJson.compact.encodeToString(SecondRecord.serializer(), ok.copy(rawState = State.INVALID, invalidReason = InvalidReason.FACE_UNSTABLE, zoneStatus = ZoneStatus.NO_HEAD_POSE, zoneId = null))
        assertTrue(json.contains("\"invalid_reason\":\"face_unstable\""))
        assertTrue(json.contains("\"zone_status\":\"no_head_pose\""))
    }

    @Test
    fun eventInvariantsAndNames() {
        assertFailsWith<IllegalArgumentException> { Event(EventType.SHAKE, 1, by = RedockBy.TAP) }
        assertFailsWith<IllegalArgumentException> { Event(EventType.REDOCK_CONFIRMED, 1) }
        assertFailsWith<IllegalArgumentException> { Event(EventType.SHAKE, 1, zoneId = 2) }
        val e = Event.redockConfirmed(5, RedockBy.TAP)
        assertEquals("""{"type":"redock_confirmed","t_mono_ms":5,"by":"tap","zone_id":null}""", FocusJson.compact.encodeToString(Event.serializer(), e))
        assertEquals("""{"type":"zone_added","t_mono_ms":6,"by":null,"zone_id":2}""", FocusJson.compact.encodeToString(Event.serializer(), Event.zoneAdded(6, 2)))
    }
}
