package kr.co.byite.focus.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kr.co.byite.focus.spike.core.LumaGrid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * camera 타입 foreground service. CameraX ImageAnalysis 하나를 서비스 lifecycle 에 바인딩한다.
 * Preview 없음. 프레임 픽셀은 분석기 안에서 1Hz 휘도 평균 하나로만 줄이고 즉시 close 한다.
 */
class CaptureService : LifecycleService() {

    private var running = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var recorder: SessionRecorder? = null
    private var files: SessionFiles? = null
    private var captureThread: HandlerThread? = null
    private var writerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var writerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SpikePrefs
    private lateinit var device: DeviceStatusReader

    // 분석기 상태 (capture 스레드에서만)
    private var lastLumaTsNs = Long.MIN_VALUE
    private var firstFrameLogged = false

    override fun onCreate() {
        super.onCreate()
        prefs = SpikePrefs(this)
        device = DeviceStatusReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession(REASON_USER_STOP)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- 시작 ----

    private fun startSession() {
        if (running) {
            recorder?.event("start_ignored already_running")
            return
        }
        val files = SessionFiles.create(this)
        this.files = files
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("시작 중"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException, 그 외 SecurityException 등
            Log.e(TAG, "startForeground failed", e)
            prefs.lastSummary = "startForeground 실패: ${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
            return
        }
        running = true
        stopping = false
        SpikeStatus.running = true
        SpikeStatus.sessionId = files.sessionId
        SpikeStatus.csvPath = files.framesCsv.absolutePath
        SpikeStatus.line = "카메라 여는 중"
        prefs.sessionActive = true
        prefs.activeSessionId = files.sessionId
        prefs.lastCsvPath = files.framesCsv.absolutePath

        val ct = HandlerThread("spike-capture", android.os.Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
        val wt = HandlerThread("spike-writer", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        captureThread = ct
        writerThread = wt
        val ch = Handler(ct.looper)
        val wh = Handler(wt.looper)
        captureHandler = ch
        writerHandler = wh
        val rec = SessionRecorder(files, device, ch, wh)
        recorder = rec

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "focus-spike:capture").also {
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider, rec)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                rec.event("camera_bind_failed ${e.javaClass.simpleName}: ${e.message}")
                mainHandler.post { stopSession(REASON_CAMERA_BIND_FAILED) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, rec: SessionRecorder) {
        if (!running || stopping) return
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        val infos = selector.filter(provider.availableCameraInfos)
        if (infos.isEmpty()) {
            rec.event("no_front_camera")
            stopSession(REASON_NO_FRONT_CAMERA)
            return
        }
        val c2 = Camera2CameraInfo.from(infos[0])
        val cameraId = c2.cameraId
        val ranges: Array<Range<Int>> = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val tsSource = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val oisModes = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val eisModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)

        val has24 = ranges.any { it.lower == 24 && it.upper == 24 }
        val has30 = ranges.any { it.lower == 30 && it.upper == 30 }
        val selected: Range<Int>? = when {
            has24 -> Range(24, 24)
            has30 -> Range(30, 30)
            else -> null // 둘 다 없으면 지정하지 않고 HAL 기본값. 지시문 밖의 경우라 header 에 남긴다.
        }

        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        val ext = Camera2Interop.Extender(builder)
        if (selected != null) ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected)
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        val oisOff = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
        if (oisOff) {
            ext.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
        ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val cb = SystemClock.elapsedRealtimeNanos()
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                val fps = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { "[${it.lower},${it.upper}]" }
                    ?: request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.let { "req[${it.lower},${it.upper}]" }
                val dur = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                captureHandler?.post { rec.onCaptureResult(ts, cb, fps, dur) }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                val reason = failure.reason
                captureHandler?.post { rec.onCaptureFailure(reason) }
            }
        })

        val analysis = builder.build()
        val ch = captureHandler ?: return
        val executor = Executor { r -> ch.post(r) }
        analysis.setAnalyzer(executor) { image -> analyze(image, rec) }
        this.analysis = analysis

        provider.unbindAll()
        val camera = provider.bindToLifecycle(this, selector, analysis)
        camera.cameraInfo.cameraState.observe(this) { st ->
            val err = st.error
            rec.event("camera_state ${st.type}${if (err != null) " error=${err.code} ${err.cause?.message ?: ""}" else ""}")
        }

