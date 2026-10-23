import Foundation
#if os(Linux)
import Glibc
#else
import Darwin
#endif

/// Relative depth. A larger raw value is farther. NaN samples are holes.
public enum DepthWarnings {
    public static let labelUnknown = "depth_unknown"
    public static let labelWall = "depth_wall"
    public static let labelApproaching = "depth_approaching"
    public static let corridorTop: Float = 0.42
    public static let corridorBottom: Float = 0.88
    public static let staticDebounce = 3
    public static let approachFrames = 4
    public static let approachShrink: Float = 0.82
    public static let approachStepSlack: Float = 1.01
    public static let approachPriority: Float = 20
    public static let wallSpanLeft: Float = 0.22
    public static let wallSpanRight: Float = 0.78
    public static let wallCloseFraction: Float = 0.55
    public static let wallThirdFraction: Float = 0.35
    public static let wallFlatness: Float = 0.12
    public static let uncalibratedVeryClose: Float = 0.55
    public static let uncalibratedNear: Float = 0.72
    public static let uncalibratedSpread: Float = 0.18
    public static let calVeryClose: Float = 0.85
    public static let calNear: Float = 0.45
    public static let explainCover: Float = 0.35
    public static let priorityWallVeryClose: Float = 8.5
    public static let priorityUnknownVeryCloseAhead: Float = 8
    public static let priorityUnknownVeryCloseSide: Float = 7
    public static let priorityWallNear: Float = 6
    public static let priorityUnknownNearAhead: Float = 5.5
    public static let priorityUnknownNearSide: Float = 5
    public static let positions: [HorizontalPosition] = [.left, .ahead, .right]

    public static func phraseName(_ warning: ObstacleWarning) -> String? {
        switch warning.label {
        case labelUnknown:
            switch (warning.closeness, warning.position) {
            case (.veryClose, .left): return "depth_unknown_very_close_left"
            case (.veryClose, .ahead): return "depth_unknown_very_close_ahead"
            case (.veryClose, .right): return "depth_unknown_very_close_right"
            case (.near, .left): return "depth_unknown_near_left"
            case (.near, .ahead): return "depth_unknown_near_ahead"
            case (.near, .right): return "depth_unknown_near_right"
            default: return nil
            }
        case labelWall:
            switch warning.closeness {
            case .veryClose: return "depth_wall_very_close"
            case .near: return "depth_wall_near"
            default: return nil
            }
        case labelApproaching:
            switch warning.position {
            case .left: return "depth_approaching_left"
            case .ahead: return "depth_approaching_ahead"
            case .right: return "depth_approaching_right"
            }
        default:
            return nil
        }
    }

    public static func aheadMedian(_ field: DepthField) -> Float {
        columnMedian(corridorSamples(field), .ahead)
    }

    static func corridorSamples(_ field: DepthField) -> [DepthSample] {
        guard field.width > 0, field.height > 0, field.raw.count == field.width * field.height else { return [] }
        let yStart = min(field.height - 1, max(0, Int(corridorTop * Float(field.height))))
        let yEnd = min(field.height - 1, max(yStart, Int(corridorBottom * Float(field.height))))
        let xStride = max(1, field.width / 48)
        let yStride = max(1, (yEnd - yStart + 1) / 32)
        var out: [DepthSample] = []
        var y = yStart
        while y <= yEnd {
            var x = 0
            while x < field.width {
                let value = field.raw[y * field.width + x]
                if value.isFinite {
                    out.append(DepthSample(nx: (Float(x) + 0.5) / Float(field.width), value: value))
                }
                x += xStride
            }
            y += yStride
        }
        return out
    }

    static func columnMedian(_ samples: [DepthSample], _ position: HorizontalPosition) -> Float {
        median(samples.filter { columnOf($0.nx) == position }.map(\.value))
    }

    static func columnOf(_ nx: Float) -> HorizontalPosition {
        if nx < WarningLogic.leftMax { return .left }
        if nx > WarningLogic.rightMin { return .right }
        return .ahead
    }

    static func scaleOf(_ samples: [DepthSample], _ calibration: DepthCalibration?) -> DepthScale {
        if let saved = calibration, saved.usable() {
            let span = saved.openRaw - saved.wallRaw
            return DepthScale(
                veryCloseAt: saved.openRaw - calVeryClose * span,
                nearAt: saved.openRaw - calNear * span,
                active: true
            )
        }
        let values = samples.map(\.value).filter(\.isFinite).sorted()
        guard values.count >= 4 else { return .inactive }
        let p25 = percentile(values, 0.25)
        let p75 = percentile(values, 0.75)
        let mid = median(values)
        guard mid.isFinite, mid > 0 else { return .inactive }
        if (p75 - p25) / mid < uncalibratedSpread { return .inactive }
        return DepthScale(
            veryCloseAt: p75 * uncalibratedVeryClose,
            nearAt: p75 * uncalibratedNear,
            active: true
        )
    }

    static func closeness(_ value: Float, _ scale: DepthScale) -> Closeness {
        guard scale.active, value.isFinite else { return .far }
        if value <= scale.veryCloseAt { return .veryClose }
        if value <= scale.nearAt { return .near }
        return .far
    }

