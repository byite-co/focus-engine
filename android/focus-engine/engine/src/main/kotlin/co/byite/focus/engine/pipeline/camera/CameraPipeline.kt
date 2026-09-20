package co.byite.focus.engine.pipeline.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.PerformanceHintManager
import android.os.Process
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
import co.byite.focus.core.aggregate.ProcessedFrame
import co.byite.focus.core.aggregate.SceneSample
import co.byite.focus.engine.CapturePreset
import co.byite.focus.engine.FrameSize
import co.byite.focus.engine.FrameSkipRule
import co.byite.focus.engine.PresetResolution
import co.byite.focus.engine.ResolutionChoice
import co.byite.focus.engine.pipeline.RgbaFrame
import co.byite.focus.engine.pipeline.face.FacePipeline
import co.byite.focus.engine.pipeline.pose.PosePipeline
import co.byite.focus.engine.pipeline.pose.PoseWorker
import co.byite.focus.engine.pipeline.scene.SceneQuality
import co.byite.focus.engine.timebase.Timebase
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** Camera facts for the header and the event log. Strings and scalars. */
data class CameraFacts(
    val cameraId: String,
    /** Resolution CameraX actually chose (confirmed against the first frame), never the requested one. */
    val width: Int,
    val height: Int,
    val requestedWidth: Int,
    val requestedHeight: Int,
    /** Preset id to report: the preset's own id, or "C2" when C fell back to 640×480. */
    val presetId: String,
    /** "FRONT", "BACK" or "EXTERNAL" (`LENS_FACING`). */
    val lensFacing: String,
    val nominalFps: Int,
    val fpsSelected: String,
    val fpsAvailable: String,
    val timestampSource: String,
    val stabilization: String,
    val gapThresholdMs: Int,
    val longGapThresholdMs: Int,
    val perfHint: String,
)

