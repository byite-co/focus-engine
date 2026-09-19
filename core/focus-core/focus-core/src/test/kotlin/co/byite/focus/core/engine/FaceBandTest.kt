package co.byite.focus.core.engine

import co.byite.focus.core.Synth
import kotlin.test.Test
import kotlin.test.assertEquals

/** v0.2.1 판정 12: two thresholds, three bands. */
class FaceBandTest {
    @Test
    fun bandsAtTheDefaultThresholds() {
        assertEquals(FaceBand.MISSING, FaceBand.of(0.0, Synth.params))
        assertEquals(FaceBand.MISSING, FaceBand.of(0.199, Synth.params))
        assertEquals(FaceBand.UNSTABLE, FaceBand.of(0.2, Synth.params))
        assertEquals(FaceBand.UNSTABLE, FaceBand.of(0.35, Synth.params))
        assertEquals(FaceBand.UNSTABLE, FaceBand.of(0.499, Synth.params))
        assertEquals(FaceBand.PRESENT, FaceBand.of(0.5, Synth.params))
        assertEquals(FaceBand.PRESENT, FaceBand.of(1.0, Synth.params))
    }

    @Test
    fun thresholdsComeFromParameters() {
        val p = Synth.params.copy(faceMissingMaxRatio = 0.1, facePresentMinRatio = 0.9)
        assertEquals(FaceBand.UNSTABLE, FaceBand.of(0.2, p))
        assertEquals(FaceBand.UNSTABLE, FaceBand.of(0.5, p))
        assertEquals(FaceBand.PRESENT, FaceBand.of(0.9, p))
        assertEquals(FaceBand.MISSING, FaceBand.of(0.09, p))
    }
}
