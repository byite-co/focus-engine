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
    private var recovering = false

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
        // targetSdk 35+ 에서는 edge-to-edge 가 강제된다. 그보다 낮은 API 에서도 같은 방식으로 창을 그리게 해
        // 시스템 바 인셋을 아래 리스너 한 곳에서만 처리한다(API 29~34 에서 DecorView 패딩과 겹치지 않게).
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))
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

    /**
     * 상태바·내비게이션 바·디스플레이 컷아웃 인셋을 루트 뷰의 패딩으로 넣어 콘텐츠가 그 아래로
     * 들어가지 않게 한다. 인셋은 회전·바 표시 상태 변화마다 다시 오므로 원래 패딩에 매번 더해
     * 절대값으로 설정한다(누적 방지).
     */
    private fun applySystemBarInsets(root: View) {
        val base = Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(
                base.left + bars.left,
                base.top + bars.top,
                base.right + bars.right,
                base.bottom + bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        maybeRecover()
        refresh()
        ui.post(refreshTick)
    }

    /**
     * 마지막 세션이 정상 정지 요약 없이 끝났으면(프로세스 사망 등) CSV 로 요약을 다시 계산해 보여 준다.
     * 서비스가 살아 있으면(같은 프로세스에서 Activity 만 재생성) 아무것도 하지 않는다.
     */
    private fun maybeRecover() {
        if (recovering || SpikeStatus.running || !prefs.sessionActive) return
        recovering = true
        val csvPath = prefs.lastCsvPath
        val sid = prefs.activeSessionId
        Thread {
            val text = try {
                SessionRecovery.recover(csvPath, sid)
            } catch (e: Exception) {
                "${SessionRecovery.PREFIX}\n복원 실패: ${e.javaClass.simpleName}: ${e.message}\n종료 사유: killed"
            }
            ui.post {
                prefs.lastSummary = text
                prefs.lastRecoveredSessionId = sid
                // 복원 중에 새 세션이 시작됐으면 새 세션의 플래그를 건드리지 않는다.
                if (prefs.activeSessionId == sid) {
                    prefs.sessionActive = false
                    prefs.activeSessionId = null
                }
                recovering = false
                refresh()
            }
        }.start()
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
        btnStart.isEnabled = cam && !running && !recovering
        btnStop.isEnabled = running

        val recovered = prefs.lastRecoveredSessionId
        status.text = when {
            running -> "실행 중 (세션 ${SpikeStatus.sessionId})\n${SpikeStatus.line}"
            recovering -> "이전 세션 ${prefs.activeSessionId} 이 정상 정지되지 않았다. CSV 로 요약을 복원하는 중"
            recovered != null -> "이전 세션 $recovered 은 서비스가 죽어 정상 정지 요약이 없다. 아래 요약은 CSV 로 다시 계산한 것이다 (종료 사유 killed).\n" +
                "마지막 기록 시각까지만 반영되고, flush 주기(30초) 안의 행은 잃었을 수 있다."
            else -> SpikeStatus.line.ifBlank { getString(R.string.status_idle) }
        }
        csvPath.text = if (running) SpikeStatus.csvPath else prefs.lastCsvPath
        if (!running && !recovering) summary.text = prefs.lastSummary ?: ""
    }
}
