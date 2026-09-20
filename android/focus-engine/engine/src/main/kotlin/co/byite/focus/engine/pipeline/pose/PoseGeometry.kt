package co.byite.focus.engine.pipeline.pose

import kotlin.math.sqrt

/** Scalars derived from one Pose Landmarker result, in the upright image frame (pixels). */
data class PoseScalars(
    val shoulderVisibilityMin: Double,
    val shoulderCenterXPx: Double,
    val shoulderCenterYPx: Double,
    val shoulderWidthPx: Double,
    val headLandmarkPresent: Boolean,
    /** (head_y − shoulder_centre_y) ÷ shoulder width; positive = below (image y grows downward). */
    val headOffsetBelowShoulderRatio: Double?,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
)

/**
 * Shoulder and head scalars from BlazePose landmarks (spec 3장 G1 재료, schema 0.2.1 fields).
 * Landmarks arrive in the un-rotated buffer frame (normalised 0..1); they are mapped to the upright
 * frame with [rotationDegrees] (clockwise, `ImageInfo.rotationDegrees`) before anything is measured.
 */
object PoseGeometry {
    const val NOSE = 0
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LANDMARK_COUNT = 33

    /** A nose or ear landmark counts as "머리 landmark 있음" from this visibility on (engine assumption, README). */
    const val HEAD_VISIBILITY_MIN = 0.5

    /** Normalised buffer point → normalised upright point. */
    fun toUpright(x: Double, y: Double, rotationDegrees: Int): Pair<Double, Double> = when (norm(rotationDegrees)) {
        0 -> x to y
        90 -> (1.0 - y) to x
        180 -> (1.0 - x) to (1.0 - y)
        else -> y to (1.0 - x)
    }

    /** Upright (width, height) of a [w]×[h] buffer. */
    fun uprightSize(w: Int, h: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (norm(rotationDegrees) % 180 == 0) w to h else h to w

    /**
     * [x], [y], [visibility] indexed by landmark (size ≥ 13). Null when the shoulders coincide.
     */
    fun compute(x: DoubleArray, y: DoubleArray, visibility: DoubleArray, bufferW: Int, bufferH: Int, rotationDegrees: Int): PoseScalars? {
        require(x.size >= RIGHT_SHOULDER + 1 && y.size == x.size && visibility.size == x.size) { "need at least ${RIGHT_SHOULDER + 1} landmarks" }
        val (uw, uh) = uprightSize(bufferW, bufferH, rotationDegrees)
        fun px(i: Int): Pair<Double, Double> {
            val (ux, uy) = toUpright(x[i], y[i], rotationDegrees)
            return (ux * uw) to (uy * uh)
        }
        val (lx, ly) = px(LEFT_SHOULDER)
        val (rx, ry) = px(RIGHT_SHOULDER)
        val width = sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry))
        if (width <= 0.0) return null
        val cx = (lx + rx) / 2
        val cy = (ly + ry) / 2

        val headY: Double? = when {
            visibility[NOSE] >= HEAD_VISIBILITY_MIN -> px(NOSE).second
            else -> {
                val ears = listOf(LEFT_EAR, RIGHT_EAR).filter { visibility[it] >= HEAD_VISIBILITY_MIN }
                if (ears.isEmpty()) null else ears.sumOf { px(it).second } / ears.size
            }
        }
        return PoseScalars(
            shoulderVisibilityMin = minOf(visibility[LEFT_SHOULDER], visibility[RIGHT_SHOULDER]),
            shoulderCenterXPx = cx,
            shoulderCenterYPx = cy,
            shoulderWidthPx = width,
            headLandmarkPresent = headY != null,
            headOffsetBelowShoulderRatio = headY?.let { (it - cy) / width },
            frameWidthPx = uw,
            frameHeightPx = uh,
        )
    }

    private fun norm(deg: Int): Int {
        val n = ((deg % 360) + 360) % 360
        require(n % 90 == 0) { "rotation must be a multiple of 90°, got $deg" }
        return n
    }
}
