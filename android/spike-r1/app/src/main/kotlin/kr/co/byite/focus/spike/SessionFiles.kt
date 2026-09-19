package kr.co.byite.focus.spike

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 세션 파일 위치. 앱 전용 외부 저장소 아래 spike-r1/<session_id>/.
 * 픽셀·프레임은 어떤 파일에도 쓰지 않는다. 전부 스칼라 값이다.
 */
class SessionFiles(val dir: File, val sessionId: String) {
    val framesCsv: File = File(dir, "frames.csv")
    val timebaseCsv: File = File(dir, "timebase.csv")
    val eventsLog: File = File(dir, "events.log")
    val summaryTxt: File = File(dir, "summary.txt")

    companion object {
        fun newSessionId(now: Date = Date()): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

        fun root(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "spike-r1")
        }

        fun create(context: Context, sessionId: String = newSessionId()): SessionFiles {
            val dir = File(root(context), sessionId)
            dir.mkdirs()
            return SessionFiles(dir, sessionId)
        }
    }
}
