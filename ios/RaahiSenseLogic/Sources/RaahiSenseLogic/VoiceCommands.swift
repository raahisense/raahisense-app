import Foundation

public enum VoiceCommand: String, CaseIterable {
    case start, stop, pause, resume, whatIsAhead, repeatLast, louder, quieter, faster, slower, changeLanguage, help
    case startCollecting, stopCollecting, missed, wrong, sendLog
}

public enum VoiceAction: String {
    case ignore, wakeOnly, unknown, start, stop, pause, resume, whatIsAhead, repeatLast, louder, quieter, faster, slower, changeLanguage, help
    case startCollecting, stopCollecting, missed, wrong, sendLog
}

public struct CommandLexicon {
    public var phrases: [VoiceCommand: [String]]

    public init(phrases: [VoiceCommand: [String]]) {
        self.phrases = phrases
    }

    public static let defaultWake = "hey guide"

    public static let english = CommandLexicon(phrases: [
        .start: ["start", "start guide", "begin"],
        .stop: ["stop", "stop app", "exit", "close", "stop guide"],
        .pause: ["pause"],
        .resume: ["resume"],
        .whatIsAhead: ["what is in front of me", "what is ahead", "what is in front"],
        .repeatLast: ["repeat", "say again", "repeat that"],
        .louder: ["louder", "speak louder", "volume up"],
        .quieter: ["quieter", "speak quieter", "softer", "volume down"],
        .faster: ["faster", "speak faster"],
        .slower: ["slower", "speak slower"],
        .changeLanguage: ["change language", "next language"],
        .help: ["help", "list commands", "what can i say"],
        .startCollecting: ["start collecting"],
        .stopCollecting: ["stop collecting"],
        .missed: ["missed"],
        .wrong: ["wrong", "false alarm", "wrong warning"],
        .sendLog: ["send log"],
    ])

    public func withExtra(_ extra: [VoiceCommand: [String]]) -> CommandLexicon {
        var merged: [VoiceCommand: [String]] = [:]
        for command in VoiceCommand.allCases {
            let combined = (extra[command] ?? []) + (phrases[command] ?? [])
            var seen = Set<String>()
            var list: [String] = []
            for phrase in combined.map(Self.normalize) where !phrase.isEmpty && seen.insert(phrase).inserted {
                list.append(phrase)
            }
            merged[command] = list
        }
        return CommandLexicon(phrases: merged)
    }

    public static func pipes(_ raw: String) -> [String] {
        raw.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    public static func normalize(_ raw: String) -> String {
        var lowered = raw.lowercased()
        lowered = lowered.replacingOccurrences(of: "\u{2019}", with: "'")
        lowered = lowered.replacingOccurrences(of: "'", with: "")
        var cleaned = ""
        for scalar in lowered.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) || CharacterSet.whitespaces.contains(scalar) {
                cleaned.append(Character(scalar))
            } else {
                cleaned.append(" ")
            }
        }
        let collapsed = cleaned.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return collapsed.replacingOccurrences(of: "\\bwhats\\b", with: "what is", options: .regularExpression)
    }
}

public enum CommandParser {
    public static func parse(_ transcript: String, lexicon: CommandLexicon = .english) -> VoiceCommand? {
        let text = CommandLexicon.normalize(transcript)
        if text.isEmpty || text == "unk" { return nil }
        let padded = " \(text) "
        var best: VoiceCommand?
        var bestLength = -1
        var bestIndex = Int.max
        for command in VoiceCommand.allCases {
            for phrase in lexicon.phrases[command] ?? [] {
                let normalized = CommandLexicon.normalize(phrase)
                if normalized.isEmpty { continue }
                guard let range = padded.range(of: " \(normalized) ") else { continue }
                let index = padded.distance(from: padded.startIndex, to: range.lowerBound)
                let length = normalized.count
                if length > bestLength || (length == bestLength && index < bestIndex) {
                    best = command
                    bestLength = length
                    bestIndex = index
                }
            }
        }
        return best
    }
}

/// Hindi and Spanish have a small offline command model. Other languages do not.
public enum SpokenEar {
    public static func hasCommandModel(_ languageTag: String) -> Bool {
        languageTag == "hi" || languageTag == "es"
    }

    public static func nativeWakes(_ languageTag: String) -> [String] {
        switch languageTag {
        case "hi": return ["हे गाइड"]
        case "es": return ["oye guía"]
        default: return []
        }
    }
}

/// Stop and the training commands stay on the English recognizer, so they win.
/// An everyday command from the selected language wins over English.
public enum EarChoice {
    private static let englishPinned: Set<VoiceAction> = [
        .stop, .startCollecting, .stopCollecting, .missed, .wrong, .sendLog,
    ]

