package co.byite.focus.engine.pipeline.face

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadPoseTest {
    /** Column-major 4×4 from a row-major 3×3 rotation, affine bottom row. */
    private fun matrix(r: DoubleArray, tz: Double = -50.0): FloatArray {
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = r[row * 3 + col].toFloat()
        m[12] = 0f; m[13] = 0f; m[14] = tz.toFloat(); m[15] = 1f
        return m
    }

    private fun rx(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c) }
    private fun ry(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c) }
    private fun rz(deg: Double): DoubleArray { val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg)); return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0) }
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; (0 until 3).sumOf { k -> a[r * 3 + k] * b[k * 3 + c] } }
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun identityIsZeroAndAffineCheckReadsTheBottomRow() {
        val a = HeadPose.fromTransform(matrix(identity), 0)
        assertEquals(0.0, a.yawDeg, 1e-9)
        assertEquals(0.0, a.pitchDeg, 1e-9)
        assertEquals(0.0, a.rollDeg, 1e-9)
        assertTrue(HeadPose.looksAffine(matrix(identity)))
        val rowMajor = matrix(identity).also { it[3] = -50f; it[14] = 0f }
        assertFalse(HeadPose.looksAffine(rowMajor))
    }

    @Test
    fun yawIsRightHandedAboutUp() {
        // nose turns toward +X (image right) → positive yaw
        val a = HeadPose.fromTransform(matrix(ry(20.0)), 0)
        assertEquals(20.0, a.yawDeg, 1e-6)
        assertEquals(0.0, a.pitchDeg, 1e-6)
        assertEquals(0.0, a.rollDeg, 1e-6)
    }

    @Test
    fun pitchIsElevationPositiveUp() {
        // Rx(−10°) moves the nose (0,0,1) to (0, +sin10, cos10): looking up
        val up = HeadPose.fromTransform(matrix(rx(-10.0)), 0)
        assertEquals(10.0, up.pitchDeg, 1e-6)
        val down = HeadPose.fromTransform(matrix(rx(10.0)), 0)
        assertEquals(-10.0, down.pitchDeg, 1e-6)
    }

    @Test
    fun rollIsRightHandedAboutTheCameraAxis() {
        // Rz(15°) moves the head top (0,1,0) to (−sin15, cos15, 0): toward image left → positive
        val a = HeadPose.fromTransform(matrix(rz(15.0)), 0)
        assertEquals(15.0, a.rollDeg, 1e-6)
        assertEquals(0.0, a.yawDeg, 1e-6)
    }

    @Test
    fun bufferRotationIsCompensatedForEveryQuarterTurn() {
        val upright = mul(ry(25.0), rx(-8.0))
        val expected = HeadPose.anglesOf(upright)
        for (theta in listOf(0, 90, 180, 270, -90, 450)) {
            // the buffer frame sees R_b = Rz(+θ) · R_upright (inverse of the compensation)
            val buffer = mul(rz(theta.toDouble()), upright)
            val a = HeadPose.fromTransform(matrix(buffer), theta)
            assertEquals(expected.yawDeg, a.yawDeg, 1e-6, "theta=$theta yaw")
            assertEquals(expected.pitchDeg, a.pitchDeg, 1e-6, "theta=$theta pitch")
            assertEquals(expected.rollDeg, a.rollDeg, 1e-6, "theta=$theta roll")
        }
        // without compensation a 90° buffer shows the pitch as yaw-ish garbage: sanity that the test is meaningful
        val wrong = HeadPose.anglesOf(mul(rz(90.0), upright))
        assertTrue(kotlin.math.abs(wrong.rollDeg - expected.rollDeg) > 60.0)
    }

    @Test
    fun rotationOfReadsColumnMajor() {
        val r = ry(30.0)
        val back = HeadPose.rotationOf(matrix(r))
        for (i in 0 until 9) assertEquals(r[i], back[i], 1e-6)
    }
}
