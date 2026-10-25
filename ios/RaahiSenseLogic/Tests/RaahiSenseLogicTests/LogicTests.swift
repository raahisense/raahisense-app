import RaahiSenseLogic
import XCTest

final class LogicTests: XCTestCase {
    private let saved = DepthCalibration(openRaw: 3, wallRaw: 1)

    func testPositionBands() {
        XCTAssertEqual(WarningLogic.positionOf(0), .left)
        XCTAssertEqual(WarningLogic.positionOf(0.349), .left)
        XCTAssertEqual(WarningLogic.positionOf(0.35), .ahead)
        XCTAssertEqual(WarningLogic.positionOf(0.65), .ahead)
        XCTAssertEqual(WarningLogic.positionOf(0.651), .right)
    }

    func testCloseness() {
        XCTAssertEqual(WarningLogic.closenessOf(area: 0.22, height: 0.10, bottom: 0.50), .veryClose)
        XCTAssertEqual(WarningLogic.closenessOf(area: 0.02, height: 0.22, bottom: 0.92), .veryClose)
        XCTAssertEqual(WarningLogic.closenessOf(area: 0.02, height: 0.219, bottom: 0.92), .near)
        XCTAssertEqual(WarningLogic.closenessOf(area: 0.06, height: 0.27, bottom: 0.74), .far)
    }

    func testThrottleAndUrgentInterrupt() {
        let throttle = WarningThrottle()
        let close = ObstacleWarning(label: "chair", position: .ahead, closeness: .veryClose, priority: 7, urgent: true)
        let far = ObstacleWarning(label: "person", position: .left, closeness: .far, priority: 3, urgent: false)
        let first = WarningLogic.selectAnnouncement(ranked: [close], throttle: throttle, speechBusy: true, nowMs: 1_000)
        XCTAssertEqual(first, close)
        XCTAssertNil(WarningLogic.selectAnnouncement(ranked: [close], throttle: throttle, speechBusy: false, nowMs: 2_000))
        XCTAssertNil(WarningLogic.selectAnnouncement(ranked: [far], throttle: throttle, speechBusy: true, nowMs: 3_000))
        XCTAssertEqual(WarningLogic.throttleMs, 4_000)
    }

    func testPotholePhraseUsesDistance() {
        let box = DetectionBox(label: "pothole", score: 0.8, left: 0.35, top: 0.4, right: 0.65, bottom: 0.95)
        let warning = WarningLogic.fromBox(box)
        XCTAssertEqual(warning?.label, "pothole")
        XCTAssertEqual(warning?.position, .ahead)
        XCTAssertEqual(warning?.closeness, .veryClose)
        XCTAssertEqual(warning?.urgent, true)
    }

    func testUnknownWallAndApproach() {
        let tracker = DepthTracker()
        let closeOnLeft = scene { x in x <= 6 ? 1 : 3 }
        XCTAssertTrue(tracker.update(closeOnLeft, detections: [], calibration: saved).isEmpty)
        XCTAssertTrue(tracker.update(closeOnLeft, detections: [], calibration: saved).isEmpty)
        let unknown = tracker.update(closeOnLeft, detections: [], calibration: saved)
        XCTAssertEqual(unknown.count, 1)
        XCTAssertEqual(unknown[0].label, DepthWarnings.labelUnknown)
        XCTAssertEqual(unknown[0].position, .left)
        XCTAssertEqual(DepthWarnings.phraseName(unknown[0]), "depth_unknown_very_close_left")
        XCTAssertFalse(PhraseIds.objects.contains(unknown[0].label))

        let wallTracker = DepthTracker()
        let wall = scene { _ in 1 }
        XCTAssertTrue(wallTracker.update(wall, detections: [], calibration: saved).isEmpty)
        XCTAssertTrue(wallTracker.update(wall, detections: [], calibration: saved).isEmpty)
        let wallWarning = wallTracker.update(wall, detections: [], calibration: saved)[0]
        XCTAssertEqual(wallWarning.label, DepthWarnings.labelWall)
        XCTAssertEqual(wallWarning.position, .ahead)
        XCTAssertTrue(wallWarning.urgent)
        XCTAssertEqual(DepthWarnings.phraseName(wallWarning), "depth_wall_very_close")

        let approach = DepthTracker()
        let series: [Float] = [2.0, 1.7, 1.45, 1.2]
        var last: [ObstacleWarning] = []
        for value in series {
            last = approach.update(scene { x in x <= 6 ? value : 3 }, detections: [], calibration: saved)
        }
        XCTAssertEqual(last.first?.label, DepthWarnings.labelApproaching)
        XCTAssertEqual(last.first?.position, .left)
        XCTAssertEqual(last.first?.priority, DepthWarnings.approachPriority)
        XCTAssertEqual(DepthWarnings.staticDebounce, 3)
        XCTAssertEqual(DepthWarnings.approachFrames, 4)
    }

