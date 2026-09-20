package co.byite.focus.engine.pipeline.face

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** j = similarity-registration residual ÷ inter-ocular distance; fast head rotation pairs excluded (CHANGELOG v0.2.2 (b)). */
class RigidJitterTest {
    private val n = RigidJitter.SUBSET.size
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private val interocular = 60.0

    /** Deterministic non-coplanar cloud in pixels (3N interleaved). */
    private val cloud: DoubleArray = DoubleArray(3 * n).also { c ->
        for (i in 0 until n) {
            val a = i * 1.7
            c[3 * i] = 640 + 80 * cos(a)
            c[3 * i + 1] = 360 + 60 * sin(a * 0.7)
            c[3 * i + 2] = -30 + 25 * sin(a * 1.3) + 10 * cos(a * 2.1)
        }
    }

    private fun rx(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c) }
    private fun ry(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c) }
    private fun rz(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0) }
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; (0 until 3).sumOf { k -> a[r * 3 + k] * b[k * 3 + c] } }

    /** s·R·p + t applied to every point of [p]. */
    private fun similarity(p: DoubleArray, r: DoubleArray, s: Double, t: DoubleArray): DoubleArray = DoubleArray(p.size).also { out ->
        for (i in 0 until p.size / 3) {
            val x = p[3 * i]; val y = p[3 * i + 1]; val z = p[3 * i + 2]
            out[3 * i] = s * (r[0] * x + r[1] * y + r[2] * z) + t[0]
            out[3 * i + 1] = s * (r[3] * x + r[4] * y + r[5] * z) + t[1]
            out[3 * i + 2] = s * (r[6] * x + r[7] * y + r[8] * z) + t[2]
        }
    }

    /** Deterministic per-landmark noise (independent pseudo-random offsets). */
    private fun noisy(p: DoubleArray, amplitude: Double): DoubleArray = DoubleArray(p.size) { i ->
        var h = (i + 1) * 2654435761L
        h = h xor (h ushr 13); h *= 0x5bd1e995L; h = h xor (h ushr 15)
        p[i] + amplitude * (((h and 0xFFFF).toDouble() / 0xFFFF) * 2 - 1)
    }

    @Test
    fun pureSimilarityMotionLeavesNoResidual() {
        val r = mul(mul(ry(20.0), rx(-8.0)), rz(5.0))
        val moved = similarity(cloud, r, 1.1, doubleArrayOf(30.0, -20.0, 10.0))
        assertEquals(0.0, RigidJitter.similarityResidualRms(cloud, moved), 1e-8)
        // translation and scale only
        assertEquals(0.0, RigidJitter.similarityResidualRms(cloud, similarity(cloud, identity, 0.8, doubleArrayOf(-5.0, 7.0, 0.0))), 1e-8)
        // through the class, with a slow head turn (1° in 41 ms ≈ 24°/s, under the 30°/s limit)
        val j = RigidJitter()
        assertNull(j.next(cloud, identity, interocular, 1_000_000_000L), "first frame has no reference")
        val res = j.next(moved, ry(1.0), interocular, 1_041_000_000L)
        assertNotNull(res)
        assertEquals(0.0, res, 1e-9)
    }

    @Test
    fun independentLandmarkNoiseGivesPositiveJitter() {
        val j = RigidJitter()
        j.next(cloud, identity, interocular, 1_000_000_000L)
        val res = j.next(noisy(cloud, 2.0), identity, interocular, 1_041_000_000L)!!
        assertTrue(res > 0.005, "j=$res")
        assertTrue(res < 2.0 / interocular * 2, "residual is bounded by the noise amplitude: j=$res")
        // and it scales with the amplitude
        val j2 = RigidJitter()
        j2.next(cloud, identity, interocular, 1_000_000_000L)
        val big = j2.next(noisy(cloud, 6.0), identity, interocular, 1_041_000_000L)!!
        assertTrue(big > res * 2, "big=$big small=$res")
    }

    @Test
    fun jitterIsNormalisedByTheInterocularDistance() {
        val a = RigidJitter().also { it.next(cloud, identity, 60.0, 1_000_000_000L) }.next(noisy(cloud, 2.0), identity, 60.0, 1_041_000_000L)!!
        val b = RigidJitter().also { it.next(cloud, identity, 120.0, 1_000_000_000L) }.next(noisy(cloud, 2.0), identity, 120.0, 1_041_000_000L)!!
        assertEquals(a / 2, b, 1e-12)
    }

    @Test
    fun fastHeadRotationPairsAreExcluded() {
        val j = RigidJitter()
        j.next(cloud, identity, interocular, 1_000_000_000L)
        // 5° in 41 ms ≈ 122°/s → excluded, counted, but the frame still becomes the reference
        assertNull(j.next(cloud, ry(5.0), interocular, 1_041_000_000L))
        assertEquals(1L, j.skippedFastRotation)
        // next pair: 1° more in 41 ms ≈ 24°/s → measured against the previous (fast) frame
        assertNotNull(j.next(cloud, ry(6.0), interocular, 1_082_000_000L))
        // exactly 30°/s is allowed (1.23° in 41 ms)
        assertNotNull(j.next(cloud, ry(6.0 + 1.229), interocular, 1_123_000_000L))
    }

    @Test
    fun aLongGapOrResetDropsTheReference() {
        val j = RigidJitter(maxFrameGapNs = 500_000_000L)
        j.next(cloud, identity, interocular, 1_000_000_000L)
        assertNull(j.next(cloud, identity, interocular, 1_600_000_000L), "gap over 500 ms")
        assertNotNull(j.next(cloud, identity, interocular, 1_641_000_000L))
        j.reset()
        assertNull(j.next(cloud, identity, interocular, 1_682_000_000L))
        assertNull(j.next(cloud, identity, 0.0, 1_723_000_000L), "no inter-ocular distance")
    }

    @Test
    fun rotationAngleBetweenMatrices() {
        assertEquals(0.0, RigidJitter.rotationAngleDeg(identity, identity), 1e-9)
        assertEquals(20.0, RigidJitter.rotationAngleDeg(identity, ry(20.0)), 1e-9)
        assertEquals(20.0, RigidJitter.rotationAngleDeg(ry(10.0), ry(30.0)), 1e-9)
        assertEquals(15.0, RigidJitter.rotationAngleDeg(mul(ry(40.0), rx(10.0)), mul(mul(ry(40.0), rx(10.0)), rz(15.0))), 1e-9)
    }

    @Test
    fun largestEigenvectorOfASymmetricMatrix() {
        // diag(1, 5, 3) → e2
        val v = RigidJitter.largestEigenvector(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 3.0), 3)
        assertEquals(1.0, kotlin.math.abs(v[1]), 1e-9)
        assertEquals(0.0, v[0], 1e-9)
    }

    @Test
    fun subsetIndicesAreValidFaceMeshIndices() {
        assertEquals(14, n)
        assertTrue(RigidJitter.SUBSET.all { it in 0 until 478 })
        assertEquals(n, RigidJitter.SUBSET.toSet().size)
    }
}
