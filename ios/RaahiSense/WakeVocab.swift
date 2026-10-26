import Foundation
import RaahiSenseLogic

/// A wake phrase is accepted when every word is in the English list, or every
/// word is in the Hindi or Spanish list for the selected language.
/// Hearing the phrase still uses Apple's on-device recognizer.
enum WakeVocab {
    private static let english: Set<String> = load("vosk-small-en-words")
    private static let hindi: Set<String> = load("vosk-small-hi-words")
    private static let spanish: Set<String> = load("vosk-small-es-words")

    static var listLoaded: Bool { !english.isEmpty }

    static func allows(_ phrase: String, languageTag: String) -> Bool {
        if phraseIn(english, phrase) { return true }
        switch languageTag {
        case "hi": return phraseIn(hindi, phrase)
        case "es": return phraseIn(spanish, phrase)
        default: return false
        }
    }

    private static func phraseIn(_ words: Set<String>, _ phrase: String) -> Bool {
        let normalized = CommandLexicon.normalize(phrase)
        if normalized.isEmpty { return false }
        let parts = normalized.split(separator: " ").map(String.init)
        if parts.isEmpty || parts.count > 6 { return false }
        guard !words.isEmpty else {
            return parts.allSatisfy { part in
                part.unicodeScalars.allSatisfy { CharacterSet.letters.contains($0) }
            }
        }
        return parts.allSatisfy { words.contains($0) }
    }

    private static func load(_ resource: String) -> Set<String> {
        guard let url = Bundle.main.url(forResource: resource, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        return Set(
            text.split(whereSeparator: \.isNewline)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        )
    }
}
