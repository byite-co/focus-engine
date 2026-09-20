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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import co.byite.focus.core.aggregate.AggregatedSecond
import co.byite.focus.core.aggregate.FeatureAggregator
import co.byite.focus.core.aggregate.FrameSample
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.core.aggregate.SceneSample
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TaskMode
import co.byite.focus.core.model.TimebaseRecord
import co.byite.focus.core.report.V0bReport
import co.byite.focus.core.util.Stats
import co.byite.focus.engine.pipeline.camera.CameraPipeline
import co.byite.focus.engine.timebase.Timebase
import androidx.camera.lifecycle.ProcessCameraProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * camera-type foreground service: the measurement session. Must be started while an Activity is
 * visible (spec 7장 플랫폼 제약). CameraX is bound to this service's lifecycle, a partial wake lock is
 * held, and one analysis thread runs Face / Pose / Scene inference and the 1 Hz aggregation.
 *
 * Intents: [ACTION_START], [ACTION_STOP], [ACTION_SET_MARKER] (+ [EXTRA_LABEL]).
 */
class CaptureService : LifecycleService(), CameraPipeline.Listener {

    private var running = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: CameraPipeline? = null
    private var motion: MotionPipeline? = null
    private var files: SessionFiles? = null
    private var logger: FeatureLogger? = null
    private var aggregator: FeatureAggregator? = null
    private var timebase: Timebase? = null
    private var header: SessionHeader? = null
    private var analysisThread: HandlerThread? = null
    private var ioThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var ioExecutor: Executor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: EnginePrefs
    private lateinit var device: DeviceStatusReader

