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
 * 29-class walking and indoor detector. The file is int8 TFLite, loaded from assets.
 * Boxes are mapped back through the same letterbox used at inference.
 */
class TfliteYoloDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val inputSize: Int
    private val inputFloat: Boolean
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        interpreter = Interpreter(buffer, options)
        val input = interpreter.getInputTensor(0)
        val shape = input.shape()
        inputSize = if (shape.size >= 3) shape[1] else 320
        inputFloat = input.dataType() == DataType.FLOAT32
    }

    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val fitted = letterbox(bitmap)
        val input = raster(fitted)
        fitted.recycle()
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        val count = shape.fold(1) { acc, dim -> acc * dim }
        val decoded = when (outputTensor.dataType()) {
            DataType.FLOAT32 -> {
                val out = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                // Kotlin's run {} would hide Interpreter.run.
                interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to out))
                out.rewind()
                FloatArray(count) { out.float }
            }
            else -> {
                val raw = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
                interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to raw))
                raw.rewind()
                val params = outputTensor.quantizationParams()
                FloatArray(count) { index ->
                    val unsigned = raw.get(index).toInt() and 0xFF
                    (unsigned - params.zeroPoint) * params.scale
                }
            }
        }
        return YoloDecode.decode(
            output = decoded,
            shape = shape,
            inputSize = inputSize,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            scoreThreshold = WarningLogic.SCORE_THRESHOLD,
        )
    }

    private fun letterbox(bitmap: Bitmap): Bitmap {
        val box = YoloDecode.letterbox(bitmap.width, bitmap.height, inputSize)
        val canvasBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val matrix = Matrix()
        matrix.postScale(box.scale, box.scale)
        matrix.postTranslate(box.padX, box.padY)
        canvas.drawBitmap(bitmap, matrix, paint)
        return canvasBitmap
    }

    private fun raster(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val buffer = if (inputFloat) {
            ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        } else {
            ByteBuffer.allocateDirect(inputSize * inputSize * 3)
        }
        buffer.order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (inputFloat) {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            } else {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            }
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "obstacles_v1.tflite"
    }
}
