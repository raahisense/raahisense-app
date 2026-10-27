"""COCO-style average precision for one detector pass.

Predictions and ground truth are xyxy boxes in pixels. A class with no
ground-truth boxes is left out of the mean so an empty curb split cannot
drag the score to zero.
"""
from __future__ import annotations

import numpy as np


def iou(a: np.ndarray, b: np.ndarray) -> float:
    x0 = max(float(a[0]), float(b[0]))
    y0 = max(float(a[1]), float(b[1]))
    x1 = min(float(a[2]), float(b[2]))
    y1 = min(float(a[3]), float(b[3]))
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    if inter <= 0:
        return 0.0
    area_a = max(0.0, float(a[2] - a[0])) * max(0.0, float(a[3] - a[1]))
    area_b = max(0.0, float(b[2] - b[0])) * max(0.0, float(b[3] - b[1]))
    union = area_a + area_b - inter
    if union <= 0:
        return 0.0
    return inter / union


def _ap(flags: list[int], npos: int) -> float:
    if npos <= 0:
        return float("nan")
    tp = np.cumsum(flags)
    fp = np.cumsum(1 - np.asarray(flags))
    recall = tp / npos
    precision = tp / np.maximum(tp + fp, 1e-9)
    mrec = np.concatenate(([0.0], recall, [1.0]))
    mpre = np.concatenate(([1.0], precision, [0.0]))
    for index in range(len(mpre) - 1, 0, -1):
        mpre[index - 1] = max(mpre[index - 1], mpre[index])
    changes = np.where(mrec[1:] != mrec[:-1])[0]
    return float(np.sum((mrec[changes + 1] - mrec[changes]) * mpre[changes + 1]))


def average_precision(
    predictions: dict[str, list[tuple[str, float, np.ndarray]]],
    ground_truth: dict[str, dict[str, list[np.ndarray]]],
    class_names: list[str],
    iou_thresholds: list[float] | None = None,
) -> dict:
    """predictions[class] = [(image_id, score, xyxy), ...]
    ground_truth[class][image_id] = [xyxy, ...]
    """
    if iou_thresholds is None:
        iou_thresholds = [round(0.50 + 0.05 * step, 2) for step in range(10)]
    per_class_50 = {}
    per_class_50_95 = {}
    for name in class_names:
        gt = ground_truth.get(name, {})
        npos = sum(len(boxes) for boxes in gt.values())
        preds = sorted(predictions.get(name, []), key=lambda item: -item[1])
        if npos == 0:
            per_class_50[name] = None
            per_class_50_95[name] = None
            continue
        aps = []
        for threshold in iou_thresholds:
            matched: set[tuple[str, int]] = set()
            flags = []
            for image_id, _score, box in preds:
                candidates = gt.get(image_id, [])
                best_iou = 0.0
                best_index = -1
                for index, truth in enumerate(candidates):
                    if (image_id, index) in matched:
                        continue
                    score = iou(box, truth)
                    if score > best_iou:
                        best_iou = score
                        best_index = index
                if best_index >= 0 and best_iou >= threshold:
                    matched.add((image_id, best_index))
                    flags.append(1)
                else:
                    flags.append(0)
            aps.append(_ap(flags, npos))
        per_class_50[name] = aps[0]
        per_class_50_95[name] = float(np.mean(aps))

    def mean(values: dict[str, float | None], names: list[str]) -> float | None:
        chosen = [values[name] for name in names if values.get(name) is not None]
        if not chosen:
            return None
        return float(sum(chosen) / len(chosen))

    return {
        "map50": mean(per_class_50, class_names),
        "map50_95": mean(per_class_50_95, class_names),
        "per_class_map50": per_class_50,
        "per_class_map50_95": per_class_50_95,
        "images": len({image_id for rows in predictions.values() for image_id, _, _ in rows}
                      | {image_id for rows in ground_truth.values() for image_id in rows}),
    }


def yolo_to_xyxy(xc: float, yc: float, w: float, h: float, width: int, height: int) -> np.ndarray:
    return np.array(
        [
            (xc - w / 2) * width,
            (yc - h / 2) * height,
            (xc + w / 2) * width,
            (yc + h / 2) * height,
        ],
        dtype=np.float32,
    )


def _self_test() -> None:
    gt = {"person": {"a": [np.array([0, 0, 10, 10], dtype=np.float32)]}}
    preds = {"person": [("a", 0.9, np.array([0, 0, 10, 10], dtype=np.float32))]}
    score = average_precision(preds, gt, ["person"])
    assert score["map50"] is not None and score["map50"] > 0.99, score
    empty = average_precision({}, {"curb": {}}, ["curb"])
    assert empty["map50"] is None
    miss = average_precision(
        {"person": [("a", 0.9, np.array([100, 100, 110, 110], dtype=np.float32))]},
        gt,
        ["person"],
    )
    assert miss["map50"] is not None and miss["map50"] < 0.01, miss
    print("map_metric self-test ok", score["map50"])


if __name__ == "__main__":
    _self_test()
