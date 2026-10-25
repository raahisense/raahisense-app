import AVFoundation
import CoreMotion
import Foundation
import RaahiSenseLogic
import Speech
import UIKit

/// Wires the camera, the shared warning logic, speech, haptics, and the wake word.
/// The screen stays on while guiding. iOS still stops the camera if the screen locks
/// or the app leaves the foreground. That limit is spoken and shown, not worked around.
final class GuideSession: ObservableObject {
    @Published var guiding = false
    @Published var statusText = ""
    @Published var settings = GuideSettings()
    @Published var cameraAllowed = false
    @Published var capturing = false
    @Published var showAbout = false

    let speech = SpeechEngine()
    private let camera = CameraEngine()
    private let ear = EarEngine()
    private let haptics = HapticEngine()
    private let motion = CMMotionManager()
    private let shake = ShakeWatch()
    private let pipeline = DispatchQueue(label: "app.raahisense.pipeline")
    private let depthQueue = DispatchQueue(label: "app.raahisense.depth")

    private var models: ModelEngine?
    private var modelFailed = false
    private let throttle = WarningThrottle()
    private let depthTracker = DepthTracker()
    private let lowLight = LowLightController()
    private var scoreThreshold = WarningLogic.scoreThreshold
    private var latestDepth: [ObstacleWarning] = []
    private var lastRanked: [ObstacleWarning] = []
    private var lastWarningPhrase = ""
    private var aheadMedian = Float.nan
    private var lastFrameAt: Int64 = 0
    private var lastDepthAt: Int64 = 0
    private var depthBusy = false
    private var appliedTorch: Bool?
    private var appliedBoost: Bool?
    private var frameErrors = 0
    private var toldFrameError = false
    private var calibrateStep = 0
    private var pendingOpen: Float = 0
    private var phaseCapturing = false
    private var captureToken = 0
    private var lastHeard = ""
    private var lastActionAt: Int64 = 0
    private var lastAction: VoiceAction = .ignore
    private let busy = BusyFlag()
    private var sceneInForeground = true
    private var earRunning = false
    private var quietUntilMs: Int64 = 0
    private var unmuteToken = 0
    private var prepared = false
    private let settingsLock = NSLock()
    private var settingsSnapshot = GuideSettings()
    private let resultLock = NSLock()
    private let guidingFlag = BusyFlag()
    private let captureFlag = BusyFlag()
    private let foregroundFlag = BusyFlag(true)

    func prepare() {
        guard !prepared else { return }
        prepared = true
        settings = SettingsStore.load()
        settingsSnapshot = settings
        haptics.prepare()
        cameraAllowed = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        statusText = phrase("status_ready")
        camera.onFrame = { [weak self] frame in
            self?.pipeline.async { self?.handle(frame) }
        }
        camera.onFailed = { [weak self] in
            DispatchQueue.main.async { self?.speakKey("status_camera_error", flush: true) }
        }
        ear.onTranscript = { [weak self] text, isFinal in
            DispatchQueue.main.async { self?.handleTranscript(text, isFinal: isFinal) }
        }
        ear.onFailure = { [weak self] key in
            DispatchQueue.main.async { self?.speakKey(key, flush: true) }
        }
        if settings.voiceEnabled {
            startMotion()
            beginSpottingIfAllowed()
        }
        if !speech.voiceAvailable("en") {
            speakKey("status_tts_error", flush: false)
        }
        GuideCommandCenter.shared.bind(self)
    }

    func setInForeground(_ foreground: Bool) {
        sceneInForeground = foreground
        foregroundFlag.set(foreground)
        if !foreground && guiding {
            camera.stop()
            speakKey("ios_limit_locked", flush: false)
        } else if foreground && guiding {
            camera.start()
            speakKey("status_listening", flush: false)
        }
        UIApplication.shared.isIdleTimerDisabled = guiding && foreground
    }

    func bigButton() {
        beginGuidance(toggleOffIfRunning: true)
    }

    /// Siri "Start RaahiSense". Starts warnings, and does not turn them off if they are already on.
    func startFromAssistant() {
        prepare()
        beginGuidance(toggleOffIfRunning: false)
    }

