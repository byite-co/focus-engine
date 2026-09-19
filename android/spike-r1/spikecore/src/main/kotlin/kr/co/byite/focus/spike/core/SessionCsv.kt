package kr.co.byite.focus.spike.core

/**
 * frames.csv / timebase.csv 파서. 순수 Kotlin. 한 줄씩 받아 스칼라만 남긴다.
 *
 * frames.csv: `# key=value` 헤더 줄, 열 이름 줄, 초당 행. 빈 칸은 null.
 * timebase.csv: `#` 줄, 열 이름 줄, `t_mono_ms,offset_ns,drift_ns,frames,complete`.
 */
object SessionCsv {
    data class Row(
        val tMonoMs: Long,
        val tUtcMs: Long,
        val frames: Long,
        val maxGapMs: Double,
        val gapsOver80: Long,
        val cbLatencyMs: Double?,
        val yMean: Double?,
        val thermal: Int,
        val batteryPct: Int?,
        val batteryCurrentUa: Int?,
        val interactive: Boolean,
        val idle: Boolean,
    )

    data class Frames(
        val header: Map<String, String>,
        val rows: List<Row>,
        /** 파싱 실패한 데이터 줄 수 (잘린 마지막 줄 등). */
        val badLines: Int,
    )

    data class TimebaseRow(
        val tMonoMs: Long,
        val offsetNs: Long,
        val driftNs: Long,
        val frames: Int,
        val complete: Boolean,
    )

    const val FRAMES_COLUMNS = "t_mono_ms,t_utc_ms,frames,max_gap_ms,gaps_over_80ms,cb_latency_ms_mean,y_mean,thermal_status,battery_pct,battery_current_ua,is_interactive,is_device_idle"
    private const val FRAMES_FIELDS = 12

    fun parseFrames(lines: Sequence<String>): Frames {
        val header = LinkedHashMap<String, String>()
        val rows = ArrayList<Row>()
        var bad = 0
        var seenColumns = false
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                val eq = body.indexOf('=')
                if (eq > 0) header[body.substring(0, eq).trim()] = body.substring(eq + 1).trim()
                continue
            }
            if (!seenColumns && line.startsWith("t_mono_ms,")) {
                seenColumns = true
                continue
            }
            val row = parseRow(line)
            if (row == null) bad++ else rows.add(row)
        }
        return Frames(header, rows, bad)
    }

    fun parseFrames(text: String): Frames = parseFrames(text.lineSequence())

    private fun parseRow(line: String): Row? {
        val f = line.split(',')
        if (f.size != FRAMES_FIELDS) return null
        return try {
            Row(
                tMonoMs = f[0].toLong(),
                tUtcMs = f[1].toLong(),
                frames = f[2].toLong(),
                maxGapMs = f[3].toDouble(),
                gapsOver80 = f[4].toLong(),
                cbLatencyMs = f[5].ifEmpty { null }?.toDouble(),
                yMean = f[6].ifEmpty { null }?.toDouble(),
                thermal = f[7].toInt(),
                batteryPct = f[8].ifEmpty { null }?.toInt(),
                batteryCurrentUa = f[9].ifEmpty { null }?.toInt(),
                interactive = f[10] == "1",
                idle = f[11] == "1",
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun parseTimebase(lines: Sequence<String>): List<TimebaseRow> {
        val out = ArrayList<TimebaseRow>()
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank() || line.startsWith("#") || line.startsWith("t_mono_ms,")) continue
            val f = line.split(',')
            if (f.size != 5) continue
            try {
                out.add(TimebaseRow(f[0].toLong(), f[1].toLong(), f[2].toLong(), f[3].toInt(), f[4] == "1"))
            } catch (e: NumberFormatException) {
                // 잘린 줄은 버린다
            }
        }
        return out
    }

    fun parseTimebase(text: String): List<TimebaseRow> = parseTimebase(text.lineSequence())
}
