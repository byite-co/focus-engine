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
    fun eventsAreSplitIntoInputAndOutput() {
        assertEquals(setOf(EventType.USER_REDOCK_TAP, EventType.ZONE_ADDED), EventType.INPUT)
        assertEquals(EventType.entries.toSet() - EventType.INPUT, EventType.OUTPUT)
        assertTrue(EventType.RECALIBRATION_ABORTED.isOutput)
        assertEquals("""{"type":"user_redock_tap","t_mono_ms":9,"by":null,"zone_id":null}""", FocusJson.compact.encodeToString(Event.serializer(), Event.userRedockTap(9)))
        assertEquals("""{"type":"recalibration_aborted","t_mono_ms":9,"by":null,"zone_id":null}""", FocusJson.compact.encodeToString(Event.serializer(), Event(EventType.RECALIBRATION_ABORTED, 9)))
        val r = Synth.record(0, events = listOf(Event.userRedockTap(Synth.mono(0)), Event.redockConfirmed(Synth.mono(0), RedockBy.TAP), Event.zoneAdded(Synth.mono(0), 1)))
        assertEquals(listOf(EventType.USER_REDOCK_TAP, EventType.ZONE_ADDED), r.inputEvents.map { it.type })
        assertEquals(listOf(EventType.REDOCK_CONFIRMED), r.outputEvents.map { it.type })
        // a tap confirmation without the tap input in the same bucket is rejected
        assertFailsWith<IllegalArgumentException> { Synth.record(0, events = listOf(Event.redockConfirmed(Synth.mono(0), RedockBy.TAP))) }
        Synth.record(0, events = listOf(Event.redockConfirmed(Synth.mono(0), RedockBy.ORIENTATION)))
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
