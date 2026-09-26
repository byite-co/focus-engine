package co.byite.focus.core.aggregate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The conservation relations of directive D 정정 4·5, as the summary checks them at a normal stop. */
class CounterConsistencyTest {
    private val ok = CounterTotals(
        framesRequested = 100, framesAnalyzerReceived = 96, framesSkippedIntentional = 10, faceInferenceErrors = 1, preFaceErrors = 2,
        framesProcessed = 83, framesSampleApplied = 80, framesSampleLateDropped = 2,
        poseRequested = 12, poseSuperseded = 3, poseCompleted = 8, poseApplied = 7, poseLateDropped = 1, poseErrors = 1,
        processingSlotsExpected = 86, processingSlotsFilled = 83, processingSlotsMissed = 3,
    )

    @Test
    fun aConservedSessionHasNoMismatch() {
        assertEquals(emptyList(), CounterConsistency.check(ok))
        assertEquals(4L, ok.backpressureDrops)
        assertEquals(82L, ok.framesSampleEnqueued)
        assertEquals(1L, ok.framesPostFaceFailed)
        assertEquals(3L, ok.framesUnprocessedUnexpected)
        assertEquals(3.0 / 86, ok.slotMissRatio!!, 1e-12)
        assertFalse(ok.stopIntegrityFailed)
    }

    @Test
    fun aLostSlotBreaksTheSlotRelationBecauseMissedIsNotDerived() {
        // expected − filled would be 3 either way; the counted missed says 2, so one slot was neither filled nor missed
        val lost = ok.copy(processingSlotsMissed = 2)
        val out = CounterConsistency.check(lost)
        assertEquals(1, out.size, out.toString())
        assertTrue(out[0].startsWith("processing_slots_expected 86 ≠ filled 83 + missed 2"), out[0])
        assertTrue(ok.copy(captureResultsAfterClose = 1).stopIntegrityFailed, "a capture result after CLOSE is a stop integrity failure, not a relation mismatch")
        assertEquals(emptyList(), CounterConsistency.check(ok.copy(captureResultsAfterClose = 1)))
    }

    @Test
    fun cancelledAtStopBalancesTheRelations() {
        val t = ok.copy(framesProcessed = 82, poseCompleted = 7, poseApplied = 6)
        assertEquals(2, CounterConsistency.check(t).size)
        assertEquals(emptyList(), CounterConsistency.check(t, framesCancelledAtStop = 1, poseCancelledAtStop = 1))
    }

    @Test
    fun everyBrokenRelationIsNamed() {
        val broken = ok.copy(
            framesAnalyzerReceived = 101, // requested < received
            framesSampleApplied = 90, // enqueued 92 > processed 83
            poseCompleted = 9, // requested 12 ≠ 3 + 9 + 1
            poseLateDropped = 3, // completed 9 ≠ 7 + 3
        )
        val out = CounterConsistency.check(broken)
        assertTrue(out.any { it.startsWith("frames_requested") }, out.toString())
        assertTrue(out.any { it.startsWith("frames_analyzer_received") }, out.toString())
        assertTrue(out.any { it.startsWith("frames_processed") }, out.toString())
        assertTrue(out.any { it.startsWith("pose_requested") }, out.toString())
        assertTrue(out.any { it.startsWith("pose_completed") }, out.toString())
    }
}
