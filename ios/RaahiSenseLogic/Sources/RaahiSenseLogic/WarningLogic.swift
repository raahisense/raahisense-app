import Foundation

/// Turns a camera detection into a spoken warning.
/// Coordinates are normalized to the upright camera frame: origin at the top left,
/// x to the right, y downward, each axis from 0 to 1.
/// This type does not build sentences. Phrase files supply the words.
public enum WarningLogic {
    public static let scoreThreshold: Float = 0.5
    public static let throttleMs: Int64 = 4_000
    public static let leftMax: Float = 0.35
    public static let rightMin: Float = 0.65
    public static let veryCloseArea: Float = 0.22
    public static let veryCloseHeight: Float = 0.55
    public static let veryCloseBottom: Float = 0.92
    public static let veryCloseBottomHeight: Float = 0.22
    public static let nearArea: Float = 0.07
    public static let nearHeight: Float = 0.28
    public static let nearBottom: Float = 0.75
    public static let nearBottomHeight: Float = 0.12

    public static func positionOf(_ centerX: Float) -> HorizontalPosition {
        if centerX < leftMax { return .left }
        if centerX > rightMin { return .right }
        return .ahead
    }

    public static func closenessOf(area: Float, height: Float, bottom: Float) -> Closeness {
        let veryClose = area >= veryCloseArea
            || height >= veryCloseHeight
            || (bottom >= veryCloseBottom && height >= veryCloseBottomHeight)
        if veryClose { return .veryClose }
        let near = area >= nearArea
            || height >= nearHeight
            || (bottom >= nearBottom && height >= nearBottomHeight)
        if near { return .near }
        return .far
    }

    public static func priorityOf(area: Float, centerX: Float, closeness: Closeness, bottom: Float) -> Float {
        let distanceRank: Float
        switch closeness {
        case .veryClose: distanceRank = 3
        case .near: distanceRank = 2
        case .far: distanceRank = 1
        }
        let centrality = 1 - abs(centerX - 0.5) / 0.5
        return distanceRank * 2 + centrality + area + bottom * 0.01
    }

    public static func fromBox(_ box: DetectionBox, scoreThreshold: Float = WarningLogic.scoreThreshold) -> ObstacleWarning? {
        guard box.score.isFinite, box.score >= scoreThreshold else { return nil }
        let label = box.label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !label.isEmpty, label != "???", PhraseIds.objects.contains(label) else { return nil }
        guard box.left.isFinite, box.top.isFinite, box.right.isFinite, box.bottom.isFinite else { return nil }
        let left = min(1, max(0, box.left))
        let top = min(1, max(0, box.top))
        let right = min(1, max(0, box.right))
        let bottom = min(1, max(0, box.bottom))
        let width = right - left
        let height = bottom - top
        guard width > 0, height > 0 else { return nil }
        let area = width * height
        let centerX = (left + right) / 2
        let position = positionOf(centerX)
        let closeness = closenessOf(area: area, height: height, bottom: bottom)
        let urgent = closeness == .veryClose && position == .ahead
        return ObstacleWarning(
            label: label,
            position: position,
            closeness: closeness,
            priority: priorityOf(area: area, centerX: centerX, closeness: closeness, bottom: bottom),
            urgent: urgent
        )
    }

    public static func rank(_ boxes: [DetectionBox], scoreThreshold: Float = WarningLogic.scoreThreshold) -> [ObstacleWarning] {
        boxes.compactMap { fromBox($0, scoreThreshold: scoreThreshold) }
            .sorted { lhs, rhs in
                if lhs.priority != rhs.priority { return lhs.priority > rhs.priority }
                return lhs.label < rhs.label
            }
    }

    public static func selectAnnouncement(
        ranked: [ObstacleWarning],
        throttle: WarningThrottle,
        speechBusy: Bool,
        nowMs: Int64
    ) -> ObstacleWarning? {
        for warning in ranked.prefix(2) {
            if speechBusy && !warning.urgent { continue }
            if throttle.isThrottled(warning, nowMs: nowMs) { continue }
            throttle.mark(warning, nowMs: nowMs)
            return warning
        }
        return nil
    }
}

public enum HorizontalPosition: String, Codable, CaseIterable {
    case left, ahead, right
}

public enum Closeness: String, Codable, CaseIterable {
    case veryClose, near, far
}

public struct DetectionBox: Equatable {
    public var label: String
    public var score: Float
    public var left: Float
    public var top: Float
    public var right: Float
    public var bottom: Float

    public init(label: String, score: Float, left: Float, top: Float, right: Float, bottom: Float) {
        self.label = label
        self.score = score
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }
}

public struct ObstacleWarning: Equatable {
    public var label: String
    public var position: HorizontalPosition
    public var closeness: Closeness
    public var priority: Float
    public var urgent: Bool