        val res = analysis.resolutionInfo?.resolution
        val header = SessionHeader(
            sessionId = files!!.sessionId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.DISPLAY}",
            appVersion = appVersion(),
            cameraId = cameraId,
            resolution = res?.let { "${it.width}x${it.height}" } ?: "unknown",
            fpsSelected = selected?.let { "[${it.lower},${it.upper}]" } ?: "unset(no [24,24] or [30,30])",
            fpsAvailable = ranges.joinToString(prefix = "[", postfix = "]") { "[${it.lower},${it.upper}]" },
            timestampSource = when (tsSource) {
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME(1)"
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN(0)"
                null -> "null"
                else -> "other($tsSource)"
            },
            batteryOptimizationIgnored = device.isIgnoringBatteryOptimizations(),
            stabilization = "video=OFF ois=${if (oisOff) "OFF" else "n/a"} eis_modes=${eisModes?.joinToString() ?: "?"} ois_modes=${oisModes?.joinToString() ?: "?"}",
        )
        ch.post { rec.start(header) }
        rec.event("camera_bound id=$cameraId resolution=${header.resolution} fps_selected=${header.fpsSelected} fps_available=${header.fpsAvailable} ts_source=${header.timestampSource}")
        updateNotification("카메라 ${header.resolution} fps ${header.fpsSelected}")
        SpikeStatus.line = "카메라 바인딩됨 ${header.resolution} fps ${header.fpsSelected}"
        scheduleNotificationUpdate()
    }

    /** capture 스레드. 프레임마다 timestamp 두 개만 읽고 1Hz 로 휘도 평균 하나를 낸 뒤 즉시 close. */
    private fun analyze(image: ImageProxy, rec: SessionRecorder) {
        val cbNs = SystemClock.elapsedRealtimeNanos()
        val tsNs = image.imageInfo.timestamp
        var y: Double? = null
        try {
            if (!firstFrameLogged) {
                firstFrameLogged = true
                rec.event("first_frame ${image.width}x${image.height} format=${image.format} planes=${image.planes.size} rowStride=${image.planes[0].rowStride} pixelStride=${image.planes[0].pixelStride}")
            }
            if (lastLumaTsNs == Long.MIN_VALUE || tsNs - lastLumaTsNs >= 1_000_000_000L) {
                lastLumaTsNs = tsNs
                val plane = image.planes[0]
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                val limit = buf.limit()
                y = LumaGrid.mean(image.width, image.height) { x, yy ->
                    val i = yy * rs + x * ps
                    if (i < limit) buf.get(i).toInt() else 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
        } finally {
            image.close()
        }
        rec.onFrame(tsNs, cbNs, y)
    }

    // ---- 정지 ----

    private fun stopSession(reason: String) {
        if (!running || stopping) {
            if (!running) stopSelf()
            return
        }
        stopping = true
        try {
            analysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        val rec = recorder
        val ch = captureHandler
        val wh = writerHandler
        if (rec == null || ch == null || wh == null) {
            finishService("")
            return
        }
        ch.post {
            val summary = rec.finish(reason)
            wh.post {
                rec.closeFiles()
                mainHandler.post { finishService(summary) }
            }
        }
    }

    private fun finishService(summary: String) {
        running = false
        SpikeStatus.running = false
        SpikeStatus.line = "정지됨"
        prefs.lastSummary = summary
        prefs.sessionActive = false
        prefs.activeSessionId = null
        mainHandler.removeCallbacks(notificationUpdate)
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakelock release failed", e)
        }
        wakeLock = null
        captureThread?.quitSafely()
        writerThread?.quitSafely()
        captureThread = null
        writerThread = null
        captureHandler = null
        writerHandler = null
        recorder = null
        analysis = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running && !stopping) {
            // 시스템이 서비스를 내리는 경우. 비동기 정리를 기다릴 시간이 없으니 동기로 최대한 남긴다.
            stopping = true
            try {
                analysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed in onDestroy", e)
            }
            val rec = recorder
            val ch = captureHandler
            val wh = writerHandler
            if (rec != null && ch != null && wh != null) {
                val latch = CountDownLatch(1)
                var summary = ""
                ch.post {
                    summary = rec.finish(REASON_SERVICE_DESTROYED)
                    wh.post {
                        rec.closeFiles()
                        latch.countDown()
                    }
                }
                latch.await(4, TimeUnit.SECONDS)
                prefs.lastSummary = summary
            }
            prefs.sessionActive = false
            prefs.activeSessionId = null
            running = false
            SpikeStatus.running = false
            SpikeStatus.line = "서비스가 시스템에 의해 종료됨"
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (e: Exception) {
                Log.w(TAG, "wakelock release failed", e)
            }
            captureThread?.quitSafely()
            writerThread?.quitSafely()
        }
        super.onDestroy()
    }

    // ---- 알림 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
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

    private val notificationUpdate = object : Runnable {
        override fun run() {
            if (!running || stopping) return
            updateNotification(SpikeStatus.line)
            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_MS)
        }
    }

    private fun scheduleNotificationUpdate() {
        mainHandler.removeCallbacks(notificationUpdate)
        mainHandler.postDelayed(notificationUpdate, NOTIFICATION_UPDATE_MS)
    }

    private fun appVersion(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    companion object {
        private const val TAG = "SpikeCapture"
        const val ACTION_START = "kr.co.byite.focus.spike.action.START"
        const val ACTION_STOP = "kr.co.byite.focus.spike.action.STOP"
        const val REASON_USER_STOP = "user_stop"
        const val REASON_SERVICE_DESTROYED = "service_destroyed"
        const val REASON_CAMERA_BIND_FAILED = "camera_bind_failed"
        const val REASON_NO_FRONT_CAMERA = "no_front_camera"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_UPDATE_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        fun startIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
