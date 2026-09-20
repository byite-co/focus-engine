package co.byite.focus.engine.pipeline.face

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Rigid-residual jitter j (spec 6장 품질 proxy; definition fixed by CHANGELOG v0.2.2 (b)).
 *
 * The rigid subset of the face mesh ([SUBSET]: nose bridge, eye corners, forehead) is taken as 3-D
 * points in buffer pixels (x·W, y·H, z·W). For two consecutive face frames the subset of the previous
 * frame is registered onto the current one with the least-squares similarity transform
 * (scale + rotation + translation, Horn's closed-form quaternion solution), and j is the RMS of the
 * remaining residual divided by the current frame's inter-ocular distance (pixel distance between the
 * eye centres, each the midpoint of its outer and inner corner). Rigid head motion — including the
 * distance change that scales the face — is absorbed by the similarity transform; what remains is
 * non-rigid deformation plus landmark noise. j is unitless (fraction of the inter-ocular distance).
 *
 * A frame pair is excluded (j = null) when the head turned faster than [maxAngularSpeedDegPerS]
 * between the two frames (angle of the relative rotation of the transformation matrices ÷ Δt),
 * when the previous face frame is older than [maxFrameGapNs], or right after a face reacquisition.
 */
class RigidJitter(
    private val maxFrameGapNs: Long = DEFAULT_MAX_FRAME_GAP_NS,
    private val maxAngularSpeedDegPerS: Double = DEFAULT_MAX_ANGULAR_SPEED_DEG_PER_S,
) {
    private var prevPoints: DoubleArray? = null
    private var prevRotation: DoubleArray? = null
    private var prevTsNs = 0L

    /** Pairs skipped because the head turned faster than the limit. */
    var skippedFastRotation: Long = 0L
        private set

    fun reset() {
        prevPoints = null
        prevRotation = null
    }

    /**
     * @param points subset as 3N interleaved (x, y, z) buffer pixels
     * @param rotation row-major 3×3 rotation block of the transformation matrix (same frame convention every call)
     * @param interocularPx inter-ocular distance of this frame in pixels
     * @param captureNs capture timestamp (monotonic ns)
     * @return j against the previous face frame, or null when there is no usable reference. The frame always becomes the new reference.
     */
    fun next(points: DoubleArray, rotation: DoubleArray, interocularPx: Double, captureNs: Long): Double? {
        require(points.size % 3 == 0 && rotation.size == 9) { "bad input sizes" }
        val p = prevPoints
        val r = prevRotation
        val gap = captureNs - prevTsNs
        prevPoints = points.copyOf()
        prevRotation = rotation.copyOf()
        prevTsNs = captureNs
        if (p == null || r == null || p.size != points.size || gap <= 0L || gap > maxFrameGapNs || interocularPx <= 0.0) return null
        val degPerS = rotationAngleDeg(r, rotation) / (gap / 1e9)
        if (degPerS > maxAngularSpeedDegPerS) {
            skippedFastRotation++
            return null
        }
        return similarityResidualRms(p, points) / interocularPx
    }

    companion object {
        const val DEFAULT_MAX_FRAME_GAP_NS: Long = 500_000_000L
        const val DEFAULT_MAX_ANGULAR_SPEED_DEG_PER_S: Double = 30.0

        /**
         * Face mesh indices of the rigid subset: nose bridge (168, 6, 197, 195, 5), eye corners
         * (33 left outer, 133 left inner, 362 right inner, 263 right outer), forehead (10, 151, 9, 108, 337).
         */
        val SUBSET: IntArray = intArrayOf(168, 6, 197, 195, 5, 33, 133, 362, 263, 10, 151, 9, 108, 337)

        const val LEFT_EYE_OUTER = 33
        const val LEFT_EYE_INNER = 133
        const val RIGHT_EYE_INNER = 362
        const val RIGHT_EYE_OUTER = 263

        /** Angle in degrees of the relative rotation aᵀ·b between two row-major 3×3 rotation matrices. */
        fun rotationAngleDeg(a: DoubleArray, b: DoubleArray): Double {
            // trace(aᵀ b) = Σ_ij a_ij b_ij
            var trace = 0.0
            for (i in 0 until 9) trace += a[i] * b[i]
            val c = ((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(c))
        }

        /**
         * RMS residual (same units as the input) after the least-squares similarity transform that maps
         * point set [p] onto [q] (both 3N interleaved, N ≥ 3): min over s, R, t of Σ|s·R·p_i + t − q_i|².
         * Rotation by Horn (1987): the unit quaternion is the eigenvector of the largest eigenvalue of the
         * 4×4 matrix built from the cross-covariance; scale by the closed-form least-squares value.
         */
        fun similarityResidualRms(p: DoubleArray, q: DoubleArray): Double {
            require(p.size == q.size && p.size % 3 == 0 && p.size >= 9) { "need the same N ≥ 3 points" }
            val n = p.size / 3
            var pcx = 0.0; var pcy = 0.0; var pcz = 0.0; var qcx = 0.0; var qcy = 0.0; var qcz = 0.0
            for (i in 0 until n) {
                pcx += p[3 * i]; pcy += p[3 * i + 1]; pcz += p[3 * i + 2]
                qcx += q[3 * i]; qcy += q[3 * i + 1]; qcz += q[3 * i + 2]
            }
            pcx /= n; pcy /= n; pcz /= n; qcx /= n; qcy /= n; qcz /= n
            // cross-covariance S = Σ p_i q_iᵀ on centred points, and Σ|p_i|²
            val s = DoubleArray(9)
            var pp = 0.0
            for (i in 0 until n) {
                val px = p[3 * i] - pcx; val py = p[3 * i + 1] - pcy; val pz = p[3 * i + 2] - pcz
                val qx = q[3 * i] - qcx; val qy = q[3 * i + 1] - qcy; val qz = q[3 * i + 2] - qcz
                s[0] += px * qx; s[1] += px * qy; s[2] += px * qz
                s[3] += py * qx; s[4] += py * qy; s[5] += py * qz
                s[6] += pz * qx; s[7] += pz * qy; s[8] += pz * qz
                pp += px * px + py * py + pz * pz
            }
            if (pp < 1e-18) return 0.0
            val sxx = s[0]; val sxy = s[1]; val sxz = s[2]
            val syx = s[3]; val syy = s[4]; val syz = s[5]
            val szx = s[6]; val szy = s[7]; val szz = s[8]
            val nMat = doubleArrayOf(
                sxx + syy + szz, syz - szy, szx - sxz, sxy - syx,
                syz - szy, sxx - syy - szz, sxy + syx, szx + sxz,
                szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy,
                sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz,
            )
            val quat = largestEigenvector(nMat, 4)
            val w = quat[0]; val x = quat[1]; val y = quat[2]; val z = quat[3]
            val rot = doubleArrayOf(
                w * w + x * x - y * y - z * z, 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), w * w - x * x + y * y - z * z, 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), w * w - x * x - y * y + z * z,
            )
            // scale: s = Σ (R p_i)·q_i / Σ|p_i|²
            var dot = 0.0
            for (i in 0 until n) {
                val px = p[3 * i] - pcx; val py = p[3 * i + 1] - pcy; val pz = p[3 * i + 2] - pcz
                val rx = rot[0] * px + rot[1] * py + rot[2] * pz
                val ry = rot[3] * px + rot[4] * py + rot[5] * pz
                val rz = rot[6] * px + rot[7] * py + rot[8] * pz
                dot += rx * (q[3 * i] - qcx) + ry * (q[3 * i + 1] - qcy) + rz * (q[3 * i + 2] - qcz)
            }
            val scale = dot / pp
            var acc = 0.0
            for (i in 0 until n) {
                val px = p[3 * i] - pcx; val py = p[3 * i + 1] - pcy; val pz = p[3 * i + 2] - pcz
                val rx = scale * (rot[0] * px + rot[1] * py + rot[2] * pz) - (q[3 * i] - qcx)
                val ry = scale * (rot[3] * px + rot[4] * py + rot[5] * pz) - (q[3 * i + 1] - qcy)
                val rz = scale * (rot[6] * px + rot[7] * py + rot[8] * pz) - (q[3 * i + 2] - qcz)
                acc += rx * rx + ry * ry + rz * rz
            }
            return sqrt(acc / n)
        }

        /** Unit eigenvector of the largest eigenvalue of a symmetric n×n matrix (row-major), by cyclic Jacobi rotations. */
        fun largestEigenvector(m: DoubleArray, n: Int): DoubleArray {
            val a = m.copyOf()
            val v = DoubleArray(n * n) { if (it / n == it % n) 1.0 else 0.0 }
            repeat(60) {
                var off = 0.0
                for (i in 0 until n) for (j in 0 until n) if (i != j) off += a[i * n + j] * a[i * n + j]
                if (off < 1e-22) return@repeat
                for (pI in 0 until n - 1) for (qI in pI + 1 until n) {
                    val apq = a[pI * n + qI]
                    if (abs(apq) < 1e-300) continue
                    val theta = (a[qI * n + qI] - a[pI * n + pI]) / (2 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val sn = t * c
                    for (k in 0 until n) {
                        val akp = a[k * n + pI]; val akq = a[k * n + qI]
                        a[k * n + pI] = c * akp - sn * akq
                        a[k * n + qI] = sn * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[pI * n + k]; val aqk = a[qI * n + k]
                        a[pI * n + k] = c * apk - sn * aqk
                        a[qI * n + k] = sn * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k * n + pI]; val vkq = v[k * n + qI]
                        v[k * n + pI] = c * vkp - sn * vkq
                        v[k * n + qI] = sn * vkp + c * vkq
                    }
                }
            }
            var best = 0
            for (i in 1 until n) if (a[i * n + i] > a[best * n + best]) best = i
            val out = DoubleArray(n) { v[it * n + best] }
            var norm = 0.0
            for (x in out) norm += x * x
            norm = sqrt(norm)
            return if (norm > 0) DoubleArray(n) { out[it] / norm } else out
        }
    }
}
