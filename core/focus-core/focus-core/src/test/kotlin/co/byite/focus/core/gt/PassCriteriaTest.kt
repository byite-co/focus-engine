package co.byite.focus.core.gt

import co.byite.focus.core.Synth
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassCriteriaTest {
    private val diff = GtDiff(Synth.params)
    private fun iv(start: Long, end: Long, behavior: String) = GtInterval(start * 1000, end * 1000, behavior)
    private fun gt(scenario: String, vararg intervals: GtInterval) = GtFile("S-synth", scenario, "scripted", Synth.T0, intervals.toList())

    @Test
    fun scenarioIdsAreParsed() {
        assertEquals("T4", PassCriteria.scenarioBase("T4b"))
        assertEquals('b', PassCriteria.scenarioVariant("T4b"))
        assertEquals("T10", PassCriteria.scenarioBase("t10"))
        assertNull(PassCriteria.scenarioVariant("T10"))
        assertEquals("FREE", PassCriteria.scenarioBase("free"))
    }

    @Test
    fun t11HasNoPassLineExceptReproducibility() {
        val report = diff.diff(gt("T11", iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE)), Synth.replayResult(Synth.stated(0, *Array(30) { State.PRESENT })), replayTwiceIdentical = true)
        val applicable = report.pass.items.filter { it.applicable }.map { it.id }
        assertEquals(listOf("reproducibility"), applicable)
        assertEquals(true, report.pass.overall)
    }

    @Test
    fun t4bPauseAndResumeTiming() {
        val states = Array(160) { State.PRESENT }
        for (s in 0 until 121) states[s] = State.INVALID
        for (s in 121 until 153) states[s] = State.PAUSED
        val gt = gt("T4b", iv(0, 150, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(150, 160, BehaviorCatalog.STUDY_IN_ZONE))
        val items = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states))).pass.items.associateBy { it.id }
        assertEquals(true, items.getValue("t4b_pause_timing").passed)
        assertEquals("121000 ms", items.getValue("t4b_pause_timing").measured)
        assertEquals(true, items.getValue("t4b_resume_timing").passed)
        assertEquals("3000 ms", items.getValue("t4b_resume_timing").measured)
        assertFalse(items.getValue("t4a_prone_recall").applicable)

        val late = states.copyOf()
        for (s in 121 until 125) late[s] = State.INVALID
        for (s in 153 until 158) late[s] = State.PAUSED
        val lateItems = diff.diff(gt, Synth.replayResult(Synth.stated(0, *late))).pass.items.associateBy { it.id }
        assertEquals(false, lateItems.getValue("t4b_pause_timing").passed)
        assertEquals(false, lateItems.getValue("t4b_resume_timing").passed)
    }

    @Test
    fun t5ProxiesAndZeroChecks() {
        val states = Array(120) { State.INVALID }
        for (s in 60 until 100) states[s] = State.PHONE
        states[70] = State.PRESENT // a spurious re-dock confirmation
        for (s in 100 until 120) states[s] = State.PRESENT
        states[110] = State.INVALID // desk bump
        states[111] = State.PHONE // wrongly PHONE during the bump
        val gt = gt("T5", iv(0, 100, BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE), iv(100, 110, BehaviorCatalog.STUDY_IN_ZONE), iv(110, 112, BehaviorCatalog.DESK_BUMP), iv(112, 120, BehaviorCatalog.STUDY_IN_ZONE))
        val items = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states))).pass.items.associateBy { it.id }
        assertEquals(false, items.getValue("t5b_redock_confirm_zero").passed)
        assertEquals("1 run(s)", items.getValue("t5b_redock_confirm_zero").measured)
        assertTrue(items.getValue("t5b_redock_confirm_zero").note!!.startsWith("proxy"))
        assertEquals(false, items.getValue("t5c_desk_bump_phone_zero").passed)
        assertEquals("1 s", items.getValue("t5c_desk_bump_phone_zero").measured)
        assertTrue(items.getValue("phone_recall").applicable)
    }

    @Test
    fun t10SessionClose() {
        val records = Synth.stated(0, *Array(100) { State.PRESENT })
        val gt = gt("T10", iv(0, 130, BehaviorCatalog.STUDY_IN_ZONE), iv(130, 200, BehaviorCatalog.APP_KILLED))
        val ok = diff.diff(gt, Synth.replayResult(records, end = SessionEnd(Synth.mono(99), null, SessionEndReason.PROCESS_DEATH_RECOVERED)))
        assertEquals(true, ok.pass.items.first { it.id == "t10_session_close" }.passed)
        val lossy = Synth.stated(0, *Array(60) { State.PRESENT })
        val bad = diff.diff(gt, Synth.replayResult(lossy, end = SessionEnd(Synth.mono(59), null, SessionEndReason.PROCESS_DEATH_RECOVERED)))
        assertEquals(false, bad.pass.items.first { it.id == "t10_session_close" }.passed)
        val notClosed = diff.diff(gt, Synth.replayResult(records))
        assertEquals(false, notClosed.pass.items.first { it.id == "t10_session_close" }.passed)
    }

    @Test
    fun overallIsInconclusiveWhenSomethingCannotBeEvaluated() {
        // T2 without any short leave: t2_short_leave_absent_zero is applicable but not evaluable
        val gt = gt("T2", iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 60, BehaviorCatalog.LEAVE_SEAT))
        val states = Array(60) { if (it < 30) State.PRESENT else State.ABSENT }
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)), replayTwiceIdentical = true)
        val item = report.pass.items.first { it.id == "t2_short_leave_absent_zero" }
        assertTrue(item.applicable)
        assertNull(item.passed)
        assertNull(report.pass.overall)
    }
}
