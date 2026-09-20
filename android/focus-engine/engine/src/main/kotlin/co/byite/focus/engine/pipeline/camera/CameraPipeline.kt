package co.byite.focus.engine.pipeline.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import co.byite.focus.core.aggregate.FrameSample
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.core.aggregate.SceneSample
import co.byite.focus.engine.pipeline.RgbaFrame
import co.byite.focus.engine.pipeline.face.FacePipeline
import co.byite.focus.engine.pipeline.pose.PosePipeline
import co.byite.focus.engine.pipeline.scene.SceneQuality
import co.byite.focus.engine.timebase.Timebase
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** Camera facts for the header and the event log. Strings and scalars. */
data class CameraFacts(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val nominalFps: Int,
    val fpsSelected: String,
    val fpsAvailable: String,
    val timestampSource: String,
    val stabilization: String,
)

/**
 * CameraX ImageAnalysis bound to the service lifecycle (v0-plan 2장 CameraPipeline): front camera,
 * 1280×720, [24,24] else [30,30] via Camera2Interop, KEEP_ONLY_LATEST, no Preview, RGBA_8888 output,
 * target rotation fixed to ROTATION_0 (portrait mount). Every frame goes through [FacePipeline]; the
 * [PosePipeline] runs on the first frame of each bucket (1 fps) and, while no face is detected, at
 * ≥ 3 fps; [SceneQuality] runs on the first frame of each bucket. Only scalars leave via [Listener].
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraPipeline(
    private val context: Context,
    private val analysisHandler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** First processed frame: the session starts at its capture time (buckets align to it). Called before its [onFrame]. */
        fun onSessionStart(captureMonoMs: Long)

        /** Camera2 capture result for one frame the HAL produced, stamped on the monotonic clock. May precede [onSessionStart]. */
        fun onFrameRequested(captureMonoNs: Long)
        fun onFrame(frame: FrameSample, pose: PoseSample?, scene: SceneSample?)
        fun onEvent(message: String)
    }

    private var face: FacePipeline? = null
    private var pose: PosePipeline? = null
    private val scene = SceneQuality()
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var scratch: ByteBuffer? = null
    private var sessionStartMs = -1L
    private var lastTimestampMs = Long.MIN_VALUE
    private var lastPoseBucket = -1L
    private var lastPoseNs = Long.MIN_VALUE
    private var lastSceneBucket = -1L
    private var firstFrameLogged = false
    private var transformWarned = false
    private var copiedFrames = 0L
    private var zeroCopyFrames = 0L
    private var nonMonotonic = 0L
    private var captureFailures = 0L
    private var fpsEffective: String? = null

    /** Camera-side facts, set by [bind]. */
    var facts: CameraFacts? = null
        private set

    /** Monotonic reference built from the camera's timestamp source, set by [bind] before any frame flows. */
    var timebase: Timebase? = null
        private set

    /**
     * Engine self-check (analysis thread): creates both landmarkers and runs one synthetic frame
     * (640×480 mid-grey RGBA, timestamp 0) through each. Returns a one-line report; throws — including
     * `Error`s such as NoClassDefFoundError / UnsatisfiedLinkError — when a model cannot be loaded or run.
     * A real frame always carries a later timestamp than the synthetic one, so VIDEO mode stays monotonic.
     */
    fun prepare(): String {
        val fp = FacePipeline(context)
        face = fp
        val pp = PosePipeline(context)
        pose = pp
        val w = SELF_CHECK_WIDTH
        val h = SELF_CHECK_HEIGHT
        val buf = ByteBuffer.allocateDirect(w * 4 * h)
        for (i in 0 until buf.capacity()) buf.put(0x80.toByte())
        buf.rewind()
        val frame = RgbaFrame(buf, w, h, 0, 0L)
        val f = fp.process(frame, 0L)
        val p = pp.process(frame, 0L)
        return "OK (Face ${"%.1f".format(f.inferMs)}ms, Pose ${"%.1f".format(p.inferMs)}ms, synthetic ${w}x$h grey: face=${f.detected} pose=${p.detected})"
    }

    /** Selects the front camera and binds one ImageAnalysis to [owner]. Returns false when there is no front camera. */
    fun bind(cameraProvider: ProcessCameraProvider, owner: LifecycleOwner): Boolean {
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        val infos = selector.filter(cameraProvider.availableCameraInfos)
        if (infos.isEmpty()) return false
        val c2 = Camera2CameraInfo.from(infos[0])
        val ranges: Array<Range<Int>> = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val oisModes = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val tb = Timebase(c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
        timebase = tb
        val has24 = ranges.any { it.lower == 24 && it.upper == 24 }
        val has30 = ranges.any { it.lower == 30 && it.upper == 30 }
        val selected: Range<Int>? = when {
            has24 -> Range(24, 24)
            has30 -> Range(30, 30)
            else -> null
        }

        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)

        val ext = Camera2Interop.Extender(builder)
        if (selected != null) ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val oisOff = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
        if (oisOff) ext.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                val fps = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { "[${it.lower},${it.upper}]" }
                analysisHandler.post {
                    if (ts != null) listener.onFrameRequested(tb.cameraToMonoNoSample(ts))
                    if (fps != null && fps != fpsEffective) {
                        listener.onEvent("ae_target_fps_range=$fps (was ${fpsEffective ?: "none"})")
                        fpsEffective = fps
                    }
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                val reason = failure.reason
                analysisHandler.post {
                    captureFailures++
                    if (captureFailures <= 20) listener.onEvent("capture_failed reason=$reason count=$captureFailures")
                }
            }
        })

        val analysis = builder.build()
        val executor = Executor { r -> analysisHandler.post(r) }
        analysis.setAnalyzer(executor) { image -> analyze(image) }
        this.analysis = analysis
        provider = cameraProvider
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(owner, selector, analysis)
        camera.cameraInfo.cameraState.observe(owner) { st ->
            val err = st.error
            listener.onEvent("camera_state ${st.type}${if (err != null) " error=${err.code} ${err.cause?.message ?: ""}" else ""}")
        }
        val res = analysis.resolutionInfo?.resolution
        facts = CameraFacts(
            cameraId = c2.cameraId,
            width = res?.width ?: 1280,
            height = res?.height ?: 720,
            nominalFps = selected?.upper ?: 30,
            fpsSelected = selected?.let { "[${it.lower},${it.upper}]" } ?: "unset(no [24,24] or [30,30])",
            fpsAvailable = ranges.joinToString(prefix = "[", postfix = "]") { "[${it.lower},${it.upper}]" },
            timestampSource = tb.cameraSourceName,
            stabilization = "video=OFF ois=${if (oisOff) "OFF" else "n/a"}",
        )
        return true
    }

    fun unbind() {
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (e: Exception) {
            listener.onEvent("unbind_failed ${e.javaClass.simpleName}: ${e.message}")
        }
        analysis = null
    }

    /** Analysis thread. Releases the landmarkers. */
    fun release() {
        face?.close()
        pose?.close()
        face = null
        pose = null
    }

    /** Analysis thread: the only place pixels are touched. Closes the image before returning. */
    private fun analyze(image: ImageProxy) {
        val cbNs = SystemClock.elapsedRealtimeNanos()
        val fp = face
        val pp = pose
        val tb = timebase
        if (fp == null || pp == null || tb == null) {
            image.close()
            return
        }
        try {
            val captureNs = tb.cameraToMono(image.imageInfo.timestamp, cbNs)
            val tsMs = captureNs / 1_000_000L
            if (!firstFrameLogged) {
                firstFrameLogged = true
                val plane = image.planes[0]
                listener.onEvent("first_frame ${image.width}x${image.height} format=${image.format} rotation=${image.imageInfo.rotationDegrees} rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} capacity=${plane.buffer.capacity()}")
            }
            if (tsMs <= lastTimestampMs) {
                nonMonotonic++
                if (nonMonotonic <= 20) listener.onEvent("non_monotonic_frame ts_ms=$tsMs last=$lastTimestampMs")
                return
            }
            lastTimestampMs = tsMs
            if (sessionStartMs < 0) {
                sessionStartMs = tsMs
                listener.onSessionStart(tsMs)
            }
            val frame = wrap(image, captureNs)
            val f = fp.process(frame, tsMs)
            if (!f.transformOk && !transformWarned) {
                transformWarned = true
                listener.onEvent("transform_matrix_layout_unexpected: bottom row is not (0,0,0,1); yaw/pitch/roll may be wrong")
            }
            val bucket = (tsMs - sessionStartMs) / 1000L
            val poseDue = bucket > lastPoseBucket || (!f.detected && captureNs - lastPoseNs >= POSE_FAST_PERIOD_NS)
            var poseSample: PoseSample? = null
            if (poseDue) {
                val p = pp.process(frame, tsMs)
                lastPoseBucket = bucket
                lastPoseNs = captureNs
                val s = p.scalars
                poseSample = if (p.detected && s != null) {
                    PoseSample(
                        captureMonoNs = captureNs, poseInferMs = p.inferMs, detected = true,
                        shoulderVisibilityMin = s.shoulderVisibilityMin,
                        shoulderCenterXPx = s.shoulderCenterXPx, shoulderCenterYPx = s.shoulderCenterYPx, shoulderWidthPx = s.shoulderWidthPx,
                        headLandmarkPresent = s.headLandmarkPresent, headOffsetBelowShoulderRatio = s.headOffsetBelowShoulderRatio,
                        frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx,
                    )
                } else {
                    PoseSample(captureMonoNs = captureNs, poseInferMs = p.inferMs, detected = false, frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx)
                }
            }
            var sceneSample: SceneSample? = null
            if (bucket > lastSceneBucket) {
                lastSceneBucket = bucket
                val st = scene.process(frame)
                sceneSample = SceneSample(captureNs, st.lumaMean, st.tileTextureMin, st.tileTextureMedian)
            }
            val frameSample = FrameSample(
                captureMonoNs = captureNs,
                latencyNs = (cbNs - captureNs).coerceAtLeast(0L),
                faceDetected = f.detected,
                yawDeg = f.yawDeg, pitchDeg = f.pitchDeg, rollDeg = f.rollDeg,
                faceWidthPx = f.faceWidthPx, jitterJ = f.jitterJ,
                faceInferMs = f.inferMs,
            )
            listener.onFrame(frameSample, poseSample, sceneSample)
        } catch (e: Exception) {
            listener.onEvent("analyze_failed ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** RGBA plane → [RgbaFrame]; zero-copy when the plane has no padding, else a row copy into a scratch buffer. */
    private fun wrap(image: ImageProxy, captureNs: Long): RgbaFrame {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = plane.buffer
        val rowBytes = w * 4
        val need = rowBytes * h
        val pixels: ByteBuffer = if (plane.pixelStride == 4 && plane.rowStride == rowBytes && src.capacity() == need && src.isDirect) {
            zeroCopyFrames++
            src.rewind()
            src
        } else {
            copiedFrames++
            val dst = scratch?.takeIf { it.capacity() == need } ?: ByteBuffer.allocateDirect(need).also { scratch = it }
            dst.clear()
            val rs = plane.rowStride
            for (row in 0 until h) {
                val start = row * rs
                src.limit(minOf(start + rowBytes, src.capacity()))
                src.position(start)
                dst.put(src)
            }
            dst.rewind()
            dst
        }
        return RgbaFrame(pixels, w, h, image.imageInfo.rotationDegrees, captureNs)
    }

    /** One-line frame-path statistics for the event log. */
    fun stats(): String = "frames zero_copy=$zeroCopyFrames copied=$copiedFrames non_monotonic=$nonMonotonic capture_failures=$captureFailures " +
        "jitter_skipped_fast_rotation=${face?.jitterSkippedFastRotation ?: 0} fps_result=${fpsEffective ?: "unknown"}"

    companion object {
        const val SELF_CHECK_WIDTH = 640
        const val SELF_CHECK_HEIGHT = 480

        /** Pose cadence while no face is detected: ≥ 3 fps. */
        const val POSE_FAST_PERIOD_NS: Long = 300_000_000L
    }
}
