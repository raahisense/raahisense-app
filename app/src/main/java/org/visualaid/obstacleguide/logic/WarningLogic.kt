package org.visualaid.obstacleguide.logic

import kotlin.math.abs

/**
 * Turns a camera detection into a spoken warning.
 *
 * Coordinates are normalized to the upright camera frame: origin at the top left,
 * x to the right, y downward, each axis from 0 to 1.
 *
 * This class does not build sentences. It only chooses an object key, a side,
 * and a distance. Phrase files supply the words.
 */
object WarningLogic {
    const val SCORE_THRESHOLD = 0.5f
    const val THROTTLE_MS = 4_000L

    /** Center x below this is on the left. */
    const val LEFT_MAX = 0.35f

    /** Center x above this is on the right. */
    const val RIGHT_MIN = 0.65f

    const val VERY_CLOSE_AREA = 0.22f
    const val VERY_CLOSE_HEIGHT = 0.55f
    const val VERY_CLOSE_BOTTOM = 0.92f
    const val VERY_CLOSE_BOTTOM_HEIGHT = 0.22f

    const val NEAR_AREA = 0.07f
    const val NEAR_HEIGHT = 0.28f
    const val NEAR_BOTTOM = 0.75f
    const val NEAR_BOTTOM_HEIGHT = 0.12f

    fun positionOf(centerX: Float): HorizontalPosition = when {
        centerX < LEFT_MAX -> HorizontalPosition.LEFT
        centerX > RIGHT_MIN -> HorizontalPosition.RIGHT
        else -> HorizontalPosition.AHEAD
    }

    /**
     * Very close: a large box, a tall box, or a box that reaches the bottom edge
     * and is tall enough to be at the walker's feet.
     * Near: a medium box, or a smaller box sitting low in the frame.
     * Far: everything else that is still detected.
     */
    fun closenessOf(area: Float, height: Float, bottom: Float): Closeness {
        val veryClose = area >= VERY_CLOSE_AREA ||
            height >= VERY_CLOSE_HEIGHT ||
            (bottom >= VERY_CLOSE_BOTTOM && height >= VERY_CLOSE_BOTTOM_HEIGHT)
        if (veryClose) return Closeness.VERY_CLOSE
        val near = area >= NEAR_AREA ||
            height >= NEAR_HEIGHT ||
            (bottom >= NEAR_BOTTOM && height >= NEAR_BOTTOM_HEIGHT)
        if (near) return Closeness.NEAR
        return Closeness.FAR
    }

    /**
     * Closer objects outrank central ones. One step of distance is worth more
     * than the full left-to-center swing. Area, then how low the box sits,
     * breaks remaining ties.
     */
    fun priorityOf(area: Float, centerX: Float, closeness: Closeness, bottom: Float): Float {
        val distanceRank = when (closeness) {
            Closeness.VERY_CLOSE -> 3
            Closeness.NEAR -> 2
            Closeness.FAR -> 1
        }
        val centrality = 1f - abs(centerX - 0.5f) / 0.5f
        return distanceRank * 2f + centrality + area + bottom * 0.01f
    }

    fun fromBox(box: DetectionBox): ObstacleWarning? {
        if (!box.score.isFinite() || box.score < SCORE_THRESHOLD) return null
        val label = box.label.trim().lowercase()
        if (label.isEmpty() || label == "???" || label !in PhraseIds.objects) return null
        if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) {
            return null
        }
        val left = box.left.coerceIn(0f, 1f)
        val top = box.top.coerceIn(0f, 1f)
        val right = box.right.coerceIn(0f, 1f)
        val bottom = box.bottom.coerceIn(0f, 1f)
        val width = right - left
        val height = bottom - top
        if (width <= 0f || height <= 0f) return null
        val area = width * height
        val centerX = (left + right) / 2f
        val position = positionOf(centerX)
        val closeness = closenessOf(area, height, bottom)
        val urgent = closeness == Closeness.VERY_CLOSE && position == HorizontalPosition.AHEAD
        return ObstacleWarning(
            label = label,
            position = position,
            closeness = closeness,
            priority = priorityOf(area, centerX, closeness, bottom),
            urgent = urgent,
        )
    }

    fun rank(boxes: List<DetectionBox>): List<ObstacleWarning> {
        return boxes.mapNotNull { fromBox(it) }
            .sortedWith(
                compareByDescending<ObstacleWarning> { it.priority }
                    .thenBy { it.label },
            )
    }

    /**
     * Speaks at most one warning, chosen from the two highest-priority objects.
     * A non-urgent warning waits while speech is still playing.
     * A very-close object ahead may be chosen even while speech is playing,
     * so the caller can interrupt. The same object, side, and distance is
     * suppressed for [THROTTLE_MS].
     */
    fun selectAnnouncement(
        ranked: List<ObstacleWarning>,
        throttle: WarningThrottle,
        speechBusy: Boolean,
        nowMs: Long,
    ): ObstacleWarning? {
        for (warning in ranked.take(2)) {
            if (speechBusy && !warning.urgent) continue
            if (throttle.isThrottled(warning, nowMs)) continue
            throttle.mark(warning, nowMs)
            return warning
        }
        return null
    }
}

enum class HorizontalPosition {
    LEFT,
    AHEAD,
    RIGHT,
}

enum class Closeness {
    VERY_CLOSE,
    NEAR,
    FAR,
}

data class DetectionBox(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ObstacleWarning(
    val label: String,
    val position: HorizontalPosition,
    val closeness: Closeness,
    val priority: Float,
    val urgent: Boolean,
)

class WarningThrottle(
    private val windowMs: Long = WarningLogic.THROTTLE_MS,
) {
    private val lastSpokenAt = HashMap<String, Long>()

    fun key(warning: ObstacleWarning): String {
        return "${warning.label}|${warning.position}|${warning.closeness}"
    }

    fun isThrottled(warning: ObstacleWarning, nowMs: Long): Boolean {
        val previous = lastSpokenAt[key(warning)] ?: return false
        return nowMs - previous < windowMs
    }

    fun mark(warning: ObstacleWarning, nowMs: Long) {
        lastSpokenAt[key(warning)] = nowMs
    }
}
