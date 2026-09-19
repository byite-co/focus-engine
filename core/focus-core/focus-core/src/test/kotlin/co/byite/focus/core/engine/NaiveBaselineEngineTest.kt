package co.byite.focus.core.engine

import co.byite.focus.core.Synth
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaiveBaselineEngineTest {
    @Test
    fun thirdMissingBucketConfirmsAbsentWithCandidateStartAtTheFirst() {
        val e = NaiveBaselineEngine(Synth.params)
        val records = Synth.faceSegments(2 to true, 5 to false, 1 to true)
        val decisions = records.map { e.judge(it) }
        assertEquals(listOf(State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT, State.ABSENT, State.PRESENT), decisions.map { it.rawState })
        // buckets 2 and 3 are unconfirmed: still PRESENT but carrying the ABSENT candidate from bucket 2
        assertEquals(State.ABSENT, decisions[2].candidateState)
        assertEquals(Synth.mono(2), decisions[2].candidateStartMonoMs)
        assertEquals(State.ABSENT, decisions[3].candidateState)
        assertEquals(Synth.mono(2), decisions[4].candidateStartMonoMs)
        assertEquals(true, decisions[4].confirmsBackdate)
        assertEquals(false, decisions[3].confirmsBackdate)
        assertEquals(Synth.mono(2), decisions[6].candidateStartMonoMs)
        assertNull(decisions[7].candidateState)
        assertNull(decisions[1].candidateState)
    }

    @Test
    fun twoBucketsAreNotAbsent() {
        val e = NaiveBaselineEngine(Synth.params)
        val decisions = Synth.faceSegments(1 to true, 2 to false, 1 to true).map { e.judge(it) }
        assertEquals(List(4) { State.PRESENT }, decisions.map { it.rawState })
    }

    @Test
    fun resetForgetsTheCandidate() {
        val e = NaiveBaselineEngine(Synth.params)
        e.judge(Synth.record(0, face = false))
        e.judge(Synth.record(1, face = false))
        e.reset()
        assertEquals(State.PRESENT, e.judge(Synth.record(2, face = false)).rawState)
    }

    @Test
    fun missingBandUsesFaceMissingMaxRatio() {
        val e = NaiveBaselineEngine(Synth.params)
        // 0.19 is missing, 0.3 (unstable band) is not
        e.judge(Synth.record(0, faceRatio = 0.19))
        e.judge(Synth.record(1, faceRatio = 0.19))
        assertEquals(State.ABSENT, e.judge(Synth.record(2, faceRatio = 0.19)).rawState)
        val d = e.judge(Synth.record(3, faceRatio = 0.3))
        assertEquals(State.PRESENT, d.rawState)
        assertNull(d.candidateState)
        val custom = NaiveBaselineEngine(Synth.params.copy(faceMissingMaxRatio = 0.4))
        repeat(2) { custom.judge(Synth.record(it.toLong(), faceRatio = 0.3)) }
        assertEquals(State.ABSENT, custom.judge(Synth.record(2, faceRatio = 0.3)).rawState)
    }
}
