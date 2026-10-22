package org.visualaid.obstacleguide.logic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns relative depth into a wall, an unknown object, or something approaching.
 *
 * [DepthField.raw] is row-major. A larger value is farther away. NaN samples are holes.
 * This does not build sentences. Each result is an [ObstacleWarning] with one of
 * [LABEL_UNKNOWN], [LABEL_WALL], or [LABEL_APPROACHING]. The phrase files hold the words.
 *
 * A wall is spoken only when the close region is large, flat, and spans most of the
 * frame width. Anything else close in the walking corridor, and not already named by
 * the detector, is an unknown object. A region whose depth shrinks quickly across
 * frames is approaching, and that warning outranks a static one.
 */
object DepthWarnings {
    const val LABEL_UNKNOWN = "depth_unknown"
    const val LABEL_WALL = "depth_wall"
    const val LABEL_APPROACHING = "depth_approaching"

    /** Lower-middle walking corridor, as a fraction of the frame height. */
    const val CORRIDOR_TOP = 0.42f
    const val CORRIDOR_BOTTOM = 0.88f

    /** Static warnings wait until the same close reading lasts this many frames. */
    const val STATIC_DEBOUNCE = 3

    /** Approaching uses this many frames, including the current one. */
    const val APPROACH_FRAMES = 4

    /**
     * The newest column median must be at most this fraction of the oldest.
     * Four frames at this ratio are a clear approach, not a shadow flicker.
     */
    const val APPROACH_SHRINK = 0.82f

    /** A single step may rise by this fraction and still count as shrinking. */
    const val APPROACH_STEP_SLACK = 1.01f

    const val APPROACH_PRIORITY = 20f

    const val WALL_SPAN_LEFT = 0.22f
    const val WALL_SPAN_RIGHT = 0.78f
    const val WALL_CLOSE_FRACTION = 0.55f
    const val WALL_THIRD_FRACTION = 0.35f
    const val WALL_FLATNESS = 0.12f

    /** Uncalibrated close cut, as a fraction of the far part of the same frame. */
    const val UNCALIBRATED_VERY_CLOSE = 0.55f
    const val UNCALIBRATED_NEAR = 0.72f

    /** (p75 - p25) / median. Below this, the frame has no near-versus-far contrast. */
    const val UNCALIBRATED_SPREAD = 0.18f

    /** 0 is the open-path sample, 1 is a wall one step away. */
    const val CAL_VERY_CLOSE = 0.85f
    const val CAL_NEAR = 0.45f

    /** A detection explains a column when it covers at least this much of the column. */
    const val EXPLAIN_COVER = 0.35f

    const val PRIORITY_WALL_VERY_CLOSE = 8.5f
    const val PRIORITY_UNKNOWN_VERY_CLOSE_AHEAD = 8f
    const val PRIORITY_UNKNOWN_VERY_CLOSE_SIDE = 7f
    const val PRIORITY_WALL_NEAR = 6f
    const val PRIORITY_UNKNOWN_NEAR_AHEAD = 5.5f
    const val PRIORITY_UNKNOWN_NEAR_SIDE = 5f

    val positions: List<HorizontalPosition> = listOf(
        HorizontalPosition.LEFT,
        HorizontalPosition.AHEAD,
        HorizontalPosition.RIGHT,
    )

    fun phraseName(warning: ObstacleWarning): String? = when (warning.label) {
        LABEL_UNKNOWN -> when (warning.closeness to warning.position) {
            (Closeness.VERY_CLOSE to HorizontalPosition.LEFT) -> "depth_unknown_very_close_left"
            (Closeness.VERY_CLOSE to HorizontalPosition.AHEAD) -> "depth_unknown_very_close_ahead"
            (Closeness.VERY_CLOSE to HorizontalPosition.RIGHT) -> "depth_unknown_very_close_right"
            (Closeness.NEAR to HorizontalPosition.LEFT) -> "depth_unknown_near_left"
            (Closeness.NEAR to HorizontalPosition.AHEAD) -> "depth_unknown_near_ahead"
            (Closeness.NEAR to HorizontalPosition.RIGHT) -> "depth_unknown_near_right"
            else -> null
        }
        LABEL_WALL -> when (warning.closeness) {
            Closeness.VERY_CLOSE -> "depth_wall_very_close"
            Closeness.NEAR -> "depth_wall_near"
            else -> null
        }
        LABEL_APPROACHING -> when (warning.position) {
            HorizontalPosition.LEFT -> "depth_approaching_left"
            HorizontalPosition.AHEAD -> "depth_approaching_ahead"
            HorizontalPosition.RIGHT -> "depth_approaching_right"
        }
        else -> null
    }

