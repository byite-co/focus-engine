package co.byite.focus.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session directory under the app-specific external storage: focus-engine/<session_id>/.
 * Only scalar text files live here: the JSONL log, an event log and the summary. No pixels, ever.
 */
class SessionFiles(val dir: File, val sessionId: String) {
    val sessionJsonl: File = File(dir, "session.jsonl")
    val eventsLog: File = File(dir, "events.log")
    val summaryTxt: File = File(dir, "summary.txt")

    companion object {
        fun newSessionId(now: Date = Date()): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

        fun root(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "focus-engine")
        }

        fun create(context: Context, sessionId: String = newSessionId()): SessionFiles {
            val dir = File(root(context), sessionId)
            dir.mkdirs()
            return SessionFiles(dir, sessionId)
        }

        fun localTime(utcMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(utcMs))
    }
}
