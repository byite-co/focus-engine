package kr.co.byite.focus.spike

import android.os.SystemClock
import android.util.Log
import kr.co.byite.focus.spike.core.RecoveredSummary
import kr.co.byite.focus.spike.core.SessionCsv
import java.io.File

/**
 * 서비스가 정상 정지 요약 없이 죽은 세션의 frames.csv / timebase.csv 로 요약을 다시 계산한다.
 * 결과는 화면에 보이고 summary.txt(없을 때만)와 events.log 에 남긴다. 읽는 것은 스칼라 CSV 뿐이다.
 */
object SessionRecovery {
    private const val TAG = "SpikeRecovery"
    const val PREFIX = "[CSV 재계산] 정상 종료 요약이 없어 frames.csv 로 다시 계산했다."

    fun recover(csvPath: String?, sessionId: String?): String {
        val csv = csvPath?.let { File(it) }
        if (csv == null || !csv.isFile) {
            return "$PREFIX\n세션 ${sessionId ?: "?"}: frames.csv 가 없다 (헤더를 쓰기 전에 죽었거나 파일이 지워졌다).\n종료 사유: ${RecoveredSummary.REASON_KILLED}"
        }
        val dir = csv.parentFile
        val frames = csv.useLines { SessionCsv.parseFrames(it) }
        val tbFile = File(dir, "timebase.csv")
        val timebase = if (tbFile.isFile) tbFile.useLines { SessionCsv.parseTimebase(it) } else emptyList()
        val last = frames.rows.lastOrNull()
        val summary = RecoveredSummary.build(
            frames = frames,
            timebase = timebase,
            reason = RecoveredSummary.REASON_KILLED,
            lastRecordLocal = last?.let { SessionRecorder.localTime(it.tUtcMs) },
        )
        val text = "$PREFIX\n${summary.render()}"
        val out = File(dir, "summary.txt")
        if (!out.exists()) {
            try {
                out.writeText(text + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "summary write failed", e)
            }
        }
        try {
            File(dir, "events.log").appendText(
                "${SystemClock.elapsedRealtime()} ${SessionRecorder.localTime(System.currentTimeMillis())} " +
                    "recovered_summary reason=${RecoveredSummary.REASON_KILLED} rows=${frames.rows.size} bad_lines=${frames.badLines}\n",
            )
        } catch (e: Exception) {
            Log.w(TAG, "events append failed", e)
        }
        return text
    }
}
