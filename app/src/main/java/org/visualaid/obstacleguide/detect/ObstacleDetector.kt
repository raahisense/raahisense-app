package org.visualaid.obstacleguide.detect

import android.content.Context
import android.graphics.Bitmap
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.WarningLogic
import java.io.Closeable

/**
 * One on-device model for the street and for the home.
 * The file is the int8 walking-and-indoor detector in assets.
 */
class ObstacleDetector(context: Context) : Closeable {
    private val detector = TfliteYoloDetector(context)

    fun detect(bitmap: Bitmap, scoreThreshold: Float = WarningLogic.SCORE_THRESHOLD): List<DetectionBox> =
        detector.detect(bitmap, scoreThreshold)

    override fun close() {
        detector.close()
    }
}
