package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCsvTest {
    private val sample = """
        # spike-r1 session header
        # session_id=20260919_143000
        # device_model=samsung SM-S931N (e1s)
        # fps_selected=[24,24]
        # start_t_mono_ms=1000
        # start_local=2026-09-19T14:30:00.000+09:00
        ${SessionCsv.FRAMES_COLUMNS}
        2000,1758260000000,24,41.7,0,31.2,87.4,0,90,-312000,1,0
        3000,1758260001000,0,0.0,0,,,1,90,,0,1
        4000,1758260002000,23,1200.5,1,30.9,88.0,1,89,-310000,0,0
        5000,1758260003000,24,41.7,0,31.0
    """.trimIndent()

    @Test
    fun parsesHeaderRowsAndCountsBadLines() {
        val p = SessionCsv.parseFrames(sample)
        assertEquals("20260919_143000", p.header["session_id"])
        assertEquals("samsung SM-S931N (e1s)", p.header["device_model"])
        assertEquals("[24,24]", p.header["fps_selected"])
        assertEquals("1000", p.header["start_t_mono_ms"])
        assertEquals(3, p.rows.size)
        assertEquals(1, p.badLines)

        val r0 = p.rows[0]
        assertEquals(2000L, r0.tMonoMs)
        assertEquals(1758260000000L, r0.tUtcMs)
        assertEquals(24L, r0.frames)
        assertEquals(41.7, r0.maxGapMs)
        assertEquals(0L, r0.gapsOver80)
        assertEquals(31.2, r0.cbLatencyMs)
        assertEquals(87.4, r0.yMean)
        assertEquals(0, r0.thermal)
        assertEquals(90, r0.batteryPct)
        assertEquals(-312000, r0.batteryCurrentUa)
        assertTrue(r0.interactive)
        assertFalse(r0.idle)

        val r1 = p.rows[1]
        assertEquals(0L, r1.frames)
        assertNull(r1.cbLatencyMs)
        assertNull(r1.yMean)
        assertNull(r1.batteryCurrentUa)
        assertFalse(r1.interactive)
        assertTrue(r1.idle)

        assertEquals(1L, p.rows[2].gapsOver80)
        assertEquals(1200.5, p.rows[2].maxGapMs)
    }

    @Test
    fun toleratesCrlfAndBlankLines() {
        val text = sample.replace("\n", "\r\n") + "\r\n\r\n"
        val p = SessionCsv.parseFrames(text)
        assertEquals(3, p.rows.size)
        assertEquals(1, p.badLines)
    }

    @Test
    fun emptyInput() {
        val p = SessionCsv.parseFrames("")
        assertTrue(p.rows.isEmpty())
        assertTrue(p.header.isEmpty())
        assertEquals(0, p.badLines)
    }

    @Test
    fun parsesTimebase() {
        val text = """
            # spike-r1 timebase session_id=x timestamp_source=REALTIME(1)
            t_mono_ms,offset_ns,drift_ns,frames,complete
            5000,31240000,0,100,1
            65000,31280000,40000,100,1
            95000,31200000,-40000,37,0
            9600
        """.trimIndent()
        val rows = SessionCsv.parseTimebase(text)
        assertEquals(3, rows.size)
        assertEquals(31240000L, rows[0].offsetNs)
        assertEquals(0L, rows[0].driftNs)
        assertTrue(rows[0].complete)
        assertEquals(40000L, rows[1].driftNs)
        assertEquals(-40000L, rows[2].driftNs)
        assertEquals(37, rows[2].frames)
        assertFalse(rows[2].complete)
    }
}
