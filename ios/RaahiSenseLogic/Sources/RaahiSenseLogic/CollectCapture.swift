import Foundation

public enum CollectEvent: String, Codable, CaseIterable {
    case periodic, warning, missed, wrong
}

/// The warning the user just heard. A "wrong" photo keeps this so the
/// false or misidentified detection can be found later.
public struct SpokenWarning: Equatable {
    public var label: String
    public var confidence: Float?
    public var left: Float?
    public var top: Float?
    public var right: Float?
    public var bottom: Float?
    public var spokenAtIso: String

    public init(
        label: String,
        confidence: Float?,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?,
        spokenAtIso: String
    ) {
        self.label = label
        self.confidence = confidence
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
        self.spokenAtIso = spokenAtIso
    }

    public func hasBox() -> Bool {
        left != nil && top != nil && right != nil && bottom != nil
    }
}

public struct GpsReading: Equatable {
    public var latitude: Double
    public var longitude: Double
    public var accuracyMeters: Float?
    public var timeIso: String

    public init(latitude: Double, longitude: Double, accuracyMeters: Float?, timeIso: String) {
        self.latitude = latitude
        self.longitude = longitude
        self.accuracyMeters = accuracyMeters
        self.timeIso = timeIso
    }
}

public struct DepthSummary: Equatable {
    public var aheadMedian: Float?
    public var corridorMin: Float?
    public var corridorMax: Float?
    public var finiteSamples: Int

    public init(aheadMedian: Float?, corridorMin: Float?, corridorMax: Float?, finiteSamples: Int) {
        self.aheadMedian = aheadMedian
        self.corridorMin = corridorMin
        self.corridorMax = corridorMax
        self.finiteSamples = finiteSamples
    }
}

public struct LightingEstimate: Equatable {
    public var meanLuma: Float?
    public var lux: Float?
    public var iso: Int?
    public var exposureTimeNs: Int64?
    public var estimate: String

    public init(meanLuma: Float?, lux: Float?, iso: Int?, exposureTimeNs: Int64?, estimate: String) {
        self.meanLuma = meanLuma
        self.lux = lux
        self.iso = iso
        self.exposureTimeNs = exposureTimeNs
        self.estimate = estimate
    }
}

/// When to save a personal training photo. Warnings keep running either way.
///
/// TODO: Blur faces before any third-party sharing. Export only builds a zip
/// for the share sheet. Nothing is uploaded.
public enum CollectCapture {
    public static let defaultIntervalMs: Int64 = 1_500
    public static let minIntervalMs: Int64 = 500
    public static let maxIntervalMs: Int64 = 10_000
    public static let burstFrames = 3
    public static let jpegQuality = 90
    public static let pollMs: Int64 = 200
    public static let hotThermalStatus = 3
    public static let hotBatteryTenthsC = 420
    public static let folder = "RaahiSense/collect"
    public static let shareNote =
        "TODO: blur faces before any third-party sharing. Personal collection. Nothing is uploaded automatically."

    public static func clampInterval(_ ms: Int64) -> Int64 {
        if ms < minIntervalMs { return minIntervalMs }
        if ms > maxIntervalMs { return maxIntervalMs }
        return ms
    }

    public static func nextIntervalMs(_ current: Int64) -> Int64 {
        switch clampInterval(current) {
        case 1_000: return defaultIntervalMs
        case defaultIntervalMs: return 3_000
        case 3_000: return 1_000
        default: return defaultIntervalMs
        }
    }

    public static func intervalLabel(_ ms: Int64) -> String {
        let clamped = clampInterval(ms)
        if clamped % 1_000 == 0 { return String(clamped / 1_000) }
        return format("%.1f", Double(clamped) / 1_000)
    }

    public static func isHot(thermalStatus: Int?, batteryTenthsC: Int?) -> Bool {
        if let thermalStatus { return thermalStatus >= hotThermalStatus }
        if let batteryTenthsC { return batteryTenthsC >= hotBatteryTenthsC }
        return false
    }

    public static func formatBytes(_ bytes: Int64) -> String {
        let safe = max(0, bytes)
        if safe < 1024 { return "\(safe) B" }
        let kb = Double(safe) / 1024
        if kb < 1024 { return format("%.1f KB", kb) }
        let mb = kb / 1024
        if mb < 1024 { return format("%.1f MB", mb) }
        return format("%.2f GB", mb / 1024)
    }

    public static func sessionDate(epochMs: Int64, timeZone: TimeZone = .current) -> String {
        let parts = parts(epochMs, timeZone)
        return String(format: "%04d-%02d-%02d", parts.year, parts.month, parts.day)
    }

