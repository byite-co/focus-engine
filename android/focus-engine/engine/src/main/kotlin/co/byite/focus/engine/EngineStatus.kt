package co.byite.focus.engine

import android.content.Context
import android.content.SharedPreferences

/** Live state the service exposes to a UI in the same process. Scalars and strings only. */
object EngineStatus {
    @Volatile var running: Boolean = false
    @Volatile var sessionId: String? = null
    @Volatile var logPath: String? = null
    @Volatile var line: String = ""
    @Volatile var segmentLabel: String? = null
}

/** What must survive the process: last summary, log path, whether a session is still open (R6 복구). */
class EnginePrefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("focus-engine", Context.MODE_PRIVATE)

    var lastSummary: String?
        get() = p.getString(KEY_SUMMARY, null)
        set(v) = p.edit().putString(KEY_SUMMARY, v).apply()

    var lastLogPath: String?
        get() = p.getString(KEY_LOG, null)
        set(v) = p.edit().putString(KEY_LOG, v).apply()

    /** True from session start until a normal stop wrote `session_end`. Synchronous so it survives a kill. */
    var sessionActive: Boolean
        get() = p.getBoolean(KEY_ACTIVE, false)
        set(v) {
            p.edit().putBoolean(KEY_ACTIVE, v).commit()
        }

    var activeSessionId: String?
        get() = p.getString(KEY_ACTIVE_ID, null)
        set(v) {
            p.edit().putString(KEY_ACTIVE_ID, v).commit()
        }

    /** Session whose summary was rebuilt from its JSONL after a crash; cleared when a new session starts. */
    var lastRecoveredSessionId: String?
        get() = p.getString(KEY_RECOVERED_ID, null)
        set(v) = p.edit().putString(KEY_RECOVERED_ID, v).apply()

    private companion object {
        const val KEY_SUMMARY = "last_summary"
        const val KEY_LOG = "last_log_path"
        const val KEY_ACTIVE = "session_active"
        const val KEY_ACTIVE_ID = "active_session_id"
        const val KEY_RECOVERED_ID = "last_recovered_session_id"
    }
}