    func testChairSuppressesUnknownAndFlatUncalibratedIsSilent() {
        let tracker = DepthTracker()
        let closeOnLeft = scene { x in x <= 6 ? 1 : 3 }
        let chair = DetectionBox(label: "chair", score: 0.9, left: 0, top: 0.40, right: 0.34, bottom: 0.95)
        for _ in 0..<3 {
            XCTAssertTrue(tracker.update(closeOnLeft, detections: [chair], calibration: saved).isEmpty)
        }
        let flat = DepthTracker()
        for _ in 0..<5 {
            XCTAssertTrue(flat.update(scene { _ in 1.5 }, detections: [], calibration: nil).isEmpty)
        }
    }

    func testLowLightHysteresis() {
        let controller = LowLightController()
        let dark = SceneLight(meanLuma: 0.08, lux: 2, iso: 1600, exposureTimeNs: 50_000_000)
        let bright = SceneLight(meanLuma: 0.62, lux: 200, iso: 100, exposureTimeNs: 8_000_000)
        XCTAssertFalse(controller.step(scene: dark, mode: .auto, torchAvailable: true, nowMs: 0).torchOn)
        let on = controller.step(scene: dark, mode: .auto, torchAvailable: true, nowMs: 2_500)
        XCTAssertTrue(on.torchOn)
        XCTAssertEqual(on.phraseResName, LowLight.phraseTurningOn)
        let caution = controller.step(scene: dark, mode: .auto, torchAvailable: true, nowMs: 4_500)
        XCTAssertEqual(caution.phraseResName, LowLight.phraseTooDark)
        let held = controller.step(scene: bright, mode: .auto, torchAvailable: true, nowMs: 5_000)
        XCTAssertTrue(held.torchOn)
        XCTAssertFalse(controller.step(scene: bright, mode: .auto, torchAvailable: true, nowMs: 8_000).torchOn)
        XCTAssertEqual(LowLight.scoreThreshold(lowLight: true), 0.40)
        XCTAssertEqual(LowLight.scoreThreshold(lowLight: false), 0.50)
        let faint = DetectionBox(label: "chair", score: 0.42, left: 0, top: 0.40, right: 0.34, bottom: 0.95)
        XCTAssertNil(WarningLogic.fromBox(faint, scoreThreshold: 0.50))
        XCTAssertNotNil(WarningLogic.fromBox(faint, scoreThreshold: 0.40))
        XCTAssertTrue(DepthWarnings.explains(faint, .left, scoreThreshold: 0.40))
    }

    func testYoloClassOrderAndDecode() {
        XCTAssertEqual(YoloDecode.classNames.count, 29)
        XCTAssertEqual(YoloDecode.classNames[0], "person")
        XCTAssertEqual(YoloDecode.classNames[11], "pothole")
        XCTAssertEqual(YoloDecode.classNames[28], "door")
        XCTAssertFalse(YoloDecode.classNames.contains("speed_bump"))
        let classes = YoloDecode.classNames.count
        let anchors = 2
        var output = [Float](repeating: 0, count: (4 + classes) * anchors)
        output[0 * anchors + 0] = 160
        output[1 * anchors + 0] = 160
        output[2 * anchors + 0] = 40
        output[3 * anchors + 0] = 40
        output[4 * anchors + 0] = 0.9
        output[4 * anchors + 1] = 0.2
        let found = YoloDecode.decode(
            output: output,
            shape: [1, 4 + classes, anchors],
            inputSize: 320,
            frameWidth: 640,
            frameHeight: 480,
            scoreThreshold: 0.5
        )
        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(found[0].label, "person")
    }

