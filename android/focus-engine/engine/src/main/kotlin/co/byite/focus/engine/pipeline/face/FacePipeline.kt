package co.byite.focus.engine.pipeline.face

import android.content.Context
import android.os.SystemClock
import co.byite.focus.engine.pipeline.RgbaFrame
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.sqrt

/** Scalars a face frame yields. Landmarks and matrices stay inside [FacePipeline]. */
data class FaceFeatures(
    val detected: Boolean,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val faceWidthPx: Double? = null,
    val jitterJ: Double? = null,
    /** Face Landmarker wall time (ms). */
    val inferMs: Double,
    /** False when the transformation matrix did not look column-major affine (logged once). */
    val transformOk: Boolean = true,
)

/**
 * MediaPipe Face Landmarker, VIDEO mode, CPU, num_faces = 1, blendshapes + transformation matrix on
 * (spec 1장 파이프라인). Head pose via [HeadPose], face width = distance between face-oval landmarks
 * 234 and 454 in buffer pixels, jitter via [RigidJitter] (similarity residual ÷ inter-ocular distance,
 * frame pairs with head rotation > 30°/s excluded).
 */
class FacePipeline(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build(),
    )
    private val jitter = RigidJitter()
    private val subset = DoubleArray(3 * RigidJitter.SUBSET.size)
    private var lastRotation = -1
    private var options: ImageProcessingOptions? = null

    /** Runs on the analysis thread. [timestampMs] must increase strictly between calls. */
    fun process(frame: RgbaFrame, timestampMs: Long): FaceFeatures {
        val opts = optionsFor(frame.rotationDegrees)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = frame.toMPImage().use { landmarker.detectForVideo(it, opts, timestampMs) }
        val inferMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val faces = result.faceLandmarks()
        val matrices = result.facialTransformationMatrixes().orElse(null)
        if (faces.isEmpty() || matrices == null || matrices.isEmpty()) {
            jitter.reset()
            return FaceFeatures(detected = false, inferMs = inferMs)
        }
        val lm = faces[0]
        val m = matrices[0]
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        val ok = HeadPose.looksAffine(m)
        val angles = HeadPose.fromTransform(m, frame.rotationDegrees)
        val a = lm[FACE_LEFT_EDGE]
        val b = lm[FACE_RIGHT_EDGE]
        val dx = (a.x() - b.x()) * w
        val dy = (a.y() - b.y()) * h
        val faceWidth = sqrt(dx * dx + dy * dy)
        val interocular = interocularPx(lm, w, h)
        val j = if (interocular > 0.0) {
            for (i in RigidJitter.SUBSET.indices) {
                val p = lm[RigidJitter.SUBSET[i]]
                subset[3 * i] = p.x() * w
                subset[3 * i + 1] = p.y() * h
                subset[3 * i + 2] = p.z() * w
            }
            jitter.next(subset, HeadPose.rotationOf(m), interocular, frame.captureMonoNs)
        } else {
            jitter.reset()
            null
        }
        return FaceFeatures(
            detected = true,
            yawDeg = angles.yawDeg,
            pitchDeg = angles.pitchDeg,
            rollDeg = angles.rollDeg,
            faceWidthPx = faceWidth,
            jitterJ = j,
            inferMs = inferMs,
            transformOk = ok,
        )
    }

    /** Pixel distance between the eye centres (midpoints of outer and inner corners) in the buffer frame. */
    private fun interocularPx(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, w: Double, h: Double): Double {
        val lo = lm[RigidJitter.LEFT_EYE_OUTER]; val li = lm[RigidJitter.LEFT_EYE_INNER]
        val ri = lm[RigidJitter.RIGHT_EYE_INNER]; val ro = lm[RigidJitter.RIGHT_EYE_OUTER]
        val lx = (lo.x() + li.x()) / 2.0 * w; val ly = (lo.y() + li.y()) / 2.0 * h
        val rx = (ri.x() + ro.x()) / 2.0 * w; val ry = (ri.y() + ro.y()) / 2.0 * h
        return sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry))
    }

    private fun optionsFor(rotation: Int): ImageProcessingOptions {
        val o = options
        if (o != null && rotation == lastRotation) return o
        lastRotation = rotation
        return ImageProcessingOptions.builder().setRotationDegrees(rotation).build().also { options = it }
    }

    /** Frame pairs the jitter skipped for fast head rotation (event log). */
    val jitterSkippedFastRotation: Long get() = jitter.skippedFastRotation

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"

        /** Face-oval landmarks at the temples; their distance is "얼굴 폭 px". */
        const val FACE_LEFT_EDGE = 234
        const val FACE_RIGHT_EDGE = 454
    }
}