    public init(label: String, position: HorizontalPosition, closeness: Closeness, priority: Float, urgent: Bool) {
        self.label = label
        self.position = position
        self.closeness = closeness
        self.priority = priority
        self.urgent = urgent
    }
}

public final class WarningThrottle {
    private let windowMs: Int64
    private var lastSpokenAt: [String: Int64] = [:]

    public init(windowMs: Int64 = WarningLogic.throttleMs) {
        self.windowMs = windowMs
    }

    public func key(_ warning: ObstacleWarning) -> String {
        "\(warning.label)|\(warning.position)|\(warning.closeness)"
    }

    public func isThrottled(_ warning: ObstacleWarning, nowMs: Int64) -> Bool {
        guard let previous = lastSpokenAt[key(warning)] else { return false }
        return nowMs - previous < windowMs
    }

    public func mark(_ warning: ObstacleWarning, nowMs: Int64) {
        lastSpokenAt[key(warning)] = nowMs
    }
}

public enum PhraseIds {
    public static let objects: Set<String> = [
        "person", "bicycle", "motorcycle", "car", "bus", "truck", "cow", "dog",
        "auto_rickshaw", "cycle_rickshaw", "handcart", "pothole", "manhole_open_drain",
        "curb", "stairs", "pole_bollard", "barrier", "street_furniture", "tree",
        "cat", "chair", "couch", "dining table", "bed", "toilet", "potted plant",
        "refrigerator", "tv", "door",
    ]
}

public struct GuideSettings: Equatable, Codable {
    public var languageTag: String
    public var batterySaver: Bool
    public var speechRate: Float
    public var vibrationEnabled: Bool
    public var voiceEnabled: Bool
    public var wakePhrase: String
    public var speechVolume: Float
    public var depthOpenRaw: Float
    public var depthWallRaw: Float
    public var torchMode: TorchMode
    public var collectEnabled: Bool
    public var collectIntervalMs: Int64
    public var collectGps: Bool

    public static let rateSlow: Float = 0.8
    public static let rateNormal: Float = 1.1
    public static let rateFast: Float = 1.35
    public static let volumeLow: Float = 0.55
    public static let volumeMid: Float = 0.8
    public static let volumeHigh: Float = 1.0
    public static let defaultWake = "Hey Guide"
    public static let intervalNormalMs: Int64 = 300
    public static let intervalSaverMs: Int64 = 1_500
    public static let depthIntervalNormalMs: Int64 = 500
    public static let depthIntervalSaverMs: Int64 = 2_000
    public static let languageTags = ["en", "hi", "ta", "bn", "sw", "es"]

    public init(
        languageTag: String = "en",
        batterySaver: Bool = false,
        speechRate: Float = GuideSettings.rateNormal,
        vibrationEnabled: Bool = true,
        voiceEnabled: Bool = true,
        wakePhrase: String = GuideSettings.defaultWake,
        speechVolume: Float = GuideSettings.volumeHigh,
        depthOpenRaw: Float = 0,
        depthWallRaw: Float = 0,
        torchMode: TorchMode = .auto,
        collectEnabled: Bool = false,
        collectIntervalMs: Int64 = CollectCapture.defaultIntervalMs,
        collectGps: Bool = false
    ) {
        self.languageTag = languageTag
        self.batterySaver = batterySaver
        self.speechRate = speechRate
        self.vibrationEnabled = vibrationEnabled
        self.voiceEnabled = voiceEnabled
        self.wakePhrase = wakePhrase
        self.speechVolume = speechVolume
        self.depthOpenRaw = depthOpenRaw
        self.depthWallRaw = depthWallRaw
        self.torchMode = torchMode
        self.collectEnabled = collectEnabled
        self.collectIntervalMs = collectIntervalMs
        self.collectGps = collectGps
    }

