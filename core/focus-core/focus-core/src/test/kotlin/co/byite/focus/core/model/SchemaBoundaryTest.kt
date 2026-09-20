package co.byite.focus.core.model

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun secondRecordFieldsMatchSchema021() {
        val expected = listOf(
            "t_mono_ms", "t_utc_ms", "raw_state", "final_state", "invalid_reason", "candidate_state", "candidate_start_mono_ms", "events",
            "face_detect_ratio", "shoulder_visibility_min", "torso_center_offset_ratio", "torso_width_ratio",
            "head_landmark_present", "head_offset_below_shoulder_ratio",
            "yaw_mean", "pitch_mean", "roll_mean", "zone_status", "zone_id",
            "pose_motion", "scene_luma", "bg_tile_texture_ratio", "jitter_j", "face_width_px",
            "imu_state", "screen_state", "app_state",
            "frames_requested", "frames_processed", "frames_dropped", "max_frame_gap_ms", "gaps_over_80ms",
            "power_state",
        )
        assertEquals(expected, names(SecondRecord.serializer().descriptor))
        assertEquals("0.2.2", FocusSchema.FEATURE_SCHEMA_VERSION)
        assertEquals("0.2.0", FocusSchema.SPEC_VERSION)
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
    fun sessionHeaderFieldsMatchSpecChapter9PlusStartTimes() {
        val expected = listOf(
            "session_id", "participant_id", "t_start_mono_ms", "t_start_utc_ms",
            "spec_version", "algorithm_version", "feature_schema_version",
            "parameter_set_id", "device_model", "os_version", "camera_resolution", "nominal_fps",
            "calibration_id", "calibration_snapshot_version", "task_mode",
        )
        assertEquals(expected, names(SessionHeader.serializer().descriptor))
    }

    @Test
    fun intervalSessionEndAndTimebaseFields() {
        assertEquals(listOf("t_start_mono_ms", "t_end_mono_ms", "t_start_utc_ms", "t_end_utc_ms", "state", "reason"), names(IntervalRecord.serializer().descriptor))
        assertEquals(listOf("t_mono_ms", "t_utc_ms", "reason"), names(SessionEnd.serializer().descriptor))
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
