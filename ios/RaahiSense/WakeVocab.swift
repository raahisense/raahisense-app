import Foundation
import RaahiSenseLogic

/// The Android wake phrase must be words the small English Vosk model knows.
/// The same word list is copied into this app so a phrase accepted on Android
/// is accepted here. Hearing the phrase still uses Apple's on-device recognizer.
enum WakeVocab {
    private static let words: Set<String> = {
        guard let url = Bundle.main.url(forResource: "vosk-small-en-words", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        return Set(text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    }()

    static var listLoaded: Bool { !words.isEmpty }

    static func allows(_ phrase: String) -> Bool {
        let normalized = CommandLexicon.normalize(phrase)
        if normalized.isEmpty { return false }
        let parts = normalized.split(separator: " ").map(String.init)
        if parts.isEmpty || parts.count > 6 { return false }
        guard listLoaded else {
            return parts.allSatisfy { part in
                part.unicodeScalars.allSatisfy { CharacterSet.letters.contains($0) }
            }
        }
        return parts.allSatisfy { words.contains($0) }
    }
}
