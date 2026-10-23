import AVFoundation
import Foundation
import Speech

/// On-device English recognition. This is not the Vosk model used on Android.
/// The Vosk iOS library is not published, so the same grammar is given to Apple's
/// on-device recognizer. Nothing is sent to a server: requiresOnDeviceRecognition stays on.
final class EarEngine {
    var onTranscript: ((String, Bool) -> Void)?
    var onFailure: ((String) -> Void)?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var phrases: [String] = ["hey guide"]
    private var wanted = false
    private var ducked = false
    private var tapInstalled = false
    private var taskGeneration = 0
    private var failures = 0
    private let queue = DispatchQueue(label: "app.raahisense.ear")

    var onDeviceSupported: Bool {
        recognizer?.supportsOnDeviceRecognition == true
    }

    func start(phrases: [String]) {
        self.phrases = phrases
        wanted = true
        queue.async { [weak self] in
            self?.startOnQueue()
        }
    }

    func updatePhrases(_ phrases: [String]) {
        self.phrases = phrases
        guard wanted else { return }
        queue.async { [weak self] in
            self?.beginTask()
        }
    }

    func setDucked(_ ducked: Bool) {
        self.ducked = ducked
        queue.async { [weak self] in
            self?.applySession()
        }
    }

    func stop() {
        wanted = false
        queue.async { [weak self] in
            self?.stopOnQueue()
        }
    }

    private func startOnQueue() {
        guard let recognizer else {
            onFailure?("status_speech_unavailable")
            return
        }
        guard recognizer.isAvailable else {
            onFailure?("status_speech_unavailable")
            return
        }
        if !recognizer.supportsOnDeviceRecognition {
            onFailure?("status_speech_unavailable")
            return
        }
        do {
            try applySession()
            try installTapIfNeeded()
            if !audioEngine.isRunning {
                audioEngine.prepare()
                try audioEngine.start()
            }
            beginTask()
        } catch {
            onFailure?("status_mic_unavailable")
        }
    }

    private func applySession() throws {
        let session = AVAudioSession.sharedInstance()
        var options: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .allowBluetooth]
        if ducked {
            options.insert(.duckOthers)
        } else {
            options.insert(.mixWithOthers)
        }
        try session.setCategory(.playAndRecord, mode: .measurement, options: options)
        try session.setActive(true, options: [])
    }

    private func installTapIfNeeded() throws {
        guard !tapInstalled else { return }
        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw EarError.noInput
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        tapInstalled = true
    }

    private func beginTask() {
        taskGeneration += 1
        let generation = taskGeneration
        task?.cancel()
        task = nil
        request?.endAudio()
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        request.contextualStrings = Array(phrases.prefix(80))
        if #available(iOS 16.0, *) {
            request.addsPunctuation = false
        }
        self.request = request
        task = recognizer?.recognitionTask(with: request) { [weak self] result, error in
            guard let self, generation == self.taskGeneration else { return }
            if let result {
                self.failures = 0
                let text = result.bestTranscription.formattedString
                if !text.isEmpty {
                    self.onTranscript?(text, result.isFinal)
                }
            } else if error != nil {
                self.failures += 1
            }
            let finished = result?.isFinal == true || error != nil
            guard finished, self.wanted, generation == self.taskGeneration else { return }
            if self.failures > 4 {
                self.wanted = false
                self.onFailure?("status_speech_unavailable")
                return
            }
            self.queue.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, self.wanted, generation == self.taskGeneration else { return }
                self.beginTask()
            }
        }
    }

    private func stopOnQueue() {
        taskGeneration += 1
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
    }
}

enum EarError: Error {
    case noInput
}
