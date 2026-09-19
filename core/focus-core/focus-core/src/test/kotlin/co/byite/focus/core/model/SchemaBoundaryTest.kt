package co.byite.focus.core.model

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Data boundary (spec 9장): every serialised field of a log model is a scalar, an enum, a string or a
 * list of event objects made of the same. No arrays of primitives, no nested blobs, no maps.
 */
class SchemaBoundaryTest {
    private val models: Map<String, SerialDescriptor> = mapOf(
        "SessionHeader" to SessionHeader.serializer().descriptor,
        "SecondRecord" to SecondRecord.serializer().descriptor,
        "IntervalRecord" to IntervalRecord.serializer().descriptor,
        "SessionEnd" to SessionEnd.serializer().descriptor,
        "ParameterSet" to ParameterSet.serializer().descriptor,
    )

    @Test
    fun logModelsCarryOnlyScalarsEnumsStringsAndEventLists() {
        for ((name, desc) in models) assertBoundary(desc, name)
    }

    private fun assertBoundary(desc: SerialDescriptor, path: String) {
        for (i in 0 until desc.elementsCount) {
            val d = desc.getElementDescriptor(i)
            val p = "$path.${desc.getElementName(i)}"
            when (val kind = d.kind) {
                is PrimitiveKind -> Unit
                SerialKind.ENUM -> Unit
                StructureKind.LIST -> {
                    val element = d.getElementDescriptor(0)
                    if (element.kind != StructureKind.CLASS) fail("$p is a list of ${element.kind}; only lists of event objects are allowed")
                    assertBoundary(element, "$p[]")
                }
                else -> fail("$p has kind $kind; only scalars, enums, strings and event lists are allowed")
            }
        }
    }

    @Test
    fun secondRecordFieldsMatchTheV0Subset() {
        val expected = listOf(
            "t_mono_ms", "t_utc_ms", "raw_state", "final_state",
            "face_detect_ratio", "torso_match", "head_landmark_present", "head_below_shoulder",
            "yaw_mean", "pitch_mean", "roll_mean", "zone_id",
            "pose_motion", "scene_luma", "bg_tile_texture_ratio", "jitter_j", "face_width_px",
            "imu_state", "app_state", "fps_actual", "power_state",
        )
        assertEquals(expected, names(SecondRecord.serializer().descriptor))
    }

    @Test
    fun sessionHeaderFieldsMatchSpecChapter9() {
        val expected = listOf(
            "session_id", "participant_id", "spec_version", "algorithm_version", "feature_schema_version",
            "parameter_set_id", "device_model", "os_version", "camera_resolution", "nominal_fps",
            "calibration_id", "calibration_snapshot_version", "task_mode",
        )
        assertEquals(expected, names(SessionHeader.serializer().descriptor))
    }

    @Test
    fun intervalRecordFields() {
        assertEquals(
            listOf("t_start_mono_ms", "t_end_mono_ms", "t_start_utc_ms", "t_end_utc_ms", "state", "reason"),
            names(IntervalRecord.serializer().descriptor),
        )
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
