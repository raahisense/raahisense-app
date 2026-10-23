import Foundation

public enum TorchMode: String, Codable, CaseIterable {
    case auto, on, off

    public init(persisted raw: String?) {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "on", "always": self = .on
        case "off": self = .off
        default: self = .auto
        }
    }
}

public struct SceneLight: Equatable {
    public var meanLuma: Float
    public var lux: Float?
    public var iso: Int?
    public var exposureTimeNs: Int64?

    public init(meanLuma: Float, lux: Float? = nil, iso: Int? = nil, exposureTimeNs: Int64? = nil) {
        self.meanLuma = meanLuma
        self.lux = lux
        self.iso = iso
        self.exposureTimeNs = exposureTimeNs
    }

    public var measured: Bool { meanLuma.isFinite }
}

public struct LightDecision: Equatable {
    public var torchOn: Bool
    public var phraseResName: String?
    public var lowLight: Bool
}

public enum LowLight {
    public static let phraseTurningOn = "light_turning_on"
    public static let phraseTooDark = "light_too_dark"
    public static let scoreNormal: Float = WarningLogic.scoreThreshold
    public static let scoreLow: Float = 0.40
    public static let darkHoldMs: Int64 = 2_500
    public static let brightHoldMs: Int64 = 3_000
    public static let torchSettleMs: Int64 = 2_000
    public static let cautionIntervalMs: Int64 = 60_000
    public static let darkLuma: Float = 0.16
    public static let ambiguousLuma: Float = 0.28
    public static let brightLuma: Float = 0.38
    public static let clearBrightLuma: Float = 0.55
    public static let darkLux: Float = 8
    public static let brightLux: Float = 40
    public static let highIso = 800
    public static let longExposureNs: Int64 = 40_000_000

    public static func scoreThreshold(lowLight: Bool) -> Float {
        lowLight ? scoreLow : scoreNormal
    }

    public static func isDark(_ scene: SceneLight) -> Bool {
        guard scene.measured else { return false }
        if scene.meanLuma >= clearBrightLuma { return false }
        if scene.meanLuma < darkLuma { return true }
        if let lux = scene.lux, lux.isFinite, lux < darkLux { return true }
        if scene.meanLuma < ambiguousLuma && exposureLooksDark(scene) { return true }
        return false
    }

    public static func isBright(_ scene: SceneLight) -> Bool {
        guard scene.measured else { return false }
        if let lux = scene.lux, lux.isFinite, lux < brightLux { return false }
        if scene.meanLuma >= clearBrightLuma { return true }
        if scene.meanLuma < brightLuma { return false }
        return !exposureLooksDark(scene)
    }

    private static func exposureLooksDark(_ scene: SceneLight) -> Bool {
        if let iso = scene.iso, iso >= highIso { return true }
        if let exposure = scene.exposureTimeNs, exposure >= longExposureNs { return true }
        return false
    }
}

public final class LowLightController {
    private var darkSinceMs: Int64?
    private var brightSinceMs: Int64?
    private var torchOn = false
    private var torchOnSinceMs: Int64?
    private var announcedTurningOn = false
    private var lastCautionAtMs: Int64?

    public init() {}

    public func reset() {
        darkSinceMs = nil
        brightSinceMs = nil
        torchOn = false
        torchOnSinceMs = nil
        announcedTurningOn = false
        lastCautionAtMs = nil
    }

    public func step(scene: SceneLight, mode: TorchMode, torchAvailable: Bool, nowMs: Int64) -> LightDecision {
        let dark = LowLight.isDark(scene)
        let bright = LowLight.isBright(scene)
        if dark {
            if darkSinceMs == nil { darkSinceMs = nowMs }
            brightSinceMs = nil
        } else if bright {
            if brightSinceMs == nil { brightSinceMs = nowMs }
            darkSinceMs = nil
        } else {
            darkSinceMs = nil
            brightSinceMs = nil
        }

        var phrase: String?
        switch mode {
        case .on:
            if torchAvailable {
                if !torchOn { torchOnSinceMs = nowMs }
                torchOn = true
            } else {
                torchOn = false
                torchOnSinceMs = nil
            }
            announcedTurningOn = false
        case .off:
            torchOn = false
            torchOnSinceMs = nil
            announcedTurningOn = false
        case .auto:
            if !torchAvailable {
                torchOn = false
                torchOnSinceMs = nil
                announcedTurningOn = false
            } else {
                let darkFor = darkSinceMs.map { nowMs - $0 } ?? 0
                let brightFor = brightSinceMs.map { nowMs - $0 } ?? 0
                if !torchOn && darkFor >= LowLight.darkHoldMs {
                    torchOn = true
                    torchOnSinceMs = nowMs
                    if !announcedTurningOn {
                        phrase = LowLight.phraseTurningOn
                        announcedTurningOn = true
                    }
                } else if torchOn && brightFor >= LowLight.brightHoldMs {
                    torchOn = false
                    torchOnSinceMs = nil
                    announcedTurningOn = false
                }
            }
        }

        let lowLight = dark || (scene.measured && torchOn && !bright)
        if phrase == nil && shouldCaution(mode: mode, torchAvailable: torchAvailable, dark: dark, nowMs: nowMs) {
            phrase = LowLight.phraseTooDark
            lastCautionAtMs = nowMs
        }
        return LightDecision(torchOn: torchOn && torchAvailable, phraseResName: phrase, lowLight: lowLight)
    }

    private func shouldCaution(mode: TorchMode, torchAvailable: Bool, dark: Bool, nowMs: Int64) -> Bool {
        guard dark else { return false }
        if let previous = lastCautionAtMs, nowMs - previous < LowLight.cautionIntervalMs { return false }
        let darkFor = darkSinceMs.map { nowMs - $0 } ?? 0
        guard darkFor >= LowLight.darkHoldMs else { return false }
        if mode == .off || !torchAvailable { return true }
        guard torchOn else { return false }
        let onFor = torchOnSinceMs.map { nowMs - $0 } ?? 0
        return onFor >= LowLight.torchSettleMs
    }
}
