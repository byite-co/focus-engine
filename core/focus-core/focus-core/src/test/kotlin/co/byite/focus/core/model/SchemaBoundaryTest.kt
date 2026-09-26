package co.byite.focus.core.model

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Data boundary (spec 9장; CHANGELOG v0.2.1 (5)): every serialised field of a log model is a scalar,
 * an enum, a string or a list of event objects made of the same. `SecondRecord` never carries a
 * numeric array; only `CalibrationSnapshot` may, and only its named fixed-length lists.
 */
class SchemaBoundaryTest {
    private val models: Map<String, SerialDescriptor> = mapOf(
        "SessionHeader" to SessionHeader.serializer().descriptor,
        "SecondRecord" to SecondRecord.serializer().descriptor,
        "IntervalRecord" to IntervalRecord.serializer().descriptor,
        "SessionEnd" to SessionEnd.serializer().descriptor,
        "ParameterSet" to ParameterSet.serializer().descriptor,
        "TimebaseRecord" to TimebaseRecord.serializer().descriptor,
        "CalibrationSnapshot" to CalibrationSnapshot.serializer().descriptor,
        "V0bRawRecord" to V0bRawRecord.serializer().descriptor,
    )

    @Test
    fun logModelsCarryOnlyScalarsEnumsStringsAndEventLists() {
        for ((name, desc) in models) {
            val allowed = if (name == "CalibrationSnapshot") CalibrationSnapshot.FIXED_LENGTH_LISTS.keys else emptySet()
            assertBoundary(desc, name, allowed)
        }
    }

    private fun assertBoundary(desc: SerialDescriptor, path: String, primitiveListsAllowed: Set<String>) {
        for (i in 0 until desc.elementsCount) {
            val d = desc.getElementDescriptor(i)
            val name = desc.getElementName(i)
            val p = "$path.$name"
            when (val kind = d.kind) {
                is PrimitiveKind -> Unit
                SerialKind.ENUM -> Unit
                StructureKind.LIST -> {
                    val element = d.getElementDescriptor(0)
                    when {
                        element.kind == StructureKind.CLASS -> assertBoundary(element, "$p[]", emptySet())
                        element.kind is PrimitiveKind && name in primitiveListsAllowed -> Unit
                        else -> fail("$p is a list of ${element.kind}; only event-object lists (or the named calibration lists) are allowed")
                    }
                }
                else -> fail("$p has kind $kind; only scalars, enums, strings and event lists are allowed")
            }
        }
    }

    @Test
    fun secondRecordHasNoNumericArray() {
        val desc = SecondRecord.serializer().descriptor
        for (i in 0 until desc.elementsCount) {
            val d = desc.getElementDescriptor(i)
            if (d.kind == StructureKind.LIST) assertEquals(StructureKind.CLASS, d.getElementDescriptor(0).kind, "SecondRecord.${desc.getElementName(i)}")
        }
        assertEquals(listOf("events"), (0 until desc.elementsCount).filter { desc.getElementDescriptor(it).kind == StructureKind.LIST }.map { desc.getElementName(it) })
    }

    @Test
    fun secondRecordFieldsMatchSchema023() {
        val expected = listOf(
            "t_mono_ms", "t_utc_ms", "raw_state", "final_state", "invalid_reason", "candidate_state", "candidate_start_mono_ms", "events",
            "face_detect_ratio", "shoulder_visibility_min", "torso_center_offset_ratio", "torso_width_ratio",
            "head_landmark_present", "head_offset_below_shoulder_ratio",
            "yaw_mean", "pitch_mean", "roll_mean", "zone_status", "zone_id",
            "pose_motion", "scene_luma", "bg_tile_texture_ratio", "jitter_j", "face_width_px",
            "imu_state", "screen_state", "app_state",
            // 0.2.1 counters + 0.2.3 additions (CHANGELOG v0.2.3): received / skipped / applied / late-dropped, preset gap threshold
            "frames_requested", "frames_processed", "frames_analyzer_received", "frames_skipped_intentional",
            "frames_sample_applied", "frames_sample_late_dropped", "frames_dropped", "max_frame_gap_ms", "gaps_over_80ms", "gaps_over_threshold", "gaps_over_long_threshold",
            "power_state",
        )
        assertEquals(expected, names(SecondRecord.serializer().descriptor))
        assertEquals("0.2.4", FocusSchema.FEATURE_SCHEMA_VERSION)
        assertEquals("0.2.0", FocusSchema.SPEC_VERSION)
    }

    @Test
    fun v0bRawFieldsMatchSchema023AndAreScalarsOnly() {
        val expected = listOf(
            "t_mono_ms", "segment_label",
            "shoulder_center_x", "shoulder_center_y", "shoulder_width", "pose_samples",
            "tile_texture_min", "tile_texture_median", "scene_samples",
            "face_infer_ms_mean", "face_infer_ms_p95", "face_infer_ms_max", "pose_infer_ms_mean", "pose_infer_ms_max", "frame_latency_ms_mean",
            "stage_wrap_ms_mean", "stage_wrap_ms_p95", "stage_wrap_ms_max",
            "stage_face_post_ms_mean", "stage_face_post_ms_p95", "stage_face_post_ms_max",
            "stage_scene_ms_mean", "stage_scene_ms_p95", "stage_scene_ms_max",
            "stage_enqueue_ms_mean", "stage_enqueue_ms_p95", "stage_enqueue_ms_max",
            "pose_frame_copy_ms_mean", "pose_frame_copy_ms_p95", "pose_frame_copy_ms_max",
            "frame_total_ms_mean", "frame_total_ms_p95", "frame_total_ms_max", "pose_wait_ms_mean", "pose_infer_ms_p95",
            "gap_cause_wrap", "gap_cause_face", "gap_cause_scene", "gap_cause_pose_copy", "gap_cause_enqueue", "gap_cause_other",
            "pose_requested", "pose_completed", "pose_applied", "pose_superseded", "pose_late_dropped", "pose_errors",
            "face_inference_errors", "pre_face_errors",
            "imu_samples", "accel_x_mean", "accel_y_mean", "accel_z_mean", "accel_variance",
            "thermal_status", "battery_pct", "battery_current_ua", "battery_voltage_mv", "is_interactive", "is_device_idle", "hinge_angle_deg",
            // schema 0.2.4 (CHANGELOG v0.2.4): processing slots (independent terminal counters) and capture-result cadence
            "processing_slots_expected", "processing_slots_filled", "processing_slots_missed",
            "capture_interval_ms_median", "capture_interval_ms_p95", "capture_interval_ms_max",
        )
        val desc = V0bRawRecord.serializer().descriptor
        assertEquals(expected, names(desc))
        for (i in 0 until desc.elementsCount) {
            val k = desc.getElementDescriptor(i).kind
            assertTrue(k is PrimitiveKind, "V0bRawRecord.${desc.getElementName(i)} must be a scalar, got $k")
        }
    }

