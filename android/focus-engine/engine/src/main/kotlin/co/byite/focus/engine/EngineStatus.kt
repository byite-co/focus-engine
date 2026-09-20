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
    /** "자가 점검: OK (…)" or the failure with exception class and message; null before the first start. */
    @Volatile var selfCheck: String? = null
    /** Preset id of the running session (C2 when C fell back), null when idle. */
    @Volatile var preset: String? = null
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

    /** Result line of the engine self-check of the last start (shown as the first summary line, also after recovery). */
    var lastSelfCheck: String?
        get() = p.getString(KEY_SELF_CHECK, null)
        set(v) {
            p.edit().putString(KEY_SELF_CHECK, v).commit()
        }

    /** Session whose summary was rebuilt from its JSONL after a crash; cleared when a new session starts. */
    var lastRecoveredSessionId: String?
        get() = p.getString(KEY_RECOVERED_ID, null)
        set(v) = p.edit().putString(KEY_RECOVERED_ID, v).apply()

    /** Capture preset id the dev app last selected (directive D). */
    var lastPreset: String?
        get() = p.getString(KEY_PRESET, null)
        set(v) = p.edit().putString(KEY_PRESET, v).apply()

    private companion object {
        const val KEY_SUMMARY = "last_summary"
        const val KEY_LOG = "last_log_path"
        const val KEY_ACTIVE = "session_active"
        const val KEY_ACTIVE_ID = "active_session_id"
        const val KEY_RECOVERED_ID = "last_recovered_session_id"
        const val KEY_SELF_CHECK = "last_self_check"
        const val KEY_PRESET = "last_preset"
    }
}
