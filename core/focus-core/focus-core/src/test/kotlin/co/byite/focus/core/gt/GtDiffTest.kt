package co.byite.focus.core.gt

import co.byite.focus.core.Synth
import co.byite.focus.core.engine.NaiveBaselineEngine
import co.byite.focus.core.log.FocusJson
import co.byite.focus.core.model.State
import co.byite.focus.core.replay.ReplayRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GtDiffTest {
    private val diff = GtDiff(Synth.params)

    private fun iv(start: Long, end: Long, behavior: String) = GtInterval(start * 1000, end * 1000, behavior)

    /** T2: study 60 s, 2 s stand-up, study 60 s, leave 10 s, study 60 s, leave 60 s, study 60 s. */
    private val t2Gt = GtFile(
        "S-synth", "T2", "scripted", Synth.T0,
        listOf(
            iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE),
            iv(60, 62, BehaviorCatalog.LEAVE_SEAT),
            iv(62, 122, BehaviorCatalog.STUDY_IN_ZONE),
            iv(122, 132, BehaviorCatalog.LEAVE_SEAT),
            iv(132, 192, BehaviorCatalog.STUDY_IN_ZONE),
            iv(192, 252, BehaviorCatalog.LEAVE_SEAT),
            iv(252, 312, BehaviorCatalog.STUDY_IN_ZONE),
        ),
    )
    private val t2Log = Synth.log(Synth.faceSegments(60 to true, 2 to false, 60 to true, 10 to false, 60 to true, 60 to false, 60 to true))

    @Test
    fun naiveEngineOnAPerfectT2LogPassesTheAbsentRows() {
        val runner = ReplayRunner(NaiveBaselineEngine(Synth.params), Synth.params)
        val a = runner.run(t2Log)
        val b = runner.run(t2Log)
        val report = diff.diff(t2Gt, a, baseline = b, loggedRecords = t2Log.records, replayTwiceIdentical = a.sameOutcomeAs(b))

        assertEquals(312, report.seconds.records)
        assertEquals(6 * 3 + 2, report.seconds.excludedReactionWindow) // the 2 s interval has only 2 seconds
        assertEquals(312 - 20, report.seconds.scored)
        assertEquals(0, report.seconds.missingRecords)
        assertEquals(0, report.seconds.outsideGt)

        val absent = report.perState.getValue(State.ABSENT)
        assertEquals(7 + 57, absent.expectedS)
        assertEquals(1.0, absent.recall)
        assertEquals(1.0, absent.precision)
        assertEquals(0.0, absent.ratioErrorPp)
        assertEquals(1.0, report.perState.getValue(State.PRESENT).recall)
        assertEquals(0, report.perState.getValue(State.INVALID).measuredS)

        val lat = assertNotNull(report.detectionLatency[State.ABSENT])
        assertEquals(2, lat.n)
        assertEquals(0, lat.missed)
        assertEquals(2_000.0, lat.medianMs)
        assertEquals(2_000, lat.p95Ms)
        // the 2 s stand-up is an INVALID transition; the 4 returns to PRESENT are transitions too
        assertEquals(1, report.detectionLatency.getValue(State.INVALID).n)
        assertEquals(1, report.detectionLatency.getValue(State.INVALID).missed)
        assertEquals(4, report.detectionLatency.getValue(State.PRESENT).n)

        assertEquals(0, report.flapping.changes)
        assertEquals(0, report.falseInvalid.numerator)
        assertEquals(0.0, report.falseAway.ratio)

        assertEquals(true, report.reproducibility.replayTwiceIdentical)
        assertEquals(0, report.reproducibility.loggedFinalComparedS)
        val cmp = assertNotNull(report.baselineComparison)
        assertEquals(0, cmp.primary.falseAbsentS)
        assertEquals(0, cmp.primary.missedAbsentS)
        assertEquals(cmp.primary.copy(engineId = cmp.baseline.engineId), cmp.baseline)

        val items = report.pass.items.associateBy { it.id }
        assertEquals(true, items.getValue("absent_recall").passed)
        assertEquals(true, items.getValue("absent_precision").passed)
        assertEquals(true, items.getValue("t2_short_leave_absent_zero").passed)
        assertEquals("0 s", items.getValue("t2_short_leave_absent_zero").measured)
        assertEquals(true, items.getValue("latency_absent_p95").passed)
        assertEquals(true, items.getValue("flapping_per_10min").passed)
        assertEquals(true, items.getValue("ratio_error_3pp").passed)
        assertEquals(true, items.getValue("reproducibility").passed)
        assertFalse(items.getValue("false_invalid").applicable)
        assertFalse(items.getValue("phone_recall").applicable)
        assertEquals(true, report.pass.overall)

        // the report serialises (enum map keys, nullable doubles) and renders
        val json = FocusJson.pretty.encodeToString(GtReport.serializer(), report)
        assertTrue(json.contains("\"ABSENT\": {"))
        assertEquals(report, FocusJson.pretty.decodeFromString(GtReport.serializer(), json))
        val text = ConsoleReport.render(report)
        assertTrue(text.contains("overall: PASS"), text)
        assertTrue(text.contains("confusion matrix"))
    }

    @Test
    fun missedAndFalseStatesAreCountedPerSecond() {
        // engine that sees ABSENT 5 s late and stays ABSENT 3 s too long, plus a spurious INVALID second
        val records = Synth.stated(0, *Array(60) { State.PRESENT }) +
            Synth.stated(60, State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT, State.ABSENT, State.ABSENT, State.ABSENT) +
            Synth.stated(70, State.ABSENT, State.ABSENT, State.ABSENT, State.INVALID) + Synth.stated(74, *Array(56) { State.PRESENT })
        val gt = GtFile("S-synth", "T2", "scripted", Synth.T0, listOf(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LEAVE_SEAT), iv(70, 130, BehaviorCatalog.STUDY_IN_ZONE)))
        val report = diff.diff(gt, Synth.replayResult(records))

        val absent = report.perState.getValue(State.ABSENT)
        assertEquals(7, absent.expectedS) // 63..69
        assertEquals(5, absent.tp) // 65..69
        assertEquals(2, absent.fn) // 63, 64
        assertEquals(0, absent.fp) // 70..72 fall in the reaction window of the next cue
        assertEquals(5.0 / 7, absent.recall!!, 1e-9)
        assertEquals(1.0, absent.precision)
        assertEquals(2, report.confusion.getValue(State.ABSENT).getValue(State.PRESENT))
        assertEquals(1, report.confusion.getValue(State.PRESENT).getValue(State.INVALID))
        assertEquals(1, report.falseInvalid.numerator)
        assertEquals(1, report.invalidRatioByExpected.getValue(State.PRESENT).numerator)

        val lat = report.detectionLatency.getValue(State.ABSENT)
        assertEquals(5_000, lat.p95Ms)
        assertEquals(false, report.pass.items.first { it.id == "absent_recall" }.passed)
        assertEquals(true, report.pass.items.first { it.id == "latency_absent_p95" }.passed)
        assertEquals(false, report.pass.overall)

        assertEquals(2, report.baselineComparison?.let { 0 } ?: 2) // no baseline supplied
        assertNull(report.baselineComparison)
    }

    @Test
    fun flappingCountsChangesInsideConstantExpectedRuns() {
        val states = Array(120) { State.PRESENT }
        states[40] = State.AWAY
        states[41] = State.AWAY
        states[80] = State.INVALID
        val gt = GtFile("S-synth", "T1", "scripted", Synth.T0, listOf(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 120, BehaviorCatalog.STUDY_IN_ZONE)))
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)))
        // PRESENT→AWAY, AWAY→PRESENT, PRESENT→INVALID, INVALID→PRESENT = 4 changes in one 114 s PRESENT run
        assertEquals(4, report.flapping.changes)
        assertEquals(1, report.flapping.runs.size)
        assertEquals(114, report.flapping.scoredS)
        assertEquals(4 / (114 / 600.0), report.flapping.per10Min!!, 1e-9)
        assertEquals(2.0 / 114, report.falseAway.ratio!!, 1e-9)
        assertEquals(false, report.pass.items.first { it.id == "false_away" }.passed)
        assertEquals(false, report.pass.items.first { it.id == "flapping_per_10min" }.passed)
        assertEquals(true, report.pass.items.first { it.id == "false_invalid" }.passed)
    }

    @Test
    fun transitionsAcrossSameExpectedStateAreNotFlapping() {
        val gt = GtFile("S-synth", "T6", "scripted", Synth.T0, listOf(iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 60, BehaviorCatalog.LOOK_AWAY_UNREGISTERED), iv(60, 90, BehaviorCatalog.STUDY_IN_ZONE)))
        val states = Array(90) { State.PRESENT }
        for (s in 34 until 60) states[s] = State.AWAY
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)))
        assertEquals(0, report.flapping.changes)
        assertEquals(3, report.flapping.runs.size)
        assertEquals(1.0, report.perState.getValue(State.AWAY).recall)
        assertEquals(4_000, report.detectionLatency.getValue(State.AWAY).p95Ms)
        assertEquals(true, report.pass.items.first { it.id == "away_recall" }.passed)
        assertEquals(true, report.pass.items.first { it.id == "latency_away_p95" }.passed)
    }

    @Test
    fun missingRecordsAndOutsideRecordsAreReported() {
        val gt = GtFile("S-synth", "T1", "scripted", Synth.T0, listOf(iv(0, 30, BehaviorCatalog.STUDY_IN_ZONE)))
        val records = Synth.stated(-2, State.PRESENT, State.PRESENT) + Synth.stated(0, *Array(20) { State.PRESENT }) + Synth.stated(30, State.PRESENT)
        val report = diff.diff(gt, Synth.replayResult(records))
        assertEquals(3, report.seconds.outsideGt)
        assertEquals(10, report.seconds.missingRecords) // 20..29 scored, absent
        assertEquals(17, report.seconds.scored)
    }

    @Test
    fun loggedFinalStatesAreComparedWithTheReplay() {
        val logged = Synth.stated(0, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT)
        val replayed = Synth.stated(0, State.PRESENT, State.PRESENT, State.ABSENT, State.PRESENT)
        val gt = GtFile("S-synth", "T1", "scripted", Synth.T0, listOf(iv(0, 4, BehaviorCatalog.STUDY_IN_ZONE)))
        val rep = diff.diff(gt, Synth.replayResult(replayed), loggedRecords = logged, replayTwiceIdentical = true).reproducibility
        assertEquals(4, rep.loggedFinalComparedS)
        assertEquals(1, rep.loggedFinalMismatchS)
        assertEquals(0.75, rep.loggedFinalMatchRatio)
    }
}
