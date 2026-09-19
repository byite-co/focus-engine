package co.byite.focus.core.engine

import co.byite.focus.core.Synth
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaiveBaselineEngineTest {
    @Test
    fun threeNoFaceSecondsConfirmAbsentWithCandidateStart() {
        val e = NaiveBaselineEngine(Synth.params)
        val records = Synth.faceSegments(2 to true, 5 to false, 1 to true)
        val decisions = records.map { e.judge(it) }
        assertEquals(listOf(State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT, State.ABSENT, State.PRESENT), decisions.map { it.rawState })
        assertNull(decisions[3].candidateStartMonoMs)
        assertEquals(Synth.mono(2), decisions[4].candidateStartMonoMs)
        assertEquals(Synth.mono(2), decisions[6].candidateStartMonoMs)
        assertNull(decisions[7].candidateStartMonoMs)
    }

    @Test
    fun twoSecondsAreNotAbsent() {
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
    fun facePresentThresholdComesFromParameters() {
        val e = NaiveBaselineEngine(Synth.params.copy(facePresentMinRatio = 0.9))
        val half = Synth.record(0).copy(faceDetectRatio = 0.5)
        e.judge(half)
        e.judge(half.copy(tMonoMs = Synth.mono(1)))
        assertEquals(State.ABSENT, e.judge(half.copy(tMonoMs = Synth.mono(2))).rawState)
    }
}