/**
 * CameraX ImageAnalysis bound to the service lifecycle (v0-plan 2장 CameraPipeline): front camera, the preset's
 * resolution ([PresetResolution]), [24,24] else [30,30] via Camera2Interop, KEEP_ONLY_LATEST, no Preview,
 * RGBA_8888 output, target rotation fixed to ROTATION_0 (portrait mount).
 *
 * Analysis thread (`focus-analysis`) per received frame: count → skip rule (preset E) → wrap → Face inference
 * (`frames_processed` fixed here) → post-processing → Pose hand-off (deep copy to [PoseWorker], 1 fps / ≥ 3 fps
 * while no face) → SceneQuality (1 Hz) → one [ProcessedFrame] posted through [Listener]. Every stage is timed.
 * Preset G adds a PerformanceHintManager session for this thread only and reports each cycle's duration.
 * Only scalars leave; the [Listener] implementation posts them to the aggregation queue (directive D 정정 3).
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraPipeline(
    private val context: Context,
    private val analysisHandler: Handler,
    private val listener: Listener,
    val preset: CapturePreset,
) {
    /** Callbacks run on the analysis thread unless noted. None may touch the aggregator directly. */
    interface Listener {
        /** First received frame: the session starts at its capture time (buckets align to it). Called before its [onFrameReceived]. */
        fun onSessionStart(captureMonoMs: Long, frameWidth: Int, frameHeight: Int)

        /** Camera2 capture result for one frame the HAL produced, stamped on the monotonic clock. May precede [onSessionStart]. */
        fun onFrameRequested(captureMonoNs: Long)
        fun onFrameReceived(captureMonoNs: Long)
        fun onFrameSkipped(captureMonoNs: Long)
        fun onFaceInferenceError(captureMonoNs: Long)
        fun onPreFaceError(captureMonoNs: Long)
        fun onFrameProcessed(frame: ProcessedFrame)
        fun onScene(sample: SceneSample)
        fun onPoseRequested(captureMonoNs: Long, copyMs: Double)
        fun onPoseSuperseded(captureMonoNs: Long)

        /** Pose worker thread. */
        fun onPose(sample: PoseSample)

        /** Pose worker thread. */
        fun onPoseError(captureMonoNs: Long)

        /** Any thread. */
        fun onEvent(message: String)
    }

    private var face: FacePipeline? = null
    private var poseWorker: PoseWorker? = null
    private val scene = SceneQuality()
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var scratch: ByteBuffer? = null
    private var skipRule: FrameSkipRule? = null
    private var hintSession: PerformanceHintManager.Session? = null
    private var hintReports = 0L
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
    private var faceErrors = 0L
    private var postFaceErrors = 0L
    private var fpsEffective: String? = null
    /** Duration of the previous frame's final ProcessedFrame post, charged to the next frame's "큐 적재" stage. */
    private var carriedEnqueueNs = 0L

    /** Camera-side facts, set by [bind]; width/height re-confirmed by the first frame. */
    @Volatile var facts: CameraFacts? = null
        private set

    /** Monotonic reference built from the camera's timestamp source, set by [bind] before any frame flows. */
    var timebase: Timebase? = null
        private set

    private val poseSink = object : PoseWorker.Sink {
        override fun onPose(sample: PoseSample) = listener.onPose(sample)
        override fun onPoseError(captureMonoNs: Long, error: Throwable) {
            listener.onEvent("pose_error ${error.javaClass.simpleName}: ${error.message}")
            listener.onPoseError(captureMonoNs)
        }
        override fun onPoseSuperseded(captureMonoNs: Long) = listener.onPoseSuperseded(captureMonoNs)
    }

    /**
     * Engine self-check (analysis thread): creates the Face landmarker with the preset's delegate and blendshape
     * setting, the Pose landmarker and its worker, opens the preset G hint session for this thread, and runs one
     * synthetic frame (640×480 mid-grey RGBA, timestamp 0) through each landmarker. Returns a one-line report;
     * throws — including `Error`s such as NoClassDefFoundError / UnsatisfiedLinkError — when a model cannot be
     * loaded or run. A real frame always carries a later timestamp than the synthetic one, so VIDEO mode stays monotonic.
     */
    fun prepare(): String {
        val fp = FacePipeline(context, preset.faceDelegate, preset.faceBlendshapes)
        face = fp
        val pp = PosePipeline(context)
        val worker = PoseWorker(pp, poseSink)
        poseWorker = worker
        val w = SELF_CHECK_WIDTH
        val h = SELF_CHECK_HEIGHT
        val buf = ByteBuffer.allocateDirect(w * 4 * h)
        for (i in 0 until buf.capacity()) buf.put(0x80.toByte())
        buf.rewind()
        val frame = RgbaFrame(buf, w, h, 0, 0L)
        val f = fp.process(frame, 0L)
        val p = pp.process(frame, 0L)
        worker.start()
        val hint = openHintSession()
        return "OK (프리셋 ${preset.id}: Face ${preset.faceDelegate} blendshape ${if (preset.faceBlendshapes) "on" else "off"} ÷${preset.frameProcessDivisor}, " +
            "Face ${"%.1f".format(f.inferMs)}ms, Pose ${"%.1f".format(p.inferMs)}ms, synthetic ${w}x$h grey: face=${f.detected} pose=${p.detected}, perf hint $hint)"
    }

    /** Preset G: PerformanceHintManager session for the analysis thread only (API 31+). Returns a description for the log. */
    private fun openHintSession(): String {
        val target = preset.perfHintTargetMs ?: return "none"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "unavailable(api ${Build.VERSION.SDK_INT})"
        val pm = context.getSystemService(PerformanceHintManager::class.java) ?: return "unavailable(no service)"
        val session = try {
            pm.createHintSession(intArrayOf(Process.myTid()), target * 1_000_000L)
        } catch (e: Exception) {
            return "failed(${e.javaClass.simpleName})"
        } ?: return "unavailable(null session)"
        hintSession = session
        return "session tid=${Process.myTid()} target=${target}ms preferred_rate=${pm.preferredUpdateRateNanos}ns"
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
        val nominalFps = selected?.upper ?: 30
        skipRule = FrameSkipRule(preset.frameProcessDivisor, nominalFps)

        val supported: List<FrameSize> = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.map { FrameSize(it.width, it.height) } ?: emptyList()
        val choice: ResolutionChoice = PresetResolution.choose(preset, supported)
        listener.onEvent("resolution_choice preset=${preset.id} requested=${choice.size} id=${choice.presetId} (${choice.reason}) supported_yuv=${supported.joinToString(",")}")

        val aspect = if (choice.size.is4x3) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(Size(choice.size.width, choice.size.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
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
        val lensFacing = when (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "unknown"
        }
        facts = CameraFacts(
            cameraId = c2.cameraId,
            width = res?.width ?: choice.size.width,
            height = res?.height ?: choice.size.height,
            requestedWidth = choice.size.width,
            requestedHeight = choice.size.height,
            presetId = choice.presetId,
            lensFacing = lensFacing,
            nominalFps = nominalFps,
            fpsSelected = selected?.let { "[${it.lower},${it.upper}]" } ?: "unset(no [24,24] or [30,30])",
            fpsAvailable = ranges.joinToString(prefix = "[", postfix = "]") { "[${it.lower},${it.upper}]" },
            timestampSource = tb.cameraSourceName,
            stabilization = "video=OFF ois=${if (oisOff) "OFF" else "n/a"}",
            gapThresholdMs = preset.gapThresholdMs(nominalFps),
            longGapThresholdMs = preset.longGapThresholdMs(nominalFps),
            perfHint = if (hintSession != null) "target ${preset.perfHintTargetMs}ms" else "none",
        )
        return true
    }

    /** Main thread. Stop step 1: no frame reaches [analyze] after this returns (a call already running finishes). */
    fun unbind() {
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (e: Exception) {
            listener.onEvent("unbind_failed ${e.javaClass.simpleName}: ${e.message}")
        }
        analysis = null
    }

    /** Stop step 2. */
    fun closePoseSlot() {
        poseWorker?.closeSlot()
    }

    /** Stop step 3: cancelled pose requests. */
    fun awaitPoseIdle(timeoutMs: Long): Long = poseWorker?.awaitIdle(timeoutMs) ?: 0L

    /** Analysis thread. Releases the landmarkers (the GPU delegate is thread-affine), the pose worker and the hint session. */
    fun release() {
        hintSession?.close()
        hintSession = null
        face?.close()
        face = null
        poseWorker?.release()
        poseWorker = null
    }

    /** Analysis thread: the only place pixels are touched. Closes the image before returning. */
    private fun analyze(image: ImageProxy) {
        val entryNs = SystemClock.elapsedRealtimeNanos()
        val tb = timebase
        if (tb == null) {
            image.close()
            return
        }
        try {
            val captureNs = tb.cameraToMono(image.imageInfo.timestamp, entryNs)
            val tsMs = captureNs / 1_000_000L
            if (!firstFrameLogged) {
                firstFrameLogged = true
                val plane = image.planes[0]
                listener.onEvent("first_frame ${image.width}x${image.height} format=${image.format} rotation=${image.imageInfo.rotationDegrees} rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} capacity=${plane.buffer.capacity()}")
                facts?.let { f ->
                    if (f.width != image.width || f.height != image.height) {
                        listener.onEvent("resolution_actual ${image.width}x${image.height} (resolutionInfo said ${f.width}x${f.height})")
                        facts = f.copy(width = image.width, height = image.height)
                    }
                }
            }
            if (sessionStartMs < 0) {
                sessionStartMs = tsMs
                listener.onSessionStart(tsMs, image.width, image.height)
            }
            var enqueueNs = carriedEnqueueNs
            carriedEnqueueNs = 0L
            val tq0 = SystemClock.elapsedRealtimeNanos()
            listener.onFrameReceived(captureNs)
            enqueueNs += SystemClock.elapsedRealtimeNanos() - tq0
            val fp = face
            val worker = poseWorker
            val rule = skipRule
            if (fp == null || worker == null || rule == null) {
                listener.onPreFaceError(captureNs)
                return
            }
            if (tsMs <= lastTimestampMs) {
                nonMonotonic++
                if (nonMonotonic <= 20) listener.onEvent("non_monotonic_frame ts_ms=$tsMs last=$lastTimestampMs")
                listener.onPreFaceError(captureNs)
                return
            }
            if (rule.shouldSkip(captureNs)) {
                listener.onFrameSkipped(captureNs)
                return
            }
            lastTimestampMs = tsMs
            val frame = try {
                wrap(image, captureNs)
            } catch (e: Exception) {
                listener.onEvent("wrap_failed ${e.javaClass.simpleName}: ${e.message}")
                listener.onPreFaceError(captureNs)
                return
            }
            val wrapMs = (SystemClock.elapsedRealtimeNanos() - entryNs) / 1e6
            val inference = try {
                fp.infer(frame, tsMs)
            } catch (t: Throwable) {
                faceErrors++
                if (faceErrors <= 20) listener.onEvent("face_inference_error ${t.javaClass.simpleName}: ${t.message}")
                listener.onFaceInferenceError(captureNs)
                return
            }
            // frames_processed is fixed from here on (정정 3), whatever the later stages do.
            rule.onProcessed(captureNs)
            var sample: FrameSample? = null
            try {
                val f = fp.extract(inference, frame)
                if (!f.transformOk && !transformWarned) {
                    transformWarned = true
                    listener.onEvent("transform_matrix_layout_unexpected: bottom row is not (0,0,0,1); yaw/pitch/roll may be wrong")
                }
                val bucket = (tsMs - sessionStartMs) / 1000L
                val poseDue = bucket > lastPoseBucket || (!f.detected && captureNs - lastPoseNs >= POSE_FAST_PERIOD_NS)
                var poseCopyMs: Double? = null
                if (poseDue) {
                    val submitted = worker.submit(frame, tsMs)
                    if (submitted != null) {
                        lastPoseBucket = bucket
                        lastPoseNs = captureNs
                        poseCopyMs = submitted.copyMs
                        val tq1 = SystemClock.elapsedRealtimeNanos()
                        submitted.supersededCaptureNs?.let { listener.onPoseSuperseded(it) }
                        listener.onPoseRequested(captureNs, submitted.copyMs)
                        enqueueNs += SystemClock.elapsedRealtimeNanos() - tq1
                    }
                }
                var sceneMs: Double? = null
                if (bucket > lastSceneBucket) {
                    lastSceneBucket = bucket
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val st = scene.process(frame)
                    val tq2 = SystemClock.elapsedRealtimeNanos()
                    sceneMs = (tq2 - t0) / 1e6
                    listener.onScene(SceneSample(captureNs, st.lumaMean, st.tileTextureMin, st.tileTextureMedian, computeMs = sceneMs))
                    enqueueNs += SystemClock.elapsedRealtimeNanos() - tq2
                }
                sample = FrameSample(
                    captureMonoNs = captureNs,
                    latencyNs = (entryNs - captureNs).coerceAtLeast(0L),
                    faceDetected = f.detected,
                    yawDeg = f.yawDeg, pitchDeg = f.pitchDeg, rollDeg = f.rollDeg,
                    faceWidthPx = f.faceWidthPx, jitterJ = f.jitterJ,
                    wrapMs = wrapMs,
                    facePostMs = f.postMs,
                    sceneMs = sceneMs,
                    poseCopyMs = poseCopyMs,
                    enqueueMs = enqueueNs / 1e6,
                )
            } catch (t: Throwable) {
                postFaceErrors++
                if (postFaceErrors <= 20) listener.onEvent("post_face_failed ${t.javaClass.simpleName}: ${t.message}")
                sample = null
            }
            val totalNs = SystemClock.elapsedRealtimeNanos() - entryNs
            val tq3 = SystemClock.elapsedRealtimeNanos()
            listener.onFrameProcessed(ProcessedFrame(captureNs, inference.inferMs, sample?.copy(totalMs = totalNs / 1e6)))
            carriedEnqueueNs = SystemClock.elapsedRealtimeNanos() - tq3
            hintSession?.let { s ->
                s.reportActualWorkDuration(totalNs)
                hintReports++
            }
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
        "face_inference_errors=$faceErrors post_face_failed=$postFaceErrors pose_submitted=${poseWorker?.submitted ?: 0} " +
        "jitter_skipped_fast_rotation=${face?.jitterSkippedFastRotation ?: 0} perf_hint_reports=$hintReports fps_result=${fpsEffective ?: "unknown"}"

    companion object {
        const val SELF_CHECK_WIDTH = 640
        const val SELF_CHECK_HEIGHT = 480

        /** Pose cadence while no face is detected: ≥ 3 fps. */
        const val POSE_FAST_PERIOD_NS: Long = 300_000_000L
    }
}
