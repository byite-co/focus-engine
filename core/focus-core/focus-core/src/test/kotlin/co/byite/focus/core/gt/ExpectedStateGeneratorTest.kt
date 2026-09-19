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

    /** Expected state of bucket number [bucket] (1-based) inside the interval starting at [startSec]. */
    private fun bucket(r: ResolvedGt, startSec: Long, bucket: Int): State? = gen.expectedAt(r, startSec * 1000 + (bucket - 1) * 1000L).expected

    @Test
    fun awayGraceIsPresentForFourBucketsAndAwayFromTheFifth() {
        val r = gen.resolve(gt(iv(0, 60, BehaviorCatalog.STUDY_IN_ZONE), iv(60, 70, BehaviorCatalog.LOOK_AWAY_UNREGISTERED), iv(70, 130, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(State.PRESENT, bucket(r, 60, 1))
        assertEquals(State.PRESENT, bucket(r, 60, 4))
        assertEquals(State.AWAY, bucket(r, 60, 5))
        assertEquals(State.AWAY, bucket(r, 60, 10))
        assertEquals(State.PRESENT, gen.expectedAt(r, 63_999).expected)
        assertEquals(State.AWAY, gen.expectedAt(r, 64_000).expected)
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
        assertEquals(3_000, r.intervals[1].leadExclusionMs)
    }

    @Test
    fun headHiddenLowMotionIsInvalidFor120BucketsAndPausedFromThe121st() {
        val r = gen.resolve(gt(iv(0, 150, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(150, 200, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(State.INVALID, bucket(r, 0, 1))
        assertEquals(State.INVALID, bucket(r, 0, 120))
        assertEquals(State.PAUSED, bucket(r, 0, 121))
        assertEquals(State.PAUSED, bucket(r, 0, 150))
        assertEquals(State.PRESENT, bucket(r, 150, 1))
        assertEquals(State.PAUSED, r.intervals[0].terminalState)
    }

    @Test
    fun cueEndingAPausedIntervalExcludesSixSeconds() {
        val r = gen.resolve(gt(iv(0, 150, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(150, 200, BehaviorCatalog.STUDY_IN_ZONE), iv(200, 210, BehaviorCatalog.LEAVE_SEAT)))
        assertEquals(6_000, r.intervals[1].leadExclusionMs)
        for (s in 150L..155L) assertEquals(Exclusion.REACTION_WINDOW, gen.expectedAt(r, s * 1000).exclusion, "t=$s")
        assertTrue(gen.expectedAt(r, 156_000).scored)
        assertEquals(State.PRESENT, gen.expectedAt(r, 156_000).expected)
        // the next cue is back to the plain 3 s
        assertEquals(3_000, r.intervals[2].leadExclusionMs)
        assertTrue(gen.expectedAt(r, 203_000).scored)
        // a PAUSED-terminal interval that is too short to reach PAUSED does not extend the window
        val short = gen.resolve(gt(iv(0, 30, BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME), iv(30, 60, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(3_000, short.intervals[1].leadExclusionMs)
    }

    @Test
    fun leaveSeatShorterThanConfirmIsInvalidNotAbsent() {
        val r = gen.resolve(gt(iv(0, 10, BehaviorCatalog.STUDY_IN_ZONE), iv(10, 12, BehaviorCatalog.LEAVE_SEAT), iv(12, 30, BehaviorCatalog.STUDY_IN_ZONE), iv(30, 40, BehaviorCatalog.LEAVE_SEAT)))
        assertEquals(State.INVALID, gen.expectedAt(r, 11_000).expected)
        assertEquals(State.ABSENT, gen.expectedAt(r, 35_000).expected)
        // exactly three buckets confirm
        val three = gen.resolve(gt(iv(0, 10, BehaviorCatalog.STUDY_IN_ZONE), iv(10, 13, BehaviorCatalog.LEAVE_SEAT)))
        assertEquals(State.ABSENT, gen.expectedAt(three, 10_000).expected)
    }

    @Test
    fun phoneOnFlatSurfaceIsInvalidForSixtyBucketsThenPhone() {
        val r = gen.resolve(gt(iv(0, 90, BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE)))
        assertEquals(State.INVALID, bucket(r, 0, 60))
        assertEquals(State.PHONE, bucket(r, 0, 61))
    }

    @Test
    fun shortRecalibratingIntervalIsAWarning() {
        val short = gen.resolve(gt(iv(0, 30, BehaviorCatalog.PHONE_PICKUP_USE), iv(30, 50, BehaviorCatalog.PHONE_REDOCK_RECALIBRATING), iv(50, 80, BehaviorCatalog.STUDY_IN_ZONE)))
        assertEquals(1, short.warnings.size)
        assertTrue(short.warnings[0].contains("22000 ms"), short.warnings[0])
        val ok = gen.resolve(gt(iv(0, 30, BehaviorCatalog.PHONE_PICKUP_USE), iv(30, 52, BehaviorCatalog.PHONE_REDOCK_RECALIBRATING), iv(52, 80, BehaviorCatalog.STUDY_IN_ZONE)))
        assertTrue(ok.warnings.isEmpty())
        assertEquals(State.INVALID, gen.expectedAt(ok, 40_000).expected)
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
