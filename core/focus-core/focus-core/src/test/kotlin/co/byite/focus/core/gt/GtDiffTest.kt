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
        assertNull(report.perState.getValue(State.INVALID).expectedRatio)
        assertEquals(0, report.excludedShares.getValue(State.INVALID).measuredS)
        assertEquals(0.0, report.excludedShares.getValue(State.PAUSED).diffPp)

        val lat = assertNotNull(report.detectionLatency[State.ABSENT])
        assertEquals(2, lat.n)
        assertEquals(0, lat.missed)
        assertEquals(2_000.0, lat.medianMs)
        assertEquals(2_000, lat.p95Ms)
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
        assertEquals(GtRules.DEFAULT, report.gtRules)
        assertEquals("0.2.1", report.featureSchemaVersion)

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

        val json = FocusJson.pretty.encodeToString(GtReport.serializer(), report)
        assertTrue(json.contains("\"ABSENT\": {"))
        assertTrue(json.contains("\"gt_rules\": {"))
        assertEquals(report, FocusJson.pretty.decodeFromString(GtReport.serializer(), json))
        val text = ConsoleReport.render(report)
        assertTrue(text.contains("overall: PASS"), text)
        assertTrue(text.contains("confusion matrix"))
        assertTrue(text.contains("share of INVALID"))
    }

    @Test
    fun missedAndFalseStatesAreCountedPerSecond() {
        val records = Synth.stated(0, *Array(60) { State.PRESENT }) +
            Synth.stated(60, State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT, State.ABSENT, State.ABSENT, State.ABSENT) +
            Synth.stated(70, State.ABSENT, State.ABSENT, State.ABSENT, State.INVALID) + Synth.stated(74, *Array(56) { State.PRESENT })
        val gt = GtFile("S-synth", "T2", "scripted", Synth.T0, listOf(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LEAVE_SEAT), iv(70, 130, BehaviorCatalog.STUDY_IN_ZONE)))
        val report = diff.diff(gt, Synth.replayResult(records))

        val absent = report.perState.getValue(State.ABSENT)
        assertEquals(7, absent.expectedS)
        assertEquals(5, absent.tp)
        assertEquals(2, absent.fn)
        assertEquals(0, absent.fp)
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
        assertNull(report.baselineComparison)
    }

    @Test
    fun ratiosExcludeInvalidAndPausedFromTheDenominator() {
        // 130 s: 60 s PRESENT expected, 10 s INVALID expected (short leave has 2 s, deep bow 8 s), 60 s PRESENT; measured has 4 s PAUSED and 2 s INVALID inside PRESENT runs
        val gt = GtFile("S-synth", "T3", "scripted", Synth.T0, listOf(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.DEEP_BOW_WRITING), iv(70, 130, BehaviorCatalog.STUDY_IN_ZONE)))
        val states = Array(130) { State.PRESENT }
        for (s in 60 until 70) states[s] = State.INVALID
        for (s in 20 until 24) states[s] = State.PAUSED
        states[30] = State.INVALID
        states[31] = State.INVALID
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)))
        val n = report.seconds.scored
        assertEquals(130 - 9, n)
        val present = report.perState.getValue(State.PRESENT)
        assertEquals(114, present.expectedS) // 57 + 57
        assertEquals(1.0, present.expectedRatio) // 114 / (121 − 7 INVALID expected)
        assertEquals(1.0, present.measuredRatio) // 108 / (121 − 9 INVALID − 4 PAUSED)
        assertEquals(0.0, present.ratioErrorPp)
        assertNull(report.perState.getValue(State.INVALID).expectedRatio)
        assertNull(report.perState.getValue(State.PAUSED).measuredRatio)
        val inv = report.excludedShares.getValue(State.INVALID)
        assertEquals(7, inv.expectedS)
        assertEquals(9, inv.measuredS)
        assertEquals((9.0 - 7.0) / n * 100, inv.diffPp!!, 1e-9)
        val paused = report.excludedShares.getValue(State.PAUSED)
        assertEquals(0, paused.expectedS)
        assertEquals(4, paused.measuredS)
        assertEquals(true, report.pass.items.first { it.id == "ratio_error_3pp" }.passed)
    }

    @Test
    fun flappingCountsChangesInsideConstantExpectedRuns() {
        val states = Array(120) { State.PRESENT }
        states[40] = State.AWAY
        states[41] = State.AWAY
        states[80] = State.INVALID
        val gt = GtFile("S-synth", "T1", "scripted", Synth.T0, listOf(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 120, BehaviorCatalog.STUDY_IN_ZONE)))
        val report = diff.diff(gt, Synth.replayResult(Synth.stated(0, *states)))
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
        assertEquals(10, report.seconds.missingRecords)
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
