import Foundation
import RaahiSenseLogic
import TensorFlowLite

/// Runs the same TensorFlow Lite files as Android. The detector is NCHW float.
/// Depth Anything V2 Small is stretched, ImageNet-normalized, and negated so larger means farther.
final class ModelEngine {
    private let yolo: Interpreter
    private let depth: Interpreter
    private let yoloSize: Int
    private let yoloChannelsFirst: Bool
    private let depthWidth: Int
    private let depthHeight: Int
    private let depthChannelsFirst: Bool
    private let yoloLock = NSLock()
    private let depthLock = NSLock()

    init(bundle: Bundle = .main) throws {
        yolo = try Self.makeInterpreter(named: "obstacles_v1", bundle: bundle)
        depth = try Self.makeInterpreter(named: "depth_anything_v2_small", bundle: bundle)
        let yoloShape = try yolo.input(at: 0).shape.dimensions
        yoloChannelsFirst = yoloShape.count == 4 && yoloShape[1] == 3 && yoloShape[2] > 3
        yoloSize = yoloChannelsFirst ? yoloShape[2] : (yoloShape.count >= 2 && yoloShape[1] > 3 ? yoloShape[1] : 320)
        let depthShape = try depth.input(at: 0).shape.dimensions
        depthChannelsFirst = depthShape.count == 4 && depthShape[1] == 3
        if depthChannelsFirst {
            depthHeight = depthShape[2]
            depthWidth = depthShape[3]
        } else if depthShape.count == 4 {
            depthHeight = depthShape[1]
            depthWidth = depthShape[2]
        } else {
            depthHeight = 518
            depthWidth = 686
        }
    }

    func detect(_ image: RGBImage, scoreThreshold: Float) throws -> [DetectionBox] {
        let input = letterbox(image, size: yoloSize, channelsFirst: yoloChannelsFirst, normalize: .unit)
        let output = try run(yolo, input: input, lock: yoloLock)
        return YoloDecode.decode(
            output: output.values,
            shape: output.shape,
            inputSize: yoloSize,
            frameWidth: image.width,
            frameHeight: image.height,
            scoreThreshold: scoreThreshold
        )
    }

    func estimateDepth(_ image: RGBImage) throws -> DepthField {
        let input = stretch(image, width: depthWidth, height: depthHeight, channelsFirst: depthChannelsFirst)
        let output = try run(depth, input: input, lock: depthLock)
        let height = output.shape.count >= 2 ? output.shape[output.shape.count - 2] : depthHeight
        let width = output.shape.count >= 1 ? output.shape[output.shape.count - 1] : depthWidth
        var raw = [Float](repeating: 0, count: width * height)
        let count = min(raw.count, output.values.count)
        for index in 0..<count {
            raw[index] = -output.values[index]
        }
        return DepthField(width: width, height: height, raw: raw)
    }

    private struct TensorOut {
        var values: [Float]
        var shape: [Int]
    }

    private func run(_ interpreter: Interpreter, input: [Float], lock: NSLock) throws -> TensorOut {
        lock.lock()
        defer { lock.unlock() }
        let data = input.withUnsafeBufferPointer { Data(buffer: $0) }
        try interpreter.copy(data, toInputAt: 0)
        try interpreter.invoke()
        let tensor = try interpreter.output(at: 0)
        let values = floats(from: tensor)
        return TensorOut(values: values, shape: tensor.shape.dimensions)
    }

    private func floats(from tensor: Tensor) -> [Float] {
        if tensor.dataType == .float32 {
            return tensor.data.withUnsafeBytes { raw in
                Array(raw.bindMemory(to: Float.self))
            }
        }
        let scale = tensor.quantizationParameters?.scale ?? 1
        let zero = tensor.quantizationParameters?.zeroPoint ?? 0
        return tensor.data.map { byte in
            (Float(Int(byte) - zero)) * scale
        }
    }

    private enum Norm {
        case unit
        case imageNet
    }

    private func letterbox(_ image: RGBImage, size: Int, channelsFirst: Bool, normalize: Norm) -> [Float] {
        let box = YoloDecode.letterbox(frameWidth: image.width, frameHeight: image.height, inputSize: size)
        var canvas = [UInt8](repeating: 114, count: size * size * 3)
        for y in 0..<image.height {
            for x in 0..<image.width {
                let dx = Int(Float(x) * box.scale + box.padX)
                let dy = Int(Float(y) * box.scale + box.padY)
                guard dx >= 0, dy >= 0, dx < size, dy < size else { continue }
                let source = (y * image.width + x) * 3
                let dest = (dy * size + dx) * 3
                canvas[dest] = image.rgb[source]
                canvas[dest + 1] = image.rgb[source + 1]
                canvas[dest + 2] = image.rgb[source + 2]
            }
        }
        return planar(canvas, width: size, height: size, channelsFirst: channelsFirst, normalize: normalize)
    }

    private func stretch(_ image: RGBImage, width: Int, height: Int, channelsFirst: Bool) -> [Float] {
        var canvas = [UInt8](repeating: 0, count: width * height * 3)
        guard image.width > 0, image.height > 0 else {
            return planar(canvas, width: width, height: height, channelsFirst: channelsFirst, normalize: .imageNet)
        }
        for y in 0..<height {
            let sy = min(image.height - 1, y * image.height / height)
            for x in 0..<width {
                let sx = min(image.width - 1, x * image.width / width)
                let source = (sy * image.width + sx) * 3
                let dest = (y * width + x) * 3
                canvas[dest] = image.rgb[source]
                canvas[dest + 1] = image.rgb[source + 1]
                canvas[dest + 2] = image.rgb[source + 2]
            }
        }
        return planar(canvas, width: width, height: height, channelsFirst: channelsFirst, normalize: .imageNet)
    }

    private func planar(_ rgb: [UInt8], width: Int, height: Int, channelsFirst: Bool, normalize: Norm) -> [Float] {
        let count = width * height
        var floats = [Float](repeating: 0, count: count * 3)
        for index in 0..<count {
            let red = Float(rgb[index * 3]) / 255
            let green = Float(rgb[index * 3 + 1]) / 255
            let blue = Float(rgb[index * 3 + 2]) / 255
            let r: Float
            let g: Float
            let b: Float
            switch normalize {
            case .unit:
                r = red
                g = green
                b = blue
            case .imageNet:
                r = (red - 0.485) / 0.229
                g = (green - 0.456) / 0.224
                b = (blue - 0.406) / 0.225
            }
            if channelsFirst {
                floats[index] = r
                floats[count + index] = g
                floats[count * 2 + index] = b
            } else {
                floats[index * 3] = r
                floats[index * 3 + 1] = g
                floats[index * 3 + 2] = b
            }
        }
        return floats
    }

    private static func makeInterpreter(named name: String, bundle: Bundle) throws -> Interpreter {
        guard let path = bundle.path(forResource: name, ofType: "tflite") else {
            throw ModelError.missing(name)
        }
        var options = Interpreter.Options()
        options.threadCount = 2
        let interpreter = try Interpreter(modelPath: path, options: options)
        try interpreter.allocateTensors()
        return interpreter
    }
}

enum ModelError: Error {
    case missing(String)
}

struct RGBImage {
    var width: Int
    var height: Int
    var rgb: [UInt8]
}
