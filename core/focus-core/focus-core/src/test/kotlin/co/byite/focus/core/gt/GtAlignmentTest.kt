package co.byite.focus.core.gt

import co.byite.focus.core.Synth
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** v0.2.1 판정 7 (2차): scripted cues sit on the session bucket grid; observed cues are rounded to it. */
class GtAlignmentTest {
    private fun iv(startMs: Long, endMs: Long, behavior: String) = GtInterval(startMs, endMs, behavior)
    private fun gt(type: String, cue: Long, vararg intervals: GtInterval, void: List<GtVoid> = emptyList()) =
        GtFile("S-synth", "T1", type, cue, intervals.toList(), void)

    @Test
    fun scriptedGtMustStartAtTheSessionStart() {
        val ok = gt("scripted", Synth.T0, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE))
        val aligned = GtParser.alignToSession(ok, Synth.T0)
        assertEquals(ok, aligned.file)
        assertTrue(aligned.warnings.isEmpty())
        val e = assertFailsWith<GtFormatException> { GtParser.alignToSession(gt("scripted", Synth.T0 + 500, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE)), Synth.T0) }
        assertTrue(e.message!!.contains("start_cue_t_mono_ms"), e.message)
    }

    @Test
    fun scriptedCuesOffTheBucketGridAreRejectedByTheParser() {
        val e = assertFailsWith<GtFormatException> { GtParser.validate(gt("scripted", Synth.T0, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE), iv(30_000, 40_500, BehaviorCatalog.LEAVE_SEAT))) }
        assertTrue(e.message!!.contains("bucket grid"), e.message)
        assertFailsWith<GtFormatException> { GtParser.validate(gt("scripted", Synth.T0, iv(250, 30_000, BehaviorCatalog.STUDY_IN_ZONE))) }
        assertFailsWith<GtFormatException> {
            GtParser.parse("""{"session_id":"s","scenario_id":"T2","gt_type":"scripted","start_cue_t_mono_ms":0,"intervals":[{"t_start_ms":0,"t_end_ms":1500,"behavior":"study_in_zone"}]}""")
        }
        // observed GT is accepted off-grid by the parser; alignment rounds it later
        GtParser.validate(gt("observed", Synth.T0 + 400, iv(0, 30_300, BehaviorCatalog.STUDY_IN_ZONE)))
    }

    @Test
    fun observedGtIsRoundedToTheNearestBoundaryWithWarnings() {
        val observed = gt(
            "observed", Synth.T0 + 400,
            iv(0, 30_300, BehaviorCatalog.STUDY_IN_ZONE), iv(30_300, 60_000, BehaviorCatalog.LEAVE_SEAT),
            void = listOf(GtVoid(10_100, 12_700, "x")),
        )
        val aligned = GtParser.alignToSession(observed, Synth.T0)
        assertEquals(Synth.T0, aligned.file.startCueTMonoMs)
        assertEquals(listOf(0L to 31_000L, 31_000L to 60_000L), aligned.file.intervals.map { it.tStartMs to it.tEndMs })
        assertEquals(listOf(11_000L to 13_000L), aligned.file.void.map { it.tStartMs to it.tEndMs })
        assertEquals(4, aligned.warnings.size)
        assertTrue(aligned.warnings[0].contains("start cue"), aligned.warnings[0])
        // a cue 600 ms after the boundary rounds up
        val up = GtParser.alignToSession(gt("observed", Synth.T0 + 600, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE)), Synth.T0)
        assertEquals(Synth.T0 + 1_000, up.file.startCueTMonoMs)
        // a cue before the session start still snaps to the grid
        val before = GtParser.alignToSession(gt("observed", Synth.T0 - 1_400, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE)), Synth.T0)
        assertEquals(Synth.T0 - 1_000, before.file.startCueTMonoMs)
    }

    @Test
    fun observedRoundingThatCollapsesAnIntervalIsAnError() {
        val tiny = gt("observed", Synth.T0, iv(0, 30_000, BehaviorCatalog.STUDY_IN_ZONE), iv(30_000, 30_300, BehaviorCatalog.LEAVE_SEAT))
        val e = assertFailsWith<GtFormatException> { GtParser.alignToSession(tiny, Synth.T0) }
        assertTrue(e.message!!.contains("after rounding"), e.message)
    }

    @Test
    fun diffAlignsObservedGtAndReportsTheWarnings() {
        val observed = gt("observed", Synth.T0 + 400, iv(0, 30_300, BehaviorCatalog.STUDY_IN_ZONE), iv(30_300, 60_000, BehaviorCatalog.LEAVE_SEAT))
        val states = Array(60) { if (it < 31) State.PRESENT else State.ABSENT }
        val report = GtDiff(Synth.params).diff(observed, Synth.replayResult(Synth.stated(0, *states)))
        assertTrue(report.warnings.any { it.contains("rounded") })
        assertEquals(1.0, report.perState.getValue(State.ABSENT).recall)
        assertEquals("observed", report.gtType)
        // a scripted GT that does not start at the session start fails the diff
        assertFailsWith<GtFormatException> {
            GtDiff(Synth.params).diff(gt("scripted", Synth.T0 + 1_000, iv(0, 60_000, BehaviorCatalog.STUDY_IN_ZONE)), Synth.replayResult(Synth.stated(0, *states)))
        }
    }
}
