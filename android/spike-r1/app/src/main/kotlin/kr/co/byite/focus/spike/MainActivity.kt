package kr.co.byite.focus.spike

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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SpikePrefs
    private lateinit var device: DeviceStatusReader
    private lateinit var permissionState: TextView
    private lateinit var status: TextView
    private lateinit var csvPath: TextView
    private lateinit var summary: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private val ui = Handler(Looper.getMainLooper())

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SpikePrefs(this)
        device = DeviceStatusReader(this)
        permissionState = findViewById(R.id.permission_state)
        status = findViewById(R.id.status)
        csvPath = findViewById(R.id.csv_path)
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
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.post(refreshTick)
    }

    override fun onPause() {
        ui.removeCallbacks(refreshTick)
        super.onPause()
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** camera 타입 FGS 는 앱이 보이는 상태에서만 시작할 수 있다. 버튼 탭 = RESUMED 이지만 한 번 더 확인한다. */
    private fun startCapture() {
        if (!hasCamera()) {
            Toast.makeText(this, R.string.toast_need_camera, Toast.LENGTH_SHORT).show()
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Toast.makeText(this, R.string.toast_not_visible, Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this, CaptureService.startIntent(this))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun copySummary() {
        val text = summary.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.toast_no_summary, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("spike-r1 summary", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val cam = hasCamera()
        permissionState.text = "CAMERA ${if (cam) "허용" else "거부"} · 알림 ${if (hasNotifications()) "허용" else "거부"} · " +
            "배터리 최적화 예외 ${if (device.isIgnoringBatteryOptimizations()) "예" else "아니오"}"
        val running = SpikeStatus.running
        btnStart.isEnabled = cam && !running
        btnStop.isEnabled = running

        val abnormal = !running && prefs.sessionActive
        status.text = when {
            running -> "실행 중 (세션 ${SpikeStatus.sessionId})\n${SpikeStatus.line}"
            abnormal -> "이전 세션 ${prefs.activeSessionId} 이 정상 정지되지 않았다. 서비스가 죽었을 수 있다.\n" +
                "CSV 마지막 행 시각이 종료 시각의 근사값이다 (flush 30초 단위)."
            else -> SpikeStatus.line.ifBlank { getString(R.string.status_idle) }
        }
        csvPath.text = if (running) SpikeStatus.csvPath else prefs.lastCsvPath
        if (!running) summary.text = prefs.lastSummary ?: ""
    }
}