    private func beginGuidance(toggleOffIfRunning: Bool) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraAllowed = true
            if guiding {
                if toggleOffIfRunning {
                    stopGuide()
                } else {
                    speakKey("confirm_started_already", flush: true)
                }
            } else {
                startGuide()
            }
        case .notDetermined:
            speakKey("prompt_allow_camera", flush: true)
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.cameraAllowed = granted
                    if granted {
                        self.startGuide()
                    } else {
                        self.speakKey("status_camera_denied", flush: true)
                    }
                }
            }
        default:
            cameraAllowed = false
            speakKey("status_camera_blocked", flush: true)
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        }
    }

    func listenNow() {
        guard settings.voiceEnabled else {
            speakKey("status_voice_off", flush: true)
            return
        }
        startMotion()
        ensureEar {
            self.beginCaptureWindow()
        }
    }

    func toggleVoice() {
        if settings.voiceEnabled {
            mutateSettings { $0.voiceEnabled = false }
            ear.stop()
            earRunning = false
            stopMotion()
            capturing = false
            phaseCapturing = false
            speakKey("wake_off", flush: true)
        } else {
            mutateSettings { $0.voiceEnabled = true }
            speakKey("prompt_allow_microphone", flush: true)
            ensureEar {
                self.startMotion()
                self.speakKey("wake_on", flush: true)
            }
        }
    }

    func commitWake(_ raw: String) {
        let proposed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if CommandLexicon.normalize(proposed) == CommandLexicon.normalize(settings.wakePhrase) { return }
        guard WakeVocab.allows(proposed) else {
            speakKey("wake_phrase_rejected", flush: true)
            return
        }
        mutateSettings { $0.wakePhrase = proposed }
        refreshEarPhrases()
        speak(phrase("confirm_wake_phrase", settings.wakePhrase), flush: true)
    }

    func setLanguage(_ tag: String) {
        guard GuideSettings.languageTags.contains(tag) else { return }
        mutateSettings { $0.languageTag = tag }
        announceMissingVoice(tag)
        speakKey("confirm_language", flush: true)
        refreshEarPhrases()
    }

    func setRate(_ rate: Float) {
        mutateSettings { $0.speechRate = rate }
    }

    func setVolume(_ volume: Float) {
        mutateSettings { $0.speechVolume = volume }
    }

    func toggleBattery() {
        mutateSettings { $0.batterySaver.toggle() }
        speakKey(settings.batterySaver ? "battery_on" : "battery_off", flush: false)
    }

    func toggleVibration() {
        mutateSettings { $0.vibrationEnabled.toggle() }
        speakKey(settings.vibrationEnabled ? "vibration_on" : "vibration_off", flush: false)
    }

    func setTorch(_ mode: TorchMode) {
        mutateSettings { $0.torchMode = mode }
        let key: String
        switch mode {
        case .auto: key = "light_auto"
        case .on: key = "light_on"
        case .off: key = "light_off"
        }
        speak(phrase("label_light") + ". " + phrase(key), flush: false)
    }

    func calibrate() {
        guard guiding else {
            calibrateStep = 0
            speakKey("calibrate_need_start", flush: true)
            return
        }
        let median = readMedian()
        switch calibrateStep {
        case 0:
            calibrateStep = 1
            speakKey("calibrate_open", flush: true)
        case 1:
            if !median.isFinite || median == 0 {
                speakKey("calibrate_failed", flush: true)
                return
            }
            pendingOpen = median
            calibrateStep = 2
            speakKey("calibrate_wall", flush: true)
        default:
            let saved = DepthCalibration(openRaw: pendingOpen, wallRaw: median)
            if !saved.usable() {
                calibrateStep = 1
                speakKey(median.isFinite && median != 0 ? "calibrate_same" : "calibrate_failed", flush: true)
                return
            }
            mutateSettings {
                $0.depthOpenRaw = pendingOpen
                $0.depthWallRaw = median
            }
            calibrateStep = 0
            speakKey("calibrate_done", flush: true)
        }
    }

    private func startGuide() {
        guiding = true
        guidingFlag.set(true)
        frameErrors = 0
        toldFrameError = false
        UIApplication.shared.isIdleTimerDisabled = sceneInForeground
        camera.start()
        pipeline.async { [weak self] in
            guard let self else { return }
            self.throttleMarkReset()
            self.depthTracker.reset()
            self.lowLight.reset()
            self.latestDepth = []
            self.scoreThreshold = WarningLogic.scoreThreshold
            if self.models == nil && !self.modelFailed {
                do {
                    self.models = try ModelEngine()
                } catch {
                    self.modelFailed = true
                    DispatchQueue.main.async { self.speakKey("status_model_error", flush: true) }
                }
            }
        }
        speakKey("status_started", flush: true)
        if settings.voiceEnabled {
            startMotion()
            beginSpottingIfAllowed()
        }
    }

    /// Stops detection, the camera, speech recognition, and keeps the screen able to sleep.
    /// iOS will not let an app remove itself, so the screen stays open and Start works again.
    func stopEverything() {
        guiding = false
        guidingFlag.set(false)
        UIApplication.shared.isIdleTimerDisabled = false
        camera.setTorch(on: false)
        camera.release()
        ear.stop()
        earRunning = false
        capturing = false
        phaseCapturing = false
        captureFlag.set(false)
        captureToken += 1
        stopMotion()
        pipeline.async { [weak self] in
            guard let self else { return }
            self.models = nil
            self.modelFailed = false
            self.depthTracker.reset()
            self.latestDepth = []
            self.lowLight.reset()
            self.scoreThreshold = WarningLogic.scoreThreshold
        }
        let goodbye = phrase("confirm_app_stopped")
        statusText = goodbye
        speak(goodbye, flush: true) {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

    private func stopGuide() {
        guiding = false
        guidingFlag.set(false)
        UIApplication.shared.isIdleTimerDisabled = false
        camera.setTorch(on: false)
        camera.stop()
        pipeline.async { [weak self] in
            self?.depthTracker.reset()
            self?.latestDepth = []
            self?.lowLight.reset()
        }
        speakKey("status_stopped", flush: true)
    }

    private func throttleMarkReset() {
        // A fresh walk should not inherit the previous throttle window.
        // WarningThrottle has no public reset, so a new window starts as time moves on.
    }

    private func handle(_ frame: CameraFrame) {
        guard guidingFlag.get(), foregroundFlag.get() else { return }
        let current = readSettings()
        let now = Self.nowMs()
        let interval = GuideSettings.frameIntervalMs(batterySaver: current.batterySaver)
        if now - lastFrameAt < interval { return }
        lastFrameAt = now
        guard let models else { return }
        let decision = lowLight.step(
            scene: frame.scene,
            mode: current.torchMode,
            torchAvailable: frame.torchAvailable,
            nowMs: now
        )
        scoreThreshold = LowLight.scoreThreshold(lowLight: decision.lowLight)
        if appliedTorch != decision.torchOn {
            appliedTorch = decision.torchOn
            camera.setTorch(on: decision.torchOn)
        }
        if appliedBoost != decision.lowLight {
            appliedBoost = decision.lowLight
            camera.setLowLightBoost(decision.lowLight)
        }
        if let name = decision.phraseResName {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.guiding else { return }
                self.speak(self.phrase(name), flush: false)
            }
        }
        do {
            let boxes = try models.detect(frame.image, scoreThreshold: scoreThreshold)
            frameErrors = 0
            scheduleDepth(frame.image, boxes: boxes, now: now)
            let ranked = rank(boxes)
            storeRanked(ranked)
            guard !captureFlag.get() else { return }
            guard let choice = WarningLogic.selectAnnouncement(
                ranked: ranked,
                throttle: throttle,
                speechBusy: busy.get(),
                nowMs: now
            ) else { return }
            let language = current.languageTag
            let text = PhraseBook.warning(choice, language: language)
            guard !text.isEmpty else { return }
            lastWarningPhrase = text
            let urgent = choice.urgent && current.vibrationEnabled
            busy.set(true)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.guidingFlag.get(), !self.captureFlag.get() else {
                    self?.busy.set(false)
                    return
                }
                if urgent { self.haptics.pulseUrgent() }
                self.statusText = text
                self.speak(text, flush: true)
            }
        } catch {
            frameErrors += 1
            if frameErrors >= 8 && !toldFrameError {
                toldFrameError = true
                DispatchQueue.main.async { [weak self] in
                    self?.speakKey("status_camera_error", flush: true)
                }
            }
        }
    }

    private func rank(_ boxes: [DetectionBox]) -> [ObstacleWarning] {
        (WarningLogic.rank(boxes, scoreThreshold: scoreThreshold) + latestDepth).sorted { lhs, rhs in
            if lhs.priority != rhs.priority { return lhs.priority > rhs.priority }
            return lhs.label < rhs.label
        }
    }

    private func scheduleDepth(_ image: RGBImage, boxes: [DetectionBox], now: Int64) {
        let interval = GuideSettings.depthIntervalMs(batterySaver: readSettings().batterySaver)
        if now - lastDepthAt < interval || depthBusy { return }
        guard let models else { return }
        lastDepthAt = now
        depthBusy = true
        let calibration = readSettings().depthCalibration()
        let threshold = scoreThreshold
        depthQueue.async { [weak self] in
            guard let self else { return }
            do {
                let field = try models.estimateDepth(image)
                self.pipeline.async {
                    defer { self.depthBusy = false }
                    guard self.guidingFlag.get() else { return }
                    let warnings = self.depthTracker.update(
                        field,
                        detections: boxes,
                        calibration: calibration,
                        scoreThreshold: threshold
                    )
                    self.latestDepth = warnings
                    self.storeMedian(DepthWarnings.aheadMedian(field))
                }
            } catch {
                self.pipeline.async { self.depthBusy = false }
            }
        }
    }

    private func ensureEar(then: @escaping () -> Void) {
        SFSpeechRecognizer.requestAuthorization { [weak self] speechStatus in
            AVAudioSession.sharedInstance().requestRecordPermission { micOK in
                DispatchQueue.main.async {
                    guard let self else { return }
                    guard speechStatus == .authorized, micOK else {
                        self.speakKey(speechStatus == .denied || !micOK ? "status_mic_denied" : "status_mic_needed", flush: true)
                        return
                    }
                    if !self.ear.onDeviceSupported {
                        self.speakKey("status_speech_unavailable", flush: true)
                        return
                    }
                    if !self.earRunning {
                        self.ear.start(phrases: self.spottingPhrases())
                        self.earRunning = true
                    } else {
                        self.refreshEarPhrases()
                    }
                    then()
                }
            }
        }
    }

    private func beginSpottingIfAllowed() {
        guard settings.voiceEnabled else { return }
        let speechStatus = SFSpeechRecognizer.authorizationStatus()
        let mic = AVAudioSession.sharedInstance().recordPermission
        guard speechStatus == .authorized, mic == .granted else { return }
        guard ear.onDeviceSupported else { return }
        if !earRunning {
            ear.start(phrases: spottingPhrases())
            earRunning = true
        }
    }

    private func beginCaptureWindow() {
        captureToken += 1
        let token = captureToken
        phaseCapturing = true
        capturing = true
        captureFlag.set(true)
        lastHeard = ""
        ear.setDucked(true)
        ear.updatePhrases(commandPhrases())
        speakKey("wake_ack", flush: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, self.captureToken == token else { return }
            self.endCapture()
        }
    }

    private func endCapture() {
        guard phaseCapturing else { return }
        phaseCapturing = false
        capturing = false
        captureFlag.set(false)
        captureToken += 1
        lastHeard = ""
        ear.setDucked(false)
        if settings.voiceEnabled && earRunning {
            ear.updatePhrases(spottingPhrases())
        }
    }

    private func handleTranscript(_ text: String, isFinal: Bool) {
        guard RecognitionGate.accepts(
            speechActive: speech.speaking,
            nowMs: Self.nowMs(),
            quietUntilMs: quietUntilMs
        ) else { return }
        let normalized = CommandLexicon.normalize(text)
        if normalized.isEmpty || normalized == lastHeard && !isFinal { return }
        lastHeard = normalized
        let action = VoiceInterpreter.interpret(
            text,
            lexicon: currentLexicon(),
            wakePhrase: settings.wakePhrase,
            requireWake: !phaseCapturing
        )
        switch action {
        case .ignore:
            break
        case .wakeOnly:
            if isFinal && !phaseCapturing { beginCaptureWindow() }
        case .unknown:
            if isFinal && phaseCapturing {
                speakKey("confirm_unknown", flush: true)
                endCapture()
            }
        default:
            let now = Self.nowMs()
            if action == lastAction && now - lastActionAt < 1_500 { return }
            lastAction = action
            lastActionAt = now
            perform(action)
            if phaseCapturing { endCapture() }
        }
    }

    private func perform(_ action: VoiceAction) {
        switch action {
        case .ignore, .wakeOnly:
            break
        case .unknown:
            speakKey("confirm_unknown", flush: true)
        case .start:
            if guiding {
                speakKey("confirm_started_already", flush: true)
            } else {
                bigButton()
            }
        case .stop:
            stopEverything()
        case .pause:
            if guiding {
                stopGuide()
            } else {
                speakKey("confirm_stopped_already", flush: true)
            }
        case .resume:
            if guiding {
                speakKey("confirm_started_already", flush: true)
            } else {
                bigButton()
            }
        case .whatIsAhead:
            speakAhead()
        case .repeatLast:
            if lastWarningPhrase.isEmpty {
                speakKey("confirm_nothing_to_repeat", flush: true)
            } else {
                let again = lastWarningPhrase
                speak(phrase("confirm_repeat"), flush: true) { [weak self] in
                    self?.speak(again, flush: false)
                }
            }
        case .louder:
            let next = settings.louder()
            let key = next.speechVolume == settings.speechVolume ? "confirm_louder_max" : "confirm_louder"
            mutateSettings { $0 = next }
            speakKey(key, flush: true)
        case .quieter:
            let next = settings.quieter()
            let key = next.speechVolume == settings.speechVolume ? "confirm_quieter_min" : "confirm_quieter"
            mutateSettings { $0 = next }
            speakKey(key, flush: true)
        case .faster:
            let next = settings.faster()
            let key = next.speechRate == settings.speechRate ? "confirm_faster_max" : "confirm_faster"
            mutateSettings { $0 = next }
            speakKey(key, flush: true)
        case .slower:
            let next = settings.slower()
            let key = next.speechRate == settings.speechRate ? "confirm_slower_min" : "confirm_slower"
            mutateSettings { $0 = next }
            speakKey(key, flush: true)
        case .changeLanguage:
            let next = settings.nextLanguage()
            mutateSettings { $0 = next }
            announceMissingVoice(next.languageTag)
            speakKey("confirm_language", flush: true)
            refreshEarPhrases()
        case .help:
            speak(phrase("help_commands", settings.wakePhrase), flush: true)
        }
    }

    private func speakAhead() {
        let language = settings.languageTag
        let lines = readRanked().prefix(3).map { PhraseBook.warning($0, language: language) }.filter { !$0.isEmpty }
        if lines.isEmpty {
            speakKey("ahead_clear", flush: true)
        } else {
            speak(lines.joined(separator: ". "), flush: true)
        }
    }

    private func currentLexicon() -> CommandLexicon {
        let language = settings.languageTag
        func phrases(_ key: String) -> [String] {
            CommandLexicon.pipes(PhraseBook.text(key, language: language))
        }
        let extra = CommandLexicon(phrases: [
            .start: phrases("cmd_start"),
            .stop: phrases("cmd_stop"),
            .pause: phrases("cmd_pause"),
            .resume: phrases("cmd_resume"),
            .whatIsAhead: phrases("cmd_ahead"),
            .repeatLast: phrases("cmd_repeat"),
            .louder: phrases("cmd_louder"),
            .quieter: phrases("cmd_quieter"),
            .faster: phrases("cmd_faster"),
            .slower: phrases("cmd_slower"),
            .changeLanguage: phrases("cmd_language"),
            .help: phrases("cmd_help"),
        ])
        return CommandLexicon.english.withExtra(extra.phrases)
    }

    private func spottingPhrases() -> [String] {
        let wake = CommandLexicon.normalize(settings.wakePhrase)
        var list = [wake]
        for command in VoiceCommand.allCases {
            for phrase in currentLexicon().phrases[command] ?? [] {
                list.append("\(wake) \(phrase)")
            }
        }
        return list
    }

    private func commandPhrases() -> [String] {
        VoiceCommand.allCases.flatMap { currentLexicon().phrases[$0] ?? [] }
    }

    private func refreshEarPhrases() {
        guard earRunning else { return }
        ear.updatePhrases(phaseCapturing ? commandPhrases() : spottingPhrases())
    }

    private func startMotion() {
        guard settings.voiceEnabled, motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        motion.accelerometerUpdateInterval = 0.02
        motion.startAccelerometerUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let data, self.settings.voiceEnabled else { return }
            let gravity: Float = 9.80665
            let fired = self.shake.onSample(
                timeMs: Self.nowMs(),
                x: Float(data.acceleration.x) * gravity,
                y: Float(data.acceleration.y) * gravity,
                z: Float(data.acceleration.z) * gravity
            )
            if fired {
                DispatchQueue.main.async { self.listenNow() }
            }
        }
    }

    private func stopMotion() {
        motion.stopAccelerometerUpdates()
    }

    private func announceMissingVoice(_ tag: String) {
        let key: String?
        switch tag {
        case "hi": key = "voice_missing_hi"
        case "ta": key = "voice_missing_ta"
        case "bn": key = "voice_missing_bn"
        case "sw": key = "voice_missing_sw"
        case "es": key = "voice_missing_es"
        default: key = nil
        }
        if let key, !speech.voiceAvailable(tag) {
            speakKey(key, flush: false)
        }
    }

    private func mutateSettings(_ body: (inout GuideSettings) -> Void) {
        settingsLock.lock()
        var copy = settingsSnapshot
        body(&copy)
        copy = copy.normalized()
        settingsSnapshot = copy
        settingsLock.unlock()
        settings = copy
        SettingsStore.save(copy)
    }

    private func readSettings() -> GuideSettings {
        settingsLock.lock()
        defer { settingsLock.unlock() }
        return settingsSnapshot
    }

    private func storeRanked(_ ranked: [ObstacleWarning]) {
        resultLock.lock()
        lastRanked = ranked
        resultLock.unlock()
    }

    private func readRanked() -> [ObstacleWarning] {
        resultLock.lock()
        defer { resultLock.unlock() }
        return lastRanked
    }

    private func storeMedian(_ value: Float) {
        resultLock.lock()
        aheadMedian = value
        resultLock.unlock()
    }

    private func readMedian() -> Float {
        resultLock.lock()
        defer { resultLock.unlock() }
        return aheadMedian
    }

    private func phrase(_ key: String) -> String {
        PhraseBook.text(key, language: readSettings().languageTag)
    }

    private func phrase(_ key: String, _ arg: String) -> String {
        PhraseBook.text(key, language: readSettings().languageTag, arg)
    }

    private func speakKey(_ key: String, flush: Bool) {
        let text = phrase(key)
        statusText = text
        speak(text, flush: flush)
    }

    private func speak(_ text: String, flush: Bool, done: (() -> Void)? = nil) {
        guard !text.isEmpty else {
            done?()
            return
        }
        beginSpeechMute()
        busy.set(true)
        let current = readSettings()
        speech.speak(
            text,
            flush: flush,
            rate: current.speechRate,
            volume: current.speechVolume,
            language: current.languageTag
        ) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.busy.set(false)
                self.endSpeechMute()
                done?()
            }
        }
    }

    private func beginSpeechMute() {
        unmuteToken += 1
        quietUntilMs = Int64.max
        ear.setMuted(true)
    }

    private func endSpeechMute() {
        unmuteToken += 1
        let token = unmuteToken
        let tail = RecognitionGate.tailMs
        quietUntilMs = RecognitionGate.quietUntil(speechEndedAtMs: Self.nowMs())
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(tail))) { [weak self] in
            guard let self, self.unmuteToken == token else { return }
            self.quietUntilMs = Self.nowMs()
            self.ear.setMuted(false)
        }
    }

    private static func nowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}

private final class BusyFlag {
    private let lock = NSLock()
    private var value: Bool

    init(_ value: Bool = false) {
        self.value = value
    }

    func get() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set(_ newValue: Bool) {
        lock.lock()
        value = newValue
        lock.unlock()
    }
}
