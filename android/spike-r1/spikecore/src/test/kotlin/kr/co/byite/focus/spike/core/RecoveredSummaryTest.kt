package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveredSummaryTest {
    private val frames = """
        # session_id=20260919_143000
        # device_model=samsung SM-S931N (e1s)
        # os_version=Android 16 (API 36)
        # app_version=0.1-spike-r1 (1)
        # camera_id=1
        # resolution=1280x720
        # fps_selected=[24,24]
        # timestamp_source=REALTIME(1)
        # battery_optimization_ignored=true
        # start_t_mono_ms=1000
        # start_local=2026-09-19T14:30:00.000+09:00
        ${SessionCsv.FRAMES_COLUMNS}
        2000,1758260000000,24,41.7,0,31.2,87.4,0,90,-312000,1,0
        3000,1758260001000,0,0.0,0,,,1,90,,0,1
        4000,1758260002000,23,1200.5,1,30.9,88.0,1,89,-310000,0,0
        7000,1758260005000,24,90.0,2,31.0,88.1,0,88,-300000,0,0
    """.trimIndent()

    private val timebase = """
        t_mono_ms,offset_ns,drift_ns,frames,complete
        2500,31240000,0,100,1
        62500,31280000,40000,100,1
        7000,31200000,-40000,37,0
    """.trimIndent()

    @Test
    fun aggregatesRowsIntoSummary() {
        val s = RecoveredSummary.build(
            SessionCsv.parseFrames(frames),
            SessionCsv.parseTimebase(timebase),
            lastRecordLocal = "2026-09-19T14:30:05.000+09:00",
        )
        assertEquals("20260919_143000", s.sessionId)
        assertEquals("killed", s.reason)
        assertFalse(s.serviceSurvived)
        assertEquals(71, s.totalFrames)
        assertEquals(6.0, s.lengthS, 1e-9) // (7000 − 1000) / 1000
        assertEquals(71 * 1000.0 / 6000.0, s.avgFps, 1e-9)
        assertEquals(3, s.gapsOver80)
        assertNull(s.gapCount)
        assertEquals(3.0 / 70.0, s.gapsOver80Ratio, 1e-12) // 분모 = 프레임 − 1
        assertEquals(1, s.gapsOverLong)
        assertTrue(s.gapsOverLongIsLowerBound)
        assertEquals(1200.5, s.maxGapMs)
        assertEquals(1, s.zeroFrameRows)
        assertEquals(2, s.rowGapSeconds) // 4000 → 7000
        assertEquals(3, s.missingSeconds)
        assertNull(s.frameBucketMissing)
        assertEquals(4, s.rows)
        assertEquals(3, s.screenOffRows)
        assertEquals(1, s.idleRows)
        assertEquals(90, s.batteryStart)
        assertEquals(88, s.batteryEnd)
        assertEquals(1, s.maxThermal)
        assertEquals(31.24, s.offsetMs!!, 1e-9)
        assertEquals(-0.04, s.lastDriftMs!!, 1e-9)
        assertEquals(0.04, s.maxAbsDriftMs!!, 1e-9)
        assertEquals(2, s.remeasureCount)
        assertFalse(s.pass)

        val text = s.render()
        assertTrue(text.contains("종료 사유: killed"))
        assertTrue(text.contains("마지막 기록: 2026-09-19T14:30:05.000+09:00 (t_mono_ms=7000, 세션 시작 뒤 6.0s)"))
        assertTrue(text.contains("누락된 초: 3 (프레임 0인 행 1 + 행 없는 초 2)"))
        assertTrue(text.contains("1초 초과 갭: 1 (행별 max_gap 기준, 하한)"))
        assertTrue(text.contains("[서비스 생존: FAIL (killed)]"))
        assertTrue(text.contains("배터리: 90% → 88%"))
        assertTrue(text.contains("timestamp source: REALTIME(1), 배터리 최적화 예외: true"))
    }

    @Test
    fun emptyCsvDoesNotCrash() {
        val s = RecoveredSummary.build(SessionCsv.parseFrames(""), emptyList())
        assertEquals(0, s.rows)
        assertEquals(0, s.totalFrames)
        assertEquals(0.0, s.avgFps)
        assertEquals("없음 (행 없음)", s.lastRecord)
        assertNull(s.offsetMs)
        assertTrue(s.render().contains("판정: 불합격"))
    }

    @Test
    fun truncatedLastLineIsNotedInLastRecord() {
        val text = frames + "\n8000,1758260006000,24"
        val s = RecoveredSummary.build(SessionCsv.parseFrames(text), emptyList())
        assertEquals(4, s.rows)
        val lastRecord = s.lastRecord
        assertNotNull(lastRecord)
        assertTrue(lastRecord.contains("잘린 줄 1"))
        assertTrue(lastRecord.contains("t_utc_ms=1758260005000"))
    }
}
