package kr.co.byite.focus.spike.core

/**
 * 정상 종료 요약 없이 끝난 세션의 frames.csv / timebase.csv 로 요약을 다시 계산한다.
 * 실시간 요약과 같은 [SpikeSummary] 를 채운다. 갭 총수와 capture result 스트림은 CSV 에 없어 비운다.
 */
object RecoveredSummary {
    const val REASON_KILLED = "killed"

    /**
     * @param lastRecordLocal 마지막 행 t_utc_ms 를 호출자가 지역 시각 문자열로 만든 것. 행이 없으면 무시.
     */
    fun build(
        frames: SessionCsv.Frames,
        timebase: List<SessionCsv.TimebaseRow>,
        reason: String = REASON_KILLED,
        lastRecordLocal: String? = null,
    ): SpikeSummary {
        val h = frames.header
        val rows = frames.rows
        val timeline = RowTimeline()
        var gapsOver80 = 0L
        var gapsOverLong = 0L
        var maxGap = 0.0
        var screenOff = 0L
        var idle = 0L
        var maxThermal = 0
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        for (r in rows) {
            timeline.onRow(r.tMonoMs, r.frames)
            gapsOver80 += r.gapsOver80
            if (r.maxGapMs > 1000.0) gapsOverLong++
            if (r.maxGapMs > maxGap) maxGap = r.maxGapMs
            if (!r.interactive) screenOff++
            if (r.idle) idle++
            if (r.thermal > maxThermal) maxThermal = r.thermal
            if (r.batteryPct != null) {
                if (batteryStart == null) batteryStart = r.batteryPct
                batteryEnd = r.batteryPct
            }
        }
        val startMono = h["start_t_mono_ms"]?.toLongOrNull() ?: timeline.firstTMonoMs
        val lastMono = timeline.lastTMonoMs
        val spanMs = if (startMono != null && lastMono != null) (lastMono - startMono).coerceAtLeast(0) else 0L
        val avgFps = if (spanMs > 0) timeline.totalFrames * 1000.0 / spanMs else 0.0

        val initial = timebase.firstOrNull()
        val drifts = timebase.drop(1)
        val last = rows.lastOrNull()
        val lastRecord = if (last == null) {
            "없음 (행 없음)"
        } else {
            "${lastRecordLocal ?: "t_utc_ms=${last.tUtcMs}"} (t_mono_ms=${last.tMonoMs}, 세션 시작 뒤 ${Fmt.f1(spanMs / 1000.0)}s)" +
                if (frames.badLines > 0) ", 잘린 줄 ${frames.badLines}" else ""
        }

        return SpikeSummary(
            sessionId = h["session_id"] ?: "?",
            device = "${h["device_model"] ?: "?"}, ${h["os_version"] ?: "?"}, app ${h["app_version"] ?: "?"}",
            camera = "id=${h["camera_id"] ?: "?"} ${h["resolution"] ?: "?"}, fps 요청 ${h["fps_selected"] ?: "?"} / 결과 미확인 (CSV 재계산)",
            timestampSource = h["timestamp_source"] ?: "?",
            batteryOptimizationIgnored = h["battery_optimization_ignored"] ?: "?",
            reason = reason,
            startLocal = h["start_local"] ?: "?",
            lengthS = spanMs / 1000.0,
            lastRecord = lastRecord,
            totalFrames = timeline.totalFrames,
            avgFps = avgFps,
            gapsOver80 = gapsOver80,
            gapCount = null,
            gapsOverLong = gapsOverLong,
            gapsOverLongIsLowerBound = true,
            maxGapMs = maxGap,
            zeroFrameRows = timeline.zeroFrameRows,
            rowGapSeconds = timeline.rowGapSeconds,
            frameBucketMissing = null,
            rows = timeline.rows,
            screenOffRows = screenOff,
            idleRows = idle,
            batteryStart = batteryStart,
            batteryEnd = batteryEnd,
            maxThermal = maxThermal,
            offsetMs = initial?.let { it.offsetNs / 1e6 },
            lastDriftMs = drifts.lastOrNull()?.let { it.driftNs / 1e6 },
            maxAbsDriftMs = drifts.maxOfOrNull { kotlin.math.abs(it.driftNs) }?.let { it / 1e6 },
            remeasureCount = drifts.size,
            resultStreamLine = null,
            serviceSurvived = false,
        )
    }
}
