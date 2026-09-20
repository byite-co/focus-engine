package co.byite.focus.core.report

import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.V0bRawRecord
import co.byite.focus.core.util.Stats
import kotlin.math.abs

/** Whole-session part of the V0-B summary (directive C "요약 · 전체"). */
data class V0bOverall(
    val sessionId: String,
    val device: String,
    val algorithmVersion: String,
    val camera: String,
    val endReason: String,
    /** Session length in seconds: `session_end.t_mono_ms − header.t_start_mono_ms`, or up to the last record when there is no end line. */
    val sessionLengthS: Double,
    val records: Int,
    /** Complete buckets between the session start and the end that have no `second` line. */
    val missingRecords: Long,
    /** Records with `frames_processed == 0`. */
    val zeroFrameRecords: Int,
    val framesRequested: Long,
    val framesProcessed: Long,
    val framesDropped: Long,
    /** Processed frames ÷ recorded seconds. */
    val processedFps: Double?,
    val gapsOver80Ms: Long,
    val maxFrameGapMs: Long?,
    /** Face Landmarker ms: frame-weighted mean, nearest-rank p95 of the per-second means, max of the per-second max. */
    val faceInferMsMean: Double?,
    val faceInferMsP95OfSecondMeans: Double?,
    val faceInferMsMax: Double?,
    val poseRuns: Long,
    val poseInferMsMean: Double?,
    val poseInferMsMax: Double?,
    val maxThermalStatus: Int?,
    val batteryStartPct: Int?,
    val batteryEndPct: Int?,
    /** Mean of the raw `battery_current_ua` values (sign as reported by the device). */
    val meanCurrentUa: Double?,
    /** Mean over seconds of |current_ua| × voltage_mv ÷ 10⁶ (mW). */
    val meanPowerMw: Double?,
    val screenOffSeconds: Int,
    val idleSeconds: Int,
    val timebaseLines: Int,
) {
    val missingSeconds: Long get() = missingRecords + zeroFrameRecords
    val dropRatio: Double? get() = if (framesRequested > 0) framesDropped.toDouble() / framesRequested else null
}

/** Per-marker part of the V0-B summary (directive C "요약 · 구간(마커)별"). */
data class V0bSegment(
    val label: String,
    val seconds: Int,
    /** Frame-weighted: Σ(face_detect_ratio × frames_processed) ÷ Σ frames_processed. */
    val faceDetectRatio: Double?,
    val yawMean: Double?,
    val yawSd: Double?,
    val pitchMean: Double?,
    val pitchSd: Double?,
    val rollMean: Double?,
    val rollSd: Double?,
    val faceWidthMedianPx: Double?,
    val jitterMedian: Double?,
    val jitterP95: Double?,
    /** Seconds with `shoulder_visibility_min ≥ 0.6` ÷ seconds. */
    val shoulderVisibleRatio: Double,
    /** Seconds with `head_landmark_present` ÷ seconds. */
    val headLandmarkRatio: Double,
    val headOffsetMedian: Double?,
    val lumaMean: Double?,
)

data class V0bSummary(val overall: V0bOverall, val segments: List<V0bSegment>, val notes: List<String>) {
    fun render(): String = V0bReport.render(this)
}

/**
 * V0-B session summary from a [SessionLog] (schema 0.2.1 `second` lines + `v0b_raw` lines).
 * The live path and the crash-recovery path build the same summary from the same records, so a
 * recovered summary differs from a live one only by the rows lost before the last flush.
 * Deterministic; no clock.
 */
object V0bReport {
    const val SHOULDER_VISIBILITY_MIN: Double = 0.6
    const val NO_LABEL: String = "(마커 없음)"

