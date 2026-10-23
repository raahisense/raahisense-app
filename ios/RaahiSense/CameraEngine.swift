import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import RaahiSenseLogic

struct CameraFrame {
    var image: RGBImage
    var scene: SceneLight
    var torchAvailable: Bool
}

/// Rear camera, portrait, so a shirt pocket with the camera facing forward matches the walk.
final class CameraEngine: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFrame: ((CameraFrame) -> Void)?
    var onFailed: (() -> Void)?

    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "app.raahisense.camera")
    private var device: AVCaptureDevice?
    private var running = false
    private var appliedTorch: Bool?
    private var appliedBias: Float?

    func start() {
        queue.async { [weak self] in
            self?.startOnQueue()
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.running = false
            self.setTorchOnQueue(false)
            self.appliedBias = nil
        }
    }

    func setTorch(on: Bool) {
        queue.async { [weak self] in
            self?.setTorchOnQueue(on)
        }
    }

    /// +1 EV while the scene is dark. Zero when it is not.
    func setLowLightBoost(_ on: Bool) {
        queue.async { [weak self] in
            self?.setBiasOnQueue(on ? 1 : 0)
        }
    }

    private func startOnQueue() {
        if running && session.isRunning { return }
        do {
            if session.inputs.isEmpty {
                try configure()
            }
            session.startRunning()
            running = true
        } catch {
            running = false
            onFailed?()
        }
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.automaticallyConfiguresApplicationAudioSession = false
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw CameraError.missing
        }
        device = camera
        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw CameraError.missing }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { throw CameraError.missing }
        session.addOutput(output)
        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        } else if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }
        if let connection = output.connection(with: .video) {
            if #available(iOS 17.0, *) {
                let angle: CGFloat = 90
                if connection.isVideoRotationAngleSupported(angle) {
                    connection.videoRotationAngle = angle
                }
            } else if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
        }
    }

    private func setTorchOnQueue(_ on: Bool) {
        guard let device, device.hasTorch else { return }
        if appliedTorch == on { return }
        do {
            try device.lockForConfiguration()
            if on && device.isTorchAvailable && device.isTorchModeSupported(.on) {
                let level = min(1, AVCaptureDevice.maxAvailableTorchLevel)
                try device.setTorchModeOn(level: level)
                appliedTorch = true
            } else if device.isTorchModeSupported(.off) {
                device.torchMode = .off
                appliedTorch = false
            }
            device.unlockForConfiguration()
        } catch {
            appliedTorch = nil
        }
    }

    private func setBiasOnQueue(_ bias: Float) {
        guard let device else { return }
        if appliedBias == bias { return }
        let clamped = min(device.maxExposureTargetBias, max(device.minExposureTargetBias, bias))
        do {
            try device.lockForConfiguration()
            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            }
            device.setExposureTargetBias(clamped, completionHandler: nil)
            device.unlockForConfiguration()
            appliedBias = bias
        } catch {
            appliedBias = nil
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard running, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard let image = Self.downsample(buffer) else { return }
        let scene = sceneLight(for: image)
        let torchAvailable = device?.hasTorch == true && device?.isTorchAvailable == true
        onFrame?(CameraFrame(image: image.image, scene: scene, torchAvailable: torchAvailable))
    }

    private func sceneLight(for sample: SampledImage) -> SceneLight {
        let iso = device.map { Int($0.iso.rounded()) }
        let exposureNs: Int64? = device.map { device in
            let seconds = CMTimeGetSeconds(device.exposureDuration)
            guard seconds.isFinite, seconds > 0 else { return 0 }
            return Int64(seconds * 1_000_000_000)
        }
        return SceneLight(meanLuma: sample.meanLuma, lux: nil, iso: iso, exposureTimeNs: exposureNs)
    }

    private struct SampledImage {
        var image: RGBImage
        var meanLuma: Float
    }

    private static func downsample(_ buffer: CVPixelBuffer) -> SampledImage? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        guard width > 0, height > 0 else { return nil }
        let longSide = max(width, height)
        let scale = min(1, 640.0 / Double(longSide))
        let outW = max(1, Int((Double(width) * scale).rounded()))
        let outH = max(1, Int((Double(height) * scale).rounded()))
        var rgb = [UInt8](repeating: 0, count: outW * outH * 3)
        var lumaSum: Float = 0
        let pointer = base.assumingMemoryBound(to: UInt8.self)
        for y in 0..<outH {
            let sy = min(height - 1, y * height / outH)
            let row = pointer.advanced(by: sy * rowBytes)
            for x in 0..<outW {
                let sx = min(width - 1, x * width / outW)
                let pixel = row.advanced(by: sx * 4)
                let blue = pixel[0]
                let green = pixel[1]
                let red = pixel[2]
                let dest = (y * outW + x) * 3
                rgb[dest] = red
                rgb[dest + 1] = green
                rgb[dest + 2] = blue
                lumaSum += (0.299 * Float(red) + 0.587 * Float(green) + 0.114 * Float(blue)) / 255
            }
        }
        let count = Float(outW * outH)
        return SampledImage(
            image: RGBImage(width: outW, height: outH, rgb: rgb),
            meanLuma: count > 0 ? lumaSum / count : .nan
        )
    }
}

enum CameraError: Error {
    case missing
}
