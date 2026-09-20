package co.byite.focus.devapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import co.byite.focus.engine.CaptureService
import co.byite.focus.engine.DeviceStatusReader
import co.byite.focus.engine.EnginePrefs
import co.byite.focus.engine.EngineStatus
import co.byite.focus.engine.SessionRecovery

/** Dev app: start / stop, segment markers, live status, summary, copy. The engine is `:engine`. */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: EnginePrefs
    private lateinit var device: DeviceStatusReader
    private lateinit var permissionState: TextView
    private lateinit var status: TextView
    private lateinit var logPath: TextView
    private lateinit var summary: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private val markerButtons = ArrayList<Button>()
    private val ui = Handler(Looper.getMainLooper())
    private var recovering = false

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))
        prefs = EnginePrefs(this)
        device = DeviceStatusReader(this)
        permissionState = findViewById(R.id.permission_state)
        status = findViewById(R.id.status)
        logPath = findViewById(R.id.log_path)
        summary = findViewById(R.id.summary)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            val wanted = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions.launch(wanted.toTypedArray())
        }
        btnStart.setOnClickListener { startCapture() }
        btnStop.setOnClickListener { startService(CaptureService.stopIntent(this)) }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copySummary() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, e.javaClass.simpleName, Toast.LENGTH_SHORT).show()
            }
        }
        val markers = listOf(
            R.id.btn_marker_front to R.string.marker_front,
            R.id.btn_marker_still to R.string.marker_still,
            R.id.btn_marker_bow to R.string.marker_bow,
            R.id.btn_marker_prone to R.string.marker_prone,
            R.id.btn_marker_away to R.string.marker_away,
            R.id.btn_marker_empty_chair to R.string.marker_empty_chair,
        )
        for ((id, label) in markers) {
            val b = findViewById<Button>(id)
            b.setOnClickListener { setMarker(getString(label)) }
            markerButtons += b
        }
        findViewById<Button>(R.id.btn_marker_clear).also { markerButtons += it }.setOnClickListener { setMarker(null) }
    }

    /** systemBars + displayCutout insets as root padding, absolute each time (no accumulation). */
    private fun applySystemBarInsets(root: View) {
        val base = Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(base.left + bars.left, base.top + bars.top, base.right + bars.right, base.bottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        maybeRecover()
        refresh()
        ui.post(refreshTick)
    }

    override fun onPause() {
        ui.removeCallbacks(refreshTick)
        super.onPause()
    }

    /** Last session ended without a normal stop (process death): rebuild the summary from its JSONL. */
    private fun maybeRecover() {
        if (recovering || EngineStatus.running || !prefs.sessionActive) return
        recovering = true
        val path = prefs.lastLogPath
        val sid = prefs.activeSessionId
        Thread {
            val text = try {
                SessionRecovery.recover(path, sid, prefs.lastSelfCheck)
            } catch (e: Exception) {
                "${SessionRecovery.PREFIX}\n복원 실패: ${e.javaClass.simpleName}: ${e.message}"
            }
            ui.post {
                prefs.lastSummary = text
                prefs.lastRecoveredSessionId = sid
                if (prefs.activeSessionId == sid) {
                    prefs.sessionActive = false
                    prefs.activeSessionId = null
                }
                recovering = false
                refresh()
            }
        }.start()
    }

    private fun hasCamera(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** camera-type FGS may only start while the app is visible; a button tap means RESUMED, checked once more. */
    private fun startCapture() {
        if (recovering) return
        if (!hasCamera()) {
            Toast.makeText(this, R.string.toast_need_camera, Toast.LENGTH_SHORT).show()
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Toast.makeText(this, R.string.toast_not_visible, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.lastRecoveredSessionId = null
        ContextCompat.startForegroundService(this, CaptureService.startIntent(this))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun setMarker(label: String?) {
        if (!EngineStatus.running) {
            Toast.makeText(this, R.string.toast_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        startService(CaptureService.markerIntent(this, label))
    }

    private fun copySummary() {
        val text = summary.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.toast_no_summary, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("focus-engine summary", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val cam = hasCamera()
        permissionState.text = "CAMERA ${if (cam) "허용" else "거부"} · 알림 ${if (hasNotifications()) "허용" else "거부"} · " +
            "배터리 최적화 예외 ${if (device.isIgnoringBatteryOptimizations()) "예" else "아니오"}"
        val running = EngineStatus.running
        btnStart.isEnabled = cam && !running && !recovering
        btnStop.isEnabled = running
        for (b in markerButtons) b.isEnabled = running
        val recovered = prefs.lastRecoveredSessionId
        status.text = when {
            running -> "실행 중 (세션 ${EngineStatus.sessionId}, 마커 ${EngineStatus.segmentLabel ?: "-"})\n${EngineStatus.selfCheck ?: "자가 점검 중"}\n${EngineStatus.line}"
            recovering -> "이전 세션 ${prefs.activeSessionId} 이 정상 정지되지 않았다. session.jsonl 로 요약을 복원하는 중"
            recovered != null -> "이전 세션 $recovered 은 서비스가 죽어 정상 정지 요약이 없다. 아래 요약은 session.jsonl 로 다시 계산한 것이다 " +
                "(session_end PROCESS_DEATH_RECOVERED).\n마지막 flush(30초) 안의 레코드는 잃었을 수 있다."
            else -> EngineStatus.line.ifBlank { getString(R.string.status_idle) }
        }
        logPath.text = if (running) EngineStatus.logPath else prefs.lastLogPath
        if (!running && !recovering) summary.text = prefs.lastSummary ?: ""
    }
}