    public static func isoTimestamp(epochMs: Int64, timeZone: TimeZone = .current) -> String {
        let parts = parts(epochMs, timeZone)
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        let offset = timeZone.secondsFromGMT(for: date) / 60
        let sign = offset >= 0 ? "+" : "-"
        let absolute = abs(offset)
        return String(
            format: "%04d-%02d-%02dT%02d:%02d:%02d.%03d%@%02d:%02d",
            parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second, parts.millis,
            sign, absolute / 60, absolute % 60
        )
    }

    public static func fileStem(epochMs: Int64, timeZone: TimeZone, event: CollectEvent, sequence: Int) -> String {
        let parts = parts(epochMs, timeZone)
        let stamp = String(
            format: "%04d%02d%02d-%02d%02d%02d%03d",
            parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second, parts.millis
        )
        let seq = String(format: "%04d", max(0, sequence))
        return "\(stamp)-\(event.rawValue)-\(seq)"
    }

    public static func isSessionDate(_ name: String) -> Bool {
        guard name.count == 10 else { return false }
        let pattern = #"^\d{4}-\d{2}-\d{2}$"#
        return name.range(of: pattern, options: .regularExpression) != nil
    }

    public static func lightingOf(_ scene: SceneLight) -> LightingEstimate {
        let luma = scene.meanLuma.isFinite ? scene.meanLuma : nil
        let lux = scene.lux.flatMap { $0.isFinite ? $0 : nil }
        let estimate: String
        if let lux {
            if lux < LowLight.darkLux { estimate = "dark" }
            else if lux < LowLight.brightLux { estimate = "dim" }
            else { estimate = "bright" }
        } else if let luma {
            if luma < LowLight.darkLuma { estimate = "dark" }
            else if luma < LowLight.ambiguousLuma { estimate = "dim" }
            else if luma < LowLight.brightLuma { estimate = "mixed" }
            else { estimate = "bright" }
        } else {
            estimate = "unknown"
        }
        return LightingEstimate(
            meanLuma: luma,
            lux: lux,
            iso: scene.iso,
            exposureTimeNs: scene.exposureTimeNs,
            estimate: estimate
        )
    }

    public static func summarizeDepth(_ field: DepthField?) -> DepthSummary {
        guard let field, field.width > 0, field.height > 0, field.raw.count == field.width * field.height else {
            return DepthSummary(aheadMedian: nil, corridorMin: nil, corridorMax: nil, finiteSamples: 0)
        }
        let ahead = DepthWarnings.aheadMedian(field)
        let yStart = min(field.height - 1, max(0, Int(DepthWarnings.corridorTop * Float(field.height))))
        let yEnd = min(field.height - 1, max(yStart, Int(DepthWarnings.corridorBottom * Float(field.height))))
        var minValue = Float.greatestFiniteMagnitude
        var maxValue = -Float.greatestFiniteMagnitude
        var count = 0
        if yStart <= yEnd {
            for y in yStart...yEnd {
                for x in 0..<field.width {
                    let value = field.raw[y * field.width + x]
                    if value.isFinite {
                        count += 1
                        if value < minValue { minValue = value }
                        if value > maxValue { maxValue = value }
                    }
                }
            }
        }
        return DepthSummary(
            aheadMedian: ahead.isFinite ? ahead : nil,
            corridorMin: count == 0 ? nil : minValue,
            corridorMax: count == 0 ? nil : maxValue,
            finiteSamples: count
        )
    }

    public static func spokenWarning(
        warning: ObstacleWarning,
        boxes: [DetectionBox],
        spokenAtIso: String
    ) -> SpokenWarning {
        let matched = boxes.filter { box in
            guard let made = WarningLogic.fromBox(box) else { return false }
            return made.label == warning.label &&
                made.position == warning.position &&
                made.closeness == warning.closeness
        }.max { $0.score < $1.score }
            ?? boxes.filter { $0.label.lowercased() == warning.label.lowercased() && $0.score.isFinite }
                .max { $0.score < $1.score }
        let finite = matched.flatMap { box -> DetectionBox? in
            guard box.left.isFinite, box.top.isFinite, box.right.isFinite, box.bottom.isFinite else { return nil }
            return box
        }
        let confidence: Float?
        if let score = matched?.score, score.isFinite {
            confidence = score
        } else {
            confidence = nil
        }
        return SpokenWarning(
            label: warning.label,
            confidence: confidence,
            left: finite?.left,
            top: finite?.top,
            right: finite?.right,
            bottom: finite?.bottom,
            spokenAtIso: spokenAtIso
        )
    }

