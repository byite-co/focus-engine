package co.byite.focus.engine.pipeline.face

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RigidJitterTest {
    private val n = RigidJitter.SUBSET.size
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** Deterministic pseudo-random cloud in camera space (px). */
    private val cloud: List<DoubleArray> = List(n) { i ->
        val a = i * 1.7
        doubleArrayOf(640 + 80 * cos(a), 360 + 60 * sin(a * 0.7), -30 + 25 * sin(a * 1.3))
    }

    private fun ry(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c) }

    /** Rotate the camera-space cloud by [r] and express it as buffer landmarks (x px, y px down, z px toward −camera). */
    private fun landmarks(r: DoubleArray, jitterIndex: Int = -1, jitterPx: Double = 0.0): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
        for (i in 0 until n) {
            val p = cloud[i]
            val cx = r[0] * p[0] + r[1] * p[1] + r[2] * p[2]
            val cy = r[3] * p[0] + r[4] * p[1] + r[5] * p[2]
            val cz = r[6] * p[0] + r[7] * p[1] + r[8] * p[2]
            xs[i] = cx + if (i == jitterIndex) jitterPx else 0.0
            ys[i] = -cy
            zs[i] = -cz
        }
        return Triple(xs, ys, zs)
    }

    @Test
    fun rigidHeadRotationLeavesNoResidual() {
        val j = RigidJitter()
        val (x0, y0, z0) = landmarks(identity)
        assertNull(j.next(RigidJitter.toCanonical(x0, y0, z0, identity, 200.0), 1_000_000_000L), "first frame has no reference")
        val r = ry(20.0)
        val (x1, y1, z1) = landmarks(r)
        val res = j.next(RigidJitter.toCanonical(x1, y1, z1, r, 200.0), 1_041_000_000L)
        assertNotNull(res)
        assertEquals(0.0, res, 1e-9)
    }

    @Test
    fun nonRigidDisplacementShowsUpAsRmsOverTheSubsetNormalisedByWidth() {
        val j = RigidJitter()
        val (x0, y0, z0) = landmarks(identity)
        j.next(RigidJitter.toCanonical(x0, y0, z0, identity, 200.0), 1_000_000_000L)
        val (x1, y1, z1) = landmarks(identity, jitterIndex = 3, jitterPx = 4.0)
        val res = j.next(RigidJitter.toCanonical(x1, y1, z1, identity, 200.0), 1_041_000_000L)!!
        // moving one point by d shifts the centroid by d/n: residual² = ((d − d/n)² + (n−1)(d/n)²) / n
        val d = 4.0 / 200.0
        val expected = sqrt(((d - d / n) * (d - d / n) + (n - 1) * (d / n) * (d / n)) / n)
        assertEquals(expected, res, 1e-12)
    }

    @Test
    fun aLongGapOrResetDropsTheReference() {
        val j = RigidJitter(maxFrameGapNs = 500_000_000L)
        val (x0, y0, z0) = landmarks(identity)
        val c = RigidJitter.toCanonical(x0, y0, z0, identity, 200.0)
        j.next(c, 1_000_000_000L)
        assertNull(j.next(c, 1_600_000_000L), "gap over 500 ms")
        assertNotNull(j.next(c, 1_641_000_000L))
        j.reset()
        assertNull(j.next(c, 1_682_000_000L))
    }

    @Test
    fun subsetIndicesAreValidFaceMeshIndices() {
        assertEquals(14, n)
        assertTrue(RigidJitter.SUBSET.all { it in 0 until 478 })
        assertEquals(n, RigidJitter.SUBSET.toSet().size)
    }
}