    func testCollectPhotosStayOffUntilAskedAndDoNotStealStop() {
        XCTAssertEqual(CollectCapture.defaultIntervalMs, 1_500)
        XCTAssertEqual(CollectCapture.jpegQuality, 90)
        XCTAssertEqual(CollectCapture.clampInterval(1), 500)
        XCTAssertEqual(CollectCapture.nextIntervalMs(1_500), 3_000)
        XCTAssertEqual(CollectCapture.intervalLabel(1_500), "1.5")
        XCTAssertFalse(CollectCapture.isHot(thermalStatus: 2, batteryTenthsC: nil))
        XCTAssertTrue(CollectCapture.isHot(thermalStatus: 3, batteryTenthsC: nil))
        let scheduler = CollectScheduler()
        XCTAssertNil(scheduler.poll(nowMs: 10_000, intervalMs: 1_500, hot: false, busy: false))
        scheduler.setEnabled(true)
        XCTAssertEqual(scheduler.poll(nowMs: 10_000, intervalMs: 1_500, hot: false, busy: false), .periodic)
        scheduler.onCaptured(nowMs: 10_000)
        scheduler.onWarning()
        XCTAssertEqual(scheduler.poll(nowMs: 11_000, intervalMs: 1_500, hot: false, busy: false), .warning)
        XCTAssertNil(scheduler.poll(nowMs: 11_100, intervalMs: 1_500, hot: true, busy: false))
        XCTAssertEqual(scheduler.poll(nowMs: 11_200, intervalMs: 1_500, hot: false, busy: false), .warning)
        XCTAssertEqual(CommandParser.parse("start collecting"), .startCollecting)
        XCTAssertEqual(CommandParser.parse("stop collecting"), .stopCollecting)
        XCTAssertEqual(CommandParser.parse("missed"), .missed)
        XCTAssertEqual(CommandParser.parse("stop"), .stop)
        XCTAssertEqual(CommandParser.parse("start"), .start)
        XCTAssertEqual(VoiceInterpreter.interpret("hey guide start collecting", requireWake: true), .startCollecting)
        let lighting = CollectCapture.lightingOf(SceneLight(meanLuma: 0.1, lux: 4, iso: 800, exposureTimeNs: 50))
        XCTAssertEqual(lighting.estimate, "dark")
        let json = CollectCapture.sidecarJson(
            timestampIso: "2026-10-25T12:00:00.000+00:00",
            event: .missed,
            detections: [DetectionBox(label: "person", score: 0.82, left: 0.1, top: 0.2, right: 0.4, bottom: 0.9)],
            depth: DepthSummary(aheadMedian: nil, corridorMin: nil, corridorMax: nil, finiteSamples: 0),
            lighting: lighting,
            gpsEnabled: false,
            gps: GpsReading(latitude: 1, longitude: 2, accuracyMeters: 3, timeIso: "t"),
            width: 4000,
            height: 3000,
            fullResolution: true
        )
        XCTAssertTrue(json.contains("\"event\": \"missed\""))
        XCTAssertTrue(json.contains("\"gps\": null"))
        XCTAssertTrue(json.contains("\"label\": \"person\""))
        XCTAssertTrue(json.contains("blur faces"))
        XCTAssertEqual(CollectCapture.folder, "RaahiSense/collect")
        let zip = CollectZip.store(files: [("2026-10-25/a.jpg", Data([1, 2, 3]))])
        XCTAssertEqual(zip.first, 0x50)
        XCTAssertEqual(zip.dropFirst().first, 0x4B)
        var settings = GuideSettings(collectEnabled: true, collectIntervalMs: 50, collectGps: true)
        settings = settings.normalized()
        XCTAssertEqual(settings.collectIntervalMs, CollectCapture.minIntervalMs)
        XCTAssertFalse(GuideSettings().collectEnabled)
        XCTAssertFalse(GuideSettings().collectGps)
    }

    func testWakeWordAndCommands() {
        XCTAssertEqual(VoiceInterpreter.interpret("hey guide", requireWake: true), .wakeOnly)
        XCTAssertEqual(VoiceInterpreter.interpret("hey guide what is ahead", requireWake: true), .whatIsAhead)
        XCTAssertEqual(VoiceInterpreter.interpret("stop", requireWake: true), .ignore)
        XCTAssertEqual(VoiceInterpreter.interpret("stop", requireWake: false), .stop)
        XCTAssertEqual(VoiceInterpreter.interpret("stop app", requireWake: false), .stop)
        XCTAssertEqual(VoiceInterpreter.interpret("exit", requireWake: false), .stop)
        XCTAssertEqual(VoiceInterpreter.interpret("close", requireWake: false), .stop)
        XCTAssertEqual(VoiceInterpreter.interpret("pause", requireWake: false), .pause)
        XCTAssertEqual(VoiceInterpreter.interpret("resume", requireWake: false), .resume)
        XCTAssertEqual(CommandParser.parse("very close stop sign ahead"), .stop)
        XCTAssertNil(CommandParser.parse("RaahiSense stopped"))
        XCTAssertFalse(RecognitionGate.accepts(speechActive: true, nowMs: 1_000, quietUntilMs: 0))
        XCTAssertFalse(RecognitionGate.accepts(speechActive: false, nowMs: 1_000, quietUntilMs: 1_400))
        XCTAssertTrue(RecognitionGate.accepts(speechActive: false, nowMs: 1_400, quietUntilMs: 1_400))
        XCTAssertEqual(RecognitionGate.tailMs, 400)
        XCTAssertEqual(CommandParser.parse("please speak louder"), .louder)
        XCTAssertEqual(VoiceInterpreter.interpret("the weather", requireWake: false), .unknown)
    }

    func testRotateDepth() {
        let field = DepthField(width: 2, height: 2, raw: [1, 2, 3, 4])
        let turned = DepthFields.rotate(field, degrees: 90)
        XCTAssertEqual(turned.raw[0], 3)
        XCTAssertEqual(turned.raw[1], 1)
        XCTAssertEqual(turned.raw[2], 4)
        XCTAssertEqual(turned.raw[3], 2)
        XCTAssertFalse(DepthCalibration(openRaw: 1, wallRaw: 1).usable())
        XCTAssertTrue(saved.usable())
    }

    private func scene(_ columnDepth: (Int) -> Float) -> DepthField {
        let width = 20
        let height = 10
        var raw = [Float](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                raw[y * width + x] = columnDepth(x)
            }
        }
        return DepthField(width: width, height: height, raw: raw)
    }
}