    fun aheadMedian(field: DepthField): Float {
        return columnMedian(corridorSamples(field), HorizontalPosition.AHEAD)
    }

    internal fun corridorSamples(field: DepthField): List<DepthSample> {
        if (field.width <= 0 || field.height <= 0 || field.raw.size != field.width * field.height) {
            return emptyList()
        }
        val yStart = (CORRIDOR_TOP * field.height).toInt().coerceIn(0, field.height - 1)
        val yEnd = (CORRIDOR_BOTTOM * field.height).toInt().coerceIn(yStart, field.height - 1)
        val xStride = max(1, field.width / 48)
        val yStride = max(1, (yEnd - yStart + 1) / 32)
        val out = ArrayList<DepthSample>(((yEnd - yStart) / yStride + 1) * (field.width / xStride + 1))
        var y = yStart
        while (y <= yEnd) {
            var x = 0
            while (x < field.width) {
                val value = field.raw[y * field.width + x]
                if (value.isFinite()) {
                    out.add(DepthSample((x + 0.5f) / field.width, value))
                }
                x += xStride
            }
            y += yStride
        }
        return out
    }

    internal fun columnMedian(samples: List<DepthSample>, position: HorizontalPosition): Float {
        return median(samples.filter { columnOf(it.nx) == position }.map { it.value })
    }

    internal fun columnOf(nx: Float): HorizontalPosition = when {
        nx < WarningLogic.LEFT_MAX -> HorizontalPosition.LEFT
        nx > WarningLogic.RIGHT_MIN -> HorizontalPosition.RIGHT
        else -> HorizontalPosition.AHEAD
    }

    internal fun scaleOf(samples: List<DepthSample>, calibration: DepthCalibration?): DepthScale {
        val saved = calibration?.takeIf { it.usable() }
        if (saved != null) {
            val span = saved.openRaw - saved.wallRaw
            return DepthScale(
                veryCloseAt = saved.openRaw - CAL_VERY_CLOSE * span,
                nearAt = saved.openRaw - CAL_NEAR * span,
                active = true,
            )
        }
        val values = samples.map { it.value }.filter { it.isFinite() }.sorted()
        if (values.size < 4) return DepthScale.inactive()
        val p25 = percentile(values, 0.25f)
        val p75 = percentile(values, 0.75f)
        val mid = median(values)
        if (!mid.isFinite() || mid <= 0f) return DepthScale.inactive()
        if ((p75 - p25) / mid < UNCALIBRATED_SPREAD) return DepthScale.inactive()
        return DepthScale(
            veryCloseAt = p75 * UNCALIBRATED_VERY_CLOSE,
            nearAt = p75 * UNCALIBRATED_NEAR,
            active = true,
        )
    }

    internal fun closeness(value: Float, scale: DepthScale): Closeness {
        if (!scale.active || !value.isFinite()) return Closeness.FAR
        if (value <= scale.veryCloseAt) return Closeness.VERY_CLOSE
        if (value <= scale.nearAt) return Closeness.NEAR
        return Closeness.FAR
    }

    /**
     * A box explains the column when a known class covers enough of that
     * column's walking corridor. Score uses the same cutoff as spoken detections.
     */
    fun explains(
        box: DetectionBox,
        position: HorizontalPosition,
        scoreThreshold: Float = WarningLogic.SCORE_THRESHOLD,
    ): Boolean {
        val label = box.label.trim().lowercase()
        if (label !in PhraseIds.objects) return false
        if (!box.score.isFinite() || box.score < scoreThreshold) return false
        val roiLeft = when (position) {
            HorizontalPosition.LEFT -> 0f
            HorizontalPosition.AHEAD -> WarningLogic.LEFT_MAX
            HorizontalPosition.RIGHT -> WarningLogic.RIGHT_MIN
        }
        val roiRight = when (position) {
            HorizontalPosition.LEFT -> WarningLogic.LEFT_MAX
            HorizontalPosition.AHEAD -> WarningLogic.RIGHT_MIN
            HorizontalPosition.RIGHT -> 1f
        }
        val roiArea = (roiRight - roiLeft) * (CORRIDOR_BOTTOM - CORRIDOR_TOP)
        if (roiArea <= 0f) return false
        val overlapW = min(box.right, roiRight) - max(box.left, roiLeft)
        val overlapH = min(box.bottom, CORRIDOR_BOTTOM) - max(box.top, CORRIDOR_TOP)
        if (overlapW <= 0f || overlapH <= 0f) return false
        return overlapW * overlapH / roiArea >= EXPLAIN_COVER
    }

