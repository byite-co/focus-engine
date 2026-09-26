package co.byite.focus.engine

import co.byite.focus.core.aggregate.GapThresholds
import co.byite.focus.core.model.FaceSchedule
import kotlin.math.abs
import kotlin.math.roundToInt

/** Face Landmarker delegate of a preset. */
enum class FaceDelegate { CPU, GPU }

/** Preset G target for the Face analysis thread (정정 1). Top level: enum entries cannot read their own companion. */
private const val PERF_HINT_TARGET_MS_G: Int = 35

/** One `CONTROL_AE_TARGET_FPS_RANGE`; `android.util.Range` is avoided so the choice stays JVM-testable. */
data class FpsRange(val lower: Int, val upper: Int) {
    init {
        require(lower > 0 && upper >= lower) { "fps range must be positive and ordered" }
    }

    val isFixed: Boolean get() = lower == upper
    override fun toString(): String = "[$lower,$upper]"
}

/** What a preset asks the camera for (directive E 1장). */
sealed class CameraFpsRequest {
    /** `[24,24]` when offered, else `[30,30]`, else no request (HAL default) — the V0-A/B rule. */
    object Default : CameraFpsRequest()

    /** A fixed range `[fps,fps]`; the preset is unavailable on a camera that does not offer it. */
    data class Fixed(val fps: Int) : CameraFpsRequest()

    /** A variable range with this upper bound and a lower bound below it (Hvar); unavailable without one. */
    data class VariableUpper(val upperFps: Int) : CameraFpsRequest()

    /** The range to request from [supported], or null when the request cannot be met (Default: no request at all). */
    fun select(supported: List<FpsRange>): FpsRange? = when (this) {
        Default -> supported.firstOrNull { it.lower == 24 && it.upper == 24 } ?: supported.firstOrNull { it.lower == 30 && it.upper == 30 }
        is Fixed -> supported.firstOrNull { it.lower == fps && it.upper == fps }
        // the narrowest variable range under the bound (highest lower bound): closest to fixed 15, least cadence swing (E2 1장)
        is VariableUpper -> supported.filter { it.upper == upperFps && it.lower < upperFps }.maxByOrNull { it.lower }
    }

    /** Default is always available (it falls back to the HAL default); the others need their range. */
    fun isAvailable(supported: List<FpsRange>): Boolean = this == Default || select(supported) != null

    val label: String
        get() = when (this) {
            Default -> "[24,24] 또는 [30,30]"
            is Fixed -> "[$fps,$fps]"
            is VariableUpper -> "[<$upperFps,$upperFps] 가변"
        }
}

/**
 * Capture presets of directives D and E (R4 performance experiments). A is the V0-A/B baseline; every other preset
 * changes as little as possible against A. The header records the preset and the resolution CameraX actually chose.
 *
 * | id | change vs A |
 * |---|---|
 * | A | 1280×720 (16:9), Face CPU, blendshapes on, Face on every frame, camera [24,24] |
 * | B | blendshapes off |
 * | C | 640×360 (16:9); nearest 16:9 size when the device lacks it, else 640×480 reported as C2 |
 * | D | Face on the GPU delegate |
 * | E | camera 24 fps, Face 12 Hz processing slots (3장 rule, period 83.3 ms) |
 * | E15 | camera 24 fps, Face 15 Hz processing slots (period 66.7 ms) |
 * | G | PerformanceHintManager session on the Face analysis thread only, target 35 ms |
 * | H12 | camera fixed [12,12], Face on every frame (the only variable is the camera cadence) |
 * | H15 | camera fixed [15,15], Face on every frame |
 * | Hvar | camera variable range with upper bound 15, Face on every frame; no pass verdict, distributions only |
 *
 * Pairs for the camera-cadence question: H12 ↔ E (Face 12 Hz both), H15 ↔ E15 (Face 15 Hz both). H15 is never compared
 * with E directly. F (ROI crop) is optional and not implemented (README "프리셋 F").
 */
