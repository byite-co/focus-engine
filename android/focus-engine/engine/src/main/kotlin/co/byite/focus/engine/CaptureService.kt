package co.byite.focus.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import co.byite.focus.core.aggregate.AggregatedSecond
import co.byite.focus.core.aggregate.CounterTotals
import co.byite.focus.core.aggregate.DeviceSample
import co.byite.focus.core.aggregate.FeatureAggregator
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.core.aggregate.ProcessedFrame
import co.byite.focus.core.aggregate.SceneSample
import co.byite.focus.core.aggregate.StopResult
import co.byite.focus.core.aggregate.StopSequence
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TaskMode
import co.byite.focus.core.model.TimebaseRecord
import co.byite.focus.core.report.StopSummary
import co.byite.focus.core.report.V0bReport
import co.byite.focus.core.util.Stats
import co.byite.focus.engine.pipeline.camera.CameraFacts
import co.byite.focus.engine.pipeline.camera.CameraPipeline
import co.byite.focus.engine.timebase.Timebase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * camera-type foreground service: the measurement session. Must be started while an Activity is
 * visible (spec 7장 플랫폼 제약). CameraX is bound to this service's lifecycle and a partial wake lock is held.
 *
 * Thread model (directive D 정정 3, README "스레드 모델"):
 * - `main`: service lifecycle, camera bind / unbind, notifications.
 * - `focus-analysis`: CameraX analyzer — Face, Scene, frame counting, skip rule, Pose hand-off (deep copy), preset G hint session.
 * - `focus-pose`: [co.byite.focus.engine.pipeline.pose.PoseWorker], depth-1 queue.
 * - `focus-aggregate`: the only thread that touches [FeatureAggregator], the records and the logger's queue; 1 Hz bucket close.
 * - `focus-io`: JSONL encoding + file writes, events.log, summary.txt.
 * - `focus-status`: 1 Hz device status sampling (binder calls) and the IMU listener.
 * - `focus-stop`: runs the [StopSequence] of a normal stop.
 * Every pipeline result is a scalar sample *posted* to `focus-aggregate`; nothing else calls the aggregator.
 *
 * Intents: [ACTION_START] (+ [EXTRA_PRESET]), [ACTION_STOP], [ACTION_SET_MARKER] (+ [EXTRA_LABEL]).
 */
class CaptureService : LifecycleService(), CameraPipeline.Listener {

    @Volatile private var running = false
    @Volatile private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var camera: CameraPipeline? = null
    @Volatile private var motion: MotionPipeline? = null
    private var files: SessionFiles? = null
    @Volatile private var logger: FeatureLogger? = null
    private var preset: CapturePreset = CapturePreset.DEFAULT
    private var analysisThread: HandlerThread? = null
    private var aggregateThread: HandlerThread? = null
    private var ioThread: HandlerThread? = null
    private var statusThread: HandlerThread? = null
    @Volatile private var analysisHandler: Handler? = null
    @Volatile private var aggHandler: Handler? = null
    @Volatile private var statusHandler: Handler? = null
    @Volatile private var ioExecutor: Executor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: EnginePrefs
    private lateinit var device: DeviceStatusReader
    @Volatile private var eventsOut: LazyWriter? = null
    @Volatile private var selfCheck: String = "자가 점검: 기록 없음"