    public static func prefer(language: VoiceAction?, english: VoiceAction) -> VoiceAction {
        if englishPinned.contains(english) { return english }
        if let language, isCommand(language) { return language }
        if isCommand(english) { return english }
        if language == .wakeOnly || english == .wakeOnly { return .wakeOnly }
        if language == .unknown || english == .unknown { return .unknown }
        return .ignore
    }

    private static func isCommand(_ action: VoiceAction) -> Bool {
        switch action {
        case .ignore, .wakeOnly, .unknown: return false
        default: return true
        }
    }
}

public enum VoiceInterpreter {
    public static func interpret(
        _ transcript: String,
        lexicon: CommandLexicon = .english,
        wakePhrase: String = CommandLexicon.defaultWake,
        requireWake: Bool,
        allowBareCorrection: Bool = false,
        extraWakePhrases: [String] = []
    ) -> VoiceAction {
        let text = CommandLexicon.normalize(transcript)
        if text.isEmpty || text == "unk" {
            return requireWake ? .ignore : .unknown
        }
        let wake = matchingWake(text, wakePhrase: wakePhrase, extra: extraWakePhrases)
        let woke = wake != nil
        let remainder = wake.map { removeFirst(text, $0) } ?? text
        if requireWake && !woke {
            if allowBareCorrection {
                if let bare = CommandParser.parse(text, lexicon: lexicon), bare == .wrong || bare == .missed {
                    return bare.action
                }
            }
            return .ignore
        }
        if remainder.isEmpty { return .wakeOnly }
        guard let command = CommandParser.parse(remainder, lexicon: lexicon) else {
            return requireWake ? .wakeOnly : .unknown
        }
        return command.action
    }

    private static func matchingWake(_ text: String, wakePhrase: String, extra: [String]) -> String? {
        let padded = " \(text) "
        var best: String?
        var bestIndex = Int.max
        for candidate in [wakePhrase] + extra {
            let wake = CommandLexicon.normalize(candidate)
            if wake.isEmpty { continue }
            guard let range = padded.range(of: " \(wake) ") else { continue }
            let index = padded.distance(from: padded.startIndex, to: range.lowerBound)
            let longer = best != nil && index == bestIndex && wake.count > (best?.count ?? 0)
            if index < bestIndex || longer {
                best = wake
                bestIndex = index
            }
        }
        return best
    }

    private static func removeFirst(_ text: String, _ phrase: String) -> String {
        let padded = " \(text) "
        let target = " \(phrase) "
        guard let range = padded.range(of: target) else { return text }
        let merged = padded[..<range.lowerBound] + " " + padded[range.upperBound...]
        return merged.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}

/// The microphone hears the speaker. A warning such as "stop sign" must not
/// be treated as a command, so recognition is dropped while speech is playing
/// and for a short tail after it ends.
///
/// Right after a warning, `acceptsCorrection` opens a short window. The user
/// says "wrong" or "missed" as soon as the warning finishes. Ordinary commands
/// still wait out the tail.
public enum RecognitionGate {
    public static let tailMs: Int64 = 400
    public static let correctionWindowMs: Int64 = 2_500

    public static func accepts(speechActive: Bool, nowMs: Int64, quietUntilMs: Int64) -> Bool {
        if speechActive { return false }
        return nowMs >= quietUntilMs
    }

    public static func quietUntil(speechEndedAtMs: Int64) -> Int64 {
        speechEndedAtMs + tailMs
    }

    public static func correctionUntil(warningEndedAtMs: Int64) -> Int64 {
        warningEndedAtMs + correctionWindowMs
    }

    /// True only while speech is idle and the post-warning window is still open.
    public static func acceptsCorrection(speechActive: Bool, nowMs: Int64, correctionUntilMs: Int64) -> Bool {
        if speechActive { return false }
        return nowMs <= correctionUntilMs
    }
}

private extension VoiceCommand {
    var action: VoiceAction {
        switch self {
        case .start: return .start
        case .stop: return .stop
        case .pause: return .pause
        case .resume: return .resume
        case .whatIsAhead: return .whatIsAhead
        case .repeatLast: return .repeatLast
        case .louder: return .louder
        case .quieter: return .quieter
        case .faster: return .faster
        case .slower: return .slower
        case .changeLanguage: return .changeLanguage
        case .help: return .help
        case .startCollecting: return .startCollecting
        case .stopCollecting: return .stopCollecting
        case .missed: return .missed
        case .wrong: return .wrong
        case .sendLog: return .sendLog
        }
    }
}