    fun build(log: SessionLog, notes: List<String> = emptyList()): V0bSummary {
        val h = log.header
        val records = log.records.sortedBy { it.tMonoMs }
        val rawByT = log.v0bRaw.associateBy { it.tMonoMs }
        val end = log.sessionEnd
        val endMono = end?.tMonoMs ?: records.lastOrNull()?.let { it.tMonoMs + FocusSchema.RECORD_PERIOD_MS } ?: h.tStartMonoMs
        val lengthMs = (endMono - h.tStartMonoMs).coerceAtLeast(0L)
        val expected = lengthMs / FocusSchema.RECORD_PERIOD_MS

        val processedTotal = records.sumOf { it.framesProcessed.toLong() }
        val faceMeans = ArrayList<Double>()
        var faceWeighted = 0.0
        var faceWeight = 0L
        var faceMax: Double? = null
        var poseRuns = 0L
        var poseWeighted = 0.0
        var poseMax: Double? = null
        val currents = ArrayList<Double>()
        val powers = ArrayList<Double>()
        var maxThermal: Int? = null
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        var screenOff = 0
        var idle = 0
        for (r in records) {
            val raw = rawByT[r.tMonoMs] ?: continue
            raw.faceInferMsMean?.let { m ->
                faceMeans.add(m)
                faceWeighted += m * r.framesProcessed
                faceWeight += r.framesProcessed
            }
            raw.faceInferMsMax?.let { faceMax = maxOf(faceMax ?: it, it) }
            poseRuns += raw.poseSamples
            raw.poseInferMsMean?.let { poseWeighted += it * raw.poseSamples }
            raw.poseInferMsMax?.let { poseMax = maxOf(poseMax ?: it, it) }
            raw.batteryCurrentUa?.let { i ->
                currents.add(i.toDouble())
                raw.batteryVoltageMv?.let { v -> powers.add(abs(i.toDouble()) * v / 1_000_000.0) }
            }
            maxThermal = maxOf(maxThermal ?: raw.thermalStatus, raw.thermalStatus)
            raw.batteryPct?.let { if (batteryStart == null) batteryStart = it; batteryEnd = it }
            if (!raw.isInteractive) screenOff++
            if (raw.isDeviceIdle) idle++
        }

        val overall = V0bOverall(
            sessionId = h.sessionId,
            device = "${h.deviceModel}, Android ${h.osVersion}",
            algorithmVersion = h.algorithmVersion,
            camera = "${h.cameraResolution} @ ${h.nominalFps}fps",
            endReason = end?.reason?.name ?: "없음 (session_end 줄 없음)",
            sessionLengthS = lengthMs / 1000.0,
            records = records.size,
            missingRecords = (expected - records.size).coerceAtLeast(0L),
            zeroFrameRecords = records.count { it.framesProcessed == 0 },
            framesRequested = records.sumOf { it.framesRequested.toLong() },
            framesProcessed = processedTotal,
            framesDropped = records.sumOf { it.framesDropped.toLong() },
            processedFps = if (records.isNotEmpty()) processedTotal.toDouble() / records.size else null,
            gapsOver80Ms = records.sumOf { it.gapsOver80Ms.toLong() },
            maxFrameGapMs = records.mapNotNull { it.maxFrameGapMs }.maxOrNull(),
            faceInferMsMean = if (faceWeight > 0) faceWeighted / faceWeight else null,
            faceInferMsP95OfSecondMeans = Stats.percentileNearestRankOf(faceMeans, 0.95),
            faceInferMsMax = faceMax,
            poseRuns = poseRuns,
            poseInferMsMean = if (poseRuns > 0) poseWeighted / poseRuns else null,
            poseInferMsMax = poseMax,
            maxThermalStatus = maxThermal,
            batteryStartPct = batteryStart,
            batteryEndPct = batteryEnd,
            meanCurrentUa = Stats.meanOf(currents),
            meanPowerMw = Stats.meanOf(powers),
            screenOffSeconds = screenOff,
            idleSeconds = idle,
            timebaseLines = log.timebase.size,
        )

        val order = ArrayList<String>()
        val groups = HashMap<String, ArrayList<Pair<SecondRecord, V0bRawRecord?>>>()
        for (r in records) {
            val raw = rawByT[r.tMonoMs]
            val label = raw?.segmentLabel ?: NO_LABEL
            if (label !in groups) {
                order.add(label)
                groups[label] = ArrayList()
            }
            groups.getValue(label).add(r to raw)
        }
        val segments = order.map { label -> segment(label, groups.getValue(label)) }
        return V0bSummary(overall, segments, notes)
    }

