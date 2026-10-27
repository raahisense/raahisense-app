import Foundation

/// Turns a small YOLO head into normalized boxes. Class order matches training.
public enum YoloDecode {
    public static let classNames: [String] = [
        "person", "bicycle", "motorcycle", "car", "bus", "truck", "cow", "dog",
        "auto_rickshaw", "cycle_rickshaw", "handcart", "pothole", "manhole_open_drain",
        "curb", "stairs", "pole_bollard", "barrier", "street_furniture", "tree",
        "cat", "chair", "couch", "dining table", "bed", "toilet", "potted plant",
        "refrigerator", "tv", "door", "wall",
        "shoe", "bag", "toy", "bin", "appliance", "cabinet",
    ]

    public struct Letterbox {
        public var scale: Float
        public var padX: Float
        public var padY: Float
    }

    public static func letterbox(frameWidth: Int, frameHeight: Int, inputSize: Int) -> Letterbox {
        let scale = min(Float(inputSize) / Float(frameWidth), Float(inputSize) / Float(frameHeight))
        let newW = Float(frameWidth) * scale
        let newH = Float(frameHeight) * scale
        return Letterbox(scale: scale, padX: (Float(inputSize) - newW) / 2, padY: (Float(inputSize) - newH) / 2)
    }

    public static func decode(
        output: [Float],
        shape: [Int],
        inputSize: Int,
        frameWidth: Int,
        frameHeight: Int,
        scoreThreshold: Float,
        maxDetections: Int = 10
    ) -> [DetectionBox] {
        guard shape.count == 3, shape[0] == 1 else { return [] }
        let raw = shape[2] == 6
            ? readEndToEnd(output, boxes: shape[1], inputSize: inputSize)
            : readRawHead(output, shape: shape, inputSize: inputSize)
        let box = letterbox(frameWidth: frameWidth, frameHeight: frameHeight, inputSize: inputSize)
        var mapped: [DetectionBox] = []
        for item in raw {
            guard item.score >= scoreThreshold else { continue }
            guard item.classId >= 0, item.classId < classNames.count else { continue }
            let name = classNames[item.classId]
            let left = ((item.x1 - box.padX) / box.scale) / Float(frameWidth)
            let top = ((item.y1 - box.padY) / box.scale) / Float(frameHeight)
            let right = ((item.x2 - box.padX) / box.scale) / Float(frameWidth)
            let bottom = ((item.y2 - box.padY) / box.scale) / Float(frameHeight)
            if right - left < 0.002 || bottom - top < 0.002 { continue }
            mapped.append(DetectionBox(
                label: name,
                score: item.score,
                left: min(1, max(0, left)),
                top: min(1, max(0, top)),
                right: min(1, max(0, right)),
                bottom: min(1, max(0, bottom))
            ))
        }
        return nms(mapped, maxDetections)
    }

    private struct RawBox {
        var x1, y1, x2, y2, score: Float
        var classId: Int
    }

    private static func readEndToEnd(_ output: [Float], boxes: Int, inputSize: Int) -> [RawBox] {
        var out: [RawBox] = []
        for i in 0..<boxes {
            let base = i * 6
            if base + 5 >= output.count { break }
            out.append(RawBox(
                x1: output[base], y1: output[base + 1], x2: output[base + 2], y2: output[base + 3],
                score: output[base + 4], classId: Int(output[base + 5])
            ))
        }
        return scaleIfNormalized(out, inputSize)
    }

    private static func readRawHead(_ output: [Float], shape: [Int], inputSize: Int) -> [RawBox] {
        let channelsFirst = shape[1] == 4 + classNames.count
        let boxesFirst = shape[2] == 4 + classNames.count
        guard channelsFirst || boxesFirst else { return [] }
        let channels = 4 + classNames.count
        let boxes = channelsFirst ? shape[2] : shape[1]
        var out: [RawBox] = []
        for anchor in 0..<boxes {
            func at(_ channel: Int) -> Float {
                let index = channelsFirst ? channel * boxes + anchor : anchor * channels + channel
                return output[index]
            }
            let cx = at(0)
            let cy = at(1)
            let w = at(2)
            let h = at(3)
            var best: Float = 0
            var bestId = -1
            for cls in classNames.indices {
                let score = at(4 + cls)
                if score > best {
                    best = score
                    bestId = cls
                }
            }
            out.append(RawBox(
                x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2,
                score: best, classId: bestId
            ))
        }
        return scaleIfNormalized(out, inputSize)
    }

    private static func scaleIfNormalized(_ boxes: [RawBox], _ inputSize: Int) -> [RawBox] {
        var peak: Float = 0
        for box in boxes {
            peak = max(peak, max(max(box.x1, box.x2), max(box.y1, box.y2)))
        }
        if peak > 1.5 { return boxes }
        let size = Float(inputSize)
        return boxes.map { box in
            RawBox(
                x1: box.x1 * size, y1: box.y1 * size, x2: box.x2 * size, y2: box.y2 * size,
                score: box.score, classId: box.classId
            )
        }
    }

    private static func nms(_ boxes: [DetectionBox], _ maxDetections: Int) -> [DetectionBox] {
        var sorted = boxes.sorted { $0.score > $1.score }
        var kept: [DetectionBox] = []
        while !sorted.isEmpty && kept.count < maxDetections {
            let winner = sorted.removeFirst()
            kept.append(winner)
            sorted.removeAll { other in other.label == winner.label && iou(winner, other) > 0.45 }
        }
        return kept
    }

    private static func iou(_ a: DetectionBox, _ b: DetectionBox) -> Float {
        let x1 = max(a.left, b.left)
        let y1 = max(a.top, b.top)
        let x2 = min(a.right, b.right)
        let y2 = min(a.bottom, b.bottom)
        let inter = max(0, x2 - x1) * max(0, y2 - y1)
        let areaA = max(0, a.right - a.left) * max(0, a.bottom - a.top)
        let areaB = max(0, b.right - b.left) * max(0, b.bottom - b.top)
        let union = areaA + areaB - inter
        if union <= 0 { return 0 }
        return inter / union
    }
}
