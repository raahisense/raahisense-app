package org.visualaid.obstacleguide.logic

/**
 * Resource names for phrases. Each warning is a fixed pattern in strings.xml
 * plus one separately translated object name. Code never assembles word order.
 */
object PhraseIds {
    data class Template(
        val closeness: Closeness,
        val position: HorizontalPosition,
        val resName: String,
    )

    val templates: List<Template> = listOf(
        Template(Closeness.VERY_CLOSE, HorizontalPosition.LEFT, "warn_very_close_left"),
        Template(Closeness.VERY_CLOSE, HorizontalPosition.AHEAD, "warn_very_close_ahead"),
        Template(Closeness.VERY_CLOSE, HorizontalPosition.RIGHT, "warn_very_close_right"),
        Template(Closeness.NEAR, HorizontalPosition.LEFT, "warn_near_left"),
        Template(Closeness.NEAR, HorizontalPosition.AHEAD, "warn_near_ahead"),
        Template(Closeness.NEAR, HorizontalPosition.RIGHT, "warn_near_right"),
        Template(Closeness.FAR, HorizontalPosition.LEFT, "warn_far_left"),
        Template(Closeness.FAR, HorizontalPosition.AHEAD, "warn_far_ahead"),
        Template(Closeness.FAR, HorizontalPosition.RIGHT, "warn_far_right"),
    )

    /** COCO label (lowercase) to the string resource that speaks it. */
    val objects: Map<String, String> = linkedMapOf(
        "person" to "obj_person",
        "bicycle" to "obj_bicycle",
        "car" to "obj_car",
        "motorcycle" to "obj_motorcycle",
        "bus" to "obj_bus",
        "truck" to "obj_truck",
        "traffic light" to "obj_traffic_light",
        "fire hydrant" to "obj_fire_hydrant",
        "stop sign" to "obj_stop_sign",
        "parking meter" to "obj_parking_meter",
        "bench" to "obj_bench",
        "cat" to "obj_cat",
        "dog" to "obj_dog",
        "horse" to "obj_horse",
        "sheep" to "obj_sheep",
        "cow" to "obj_cow",
        "backpack" to "obj_backpack",
        "umbrella" to "obj_umbrella",
        "suitcase" to "obj_suitcase",
        "bottle" to "obj_bottle",
        "chair" to "obj_chair",
        "couch" to "obj_couch",
        "potted plant" to "obj_potted_plant",
        "bed" to "obj_bed",
        "dining table" to "obj_dining_table",
        "toilet" to "obj_toilet",
        "tv" to "obj_tv",
        "oven" to "obj_oven",
        "sink" to "obj_sink",
        "refrigerator" to "obj_refrigerator",
    )

    val uiStrings: List<String> = listOf(
        "app_name",
        "tagline",
        "hold_phone",
        "safety_note",
        "action_start",
        "action_stop",
        "action_allow_camera",
        "action_open_settings",
        "cd_start",
        "cd_stop",
        "cd_allow_camera",
        "cd_open_settings",
        "status_ready",
        "status_started",
        "status_stopped",
        "status_listening",
        "status_camera_needed",
        "status_camera_denied",
        "status_camera_blocked",
        "status_camera_error",
        "status_model_error",
        "status_tts_error",
        "status_battery_settings_unavailable",
        "prompt_allow_camera",
        "settings_heading",
        "label_language",
        "label_speech_rate",
        "rate_slow",
        "rate_normal",
        "rate_fast",
        "battery_on",
        "battery_off",
        "vibration_on",
        "vibration_off",
        "cd_battery_turn_on",
        "cd_battery_turn_off",
        "cd_vibration_turn_on",
        "cd_vibration_turn_off",
        "battery_opt_button",
        "cd_battery_opt",
        "battery_hint_unrestricted",
        "battery_hint_restricted",
        "draft_banner",
        "voice_missing_screen",
        "notif_channel",
        "notif_title",
        "notif_listening",
        "notif_stop",
    )

    val requiredInEveryLocale: List<String> =
        templates.map { it.resName } + objects.values + uiStrings

    val englishVoiceMissing: List<String> = listOf(
        "voice_missing_hi",
        "voice_missing_ta",
        "voice_missing_bn",
        "voice_missing_sw",
        "voice_missing_es",
    )
}
