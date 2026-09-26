package co.byite.focus.core.log

import co.byite.focus.core.report.ComparisonState
import co.byite.focus.core.report.StopDiagnostics
import co.byite.focus.core.report.V0bReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schema 0.2.3 is additive: a 0.2.2 session (no preset fields, no new counters) decodes with the documented defaults. */
class SchemaCompatTest {
    private val text = """
        {"type":"header","session_id":"S1","participant_id":"dev","t_start_mono_ms":4000000,"t_start_utc_ms":1789000000000,"spec_version":"0.2.0","algorithm_version":"abc123","feature_schema_version":"0.2.2","parameter_set_id":"ps-v0.2.1-default","device_model":"SM-F966N","os_version":"16","camera_resolution":"1280x720","nominal_fps":24,"calibration_id":"none","calibration_snapshot_version":"none","task_mode":"VISUAL"}
        {"type":"timebase","t_mono_ms":4000000,"camera_ts_source":"REALTIME","camera_to_mono_offset_ns":0,"imu_to_mono_offset_ns":0}
        {"type":"second","t_mono_ms":4000000,"t_utc_ms":1789000000000,"raw_state":null,"final_state":null,"invalid_reason":null,"candidate_state":null,"candidate_start_mono_ms":null,"events":[],"face_detect_ratio":0.98,"shoulder_visibility_min":0.93,"torso_center_offset_ratio":null,"torso_width_ratio":null,"head_landmark_present":true,"head_offset_below_shoulder_ratio":-0.8,"yaw_mean":1.2,"pitch_mean":-7.5,"roll_mean":0.3,"zone_status":"uncalibrated","zone_id":null,"pose_motion":0.05,"scene_luma":118.0,"bg_tile_texture_ratio":null,"jitter_j":0.004,"face_width_px":210.0,"imu_state":"UNKNOWN","screen_state":"OFF","app_state":"BACKGROUND","frames_requested":24,"frames_processed":22,"frames_dropped":2,"max_frame_gap_ms":90,"gaps_over_80ms":1,"power_state":"P0"}
        {"type":"v0b_raw","t_mono_ms":4000000,"segment_label":"정면","shoulder_center_x":0.5,"shoulder_center_y":0.6,"shoulder_width":0.4,"pose_samples":1,"tile_texture_min":3.0,"tile_texture_median":9.0,"scene_samples":1,"face_infer_ms_mean":31.0,"face_infer_ms_p95":35.0,"face_infer_ms_max":40.0,"pose_infer_ms_mean":42.0,"pose_infer_ms_max":42.0,"frame_latency_ms_mean":30.0,"imu_samples":5,"accel_x_mean":0.1,"accel_y_mean":7.2,"accel_z_mean":6.6,"accel_variance":0.002,"thermal_status":1,"battery_pct":80,"battery_current_ua":-400000,"battery_voltage_mv":4000,"is_interactive":false,"is_device_idle":false}
        {"type":"session_end","t_mono_ms":4001000,"t_utc_ms":1789000001000,"reason":"USER"}
    """.trimIndent()

