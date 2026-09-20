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
    /** True when the device exposes a hinge-angle sensor (foldable); false when it does not; null in older logs. */
    val foldable: Boolean? = null,
) {
    init {
        require(frameProcessDivisor >= 1) { "frame_process_divisor must be >= 1" }
        require(frameGapThresholdMs > 0) { "frame_gap_threshold_ms must be positive" }
        require(frameLongGapThresholdMs >= frameGapThresholdMs) { "frame_long_gap_threshold_ms must not be below frame_gap_threshold_ms" }
        perfHintTargetMs?.let { require(it > 0) { "perf_hint_target_ms must be positive" } }
    }

    /** Camera aspect ratio reduced from [cameraResolution] ("1280x720" → "16:9"); null when the string is not WxH. */
    val cameraAspectRatio: String? get() = aspectRatioOf(cameraResolution)

    companion object {
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
