package co.byite.focus.engine.timebase

import android.hardware.camera2.CameraMetadata
import co.byite.focus.core.model.TimebaseRecord

/**
 * One monotonic reference for camera and IMU timestamps (v0-plan 2장 Timebase, spec 9장 시간 기준).
 *
 * - Camera: when `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME the capture timestamp already is
 *   `elapsedRealtimeNanos` and the offset is 0 (R1 result on SM-S948N). Otherwise the offset is estimated
 *   from the analyzer callbacks ([ClockOffsetEstimator], 100 frames) and re-measured every minute.
 * - IMU: sensor event timestamps are `elapsedRealtimeNanos` on most devices but not guaranteed, so the
 *   offset is always estimated (25 samples ≈ 5 s at 5 Hz) and re-measured every minute. Until the first
 *   window completes the running minimum is used; the bias is at most the delivery latency.
 */
class Timebase(cameraTsSource: Int?) {
    val cameraSourceName: String = when (cameraTsSource) {
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        null -> "null"
        else -> "other($cameraTsSource)"
    }
    val cameraIsRealtime: Boolean = cameraTsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    private val camera = ClockOffsetEstimator(windowSamples = 100)
    private val imu = ClockOffsetEstimator(windowSamples = 25)

    val cameraOffsetNs: Long get() = if (cameraIsRealtime) 0L else camera.currentOffsetNs
    val imuOffsetNs: Long get() = imu.currentOffsetNs

    /** Feed one analyzer callback; returns the frame's capture time on the monotonic clock. */
    fun cameraToMono(captureTsNs: Long, callbackNs: Long): Long {
        if (cameraIsRealtime) return captureTsNs
        camera.onSample(captureTsNs, callbackNs)
        return captureTsNs + camera.currentOffsetNs
    }

    /** Map a camera timestamp with the current offset without feeding the estimator (capture results). */
    fun cameraToMonoNoSample(captureTsNs: Long): Long = captureTsNs + cameraOffsetNs

    fun imuToMono(eventTsNs: Long, callbackNs: Long): Long {
        imu.onSample(eventTsNs, callbackNs)
        return eventTsNs + imu.currentOffsetNs
    }

    /** `timebase` line for the log. */
    fun record(tMonoMs: Long): TimebaseRecord = TimebaseRecord(
        tMonoMs = tMonoMs,
        cameraTsSource = cameraSourceName,
        cameraToMonoOffsetNs = cameraOffsetNs,
        imuToMonoOffsetNs = imuOffsetNs,
    )
}