    private enum CodingKeys: String, CodingKey {
        case languageTag, batterySaver, speechRate, vibrationEnabled, voiceEnabled
        case wakePhrase, speechVolume, depthOpenRaw, depthWallRaw, torchMode
        case collectEnabled, collectIntervalMs, collectGps
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        languageTag = try container.decodeIfPresent(String.self, forKey: .languageTag) ?? "en"
        batterySaver = try container.decodeIfPresent(Bool.self, forKey: .batterySaver) ?? false
        speechRate = try container.decodeIfPresent(Float.self, forKey: .speechRate) ?? Self.rateNormal
        vibrationEnabled = try container.decodeIfPresent(Bool.self, forKey: .vibrationEnabled) ?? true
        voiceEnabled = try container.decodeIfPresent(Bool.self, forKey: .voiceEnabled) ?? true
        wakePhrase = try container.decodeIfPresent(String.self, forKey: .wakePhrase) ?? Self.defaultWake
        speechVolume = try container.decodeIfPresent(Float.self, forKey: .speechVolume) ?? Self.volumeHigh
        depthOpenRaw = try container.decodeIfPresent(Float.self, forKey: .depthOpenRaw) ?? 0
        depthWallRaw = try container.decodeIfPresent(Float.self, forKey: .depthWallRaw) ?? 0
        torchMode = try container.decodeIfPresent(TorchMode.self, forKey: .torchMode) ?? .auto
        collectEnabled = try container.decodeIfPresent(Bool.self, forKey: .collectEnabled) ?? false
        collectIntervalMs = try container.decodeIfPresent(Int64.self, forKey: .collectIntervalMs) ?? CollectCapture.defaultIntervalMs
        collectGps = try container.decodeIfPresent(Bool.self, forKey: .collectGps) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(languageTag, forKey: .languageTag)
        try container.encode(batterySaver, forKey: .batterySaver)
        try container.encode(speechRate, forKey: .speechRate)
        try container.encode(vibrationEnabled, forKey: .vibrationEnabled)
        try container.encode(voiceEnabled, forKey: .voiceEnabled)
        try container.encode(wakePhrase, forKey: .wakePhrase)
        try container.encode(speechVolume, forKey: .speechVolume)
        try container.encode(depthOpenRaw, forKey: .depthOpenRaw)
        try container.encode(depthWallRaw, forKey: .depthWallRaw)
        try container.encode(torchMode, forKey: .torchMode)
        try container.encode(collectEnabled, forKey: .collectEnabled)
        try container.encode(collectIntervalMs, forKey: .collectIntervalMs)
        try container.encode(collectGps, forKey: .collectGps)
    }

    public func normalized() -> GuideSettings {
        var copy = self
        copy.languageTag = Self.languageTags.contains(languageTag) ? languageTag : "en"
        if speechRate != Self.rateSlow && speechRate != Self.rateFast {
            copy.speechRate = Self.rateNormal
        }
        if speechVolume != Self.volumeLow && speechVolume != Self.volumeMid {
            copy.speechVolume = Self.volumeHigh
        }
        let phrase = wakePhrase.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        copy.wakePhrase = phrase.isEmpty ? Self.defaultWake : String(phrase.prefix(48))
        copy.depthOpenRaw = depthOpenRaw.isFinite && depthOpenRaw > 0 ? depthOpenRaw : 0
        copy.depthWallRaw = depthWallRaw.isFinite && depthWallRaw > 0 ? depthWallRaw : 0
        copy.collectIntervalMs = CollectCapture.clampInterval(collectIntervalMs)
        return copy
    }

    public func nextCollectInterval() -> GuideSettings {
        var copy = self
        copy.collectIntervalMs = CollectCapture.nextIntervalMs(collectIntervalMs)
        return copy
    }

    public func depthCalibration() -> DepthCalibration? {
        let saved = DepthCalibration(openRaw: depthOpenRaw, wallRaw: depthWallRaw)
        return saved.usable() ? saved : nil
    }

    public static func frameIntervalMs(batterySaver: Bool) -> Int64 {
        batterySaver ? intervalSaverMs : intervalNormalMs
    }

    public static func depthIntervalMs(batterySaver: Bool) -> Int64 {
        batterySaver ? depthIntervalSaverMs : depthIntervalNormalMs
    }

    public func nextLanguage() -> GuideSettings {
        var copy = self
        let index = Self.languageTags.firstIndex(of: languageTag) ?? 0
        copy.languageTag = Self.languageTags[(index + 1) % Self.languageTags.count]
        return copy
    }

    public func louder() -> GuideSettings {
        var copy = self
        if speechVolume == Self.volumeLow { copy.speechVolume = Self.volumeMid }
        else if speechVolume == Self.volumeMid { copy.speechVolume = Self.volumeHigh }
        return copy
    }

    public func quieter() -> GuideSettings {
        var copy = self
        if speechVolume == Self.volumeHigh { copy.speechVolume = Self.volumeMid }
        else if speechVolume == Self.volumeMid { copy.speechVolume = Self.volumeLow }
        return copy
    }

    public func faster() -> GuideSettings {
        var copy = self
        if speechRate == Self.rateSlow { copy.speechRate = Self.rateNormal }
        else if speechRate == Self.rateNormal { copy.speechRate = Self.rateFast }
        return copy
    }

    public func slower() -> GuideSettings {
        var copy = self
        if speechRate == Self.rateFast { copy.speechRate = Self.rateNormal }
        else if speechRate == Self.rateNormal { copy.speechRate = Self.rateSlow }
        return copy
    }
}
