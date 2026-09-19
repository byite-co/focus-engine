package co.byite.focus.core.replay

import co.byite.focus.core.Synth
import co.byite.focus.core.engine.NaiveBaselineEngine
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.Event
import co.byite.focus.core.model.EventType
import co.byite.focus.core.model.GapReason
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayRunnerTest {
    private fun runner() = ReplayRunner(NaiveBaselineEngine(Synth.params), Synth.params)

    private val basic: SessionLog = Synth.log(Synth.faceSegments(60 to true, 10 to false, 60 to true), calibrations = listOf(Synth.calibration(0)), timebase = listOf(Synth.timebase(0)))

    @Test
    fun recomputesRawFinalAndCandidateFields() {
        val result = runner().run(basic)
        assertEquals(130, result.records.size)
        assertEquals(basic.records.map { it.tMonoMs }, result.records.map { it.tMonoMs })
        val raw = result.records.map { it.rawState!! }
        val final = result.records.map { it.finalState!! }
        assertEquals(State.PRESENT, raw[61])
        assertEquals(State.ABSENT, raw[62])
        for (s in 60 until 70) assertEquals(State.ABSENT, final[s], "t=$s")
        assertEquals(State.PRESENT, final[59])
        assertEquals(State.PRESENT, final[70])
        assertEquals(State.ABSENT, result.records[60].candidateState)
        assertEquals(Synth.mono(60), result.records[65].candidateStartMonoMs)
        assertNull(result.records[70].candidateState)
        assertEquals(SessionEndReason.UNKNOWN, result.sessionEnd!!.reason)
        assertEquals(Synth.mono(129), result.sessionEnd!!.tMonoMs)
        assertEquals(Synth.utc(129), result.sessionEnd!!.tUtcMs)
    }

    @Test
    fun replayIsDeterministic() {
        val r = runner()
        val a = r.run(basic)
        val b = r.run(basic)
        val c = runner().run(basic)
        assertTrue(a.sameOutcomeAs(b))
        assertTrue(a.sameOutcomeAs(c))
        assertEquals(a, b)
        val encodedA = JsonlCodec.encode(SessionLog(basic.header, a.records, a.intervals, a.sessionEnd))
        val encodedC = JsonlCodec.encode(SessionLog(basic.header, c.records, c.intervals, c.sessionEnd))
        assertEquals(encodedA, encodedC)
    }

    @Test
    fun ignoresLoggedStatesAndOutputEventsButKeepsInputEvents() {
        val zone = Event.zoneAdded(Synth.mono(3), 2)
        val tap = Event.userRedockTap(Synth.mono(3))
        val gate = Event(EventType.PICKUP_CONFIRMED, Synth.mono(3))
        val tampered = basic.copy(
            records = basic.records.map { it.copy(rawState = State.PHONE, finalState = State.PHONE, candidateState = State.PHONE, candidateStartMonoMs = it.tMonoMs) }
                .mapIndexed { i, r -> if (i == 3) r.copy(events = listOf(gate, zone, tap)) else r },
        )
        val clean = runner().run(basic)
        val replayed = runner().run(tampered)
        assertEquals(clean.records.map { it.finalState }, replayed.records.map { it.finalState })
        assertEquals(clean.records.map { it.candidateState }, replayed.records.map { it.candidateState })
        assertEquals(listOf(zone, tap), replayed.records[3].events)
    }

    @Test
    fun unsortedRecordsAreReplayedInTimeOrder() {
        val shuffled = basic.copy(records = basic.records.reversed())
        assertTrue(runner().run(basic).sameOutcomeAs(runner().run(shuffled)))
    }

    @Test
    fun lifecycleGapIsReplayedThroughTheFinalizer() {
        val before = Synth.faceSegments(100 to true)
        val after = (0 until 60).map { Synth.record(220L + it) }
        val gap = IntervalRecord(Synth.mono(100), Synth.mono(220), Synth.utc(100), Synth.utc(220), State.PHONE, GapReason.APP_SWITCH)
        val result = runner().run(Synth.log(before + after, listOf(gap)))
        assertEquals(160, result.records.size)
        assertEquals(listOf(gap), result.intervals)
        assertEquals(0, result.droppedAfterSessionEnd)
        assertEquals(Synth.mono(279), result.sessionEnd!!.tMonoMs)
    }

    @Test
    fun elevenMinuteGapEndsTheSessionAndDropsLaterRecords() {
        val before = Synth.faceSegments(100 to true)
        val after = (0 until 30).map { Synth.record(760L + it) }
        val gap = IntervalRecord(Synth.mono(100), Synth.mono(760), Synth.utc(100), Synth.utc(760), State.PHONE, GapReason.APP_SWITCH)
        val result = runner().run(Synth.log(before + after, listOf(gap)))
        assertEquals(100, result.records.size)
        assertTrue(result.intervals.isEmpty())
        assertEquals(30, result.droppedAfterSessionEnd)
        val end = assertNotNull(result.sessionEnd)
        assertEquals(SessionEndReason.LIFECYCLE_GAP_TIMEOUT, end.reason)
        assertEquals(Synth.mono(100), end.tMonoMs)
        assertTrue(result.warnings.any { it.contains("dropped") })
    }

    @Test
    fun loggedSessionEndIsHonoured() {
        val end = SessionEnd(Synth.mono(50), Synth.utc(50), SessionEndReason.PROCESS_DEATH_RECOVERED)
        val result = runner().run(Synth.log(Synth.faceSegments(51 to true), end = end))
        assertEquals(end, result.sessionEnd)
        assertEquals(51, result.records.size)
    }

    @Test
    fun recordInsideGapIsRejected() {
        val gap = IntervalRecord(Synth.mono(10), Synth.mono(20), Synth.utc(10), Synth.utc(20), State.PAUSED, GapReason.SCREEN_LOCK)
        assertFailsWith<IllegalArgumentException> { runner().run(Synth.log(Synth.faceSegments(30 to true), listOf(gap))) }
    }

    @Test
    fun parameterSetMismatchIsAWarning() {
        val other = Synth.params.copy(parameterSetId = "other")
        val result = ReplayRunner(NaiveBaselineEngine(other), other).run(basic)
        assertTrue(result.warnings.any { it.contains("parameter_set_id mismatch") })
    }
}