    public static func sidecarJson(
        timestampIso: String,
        event: CollectEvent,
        detections: [DetectionBox],
        depth: DepthSummary,
        lighting: LightingEstimate,
        gpsEnabled: Bool,
        gps: GpsReading?,
        jpegQuality: Int = CollectCapture.jpegQuality,
        width: Int?,
        height: Int?,
        fullResolution: Bool,
        spokenWarning: SpokenWarning? = nil,
        includeSpokenWarning: Bool = false
    ) -> String {
        let savedGps = gpsEnabled ? gps : nil
        var text = "{\n"
        text += "  \"timestamp\": \(jsonString(timestampIso)),\n"
        text += "  \"event\": \(jsonString(event.rawValue)),\n"
        text += "  \"jpegQuality\": \(jpegQuality),\n"
        text += "  \"fullResolution\": \(fullResolution),\n"
        text += "  \"image\": { \"width\": \(jsonInt(width)), \"height\": \(jsonInt(height)) },\n"
        text += "  \"detections\": [\n"
        for (index, box) in detections.enumerated() {
            text += "    { "
            text += "\"label\": \(jsonString(box.label)), "
            text += "\"confidence\": \(jsonFloat(box.score)), "
            text += "\"box\": { "
            text += "\"left\": \(jsonFloat(box.left)), "
            text += "\"top\": \(jsonFloat(box.top)), "
            text += "\"right\": \(jsonFloat(box.right)), "
            text += "\"bottom\": \(jsonFloat(box.bottom)) "
            text += "} }"
            if index != detections.count - 1 { text += "," }
            text += "\n"
        }
        text += "  ],\n"
        text += "  \"depth\": {\n"
        text += "    \"aheadMedian\": \(jsonFloat(depth.aheadMedian)),\n"
        text += "    \"corridorMin\": \(jsonFloat(depth.corridorMin)),\n"
        text += "    \"corridorMax\": \(jsonFloat(depth.corridorMax)),\n"
        text += "    \"finiteSamples\": \(depth.finiteSamples)\n"
        text += "  },\n"
        text += "  \"lighting\": {\n"
        text += "    \"meanLuma\": \(jsonFloat(lighting.meanLuma)),\n"
        text += "    \"lux\": \(jsonFloat(lighting.lux)),\n"
        text += "    \"iso\": \(jsonInt(lighting.iso)),\n"
        text += "    \"exposureTimeNs\": \(jsonLong(lighting.exposureTimeNs)),\n"
        text += "    \"estimate\": \(jsonString(lighting.estimate))\n"
        text += "  },\n"
        text += "  \"gpsEnabled\": \(gpsEnabled),\n"
        if let savedGps {
            text += "  \"gps\": {\n"
            text += "    \"latitude\": \(jsonDouble(savedGps.latitude, digits: 6)),\n"
            text += "    \"longitude\": \(jsonDouble(savedGps.longitude, digits: 6)),\n"
            text += "    \"accuracyMeters\": \(jsonFloat(savedGps.accuracyMeters, digits: 1)),\n"
            text += "    \"time\": \(jsonString(savedGps.timeIso))\n"
            text += "  },\n"
        } else {
            text += "  \"gps\": null,\n"
        }
        if includeSpokenWarning {
            text += "  \"spokenWarning\": \(spokenWarningJson(spokenWarning)),\n"
        }
        text += "  \"note\": \(jsonString(shareNote))\n"
        text += "}\n"
        return text
    }

    private static let posix = Locale(identifier: "en_US_POSIX")

    private struct ClockParts {
        var year: Int
        var month: Int
        var day: Int
        var hour: Int
        var minute: Int
        var second: Int
        var millis: Int
    }

    private static func parts(_ epochMs: Int64, _ timeZone: TimeZone) -> ClockParts {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let millis = Int(epochMs % 1000)
        return ClockParts(
            year: components.year ?? 1970,
            month: components.month ?? 1,
            day: components.day ?? 1,
            hour: components.hour ?? 0,
            minute: components.minute ?? 0,
            second: components.second ?? 0,
            millis: millis < 0 ? millis + 1000 : millis
        )
    }

    private static func spokenWarningJson(_ warning: SpokenWarning?) -> String {
        guard let warning else { return "null" }
        let box: String
        if warning.hasBox() {
            box = "{ \"left\": \(jsonFloat(warning.left)), \"top\": \(jsonFloat(warning.top)), " +
                "\"right\": \(jsonFloat(warning.right)), \"bottom\": \(jsonFloat(warning.bottom)) }"
        } else {
            box = "null"
        }
        return "{ \"label\": \(jsonString(warning.label)), \"confidence\": \(jsonFloat(warning.confidence)), " +
            "\"box\": \(box), \"spokenAt\": \(jsonString(warning.spokenAtIso)) }"
    }

    private static func jsonString(_ value: String) -> String {
        var out = "\""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }

