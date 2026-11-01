package org.visualaid.obstacleguide.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.WarningLogic
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 36-class walking and indoor detector, including wall and household obstacles. The file is int8 TFLite, loaded from assets.
 * Boxes are mapped back through the same letterbox used at inference.
 * Input, output, and the letterbox bitmap are reused so a frame does not allocate them again.
 */
class TfliteYoloDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val inputSize: Int
    private val inputFloat: Boolean
    private val channelsFirst: Boolean
    private val outputFloat: Boolean
    private val outputCount: Int
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val letterboxMatrix = Matrix()
    private val letterboxBitmap: Bitmap
    private val letterboxCanvas: Canvas
    private val pixels: IntArray
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val decoded: FloatArray
    private val reds: FloatArray
    private val greens: FloatArray
    private val blues: FloatArray
    private val inferenceLock = Any()

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        interpreter = Interpreter(buffer, options)
        val input = interpreter.getInputTensor(0)
        val shape = input.shape()
        // The shipped file is NCHW [1, 3, 320, 320]. An NHWC file would be [1, 320, 320, 3].
        channelsFirst = shape.size == 4 && shape[1] == 3 && shape[2] > 3
        inputSize = when {
            channelsFirst -> shape[2]
            shape.size >= 3 && shape[1] > 3 -> shape[1]
            else -> 320
        }
        inputFloat = input.dataType() == DataType.FLOAT32
        val output = interpreter.getOutputTensor(0)
        outputFloat = output.dataType() == DataType.FLOAT32
        outputCount = output.shape().fold(1) { acc, dim -> acc * dim }
        val plane = inputSize * inputSize
        letterboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxBitmap)
        pixels = IntArray(plane)
        inputBuffer = ByteBuffer.allocateDirect(if (inputFloat) plane * 3 * 4 else plane * 3)
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(if (outputFloat) outputCount * 4 else outputCount)
            .order(ByteOrder.nativeOrder())
        decoded = FloatArray(outputCount)
        reds = FloatArray(plane)
        greens = FloatArray(plane)
        blues = FloatArray(plane)
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float = WarningLogic.SCORE_THRESHOLD): List<DetectionBox> {
        synchronized(inferenceLock) {
            return detectLocked(bitmap, scoreThreshold)
        }
    }

    private fun detectLocked(bitmap: Bitmap, scoreThreshold: Float): List<DetectionBox> {
        letterbox(bitmap)
        raster()
        outputBuffer.clear()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), mapOf(0 to outputBuffer))
        outputBuffer.rewind()
        if (outputFloat) {
            for (index in 0 until outputCount) {
                decoded[index] = outputBuffer.float
            }
        } else {
            val params = interpreter.getOutputTensor(0).quantizationParams()
            for (index in 0 until outputCount) {
                val unsigned = outputBuffer.get(index).toInt() and 0xFF
                decoded[index] = (unsigned - params.zeroPoint) * params.scale
            }
        }
        return YoloDecode.decode(
            output = decoded,
            shape = interpreter.getOutputTensor(0).shape(),
            inputSize = inputSize,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            scoreThreshold = scoreThreshold,
        )
    }

    private fun letterbox(bitmap: Bitmap) {
        val box = YoloDecode.letterbox(bitmap.width, bitmap.height, inputSize)
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        letterboxMatrix.reset()
        letterboxMatrix.postScale(box.scale, box.scale)
        letterboxMatrix.postTranslate(box.padX, box.padY)
        letterboxCanvas.drawBitmap(bitmap, letterboxMatrix, paint)
    }

    private fun raster() {
        letterboxBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.clear()
        val plane = inputSize * inputSize
        if (channelsFirst && inputFloat) {
            for (index in pixels.indices) {
                val pixel = pixels[index]
                reds[index] = ((pixel shr 16) and 0xFF) / 255f
                greens[index] = ((pixel shr 8) and 0xFF) / 255f
                blues[index] = (pixel and 0xFF) / 255f
            }
            for (index in 0 until plane) inputBuffer.putFloat(reds[index])
            for (index in 0 until plane) inputBuffer.putFloat(greens[index])
            for (index in 0 until plane) inputBuffer.putFloat(blues[index])
        } else {
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (inputFloat) {
                    inputBuffer.putFloat(r / 255f)
                    inputBuffer.putFloat(g / 255f)
                    inputBuffer.putFloat(b / 255f)
                } else {
                    inputBuffer.put(r.toByte())
                    inputBuffer.put(g.toByte())
                    inputBuffer.put(b.toByte())
                }
            }
        }
        inputBuffer.rewind()
    }

    override fun close() {
        if (!letterboxBitmap.isRecycled) letterboxBitmap.recycle()
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "obstacles_v1.tflite"
    }
}
