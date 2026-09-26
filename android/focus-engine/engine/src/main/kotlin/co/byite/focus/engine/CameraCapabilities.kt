package co.byite.focus.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/** Camera facts the dev app needs before a session starts (directive E 1장: list only the H variants the camera can run). */
object CameraCapabilities {
    /** AE target fps ranges of the first front camera, or null when there is none / the query fails. Characteristics need no permission. */
    fun frontCameraFpsRanges(context: Context): List<FpsRange>? = try {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT } ?: return null
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { FpsRange(it.lower, it.upper) } ?: emptyList()
    } catch (e: Exception) {
        null
    }
}
