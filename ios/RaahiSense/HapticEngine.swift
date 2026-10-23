import Foundation
#if canImport(CoreHaptics)
import CoreHaptics
#endif
#if canImport(UIKit)
import UIKit
#endif

/// Three pulses, about 280 ms on and 120 ms off. Same pattern as Android.
final class HapticEngine {
    #if canImport(CoreHaptics)
    private var engine: CHHapticEngine?
    #endif

    func prepare() {
        #if canImport(CoreHaptics)
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { return }
        do {
            let engine = try CHHapticEngine()
            engine.isAutoShutdownEnabled = true
            engine.resetHandler = { [weak self] in
                try? self?.engine?.start()
            }
            try engine.start()
            self.engine = engine
        } catch {
            engine = nil
        }
        #endif
    }

    func pulseUrgent() {
        #if canImport(CoreHaptics)
        if let engine, playPattern(on: engine) { return }
        #endif
        fallback()
    }

    #if canImport(CoreHaptics)
    private func playPattern(on engine: CHHapticEngine) -> Bool {
        let on: TimeInterval = 0.28
        let gap: TimeInterval = 0.12
        var events: [CHHapticEvent] = []
        for index in 0..<3 {
            let start = TimeInterval(index) * (on + gap)
            events.append(
                CHHapticEvent(
                    eventType: .hapticContinuous,
                    parameters: [
                        CHHapticEventParameter(parameterID: .hapticIntensity, value: 1),
                        CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.55),
                    ],
                    relativeTime: start,
                    duration: on
                )
            )
        }
        do {
            try engine.start()
            let pattern = try CHHapticPattern(events: events, parameters: [])
            let player = try engine.makePlayer(with: pattern)
            try player.start(atTime: 0)
            return true
        } catch {
            return false
        }
    }
    #endif

    private func fallback() {
        #if canImport(UIKit)
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        for index in 0..<3 {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(index) * 0.40) {
                generator.notificationOccurred(.warning)
            }
        }
        #endif
    }
}