    public static func explains(
        _ box: DetectionBox,
        _ position: HorizontalPosition,
        scoreThreshold: Float = WarningLogic.scoreThreshold
    ) -> Bool {
        let label = box.label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard PhraseIds.objects.contains(label), box.score.isFinite, box.score >= scoreThreshold else { return false }
        let roiLeft: Float
        let roiRight: Float
        switch position {
        case .left:
            roiLeft = 0
            roiRight = WarningLogic.leftMax
        case .ahead:
            roiLeft = WarningLogic.leftMax
            roiRight = WarningLogic.rightMin
        case .right:
            roiLeft = WarningLogic.rightMin
            roiRight = 1
        }
        let roiArea = (roiRight - roiLeft) * (corridorBottom - corridorTop)
        guard roiArea > 0 else { return false }
        let overlapW = min(box.right, roiRight) - max(box.left, roiLeft)
        let overlapH = min(box.bottom, corridorBottom) - max(box.top, corridorTop)
        guard overlapW > 0, overlapH > 0 else { return false }
        return overlapW * overlapH / roiArea >= explainCover
    }

    static func wallCandidate(_ samples: [DepthSample], _ scale: DepthScale) -> WallRead? {
        guard scale.active, samples.count >= 8 else { return nil }
        let close = samples.filter { closeness($0.value, scale) != .far }
        guard close.count >= 8 else { return nil }
        guard Float(close.count) / Float(samples.count) >= wallCloseFraction else { return nil }
        let minX = close.map(\.nx).min() ?? 1
        let maxX = close.map(\.nx).max() ?? 0
        guard minX < wallSpanLeft, maxX > wallSpanRight else { return nil }
        let thirds: [(Float, Float)] = [(0, 1.0 / 3.0), (1.0 / 3.0, 2.0 / 3.0), (2.0 / 3.0, 1.01)]
        for (from, to) in thirds {
            let band = samples.filter { $0.nx >= from && $0.nx < to }
            if band.isEmpty { return nil }
            let fraction = Float(band.filter { closeness($0.value, scale) != .far }.count) / Float(band.count)
            if fraction < wallThirdFraction { return nil }
        }
        let closeValues = close.map(\.value)
        let mid = median(closeValues)
        guard mid.isFinite, mid != 0 else { return nil }
        if stdev(closeValues) / abs(mid) > wallFlatness { return nil }
        let howClose = closeness(median(samples.map(\.value)), scale)
        guard howClose != .far else { return nil }
        return WallRead(closeness: howClose)
    }

    static func median(_ values: [Float]) -> Float {
        guard !values.isEmpty else { return .nan }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        if sorted.count % 2 == 1 { return sorted[mid] }
        return (sorted[mid - 1] + sorted[mid]) / 2
    }

    private static func percentile(_ sorted: [Float], _ fraction: Float) -> Float {
        guard !sorted.isEmpty else { return .nan }
        let rank = min(1, max(0, fraction)) * Float(sorted.count - 1)
        let low = Int(rank)
        let high = min(low + 1, sorted.count - 1)
        let weight = rank - Float(low)
        return sorted[low] * (1 - weight) + sorted[high] * weight
    }

    private static func stdev(_ values: [Float]) -> Float {
        guard !values.isEmpty else { return 0 }
        let mean = values.reduce(0, +) / Float(values.count)
        var acc = 0.0
        for value in values {
            let delta = Double(value - mean)
            acc += delta * delta
        }
        return Float(sqrt(acc / Double(values.count)))
    }
}

public struct DepthField {
    public var width: Int
    public var height: Int
    public var raw: [Float]

    public init(width: Int, height: Int, raw: [Float]) {
        self.width = width
        self.height = height
        self.raw = raw
    }
}

public enum DepthFields {
    public static func rotate(_ field: DepthField, degrees: Int) -> DepthField {
        let turns = ((degrees % 360) + 360) % 360 / 90
        var current = field
        for _ in 0..<turns { current = rotate90(current) }
        return current
    }

    private static func rotate90(_ field: DepthField) -> DepthField {
        let width = field.width
        let height = field.height
        var raw = [Float](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                let nx = height - 1 - y
                let ny = x
                raw[ny * height + nx] = field.raw[y * width + x]
            }
        }
        return DepthField(width: height, height: width, raw: raw)
    }
}

public struct DepthCalibration: Equatable {
    public var openRaw: Float
    public var wallRaw: Float

    public init(openRaw: Float, wallRaw: Float) {
        self.openRaw = openRaw
        self.wallRaw = wallRaw
    }

    public func usable() -> Bool {
        openRaw.isFinite && wallRaw.isFinite && wallRaw > 0 && openRaw > wallRaw * 1.05
    }
}

struct DepthSample {
    var nx: Float
    var value: Float
}

struct DepthScale {
    var veryCloseAt: Float
    var nearAt: Float
    var active: Bool

    static let inactive = DepthScale(veryCloseAt: .nan, nearAt: .nan, active: false)
}

