package co.byite.focus.core.gt

import co.byite.focus.core.Synth
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpectedStateGeneratorTest {
    private val gen = ExpectedStateGenerator(Synth.params)

    private fun gt(vararg intervals: GtInterval, void: List<GtVoid> = emptyList(), scenario: String = "T0") =
        GtFile("S-synth", scenario, "scripted", Synth.T0, intervals.toList(), void)

    private fun iv(start: Long, end: Long, behavior: String, expected: State? = null) = GtInterval(start * 1000, end * 1000, behavior, expected)

    @Test
    fun awayGraceIsPresentForFourSeconds() {
        val r = gen.resolve(gt(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LOOK_AWAY_UNREGISTERED), iv(70, 130, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(State.PRESENT, gen.expectedAt(r, 60_000).expected)
        assertEquals(State.PRESENT, gen.expectedAt(r, 63_999).expected)
        assertEquals(State.AWAY, gen.expectedAt(r, 64_000).expected)
        assertEquals(State.AWAY, gen.expectedAt(r, 69_999).expected)
        assertEquals(State.PRESENT, gen.expectedAt(r, 70_000).expected)
        assertEquals(State.AWAY, r.intervals[1].terminalState)
    }

    @Test
    fun threeSecondLookAwayStaysPresent() {
        val r = gen.resolve(gt(iv(0, 10, BehaviorCatalog.STUDY_IN_ZONE), iv(10, 13, BehaviorCatalog.LOOK_AWAY_UNREGISTERED)))
        assertEquals(State.PRESENT, gen.expectedAt(r, 12_999).expected)
        assertEquals(State.PRESENT, r.intervals[1].terminalState)
    }

    @Test
    fun reactionWindowExcludesThreeSecondsAfterEveryCue() {
        val r = gen.resolve(gt(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LEAVE_SEAT)))
        assertEquals(Exclusion.REACTION_WINDOW, gen.expectedAt(r, 0).exclusion)
        assertEquals(Exclusion.REACTION_WINDOW, gen.expectedAt(r, 2_999).exclusion)
        assertNull(gen.expectedAt(r, 3_000).exclusion)
        assertEquals(Exclusion.REACTION_WINDOW, gen.expectedAt(r, 60_000).exclusion)
        assertEquals(State.ABSENT, gen.expectedAt(r, 60_000).expected)
        assertFalse(gen.expectedAt(r, 62_999).scored)
        assertTrue(gen.expectedAt(r, 63_000).scored)
    }

    @Test
    fun headHiddenLowMotionPausesAt120Seconds() {
        val r = gen.resolve(gt(iv(0, 150, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(150, 200, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(State.INVALID, gen.expectedAt(r, 119_999).expected)
        assertEquals(State.PAUSED, gen.expectedAt(r, 120_000).expected)
        assertEquals(State.PAUSED, gen.expectedAt(r, 149_999).expected)
        assertEquals(State.PRESENT, gen.expectedAt(r, 150_000).expected)
        assertEquals(State.PAUSED, r.intervals[0].terminalState)
    }

    @Test
    fun leaveSeatShorterThanConfirmIsInvalidNotAbsent() {
        val r = gen.resolve(gt(iv(0, 10, BehaviorCatalog.STUDY_IN_ZONE), iv(10, 12, BehaviorCatalog.LEAVE_SEAT), iv(12, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 40, BehaviorCatalog.LEAVE_SEAT)))
        assertEquals(State.INVALID, gen.expectedAt(r, 11_000).expected)
        assertEquals(State.ABSENT, gen.expectedAt(r, 35_000).expected)
    }

    @Test
    fun phoneOnFlatSurfaceIsInvalidForSixtySecondsThenPhone() {
        val r = gen.resolve(gt(iv(0, 90, BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE)))
        assertEquals(State.INVALID, gen.expectedAt(r, 59_999).expected)
        assertEquals(State.PHONE, gen.expectedAt(r, 60_000).expected)
    }

    @Test
    fun voidAndGapsAreExcluded() {
        val r = gen.resolve(gt(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(70, 100, BehaviorCatalog.STUDY_IN_ZONE), void = listOf(GtVoid(20_000, 30_000, "missed cue"))))
        assertEquals(Exclusion.VOID, gen.expectedAt(r, 25_000).exclusion)
        assertEquals(Exclusion.NO_INTERVAL, gen.expectedAt(r, 65_000).exclusion)
        assertEquals(Exclusion.NO_INTERVAL, gen.expectedAt(r, 100_000).exclusion)
        assertEquals(Exclusion.BEFORE_START, gen.expectedAt(r, -1).exclusion)
        assertEquals(Exclusion.UNSCORED_BEHAVIOR, gen.expectedAt(gen.resolve(gt(iv(0, 10, BehaviorCatalog.APP_KILLED))), 5_000).exclusion)
    }

    @Test
    fun timelineIsOneHertzOverTheGtSpan() {
        val r = gen.resolve(gt(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LEAVE_SEAT)))
        val tl = gen.timeline(r)
        assertEquals(70, tl.size)
        assertEquals(0, tl.first().tRelMs)
        assertEquals(69_000, tl.last().tRelMs)
        assertEquals(70 - 6, tl.count { it.scored })
    }

    @Test
    fun unknownBehaviorNeedsAnExplicitState() {
        assertFailsWith<GtFormatException> { gen.resolve(gt(iv(0, 10, "dance"))) }
        val r = gen.resolve(gt(iv(0, 10, "dance", State.AWAY)))
        assertEquals(State.AWAY, gen.expectedAt(r, 5_000).expected)
        assertTrue(r.warnings.single().contains("unknown behavior 'dance'"))
    }

    @Test
    fun explicitStateThatDisagreesWithTheCatalogIsAWarningOnly() {
        val r = gen.resolve(gt(iv(0, 10, BehaviorCatalog.LEAVE_SEAT, State.PRESENT)))
        assertEquals(State.ABSENT, gen.expectedAt(r, 5_000).expected)
        assertTrue(r.warnings.single().contains("differs"))
        assertTrue(gen.resolve(gt(iv(0, 10, BehaviorCatalog.LEAVE_SEAT, State.ABSENT))).warnings.isEmpty())
    }

    @Test
    fun parserValidatesOrderAndOverlap() {
        assertFailsWith<GtFormatException> { GtParser.validate(gt(iv(0, 10, "a"), iv(5, 20, "b"))) }
        assertFailsWith<GtFormatException> { GtParser.validate(gt(iv(10, 10, "a"))) }
        assertFailsWith<GtFormatException> { GtParser.parse("{\"session_id\":\"x\"}") }
        val parsed = GtParser.parse(
            """{"session_id":"s","scenario_id":"T2","gt_type":"scripted","start_cue_t_mono_ms":5,
               "intervals":[{"t_start_ms":0,"t_end_ms":1000,"behavior":"study_in_zone","expected_state":"PRESENT"}],
               "void":[{"t_start_ms":0,"t_end_ms":10,"reason":"x"}]}""",
        )
        assertEquals(State.PRESENT, parsed.intervals[0].expectedState)
        assertEquals(1, parsed.void.size)
    }
}
