package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session header (spec 9장 세션 header). First line of a session JSONL. */
@Serializable
data class SessionHeader(
    @SerialName("session_id") val sessionId: String,
    @SerialName("participant_id") val participantId: String,
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
)
