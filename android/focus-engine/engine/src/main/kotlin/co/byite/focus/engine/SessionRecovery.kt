package co.byite.focus.engine

import android.os.SystemClock
import android.util.Log
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.LogFormatException
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.report.V0bReport
import java.io.File

/**
 * Process-death recovery (spec 9장 "Android에서도 프로세스가 죽을 수 있다", v0.2.1 판정 10, R6):
 * reads the session JSONL, closes the session at the last record with `session_end(PROCESS_DEATH_RECOVERED)`
 * and rebuilds the same summary the live path would have produced. A torn last line (killed mid-write)
 * is dropped.
 */
object SessionRecovery {
    private const val TAG = "FocusRecovery"
    const val PREFIX = "[JSONL 재계산] 정상 종료 요약이 없어 session.jsonl 로 다시 계산했다."

    fun recover(logPath: String?, sessionId: String?): String {
        val file = logPath?.let { File(it) }
        if (file == null || !file.isFile) {
            return "$PREFIX\n세션 ${sessionId ?: "?"}: session.jsonl 이 없다 (헤더를 쓰기 전에 죽었거나 파일이 지워졌다)."
        }
        val text = file.readText()
        val (log, droppedTornLine) = readTolerant(text)
        val closed = if (log.sessionEnd == null) {
            val last = log.records.lastOrNull()
            val end = SessionEnd(
                tMonoMs = last?.tMonoMs ?: log.header.tStartMonoMs,
                tUtcMs = last?.tUtcMs ?: log.header.tStartUtcMs,
                reason = SessionEndReason.PROCESS_DEATH_RECOVERED,
            )
            try {
                file.appendText(JsonlCodec.encodeSessionEnd(end) + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "session_end append failed", e)
            }
            log.copy(sessionEnd = end)
        } else {
            log
        }
        val notes = ArrayList<String>()
        notes.add("프로세스 종료 뒤 복원: 마지막 flush(최대 30초) 이후의 레코드는 잃었을 수 있다.")
        if (droppedTornLine) notes.add("잘린 마지막 줄 하나를 버렸다.")
        val summary = "$PREFIX\n${V0bReport.build(closed, notes).render()}"
        val out = File(file.parentFile, "summary.txt")
        if (!out.exists()) {
            try {
                out.writeText(summary + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "summary write failed", e)
            }
        }
        try {
            File(file.parentFile, "events.log").appendText(
                "${SystemClock.elapsedRealtime()} ${SessionFiles.localTime(System.currentTimeMillis())} recovered_summary records=${closed.records.size} torn_line=$droppedTornLine\n",
            )
        } catch (e: Exception) {
            Log.w(TAG, "events append failed", e)
        }
        return summary
    }

    /** Decode, dropping one torn trailing line if that is what makes the file unreadable. Returns (log, droppedLine). */
    fun readTolerant(text: String): Pair<SessionLog, Boolean> {
        return try {
            JsonlCodec.decode(text) to false
        } catch (e: LogFormatException) {
            val lines = text.lines().filter { it.isNotBlank() }
            val lastNo = lines.size
            if (lastNo > 1 && e.message?.startsWith("line $lastNo:") == true) {
                JsonlCodec.decode(lines.dropLast(1).joinToString("\n")) to true
            } else {
                throw e
            }
        }
    }
}
