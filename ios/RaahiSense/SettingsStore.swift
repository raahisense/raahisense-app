import Foundation
import RaahiSenseLogic

enum SettingsStore {
    private static let key = "app.raahisense.guide.settings"

    static func load() -> GuideSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode(GuideSettings.self, from: data) else {
            return GuideSettings()
        }
        return decoded.normalized()
    }

    static func save(_ settings: GuideSettings) {
        let normalized = settings.normalized()
        guard let data = try? JSONEncoder().encode(normalized) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