    @Test
    fun faceScheduleValuesOfSchema024() {
        assertEquals(listOf("every_frame", "slot"), names(FaceSchedule.serializer().descriptor))
    }

    @Test
    fun zoneStatusHasTheUncalibratedValueOfSchema022() {
        assertEquals(listOf("in_zone", "outside", "no_head_pose", "uncalibrated"), names(ZoneStatus.serializer().descriptor))
    }

    @Test
    fun eventFields() {
        assertEquals(listOf("type", "t_mono_ms", "by", "zone_id"), names(Event.serializer().descriptor))
    }

    @Test
    fun sessionHeaderFieldsMatchSpecChapter9PlusStartTimesPlusPreset() {
        val expected = listOf(
            "session_id", "participant_id", "t_start_mono_ms", "t_start_utc_ms",
            "spec_version", "algorithm_version", "feature_schema_version",
            "parameter_set_id", "device_model", "os_version", "camera_resolution", "nominal_fps",
            "calibration_id", "calibration_snapshot_version", "task_mode",
            // schema 0.2.3 capture preset (CHANGELOG v0.2.3)
            "capture_preset", "frame_process_divisor", "frame_gap_threshold_ms", "face_delegate", "face_blendshapes", "perf_hint_target_ms",
            "frame_long_gap_threshold_ms", "camera_id", "lens_facing", "hinge_sensor",
            // schema 0.2.4 camera cadence / Face schedule / exact thresholds (CHANGELOG v0.2.4, directive E)
            "camera_fps_request_lower", "camera_fps_request_upper", "camera_fps_ranges_supported", "face_schedule",
            "face_process_period_ns", "frame_gap_threshold_ns", "frame_long_gap_threshold_ns",
        )
        assertEquals(expected, names(SessionHeader.serializer().descriptor))
        assertEquals("16:9", SessionHeader.aspectRatioOf("1280x720"))
        assertEquals("4:3", SessionHeader.aspectRatioOf("640x480"))
        assertEquals(null, SessionHeader.aspectRatioOf("unknown"))
    }

    @Test
    fun intervalSessionEndAndTimebaseFields() {
        assertEquals(listOf("t_start_mono_ms", "t_end_mono_ms", "t_start_utc_ms", "t_end_utc_ms", "state", "reason"), names(IntervalRecord.serializer().descriptor))
        assertEquals(
            listOf("t_mono_ms", "t_utc_ms", "reason", "capture_results_before_start", "capture_results_after_fence", "capture_results_after_close", "stop_integrity_failed"),
            names(SessionEnd.serializer().descriptor),
        )
        assertEquals(listOf("t_mono_ms", "camera_ts_source", "camera_to_mono_offset_ns", "imu_to_mono_offset_ns"), names(TimebaseRecord.serializer().descriptor))
    }

    @Test
    fun calibrationListsAreNamedAndFixedLength() {
        val desc = CalibrationSnapshot.serializer().descriptor
        val lists = (0 until desc.elementsCount).filter { desc.getElementDescriptor(it).kind == StructureKind.LIST }.map { desc.getElementName(it) }
        assertEquals(listOf("zones", "dock_gravity_vector", "bg_tile_texture_baseline", "bg_tile_mask"), lists)
        assertEquals(mapOf("dock_gravity_vector" to 3, "bg_tile_texture_baseline" to 16, "bg_tile_mask" to 16), CalibrationSnapshot.FIXED_LENGTH_LISTS)
        val ok = co.byite.focus.core.Synth.calibration(0)
        assertFailsWith<IllegalArgumentException> { ok.copy(dockGravityVector = listOf(0.0, 9.8)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(bgTileTextureBaseline = List(15) { 1.0 }) }
        assertFailsWith<IllegalArgumentException> { ok.copy(bgTileMask = List(17) { true }) }
        assertFailsWith<IllegalArgumentException> { ok.copy(zones = List(4) { CalibrationZone(it, 0.0, 0.0, 12.0, 12.0) }) }
        assertFailsWith<IllegalArgumentException> { ok.copy(zones = List(2) { CalibrationZone(7, 0.0, 0.0, 12.0, 12.0) }) }
    }

    @Test
    fun allSerialNamesAreSnakeCase() {
        val snake = Regex("^[a-z][a-z0-9_]*$")
        for ((model, desc) in models) {
            for (n in names(desc)) if (!snake.matches(n)) fail("$model.$n is not snake_case")
        }
    }

    private fun names(desc: SerialDescriptor): List<String> = (0 until desc.elementsCount).map { desc.getElementName(it) }
}
