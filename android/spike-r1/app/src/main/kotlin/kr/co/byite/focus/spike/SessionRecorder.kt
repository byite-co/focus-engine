package kr.co.byite.focus.spike

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kr.co.byite.focus.spike.core.FrameStats
import kr.co.byite.focus.spike.core.FrameSummary
import kr.co.byite.focus.spike.core.TimebaseEstimator
import kr.co.byite.focus.spike.core.TimebaseSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 세션 header. 전부 스칼라·문자열이다. */
data class SessionHeader(
    val sessionId: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val cameraId: String,
    val resolution: String,
    val fpsSelected: String,
    val fpsAvailable: String,
    val timestampSource: String,
    val batteryOptimizationIgnored: Boolean,
    val stabilization: String,
)

/**
 * 세션 기록. 상태는 전부 captureHandler 스레드에서만 만진다.
 * 파일 I/O 는 writerHandler 스레드로 넘겨서 프레임 콜백을 막지 않는다.
 */
class SessionRecorder(
    private val files: SessionFiles,
    private val device: DeviceStatusReader,
    private val captureHandler: Handler,
    private val writerHandler: Handler,
) {
    private val stats = FrameStats()
    private val resultStats = FrameStats() // Camera2 capture result 스트림 (분석기 backpressure 와 무관)
    private val timebase = TimebaseEstimator()

    private lateinit var header: SessionHeader
    private var startMonoMs = 0L
    private var startUtcMs = 0L
    private var stopped = false
    private var stopReason: String? = null

    private var started = false
    private var framesBeforeStart = 0L
    private var lastYMean: Double? = null
    private var lastFrameCbNs = 0L
    private var lastFlushMonoMs = 0L
    private var rows = 0L
    private var rowsMissed = 0L
    private var lastRowMonoMs = 0L
    private var rowsScreenOff = 0L
    private var rowsIdle = 0L
    private var maxThermal = 0
    private var startBattery: Int? = null
    private var lastBattery: Int? = null
    private var captureFailures = 0L
    private var fpsEffective: String? = null
    private var frameDurationNs: Long? = null
    private var frameDurationChanges = 0L
    private var fpsRangeChanges = 0L

    private val framesOut = LazyWriter(files.framesCsv)
    private val timebaseOut = LazyWriter(files.timebaseCsv)
    private val eventsOut = LazyWriter(files.eventsLog)

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            emitRow()
            scheduleTick()
        }
    }

    // ---- 시작 ----

    fun start(header: SessionHeader) {
        this.header = header
        startMonoMs = SystemClock.elapsedRealtime()
        startUtcMs = System.currentTimeMillis()
        lastRowMonoMs = startMonoMs
        lastFlushMonoMs = startMonoMs
        started = true
        val ds = device.read()
        startBattery = ds.batteryPct
        lastBattery = ds.batteryPct
        maxThermal = ds.thermalStatus
        val h = buildHeaderLines(header)
        writerHandler.post {
            framesOut.writeLine(h)
            framesOut.writeLine(CSV_COLUMNS)
            framesOut.flush()
            timebaseOut.writeLine("# spike-r1 timebase session_id=${header.sessionId} timestamp_source=${header.timestampSource}")
            timebaseOut.writeLine("t_mono_ms,offset_ns,drift_ns,frames,complete")
            timebaseOut.flush()
        }
        event("session_start t_utc_ms=$startUtcMs battery_pct=${ds.batteryPct} thermal=${ds.thermalStatus} interactive=${ds.isInteractive}")
        if (framesBeforeStart > 0) event("frames_before_start=$framesBeforeStart (통계에서 제외)")
        scheduleTick()
    }

    private fun buildHeaderLines(h: SessionHeader): String = buildString {
        appendLine("# spike-r1 session header")
        appendLine("# session_id=${h.sessionId}")
        appendLine("# device_model=${h.deviceModel}")
        appendLine("# os_version=${h.osVersion}")
        appendLine("# app_version=${h.appVersion}")
        appendLine("# camera_id=${h.cameraId}")
        appendLine("# resolution=${h.resolution}")
        appendLine("# fps_selected=${h.fpsSelected}")
        appendLine("# fps_available=${h.fpsAvailable}")
        appendLine("# timestamp_source=${h.timestampSource}")
        appendLine("# battery_optimization_ignored=${h.batteryOptimizationIgnored}")
        appendLine("# stabilization=${h.stabilization}")
        appendLine("# start_t_mono_ms=$startMonoMs")
        appendLine("# start_t_utc_ms=$startUtcMs")
        append("# start_local=${localTime(startUtcMs)}")
    }

    // ---- 프레임 (captureHandler 스레드) ----

    /** 분석기에서 프레임마다. yMean 은 1Hz 로만 온다. */
    fun onFrame(captureTsNs: Long, callbackNs: Long, yMean: Double?) {
        if (stopped) return
        if (!started) {
            framesBeforeStart++
            return
        }
        val gapNs = stats.onFrame(captureTsNs, callbackNs)
        lastFrameCbNs = callbackNs
        if (yMean != null) lastYMean = yMean
        timebase.onFrame(captureTsNs, callbackNs)?.let { writeTimebase(it) }
        if (gapNs > FrameStats.DEFAULT_LONG_GAP_THRESHOLD_NS) {
            event("long_gap gap_ms=${fmt1(gapNs / 1e6)} total_frames=${stats.summary().totalFrames}")
        }
    }

    /** Camera2 capture result 마다 (HAL 이 낸 프레임 수, 분석기 drop 과 무관). */
    fun onCaptureResult(sensorTsNs: Long?, callbackNs: Long, fpsRange: String?, frameDurationNs: Long?) {
        if (stopped || !started) return
        if (sensorTsNs != null) resultStats.onFrame(sensorTsNs, callbackNs)
        if (fpsRange != null && fpsRange != fpsEffective) {
            if (fpsEffective != null) fpsRangeChanges++
            if (fpsRangeChanges < 50) event("ae_target_fps_range=$fpsRange (was ${fpsEffective ?: "none"})")
            fpsEffective = fpsRange
        }
        if (frameDurationNs != null && frameDurationNs != this.frameDurationNs) {
            if (this.frameDurationNs != null) frameDurationChanges++
            // 처음 값과 변화만 남긴다. 매 프레임 로그하지 않는다.
            if (frameDurationChanges < 50) event("sensor_frame_duration_ns=$frameDurationNs (was ${this.frameDurationNs ?: "none"})")
            this.frameDurationNs = frameDurationNs
        }
    }

    fun onCaptureFailure(reason: Int) {
        if (stopped || !started) return
        captureFailures++
        if (captureFailures <= 20) event("capture_failed reason=$reason count=$captureFailures")
    }

    // ---- 초당 행 ----

    private fun scheduleTick() {
        val now = SystemClock.elapsedRealtime()
        val next = startMonoMs + ((now - startMonoMs) / 1000 + 1) * 1000
        captureHandler.postDelayed(tick, (next - now).coerceAtLeast(1))
    }

    private fun emitRow() {
        val tMono = SystemClock.elapsedRealtime()
        val tUtc = System.currentTimeMillis()
        val iv = stats.takeInterval()
        val y = lastYMean
        lastYMean = null
        val ds = device.read()
        if (ds.thermalStatus > maxThermal) maxThermal = ds.thermalStatus
        if (ds.batteryPct != null) lastBattery = ds.batteryPct
        if (!ds.isInteractive) rowsScreenOff++
        if (ds.isDeviceIdle) rowsIdle++
        val sinceLast = tMono - lastRowMonoMs
        if (rows > 0 && sinceLast > 1500) {
            val missed = sinceLast / 1000 - 1
            rowsMissed += missed
            event("row_gap since_last_row_ms=$sinceLast missed_rows=$missed")
        }
        lastRowMonoMs = tMono
        rows++

        val line = buildString(160) {
            append(tMono).append(',')
            append(tUtc).append(',')
            append(iv.frames).append(',')
            append(fmt1(iv.maxGapMs)).append(',')
            append(iv.gapsOverThreshold).append(',')
            append(iv.callbackLatencyMeanMs?.let { fmt1(it) } ?: "").append(',')
            append(y?.let { fmt1(it) } ?: "").append(',')
            append(ds.thermalStatus).append(',')
            append(ds.batteryPct ?: "").append(',')
            append(ds.batteryCurrentUa ?: "").append(',')
            append(if (ds.isInteractive) 1 else 0).append(',')
            append(if (ds.isDeviceIdle) 1 else 0)
        }
        val flush = tMono - lastFlushMonoMs >= FLUSH_INTERVAL_MS
        if (flush) lastFlushMonoMs = tMono
        writerHandler.post {
            framesOut.writeLine(line)
            if (flush) framesOut.flush()
        }

        val s = stats.summary()
        val lastFrameAgoMs = if (lastFrameCbNs > 0) (SystemClock.elapsedRealtimeNanos() - lastFrameCbNs) / 1_000_000 else -1
        SpikeStatus.line = "행 $rows · 프레임 ${s.totalFrames} (${fmt1(s.avgFps)} fps) · 이번 초 ${iv.frames}f 최대갭 ${fmt1(iv.maxGapMs)}ms\n" +
            ">80ms 갭 ${s.gapsOverThreshold} (${fmtPct(s.gapsOverThresholdRatio)}) · >1s 갭 ${s.gapsOverLong} · 누락 초 ${s.missingSeconds}\n" +
            "마지막 프레임 ${lastFrameAgoMs}ms 전 · 화면 ${if (ds.isInteractive) "on" else "off"} · idle ${ds.isDeviceIdle} · thermal ${ds.thermalStatus} · 배터리 ${ds.batteryPct}%"
    }

    private fun writeTimebase(s: TimebaseSample) {
        val tMono = SystemClock.elapsedRealtime()
        val line = "$tMono,${s.offsetNs},${s.driftNs},${s.frames},${if (s.complete) 1 else 0}"
        writerHandler.post {
            timebaseOut.writeLine(line)
            timebaseOut.flush()
        }
    }

    // ---- 이벤트 ----

    /** 어느 스레드에서든 호출 가능. 스칼라·문자열만 넘긴다. */
    fun event(msg: String) {
        val tMono = SystemClock.elapsedRealtime()
        val line = "$tMono ${localTime(System.currentTimeMillis())} $msg"
        Log.i(TAG, msg)
        writerHandler.post {
            eventsOut.writeLine(line)
            eventsOut.flush()
        }
    }

    // ---- 종료 ----

    /**
     * captureHandler 스레드에서 호출. 통계를 닫고 요약을 만들어 파일에 쓰고 돌려준다.
     * 파일 쓰기는 writer 스레드로 넘기고, 그 완료를 최대 [waitMs] 기다린다.
     */
    fun finish(reason: String, waitMs: Long = 3000): String {
        if (stopped) return stopReason?.let { "already stopped: $it" } ?: ""
        stopped = true
        stopReason = reason
        captureHandler.removeCallbacks(tick)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        timebase.finish(nowNs)?.let { writeTimebase(it) }
        val endDs = device.read()
        if (endDs.batteryPct != null) lastBattery = endDs.batteryPct
        if (endDs.thermalStatus > maxThermal) maxThermal = endDs.thermalStatus
        val summary = buildSummary(reason)
        event("session_stop reason=$reason")
        val done = CountDownLatch(1)
        writerHandler.post {
            try {
                framesOut.flush()
                timebaseOut.flush()
                eventsOut.flush()
                LazyWriter(files.summaryTxt).use { it.writeLine(summary) }
            } catch (e: Exception) {
                Log.w(TAG, "summary write failed", e)
            } finally {
                done.countDown()
            }
        }
        done.await(waitMs, TimeUnit.MILLISECONDS)
        return summary
    }

    /** writer 스레드에서 호출. 파일을 닫는다. */
    fun closeFiles() {
        framesOut.close()
        timebaseOut.close()
        eventsOut.close()
    }

    private fun buildSummary(reason: String): String {
        val s: FrameSummary = stats.summary()
        val r = resultStats.summary()
        val tb = timebase.summary()
        val endMono = SystemClock.elapsedRealtime()
        val lengthS = (endMono - startMonoMs) / 1000.0
        val passGap = s.gapsOverThresholdRatio < 0.01
        val passLong = s.gapsOverLong == 0L
        val passMissing = s.missingSeconds == 0L
        val pass = passGap && passLong && passMissing
        fun mark(b: Boolean) = if (b) "OK" else "FAIL"
        return buildString {
            appendLine("spike-r1 요약  세션 ${header.sessionId}")
            appendLine("기기: ${header.deviceModel}, ${header.osVersion}, app ${header.appVersion}")
            appendLine("카메라: id=${header.cameraId} ${header.resolution}, fps 요청 ${header.fpsSelected} / 결과 ${fpsEffective ?: "미확인"} (변경 ${fpsRangeChanges}회), frame_duration ${frameDurationNs?.let { fmt1(it / 1e6) + "ms" } ?: "미확인"} (변경 ${frameDurationChanges}회)")
            appendLine("timestamp source: ${header.timestampSource}, 배터리 최적화 예외: ${header.batteryOptimizationIgnored}")
            appendLine("종료 사유: $reason, 세션 길이 ${fmt1(lengthS)}s, 시작 ${localTime(startUtcMs)}")
            appendLine("총 프레임: ${s.totalFrames}, 평균 fps ${"%.2f".format(Locale.US, s.avgFps)}")
            appendLine("80ms 초과 갭: ${s.gapsOverThreshold} / ${s.gapCount} (${fmtPct(s.gapsOverThresholdRatio)})")
            appendLine("1초 초과 갭: ${s.gapsOverLong}")
            appendLine("최대 갭: ${fmt1(s.maxGapMs)} ms")
            appendLine("누락된 초 (프레임 0인 1초 버킷): ${s.missingSeconds}")
            appendLine("초당 행: $rows, 행 누락(틱 지연): $rowsMissed, 화면 off 행 $rowsScreenOff, idle 행 $rowsIdle")
            appendLine("배터리: ${startBattery ?: "?"}% → ${lastBattery ?: "?"}%")
            appendLine("최고 thermal status: $maxThermal (${DeviceStatusReader.thermalName(maxThermal)})")
            appendLine(
                "offset: ${tb.offsetNs?.let { fmt3(it / 1e6) + " ms" } ?: "미확정"}, drift: 마지막 ${tb.lastDriftNs?.let { fmt3(it / 1e6) + " ms" } ?: "없음"}, " +
                    "최대 |drift| ${tb.maxAbsDriftNs?.let { fmt3(it / 1e6) + " ms" } ?: "없음"} (재측정 ${tb.remeasureCount}회)",
            )
            appendLine("capture result 스트림: ${r.totalFrames}개, 80ms 초과 갭 ${r.gapsOverThreshold}, 최대 갭 ${fmt1(r.maxGapMs)} ms, 실패 $captureFailures, 비단조 ${s.nonMonotonic}")
            append("판정: ${if (pass) "합격" else "불합격"}  [80ms 초과 갭 <1%: ${mark(passGap)}] [1초 초과 갭 0: ${mark(passLong)}] [누락된 초 0: ${mark(passMissing)}] [서비스 생존: 사람이 확인]")
        }
    }

    companion object {
        private const val TAG = "SpikeRecorder"
        const val FLUSH_INTERVAL_MS = 30_000L
        const val CSV_COLUMNS =
            "t_mono_ms,t_utc_ms,frames,max_gap_ms,gaps_over_80ms,cb_latency_ms_mean,y_mean,thermal_status,battery_pct,battery_current_ua,is_interactive,is_device_idle"

        fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
        fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
        fun fmtPct(ratio: Double): String = String.format(Locale.US, "%.3f%%", ratio * 100)
        fun localTime(utcMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(utcMs))
    }
}

/** 첫 쓰기 때 파일을 연다. writer 스레드에서만 쓴다. */
class LazyWriter(private val file: File) : AutoCloseable {
    private var w: BufferedWriter? = null

    private fun writer(): BufferedWriter {
        w?.let { return it }
        file.parentFile?.mkdirs()
        return BufferedWriter(FileWriter(file, true)).also { w = it }
    }

    fun writeLine(s: String) {
        try {
            writer().write(s)
            writer().newLine()
        } catch (e: Exception) {
            Log.w("SpikeRecorder", "write failed: ${file.name}", e)
        }
    }

    fun flush() {
        try {
            w?.flush()
        } catch (e: Exception) {
            Log.w("SpikeRecorder", "flush failed: ${file.name}", e)
        }
    }

    override fun close() {
        try {
            w?.close()
        } catch (e: Exception) {
            Log.w("SpikeRecorder", "close failed: ${file.name}", e)
        } finally {
            w = null
        }
    }
}