    // aggregation thread only
    private var aggregator: FeatureAggregator? = null
    private val records = ArrayList<AggregatedSecond>()
    private val timebaseLines = ArrayList<TimebaseRecord>()
    private var timebase: Timebase? = null
    private var header: SessionHeader? = null
    private var startMonoMs = 0L
    private var startUtcMs = 0L
    private var lastTimebaseMs = 0L
    private var latestDevice: DeviceSample? = null
    /** Capture results that arrived before the first frame fixed the session start. */
    private val pendingRequested = ArrayList<Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = EnginePrefs(this)
        device = DeviceStatusReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(CapturePreset.byId(intent.getStringExtra(EXTRA_PRESET)))
            ACTION_STOP -> stopSession(SessionEndReason.USER)
            ACTION_SET_MARKER -> setMarker(intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() })
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- start

    private fun startSession(preset: CapturePreset) {
        if (running) {
            event("start_ignored already_running")
            return
        }
        val files = SessionFiles.create(this)
        this.files = files
        this.preset = preset
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("시작 중 (프리셋 ${preset.id})"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            prefs.lastSummary = "startForeground 실패: ${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
            return
        }
        running = true
        stopping = false
        records.clear()
        timebaseLines.clear()
        pendingRequested.clear()
        aggregator = null
        header = null
        latestDevice = null
        EngineStatus.running = true
        EngineStatus.sessionId = files.sessionId
        EngineStatus.logPath = files.sessionJsonl.absolutePath
        EngineStatus.line = "카메라 여는 중"
        EngineStatus.segmentLabel = null
        EngineStatus.selfCheck = null
        EngineStatus.preset = preset.id
        prefs.lastRecoveredSessionId = null
        prefs.lastLogPath = files.sessionJsonl.absolutePath
        prefs.activeSessionId = files.sessionId
        prefs.lastPreset = preset.id
        prefs.sessionActive = true

        val at = HandlerThread("focus-analysis", android.os.Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
        val agt = HandlerThread("focus-aggregate").also { it.start() }
        val iot = HandlerThread("focus-io", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        val stt = HandlerThread("focus-status").also { it.start() }
        analysisThread = at
        aggregateThread = agt
        ioThread = iot
        statusThread = stt
        val ah = Handler(at.looper)
        val ih = Handler(iot.looper)
        analysisHandler = ah
        aggHandler = Handler(agt.looper)
        statusHandler = Handler(stt.looper)
        val io = Executor { r -> ih.post(r) }
        ioExecutor = io
        eventsOut = LazyWriter(files.eventsLog)
        logger = FeatureLogger(files.sessionJsonl, io) { what, e -> Log.w(TAG, what, e) }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "focus-engine:capture").also { it.acquire(WAKE_LOCK_TIMEOUT_MS) }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                ah.post { openSession(provider) }
            } catch (e: Exception) {
                Log.e(TAG, "camera provider failed", e)
                event("camera_provider_failed ${e.javaClass.simpleName}: ${e.message}")
                mainHandler.post { stopSession(SessionEndReason.UNKNOWN) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analysis thread: self-check (models + one synthetic frame), then bind the camera on the main thread. */
    private fun openSession(provider: ProcessCameraProvider) {
        val ah = analysisHandler ?: return
        val cam = CameraPipeline(this, ah, this, preset)
        val check = try {
            "자가 점검: " + cam.prepare()
        } catch (t: Throwable) {
            // Errors (NoClassDefFoundError, UnsatisfiedLinkError) included: this is what the stubbed
            // telemetry dependency or a missing GPU delegate would surface as, and it must be visible, not a silent crash.
            Log.e(TAG, "self-check failed", t)
            val msg = "자가 점검 실패 (프리셋 ${preset.id}): ${t.javaClass.name}: ${t.message}"
            selfCheck = msg
            EngineStatus.selfCheck = msg
            prefs.lastSelfCheck = msg
            prefs.lastSummary = msg
            event("self_check_failed preset=${preset.id} ${t.javaClass.name}: ${t.message}")
            runCatching { cam.release() }
            mainHandler.post { stopSession(SessionEndReason.UNKNOWN) }
            return
        }
        selfCheck = check
        EngineStatus.selfCheck = check
        prefs.lastSelfCheck = check
        event("self_check $check")
        // From here every stop path releases the landmarkers on the analysis thread.
        camera = cam
        mainHandler.post {
            if (!running || stopping) return@post
            val bound = try {
                cam.bind(provider, this)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                event("camera_bind_failed ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!bound) {
                event("no_front_camera_or_bind_failed")
                stopSession(SessionEndReason.UNKNOWN)
                return@post
            }
            val facts = cam.facts!!
            event(
                "camera_bound camera=${facts.cameraId} chosen=${facts.width}x${facts.height} requested=${facts.requestedWidth}x${facts.requestedHeight} preset=${facts.presetId} " +
                    "fps_selected=${facts.fpsSelected} fps_available=${facts.fpsAvailable} ts_source=${facts.timestampSource} ${facts.stabilization} gap_threshold_ms=${facts.gapThresholdMs} perf_hint=${facts.perfHint}",
            )
            EngineStatus.preset = facts.presetId
            EngineStatus.line = "$check\n카메라 바인딩됨 ${facts.width}x${facts.height} fps ${facts.fpsSelected} ts ${facts.timestampSource} · 첫 프레임 대기"
            updateNotification("프리셋 ${facts.presetId} · 카메라 ${facts.width}x${facts.height} fps ${facts.fpsSelected}")
        }
    }

    // ---- CameraPipeline.Listener: analysis / pose threads → aggregation queue

    private inline fun toAggregator(crossinline block: FeatureAggregator.() -> Unit) {
        aggHandler?.post { aggregator?.block() }
    }

    override fun onSessionStart(captureMonoMs: Long, frameWidth: Int, frameHeight: Int) {
        val cam = camera ?: return
        val facts = cam.facts ?: return
        val tb = cam.timebase ?: return
        aggHandler?.post { startAggregation(captureMonoMs, frameWidth, frameHeight, facts, tb) }
    }

    override fun onFrameRequested(captureMonoNs: Long) {
        aggHandler?.post {
            val agg = aggregator
            if (agg == null) {
                if (pendingRequested.size < PENDING_REQUESTED_MAX) pendingRequested.add(captureMonoNs)
            } else {
                agg.onFrameRequested(captureMonoNs)
            }
        }
    }

    override fun onFrameReceived(captureMonoNs: Long) = toAggregator { onFrameReceived(captureMonoNs) }
    override fun onFrameSkipped(captureMonoNs: Long) = toAggregator { onFrameSkipped(captureMonoNs) }
    override fun onFaceInferenceError(captureMonoNs: Long) = toAggregator { onFaceInferenceError(captureMonoNs) }
    override fun onPreFaceError(captureMonoNs: Long) = toAggregator { onPreFaceError(captureMonoNs) }
    override fun onFrameProcessed(frame: ProcessedFrame) = toAggregator { onFrameProcessed(frame) }
    override fun onScene(sample: SceneSample) = toAggregator { onScene(sample) }
    override fun onPoseRequested(captureMonoNs: Long, copyMs: Double) = toAggregator { onPoseRequested(captureMonoNs, copyMs) }
    override fun onPoseSuperseded(captureMonoNs: Long) = toAggregator { onPoseSuperseded(captureMonoNs) }
    override fun onPose(sample: PoseSample) = toAggregator { onPose(sample) }
    override fun onPoseError(captureMonoNs: Long) = toAggregator { onPoseError(captureMonoNs) }
    override fun onEvent(message: String) = event(message)

    // ---- aggregation thread

    /** The session starts at the first received frame's capture time: buckets align to it and bucket 0 always has a scene sample. */
    private fun startAggregation(captureMonoMs: Long, frameWidth: Int, frameHeight: Int, facts: CameraFacts, tb: Timebase) {
        if (aggregator != null) return
        timebase = tb
        startMonoMs = captureMonoMs
        startUtcMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - captureMonoMs)
        val agg = FeatureAggregator(
            startMonoMs, startUtcMs,
            gapThresholdNs = facts.gapThresholdMs * FeatureAggregator.NS_PER_MS,
            longGapThresholdNs = facts.longGapThresholdMs * FeatureAggregator.NS_PER_MS,
        )
        aggregator = agg
        for (t in pendingRequested) agg.onFrameRequested(t)
        pendingRequested.clear()
        val h = SessionHeader(
            sessionId = files!!.sessionId,
            participantId = PARTICIPANT_ID,
            tStartMonoMs = startMonoMs,
            tStartUtcMs = startUtcMs,
            specVersion = FocusSchema.SPEC_VERSION,
            algorithmVersion = BuildConfig.GIT_SHA,
            featureSchemaVersion = FocusSchema.FEATURE_SCHEMA_VERSION,
            parameterSetId = ParameterSet.DEFAULT.parameterSetId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cameraResolution = "${frameWidth}x$frameHeight",
            nominalFps = facts.nominalFps,
            calibrationId = NO_CALIBRATION,
            calibrationSnapshotVersion = NO_CALIBRATION,
            taskMode = TaskMode.VISUAL,
            capturePreset = facts.presetId,
            frameProcessDivisor = preset.frameProcessDivisor,
            frameGapThresholdMs = facts.gapThresholdMs,
            faceDelegate = preset.faceDelegate.name,
            faceBlendshapes = preset.faceBlendshapes,
            perfHintTargetMs = if (facts.perfHint != "none") preset.perfHintTargetMs else null,
            frameLongGapThresholdMs = facts.longGapThresholdMs,
            cameraId = facts.cameraId,
            lensFacing = facts.lensFacing,
            foldable = device.foldable,
        )
        header = h
        logger?.writeHeader(h)
        val tbRec = tb.record(startMonoMs)
        timebaseLines.add(tbRec)
        logger?.writeTimebase(tbRec)
        lastTimebaseMs = startMonoMs
        val sh = statusHandler ?: return
        val ag = aggHandler ?: return
        val mp = MotionPipeline(this, sh, tb) { s -> ag.post { aggregator?.onImu(s) } }
        motion = mp
        val imuOk = mp.start()
        val hingeOk = device.startHingeMonitor(sh)
        sh.post(statusTick)
        event(
            "session_start id=${h.sessionId} t_start_mono_ms=$startMonoMs ${h.cameraResolution} (${h.cameraAspectRatio}) nominal_fps=${facts.nominalFps} preset=${h.capturePreset} " +
                "divisor=${h.frameProcessDivisor} gap_threshold_ms=${h.frameGapThresholdMs} face=${h.faceDelegate} blendshapes=${h.faceBlendshapes} perf_hint_ms=${h.perfHintTargetMs} " +
                "camera_id=${h.cameraId} lens=${h.lensFacing} foldable=${h.foldable} hinge_sensor=$hingeOk long_gap_threshold_ms=${h.frameLongGapThresholdMs} " +
                "ts_source=${tb.cameraSourceName} imu=$imuOk engine=${BuildConfig.GIT_SHA} battery_opt_ignored=${device.isIgnoringBatteryOptimizations()}",
        )
        scheduleTick()
    }

    /** Status thread: 1 Hz device status (binder calls) → posted to the aggregation queue. */
    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val sample = try {
                device.read()
            } catch (e: Exception) {
                Log.w(TAG, "device status read failed", e)
                null
            }
            if (sample != null) aggHandler?.post { latestDevice = sample }
            if (!stopping) statusHandler?.postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || stopping) return
            closeBuckets(SystemClock.elapsedRealtime(), finishing = false)
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        val ag = aggHandler ?: return
        val now = SystemClock.elapsedRealtime()
        val period = FocusSchema.RECORD_PERIOD_MS
        val next = startMonoMs + ((now - startMonoMs) / period + 1) * period + FeatureAggregator.DEFAULT_CLOSE_DELAY_MS
        ag.postDelayed(tick, (next - now).coerceAtLeast(1))
    }

    private fun currentDevice(): DeviceSample = latestDevice ?: device.read().also { latestDevice = it }

    private fun closeBuckets(now: Long, finishing: Boolean): List<AggregatedSecond> {
        val agg = aggregator ?: return emptyList()
        val lg = logger ?: return emptyList()
        val ds = currentDevice()
        val closed = if (finishing) agg.finish(now, ds) else agg.closeBuckets(now, ds)
        for (c in closed) {
            records.add(c)
            lg.append(c)
        }
        closed.lastOrNull()?.let { EngineStatus.line = statusLine(it, records.size) }
        if (!finishing && now - lastTimebaseMs >= TIMEBASE_PERIOD_MS) {
            lastTimebaseMs = now
            timebase?.record(now)?.let { rec ->
                timebaseLines.add(rec)
                lg.writeTimebase(rec)
            }
        }
        return closed
    }

    private fun statusLine(c: AggregatedSecond, n: Int): String {
        val s = c.second
        val r = c.raw
        fun f(x: Double?, d: Int = 1) = Stats.fmt(x, d)
        return "초 $n · 요청 ${s.framesRequested} 수신 ${s.framesAnalyzerReceived} 건너뜀 ${s.framesSkippedIntentional} 처리 ${s.framesProcessed} 반영 ${s.framesSampleApplied} 드롭 ${s.framesDropped} · 최대갭 ${s.maxFrameGapMs ?: "-"}ms 갭> ${s.gapsOverThreshold}\n" +
            "face ${Stats.pct(s.faceDetectRatio, 0)} yaw ${f(s.yawMean)} pitch ${f(s.pitchMean)} roll ${f(s.rollMean)} · 폭 ${f(s.faceWidthPx, 0)}px j ${f(s.jitterJ, 4)}\n" +
            "face ${f(r.faceInferMsMean)}ms 사이클 ${f(r.frameTotalMsMean)}ms pose ${f(r.poseInferMsMean)}ms (요청 ${r.poseRequested} 반영 ${r.poseApplied} 교체 ${r.poseSuperseded} 늦음 ${r.poseLateDropped}) copy ${f(r.poseFrameCopyMsMean, 2)}ms\n" +
            "어깨 vis ${f(s.shoulderVisibilityMin, 2)} 머리 ${if (s.headLandmarkPresent) "Y" else "N"} off ${f(s.headOffsetBelowShoulderRatio, 2)} · 휘도 ${f(s.sceneLuma, 0)} · " +
            "thermal ${r.thermalStatus} 배터리 ${r.batteryPct ?: "?"}% ${r.batteryCurrentUa ?: "?"}µA · 화면 ${if (r.isInteractive) "on" else "off"} idle ${r.isDeviceIdle} · 마커 ${r.segmentLabel ?: "-"}"
    }

    private fun setMarker(label: String?) {
        val ag = aggHandler
        if (!running || ag == null) return
        ag.post {
            aggregator?.setSegmentLabel(label, SystemClock.elapsedRealtime())
            EngineStatus.segmentLabel = label
            event("marker ${label ?: "(none)"}")
        }
    }

    // ---- stop (정정 5: normal stop order)

    private fun stopSession(reason: SessionEndReason) {
        if (!running || stopping) {
            if (!running) stopSelf()
            return
        }
        stopping = true
        Thread({ runStopSequence(reason) }, "focus-stop").start()
    }

    /** `focus-stop` thread: the [StopSequence] contract, then thread teardown on the main thread. */
    private fun runStopSequence(reason: SessionEndReason) {
        var summary = ""
        val seq = StopSequence(
            stopInputs = {
                var fence = 0L
                runOn(mainHandler, 5_000L) {
                    motion?.stop()
                    device.stopHingeMonitor()
                    camera?.unbind()
                    fence = SystemClock.elapsedRealtime()
                }
                if (fence == 0L) fence = SystemClock.elapsedRealtime()
                fence
            },
            raiseFence = { fence -> aggHandler?.post { aggregator?.stopInputs(fence) } },
            awaitAnalysisIdle = { timeout -> if (awaitIdle(analysisHandler, timeout)) 0L else 1L },
            closePoseSlot = { camera?.closePoseSlot() },
            awaitPoseIdle = { timeout -> camera?.awaitPoseIdle(timeout) ?: 0L },
            drainAggregationQueue = { timeout -> awaitIdle(statusHandler, timeout) && awaitIdle(aggHandler, timeout) },
            finish = { _ ->
                var totals = CounterTotals()
                runOn(aggHandler, 10_000L) {
                    closeBuckets(SystemClock.elapsedRealtime(), finishing = true)
                    totals = aggregator?.totals ?: CounterTotals()
                }
                records.toList() to totals
            },
            writeEnd = { result ->
                runOn(aggHandler, 10_000L) { summary = writeEndOnAggregationThread(reason, result) }
            },
        )
        val result = try {
            seq.run()
        } catch (t: Throwable) {
            Log.e(TAG, "stop sequence failed", t)
            event("stop_sequence_failed ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (result != null) {
            event(
                "stop_sequence steps=${result.steps.joinToString(">")} fence=${result.fenceMonoMs} frames_cancelled=${result.framesCancelledAtStop} " +
                    "pose_cancelled=${result.poseCancelledAtStop} drained=${result.queueDrained} mismatches=${result.mismatches.size}",
            )
        }
        runOn(analysisHandler, 10_000L) {
            camera?.let { cam ->
                event(cam.stats())
                cam.release()
            }
        }
        closeEventsOnIo()
        val out = if (summary.isNotEmpty()) summary else selfCheck + "\n세션 종료 처리 실패: $reason"
        mainHandler.post { finishService(out) }
    }

    /** events.log is written on the IO thread only, so it is closed there too (after every queued line). */
    private fun closeEventsOnIo() {
        val out = eventsOut ?: return
        val io = ioExecutor
        if (io == null) {
            out.close()
            return
        }
        val latch = CountDownLatch(1)
        io.execute {
            try {
                out.close()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    /** Aggregation thread (stop step 7): session_end, summary with the counter check, summary.txt. */
    private fun writeEndOnAggregationThread(reason: SessionEndReason, r: StopResult): String {
        val lg = logger
        val h = header
        val agg = aggregator
        if (lg == null || h == null || agg == null) {
            event("session_stop reason=$reason (no header)")
            logger?.close()
            logger?.awaitIdle(3000)
            return selfCheck + "\n세션이 첫 프레임 전에 끝났다: $reason"
        }
        val now = SystemClock.elapsedRealtime()
        val end = SessionEnd(now, System.currentTimeMillis(), reason)
        lg.writeSessionEnd(end)
        lg.close()
        val notes = ArrayList<String>()
        if (agg.lateInputs > 0) notes.add("닫힌 버킷에 늦게 도착한 scene/IMU 표본 ${agg.lateInputs}건은 버렸다.")
        if (agg.nonMonotonicFrames > 0) notes.add("capture timestamp 가 역행한 처리 프레임 ${agg.nonMonotonicFrames}개.")
        if (agg.inputsBeforeStart > 0) notes.add("세션 시작 전 timestamp 의 입력 ${agg.inputsBeforeStart}건은 계수에서 뺐다.")
        if (agg.inputsAfterFence > 0) notes.add("정지 fence(${r.fenceMonoMs}) 이후 timestamp 의 입력 ${agg.inputsAfterFence}건은 계수에서 뺐다.")
        if (!r.queueDrained) notes.add("aggregation 큐 barrier 가 제한 시간 안에 돌아오지 않았다. 마지막 레코드가 불완전할 수 있다.")
        if (r.framesCancelledAtStop > 0) notes.add("정지 시 분석 스레드의 Face 작업 ${r.framesCancelledAtStop}건이 제한 시간 안에 끝나지 않아 취소로 셌다.")
        val stop = StopSummary(checked = true, mismatches = r.mismatches, framesCancelledAtStop = r.framesCancelledAtStop, poseCancelledAtStop = r.poseCancelledAtStop, totals = r.totals)
        val log = SessionLog(h, records.map { it.second }, sessionEnd = end, timebase = timebaseLines.toList(), v0bRaw = records.map { it.raw })
        val summary = selfCheck + "\n" + V0bReport.build(log, notes, stop).render()
        val t = r.totals
        event(
            "session_stop reason=$reason records=${records.size} totals requested=${t.framesRequested} received=${t.framesAnalyzerReceived} skipped=${t.framesSkippedIntentional} " +
                "processed=${t.framesProcessed} applied=${t.framesSampleApplied} sample_late=${t.framesSampleLateDropped} face_err=${t.faceInferenceErrors} pre_face_err=${t.preFaceErrors} " +
                "pose requested=${t.poseRequested} superseded=${t.poseSuperseded} completed=${t.poseCompleted} applied=${t.poseApplied} late=${t.poseLateDropped} errors=${t.poseErrors} " +
                "cancelled=${r.poseCancelledAtStop} before_start=${t.inputsBeforeStart} after_fence=${t.inputsAfterFence}",
        )
        for (m in r.mismatches) event("counter_mismatch $m")
        files?.let { f ->
            val done = CountDownLatch(1)
            ioExecutor?.execute {
                try {
                    f.summaryTxt.writeText(summary + "\n")
                } catch (e: Exception) {
                    Log.w(TAG, "summary write failed", e)
                } finally {
                    done.countDown()
                }
            }
            done.await(3, TimeUnit.SECONDS)
        }
        lg.awaitIdle(3000)
        return summary
    }

    /** Post a marker and wait for it: true when everything queued before it has run. */
    private fun awaitIdle(handler: Handler?, timeoutMs: Long): Boolean {
        val h = handler ?: return true
        val latch = CountDownLatch(1)
        if (!h.post { latch.countDown() }) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Run [block] on [handler]'s thread and wait for it (bounded). */
    private fun runOn(handler: Handler?, timeoutMs: Long, block: () -> Unit): Boolean {
        val h = handler ?: return false
        val latch = CountDownLatch(1)
        val posted = h.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "runOn block failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun finishService(summary: String) {
        running = false
        EngineStatus.running = false
        EngineStatus.line = "정지됨"
        EngineStatus.preset = null
        prefs.lastSummary = summary
        prefs.sessionActive = false
        prefs.activeSessionId = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakelock release failed", e)
        }
        wakeLock = null
        analysisThread?.quitSafely()
        aggregateThread?.quitSafely()
        statusThread?.quitSafely()
        ioThread?.quitSafely()
        analysisThread = null
        aggregateThread = null
        statusThread = null
        ioThread = null
        analysisHandler = null
        aggHandler = null
        statusHandler = null
        ioExecutor = null
        camera = null
        motion = null
        aggregator = null
        logger = null
        eventsOut = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running && !stopping) {
            // The system is tearing the service down. Flush what we have; the next launch closes the
            // session at the last record with session_end(PROCESS_DEATH_RECOVERED) (v0.2.1 판정 10) and the
            // recovered summary skips the counter check.
            stopping = true
            motion?.stop()
            device.stopHingeMonitor()
            camera?.unbind()
            runOn(aggHandler, 4_000L) {
                closeBuckets(SystemClock.elapsedRealtime(), finishing = true)
                event("service_destroyed records=${records.size}")
                logger?.close()
                logger?.awaitIdle(2000)
            }
            runOn(analysisHandler, 2_000L) { camera?.release() }
            closeEventsOnIo()
            running = false
            EngineStatus.running = false
            EngineStatus.preset = null
            EngineStatus.line = "서비스가 시스템에 의해 종료됨 (다음 실행 때 요약을 복원한다)"
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (e: Exception) {
                Log.w(TAG, "wakelock release failed", e)
            }
            analysisThread?.quitSafely()
            aggregateThread?.quitSafely()
            statusThread?.quitSafely()
            ioThread?.quitSafely()
        }
        super.onDestroy()
    }

    // ---- events (scalars and strings only; any thread → IO thread)

    private fun event(msg: String) {
        Log.i(TAG, msg)
        val line = "${SystemClock.elapsedRealtime()} ${SessionFiles.localTime(System.currentTimeMillis())} $msg"
        val out = eventsOut ?: return
        ioExecutor?.execute { out.writeLine(line) }
    }

    // ---- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.focus_engine_notif_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.focus_engine_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }

    companion object {
        private const val TAG = "FocusCapture"
        const val ACTION_START = "co.byite.focus.engine.action.START"
        const val ACTION_STOP = "co.byite.focus.engine.action.STOP"
        const val ACTION_SET_MARKER = "co.byite.focus.engine.action.SET_MARKER"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PRESET = "preset"
        const val PARTICIPANT_ID = "dev"
        /** header calibration_id / calibration_snapshot_version before V0-C (CHANGELOG v0.2.2 (a)). */
        const val NO_CALIBRATION = "none"
        private const val PENDING_REQUESTED_MAX = 300
        private const val CHANNEL_ID = "focus-engine-capture"
        private const val NOTIFICATION_ID = 11
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
        private const val TIMEBASE_PERIOD_MS = 60_000L
        private const val STATUS_PERIOD_MS = 1_000L

        fun startIntent(context: Context, preset: CapturePreset = CapturePreset.DEFAULT): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_PRESET, preset.id)
        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
        fun markerIntent(context: Context, label: String?): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_SET_MARKER).putExtra(EXTRA_LABEL, label ?: "")
    }
}

/** Append-only text writer opened on first use; IO thread only. */
class LazyWriter(private val file: File) : AutoCloseable {
    private var w: java.io.BufferedWriter? = null

    private fun writer(): java.io.BufferedWriter {
        w?.let { return it }
        file.parentFile?.mkdirs()
        return java.io.BufferedWriter(java.io.FileWriter(file, true)).also { w = it }
    }

    fun writeLine(s: String) {
        try {
            writer().write(s)
            writer().newLine()
            writer().flush()
        } catch (e: Exception) {
            Log.w("FocusCapture", "write failed: ${file.name}", e)
        }
    }

    override fun close() {
        try {
            w?.close()
        } catch (e: Exception) {
            Log.w("FocusCapture", "close failed: ${file.name}", e)
        } finally {
            w = null
        }
    }
}
