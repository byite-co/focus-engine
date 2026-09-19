package co.byite.focus.core.finalizer

import co.byite.focus.core.Synth
import co.byite.focus.core.engine.GateDecision
import co.byite.focus.core.model.GapReason
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateFinalizerTest {
    private fun fin() = StateFinalizer(Synth.params)

    private fun push(f: StateFinalizer, sec: Long, state: State, candidate: Long? = null, face: Boolean = state == State.PRESENT): List<SecondRecord> =
        f.push(Synth.record(sec, face = face), GateDecision(state, candidate?.let { Synth.mono(it) }))

    private fun finalOf(records: List<SecondRecord>, sec: Long): State = records.first { it.tMonoMs == Synth.mono(sec) }.finalState!!
    private fun rawOf(records: List<SecondRecord>, sec: Long): State = records.first { it.tMonoMs == Synth.mono(sec) }.rawState!!

    @Test
    fun absentIsBackdatedToTheCandidateStart() {
        val f = fin()
        val out = ArrayList<SecondRecord>()
        for (s in 0L until 10) out += push(f, s, State.PRESENT)
        out += push(f, 10, State.PRESENT, face = false)
        out += push(f, 11, State.PRESENT, face = false)
        out += push(f, 12, State.ABSENT, candidate = 10)
        out += push(f, 13, State.ABSENT, candidate = 10)
        out += f.flushNow()
        assertEquals(14, out.size)
        for (s in 0L until 10) assertEquals(State.PRESENT, finalOf(out, s))
        assertEquals(State.PRESENT, rawOf(out, 10))
        assertEquals(State.PRESENT, rawOf(out, 11))
        assertEquals(State.ABSENT, finalOf(out, 10))
        assertEquals(State.ABSENT, finalOf(out, 11))
        assertEquals(State.ABSENT, finalOf(out, 12))
        assertEquals(State.ABSENT, finalOf(out, 13))
    }

    @Test
    fun recordsAreFinalizedThirtySecondsLater() {
        val f = fin()
        for (s in 0L until 30) assertTrue(push(f, s, State.PRESENT).isEmpty(), "nothing finalised before 30 s (t=$s)")
        assertEquals(30, f.pendingCount)
        val at30 = push(f, 30, State.PRESENT)
        assertEquals(listOf(Synth.mono(0)), at30.map { it.tMonoMs })
        val at31 = push(f, 31, State.PRESENT)
        assertEquals(listOf(Synth.mono(1)), at31.map { it.tMonoMs })
        assertEquals(30, f.pendingCount)
    }

    @Test
    fun finalizedRecordsAreNotBackdatedAndBackdateIsCappedAtThirtySeconds() {
        val f = fin()
        val out = ArrayList<SecondRecord>()
        for (s in 0L until 50) out += push(f, s, State.PRESENT)
        // PRONE confirmed at 50 s with a candidate start at 5 s: only [20, 50) is reachable
        out += push(f, 50, State.PRONE, candidate = 5)
        out += f.flushNow()
        for (s in 0L until 20) assertEquals(State.PRESENT, finalOf(out, s), "t=$s")
        for (s in 20L..50) assertEquals(State.PRONE, finalOf(out, s), "t=$s")
    }

    @Test
    fun awayAndPausedNeverBackdate() {
        val f = fin()
        val out = ArrayList<SecondRecord>()
        for (s in 0L until 5) out += push(f, s, State.PRESENT)
        out += push(f, 5, State.AWAY, candidate = 1)
        out += push(f, 6, State.PAUSED, candidate = 0)
        out += f.flushNow()
        for (s in 0L until 5) assertEquals(State.PRESENT, finalOf(out, s))
        assertEquals(State.AWAY, finalOf(out, 5))
        assertEquals(State.PAUSED, finalOf(out, 6))
    }

    @Test
    fun phonePickupBackdates() {
        val f = fin()
        val out = ArrayList<SecondRecord>()
        out += push(f, 0, State.PRESENT)
        out += push(f, 1, State.INVALID)
        out += push(f, 2, State.INVALID)
        out += push(f, 3, State.PHONE, candidate = 1)
        out += f.flushNow()
        assertEquals(listOf(State.PRESENT, State.PHONE, State.PHONE, State.PHONE), out.map { it.finalState })
        assertEquals(listOf(State.PRESENT, State.INVALID, State.INVALID, State.PHONE), out.map { it.rawState })
    }

    @Test
    fun flushNowFinalizesEverythingPending() {
        val f = fin()
        for (s in 0L until 5) push(f, s, State.PRESENT)
        val flushed = f.flushNow()
        assertEquals((0L until 5).map { Synth.mono(it) }, flushed.map { it.tMonoMs })
        assertEquals(0, f.pendingCount)
        assertTrue(f.flushNow().isEmpty())
    }

    @Test
    fun twoMinuteGapBecomesAnIntervalRecord() {
        val f = fin()
        for (s in 0L until 100) push(f, s, State.PRESENT)
        val flushed = f.onBackground(Synth.mono(100), Synth.utc(100))
        assertEquals(30, flushed.size)
        assertTrue(f.isInBackground)
        val outcome = f.onForeground(Synth.mono(220), Synth.utc(220), GapReason.APP_SWITCH)
        val gap = assertIs<LifecycleOutcome.Gap>(outcome)
        assertEquals(Synth.mono(100), gap.interval.tStartMonoMs)
        assertEquals(Synth.mono(220), gap.interval.tEndMonoMs)
        assertEquals(Synth.utc(100), gap.interval.tStartUtcMs)
        assertEquals(State.PHONE, gap.interval.state)
        assertEquals(GapReason.APP_SWITCH, gap.interval.reason)
        assertEquals(120_000, gap.interval.durationMs)
        // measurement continues
        assertTrue(push(f, 220, State.PRESENT).isEmpty())
    }

    @Test
    fun screenLockGapIsPaused() {
        val f = fin()
        push(f, 0, State.PRESENT)
        f.onBackground(Synth.mono(1), Synth.utc(1))
        val gap = assertIs<LifecycleOutcome.Gap>(f.onForeground(Synth.mono(61), Synth.utc(61), GapReason.SCREEN_LOCK))
        assertEquals(State.PAUSED, gap.interval.state)
    }

    @Test
    fun elevenMinuteGapEndsTheSessionAtTheEntryTime() {
        val f = fin()
        for (s in 0L until 10) push(f, s, State.PRESENT)
        f.onBackground(Synth.mono(10), Synth.utc(10))
        val outcome = f.onForeground(Synth.mono(10 + 660), Synth.utc(10 + 660), GapReason.APP_SWITCH)
        val ended = assertIs<LifecycleOutcome.SessionEnded>(outcome)
        assertEquals(Synth.mono(10), ended.end.tMonoMs)
        assertEquals(Synth.utc(10), ended.end.tUtcMs)
        assertEquals(SessionEndReason.LIFECYCLE_GAP_TIMEOUT, ended.end.reason)
        assertEquals(ended.end, f.sessionEnd)
        assertFailsWith<IllegalStateException> { push(f, 700, State.PRESENT) }
    }

    @Test
    fun exactlyTenMinutesIsStillAGap() {
        val f = fin()
        push(f, 0, State.PRESENT)
        f.onBackground(Synth.mono(1), Synth.utc(1))
        assertIs<LifecycleOutcome.Gap>(f.onForeground(Synth.mono(601), Synth.utc(601), GapReason.APP_SWITCH))
    }

    @Test
    fun endSessionFlushesAndRecordsTheReason() {
        val f = fin()
        for (s in 0L until 3) push(f, s, State.PRESENT)
        val close = f.endSession(Synth.mono(2), Synth.utc(2), SessionEndReason.PROCESS_DEATH_RECOVERED)
        assertEquals(3, close.flushed.size)
        assertEquals(SessionEndReason.PROCESS_DEATH_RECOVERED, close.end.reason)
        assertEquals(Synth.mono(2), close.end.tMonoMs)
        assertNotNull(f.sessionEnd)
        assertFailsWith<IllegalStateException> { push(f, 3, State.PRESENT) }
    }

    @Test
    fun rejectsNonMonotonicAndBackgroundPushes() {
        val f = fin()
        push(f, 5, State.PRESENT)
        assertFailsWith<IllegalArgumentException> { push(f, 5, State.PRESENT) }
        assertFailsWith<IllegalArgumentException> { push(f, 4, State.PRESENT) }
        f.onBackground(Synth.mono(6), Synth.utc(6))
        assertFailsWith<IllegalStateException> { push(f, 7, State.PRESENT) }
        assertFailsWith<IllegalStateException> { f.onBackground(Synth.mono(8), Synth.utc(8)) }
        assertFailsWith<IllegalStateException> { fin().onForeground(Synth.mono(1), Synth.utc(1), GapReason.APP_SWITCH) }
    }
}