    private static func jsonFloat(_ value: Float?, digits: Int = 4) -> String {
        guard let value, value.isFinite else { return "null" }
        return format("%.\(digits)f", Double(value))
    }

    private static func jsonDouble(_ value: Double, digits: Int) -> String {
        guard value.isFinite else { return "null" }
        return format("%.\(digits)f", value)
    }

    private static func format(_ template: String, _ args: CVarArg...) -> String {
        String(format: template, locale: posix, arguments: args)
    }

    private static func jsonInt(_ value: Int?) -> String {
        value.map(String.init) ?? "null"
    }

    private static func jsonLong(_ value: Int64?) -> String {
        value.map(String.init) ?? "null"
    }
}

public final class CollectScheduler {
    private let burstFrames: Int
    private let lock = NSLock()
    private var queue: [CollectEvent] = []
    private var enabled = false
    private var lastCaptureAtMs: Int64 = .min

    public init(burstFrames: Int = CollectCapture.burstFrames) {
        self.burstFrames = burstFrames
    }

    public func setEnabled(_ on: Bool) {
        lock.lock()
        defer { lock.unlock() }
        let was = enabled
        enabled = on
        if !on {
            queue.removeAll()
        } else if !was {
            lastCaptureAtMs = .min
            queue.removeAll()
        }
    }

    public func onWarning() { enqueue(.warning, front: false) }

    public func onMissed() { enqueue(.missed, front: true) }

    public func onWrong() { enqueue(.wrong, front: true) }

    public func requeue(_ event: CollectEvent) {
        lock.lock()
        defer { lock.unlock() }
        guard enabled else { return }
        queue.insert(event, at: 0)
        trim()
    }

    public func poll(nowMs: Int64, intervalMs: Int64, hot: Bool, busy: Bool) -> CollectEvent? {
        lock.lock()
        defer { lock.unlock() }
        if !enabled || hot || busy { return nil }
        if !queue.isEmpty { return queue.removeFirst() }
        let interval = CollectCapture.clampInterval(intervalMs)
        let neverSaved = lastCaptureAtMs == .min
        if neverSaved || nowMs - lastCaptureAtMs >= interval { return .periodic }
        return nil
    }

    public func onCaptured(nowMs: Int64) {
        lock.lock()
        lastCaptureAtMs = nowMs
        lock.unlock()
    }

    private func enqueue(_ event: CollectEvent, front: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard enabled else { return }
        for _ in 0..<burstFrames {
            if front { queue.insert(event, at: 0) } else { queue.append(event) }
        }
        trim()
    }

    private func trim() {
        while queue.count > burstFrames * 3 {
            queue.removeFirst()
        }
    }
}

public enum CollectZip {
    /// TODO: Blur faces before any third-party sharing. The app does not upload this zip.
    public static func store(files: [(name: String, data: Data)]) -> Data {
        var locals = Data()
        var central = Data()
        var count: UInt16 = 0
        for file in files {
            let nameData = Data(file.name.utf8)
            let payload = file.data
            let crc = Crc32.checksum(payload)
            let localOffset = UInt32(locals.count)
            appendLocal(&locals, name: nameData, payload: payload, crc: crc)
            appendCentral(&central, name: nameData, payload: payload, crc: crc, offset: localOffset)
            count += 1
        }
        var out = locals
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.appendU32(0x06054b50)
        out.appendU16(0)
        out.appendU16(0)
        out.appendU16(count)
        out.appendU16(count)
        out.appendU32(UInt32(central.count))
        out.appendU32(centralOffset)
        out.appendU16(0)
        return out
    }

    private static func appendLocal(_ data: inout Data, name: Data, payload: Data, crc: UInt32) {
        data.appendU32(0x04034b50)
        data.appendU16(20)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU32(crc)
        data.appendU32(UInt32(payload.count))
        data.appendU32(UInt32(payload.count))
        data.appendU16(UInt16(name.count))
        data.appendU16(0)
        data.append(name)
        data.append(payload)
    }

    private static func appendCentral(_ data: inout Data, name: Data, payload: Data, crc: UInt32, offset: UInt32) {
        data.appendU32(0x02014b50)
        data.appendU16(20)
        data.appendU16(20)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU32(crc)
        data.appendU32(UInt32(payload.count))
        data.appendU32(UInt32(payload.count))
        data.appendU16(UInt16(name.count))
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU16(0)
        data.appendU32(0)
        data.appendU32(offset)
        data.append(name)
    }
}

enum Crc32 {
    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                if crc & 1 == 1 {
                    crc = (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >>= 1
                }
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func appendU16(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func appendU32(_ value: UInt32) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 24) & 0xFF))
    }
}