enum class CapturePreset(
    val id: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val faceDelegate: FaceDelegate,
    val faceBlendshapes: Boolean,
    /** Legacy header field: 2 for E (every other frame), 1 otherwise. Schema 0.2.4 readers use the period instead. */
    val frameProcessDivisor: Int,
    /** PerformanceHintManager target for the analysis thread; null = no hint session. */
    val perfHintTargetMs: Int?,
    val cameraFps: CameraFpsRequest,
    /** Face processing rate of the slot rule (Hz); null = Face on every capture result. */
    val faceRateHz: Int?,
) {
    A("A", 1280, 720, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.Default, null),
    B("B", 1280, 720, FaceDelegate.CPU, false, 1, null, CameraFpsRequest.Default, null),
    C("C", 640, 360, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.Default, null),
    D("D", 1280, 720, FaceDelegate.GPU, true, 1, null, CameraFpsRequest.Default, null),
    E("E", 1280, 720, FaceDelegate.CPU, true, 2, null, CameraFpsRequest.Default, 12),
    E15("E15", 1280, 720, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.Default, 15),
    G("G", 1280, 720, FaceDelegate.CPU, true, 1, PERF_HINT_TARGET_MS_G, CameraFpsRequest.Default, null),
    H12("H12", 1280, 720, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.Fixed(12), null),
    H15("H15", 1280, 720, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.Fixed(15), null),
    Hvar("Hvar", 1280, 720, FaceDelegate.CPU, true, 1, null, CameraFpsRequest.VariableUpper(15), null);

    val faceSchedule: FaceSchedule get() = if (faceRateHz == null) FaceSchedule.EVERY_FRAME else FaceSchedule.SLOT

    /** True for a preset whose only variable is the camera cadence. */
    val isCameraCadencePreset: Boolean get() = cameraFps != CameraFpsRequest.Default

    /** Expected interval between Face-processed frames (ns): the slot period, or the camera frame interval at [nominalFps]. */
    fun faceProcessPeriodNs(nominalFps: Int): Long = faceRateHz?.let { GapThresholds.periodNs(it.toDouble()) } ?: GapThresholds.frameIntervalNs(nominalFps)

    /** Slot period for the [co.byite.focus.core.aggregate.FrameScheduler]; null = every capture result is a slot. */
    fun slotPeriodNs(): Long? = faceRateHz?.let { GapThresholds.periodNs(it.toDouble()) }

    /** `gaps_over_threshold` threshold: expected interval × 1.5 ([GapThresholds]); 62.5 ms at 24 fps, 100 ms at 15 Hz, 125 ms at 12 Hz. */
    fun gapThresholdNs(nominalFps: Int): Long = GapThresholds.gapThresholdNs(faceProcessPeriodNs(nominalFps))

    /** `gaps_over_long_threshold` threshold: expected interval × 4.5; 187.5 / 300 / 375 ms. */
    fun longGapThresholdNs(nominalFps: Int): Long = GapThresholds.longGapThresholdNs(faceProcessPeriodNs(nominalFps))

    /** Rounded ms of [gapThresholdNs] for the legacy header field (`frame_gap_threshold_ms`). */
    fun gapThresholdMs(nominalFps: Int): Int = (gapThresholdNs(nominalFps) / 1e6).roundToInt()

    /** Rounded ms of [longGapThresholdNs] for the legacy header field. */
    fun longGapThresholdMs(nominalFps: Int): Int = (longGapThresholdNs(nominalFps) / 1e6).roundToInt()

    /** The preset can run on a camera offering [supported] AE ranges (H12 / H15 / Hvar need theirs; E15 is always offered). */
    fun isAvailable(supported: List<FpsRange>): Boolean = cameraFps.isAvailable(supported)

    /** Expected interval, thresholds and slot tolerance for the [request] the camera accepted (null = fps unset) among [supported]. */
    fun cadencePlan(request: FpsRange?, supported: List<FpsRange>): CadencePlan {
        val nominal = request?.upper
        val period: Long? = if (nominal != null) faceProcessPeriodNs(nominal) else slotPeriodNs()
        val fastest = supported.maxOfOrNull { it.upper } ?: CadencePlan.FALLBACK_TOLERANCE_FPS
        return CadencePlan(
            nominalFps = nominal,
            faceProcessPeriodNs = period,
            gapThresholdNs = period?.let { GapThresholds.gapThresholdNs(it) },
            longGapThresholdNs = period?.let { GapThresholds.longGapThresholdNs(it) },
            slotToleranceIntervalNs = GapThresholds.frameIntervalNs(nominal ?: fastest),
        )
    }

    companion object {
        const val PERF_HINT_TARGET_MS: Int = PERF_HINT_TARGET_MS_G

        val DEFAULT: CapturePreset = A

        fun byId(id: String?): CapturePreset = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Presets the dev app lists for a camera offering [supported]: every preset except the H variants the camera cannot run. */
        fun availableOn(supported: List<FpsRange>): List<CapturePreset> = entries.filter { it.isAvailable(supported) }
    }
}

