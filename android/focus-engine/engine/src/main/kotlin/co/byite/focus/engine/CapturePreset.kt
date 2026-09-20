package co.byite.focus.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** Face Landmarker delegate of a preset. */
enum class FaceDelegate { CPU, GPU }

/** Preset G target for the Face analysis thread (정정 1). Top level: enum entries cannot read their own companion. */
private const val PERF_HINT_TARGET_MS_G: Int = 35

/**
 * Capture presets of directive D (R4 performance experiments). A is the V0-A/B baseline; every other preset
 * changes exactly one thing. The header records the preset and the resolution CameraX actually chose.
 *
 * | id | change vs A |
 * |---|---|
 * | A | 1280×720 (16:9), Face CPU, blendshapes on, Face on every frame |
 * | B | blendshapes off |
 * | C | 640×360 (16:9); nearest 16:9 size when the device lacks it, else 640×480 reported as C2 |
 * | D | Face on the GPU delegate |
 * | E | Face on every other frame (12 fps at 24 fps) |
 * | G | PerformanceHintManager session on the Face analysis thread only, target 35 ms |
 *
 * F (ROI crop) is optional and not implemented (README "프리셋 F").
 */
enum class CapturePreset(
    val id: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val faceDelegate: FaceDelegate,
    val faceBlendshapes: Boolean,
    /** Face runs on every n-th received frame. */
    val frameProcessDivisor: Int,
    /** PerformanceHintManager target for the analysis thread; null = no hint session. */
    val perfHintTargetMs: Int?,
) {
    A("A", 1280, 720, FaceDelegate.CPU, true, 1, null),
    B("B", 1280, 720, FaceDelegate.CPU, false, 1, null),
    C("C", 640, 360, FaceDelegate.CPU, true, 1, null),
    D("D", 1280, 720, FaceDelegate.GPU, true, 1, null),
    E("E", 1280, 720, FaceDelegate.CPU, true, 2, null),
    G("G", 1280, 720, FaceDelegate.CPU, true, 1, PERF_HINT_TARGET_MS_G);

    /**
     * `gaps_over_threshold` threshold for this preset at [nominalFps] (정정 1): 80 ms when Face runs on every frame,
     * otherwise twice the expected interval between processed frames (E at 24 fps: 2 × 83.3 ms → 167 ms).
     */
    fun gapThresholdMs(nominalFps: Int): Int =
        if (frameProcessDivisor == 1) EVERY_FRAME_GAP_THRESHOLD_MS else (2.0 * frameProcessDivisor * 1000.0 / nominalFps).roundToInt()

    companion object {
        const val PERF_HINT_TARGET_MS: Int = PERF_HINT_TARGET_MS_G
        const val EVERY_FRAME_GAP_THRESHOLD_MS: Int = 80

        val DEFAULT: CapturePreset = A

        fun byId(id: String?): CapturePreset = entries.firstOrNull { it.id == id } ?: DEFAULT
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

/**
 * Every-n-th-frame rule of preset E (정정 1: "격프레임"), decided by capture timestamps so that a frame the
 * camera dropped does not shift the phase: a received frame is skipped when it follows the last processed
 * frame by less than `(divisor − 0.5) × frame period`. Every skipped frame is `frames_skipped_intentional`.
 */
class FrameSkipRule(divisor: Int, nominalFps: Int) {
    init {
        require(divisor >= 1 && nominalFps > 0) { "divisor >= 1 and fps > 0" }
    }

    val minIntervalNs: Long = if (divisor == 1) 0L else ((divisor - 0.5) * 1_000_000_000.0 / nominalFps).toLong()
    private var lastProcessedNs = Long.MIN_VALUE

    fun shouldSkip(captureNs: Long): Boolean = minIntervalNs > 0L && lastProcessedNs != Long.MIN_VALUE && captureNs - lastProcessedNs < minIntervalNs

    fun onProcessed(captureNs: Long) {
        lastProcessedNs = captureNs
    }
}
