package co.byite.focus.engine.pipeline

import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer

/**
 * One RGBA_8888 frame as the pipelines see it. Exists only inside `co.byite.focus.engine.pipeline.*`
 * (spec 9장 강제 규칙; DataBoundaryTest). Valid until the analyzer closes the ImageProxy.
 *
 * [pixels] is a direct buffer of exactly width×4×height bytes with no row padding, as MediaPipe's
 * `PacketCreator.createImage` requires. When the camera buffer already has that layout it is used as is;
 * otherwise the analyzer copies rows into a reusable scratch buffer.
 */
class RgbaFrame(
    val pixels: ByteBuffer,
    val width: Int,
    val height: Int,
    /** Clockwise rotation that makes the buffer upright (`ImageInfo.rotationDegrees`). */
    val rotationDegrees: Int,
    /** Capture timestamp on the monotonic clock (ns). */
    val captureMonoNs: Long,
    /** Raw camera `SENSOR_TIMESTAMP` (`ImageInfo.timestamp`): the frame identity (directive E 4장). */
    val rawSensorTs: Long = captureMonoNs,
) {
    /** MediaPipe view over [pixels]; zero-copy. */
    fun toMPImage(): MPImage = ByteBufferImageBuilder(pixels, width, height, MPImage.IMAGE_FORMAT_RGBA).build()

    /** 8-bit luma at pixel (x, y) from the RGBA bytes. */
    fun luma(x: Int, y: Int): Int {
        val i = (y * width + x) * 4
        val r = pixels.get(i).toInt() and 0xFF
        val g = pixels.get(i + 1).toInt() and 0xFF
        val b = pixels.get(i + 2).toInt() and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }
}
