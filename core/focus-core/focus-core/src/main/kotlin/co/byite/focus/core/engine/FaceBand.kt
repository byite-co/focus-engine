package co.byite.focus.core.engine

import co.byite.focus.core.model.ParameterSet

/**
 * Per-bucket face detection band (v0.2.1 판정 12).
 *
 * - MISSING: `face_detect_ratio < face_missing_max_ratio` → G1 "얼굴 미검출" bucket.
 * - UNSTABLE: between the two thresholds → INVALID(face_unstable), zone_status = no_head_pose.
 * - PRESENT: `face_detect_ratio >= face_present_min_ratio` → head pose usable for G2.
 */
enum class FaceBand {
    MISSING, UNSTABLE, PRESENT;

    companion object {
        fun of(faceDetectRatio: Double, params: ParameterSet): FaceBand = when {
            faceDetectRatio < params.faceMissingMaxRatio -> MISSING
            faceDetectRatio < params.facePresentMinRatio -> UNSTABLE
            else -> PRESENT
        }
    }
}
