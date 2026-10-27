import AVFoundation
import Foundation
import Speech

/// On-device recognition. This is not the Vosk model used on Android.
/// The Vosk iOS library is not published, so the same phrases are given to Apple's
/// on-device recognizer. English is always on. A second recognizer is added when
/// that language is installed on the phone. Nothing is sent to a server.
final class EarEngine {
    var onTranscript: ((String, Bool, Bool) -> Void)?
    var onFailure: ((String) -> Void)?

    private let englishRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var languageRecognizer: SFSpeechRecognizer?
    private let audioEngine = AVAudioEngine()
    private var requests: [LaneRequest] = []
    private var englishPhrases: [String] = ["hey guide"]
    private var languagePhrases: [String] = []
    private var languageTag = "en"
    private var wanted = false
    private var ducked = false
    private var tapInstalled = false
    private var taskGeneration = 0
    private var englishFailures = 0
    private let queue = DispatchQueue(label: "app.raahisense.ear")
    private let stateLock = NSLock()
    private var muted = false

    var onDeviceSupported: Bool {
        englishRecognizer?.supportsOnDeviceRecognition == true
    }

    func start(english: [String], language: [String], languageTag: String) {
        englishPhrases = english
        languagePhrases = language
        self.languageTag = languageTag
        wanted = true
        queue.async { [weak self] in
            self?.startOnQueue()
        }
    }

    func updatePhrases(english: [String], language: [String], languageTag: String) {
        englishPhrases = english
        languagePhrases = language
        self.languageTag = languageTag
        guard wanted else { return }
        queue.async { [weak self] in
            self?.beginTasks()
        }
    }

    /// Ignore transcripts and drop buffered audio. Used while speech is playing.
    func setMuted(_ muted: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            if muted {
                self.stateLock.lock()
                self.muted = true
                self.stateLock.unlock()
                guard self.wanted else { return }
                self.taskGeneration += 1
                self.cancelRequests()
            } else {
                if self.wanted {
                    self.beginTasks()
                }
                self.stateLock.lock()
                self.muted = false
                self.stateLock.unlock()
            }
        }
    }

    func setDucked(_ ducked: Bool) {
        self.ducked = ducked
        queue.async { [weak self] in
            try? self?.applySession()
        }
    }

    func stop() {
        wanted = false
        queue.async { [weak self] in
            self?.stopOnQueue()
        }
    }

    private func startOnQueue() {
        guard let englishRecognizer else {
            onFailure?("status_speech_unavailable")
            return
        }
        guard englishRecognizer.isAvailable, englishRecognizer.supportsOnDeviceRecognition else {
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
            beginTasks()
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
            guard let self else { return }
            self.stateLock.lock()
            let current = self.requests
            self.stateLock.unlock()
            for lane in current {
                lane.request.append(buffer)
            }
        }
        tapInstalled = true
    }

    private func beginTasks() {
        taskGeneration += 1
        let generation = taskGeneration
        cancelRequests()
        var lanes: [LaneRequest] = []
        if let english = makeRequest(
            recognizer: englishRecognizer,
            phrases: englishPhrases,
            languageLane: false,
            generation: generation
        ) {
            lanes.append(english)
        }
        if languageTag != "en", !languagePhrases.isEmpty {
            let code = PhraseBook.speechLanguageCode(languageTag)
            let recognizer = SFSpeechRecognizer(locale: Locale(identifier: code))
            languageRecognizer = recognizer
            if recognizer?.supportsOnDeviceRecognition == true,
               let language = makeRequest(
                recognizer: recognizer,
                phrases: languagePhrases,
                languageLane: true,
                generation: generation
               ) {
                lanes.append(language)
            }
        }
        stateLock.lock()
        requests = lanes
        stateLock.unlock()
    }

    private func makeRequest(
        recognizer: SFSpeechRecognizer?,
        phrases: [String],
        languageLane: Bool,
        generation: Int
    ) -> LaneRequest? {
        guard let recognizer, recognizer.supportsOnDeviceRecognition else { return nil }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        request.contextualStrings = Array(phrases.prefix(80))
        if #available(iOS 16.0, *) {
            request.addsPunctuation = false
        }
        let task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self, generation == self.taskGeneration else { return }
            if let result {
                if !languageLane { self.englishFailures = 0 }
                if self.isMuted() { return }
                let text = result.bestTranscription.formattedString
                if !text.isEmpty {
                    self.onTranscript?(text, result.isFinal, languageLane)
                }
            } else if error != nil, !languageLane {
                self.englishFailures += 1
            }
            let finished = result?.isFinal == true || error != nil
            guard finished, self.wanted, generation == self.taskGeneration else { return }
            if !languageLane && self.englishFailures > 4 {
                self.wanted = false
                self.onFailure?("status_speech_unavailable")
                return
            }
            self.queue.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, self.wanted, generation == self.taskGeneration else { return }
                self.beginTasks()
            }
        }
        return LaneRequest(request: request, task: task, languageLane: languageLane)
    }

    private func cancelRequests() {
        stateLock.lock()
        let current = requests
        requests = []
        stateLock.unlock()
        for lane in current {
            lane.task?.cancel()
            lane.request.endAudio()
        }
    }

    private func isMuted() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return muted
    }

    private func stopOnQueue() {
        stateLock.lock()
        muted = false
        stateLock.unlock()
        taskGeneration += 1
        cancelRequests()
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
    }
}

private struct LaneRequest {
    let request: SFSpeechAudioBufferRecognitionRequest
    let task: SFSpeechRecognitionTask?
    let languageLane: Bool
}

enum EarError: Error {
    case noInput
}
