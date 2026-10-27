package org.visualaid.obstacleguide.speech

import android.content.Context
import org.visualaid.obstacleguide.R
import org.visualaid.obstacleguide.logic.Closeness
import org.visualaid.obstacleguide.logic.DepthWarnings
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
        "motorcycle" to R.string.obj_motorcycle,
        "car" to R.string.obj_car,
        "bus" to R.string.obj_bus,
        "truck" to R.string.obj_truck,
        "cow" to R.string.obj_cow,
        "dog" to R.string.obj_dog,
        "auto_rickshaw" to R.string.obj_auto_rickshaw,
        "cycle_rickshaw" to R.string.obj_cycle_rickshaw,
        "handcart" to R.string.obj_handcart,
        "pothole" to R.string.obj_pothole,
        "manhole_open_drain" to R.string.obj_manhole_open_drain,
        "curb" to R.string.obj_curb,
        "stairs" to R.string.obj_stairs,
        "pole_bollard" to R.string.obj_pole_bollard,
        "barrier" to R.string.obj_barrier,
        "street_furniture" to R.string.obj_street_furniture,
        "tree" to R.string.obj_tree,
        "cat" to R.string.obj_cat,
        "chair" to R.string.obj_chair,
        "couch" to R.string.obj_couch,
        "dining table" to R.string.obj_dining_table,
        "bed" to R.string.obj_bed,
        "toilet" to R.string.obj_toilet,
        "potted plant" to R.string.obj_potted_plant,
        "refrigerator" to R.string.obj_refrigerator,
        "tv" to R.string.obj_tv,
        "door" to R.string.obj_door,
        "wall" to R.string.obj_wall,
        "shoe" to R.string.obj_shoe,
        "bag" to R.string.obj_bag,
        "toy" to R.string.obj_toy,
        "bin" to R.string.obj_bin,
        "appliance" to R.string.obj_appliance,
        "cabinet" to R.string.obj_cabinet,
    )

    private val depthSentences = mapOf(
        "depth_unknown_very_close_left" to R.string.depth_unknown_very_close_left,
        "depth_unknown_very_close_ahead" to R.string.depth_unknown_very_close_ahead,
        "depth_unknown_very_close_right" to R.string.depth_unknown_very_close_right,
        "depth_unknown_near_left" to R.string.depth_unknown_near_left,
        "depth_unknown_near_ahead" to R.string.depth_unknown_near_ahead,
        "depth_unknown_near_right" to R.string.depth_unknown_near_right,
        "depth_wall_very_close" to R.string.depth_wall_very_close,
        "depth_wall_near" to R.string.depth_wall_near,
        "depth_approaching_left" to R.string.depth_approaching_left,
        "depth_approaching_ahead" to R.string.depth_approaching_ahead,
        "depth_approaching_right" to R.string.depth_approaching_right,
    )

    fun warning(context: Context, warning: ObstacleWarning): String {
        val depthName = DepthWarnings.phraseName(warning)
        if (depthName != null) {
            val sentence = depthSentences[depthName] ?: return ""
            return context.getString(sentence)
        }
        val objectRes = objects[warning.label] ?: return ""
        val templateRes = templates[warning.closeness to warning.position] ?: return ""
        val objectName = context.getString(objectRes)
        return context.getString(templateRes, objectName)
    }
}