    internal fun wallCandidate(samples: List<DepthSample>, scale: DepthScale): WallRead? {
        if (!scale.active || samples.size < 8) return null
        val close = samples.filter { closeness(it.value, scale) != Closeness.FAR }
        if (close.size < 8) return null
        if (close.size.toFloat() / samples.size < WALL_CLOSE_FRACTION) return null
        val minX = close.minOf { it.nx }
        val maxX = close.maxOf { it.nx }
        if (minX >= WALL_SPAN_LEFT || maxX <= WALL_SPAN_RIGHT) return null
        val thirds = listOf(0f to 1f / 3f, 1f / 3f to 2f / 3f, 2f / 3f to 1.01f)
        for ((from, to) in thirds) {
            val band = samples.filter { it.nx >= from && it.nx < to }
            if (band.isEmpty()) return null
            val fraction = band.count { closeness(it.value, scale) != Closeness.FAR }.toFloat() / band.size
            if (fraction < WALL_THIRD_FRACTION) return null
        }
        val closeValues = close.map { it.value }
        val mid = median(closeValues)
        if (!mid.isFinite() || mid == 0f) return null
        if (stdev(closeValues) / kotlin.math.abs(mid) > WALL_FLATNESS) return null
        val corridorMedian = median(samples.map { it.value })
        val howClose = closeness(corridorMedian, scale)
        if (howClose == Closeness.FAR) return null
        return WallRead(howClose)
    }

    internal fun median(values: List<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        if (sorted.isEmpty()) return Float.NaN
        val rank = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val low = rank.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = rank - low
        return sorted[low] * (1f - weight) + sorted[high] * weight
    }

    private fun stdev(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.sum() / values.size
        var acc = 0.0
        for (value in values) {
            val delta = (value - mean).toDouble()
            acc += delta * delta
        }
        return sqrt(acc / values.size).toFloat()
    }
}

class DepthField(
    val width: Int,
    val height: Int,
    val raw: FloatArray,
)

/**
 * Rotates a depth map clockwise so it matches an upright camera bitmap.
 * 90 degrees turns the old left edge into the bottom edge.
 */
object DepthFields {
    fun rotate(field: DepthField, degrees: Int): DepthField {
        val turns = (((degrees % 360) + 360) % 360) / 90
        var current = field
        repeat(turns) { current = rotate90(current) }
        return current
    }

    private fun rotate90(field: DepthField): DepthField {
        val width = field.width
        val height = field.height
        val raw = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                raw[ny * height + nx] = field.raw[y * width + x]
            }
        }
        return DepthField(height, width, raw)
    }
}

data class DepthCalibration(
    val openRaw: Float,
    val wallRaw: Float,
) {
    /** Larger raw means farther, so an open path sits above a one-step wall. */
    fun usable(): Boolean {
        return openRaw.isFinite() && wallRaw.isFinite() && wallRaw > 0f && openRaw > wallRaw * 1.05f
    }
}

internal data class DepthSample(val nx: Float, val value: Float)

internal data class DepthScale(
    val veryCloseAt: Float,
    val nearAt: Float,
    val active: Boolean,
) {
    companion object {
        fun inactive() = DepthScale(Float.NaN, Float.NaN, false)
    }
}

internal data class WallRead(val closeness: Closeness)

/**
 * Remembers a few depth frames so a shadow or a shake does not speak,
 * and so a shrinking region can be told from a still one.
 */
class DepthTracker {
    private val history = ArrayDeque<FloatArray>()
    private val closeStreak = IntArray(3)
    private var wallStreak = 0

    fun reset() {
        history.clear()
        closeStreak.fill(0)
        wallStreak = 0
    }