struct WallRead {
    var closeness: Closeness
}

public final class DepthTracker {
    private var history: [[Float]] = []
    private var closeStreak = [0, 0, 0]
    private var wallStreak = 0

    public init() {}

    public func reset() {
        history.removeAll()
        closeStreak = [0, 0, 0]
        wallStreak = 0
    }

    public func update(
        _ field: DepthField,
        detections: [DetectionBox],
        calibration: DepthCalibration?,
        scoreThreshold: Float = WarningLogic.scoreThreshold
    ) -> [ObstacleWarning] {
        let samples = DepthWarnings.corridorSamples(field)
        let scale = DepthWarnings.scaleOf(samples, calibration)
        let medians = (0..<3).map { DepthWarnings.columnMedian(samples, DepthWarnings.positions[$0]) }
        history.append(medians)
        while history.count > DepthWarnings.approachFrames {
            history.removeFirst()
        }
        let explained = (0..<3).map { index in
            detections.contains { DepthWarnings.explains($0, DepthWarnings.positions[index], scoreThreshold: scoreThreshold) }
        }
        let wall = DepthWarnings.wallCandidate(samples, scale)
        let wallBlocked = explained[1] || (explained[0] && explained[2])
        if wall != nil && !wallBlocked {
            wallStreak += 1
        } else {
            wallStreak = 0
        }
        let staticClose: [Closeness?] = (0..<3).map { index in
            let how = DepthWarnings.closeness(medians[index], scale)
            if !explained[index] && how != .far { return how }
            return nil
        }
        for index in 0..<3 {
            closeStreak[index] = staticClose[index] != nil ? closeStreak[index] + 1 : 0
        }

        var warnings: [ObstacleWarning] = []
        if let approaching = approachingWarning(explained: explained, scale: scale) {
            warnings.append(approaching)
        }
        let wallReady = wall != nil && !wallBlocked && wallStreak >= DepthWarnings.staticDebounce
        if wallReady, let wall {
            warnings.append(wallWarning(wall.closeness))
        } else {
            for index in 0..<3 {
                guard let how = staticClose[index], closeStreak[index] >= DepthWarnings.staticDebounce else { continue }
                warnings.append(unknownWarning(DepthWarnings.positions[index], how))
            }
        }
        return warnings.sorted { lhs, rhs in
            if lhs.priority != rhs.priority { return lhs.priority > rhs.priority }
            return lhs.label < rhs.label
        }
    }

    private func approachingWarning(explained: [Bool], scale: DepthScale) -> ObstacleWarning? {
        guard history.count >= DepthWarnings.approachFrames else { return nil }
        var hits: [(Int, Float)] = []
        for index in 0..<3 {
            if explained[index] { continue }
            let series = history.map { $0[index] }
            if series.contains(where: { !$0.isFinite || $0 <= 0 }) { continue }
            let shrinking = (1..<series.count).allSatisfy { step in
                series[step] < series[step - 1] * DepthWarnings.approachStepSlack
            }
            if !shrinking { continue }
            let oldest = series[0]
            let newest = series[series.count - 1]
            if newest > oldest * DepthWarnings.approachShrink { continue }
            if DepthWarnings.closeness(newest, scale) == .far { continue }
            hits.append((index, newest / oldest))
        }
        guard !hits.isEmpty else { return nil }
        let index: Int
        if hits.contains(where: { $0.0 == 1 }) {
            index = 1
        } else if hits.count >= 2 && hits.contains(where: { $0.0 == 0 }) && hits.contains(where: { $0.0 == 2 }) {
            index = 1
        } else {
            index = hits.min(by: { $0.1 < $1.1 })?.0 ?? 0
        }
        return ObstacleWarning(
            label: DepthWarnings.labelApproaching,
            position: DepthWarnings.positions[index],
            closeness: .veryClose,
            priority: DepthWarnings.approachPriority,
            urgent: true
        )
    }

    private func wallWarning(_ closeness: Closeness) -> ObstacleWarning {
        let very = closeness == .veryClose
        return ObstacleWarning(
            label: DepthWarnings.labelWall,
            position: .ahead,
            closeness: very ? .veryClose : .near,
            priority: very ? DepthWarnings.priorityWallVeryClose : DepthWarnings.priorityWallNear,
            urgent: very
        )
    }

    private func unknownWarning(_ position: HorizontalPosition, _ closeness: Closeness) -> ObstacleWarning {
        let ahead = position == .ahead
        let very = closeness == .veryClose
        let priority: Float
        if very && ahead {
            priority = DepthWarnings.priorityUnknownVeryCloseAhead
        } else if very {
            priority = DepthWarnings.priorityUnknownVeryCloseSide
        } else if ahead {
            priority = DepthWarnings.priorityUnknownNearAhead
        } else {
            priority = DepthWarnings.priorityUnknownNearSide
        }
        return ObstacleWarning(
            label: DepthWarnings.labelUnknown,
            position: position,
            closeness: very ? .veryClose : .near,
            priority: priority,
            urgent: very && ahead
        )
    }
}