    // analysis thread only
    private val records = ArrayList<AggregatedSecond>()
    private val timebaseLines = ArrayList<TimebaseRecord>()
    private var startMonoMs = 0L
    private var startUtcMs = 0L
    private var lastTimebaseMs = 0L
    private var eventsOut: LazyWriter? = null
    private var selfCheck: String = "자가 점검: 기록 없음"
    /** Capture results that arrived before the first processed frame fixed the session start. */
    private val pendingRequested = ArrayList<Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = EnginePrefs(this)
        device = DeviceStatusReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession(SessionEndReason.USER)
            ACTION_SET_MARKER -> setMarker(intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() })
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- start

    private fun startSession() {
        if (running) {
            event("start_ignored already_running")
            return
        }
        val files = SessionFiles.create(this)
        this.files = files
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("시작 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
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
        EngineStatus.running = true
        EngineStatus.sessionId = files.sessionId
        EngineStatus.logPath = files.sessionJsonl.absolutePath
        EngineStatus.line = "카메라 여는 중"
        EngineStatus.segmentLabel = null
        EngineStatus.selfCheck = null
        pendingRequested.clear()
        prefs.lastRecoveredSessionId = null
        prefs.lastLogPath = files.sessionJsonl.absolutePath
        prefs.activeSessionId = files.sessionId
        prefs.sessionActive = true

        val at = HandlerThread("focus-analysis", android.os.Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
        val iot = HandlerThread("focus-io", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        analysisThread = at
        ioThread = iot
        val ah = Handler(at.looper)
        val ih = Handler(iot.looper)
        analysisHandler = ah
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
        val cam = CameraPipeline(this, ah, this)
        val check = try {
            "자가 점검: " + cam.prepare()
        } catch (t: Throwable) {
            // Errors (NoClassDefFoundError, UnsatisfiedLinkError) included: this is what the stubbed
            // telemetry dependency would surface as, and it must be visible, not a silent crash.
            Log.e(TAG, "self-check failed", t)
            val msg = "자가 점검 실패: ${t.javaClass.name}: ${t.message}"
            selfCheck = msg
            EngineStatus.selfCheck = msg
            prefs.lastSelfCheck = msg
            prefs.lastSummary = msg
            event("self_check_failed ${t.javaClass.name}: ${t.message}")
            runCatching { cam.release() }
            mainHandler.post { stopSession(SessionEndReason.UNKNOWN) }
            return
        }
        selfCheck = check
        EngineStatus.selfCheck = check
        prefs.lastSelfCheck = check
        event("self_check $check")
        // From here every stop path releases the landmarkers through finishOnAnalysisThread.
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
            ah.post {
                timebase = cam.timebase
                event(
                    "camera_bound camera=${facts.cameraId} ${facts.width}x${facts.height} fps_selected=${facts.fpsSelected} " +
                        "fps_available=${facts.fpsAvailable} ts_source=${facts.timestampSource} ${facts.stabilization}",
                )
                EngineStatus.line = "$check\n카메라 바인딩됨 ${facts.width}x${facts.height} fps ${facts.fpsSelected} ts ${facts.timestampSource} · 첫 프레임 대기"
                mainHandler.post { updateNotification("카메라 ${facts.width}x${facts.height} fps ${facts.fpsSelected}") }
            }
        }
    }

    // ---- CameraPipeline.Listener (analysis thread)

    /** The session starts at the first processed frame's capture time: buckets align to it and bucket 0 always has a scene sample. */
    override fun onSessionStart(captureMonoMs: Long) {
        val cam = camera ?: return
        val tb = timebase ?: return
        val facts = cam.facts ?: return
        startMonoMs = captureMonoMs
        startUtcMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - captureMonoMs)
        val agg = FeatureAggregator(startMonoMs, startUtcMs)
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
            cameraResolution = "${facts.width}x${facts.height}",
            nominalFps = facts.nominalFps,
            calibrationId = NO_CALIBRATION,
            calibrationSnapshotVersion = NO_CALIBRATION,
            taskMode = TaskMode.VISUAL,
        )
        header = h
        logger?.writeHeader(h)
        val tbRec = tb.record(startMonoMs)
        timebaseLines.add(tbRec)
        logger?.writeTimebase(tbRec)
        lastTimebaseMs = startMonoMs
        val ah = analysisHandler ?: return
        val mp = MotionPipeline(this, ah, tb) { s -> aggregator?.onImu(s) }
        motion = mp
        val imuOk = mp.start()
        event(
            "session_start id=${h.sessionId} t_start_mono_ms=$startMonoMs ${h.cameraResolution} nominal_fps=${facts.nominalFps} " +
                "ts_source=${tb.cameraSourceName} imu=$imuOk engine=${BuildConfig.GIT_SHA} battery_opt_ignored=${device.isIgnoringBatteryOptimizations()}",
        )
        scheduleTick()
    }

    override fun onFrameRequested(captureMonoNs: Long) {
        val agg = aggregator
        if (agg == null) {
            if (pendingRequested.size < PENDING_REQUESTED_MAX) pendingRequested.add(captureMonoNs)
        } else {
            agg.onFrameRequested(captureMonoNs)
        }
    }

    override fun onFrame(frame: FrameSample, pose: PoseSample?, scene: SceneSample?) {
        val agg = aggregator ?: return
        agg.onFrame(frame)
        if (pose != null) agg.onPose(pose)
        if (scene != null) agg.onScene(scene)
    }

    override fun onEvent(message: String) = event(message)

    // ---- 1 Hz tick (analysis thread)

    private val tick = object : Runnable {
        override fun run() {
            if (!running || stopping) return
            closeBuckets(SystemClock.elapsedRealtime(), finishing = false)
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        val ah = analysisHandler ?: return
        val now = SystemClock.elapsedRealtime()
        val period = FocusSchema.RECORD_PERIOD_MS
        val next = startMonoMs + ((now - startMonoMs) / period + 1) * period + FeatureAggregator.DEFAULT_CLOSE_DELAY_MS
        ah.postDelayed(tick, (next - now).coerceAtLeast(1))
    }

    private fun closeBuckets(now: Long, finishing: Boolean) {
        val agg = aggregator ?: return
        val lg = logger ?: return
        val ds = device.read()
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
    }

    private fun statusLine(c: AggregatedSecond, n: Int): String {
        val s = c.second
        val r = c.raw
        fun f(x: Double?, d: Int = 1) = Stats.fmt(x, d)
        return "초 $n · 프레임 요청 ${s.framesRequested} 처리 ${s.framesProcessed} 드롭 ${s.framesDropped} · 최대갭 ${s.maxFrameGapMs ?: "-"}ms\n" +
            "face ${Stats.pct(s.faceDetectRatio, 0)} yaw ${f(s.yawMean)} pitch ${f(s.pitchMean)} roll ${f(s.rollMean)} · 폭 ${f(s.faceWidthPx, 0)}px j ${f(s.jitterJ, 4)}\n" +
            "face ${f(r.faceInferMsMean)}ms pose ${f(r.poseInferMsMean)}ms · 어깨 vis ${f(s.shoulderVisibilityMin, 2)} 머리 ${if (s.headLandmarkPresent) "Y" else "N"} off ${f(s.headOffsetBelowShoulderRatio, 2)} · 휘도 ${f(s.sceneLuma, 0)}\n" +
            "thermal ${r.thermalStatus} 배터리 ${r.batteryPct ?: "?"}% ${r.batteryCurrentUa ?: "?"}µA · 화면 ${if (r.isInteractive) "on" else "off"} idle ${r.isDeviceIdle} · 마커 ${r.segmentLabel ?: "-"}"
    }

    private fun setMarker(label: String?) {
        val ah = analysisHandler
        if (!running || ah == null) return
        ah.post {
            aggregator?.setSegmentLabel(label, SystemClock.elapsedRealtime())
            EngineStatus.segmentLabel = label
            event("marker ${label ?: "(none)"}")
        }
    }

    // ---- stop

    private fun stopSession(reason: SessionEndReason) {
        if (!running || stopping) {
            if (!running) stopSelf()
            return
        }
        stopping = true
        motion?.stop()
        camera?.unbind()
        val ah = analysisHandler
        if (ah == null) {
            finishService("")
            return
        }
        ah.post {
            val summary = finishOnAnalysisThread(reason)
            mainHandler.post { finishService(summary) }
        }
    }

    /** Analysis thread: close complete buckets, write the end marker, build and store the summary. */
    private fun finishOnAnalysisThread(reason: SessionEndReason): String {
        val now = SystemClock.elapsedRealtime()
        closeBuckets(now, finishing = true)
        camera?.let { event(it.stats()) }
        camera?.release()
        val lg = logger
        val h = header
        val agg = aggregator
        if (lg == null || h == null || agg == null) {
            event("session_stop reason=$reason (no header)")
            logger?.close()
            logger?.awaitIdle(3000)
            return selfCheck + "\n세션이 첫 프레임 전에 끝났다: $reason"
        }
        val end = SessionEnd(now, System.currentTimeMillis(), reason)
        lg.writeSessionEnd(end)
        lg.close()
        val notes = ArrayList<String>()
        if (agg.lateInputs > 0) notes.add("닫힌 버킷에 늦게 도착한 입력 ${agg.lateInputs}건은 버렸다.")
        if (agg.nonMonotonicFrames > 0) notes.add("capture timestamp 가 역행한 프레임 ${agg.nonMonotonicFrames}개.")
        val log = SessionLog(h, records.map { it.second }, sessionEnd = end, timebase = timebaseLines.toList(), v0bRaw = records.map { it.raw })
        val summary = selfCheck + "\n" + V0bReport.build(log, notes).render()
        event("session_stop reason=$reason records=${records.size}")
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
        eventsOut?.close()
        return summary
    }

    private fun finishService(summary: String) {
        running = false
        EngineStatus.running = false
        EngineStatus.line = "정지됨"
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
        ioThread?.quitSafely()
        analysisThread = null
        ioThread = null
        analysisHandler = null
        ioExecutor = null
        camera = null
        motion = null
        aggregator = null
        logger = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running && !stopping) {
            // The system is tearing the service down. Flush what we have; the next launch closes the
            // session at the last record with session_end(PROCESS_DEATH_RECOVERED) (v0.2.1 판정 10).
            stopping = true
            motion?.stop()
            camera?.unbind()
            val ah = analysisHandler
            if (ah != null) {
                val latch = CountDownLatch(1)
                ah.post {
                    try {
                        closeBuckets(SystemClock.elapsedRealtime(), finishing = true)
                        event("service_destroyed records=${records.size}")
                        logger?.close()
                        logger?.awaitIdle(2000)
                        eventsOut?.close()
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(4, TimeUnit.SECONDS)
            }
            running = false
            EngineStatus.running = false
            EngineStatus.line = "서비스가 시스템에 의해 종료됨 (다음 실행 때 요약을 복원한다)"
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (e: Exception) {
                Log.w(TAG, "wakelock release failed", e)
            }
            analysisThread?.quitSafely()
            ioThread?.quitSafely()
        }
        super.onDestroy()
    }

    // ---- events (scalars and strings only)

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
        const val PARTICIPANT_ID = "dev"
        /** header calibration_id / calibration_snapshot_version before V0-C (CHANGELOG v0.2.2 (a)). */
        const val NO_CALIBRATION = "none"
        private const val PENDING_REQUESTED_MAX = 300
        private const val CHANNEL_ID = "focus-engine-capture"
        private const val NOTIFICATION_ID = 11
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
        private const val TIMEBASE_PERIOD_MS = 60_000L

        fun startIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
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
