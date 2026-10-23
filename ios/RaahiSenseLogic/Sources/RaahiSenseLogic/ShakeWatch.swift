import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// A hard shake is two strong opposite jerks close together.
public final class ShakeWatch {
    private let thresholdMps2: Float
    private let releaseMps2: Float
    private let windowMs: Int64
    private let cooldownMs: Int64
    private var lastPeakAt: Int64 = 0
    private var lastSign = 0
    private var needRelease = false
    private var cooldownUntil: Int64 = 0

    public init(
        thresholdMps2: Float = 28,
        releaseMps2: Float = 14,
        windowMs: Int64 = 700,
        cooldownMs: Int64 = 3_000
    ) {
        self.thresholdMps2 = thresholdMps2
        self.releaseMps2 = releaseMps2
        self.windowMs = windowMs
        self.cooldownMs = cooldownMs
    }

    public func onSample(timeMs: Int64, x: Float, y: Float, z: Float) -> Bool {
        if timeMs < cooldownUntil { return false }
        let magnitude = sqrtf(x * x + y * y + z * z)
        if needRelease {
            if magnitude < releaseMps2 { needRelease = false }
            return false
        }
        if magnitude < thresholdMps2 { return false }
        let sign = dominantSign(x: x, y: y, z: z)
        let opposite = lastSign != 0 && sign != lastSign && timeMs - lastPeakAt <= windowMs
        lastPeakAt = timeMs
        lastSign = sign
        needRelease = true
        if !opposite { return false }
        cooldownUntil = timeMs + cooldownMs
        lastSign = 0
        return true
    }

    private func dominantSign(x: Float, y: Float, z: Float) -> Int {
        let ax = abs(x)
        let ay = abs(y)
        let az = abs(z)
        if ax >= ay && ax >= az { return x >= 0 ? 1 : -1 }
        if ay >= az { return y >= 0 ? 1 : -1 }
        return z >= 0 ? 1 : -1
    }
}
