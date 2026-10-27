package org.visualaid.obstacleguide.detect

import org.visualaid.obstacleguide.logic.DetectionBox
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a small YOLO head into normalized boxes.
 * No TensorFlow types live here, so unit tests can feed a plain array.
 *
 * Class order matches training/class_map.py.
 * Walking classes come first. Indoor classes, then door, wall, shoe, bag,
 * toy, bin, appliance, and cabinet, are appended.
 * speed_bump and low_branch are not included.
 */
object YoloDecode {
    val CLASS_NAMES: List<String> = listOf(
        "person",
        "bicycle",
        "motorcycle",
        "car",
        "bus",
        "truck",
        "cow",
        "dog",
        "auto_rickshaw",
        "cycle_rickshaw",
        "handcart",
        "pothole",
        "manhole_open_drain",
        "curb",
        "stairs",
        "pole_bollard",
        "barrier",
        "street_furniture",
        "tree",
        "cat",
        "chair",
        "couch",
        "dining table",
        "bed",
        "toilet",
        "potted plant",
        "refrigerator",
        "tv",
        "door",
        "wall",
        "shoe",
        "bag",
        "toy",
        "bin",
        "appliance",
        "cabinet",
    )

    data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    fun letterbox(frameWidth: Int, frameHeight: Int, inputSize: Int): Letterbox {
        val scale = min(inputSize / frameWidth.toFloat(), inputSize / frameHeight.toFloat())
        val newW = frameWidth * scale
        val newH = frameHeight * scale
        return Letterbox(
            scale = scale,
            padX = (inputSize - newW) / 2f,
            padY = (inputSize - newH) / 2f,
        )
    }

    /**
     * @param shape rank-3 output, either channels-first `[1, 4+classes, boxes]`,
     * boxes-first `[1, boxes, 4+classes]`, or an end-to-end head `[1, boxes, 6]`
     * of x1, y1, x2, y2, score, class.
     * Coordinates may be input pixels or 0..1.
     */
    fun decode(
        output: FloatArray,
        shape: IntArray,
        inputSize: Int,
        frameWidth: Int,
        frameHeight: Int,
        scoreThreshold: Float,
        maxDetections: Int = 10,
    ): List<DetectionBox> {
        if (shape.size != 3 || shape[0] != 1) return emptyList()
        val raw = if (shape[2] == 6) {
            readEndToEnd(output, shape[1], inputSize)
        } else {
            readRawHead(output, shape, inputSize)
        }
        val box = letterbox(frameWidth, frameHeight, inputSize)
        val mapped = ArrayList<DetectionBox>(raw.size)
        for (item in raw) {
            if (item.score < scoreThreshold) continue
            val name = CLASS_NAMES.getOrNull(item.classId) ?: continue
            val left = ((item.x1 - box.padX) / box.scale) / frameWidth
            val top = ((item.y1 - box.padY) / box.scale) / frameHeight
            val right = ((item.x2 - box.padX) / box.scale) / frameWidth
            val bottom = ((item.y2 - box.padY) / box.scale) / frameHeight
            if (right - left < 0.002f || bottom - top < 0.002f) continue
            mapped.add(
                DetectionBox(
                    label = name,
                    score = item.score,
                    left = left.coerceIn(0f, 1f),
                    top = top.coerceIn(0f, 1f),
                    right = right.coerceIn(0f, 1f),
                    bottom = bottom.coerceIn(0f, 1f),
                ),
            )
        }
        return nms(mapped, maxDetections)
    }

    private data class RawBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int,
    )

    private fun readEndToEnd(output: FloatArray, boxes: Int, inputSize: Int): List<RawBox> {
        val out = ArrayList<RawBox>(boxes)
        for (i in 0 until boxes) {
            val base = i * 6
            if (base + 5 >= output.size) break
            out.add(
                RawBox(
                    x1 = output[base],
                    y1 = output[base + 1],
                    x2 = output[base + 2],
                    y2 = output[base + 3],
                    score = output[base + 4],
                    classId = output[base + 5].toInt(),
                ),
            )
        }
        return scaleIfNormalized(out, inputSize)
    }

    private fun readRawHead(output: FloatArray, shape: IntArray, inputSize: Int): List<RawBox> {
        val channelsFirst = shape[1] == 4 + CLASS_NAMES.size
        val boxesFirst = shape[2] == 4 + CLASS_NAMES.size
        if (!channelsFirst && !boxesFirst) return emptyList()
        val channels = 4 + CLASS_NAMES.size
        val boxes = if (channelsFirst) shape[2] else shape[1]
        val out = ArrayList<RawBox>(boxes)
        for (anchor in 0 until boxes) {
            fun at(channel: Int): Float {
                val index = if (channelsFirst) channel * boxes + anchor else anchor * channels + channel
                return output[index]
            }
            val cx = at(0)
            val cy = at(1)
            val w = at(2)
            val h = at(3)
            var best = 0f
            var bestId = -1
            for (cls in CLASS_NAMES.indices) {
                val score = at(4 + cls)
                if (score > best) {
                    best = score
                    bestId = cls
                }
            }
            out.add(
                RawBox(
                    x1 = cx - w / 2f,
                    y1 = cy - h / 2f,
                    x2 = cx + w / 2f,
                    y2 = cy + h / 2f,
                    score = best,
                    classId = bestId,
                ),
            )
        }
        return scaleIfNormalized(out, inputSize)
    }

    private fun scaleIfNormalized(boxes: List<RawBox>, inputSize: Int): List<RawBox> {
        var peak = 0f
        for (box in boxes) {
            peak = max(peak, max(max(box.x1, box.x2), max(box.y1, box.y2)))
        }
        if (peak > 1.5f) return boxes
        val size = inputSize.toFloat()
        return boxes.map { box ->
            box.copy(
                x1 = box.x1 * size,
                y1 = box.y1 * size,
                x2 = box.x2 * size,
                y2 = box.y2 * size,
            )
        }
    }

    private fun nms(boxes: List<DetectionBox>, maxDetections: Int): List<DetectionBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<DetectionBox>(min(maxDetections, sorted.size))
        while (sorted.isNotEmpty() && kept.size < maxDetections) {
            val winner = sorted.removeAt(0)
            kept.add(winner)
            sorted.removeAll { other -> other.label == winner.label && iou(winner, other) > 0.45f }
        }
        return kept
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - inter
        if (union <= 0f) return 0f
        return inter / union
    }
}
