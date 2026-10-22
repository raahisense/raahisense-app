package org.visualaid.obstacleguide.detect

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.visualaid.obstacleguide.logic.DepthField
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Relative depth from Depth Anything V2 Small, Apache License 2.0.
 * The file is dynamic int8 weights with a float interface, about 28 MB.
 * Input is fixed at 518 by 686, so this runs a few times a second rather than
 * on every detector frame.
 *
 * The checkpoint predicts disparity: a larger raw value is closer. [estimate]
 * negates it, so the returned [DepthField] uses a larger value for farther.
 */
class TfliteDepth(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val inputHeight: Int
    private val inputWidth: Int
    private val channelsFirst: Boolean

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        interpreter = Interpreter(buffer, options)
        val shape = interpreter.getInputTensor(0).shape()
        when {
            shape.size == 4 && shape[1] == 3 -> {
                channelsFirst = true
                inputHeight = shape[2]
                inputWidth = shape[3]
            }
            shape.size == 4 && shape[3] == 3 -> {
                channelsFirst = false
                inputHeight = shape[1]
                inputWidth = shape[2]
            }
            else -> {
                interpreter.close()
                error("Unexpected depth input shape ${shape.contentToString()}")
            }
        }
        if (interpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
            interpreter.close()
            error("Depth model input is not float32")
        }
    }

    fun estimate(bitmap: Bitmap): DepthField {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val input = raster(scaled)
        if (scaled != bitmap) scaled.recycle()
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        val count = shape.fold(1) { acc, dim -> acc * dim }
        val disparity = FloatArray(count)
        val out = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to out))
        out.rewind()
        for (index in disparity.indices) {
            disparity[index] = out.float
        }
        val height = if (shape.size >= 2) shape[shape.size - 2] else inputHeight
        val width = if (shape.size >= 1) shape[shape.size - 1] else inputWidth
        val raw = FloatArray(width * height)
        val usable = minOf(raw.size, disparity.size)
        for (index in 0 until usable) {
            // Negate disparity so a larger value is farther.
            raw[index] = -disparity[index]
        }
        return DepthField(width, height, raw)
    }

    private fun raster(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val plane = inputWidth * inputHeight
        val floats = FloatArray(plane * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            val r = (red - MEAN_R) / STD_R
            val g = (green - MEAN_G) / STD_G
            val b = (blue - MEAN_B) / STD_B
            if (channelsFirst) {
                floats[index] = r
                floats[plane + index] = g
                floats[plane * 2 + index] = b
            } else {
                floats[index * 3] = r
                floats[index * 3 + 1] = g
                floats[index * 3 + 2] = b
            }
        }
        val buffer = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        floats.forEach { buffer.putFloat(it) }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "depth_anything_v2_small.tflite"
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f
    }
}
