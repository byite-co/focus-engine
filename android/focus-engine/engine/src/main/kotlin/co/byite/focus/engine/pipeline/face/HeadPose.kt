package co.byite.focus.engine.pipeline.face

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Head pose in degrees. Sign convention: see [HeadPose]. */
data class HeadAngles(val yawDeg: Double, val pitchDeg: Double, val rollDeg: Double)

/**
 * yaw / pitch / roll from MediaPipe's facial transformation matrix (README "yaw·pitch·roll 부호 규약").
 *
 * The matrix is the column-major 4×4 `MatrixData` of the face geometry module: `m[c*4 + r]` is row `r`,
 * column `c`. Its 3×3 block R maps the canonical face frame into the camera frame of the image the
 * landmarks are expressed in (X right, Y up, Z toward the camera; a face looking into the lens has
 * R ≈ I). The analyzer passes the buffer rotation through `ImageProcessingOptions` instead of rotating
 * pixels, so landmarks and R come back in the *un-rotated buffer* frame. [rotationDegrees]
 * (`ImageInfo.rotationDegrees`, clockwise) is compensated here: R_upright = Rz(−θ) · R.
 *
 * From R_upright with f = R·(0,0,1) (nose direction) and u = R·(0,1,0) (top of the head):
 * - yaw   = atan2(f_x, f_z). Right-hand rotation about +Y. Positive = nose toward the image's right side,
 *           i.e. the user turns toward their own left (the analysis image is not mirrored).
 * - pitch = atan2(f_y, √(f_x² + f_z²)) (elevation). Positive = looking up, negative = looking down.
 * - roll  = atan2(−u_x, u_y). Right-hand rotation about +Z (toward the camera). Positive = the top of the
 *           head tilts toward the image's left (counter-clockwise for a viewer facing the user).
 */
object HeadPose {
    fun fromTransform(m: FloatArray, rotationDegrees: Int): HeadAngles {
        require(m.size == 16) { "transformation matrix must have 16 elements, got ${m.size}" }
        return anglesOf(compensate(rotationOf(m), rotationDegrees))
    }

    /** Row-major 3×3 rotation block of the column-major 4×4 [m]. */
    fun rotationOf(m: FloatArray): DoubleArray {
        val r = DoubleArray(9)
        for (row in 0 until 3) for (col in 0 until 3) r[row * 3 + col] = m[col * 4 + row].toDouble()
        return r
    }

    /** Rz(−θ) · [r] for θ a multiple of 90° (exact, no trigonometric rounding). */
    fun compensate(r: DoubleArray, rotationDegrees: Int): DoubleArray {
        val theta = ((rotationDegrees % 360) + 360) % 360
        require(theta % 90 == 0) { "rotation must be a multiple of 90°, got $rotationDegrees" }
        // cos(−θ), sin(−θ)
        val (c, s) = when (theta) {
            0 -> 1.0 to 0.0
            90 -> 0.0 to -1.0
            180 -> -1.0 to 0.0
            else -> 0.0 to 1.0
        }
        // Rz(φ) = [[c, −s, 0], [s, c, 0], [0, 0, 1]] with φ = −θ
        val out = DoubleArray(9)
        for (col in 0 until 3) {
            val r0 = r[0 * 3 + col]
            val r1 = r[1 * 3 + col]
            out[0 * 3 + col] = c * r0 - s * r1
            out[1 * 3 + col] = s * r0 + c * r1
            out[2 * 3 + col] = r[2 * 3 + col]
        }
        return out
    }

    fun anglesOf(r: DoubleArray): HeadAngles {
        val fx = r[0 * 3 + 2]
        val fy = r[1 * 3 + 2]
        val fz = r[2 * 3 + 2]
        val ux = r[0 * 3 + 1]
        val uy = r[1 * 3 + 1]
        val yaw = Math.toDegrees(atan2(fx, fz))
        val pitch = Math.toDegrees(atan2(fy, sqrt(fx * fx + fz * fz)))
        val roll = Math.toDegrees(atan2(-ux, uy))
        return HeadAngles(yaw, pitch, roll)
    }

    /** True when the 4th row is (0, 0, 0, 1) — the column-major affine layout this code assumes. */
    fun looksAffine(m: FloatArray, eps: Double = 1e-3): Boolean =
        m.size == 16 && abs(m[3].toDouble()) < eps && abs(m[7].toDouble()) < eps && abs(m[11].toDouble()) < eps && abs(m[15].toDouble() - 1.0) < eps
}