    @Test
    fun aSchema022LogDecodesWithTheDefaults() {
        val log = JsonlCodec.decode(text)
        val h = log.header
        assertNull(h.capturePreset)
        assertEquals(1, h.frameProcessDivisor)
        assertEquals(80, h.frameGapThresholdMs)
        assertNull(h.faceDelegate)
        assertNull(h.faceBlendshapes)
        assertNull(h.perfHintTargetMs)
        assertEquals("16:9", h.cameraAspectRatio)
        val s = log.records.single()
        assertEquals(22, s.framesAnalyzerReceived, "received defaults to processed")
        assertEquals(0, s.framesSkippedIntentional)
        assertEquals(22, s.framesSampleApplied)
        assertEquals(0, s.framesSampleLateDropped)
        assertEquals(2, s.backpressureDrops, "every old drop is a backpressure drop")
        assertEquals(0, s.framesUnprocessedUnexpected)
        assertEquals(0, s.framesPostFaceFailed)
        assertEquals(1, s.gapsOverThreshold, "defaults to gaps_over_80ms")
        val r = log.v0bRaw.single()
        assertEquals(1, r.poseCompleted, "pose_completed defaults to pose_samples")
        assertEquals(1, r.poseApplied)
        assertEquals(0, r.poseRequested)
        assertEquals(0, r.poseSuperseded)
        assertEquals(0, r.poseLateDropped)
        assertEquals(0, r.poseErrors)
        assertEquals(0, r.faceInferenceErrors)
        assertEquals(0, r.preFaceErrors)
        assertNull(r.frameTotalMsMean)
        assertNull(r.poseFrameCopyMsMean)
        // schema 0.2.4 additions default to "unknown" / 0
        assertEquals(0, r.processingSlotsExpected)
        assertEquals(0, r.processingSlotsFilled)
        assertEquals(0, r.processingSlotsMissed)
        assertNull(r.captureIntervalMsMedian)
        assertNull(h.cameraFpsRequestLower)
        assertNull(h.cameraFpsRequestFixed)
        assertEquals("unset", h.cameraFpsRequestLabel)
        assertNull(h.faceSchedule)
        assertNull(h.faceProcessPeriodNs)
        assertNull(h.frameGapThresholdNs)
        assertNull(log.sessionEnd!!.stopIntegrityFailed)
        assertNull(log.sessionEnd!!.captureResultsAfterClose)
        assertNull(log.sessionEnd!!.captureResultsOutOfOrder)
        assertNull(log.sessionEnd!!.captureResultDrainComplete)
        assertNull(log.sessionEnd!!.aggregationQueueDrained)
        assertFalse(h.cameraFpsRequestRecorded)
        assertFalse(h.cameraFpsUnset, "an older log does not know its request: 'unset', not 'fps unset(가변)'")
        val summary = V0bReport.build(log)
        assertEquals(2L, summary.overall.framesDropped)
        assertEquals("1280x720 (16:9) @ 24fps", summary.overall.camera)
        // an older log keeps its rounded ms thresholds as the counted values, but a request that is not recorded is not judged (E2 1장: only a fixed request is)
        assertEquals(80.0, summary.overall.thresholds.gapMs)
        assertEquals("80ms", summary.overall.thresholds.gapCountedLabel)
        assertEquals("n/a", summary.overall.thresholds.gapLabel)
        assertFalse(summary.overall.thresholds.applicable)
        assertFalse(summary.pass.judged)
        assertEquals(V0bReport.REASON_FPS_NOT_RECORDED, summary.pass.notJudgedReason)
        assertNull(summary.overall.cadence.mismatch)
        assertEquals(0L, summary.overall.slots.expected)
        assertEquals(StopDiagnostics.UNKNOWN, summary.overall.diagnostics)
        assertEquals(ComparisonState.NOT_COMPARABLE, summary.comparability.state)
        assertTrue(V0bReport.REASON_FPS_NOT_RECORDED in summary.comparability.reasons, summary.comparability.toString())
    }

    @Test
    fun reEncodingWritesTheSchema023FieldsExplicitly() {
        val log = JsonlCodec.decode(text)
        val line = JsonlCodec.encodeSecond(log.records.single())
        for (k in listOf("frames_analyzer_received", "frames_skipped_intentional", "frames_sample_applied", "frames_sample_late_dropped", "gaps_over_threshold")) {
            kotlin.test.assertTrue("\"$k\":" in line, "$k missing from $line")
        }
        val header = JsonlCodec.encodeHeader(log.header)
        kotlin.test.assertTrue("\"capture_preset\":null" in header && "\"frame_gap_threshold_ms\":80" in header, header)
        assertEquals(log, JsonlCodec.decode(JsonlCodec.encode(log)))
    }
}