    private fun segment(label: String, rows: List<Pair<SecondRecord, V0bRawRecord?>>): V0bSegment {
        val secs = rows.map { it.first }
        val processed = secs.sumOf { it.framesProcessed.toLong() }
        val faceFrames = secs.sumOf { it.faceDetectRatio * it.framesProcessed }
        val yaw = secs.mapNotNull { it.yawMean }
        val pitch = secs.mapNotNull { it.pitchMean }
        val roll = secs.mapNotNull { it.rollMean }
        val jitter = secs.mapNotNull { it.jitterJ }
        return V0bSegment(
            label = label,
            seconds = secs.size,
            faceDetectRatio = if (processed > 0) faceFrames / processed else null,
            yawMean = Stats.meanOf(yaw),
            yawSd = Stats.stddevOf(yaw),
            pitchMean = Stats.meanOf(pitch),
            pitchSd = Stats.stddevOf(pitch),
            rollMean = Stats.meanOf(roll),
            rollSd = Stats.stddevOf(roll),
            faceWidthMedianPx = Stats.medianOf(secs.mapNotNull { it.faceWidthPx }),
            jitterMedian = Stats.medianOf(jitter),
            jitterP95 = Stats.percentileNearestRankOf(jitter, 0.95),
            shoulderVisibleRatio = secs.count { (it.shoulderVisibilityMin ?: -1.0) >= SHOULDER_VISIBILITY_MIN }.toDouble() / secs.size,
            headLandmarkRatio = secs.count { it.headLandmarkPresent }.toDouble() / secs.size,
            headOffsetMedian = Stats.medianOf(secs.mapNotNull { it.headOffsetBelowShoulderRatio }),
            lumaMean = Stats.meanOf(secs.map { it.sceneLuma }),
        )
    }

    fun render(s: V0bSummary): String {
        val o = s.overall
        fun f(x: Double?, d: Int = 1): String = Stats.fmt(x, d)
        fun deg(m: Double?, sd: Double?): String = "${f(m)}° ± ${f(sd)}°"
        return buildString {
            appendLine("focus-engine V0-A/B 요약  세션 ${o.sessionId}")
            appendLine("기기: ${o.device}  엔진 ${o.algorithmVersion}  카메라 ${o.camera}")
            appendLine("종료: ${o.endReason}, 세션 길이 ${f(o.sessionLengthS)}s, 초당 레코드 ${o.records}")
            appendLine("프레임: 요청 ${o.framesRequested}, 처리 ${o.framesProcessed}, 드롭 ${o.framesDropped} (${Stats.pct(o.dropRatio)}), 처리 fps ${f(o.processedFps, 2)}")
            appendLine("80ms 초과 갭: ${o.gapsOver80Ms}, 최대 갭 ${o.maxFrameGapMs?.let { "$it ms" } ?: "-"}")
            appendLine("누락된 초: ${o.missingSeconds} (레코드 없는 초 ${o.missingRecords} + 프레임 0인 초 ${o.zeroFrameRecords})")
            appendLine("Face 추론 ms: 평균 ${f(o.faceInferMsMean)}, p95 ${f(o.faceInferMsP95OfSecondMeans)} (초당 평균 기준), 최대 ${f(o.faceInferMsMax)}")
            appendLine("Pose 추론 ms: 평균 ${f(o.poseInferMsMean)}, 최대 ${f(o.poseInferMsMax)} (${o.poseRuns}회)")
            appendLine("최고 thermal status: ${o.maxThermalStatus?.let { "$it (${thermalName(it)})" } ?: "-"}")
            appendLine("배터리: ${o.batteryStartPct ?: "?"}% → ${o.batteryEndPct ?: "?"}%, 평균 전류 ${f(o.meanCurrentUa, 0)} µA, 추정 평균 전력 ${f(o.meanPowerMw)} mW")
            appendLine("화면 off 행 ${o.screenOffSeconds}, idle 행 ${o.idleSeconds}, timebase 줄 ${o.timebaseLines}")
            for (g in s.segments) {
                appendLine("[${g.label}] ${g.seconds}s  face ${Stats.pct(g.faceDetectRatio)}  yaw ${deg(g.yawMean, g.yawSd)}  pitch ${deg(g.pitchMean, g.pitchSd)}  roll ${deg(g.rollMean, g.rollSd)}")
                appendLine("    얼굴 폭 중앙값 ${f(g.faceWidthMedianPx, 0)}px  j 중앙값 ${f(g.jitterMedian, 4)} p95 ${f(g.jitterP95, 4)}  어깨 vis≥0.6 ${Stats.pct(g.shoulderVisibleRatio)}  머리 landmark ${Stats.pct(g.headLandmarkRatio)}  head_offset 중앙값 ${f(g.headOffsetMedian, 2)}  휘도 ${f(g.lumaMean, 0)}")
            }
            for (n in s.notes) appendLine("※ $n")
        }.trimEnd()
    }

    /** Android `PowerManager` thermal status names, same order as the constants. */
    fun thermalName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
