package co.byite.focus.engine.pipeline.pose

import android.content.Context
import android.os.SystemClock
import co.byite.focus.engine.pipeline.RgbaFrame
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/** Scalars a pose run yields. The 33 landmarks stay inside [PosePipeline]. */
data class PoseFeatures(
    val detected: Boolean,
    val scalars: PoseScalars?,
    /** Pose Landmarker wall time (ms). */
    val inferMs: Double,
    /** Upright frame size for the record normalisation. */
    val frameWidthPx: Int,
    val frameHeightPx: Int,
)

/** MediaPipe Pose Landmarker (lite), VIDEO mode, CPU, num_poses = 1. Scheduling (1 fps / 3 fps) is the camera pipeline's. */
class PosePipeline(context: Context) : AutoCloseable {
    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setOutputSegmentationMasks(false)
            .build(),
    )
    private val x = DoubleArray(PoseGeometry.LANDMARK_COUNT)
    private val y = DoubleArray(PoseGeometry.LANDMARK_COUNT)
    private val vis = DoubleArray(PoseGeometry.LANDMARK_COUNT)
    private var lastRotation = -1
    private var options: ImageProcessingOptions? = null

    fun process(frame: RgbaFrame, timestampMs: Long): PoseFeatures {
        val opts = optionsFor(frame.rotationDegrees)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = frame.toMPImage().use { landmarker.detectForVideo(it, opts, timestampMs) }
        val inferMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val (uw, uh) = PoseGeometry.uprightSize(frame.width, frame.height, frame.rotationDegrees)
        val poses = result.landmarks()
        if (poses.isEmpty() || poses[0].size < PoseGeometry.LANDMARK_COUNT) {
            return PoseFeatures(detected = false, scalars = null, inferMs = inferMs, frameWidthPx = uw, frameHeightPx = uh)
        }
        val lm = poses[0]
        for (i in 0 until PoseGeometry.LANDMARK_COUNT) {
            val p = lm[i]
            x[i] = p.x().toDouble()
            y[i] = p.y().toDouble()
            vis[i] = p.visibility().orElse(0f).toDouble()
        }
        val scalars = PoseGeometry.compute(x, y, vis, frame.width, frame.height, frame.rotationDegrees)
        return PoseFeatures(detected = scalars != null, scalars = scalars, inferMs = inferMs, frameWidthPx = uw, frameHeightPx = uh)
    }

    private fun optionsFor(rotation: Int): ImageProcessingOptions {
        val o = options
        if (o != null && rotation == lastRotation) return o
        lastRotation = rotation
        return ImageProcessingOptions.builder().setRotationDegrees(rotation).build().also { options = it }
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
