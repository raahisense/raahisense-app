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
    @Published var collectPhotos = 0
    @Published var collectBytes: Int64 = 0
    @Published var shareItem: ShareItem?

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
    private let detectionConfirm = DetectionConfirm()
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
    private var correctionUntilMs: Int64 = 0
    private var correctionToken = 0
    private let warningLock = NSLock()
    private var lastSpokenWarning: SpokenWarning?
    private var prepared = false
    private let settingsLock = NSLock()
    private var settingsSnapshot = GuideSettings()
    private let resultLock = NSLock()
    private let guidingFlag = BusyFlag()
    private let captureFlag = BusyFlag()
    private let foregroundFlag = BusyFlag(true)
    private let collectScheduler = CollectScheduler()
    private let collectQueue = DispatchQueue(label: "app.raahisense.collect", qos: .utility)
    private let locator = CollectLocator()
    private var collectBusy = false
    private var collectHot = false
    private var collectSequence = 0
    private var latestBoxes: [DetectionBox] = []
    private var latestField: DepthField?
    private var latestScene = SceneLight(meanLuma: .nan)
    private var collectTimer: DispatchSourceTimer?

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
        ear.onTranscript = { [weak self] text, isFinal, languageLane in
            DispatchQueue.main.async {
                self?.handleTranscript(text, isFinal: isFinal, languageLane: languageLane)
            }
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
        locator.onDenied = { [weak self] in
            guard let self else { return }
            self.mutateSettings { $0.collectGps = false }
            self.speakKey("collect_gps_denied", flush: true)
        }
        refreshCollectUsage()
    }

    func setInForeground(_ foreground: Bool) {
        sceneInForeground = foreground
        foregroundFlag.set(foreground)
        if !foreground && guiding {
            camera.setCollecting(false)
            camera.stop()
            speakKey("ios_limit_locked", flush: false)
        } else if foreground && guiding {
            camera.start()
            camera.setCollecting(settings.collectEnabled)
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
        guard WakeVocab.allows(proposed, languageTag: settings.languageTag) else {
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
        syncCollectRuntime()
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
        camera.setCollecting(false)
        camera.release()
        stopCollectWork()
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
        camera.setCollecting(false)
        camera.stop()
        stopCollectWork()
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
            latestBoxes = boxes
            latestScene = frame.scene
            scheduleDepth(frame.image, boxes: boxes, now: now)
            let ranked = rank(boxes)
            storeRanked(ranked)
            guard !captureFlag.get() else { return }
            let confirmed = detectionConfirm.observe(Set(ranked.map { $0.label }))
            let stable = ranked.filter { confirmed.contains($0.label) }
            throttle.gapMs = WarningLogic.defaultGapMs
            guard let choice = WarningLogic.selectAnnouncement(
                ranked: stable,
                throttle: throttle,
                speechBusy: busy.get(),
                nowMs: now
            ) else { return }
            let language = current.languageTag
            let text = PhraseBook.warning(choice, language: language)
            guard !text.isEmpty else { return }
            lastWarningPhrase = text
            if current.collectEnabled {
                collectScheduler.onWarning()
                pumpCollect()
            }
            let urgent = choice.urgent && current.vibrationEnabled
            let warningBoxes = boxes
            busy.set(true)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.guidingFlag.get(), !self.captureFlag.get() else {
                    self?.busy.set(false)
                    return
                }
                if urgent { self.haptics.pulseUrgent() }
                self.statusText = text
                self.storeSpokenWarning(
                    CollectCapture.spokenWarning(
                        warning: choice,
                        boxes: warningBoxes,
                        spokenAtIso: CollectCapture.isoTimestamp(epochMs: Self.epochMs())
                    )
                )
                self.speak(text, flush: true, opensCorrection: true)
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
                    self.latestField = field
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
                        self.ear.start(
                            english: self.englishPhrases(correction: false),
                            language: self.languagePhrases(),
                            languageTag: self.settings.languageTag
                        )
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
            ear.start(
                english: englishPhrases(correction: false),
                language: languagePhrases(),
                languageTag: settings.languageTag
            )
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
        ear.updatePhrases(
            english: englishPhrases(correction: false),
            language: languagePhrases(),
            languageTag: settings.languageTag
        )
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
            ear.updatePhrases(
                english: englishPhrases(correction: false),
                language: languagePhrases(),
                languageTag: settings.languageTag
            )
        }
    }

    private func handleTranscript(_ text: String, isFinal: Bool, languageLane: Bool) {
        let now = Self.nowMs()
        let speaking = speech.speaking
        let correctionOpen = RecognitionGate.acceptsCorrection(
            speechActive: speaking,
            nowMs: now,
            correctionUntilMs: correctionUntilMs
        )
        let gateOpen = RecognitionGate.accepts(
            speechActive: speaking,
            nowMs: now,
            quietUntilMs: quietUntilMs
        )
        if !gateOpen && !correctionOpen { return }
        let normalized = CommandLexicon.normalize(text)
        if normalized.isEmpty || normalized == lastHeard && !isFinal { return }
        lastHeard = normalized
        let action = VoiceInterpreter.interpret(
            text,
            lexicon: currentLexicon(),
            wakePhrase: settings.wakePhrase,
            requireWake: !phaseCapturing,
            allowBareCorrection: correctionOpen && !phaseCapturing && !languageLane,
            extraWakePhrases: languageLane ? SpokenEar.nativeWakes(settings.languageTag) : []
        )
        let correctionAction = action == .wrong || action == .missed
        if !gateOpen && !(correctionOpen && correctionAction) { return }
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
        case .startCollecting:
            setCollectingFromVoice(on: true)
        case .stopCollecting:
            setCollectingFromVoice(on: false)
        case .missed:
            noteMissed()
        case .wrong:
            noteWrong()
        case .sendLog:
            speakKey("diagnostics_empty", flush: true)
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
            .startCollecting: phrases("cmd_collect_start"),
            .stopCollecting: phrases("cmd_collect_stop"),
            .missed: phrases("cmd_missed"),
            .wrong: phrases("cmd_wrong"),
            .sendLog: phrases("cmd_send_log"),
        ])
        return CommandLexicon.english.withExtra(extra.phrases)
    }

    private func englishPhrases(correction: Bool) -> [String] {
        let wake = CommandLexicon.normalize(settings.wakePhrase)
        let lexicon = CommandLexicon.english
        if phaseCapturing {
            return VoiceCommand.allCases.flatMap { lexicon.phrases[$0] ?? [] }
        }
        var list = [wake]
        for command in VoiceCommand.allCases {
            for phrase in lexicon.phrases[command] ?? [] {
                list.append("\(wake) \(phrase)")
            }
        }
        if correction {
            for command in [VoiceCommand.wrong, VoiceCommand.missed] {
                list.append(contentsOf: lexicon.phrases[command] ?? [])
            }
        }
        return list
    }

    private func languagePhrases() -> [String] {
        let tag = settings.languageTag
        if tag == "en" { return [] }
        let everyday: [VoiceCommand] = [
            .start, .pause, .resume, .whatIsAhead, .repeatLast,
            .louder, .quieter, .faster, .slower, .changeLanguage, .help,
        ]
        let english = CommandLexicon.english
        var localized: [String] = []
        for command in everyday {
            let phrases = currentLexicon().phrases[command] ?? []
            let known = Set(english.phrases[command] ?? [])
            localized.append(contentsOf: phrases.filter { !known.contains($0) })
        }
        var wakes = SpokenEar.nativeWakes(tag)
        let saved = CommandLexicon.normalize(settings.wakePhrase)
        if !wakes.contains(saved) { wakes.append(saved) }
        if phaseCapturing {
            return wakes + localized
        }
        var list = wakes
        for wake in wakes {
            for phrase in localized {
                list.append("\(wake) \(phrase)")
            }
        }
        return list
    }

    private func refreshEarPhrases() {
        guard earRunning else { return }
        ear.updatePhrases(
            english: englishPhrases(correction: false),
            language: languagePhrases(),
            languageTag: settings.languageTag
        )
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
        case "kn": key = "voice_missing_kn"
        case "te": key = "voice_missing_te"
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

    private func speak(_ text: String, flush: Bool, opensCorrection: Bool = false, done: (() -> Void)? = nil) {
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
                self.endSpeechMute(opensCorrection: opensCorrection)
                done?()
            }
        }
    }

    private func beginSpeechMute() {
        unmuteToken += 1
        quietUntilMs = Int64.max
        ear.setMuted(true)
    }

    /// After a warning, unmute at once so "wrong" or "missed" can be heard.
    /// Other commands still wait out the tail. Other speech keeps the tail muted.
    private func endSpeechMute(opensCorrection: Bool) {
        unmuteToken += 1
        let token = unmuteToken
        let ended = Self.nowMs()
        if opensCorrection {
            correctionUntilMs = RecognitionGate.correctionUntil(warningEndedAtMs: ended)
            quietUntilMs = RecognitionGate.quietUntil(speechEndedAtMs: ended)
            correctionToken += 1
            let correction = correctionToken
            if earRunning {
                let phrases = listeningPhrases(correction: true)
                ear.updatePhrases(
                    english: phrases.english,
                    language: phrases.language,
                    languageTag: settings.languageTag
                )
                ear.setMuted(false)
            }
            let window = RecognitionGate.correctionWindowMs
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(window))) { [weak self] in
                guard let self, self.correctionToken == correction, self.unmuteToken == token else { return }
                self.correctionUntilMs = 0
                self.restoreListeningPhrases()
            }
            return
        }
        correctionToken += 1
        correctionUntilMs = 0
        restoreListeningPhrases()
        quietUntilMs = RecognitionGate.quietUntil(speechEndedAtMs: ended)
        let tail = RecognitionGate.tailMs
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(tail))) { [weak self] in
            guard let self, self.unmuteToken == token else { return }
            self.quietUntilMs = Self.nowMs()
            self.ear.setMuted(false)
        }
    }

    private func listeningPhrases(correction: Bool) -> (english: [String], language: [String]) {
        (englishPhrases(correction: correction), languagePhrases())
    }

    private func restoreListeningPhrases() {
        guard earRunning else { return }
        let phrases = listeningPhrases(correction: false)
        ear.updatePhrases(
            english: phrases.english,
            language: phrases.language,
            languageTag: settings.languageTag
        )
    }

    private func storeSpokenWarning(_ warning: SpokenWarning) {
        warningLock.lock()
        lastSpokenWarning = warning
        warningLock.unlock()
    }

    private func readSpokenWarning() -> SpokenWarning? {
        warningLock.lock()
        defer { warningLock.unlock() }
        return lastSpokenWarning
    }

    func toggleCollect() {
        let turningOn = !settings.collectEnabled
        mutateSettings { $0.collectEnabled = turningOn }
        syncCollectRuntime()
        speakKey(turningOn ? "collect_on" : "collect_off", flush: true)
        if turningOn && !guiding { bigButton() }
    }

    func cycleCollectInterval() {
        let next = settings.nextCollectInterval()
        mutateSettings { $0.collectIntervalMs = next.collectIntervalMs }
        let label = CollectCapture.intervalLabel(next.collectIntervalMs)
        speak(phrase("collect_interval", label), flush: true)
    }

    func toggleCollectGps() {
        if settings.collectGps {
            mutateSettings { $0.collectGps = false }
            syncCollectRuntime()
            speakKey("collect_gps_off", flush: true)
        } else {
            mutateSettings { $0.collectGps = true }
            syncCollectRuntime()
            speakKey("collect_gps_on", flush: true)
        }
    }

    func exportCollect() {
        speakKey("collect_export_working", flush: false)
        collectQueue.async { [weak self] in
            guard let self else { return }
            let zip = self.makeExportZip()
            DispatchQueue.main.async {
                guard let zip else {
                    self.speakKey("collect_export_empty", flush: true)
                    return
                }
                self.shareItem = ShareItem(url: zip)
            }
        }
    }

    func refreshCollectUsage() {
        collectQueue.async { [weak self] in
            guard let self else { return }
            let usage = self.scanUsage()
            DispatchQueue.main.async {
                self.collectPhotos = usage.photos
                self.collectBytes = usage.bytes
            }
        }
    }

    private func setCollectingFromVoice(on: Bool) {
        if settings.collectEnabled == on {
            speakKey(on ? "confirm_collect_already_on" : "confirm_collect_already_off", flush: true)
            if on && !guiding { bigButton() }
            return
        }
        mutateSettings { $0.collectEnabled = on }
        syncCollectRuntime()
        speakKey(on ? "confirm_collect_on" : "confirm_collect_off", flush: true)
        if on && !guiding { bigButton() }
    }

    private func noteMissed() {
        guard settings.collectEnabled else {
            speakKey("confirm_missed_off", flush: true)
            return
        }
        if !guiding { bigButton() }
        pipeline.async { [weak self] in
            self?.collectScheduler.onMissed()
            self?.pumpCollect()
        }
        speakKey("confirm_noted", flush: true)
    }

    private func noteWrong() {
        guard settings.collectEnabled else {
            speakKey("confirm_missed_off", flush: true)
            return
        }
        if !guiding { bigButton() }
        pipeline.async { [weak self] in
            self?.collectScheduler.onWrong()
            self?.pumpCollect()
        }
        speakKey("confirm_noted", flush: true)
    }

    private func syncCollectRuntime() {
        let current = readSettings()
        let active = guiding && current.collectEnabled
        pipeline.async { [weak self] in
            self?.collectScheduler.setEnabled(current.collectEnabled)
            if active { self?.pumpCollect() }
        }
        camera.setCollecting(active)
        locator.setActive(active && current.collectGps)
        if active {
            startCollectTimer()
        } else {
            stopCollectWork()
        }
        refreshCollectUsage()
    }

    private func startCollectTimer() {
        if collectTimer != nil { return }
        let timer = DispatchSource.makeTimerSource(queue: pipeline)
        timer.schedule(deadline: .now() + .milliseconds(200), repeating: .milliseconds(200))
        timer.setEventHandler { [weak self] in
            self?.pumpCollect()
        }
        collectTimer = timer
        timer.resume()
    }

    private func stopCollectWork() {
        collectTimer?.cancel()
        collectTimer = nil
        collectHot = false
        locator.setActive(false)
        pipeline.async { [weak self] in
            self?.collectBusy = false
        }
    }

    private func pumpCollect() {
        let current = readSettings()
        guard current.collectEnabled, guidingFlag.get(), foregroundFlag.get() else { return }
        let hot = Self.phoneIsHot()
        noteHeat(hot)
        guard let event = collectScheduler.poll(
            nowMs: Self.nowMs(),
            intervalMs: current.collectIntervalMs,
            hot: hot,
            busy: collectBusy
        ) else { return }
        collectBusy = true
        let snapshot = currentSnapshot(event: event, settings: current)
        camera.takePhoto { [weak self] data, width, height in
            guard let self else { return }
            self.collectQueue.async {
                if let data, !data.isEmpty {
                    self.writeCapture(data, width: width, height: height, snapshot: snapshot)
                }
                self.pipeline.async {
                    self.collectScheduler.onCaptured(nowMs: Self.nowMs())
                    self.collectBusy = false
                    self.pumpCollect()
                    self.refreshCollectUsage()
                }
            }
        }
    }

    private func noteHeat(_ hot: Bool) {
        if hot == collectHot { return }
        collectHot = hot
        let key = hot ? "collect_paused_hot" : "collect_resumed"
        DispatchQueue.main.async { [weak self] in
            guard let self, self.guiding, self.settings.collectEnabled else { return }
            self.speakKey(key, flush: false)
        }
    }

    private func currentSnapshot(event: CollectEvent, settings: GuideSettings) -> CollectSnapshot {
        let gps = settings.collectGps ? locator.currentReading() : nil
        return CollectSnapshot(
            event: event,
            requestedAtMs: Self.epochMs(),
            detections: latestBoxes,
            depth: CollectCapture.summarizeDepth(latestField),
            lighting: CollectCapture.lightingOf(latestScene),
            gpsEnabled: settings.collectGps,
            gps: gps,
            spokenWarning: event == .wrong ? readSpokenWarning() : nil
        )
    }

    private func writeCapture(_ data: Data, width: Int, height: Int, snapshot: CollectSnapshot) {
        let folder = sessionFolder(snapshot.requestedAtMs)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        collectSequence += 1
        let stem = CollectCapture.fileStem(
            epochMs: snapshot.requestedAtMs,
            timeZone: .current,
            event: snapshot.event,
            sequence: collectSequence
        )
        let jpg = folder.appendingPathComponent("\(stem).jpg")
        let json = folder.appendingPathComponent("\(stem).json")
        do {
            try data.write(to: jpg, options: .atomic)
            let body = CollectCapture.sidecarJson(
                timestampIso: CollectCapture.isoTimestamp(epochMs: snapshot.requestedAtMs),
                event: snapshot.event,
                detections: snapshot.detections,
                depth: snapshot.depth,
                lighting: snapshot.lighting,
                gpsEnabled: snapshot.gpsEnabled,
                gps: snapshot.gps,
                width: width > 0 ? width : nil,
                height: height > 0 ? height : nil,
                fullResolution: true,
                spokenWarning: snapshot.spokenWarning,
                includeSpokenWarning: snapshot.event == .wrong
            )
            try body.write(to: json, atomically: true, encoding: .utf8)
        } catch {
            try? FileManager.default.removeItem(at: jpg)
            try? FileManager.default.removeItem(at: json)
        }
    }

    private func sessionFolder(_ epochMs: Int64) -> URL {
        collectRoot().appendingPathComponent(CollectCapture.sessionDate(epochMs: epochMs), isDirectory: true)
    }

    private func collectRoot() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(CollectCapture.folder, isDirectory: true)
    }

    private func scanUsage() -> (photos: Int, bytes: Int64) {
        let root = collectRoot()
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) else {
            return (0, 0)
        }
        var photos = 0
        var bytes: Int64 = 0
        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            guard values?.isRegularFile == true else { continue }
            bytes += Int64(values?.fileSize ?? 0)
            if url.pathExtension.lowercased() == "jpg" { photos += 1 }
        }
        return (photos, bytes)
    }

    /// TODO: Blur faces before any third-party sharing. This only writes a zip and opens the share sheet.
    private func makeExportZip() -> URL? {
        let root = collectRoot()
        let sessions = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil))?
            .filter { CollectCapture.isSessionDate($0.lastPathComponent) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard let session = sessions?.last else { return nil }
        let files = (try? FileManager.default.contentsOfDirectory(at: session, includingPropertiesForKeys: nil)) ?? []
        var payload: [(name: String, data: Data)] = []
        for file in files {
            let ext = file.pathExtension.lowercased()
            guard ext == "jpg" || ext == "json", let data = try? Data(contentsOf: file) else { continue }
            payload.append((name: "\(session.lastPathComponent)/\(file.lastPathComponent)", data: data))
        }
        guard payload.contains(where: { $0.name.hasSuffix(".jpg") }) else { return nil }
        let zip = CollectZip.store(files: payload)
        let export = root.deletingLastPathComponent().appendingPathComponent("export", isDirectory: true)
        try? FileManager.default.createDirectory(at: export, withIntermediateDirectories: true)
        let url = export.appendingPathComponent("RaahiSense-collect-\(session.lastPathComponent).zip")
        do {
            try zip.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    private static func phoneIsHot() -> Bool {
        switch ProcessInfo.processInfo.thermalState {
        case .serious, .critical: return true
        default: return false
        }
    }

    private static func epochMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func nowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct CollectSnapshot {
    var event: CollectEvent
    var requestedAtMs: Int64
    var detections: [DetectionBox]
    var depth: DepthSummary
    var lighting: LightingEstimate
    var gpsEnabled: Bool
    var gps: GpsReading?
    var spokenWarning: SpokenWarning?
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
