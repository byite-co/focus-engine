package co.byite.focus.engine.pipeline.pose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseGeometryTest {
    private fun arrays(): Triple<DoubleArray, DoubleArray, DoubleArray> =
        Triple(DoubleArray(PoseGeometry.LANDMARK_COUNT), DoubleArray(PoseGeometry.LANDMARK_COUNT), DoubleArray(PoseGeometry.LANDMARK_COUNT))

    @Test
    fun uprightMappingOfTheCornersForEachRotation() {
        assertEquals(0.0 to 0.0, PoseGeometry.toUpright(0.0, 0.0, 0))
        assertEquals(1.0 to 0.0, PoseGeometry.toUpright(0.0, 0.0, 90)) // top-left → top-right under a cw quarter turn
        assertEquals(1.0 to 1.0, PoseGeometry.toUpright(1.0, 0.0, 90))
        assertEquals(0.0 to 1.0, PoseGeometry.toUpright(0.0, 0.0, 270))
        assertEquals(1.0 to 1.0, PoseGeometry.toUpright(0.0, 0.0, 180))
        assertEquals(720 to 1280, PoseGeometry.uprightSize(1280, 720, 90))
        assertEquals(720 to 1280, PoseGeometry.uprightSize(1280, 720, 270))
        assertEquals(1280 to 720, PoseGeometry.uprightSize(1280, 720, 0))
    }

    @Test
    fun shouldderScalarsInUprightPixelsWithTheNoseAsHead() {
        val (x, y, v) = arrays()
        // portrait buffer already upright (rotation 0), 720×1280
        x[PoseGeometry.LEFT_SHOULDER] = 0.75; y[PoseGeometry.LEFT_SHOULDER] = 0.5; v[PoseGeometry.LEFT_SHOULDER] = 0.9
        x[PoseGeometry.RIGHT_SHOULDER] = 0.25; y[PoseGeometry.RIGHT_SHOULDER] = 0.5; v[PoseGeometry.RIGHT_SHOULDER] = 0.7
        x[PoseGeometry.NOSE] = 0.5; y[PoseGeometry.NOSE] = 0.25; v[PoseGeometry.NOSE] = 0.95
        val s = PoseGeometry.compute(x, y, v, 720, 1280, 0)!!
        assertEquals(0.7, s.shoulderVisibilityMin)
        assertEquals(360.0, s.shoulderCenterXPx)
        assertEquals(640.0, s.shoulderCenterYPx)
        assertEquals(360.0, s.shoulderWidthPx)
        assertTrue(s.headLandmarkPresent)
        assertEquals((320.0 - 640.0) / 360.0, s.headOffsetBelowShoulderRatio!!, 1e-12) // nose above the shoulders → negative
        assertEquals(720, s.frameWidthPx)
        assertEquals(1280, s.frameHeightPx)
    }

    @Test
    fun rotatedBufferGivesTheSameUprightGeometry() {
        // landscape 1280×720 buffer that needs a 270° cw turn: upright (ux, uy) = (y, 1 − x)
        val (x, y, v) = arrays()
        fun put(i: Int, ux: Double, uy: Double, vis: Double) { x[i] = 1.0 - uy; y[i] = ux; v[i] = vis }
        put(PoseGeometry.LEFT_SHOULDER, 0.75, 0.5, 0.9)
        put(PoseGeometry.RIGHT_SHOULDER, 0.25, 0.5, 0.8)
        put(PoseGeometry.NOSE, 0.5, 0.25, 0.9)
        val s = PoseGeometry.compute(x, y, v, 1280, 720, 270)!!
        assertEquals(360.0, s.shoulderCenterXPx, 1e-9)
        assertEquals(640.0, s.shoulderCenterYPx, 1e-9)
        assertEquals(360.0, s.shoulderWidthPx, 1e-9)
        assertEquals(720, s.frameWidthPx)
    }

    @Test
    fun earsStandInForAHiddenNoseAndNothingMeansNoHead() {
        val (x, y, v) = arrays()
        x[PoseGeometry.LEFT_SHOULDER] = 0.7; y[PoseGeometry.LEFT_SHOULDER] = 0.6; v[PoseGeometry.LEFT_SHOULDER] = 0.9
        x[PoseGeometry.RIGHT_SHOULDER] = 0.3; y[PoseGeometry.RIGHT_SHOULDER] = 0.6; v[PoseGeometry.RIGHT_SHOULDER] = 0.9
        v[PoseGeometry.NOSE] = 0.2
        x[PoseGeometry.LEFT_EAR] = 0.6; y[PoseGeometry.LEFT_EAR] = 0.8; v[PoseGeometry.LEFT_EAR] = 0.6
        x[PoseGeometry.RIGHT_EAR] = 0.4; y[PoseGeometry.RIGHT_EAR] = 0.7; v[PoseGeometry.RIGHT_EAR] = 0.3
        val s = PoseGeometry.compute(x, y, v, 720, 1280, 0)!!
        assertTrue(s.headLandmarkPresent)
        assertEquals((0.8 * 1280 - 0.6 * 1280) / (0.4 * 720), s.headOffsetBelowShoulderRatio!!, 1e-9) // head below the shoulders → positive
        v[PoseGeometry.LEFT_EAR] = 0.1
        val none = PoseGeometry.compute(x, y, v, 720, 1280, 0)!!
        assertFalse(none.headLandmarkPresent)
        assertNull(none.headOffsetBelowShoulderRatio)
    }

    @Test
    fun coincidingShouldersGiveNull() {
        val (x, y, v) = arrays()
        x[PoseGeometry.LEFT_SHOULDER] = 0.5; y[PoseGeometry.LEFT_SHOULDER] = 0.5
        x[PoseGeometry.RIGHT_SHOULDER] = 0.5; y[PoseGeometry.RIGHT_SHOULDER] = 0.5
        assertNull(PoseGeometry.compute(x, y, v, 720, 1280, 0))
    }
}
