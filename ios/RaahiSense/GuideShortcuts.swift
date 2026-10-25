import AppIntents
import Foundation

/// Siri Shortcuts for a blind user: "Start RaahiSense" and "Stop RaahiSense".
/// Stop uses the same full shutdown as the Stop RaahiSense button.
enum GuideShortcutCommand {
    case start
    case stop
}

final class GuideCommandCenter {
    static let shared = GuideCommandCenter()

    private weak var session: GuideSession?
    private var pending: GuideShortcutCommand?

    func bind(_ session: GuideSession) {
        DispatchQueue.main.async {
            self.session = session
            self.deliver()
        }
    }

    func start() {
        DispatchQueue.main.async {
            self.pending = .start
            self.deliver()
        }
    }

    func stop() {
        DispatchQueue.main.async {
            self.pending = .stop
            self.deliver()
        }
    }

    private func deliver() {
        guard let session, let command = pending else { return }
        pending = nil
        switch command {
        case .start:
            session.startFromAssistant()
        case .stop:
            session.prepare()
            session.stopEverything()
        }
    }
}

struct StartRaahiSenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Start RaahiSense"
    static var description = IntentDescription("Start obstacle warnings.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        GuideCommandCenter.shared.start()
        return .result()
    }
}

struct StopRaahiSenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop RaahiSense"
    static var description = IntentDescription("Stop RaahiSense. Stops warnings, the camera, and listening.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        GuideCommandCenter.shared.stop()
        return .result()
    }
}

struct RaahiSenseShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRaahiSenseIntent(),
            phrases: [
                "Start \(.applicationName)",
                "Start guidance in \(.applicationName)",
            ],
            shortTitle: "Start RaahiSense",
            systemImageName: "figure.walk"
        )
        AppShortcut(
            intent: StopRaahiSenseIntent(),
            phrases: [
                "Stop \(.applicationName)",
                "Close \(.applicationName)",
            ],
            shortTitle: "Stop RaahiSense",
            systemImageName: "stop.circle"
        )
    }
}
