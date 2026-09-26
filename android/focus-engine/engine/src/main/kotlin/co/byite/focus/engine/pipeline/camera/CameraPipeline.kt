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
import co.byite.focus.core.aggregate.CameraCounterSink
import co.byite.focus.core.aggregate.CameraStamp
import co.byite.focus.core.aggregate.FrameSample
import co.byite.focus.core.aggregate.FrameScheduler
import co.byite.focus.core.aggregate.GapThresholds
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.core.aggregate.ProcessedFrame
import co.byite.focus.core.aggregate.SceneSample
import co.byite.focus.core.model.FaceSchedule
import co.byite.focus.engine.AnalysisGate
import co.byite.focus.engine.CameraFpsRequest
import co.byite.focus.engine.CapturePreset
import co.byite.focus.engine.FpsRange
import co.byite.focus.engine.FrameSize
import co.byite.focus.engine.PresetResolution
import co.byite.focus.engine.ResolutionChoice
import co.byite.focus.engine.pipeline.RgbaFrame
import co.byite.focus.engine.pipeline.face.FacePipeline
import co.byite.focus.engine.pipeline.pose.PosePipeline
import co.byite.focus.engine.pipeline.pose.PoseWorker
import co.byite.focus.engine.pipeline.scene.SceneQuality
import co.byite.focus.engine.timebase.Timebase
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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
    /** Cadence the thresholds were computed for: the requested range's upper bound (Hvar: 15); [CadencePlan.NOMINAL_FPS_UNSET] (0) for fps unset. */
    val nominalFps: Int,
    val fpsSelected: String,
    val fpsAvailable: String,
    val timestampSource: String,
    val stabilization: String,
    /** Rounded ms of the ns thresholds (legacy header fields); null for fps unset (no expected interval at the start). */
    val gapThresholdMs: Int?,
    val longGapThresholdMs: Int?,
    val perfHint: String,
    /** Requested AE range, null when nothing was requested (HAL default). */
    val fpsRequest: FpsRange? = null,
    /** Every AE range the camera offers. */
    val fpsRanges: List<FpsRange> = emptyList(),
    val faceSchedule: FaceSchedule = FaceSchedule.EVERY_FRAME,
    /** Expected interval between Face-processed frames (ns) and the formula thresholds (directive E 2장); all null for an every-frame preset with fps unset (E2 1장). */
    val faceProcessPeriodNs: Long? = null,
    val gapThresholdNs: Long? = null,
    val longGapThresholdNs: Long? = null,
    /** No AE range could be requested (neither [24,24] nor [30,30]): no cadence is assumed, the session is not comparable. */
    val fpsUnset: Boolean = false,
)

