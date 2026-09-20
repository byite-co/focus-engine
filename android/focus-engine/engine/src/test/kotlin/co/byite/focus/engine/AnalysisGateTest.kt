package co.byite.focus.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Code review item 2: the analysis thread's outcome gate around the stop timeout. */
class AnalysisGateTest {
    @Test
    fun normalFramesPassAndTheGateIsFreeAgain() {
        val g = AnalysisGate()
        val f = assertNotNull(g.begin(1L))
        assertTrue(g.inFlight)
        assertTrue(g.end(f))
        assertFalse(g.inFlight)
        assertEquals(0L, g.suppressed)
        assertEquals(0L, g.cancel(), "nothing in flight: nothing cancelled")
    }

    @Test
    fun aFrameInFlightWhenTheStopTimesOutIsCancelledAndItsOutcomeSuppressed() {
        val g = AnalysisGate()
        val f = assertNotNull(g.begin(1L)) // received has been posted for this frame
        assertEquals(1L, g.cancel(), "exactly the in-flight frame is frames_cancelled_at_stop")
        assertFalse(g.end(f), "its outcome must not be posted")
        assertEquals(1L, g.suppressed)
        assertNull(g.begin(2L), "the gate stays closed for frames arriving afterwards: they post nothing at all")
    }

    @Test
    fun aFrameBeginningAfterTheBumpPostsNothingAndIsNotCounted() {
        val g = AnalysisGate()
        assertEquals(0L, g.cancel())
        assertNull(g.begin(5L))
        assertEquals(0L, g.suppressed)
    }

    @Test
    fun aFrameThatFinishedBeforeTheBumpIsNotCancelled() {
        val g = AnalysisGate()
        val f = assertNotNull(g.begin(1L))
        assertTrue(g.end(f))
        assertEquals(0L, g.cancel())
    }
}