    fun update(
        field: DepthField,
        detections: List<DetectionBox>,
        calibration: DepthCalibration?,
        scoreThreshold: Float = WarningLogic.SCORE_THRESHOLD,
    ): List<ObstacleWarning> {
        val samples = DepthWarnings.corridorSamples(field)
        val scale = DepthWarnings.scaleOf(samples, calibration)
        val medians = FloatArray(3) { index ->
            DepthWarnings.columnMedian(samples, DepthWarnings.positions[index])
        }
        history.addLast(medians)
        while (history.size > DepthWarnings.APPROACH_FRAMES) {
            history.removeFirst()
        }
        val explained = BooleanArray(3) { index ->
            detections.any { DepthWarnings.explains(it, DepthWarnings.positions[index], scoreThreshold) }
        }
        val wall = DepthWarnings.wallCandidate(samples, scale)
        val wallBlocked = explained[1] || (explained[0] && explained[2])
        if (wall != null && !wallBlocked) {
            wallStreak += 1
        } else {
            wallStreak = 0
        }
        val staticClose = Array(3) { index ->
            val how = DepthWarnings.closeness(medians[index], scale)
            if (!explained[index] && how != Closeness.FAR) how else null
        }
        for (index in 0..2) {
            closeStreak[index] = if (staticClose[index] != null) closeStreak[index] + 1 else 0
        }

        val warnings = ArrayList<ObstacleWarning>(4)
        approachingWarning(explained, scale)?.let { warnings.add(it) }

        val wallReady = wall != null && !wallBlocked && wallStreak >= DepthWarnings.STATIC_DEBOUNCE
        if (wallReady) {
            warnings.add(wallWarning(wall!!.closeness))
        } else {
            for (index in 0..2) {
                val how = staticClose[index] ?: continue
                if (closeStreak[index] < DepthWarnings.STATIC_DEBOUNCE) continue
                warnings.add(unknownWarning(DepthWarnings.positions[index], how))
            }
        }
        return warnings.sortedWith(
            compareByDescending<ObstacleWarning> { it.priority }.thenBy { it.label },
        )
    }

    private fun approachingWarning(explained: BooleanArray, scale: DepthScale): ObstacleWarning? {
        if (history.size < DepthWarnings.APPROACH_FRAMES) return null
        val recent = history.toList()
        val hits = ArrayList<Pair<Int, Float>>(3)
        for (index in 0..2) {
            if (explained[index]) continue
            val series = recent.map { it[index] }
            if (series.any { !it.isFinite() || it <= 0f }) continue
            val shrinking = (1 until series.size).all { step ->
                series[step] < series[step - 1] * DepthWarnings.APPROACH_STEP_SLACK
            }
            if (!shrinking) continue
            val oldest = series.first()
            val newest = series.last()
            if (newest > oldest * DepthWarnings.APPROACH_SHRINK) continue
            // Still far: the walker may just be moving. Warn once the region is already near.
            if (DepthWarnings.closeness(newest, scale) == Closeness.FAR) continue
            hits.add(index to newest / oldest)
        }
        if (hits.isEmpty()) return null
        val index = when {
            hits.any { it.first == 1 } -> 1
            hits.size >= 2 && hits.any { it.first == 0 } && hits.any { it.first == 2 } -> 1
            else -> hits.minBy { it.second }.first
        }
        return ObstacleWarning(
            label = DepthWarnings.LABEL_APPROACHING,
            position = DepthWarnings.positions[index],
            closeness = Closeness.VERY_CLOSE,
            priority = DepthWarnings.APPROACH_PRIORITY,
            urgent = true,
        )
    }

    private fun wallWarning(closeness: Closeness): ObstacleWarning {
        val very = closeness == Closeness.VERY_CLOSE
        return ObstacleWarning(
            label = DepthWarnings.LABEL_WALL,
            position = HorizontalPosition.AHEAD,
            closeness = if (very) Closeness.VERY_CLOSE else Closeness.NEAR,
            priority = if (very) DepthWarnings.PRIORITY_WALL_VERY_CLOSE else DepthWarnings.PRIORITY_WALL_NEAR,
            urgent = very,
        )
    }

    private fun unknownWarning(position: HorizontalPosition, closeness: Closeness): ObstacleWarning {
        val ahead = position == HorizontalPosition.AHEAD
        val very = closeness == Closeness.VERY_CLOSE
        val priority = when {
            very && ahead -> DepthWarnings.PRIORITY_UNKNOWN_VERY_CLOSE_AHEAD
            very -> DepthWarnings.PRIORITY_UNKNOWN_VERY_CLOSE_SIDE
            ahead -> DepthWarnings.PRIORITY_UNKNOWN_NEAR_AHEAD
            else -> DepthWarnings.PRIORITY_UNKNOWN_NEAR_SIDE
        }
        return ObstacleWarning(
            label = DepthWarnings.LABEL_UNKNOWN,
            position = position,
            closeness = if (very) Closeness.VERY_CLOSE else Closeness.NEAR,
            priority = priority,
            urgent = very && ahead,
        )
    }
}