/**
 * CameraX ImageAnalysis bound to the service lifecycle (v0-plan 2장 CameraPipeline): front camera, the preset's
 * resolution ([PresetResolution]), [24,24] else [30,30] via Camera2Interop, KEEP_ONLY_LATEST, no Preview,
 * RGBA_8888 output, target rotation fixed to ROTATION_0 (portrait mount).
 *
 * Analysis thread (`focus-analysis`) per received frame: count → slot decision ([FrameScheduler]: every capture
 * result for every-frame presets, the 3장 slot rule for E / E15) → wrap → Face inference (`frames_processed` and the
 * slot `filled` fixed here) → post-processing → Pose hand-off (deep copy to [PoseWorker], 1 fps / ≥ 3 fps while no
 * face) → SceneQuality (1 Hz) → one [ProcessedFrame] posted through [Listener]. Every stage is timed.
 * Preset G adds a PerformanceHintManager session for this thread only and reports each cycle's duration.
 * Only scalars leave; the [Listener] implementation posts them to the aggregation queue (directive D 정정 3).
 *
 * Timestamp domains (directive E 4장): every camera-origin event carries a [CameraStamp] — the raw `SENSOR_TIMESTAMP`
 * (identity: `CaptureResult.SENSOR_TIMESTAMP == ImageProxy.imageInfo.timestamp`, session membership, slots) and its
 * mono conversion (position). Capture results reach this thread through the capture callback; the scheduler holds the
 * ones that precede the first analyzer frame and replays them once `sessionStartRawTs` is known.
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraPipeline(
    private val context: Context,
    private val analysisHandler: Handler,
    private val listener: Listener,
    val preset: CapturePreset,
) {
    /**
     * Callbacks run on the analysis thread unless noted. None may touch the aggregator directly. The camera counters
     * ([CameraCounterSink]: requested / received / skipped / errors / slot expected·filled·missed / after-close) are
     * emitted by the [FrameScheduler]; the sample callbacks below by this class.
     */
    interface Listener : CameraCounterSink {
        /** First received frame: the session starts at its capture time (buckets align to it, `sessionStartRawTs` = its raw timestamp). Called before its counters. */
        fun onSessionStart(captureMonoMs: Long, rawSensorTs: Long, frameWidth: Int, frameHeight: Int)
        fun onFrameProcessed(frame: ProcessedFrame)
        fun onScene(sample: SceneSample)
        fun onPoseRequested(stamp: CameraStamp, copyMs: Double)
        fun onPoseSuperseded(stamp: CameraStamp)

        /** Pose worker thread. */
        fun onPose(sample: PoseSample)

        /** Pose worker thread. */
        fun onPoseError(stamp: CameraStamp)

        /**
         * Analysis thread, the moment the scheduler takes the stop fence: the aggregator's `stopInputs(fenceMono, fenceRaw)`
         * must be queued here so it precedes every counter the scheduler emits from now on (same FIFO queue).
         */
        fun onStopFence(fenceMonoMs: Long, fenceRawNs: Long)

        /** Any thread. */
        fun onEvent(message: String)
    }

    private var face: FacePipeline? = null
    private var poseWorker: PoseWorker? = null
    private val scene = SceneQuality()
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var scratch: ByteBuffer? = null
    /** Slot scheduler + counter glue (core), created at [bind]; analysis thread only. */
    @Volatile private var scheduler: FrameScheduler? = null
    /** Stop fence requested by the main thread ([requestFence]); the analysis thread applies it at its next entry, before any producer stops. */
    @Volatile private var pendingFenceMonoMs: Long? = null
    @Volatile private var pendingFenceRawNs: Long? = null
    private var fenceApplied = false
    /** Camera2 capture-result drain (stop): the last capture result's arrival and the camera's CLOSED state. */
    @Volatile private var lastCaptureResultAtMs = 0L
    private val cameraClosed = CountDownLatch(1)
    @Volatile private var frameIntervalMs = 42L
    /** Generation gate of the per-frame work (code review item 2). */
    private val gate = AnalysisGate()
    private var hintSession: PerformanceHintManager.Session? = null
    private var hintReports = 0L
    private var sessionStartMs = -1L
    private var sessionStartRaw = Long.MIN_VALUE
    private var lastTimestampMs = Long.MIN_VALUE
    private var captureAfterCloseLogged = 0L
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
        override fun onPoseError(stamp: CameraStamp, error: Throwable) {
            listener.onEvent("pose_error ${error.javaClass.simpleName}: ${error.message}")
            listener.onPoseError(stamp)
        }
        override fun onPoseSuperseded(stamp: CameraStamp) = listener.onPoseSuperseded(stamp)
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
        val schedule = preset.faceRateHz?.let { "슬롯 ${it}Hz" } ?: "매 프레임"
        return "OK (프리셋 ${preset.id}: Face ${preset.faceDelegate} blendshape ${if (preset.faceBlendshapes) "on" else "off"} $schedule 카메라 ${preset.cameraFps.label}, " +
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
        val supportedRanges = ranges.map { FpsRange(it.lower, it.upper) }
        val request: FpsRange? = preset.cameraFps.select(supportedRanges)
        if (request == null && preset.cameraFps != CameraFpsRequest.Default) {
            listener.onEvent("fps_range_unavailable preset=${preset.id} wanted=${preset.cameraFps.label} supported=${supportedRanges.joinToString(",")}")
            return false
        }
        val selected: Range<Int>? = request?.let { Range(it.lower, it.upper) }
        // the cadence the thresholds are computed for: the requested upper bound (Hvar: 15); nothing is assumed for fps unset (E2 1장)
        val plan = preset.cadencePlan(request, supportedRanges)
        frameIntervalMs = (plan.slotToleranceIntervalNs / 1_000_000L).coerceAtLeast(1L)
        scheduler = FrameScheduler(listener, preset.slotPeriodNs(), plan.slotToleranceIntervalNs)
        listener.onEvent(
            "fps_request preset=${preset.id} wanted=${preset.cameraFps.label} selected=${request?.toString() ?: "unset"} nominal=${plan.nominalFps ?: "unset"} " +
                "face_schedule=${preset.faceSchedule.name.lowercase()} face_period_ns=${plan.faceProcessPeriodNs} slot_tolerance_interval_ns=${plan.slotToleranceIntervalNs} " +
                "gap_threshold_ns=${plan.gapThresholdNs} long_gap_threshold_ns=${plan.longGapThresholdNs} supported=${supportedRanges.joinToString(",")}",
        )

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
                lastCaptureResultAtMs = SystemClock.elapsedRealtime()
                analysisHandler.post {
                    if (ts != null) onCaptureResult(ts, tb)
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
            if (st.type == androidx.camera.core.CameraState.Type.CLOSED) cameraClosed.countDown()
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
            nominalFps = plan.nominalFpsForHeader,
            fpsSelected = request?.toString() ?: "unset(no [24,24] or [30,30])",
            fpsAvailable = supportedRanges.joinToString(prefix = "[", postfix = "]"),
            timestampSource = tb.cameraSourceName,
            stabilization = "video=OFF ois=${if (oisOff) "OFF" else "n/a"}",
            gapThresholdMs = plan.gapThresholdMs,
            longGapThresholdMs = plan.longGapThresholdMs,
            perfHint = if (hintSession != null) "target ${preset.perfHintTargetMs}ms" else "none",
            fpsRequest = request,
            fpsRanges = supportedRanges,
            faceSchedule = preset.faceSchedule,
            faceProcessPeriodNs = plan.faceProcessPeriodNs,
            gapThresholdNs = plan.gapThresholdNs,
            longGapThresholdNs = plan.longGapThresholdNs,
            fpsUnset = plan.fpsUnset,
        )
        return true
    }

    /** Analysis thread: one capture result (raw `SENSOR_TIMESTAMP`) into the scheduler; it emits `frames_requested` and the slot decision. */
    private fun onCaptureResult(rawTs: Long, tb: Timebase) {
        val sch = scheduler ?: return
        applyPendingFence(sch)
        val d = sch.onCaptureResult(CameraStamp(rawTs, tb.cameraToMonoNoSample(rawTs)))
        if (d == FrameScheduler.CaptureDecision.AFTER_CLOSE && ++captureAfterCloseLogged <= 20) listener.onEvent("capture_result_after_close raw_ts=$rawTs")
    }

    /**
     * Stop step 1, main thread, **before** the producers stop (directive E 5장): the fence in both domains. The analysis
     * thread applies it at its next capture result or frame ([applyPendingFence]) and queues the aggregator's fence
     * through [Listener.onStopFence] ahead of every later counter, so no capture result stamped at or after the raw fence
     * can become an expected slot while the camera is still winding down.
     */
    fun requestFence(fenceMonoMs: Long, fenceRawNs: Long) {
        if (pendingFenceRawNs == null) {
            pendingFenceMonoMs = fenceMonoMs
            pendingFenceRawNs = fenceRawNs
        }
    }

    /** Analysis thread: apply a requested fence once (idempotent); also the target of the stop sequence's `raise_fence` post. */
    fun applyPendingFence(sch: FrameScheduler? = scheduler) {
        if (fenceApplied) return
        val raw = pendingFenceRawNs ?: return
        val mono = pendingFenceMonoMs ?: return
        fenceApplied = true
        sch?.fence(raw)
        listener.onStopFence(mono, raw)
    }

    /**
     * Stop step 1, stop thread: wait for the Camera2 capture results still in flight after [unbind] — until the camera
     * reports CLOSED (bounded by [closedTimeoutMs]) and no capture result arrived for [quietFrames] frame intervals
     * (bounded by [quietTimeoutMs]). Returns (closed seen, quiet reached, elapsed ms) for the event log.
     */
    fun awaitCaptureResultsDrained(closedTimeoutMs: Long, quietFrames: Int, quietTimeoutMs: Long): Triple<Boolean, Boolean, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val closed = try {
            cameraClosed.await(closedTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        val quietMs = frameIntervalMs * quietFrames
        var quiet = false
        val deadline = SystemClock.elapsedRealtime() + quietTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val since = SystemClock.elapsedRealtime() - lastCaptureResultAtMs
            if (since >= quietMs) {
                quiet = true
                break
            }
            try {
                Thread.sleep((quietMs - since).coerceIn(1L, 20L))
            } catch (e: InterruptedException) {
                break
            }
        }
        return Triple(closed, quiet, SystemClock.elapsedRealtime() - t0)
    }

    /** The CLOSE step of the stop order (analysis thread): CLOSE the scheduler; returns the open slots terminated as missed. */
    fun closeScheduler(): Long {
        val sch = scheduler ?: return 0L
        applyPendingFence(sch)
        if (sch.fenceRawTs == null) {
            listener.onEvent("close_scheduler_without_fence") // stop order violated; leave the scheduler open rather than close it unfenced
            return 0L
        }
        return sch.close()
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

    /**
     * Stop step 1, after the bounded wait for the analysis thread timed out: the in-flight frame's outcome is never
     * posted nor counted. Returns the frames counted as `frames_cancelled_at_stop` (0 or 1).
     */
    fun cancelAnalysis(): Long = gate.cancel()

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
            val rawTs = image.imageInfo.timestamp
            val captureNs = tb.cameraToMono(rawTs, entryNs)
            val tsMs = captureNs / 1_000_000L
            val stamp = CameraStamp(rawTs, captureNs)
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
            val sch = scheduler
            if (sch == null) return // bind() has not run: nothing is counted
            applyPendingFence(sch)
            if (sessionStartMs < 0) {
                sessionStartMs = tsMs
                sessionStartRaw = rawTs
                listener.onSessionStart(tsMs, rawTs, image.width, image.height)
                val replay = sch.onSessionStart(stamp)
                listener.onEvent("session_start_raw raw_ts=$rawTs mono_ns=$captureNs offset_ns=${tb.cameraOffsetNs} replayed_capture_results=${replay.size} before_start=${replay.count { it == FrameScheduler.CaptureDecision.BEFORE_START }}")
            }
            // Gate (code review item 2): a frame that starts after the stop path gave up on this thread posts nothing.
            val frameGeneration = gate.begin(captureNs) ?: return
            var enqueueNs = carriedEnqueueNs
            carriedEnqueueNs = 0L
            val tq0 = SystemClock.elapsedRealtimeNanos()
            // received + backpressure resolution + the slot decision, all by the raw timestamp
            val outcome = sch.onFrameReceived(stamp)
            enqueueNs += SystemClock.elapsedRealtimeNanos() - tq0
            when (outcome) {
                FrameScheduler.FrameOutcome.AFTER_FENCE -> {
                    gate.end(frameGeneration)
                    return
                }
                FrameScheduler.FrameOutcome.OUT_OF_ORDER -> {
                    nonMonotonic++
                    if (nonMonotonic <= 20) listener.onEvent("non_monotonic_frame raw_ts=$rawTs ts_ms=$tsMs last=$lastTimestampMs")
                    if (gate.end(frameGeneration)) sch.onPreFaceError(stamp)
                    return
                }
                FrameScheduler.FrameOutcome.NOT_SLOT -> {
                    if (gate.end(frameGeneration)) sch.onFrameSkipped(stamp)
                    return
                }
                FrameScheduler.FrameOutcome.SLOT -> Unit
            }
            val fp = face
            val worker = poseWorker
            if (fp == null || worker == null) {
                if (gate.end(frameGeneration)) sch.onPreFaceError(stamp)
                return
            }
            if (tsMs <= lastTimestampMs) {
                // MediaPipe VIDEO mode needs strictly increasing ms timestamps; a raw-monotonic frame can still tie at ms resolution
                nonMonotonic++
                if (nonMonotonic <= 20) listener.onEvent("non_monotonic_frame_ms raw_ts=$rawTs ts_ms=$tsMs last=$lastTimestampMs")
                if (gate.end(frameGeneration)) sch.onPreFaceError(stamp)
                return
            }
            lastTimestampMs = tsMs
            val frame = try {
                wrap(image, captureNs, rawTs)
            } catch (e: Exception) {
                listener.onEvent("wrap_failed ${e.javaClass.simpleName}: ${e.message}")
                if (gate.end(frameGeneration)) sch.onPreFaceError(stamp)
                return
            }
            val wrapMs = (SystemClock.elapsedRealtimeNanos() - entryNs) / 1e6
            val inference = try {
                fp.infer(frame, tsMs)
            } catch (t: Throwable) {
                faceErrors++
                if (faceErrors <= 20) listener.onEvent("face_inference_error ${t.javaClass.simpleName}: ${t.message}")
                if (gate.end(frameGeneration)) sch.onFaceInferenceError(stamp)
                return
            }
            // frames_processed and the slot's `filled` are fixed from here on (정정 3), whatever the later stages do.
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
                        submitted.superseded?.let { listener.onPoseSuperseded(it) }
                        listener.onPoseRequested(stamp, submitted.copyMs)
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
                    listener.onScene(SceneSample(captureNs, st.lumaMean, st.tileTextureMin, st.tileTextureMedian, computeMs = sceneMs, rawSensorTs = rawTs))
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
                    rawSensorTs = rawTs,
                )
            } catch (t: Throwable) {
                postFaceErrors++
                if (postFaceErrors <= 20) listener.onEvent("post_face_failed ${t.javaClass.simpleName}: ${t.message}")
                sample = null
            }
            val totalNs = SystemClock.elapsedRealtimeNanos() - entryNs
            // frames_processed is the counter that means "Face succeeded"; the increment itself happens on the
            // aggregation thread when this message is applied. A frame cancelled at stop never sends it.
            if (gate.end(frameGeneration)) {
                val tq3 = SystemClock.elapsedRealtimeNanos()
                if (!sch.onFaceSucceeded(stamp)) listener.onEvent("slot_not_open_on_face_success raw_ts=$rawTs")
                listener.onFrameProcessed(ProcessedFrame(captureNs, inference.inferMs, sample?.copy(totalMs = totalNs / 1e6), rawSensorTs = rawTs))
                carriedEnqueueNs = SystemClock.elapsedRealtimeNanos() - tq3
            }
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
    private fun wrap(image: ImageProxy, captureNs: Long, rawTs: Long): RgbaFrame {
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
        return RgbaFrame(pixels, w, h, image.imageInfo.rotationDegrees, captureNs, rawTs)
    }

    /** One-line frame-path statistics for the event log. */
    fun stats(): String = "frames zero_copy=$zeroCopyFrames copied=$copiedFrames non_monotonic=$nonMonotonic capture_failures=$captureFailures " +
        "face_inference_errors=$faceErrors post_face_failed=$postFaceErrors outcomes_suppressed_at_stop=${gate.suppressed} " +
        "pose_submitted=${poseWorker?.submitted ?: 0} pose_results_suppressed_at_stop=${poseWorker?.suppressedResults ?: 0} " +
        "jitter_skipped_fast_rotation=${face?.jitterSkippedFastRotation ?: 0} perf_hint_reports=$hintReports fps_result=${fpsEffective ?: "unknown"} " +
        "session_start_raw=$sessionStartRaw offset_frozen=${timebase?.cameraOffsetFrozen} ${scheduler?.stats() ?: "scheduler=none"}"

    companion object {
        const val SELF_CHECK_WIDTH = 640
        const val SELF_CHECK_HEIGHT = 480

        /** Pose cadence while no face is detected: ≥ 3 fps. */
        const val POSE_FAST_PERIOD_NS: Long = 300_000_000L
    }
}
