package kr.co.byite.focus.spike.core

/**
 * 세션 요약. 실시간 기록(SessionRecorder)과 CSV 재계산(RecoveredSummary)이 같은 형태를 채우고
 * [render] 로 같은 글을 만든다. 날짜·시각 문자열은 호출자가 미리 만든다.
 */
data class SpikeSummary(
    val sessionId: String,
    val device: String,
    val camera: String,
    val timestampSource: String,
    val batteryOptimizationIgnored: String,
    val reason: String,
    val startLocal: String,
    val lengthS: Double,
    /** 비정상 종료 복원 때 마지막 기록 시각. 정상 종료면 null. */
    val lastRecord: String?,
    val totalFrames: Long,
    val avgFps: Double,
    val gapsOver80: Long,
    /** 갭 총수. CSV 재계산에서는 알 수 없어 null 이고, 이때 비율 분모는 프레임 수 − 1 이다. */
    val gapCount: Long?,
    val gapsOverLong: Long,
    /** CSV 재계산은 행별 max_gap 으로 세므로 하한이다. */
    val gapsOverLongIsLowerBound: Boolean,
    val maxGapMs: Double,
    val zeroFrameRows: Long,
    val rowGapSeconds: Long,
    /** 프레임 capture timestamp 1초 버킷 기준 누락 (실시간 기록만). */
    val frameBucketMissing: Long?,
    val rows: Long,
    val screenOffRows: Long,
    val idleRows: Long,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val maxThermal: Int,
    val offsetMs: Double?,
    val lastDriftMs: Double?,
    val maxAbsDriftMs: Double?,
    val remeasureCount: Int,
    /** 실시간 기록만: Camera2 capture result 스트림 요약 한 줄. */
    val resultStreamLine: String?,
    /** 서비스가 살아서 정상 정지됐는지. false 면 판정에 FAIL 로 적는다. */
    val serviceSurvived: Boolean,
) {
    val missingSeconds: Long get() = zeroFrameRows + rowGapSeconds

    val gapsOver80Ratio: Double
        get() {
            val denom = gapCount ?: (totalFrames - 1).coerceAtLeast(0)
            return if (denom > 0) gapsOver80.toDouble() / denom else 0.0
        }

    val passGapRatio: Boolean get() = gapsOver80Ratio < PASS_GAP_RATIO
    val passLongGap: Boolean get() = gapsOverLong == 0L
    val passMissing: Boolean get() = missingSeconds == 0L
    val pass: Boolean get() = passGapRatio && passLongGap && passMissing && serviceSurvived

    fun render(): String {
        fun mark(b: Boolean) = if (b) "OK" else "FAIL"
        fun ms(v: Double?) = v?.let { Fmt.f3(it) + " ms" } ?: "없음"
        return buildString {
            appendLine("spike-r1 요약  세션 $sessionId")
            appendLine("기기: $device")
            appendLine("카메라: $camera")
            appendLine("timestamp source: $timestampSource, 배터리 최적화 예외: $batteryOptimizationIgnored")
            appendLine("종료 사유: $reason, 세션 길이 ${Fmt.f1(lengthS)}s, 시작 $startLocal")
            if (lastRecord != null) appendLine("마지막 기록: $lastRecord")
            appendLine("총 프레임: $totalFrames, 평균 fps ${Fmt.f2(avgFps)}")
            val denomText = gapCount?.toString() ?: "프레임 ${(totalFrames - 1).coerceAtLeast(0)}"
            appendLine("80ms 초과 갭: $gapsOver80 / $denomText (${Fmt.pct(gapsOver80Ratio)})")
            appendLine("1초 초과 갭: $gapsOverLong${if (gapsOverLongIsLowerBound) " (행별 max_gap 기준, 하한)" else ""}")
            appendLine("최대 갭: ${Fmt.f1(maxGapMs)} ms")
            append("누락된 초: $missingSeconds (프레임 0인 행 $zeroFrameRows + 행 없는 초 $rowGapSeconds")
            if (frameBucketMissing != null) append(", 프레임 버킷 기준 $frameBucketMissing")
            appendLine(")")
            appendLine("초당 행: $rows, 화면 off 행 $screenOffRows, idle 행 $idleRows")
            appendLine("배터리: ${batteryStart ?: "?"}% → ${batteryEnd ?: "?"}%")
            appendLine("최고 thermal status: $maxThermal (${ThermalStatus.name(maxThermal)})")
            appendLine("offset: ${offsetMs?.let { Fmt.f3(it) + " ms" } ?: "미확정"}, drift: 마지막 ${ms(lastDriftMs)}, 최대 |drift| ${ms(maxAbsDriftMs)} (재측정 ${remeasureCount}회)")
            if (resultStreamLine != null) appendLine(resultStreamLine)
            append("판정: ${if (pass) "합격" else "불합격"}  ")
            append("[80ms 초과 갭 <1%: ${mark(passGapRatio)}] [1초 초과 갭 0: ${mark(passLongGap)}] [누락된 초 0: ${mark(passMissing)}] ")
            append("[서비스 생존: ${if (serviceSurvived) "OK (정상 정지)" else "FAIL ($reason)"}]")
        }
    }

    companion object {
        const val PASS_GAP_RATIO = 0.01
    }
}
