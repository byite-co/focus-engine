package kr.co.byite.focus.spike

import android.content.Context
import android.content.SharedPreferences

/** 서비스 → Activity 로 보여 주는 진행 상태. 같은 프로세스 안에서만 유효하다. */
object SpikeStatus {
    @Volatile var running: Boolean = false
    @Volatile var sessionId: String? = null
    @Volatile var csvPath: String? = null
    @Volatile var line: String = ""
}

/** 프로세스가 죽어도 남아야 하는 것: 마지막 요약, CSV 경로, 세션이 정상 종료됐는지. */
class SpikePrefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("spike", Context.MODE_PRIVATE)

    var lastSummary: String?
        get() = p.getString(KEY_SUMMARY, null)
        set(v) = p.edit().putString(KEY_SUMMARY, v).apply()

    var lastCsvPath: String?
        get() = p.getString(KEY_CSV, null)
        set(v) = p.edit().putString(KEY_CSV, v).apply()

    /** 세션 시작 때 true, 정상 정지 때 false. 앱 재실행 시 true 면 서비스가 비정상 종료된 것이다. */
    var sessionActive: Boolean
        get() = p.getBoolean(KEY_ACTIVE, false)
        set(v) {
            p.edit().putBoolean(KEY_ACTIVE, v).commit() // 프로세스가 곧 죽어도 남게 동기 저장
        }

    var activeSessionId: String?
        get() = p.getString(KEY_ACTIVE_ID, null)
        set(v) {
            p.edit().putString(KEY_ACTIVE_ID, v).commit()
        }

    /** 마지막으로 CSV 재계산으로 복원한 세션. 다음 세션을 시작하면 지운다. */
    var lastRecoveredSessionId: String?
        get() = p.getString(KEY_RECOVERED_ID, null)
        set(v) = p.edit().putString(KEY_RECOVERED_ID, v).apply()

    private companion object {
        const val KEY_RECOVERED_ID = "last_recovered_session_id"
        const val KEY_SUMMARY = "last_summary"
        const val KEY_CSV = "last_csv_path"
        const val KEY_ACTIVE = "session_active"
        const val KEY_ACTIVE_ID = "active_session_id"
    }
}
