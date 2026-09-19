package co.byite.focus.core.gt

import co.byite.focus.core.Synth
import co.byite.focus.core.model.Event
import co.byite.focus.core.model.InvalidReason
import co.byite.focus.core.model.RedockBy
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
    fun t4bPauseTimingAndResumeWithinSevenSeconds() {
        val gt = gt("T4b", iv(0, 150, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(150, 160, BehaviorCatalog.STUDY_IN_ZONE))
        fun states(pauseFrom: Int, resumeAt: Int): Array<State> = Array(160) { s ->
            when {
                s < pauseFrom -> State.INVALID
                s < resumeAt -> State.PAUSED
                else -> State.PRESENT
            }
        }
        val onTime = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states(120, 153)))).pass.items.associateBy { it.id }
        assertEquals(true, onTime.getValue("t4b_pause_timing").passed)
        assertEquals("120000 ms", onTime.getValue("t4b_pause_timing").measured)
        assertEquals(true, onTime.getValue("t4b_resume_timing").passed)
        assertEquals("3000 ms", onTime.getValue("t4b_resume_timing").measured)
        assertEquals("<= 7000 ms", onTime.getValue("t4b_resume_timing").threshold)
        assertFalse(onTime.getValue("t4a_prone_recall").applicable)

        // the 6 s exclusion after the return cue hides the PAUSED seconds 150..155 from the state score
        val onTimeReport = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states(120, 156))))
        assertEquals(0, onTimeReport.confusion.getValue(State.PRESENT).getValue(State.PAUSED))
        assertEquals(true, onTimeReport.pass.items.first { it.id == "t4b_resume_timing" }.passed)
        assertEquals("6000 ms", onTimeReport.pass.items.first { it.id == "t4b_resume_timing" }.measured)

        val seven = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states(121, 157)))).pass.items.associateBy { it.id }
        assertEquals(true, seven.getValue("t4b_resume_timing").passed)
        assertEquals(true, seven.getValue("t4b_pause_timing").passed)

        val late = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states(125, 158)))).pass.items.associateBy { it.id }
        assertEquals(false, late.getValue("t4b_pause_timing").passed)
        assertEquals(false, late.getValue("t4b_resume_timing").passed)
        assertEquals("8000 ms", late.getValue("t4b_resume_timing").measured)
    }

    @Test
    fun t5bCountsRedockConfirmedEventsAndT5cCountsPhoneSeconds() {
        val states = Array(120) { State.INVALID }
        for (s in 60 until 100) states[s] = State.PHONE
        for (s in 100 until 120) states[s] = State.PRESENT
        states[110] = State.INVALID
        states[111] = State.PHONE
        val gt = gt("T5", iv(0, 100, BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE), iv(100, 110, BehaviorCatalog.STUDY_IN_ZONE), iv(110, 112, BehaviorCatalog.DESK_BUMP), iv(112, 120, BehaviorCatalog.STUDY_IN_ZONE))
        val clean = Synth.stated(0, *states)
        val items = diff.diff(gt, Synth.replayResult(clean)).pass.items.associateBy { it.id }
        assertEquals(true, items.getValue("t5b_redock_confirm_zero").passed)
        assertEquals("0 redock_confirmed event(s)", items.getValue("t5b_redock_confirm_zero").measured)
        assertNull(items.getValue("t5b_redock_confirm_zero").note)
        assertEquals(false, items.getValue("t5c_desk_bump_phone_zero").passed)
        assertEquals("1 s", items.getValue("t5c_desk_bump_phone_zero").measured)
        assertTrue(items.getValue("phone_recall").applicable)

        val withRedock = clean.mapIndexed { i, r -> if (i == 70) r.copy(events = listOf(Event.redockConfirmed(Synth.mono(70), RedockBy.ORIENTATION))) else r }
        val bad = diff.diff(gt, Synth.replayResult(withRedock)).pass.items.associateBy { it.id }
        assertEquals(false, bad.getValue("t5b_redock_confirm_zero").passed)
        assertEquals("1 redock_confirmed event(s)", bad.getValue("t5b_redock_confirm_zero").measured)

        // an event outside the flat-surface interval does not count
        val outside = clean.mapIndexed { i, r -> if (i == 105) r.copy(events = listOf(Event.userRedockTap(Synth.mono(105)), Event.redockConfirmed(Synth.mono(105), RedockBy.TAP))) else r }
        assertEquals(true, diff.diff(gt, Synth.replayResult(outside)).pass.items.first { it.id == "t5b_redock_confirm_zero" }.passed)
    }

    @Test
    fun t7cCountsCameraOccludedSecondsAcrossTheWholeInterval() {
        val gt = gt("T7", iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 60, BehaviorCatalog.LEAVE_SEAT_PLAIN_BACKGROUND), iv(60, 90, BehaviorCatalog.STUDY_IN_ZONE))
        val states = Array(90) { if (it in 30 until 60) State.ABSENT else State.PRESENT }
        val clean = Synth.stated(0, *states)
        val ok = diff.diff(gt, Synth.replayResult(clean)).pass.items.associateBy { it.id }
        assertEquals(true, ok.getValue("t7c_occluded_zero").passed)
        assertNull(ok.getValue("t7c_occluded_zero").note)
        assertFalse(ok.getValue("t7ab_absent_zero").applicable == false)

        // one occluded second inside the reaction window still counts (whole interval), an unconfirmed-face second does not
        val occluded = clean.mapIndexed { i, r ->
            when (i) {
                31 -> r.copy(rawState = State.INVALID, finalState = State.INVALID, invalidReason = InvalidReason.CAMERA_OCCLUDED)
                45 -> r.copy(rawState = State.INVALID, finalState = State.ABSENT, invalidReason = InvalidReason.FACE_MISSING_UNCONFIRMED)
                else -> r
            }
        }
        val bad = diff.diff(gt, Synth.replayResult(occluded)).pass.items.associateBy { it.id }
        assertEquals(false, bad.getValue("t7c_occluded_zero").passed)
        assertEquals("1 s", bad.getValue("t7c_occluded_zero").measured)
    }

    @Test
    fun t10SessionClose() {
        val records = Synth.stated(0, *Array(100) { State.PRESENT })
        val gt = gt("T10", iv(0, 130, BehaviorCatalog.STUDY_IN_ZONE), iv(130, 200, BehaviorCatalog.APP_KILLED))
        val ok = diff.diff(gt, Synth.replayResult(records, end = SessionEnd(Synth.mono(99), Synth.utc(99), SessionEndReason.PROCESS_DEATH_RECOVERED)))
        assertEquals(true, ok.pass.items.first { it.id == "t10_session_close" }.passed)
        val lossy = Synth.stated(0, *Array(60) { State.PRESENT })
        val bad = diff.diff(gt, Synth.replayResult(lossy, end = SessionEnd(Synth.mono(59), Synth.utc(59), SessionEndReason.PROCESS_DEATH_RECOVERED)))
        assertEquals(false, bad.pass.items.first { it.id == "t10_session_close" }.passed)
        val notClosed = diff.diff(gt, Synth.replayResult(records))
        assertEquals(false, notClosed.pass.items.first { it.id == "t10_session_close" }.passed)
    }

    @Test
    fun overallIsInconclusiveWhenSomethingCannotBeEvaluated() {
        val gt = gt("T2", iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 60, BehaviorCatalog.LEAVE_SEAT))
        val states = Array(60) { if (it < 30) State.PRESENT else State.ABSENT }
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)), replayTwiceIdentical = true)
        val item = report.pass.items.first { it.id == "t2_short_leave_absent_zero" }
        assertTrue(item.applicable)
        assertNull(item.passed)
        assertNull(report.pass.overall)
    }
}
