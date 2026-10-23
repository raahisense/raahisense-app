import AVFoundation
import Foundation
import ObjectiveC

private var speechEpochKey: UInt8 = 0

final class SpeechEngine: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var finish: (() -> Void)?
    private var epoch = 0
    private(set) var speaking = false

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(
        _ text: String,
        flush: Bool,
        rate: Float,
        volume: Float,
        language: String,
        done: @escaping () -> Void
    ) {
        guard !text.isEmpty else {
            done()
            return
        }
        epoch += 1
        let mine = epoch
        if flush {
            synthesizer.stopSpeaking(at: .immediate)
        }
        let utterance = AVSpeechUtterance(string: text)
        objc_setAssociatedObject(utterance, &speechEpochKey, NSNumber(value: mine), .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        let code = PhraseBook.speechLanguageCode(language)
        if let voice = AVSpeechSynthesisVoice(language: code) {
            utterance.voice = voice
        } else if let english = AVSpeechSynthesisVoice(language: "en-US") {
            utterance.voice = english
        }
        let scaled = AVSpeechUtteranceDefaultSpeechRate * rate
        utterance.rate = min(0.65, max(0.35, scaled))
        utterance.volume = min(1, max(0, volume))
        if #available(iOS 17.0, *) {
            utterance.prefersAssistiveTechnologySettings = false
        }
        speaking = true
        finish = done
        synthesizer.speak(utterance)
    }

    func voiceAvailable(_ language: String) -> Bool {
        AVSpeechSynthesisVoice(language: PhraseBook.speechLanguageCode(language)) != nil
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        guard epoch(of: utterance) == epoch else { return }
        speaking = false
        let callback = finish
        finish = nil
        callback?()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        guard epoch(of: utterance) == epoch else { return }
        speaking = false
        let callback = finish
        finish = nil
        callback?()
    }

    private func epoch(of utterance: AVSpeechUtterance) -> Int? {
        (objc_getAssociatedObject(utterance, &speechEpochKey) as? NSNumber)?.intValue
    }
}
