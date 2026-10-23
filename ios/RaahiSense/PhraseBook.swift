import Foundation
import RaahiSenseLogic

enum PhraseBook {
    static func text(_ key: String, language: String, _ args: CVarArg...) -> String {
        let format = bundle(language).localizedString(forKey: key, value: key, table: nil)
        guard !args.isEmpty else { return format }
        return String(format: format, locale: Locale(identifier: language), arguments: args)
    }

    static func warning(_ warning: ObstacleWarning, language: String) -> String {
        if let depthName = DepthWarnings.phraseName(warning) {
            let sentence = text(depthName, language: language)
            return sentence == depthName ? "" : sentence
        }
        guard let objectKey = objectKeys[warning.label] else { return "" }
        let objectName = text(objectKey, language: language)
        let template = text(templateKey(warning), language: language)
        return String(format: template, locale: Locale(identifier: language), objectName)
    }

    static func speechLanguageCode(_ tag: String) -> String {
        switch tag {
        case "hi": return "hi-IN"
        case "ta": return "ta-IN"
        case "bn": return "bn-IN"
        case "sw": return "sw"
        case "es": return "es-ES"
        default: return "en-US"
        }
    }

    private static func bundle(_ tag: String) -> Bundle {
        let code = GuideSettings.languageTags.contains(tag) ? tag : "en"
        if let path = Bundle.main.path(forResource: code, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return bundle
        }
        return .main
    }

    private static func templateKey(_ warning: ObstacleWarning) -> String {
        let distance: String
        switch warning.closeness {
        case .veryClose: distance = "very_close"
        case .near: distance = "near"
        case .far: distance = "far"
        }
        return "warn_\(distance)_\(warning.position.rawValue)"
    }

    private static let objectKeys = [
        "person": "obj_person",
        "bicycle": "obj_bicycle",
        "motorcycle": "obj_motorcycle",
        "car": "obj_car",
        "bus": "obj_bus",
        "truck": "obj_truck",
        "cow": "obj_cow",
        "dog": "obj_dog",
        "auto_rickshaw": "obj_auto_rickshaw",
        "cycle_rickshaw": "obj_cycle_rickshaw",
        "handcart": "obj_handcart",
        "pothole": "obj_pothole",
        "manhole_open_drain": "obj_manhole_open_drain",
        "curb": "obj_curb",
        "stairs": "obj_stairs",
        "pole_bollard": "obj_pole_bollard",
        "barrier": "obj_barrier",
        "street_furniture": "obj_street_furniture",
        "tree": "obj_tree",
        "cat": "obj_cat",
        "chair": "obj_chair",
        "couch": "obj_couch",
        "dining table": "obj_dining_table",
        "bed": "obj_bed",
        "toilet": "obj_toilet",
        "potted plant": "obj_potted_plant",
        "refrigerator": "obj_refrigerator",
        "tv": "obj_tv",
        "door": "obj_door",
    ]
}
