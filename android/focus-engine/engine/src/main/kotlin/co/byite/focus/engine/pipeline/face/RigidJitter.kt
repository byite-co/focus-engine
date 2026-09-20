package co.byite.focus.engine.pipeline.face

import kotlin.math.sqrt

/**
 * Rigid-residual jitter j (spec 6장 품질 proxy).
 *
 * A subset of landmarks that moves little with expression (nose bridge, eye corners, forehead) is moved
 * into the canonical face frame with the inverse rotation of the transformation matrix, centred on its
 * mean and scaled by the face width. j is the RMS displacement of those points between consecutive face
 * frames. Rigid head motion is absorbed by R (it changes between frames) and does not enter j.
 *
 * Approximation, documented in the README: the inverse rotation is applied to image-space points
 * (pixels, weak perspective), not to the metric landmarks of the geometry module, which the Java API does
 * not expose. j is therefore unitless (fraction of the face width), and only ratios against a baseline
 * measured the same way are meaningful.
 */
class RigidJitter(private val maxFrameGapNs: Long = DEFAULT_MAX_FRAME_GAP_NS) {
    private var prev: DoubleArray? = null
    private var prevTsNs = 0L

    fun reset() {
        prev = null
    }

    /**
     * [canonical]: output of [toCanonical] for this frame. Returns j against the previous frame, or null
     * when there is none or it is older than [maxFrameGapNs]. Always stores this frame as the new reference.
     */
    fun next(canonical: DoubleArray, captureNs: Long): Double? {
        val p = prev
        prev = canonical
        val gap = captureNs - prevTsNs
        prevTsNs = captureNs
        if (p == null || p.size != canonical.size || gap <= 0L || gap > maxFrameGapNs) return null
        var acc = 0.0
        for (i in canonical.indices) {
            val d = canonical[i] - p[i]
            acc += d * d
        }
        return sqrt(acc / (canonical.size / 3))
    }

    companion object {
        const val DEFAULT_MAX_FRAME_GAP_NS: Long = 500_000_000L

        /**
         * Face mesh indices of the rigid subset: nose bridge (168, 6, 197, 195, 5), eye corners
         * (33, 133 left outer/inner; 362, 263 right inner/outer), forehead (10, 151, 9, 108, 337).
         */
        val SUBSET: IntArray = intArrayOf(168, 6, 197, 195, 5, 33, 133, 362, 263, 10, 151, 9, 108, 337)

        /**
         * Canonical-frame, centred, width-normalised coordinates (3N, xyz interleaved).
         *
         * [xs]/[ys] are buffer pixels, [zs] is the landmark z scaled to pixels; they are converted to the
         * buffer camera frame (X right, Y up, Z toward the camera) as (x, −y, −z) and rotated by Rᵀ where
         * [r] is the row-major 3×3 rotation block of the transformation matrix in the same buffer frame.
         */
        fun toCanonical(xs: DoubleArray, ys: DoubleArray, zs: DoubleArray, r: DoubleArray, faceWidthPx: Double): DoubleArray {
            require(xs.size == ys.size && ys.size == zs.size && r.size == 9) { "bad input sizes" }
            require(faceWidthPx > 0.0) { "face width must be positive" }
            val n = xs.size
            val out = DoubleArray(3 * n)
            var mx = 0.0
            var my = 0.0
            var mz = 0.0
            for (i in 0 until n) {
                val cx = xs[i]
                val cy = -ys[i]
                val cz = -zs[i]
                // q = Rᵀ c  →  q_j = Σ_i R[i][j] c_i
                val qx = r[0] * cx + r[3] * cy + r[6] * cz
                val qy = r[1] * cx + r[4] * cy + r[7] * cz
                val qz = r[2] * cx + r[5] * cy + r[8] * cz
                out[3 * i] = qx
                out[3 * i + 1] = qy
                out[3 * i + 2] = qz
                mx += qx
                my += qy
                mz += qz
            }
            mx /= n
            my /= n
            mz /= n
            for (i in 0 until n) {
                out[3 * i] = (out[3 * i] - mx) / faceWidthPx
                out[3 * i + 1] = (out[3 * i + 1] - my) / faceWidthPx
                out[3 * i + 2] = (out[3 * i + 2] - mz) / faceWidthPx
            }
            return out
        }
    }
}
