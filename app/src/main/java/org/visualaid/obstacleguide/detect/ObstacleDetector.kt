package org.visualaid.obstacleguide.detect

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.WarningLogic
import java.io.Closeable

/**
 * EfficientDet-Lite0, quantized, with COCO labels stored inside the model file.
 * Inference stays on the device. The file is loaded from assets, not the network.
 */
class ObstacleDetector(context: Context) : Closeable {
    private val detector: ObjectDetector

    init {
        val base = BaseOptions.builder().setNumThreads(2).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setScoreThreshold(WarningLogic.SCORE_THRESHOLD)
            .setMaxResults(10)
            .build()
        detector = ObjectDetector.createFromFileAndOptions(
            context.applicationContext,
            MODEL_ASSET,
            options,
        )
    }

    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val image = TensorImage.fromBitmap(bitmap)
        val results = detector.detect(image)
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)
        return results.map { detection ->
            val category = detection.categories.maxByOrNull { it.score }
            val rect = detection.boundingBox
            DetectionBox(
                label = category?.label.orEmpty(),
                score = category?.score ?: 0f,
                left = rect.left / width,
                top = rect.top / height,
                right = rect.right / width,
                bottom = rect.bottom / height,
            )
        }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
    }
}
