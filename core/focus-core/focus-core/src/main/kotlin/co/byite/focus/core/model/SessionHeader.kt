package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session header (spec 9장 세션 header + schema 0.2.1 start times + schema 0.2.3 capture preset). First line of a session JSONL. */
@Serializable
data class SessionHeader(
    @SerialName("session_id") val sessionId: String,
    @SerialName("participant_id") val participantId: String,
    /** Session start on the monotonic clock; per-second buckets are aligned to it. */
    @SerialName("t_start_mono_ms") val tStartMonoMs: Long,
    @SerialName("t_start_utc_ms") val tStartUtcMs: Long,
    @SerialName("spec_version") val specVersion: String = FocusSchema.SPEC_VERSION,
    /** git commit or build id of the engine that produced the log. */
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("feature_schema_version") val featureSchemaVersion: String = FocusSchema.FEATURE_SCHEMA_VERSION,
    /** Immutable id of the [ParameterSet] used; never copied per second. */
    @SerialName("parameter_set_id") val parameterSetId: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_version") val osVersion: String,
    /** e.g. "640x480". */
    @SerialName("camera_resolution") val cameraResolution: String,
    @SerialName("nominal_fps") val nominalFps: Int,
    @SerialName("calibration_id") val calibrationId: String,
    @SerialName("calibration_snapshot_version") val calibrationSnapshotVersion: String,
    @SerialName("task_mode") val taskMode: TaskMode,

    // ---- capture preset (schema 0.2.3, CHANGELOG v0.2.3). Null / default in older logs.
    /** Dev-app capture preset id (A, B, C, C2, D, E, G, F); null when the log predates presets. */
    @SerialName("capture_preset") val capturePreset: String? = null,
    /** Face runs on every n-th received frame: 1 = every frame, 2 = every other frame (preset E). */
    @SerialName("frame_process_divisor") val frameProcessDivisor: Int = 1,
    /** Threshold of `gaps_over_threshold`: 80 ms for every-frame presets, 2 × expected interval otherwise (167 ms for E). */
    @SerialName("frame_gap_threshold_ms") val frameGapThresholdMs: Int = 80,
    /** Face Landmarker delegate ("CPU", "GPU"); null when unknown. */
    @SerialName("face_delegate") val faceDelegate: String? = null,
    /** Face Landmarker blendshape output on/off; null when unknown. */
    @SerialName("face_blendshapes") val faceBlendshapes: Boolean? = null,
    /** PerformanceHintManager target for the Face analysis thread (preset G); null when no hint session. */
    @SerialName("perf_hint_target_ms") val perfHintTargetMs: Int? = null,
    /** Threshold of `gaps_over_long_threshold`: 200 ms for every-frame presets, 5 × expected interval otherwise (417 ms for E at 24 fps). */
    @SerialName("frame_long_gap_threshold_ms") val frameLongGapThresholdMs: Int = 200,
    /** Camera2 id of the camera in use; null in older logs. */
    @SerialName("camera_id") val cameraId: String? = null,
    /** Lens facing ("FRONT", "BACK", "EXTERNAL"); null in older logs. */
    @SerialName("lens_facing") val lensFacing: String? = null,
    /** True when a hinge-angle sensor was detected, false when none was found (fold state then unknown), null in older logs. */
    @SerialName("hinge_sensor") val hingeSensor: Boolean? = null,

    // ---- camera cadence and Face schedule (schema 0.2.4, CHANGELOG v0.2.4, directive E). Null in older logs.
    /** Requested `CONTROL_AE_TARGET_FPS_RANGE` lower / upper bound; both null when no range was requested (HAL default). Fixed cadence = lower == upper. */
    @SerialName("camera_fps_request_lower") val cameraFpsRequestLower: Int? = null,
    @SerialName("camera_fps_request_upper") val cameraFpsRequestUpper: Int? = null,
    /** Every AE target fps range the camera offers, e.g. "[7,15],[15,15],[24,24],[30,30]". */
    @SerialName("camera_fps_ranges_supported") val cameraFpsRangesSupported: String? = null,
    /** How Face frames are chosen: every capture result, or the 3장 slot rule at [faceProcessPeriodNs]. */
    @SerialName("face_schedule") val faceSchedule: FaceSchedule? = null,
    /** Expected interval between Face-processed frames (ns): the camera frame interval for every-frame presets, `1e9 / rate` for slot presets. Hvar: the interval at the range's upper bound. */
    @SerialName("face_process_period_ns") val faceProcessPeriodNs: Long? = null,
    /** `face_process_period_ns × 1.5` — the exact threshold `gaps_over_threshold` was counted against (the ms field is its rounding). */
    @SerialName("frame_gap_threshold_ns") val frameGapThresholdNs: Long? = null,
    /** `face_process_period_ns × 4.5` — the exact threshold of `gaps_over_long_threshold`. */
    @SerialName("frame_long_gap_threshold_ns") val frameLongGapThresholdNs: Long? = null,
) {
    init {
        require(frameProcessDivisor >= 1) { "frame_process_divisor must be >= 1" }
        require(frameGapThresholdMs > 0) { "frame_gap_threshold_ms must be positive" }
        require(frameLongGapThresholdMs >= frameGapThresholdMs) { "frame_long_gap_threshold_ms must not be below frame_gap_threshold_ms" }
        perfHintTargetMs?.let { require(it > 0) { "perf_hint_target_ms must be positive" } }
        require((cameraFpsRequestLower == null) == (cameraFpsRequestUpper == null)) { "camera_fps_request_lower and _upper go together" }
        if (cameraFpsRequestLower != null && cameraFpsRequestUpper != null) {
            require(cameraFpsRequestLower > 0 && cameraFpsRequestUpper >= cameraFpsRequestLower) { "camera_fps_request must be a positive range" }
        }
        faceProcessPeriodNs?.let { require(it > 0) { "face_process_period_ns must be positive" } }
        frameGapThresholdNs?.let { require(it > 0) { "frame_gap_threshold_ns must be positive" } }
        if (frameGapThresholdNs != null && frameLongGapThresholdNs != null) {
            require(frameLongGapThresholdNs >= frameGapThresholdNs) { "frame_long_gap_threshold_ns must not be below frame_gap_threshold_ns" }
        }
    }

    /** Camera aspect ratio reduced from [cameraResolution] ("1280x720" → "16:9"); null when the string is not WxH. */
    val cameraAspectRatio: String? get() = aspectRatioOf(cameraResolution)

    /** True for a fixed AE range (lower == upper), false for a variable one, null when none was requested (older log or HAL default). */
    val cameraFpsRequestFixed: Boolean? get() = if (cameraFpsRequestLower == null || cameraFpsRequestUpper == null) null else cameraFpsRequestLower == cameraFpsRequestUpper

    /** "[24,24]", "[7,15]" or "unset". */
    val cameraFpsRequestLabel: String get() = if (cameraFpsRequestLower == null || cameraFpsRequestUpper == null) "unset" else "[$cameraFpsRequestLower,$cameraFpsRequestUpper]"

    /** Expected Face processing rate (Hz) from [faceProcessPeriodNs]; null in older logs. */
    val expectedFaceRateHz: Double? get() = faceProcessPeriodNs?.let { 1e9 / it }

    companion object {
        const val FPS_RANGES_SEPARATOR: String = ","

        /** "[a,b],[c,d]" for the header from (lower, upper) pairs. */
        fun fpsRangesLabel(ranges: List<Pair<Int, Int>>): String = ranges.joinToString(FPS_RANGES_SEPARATOR) { "[${it.first},${it.second}]" }

        fun aspectRatioOf(resolution: String): String? {
            val parts = resolution.split('x')
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            var a = w
            var b = h
            while (b != 0) {
                val t = a % b
                a = b
                b = t
            }
            return "${w / a}:${h / a}"
        }
    }
}

/** How the analysis thread chooses the frames Face runs on (schema 0.2.4 header `face_schedule`). */
@Serializable
enum class FaceSchedule {
    /** Every capture result inside the session window is a processing opportunity (A, B, C, D, G, H12, H15, Hvar). */
    @SerialName("every_frame") EVERY_FRAME,
    /** The 3장 slot rule: opportunities at `face_process_period_ns` from the anchor, phase kept (E, E15). */
    @SerialName("slot") SLOT,
}
