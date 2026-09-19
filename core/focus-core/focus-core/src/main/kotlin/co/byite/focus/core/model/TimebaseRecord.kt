package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Timebase calibration, `timebase` line of a session JSONL (schema 0.2.1; spec 9장 시간 기준, R3).
 * Written at session start and once a minute so camera/IMU timestamp drift can be checked in replay.
 */
@Serializable
data class TimebaseRecord(
    @SerialName("t_mono_ms") val tMonoMs: Long,
    /** Camera timestamp source as reported by the platform (e.g. "REALTIME", "UNKNOWN"). */
    @SerialName("camera_ts_source") val cameraTsSource: String,
    @SerialName("camera_to_mono_offset_ns") val cameraToMonoOffsetNs: Long,
    @SerialName("imu_to_mono_offset_ns") val imuToMonoOffsetNs: Long,
)