/**
 * What the pipeline derives from the AE range it selected (E2 1장), pure so it is JVM-tested. [nominalFps] is the requested
 * range's upper bound, null for **fps unset** (a preset with the default request on a camera offering neither [24,24] nor
 * [30,30]): then nothing is assumed about the cadence — an every-frame preset has no expected interval and no thresholds
 * (the aggregator learns a diagnostic interval from the warm-up), a slot preset keeps its slot period, and the session is
 * not comparable. [slotToleranceIntervalNs] is the camera frame interval the slot rule's half-frame tolerance is taken from:
 * at the nominal cadence, or for fps unset at the fastest cadence the camera offers — a bound (the rule never selects a
 * frame earlier than it would at any real cadence), not an assumed cadence.
 */
data class CadencePlan(
    val nominalFps: Int?,
    val faceProcessPeriodNs: Long?,
    val gapThresholdNs: Long?,
    val longGapThresholdNs: Long?,
    val slotToleranceIntervalNs: Long,
) {
    val fpsUnset: Boolean get() = nominalFps == null

    /** Rounded ms of the thresholds for the legacy header fields; null for fps unset. */
    val gapThresholdMs: Int? get() = gapThresholdNs?.let { (it / 1e6).roundToInt() }
    val longGapThresholdMs: Int? get() = longGapThresholdNs?.let { (it / 1e6).roundToInt() }

    /** Header `nominal_fps`: the requested upper bound, 0 for fps unset (the header field is not nullable). */
    val nominalFpsForHeader: Int get() = nominalFps ?: NOMINAL_FPS_UNSET

    companion object {
        const val NOMINAL_FPS_UNSET: Int = 0
        /** Slot tolerance basis when the camera reports no AE range at all: the fastest cadence a phone camera runs at. */
        const val FALLBACK_TOLERANCE_FPS: Int = 30
    }
}

/** A camera output size; `android.util.Size` is avoided so the choice stays JVM-testable. */
data class FrameSize(val width: Int, val height: Int) {
    val area: Int get() = width * height
    val is16x9: Boolean get() = width * 9 == height * 16
    val is4x3: Boolean get() = width * 3 == height * 4
    override fun toString(): String = "${width}x$height"
}

/** What to request from CameraX for a preset, and the id the summary shows (C2 when C fell back to 640×480). */
data class ResolutionChoice(val size: FrameSize, val presetId: String, val reason: String)

/** Resolution choice of preset C (정정 1 2번); every other preset requests its nominal size. */
object PresetResolution {
    val C_TARGET = FrameSize(640, 360)
    val C2_FALLBACK = FrameSize(640, 480)

    /**
     * [supported]: the camera's output sizes for the analysis stream. C → 640×360 when offered, else the 16:9 size
     * whose area is closest to 640×360, else 640×480 as "C2". Other presets → their requested size as is.
     */
    fun choose(preset: CapturePreset, supported: List<FrameSize>): ResolutionChoice {
        if (preset != CapturePreset.C) return ResolutionChoice(FrameSize(preset.requestedWidth, preset.requestedHeight), preset.id, "preset size")
        if (C_TARGET in supported) return ResolutionChoice(C_TARGET, "C", "640x360 supported")
        val nearest = supported.filter { it.is16x9 }.minByOrNull { abs(it.area - C_TARGET.area) }
        if (nearest != null) return ResolutionChoice(nearest, "C", "640x360 not supported; nearest 16:9 size")
        return ResolutionChoice(C2_FALLBACK, "C2", "no 16:9 size; 640x480")
    }
}
