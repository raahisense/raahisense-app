package org.visualaid.obstacleguide.detect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * CameraX frames arrive in sensor orientation. This converts them to an upright
 * bitmap so "left" matches the walker's left when the phone is held upright.
 */
internal fun ImageProxy.toUprightBitmap(): Bitmap {
    val nv21 = toNv21Capped(maxPixels = 640 * 480)
    val yuv = YuvImage(nv21.bytes, ImageFormat.NV21, nv21.width, nv21.height, null)
    val jpeg = ByteArrayOutputStream()
    if (!yuv.compressToJpeg(Rect(0, 0, nv21.width, nv21.height), 85, jpeg)) {
        error("Could not encode the camera frame")
    }
    val bytes = jpeg.toByteArray()
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: error("Could not decode the camera frame")
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright != decoded) decoded.recycle()
    return upright
}

private class Nv21(val bytes: ByteArray, val width: Int, val height: Int)

private fun ImageProxy.toNv21Capped(maxPixels: Int): Nv21 {
    val srcW = width
    val srcH = height
    var sample = 1
    while ((srcW / (sample + 1)) * (srcH / (sample + 1)) > maxPixels && sample < 8) {
        sample++
    }
    var outW = srcW / sample
    var outH = srcH / sample
    if (outW % 2 != 0) outW--
    if (outH % 2 != 0) outH--
    require(outW >= 2 && outH >= 2)

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuf = yPlane.buffer.duplicate().apply { rewind() }
    val uBuf = uPlane.buffer.duplicate().apply { rewind() }
    val vBuf = vPlane.buffer.duplicate().apply { rewind() }
    val nv21 = ByteArray(outW * outH + outW * outH / 2)
    var pos = 0
    for (row in 0 until outH) {
        val srcY = row * sample
        for (col in 0 until outW) {
            val srcX = col * sample
            nv21[pos++] = yBuf.byteAt(srcY * yPlane.rowStride + srcX * yPlane.pixelStride)
        }
    }
    for (row in 0 until outH / 2) {
        val srcChromaRow = row * sample
        for (col in 0 until outW / 2) {
            val srcChromaCol = col * sample
            val uvIndex = srcChromaRow * uPlane.rowStride + srcChromaCol * uPlane.pixelStride
            nv21[pos++] = vBuf.byteAt(uvIndex)
            nv21[pos++] = uBuf.byteAt(uvIndex)
        }
    }
    return Nv21(nv21, outW, outH)
}

/** Mean luma of the camera Y plane, 0 to 1. Rotation does not change the mean. */
internal fun ImageProxy.meanLumaY(): Float {
    val plane = planes.firstOrNull() ?: return Float.NaN
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    if (rowStride <= 0 || width <= 0 || height <= 0) return Float.NaN
    val step = 8
    var sum = 0L
    var count = 0
    var row = 0
    while (row < height) {
        var col = 0
        val rowStart = row * rowStride
        while (col < width) {
            val index = rowStart + col * pixelStride
            if (index in 0 until buffer.limit()) {
                sum += buffer.get(index).toInt() and 0xFF
                count++
            }
            col += step
        }
        row += step
    }
    return if (count == 0) Float.NaN else sum.toFloat() / (count * 255f)
}

/** Mean luma of an upright bitmap, 0 to 1. Used when the frame is already a bitmap. */
internal fun Bitmap.meanLuma(): Float {
    if (width <= 0 || height <= 0) return Float.NaN
    val step = 8
    var sum = 0L
    var count = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            sum += (red * 299 + green * 587 + blue * 114) / 1000
            count++
            x += step
        }
        y += step
    }
    return if (count == 0) Float.NaN else sum.toFloat() / (count * 255f)
}

/**
 * ISO and exposure time when this CameraX build attaches a capture result.
 * CameraX 1.3 does not publish them on ImageInfo, so this often returns nulls.
 */
internal fun ImageProxy.captureExposure(): Pair<Int?, Long?> {
    return try {
        val info = imageInfo
        val method = info.javaClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 0 &&
                (candidate.name == "getCaptureResult" || candidate.name == "captureResult")
        } ?: return null to null
        method.isAccessible = true
        val result = method.invoke(info) as? android.hardware.camera2.CaptureResult ?: return null to null
        val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val exposure = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
        iso to exposure
    } catch (_: Exception) {
        null to null
    }
}

private fun ByteBuffer.byteAt(index: Int): Byte {
    val size = limit()
    if (index < 0 || index >= size) {
        throw IndexOutOfBoundsException("Camera plane index $index is outside $size")
    }
    return get(index)
}
