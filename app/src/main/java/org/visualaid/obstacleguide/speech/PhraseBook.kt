package org.visualaid.obstacleguide.speech

import android.content.Context
import org.visualaid.obstacleguide.R
import org.visualaid.obstacleguide.logic.Closeness
import org.visualaid.obstacleguide.logic.HorizontalPosition
import org.visualaid.obstacleguide.logic.ObstacleWarning

/**
 * Looks up a finished phrase. The pattern and the object name both come from
 * the selected language file. Nothing here decides word order.
 */
object PhraseBook {
    private val templates = mapOf(
        (Closeness.VERY_CLOSE to HorizontalPosition.LEFT) to R.string.warn_very_close_left,
        (Closeness.VERY_CLOSE to HorizontalPosition.AHEAD) to R.string.warn_very_close_ahead,
        (Closeness.VERY_CLOSE to HorizontalPosition.RIGHT) to R.string.warn_very_close_right,
        (Closeness.NEAR to HorizontalPosition.LEFT) to R.string.warn_near_left,
        (Closeness.NEAR to HorizontalPosition.AHEAD) to R.string.warn_near_ahead,
        (Closeness.NEAR to HorizontalPosition.RIGHT) to R.string.warn_near_right,
        (Closeness.FAR to HorizontalPosition.LEFT) to R.string.warn_far_left,
        (Closeness.FAR to HorizontalPosition.AHEAD) to R.string.warn_far_ahead,
        (Closeness.FAR to HorizontalPosition.RIGHT) to R.string.warn_far_right,
    )

    private val objects = mapOf(
        "person" to R.string.obj_person,
        "bicycle" to R.string.obj_bicycle,
        "car" to R.string.obj_car,
        "motorcycle" to R.string.obj_motorcycle,
        "bus" to R.string.obj_bus,
        "truck" to R.string.obj_truck,
        "traffic light" to R.string.obj_traffic_light,
        "fire hydrant" to R.string.obj_fire_hydrant,
        "stop sign" to R.string.obj_stop_sign,
        "parking meter" to R.string.obj_parking_meter,
        "bench" to R.string.obj_bench,
        "cat" to R.string.obj_cat,
        "dog" to R.string.obj_dog,
        "horse" to R.string.obj_horse,
        "sheep" to R.string.obj_sheep,
        "cow" to R.string.obj_cow,
        "backpack" to R.string.obj_backpack,
        "umbrella" to R.string.obj_umbrella,
        "suitcase" to R.string.obj_suitcase,
        "bottle" to R.string.obj_bottle,
        "chair" to R.string.obj_chair,
        "couch" to R.string.obj_couch,
        "potted plant" to R.string.obj_potted_plant,
        "bed" to R.string.obj_bed,
        "dining table" to R.string.obj_dining_table,
        "toilet" to R.string.obj_toilet,
        "tv" to R.string.obj_tv,
        "oven" to R.string.obj_oven,
        "sink" to R.string.obj_sink,
        "refrigerator" to R.string.obj_refrigerator,
    )

    fun warning(context: Context, warning: ObstacleWarning): String {
        val objectRes = objects[warning.label] ?: return ""
        val templateRes = templates[warning.closeness to warning.position] ?: return ""
        val objectName = context.getString(objectRes)
        return context.getString(templateRes, objectName)
    }
}
