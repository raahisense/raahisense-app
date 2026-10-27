#!/usr/bin/env python3
"""Score the current phone model and a candidate on the same held-out pictures.

The low-light half is the test split darkened with training/lowlight.py.
The candidate replaces app assets only when its int8 file is at least as
large as the current file and its shared-class mAP50-95 is higher on the
normal test split, without a meaningful drop on the darkened split.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np
import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from class_map import BASELINE_CLASSES, CLASSES, INDOOR_CLASSES
from lowlight import darken
from map_metric import average_precision, yolo_to_xyxy

ROOT = Path("/workspace")
DATA = Path(os.environ.get("OBSTACLE_DATA", "/workspace/datasets/obstacles_v2/data.yaml"))
ASSET = ROOT / "app/src/main/assets/obstacles_v1.tflite"
BASELINE_COPY = ROOT / "training/runs/obstacles_v1_baseline.tflite"
DARK = ROOT / "datasets/obstacles_v2_darktest"
REPORT = ROOT / "training/metrics/compare_report.json"


def load_ground_truth(image_dir: Path, label_dir: Path, class_names: list[str]) -> tuple[list[Path], dict]:
    images = sorted(path for path in image_dir.iterdir() if path.suffix.lower() in {".jpg", ".jpeg", ".png"})
    ground = {name: {} for name in class_names}
    for path in images:
        label = label_dir / f"{path.stem}.txt"
        if not label.exists():
            continue
        # Size is filled when the prediction result reports orig_shape. Store normalized for now.
        rows = []
        for line in label.read_text().splitlines():
            parts = line.split()
            if len(parts) < 5:
                continue
            index = int(float(parts[0]))
            if index < 0 or index >= len(class_names):
                continue
            rows.append((class_names[index], list(map(float, parts[1:5]))))
        for name, box in rows:
            ground[name].setdefault(path.name, []).append(box)
    return images, ground


def ensure_dark(images: list[Path], label_dir: Path) -> Path:
    out_images = DARK / "images/test"
    out_labels = DARK / "labels/test"
    out_images.mkdir(parents=True, exist_ok=True)
    out_labels.mkdir(parents=True, exist_ok=True)
    import cv2

    for index, path in enumerate(images):
        dest = out_images / path.name
        if not dest.exists() or dest.stat().st_size == 0:
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                continue
            cv2.imwrite(str(dest), darken(image, seed=index + 1))
        label = label_dir / f"{path.stem}.txt"
        link = out_labels / f"{path.stem}.txt"
        if label.exists() and not link.exists():
            link.symlink_to(label)
    return out_images


def predict(model_path: Path, images: list[Path], class_names: list[str]) -> dict:
    from ultralytics import YOLO

    model = YOLO(str(model_path), task="detect")
    predictions = {name: [] for name in class_names}
    results = model.predict(
        source=[str(path) for path in images],
        imgsz=320,
        conf=0.001,
        iou=0.7,
        max_det=300,
        device="cpu",
        stream=True,
        verbose=False,
        batch=8,
    )
    for result in results:
        name = Path(result.path).name
        boxes = result.boxes
        if boxes is None or len(boxes) == 0:
            continue
        xyxy = boxes.xyxy.cpu().numpy()
        confs = boxes.conf.cpu().numpy()
        clss = boxes.cls.cpu().numpy().astype(int)
        height, width = result.orig_shape
        for box, score, class_id in zip(xyxy, confs, clss):
            if class_id < 0 or class_id >= len(class_names):
                continue
            coords = box.astype(np.float32)
            if float(np.max(coords)) <= 1.5:
                coords = coords * np.array([width, height, width, height], dtype=np.float32)
            predictions[class_names[class_id]].append((name, float(score), coords))
    return predictions


def ground_pixels(images: list[Path], normalized: dict, class_names: list[str]) -> dict:
    """Convert stored YOLO boxes using each file's pixel size."""
    from PIL import Image

    sizes = {}
    for path in images:
        with Image.open(path) as image:
            sizes[path.name] = image.size
    ground = {name: {} for name in class_names}
    for name in class_names:
        for image_name, boxes in normalized.get(name, {}).items():
            if image_name not in sizes:
                continue
            width, height = sizes[image_name]
            ground[name][image_name] = [yolo_to_xyxy(*box, width, height) for box in boxes]
    return ground


def pack(score: dict, class_names: list[str]) -> dict:
    return {
        "map50": score["map50"],
        "map50_95": score["map50_95"],
        "per_class_map50": [
            {"class": name, "map50": score["per_class_map50"].get(name)} for name in class_names
        ],
        "per_class_map50_95": [
            {"class": name, "map50_95": score["per_class_map50_95"].get(name)} for name in class_names
        ],
    }


def indoor_rows(score: dict, ground: dict, names: list[str]) -> list[dict]:
    """Per-class scores for household obstacles, separate from the street mean."""
    rows = []
    for name in names:
        boxes = ground.get(name, {})
        npos = sum(len(items) for items in boxes.values())
        rows.append(
            {
                "class": name,
                "test_images": sum(1 for items in boxes.values() if items),
                "test_boxes": npos,
                "map50": score["per_class_map50"].get(name),
                "map50_95": score["per_class_map50_95"].get(name),
            }
        )
    return rows


def indoor_mean(rows: list[dict], key: str) -> float | None:
    values = [row[key] for row in rows if row[key] is not None]
    if not values:
        return None
    return float(sum(values) / len(values))


def mean_shared(score: dict, names: list[str]) -> float | None:
    values = [score["per_class_map50_95"][name] for name in names if score["per_class_map50_95"].get(name) is not None]
    if not values:
        return None
    return float(sum(values) / len(values))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=ASSET)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    if not DATA.exists():
        raise SystemExit(f"missing {DATA}")
    if not args.candidate.exists():
        raise SystemExit(f"missing candidate {args.candidate}")
    BASELINE_COPY.parent.mkdir(parents=True, exist_ok=True)
    source_baseline = args.baseline
    if not BASELINE_COPY.exists():
        if not source_baseline.exists():
            raise SystemExit(f"missing baseline {source_baseline}")
        shutil.copy(source_baseline, BASELINE_COPY)
    baseline = BASELINE_COPY
    with DATA.open() as handle:
        data = yaml.safe_load(handle)
    root = Path(data["path"])
    image_dir = root / "images/test"
    label_dir = root / "labels/test"
    images, normalized = load_ground_truth(image_dir, label_dir, CLASSES)
    if not images:
        raise SystemExit(f"no test images in {image_dir}")
    leaked = [path.name for path in images if not path.stem.isdigit()]
    if leaked:
        raise SystemExit(f"test split is not the real-photo set: {leaked[:5]}")
    dark_images_dir = ensure_dark(images, label_dir)
    dark_images = sorted(dark_images_dir.iterdir())
    print(f"test images {len(images)} dark {len(dark_images)}", flush=True)
    ground = ground_pixels(images, normalized, CLASSES)
    # Dark pictures keep the same pixel size and the same labels.
    print("predict baseline", flush=True)
    base_pred = predict(baseline, images, BASELINE_CLASSES)
    print("predict candidate", flush=True)
    cand_pred = predict(args.candidate, images, CLASSES)
    print("predict baseline dark", flush=True)
    base_dark = predict(baseline, dark_images, BASELINE_CLASSES)
    print("predict candidate dark", flush=True)
    cand_dark = predict(args.candidate, dark_images, CLASSES)
    base_score = average_precision(base_pred, ground, BASELINE_CLASSES)
    cand_score = average_precision(cand_pred, ground, CLASSES)
    base_dark_score = average_precision(base_dark, ground, BASELINE_CLASSES)
    cand_dark_score = average_precision(cand_dark, ground, CLASSES)
    indoor_normal_base = indoor_rows(base_score, ground, INDOOR_CLASSES)
    indoor_normal_cand = indoor_rows(cand_score, ground, INDOOR_CLASSES)
    indoor_dark_base = indoor_rows(base_dark_score, ground, INDOOR_CLASSES)
    indoor_dark_cand = indoor_rows(cand_dark_score, ground, INDOOR_CLASSES)
    indoor = {
        "classes": INDOOR_CLASSES,
        "note": "Household classes scored on the same held-out test images, including the darkened copies. The current model has no shoe, bag, toy, bin, appliance, or cabinet outputs, so those baseline cells are empty.",
        "normal": {"baseline": indoor_normal_base, "candidate": indoor_normal_cand},
        "lowlight": {"baseline": indoor_dark_base, "candidate": indoor_dark_cand},
        "candidate_mean_map50_95": indoor_mean(indoor_normal_cand, "map50_95"),
        "candidate_lowlight_mean_map50_95": indoor_mean(indoor_dark_cand, "map50_95"),
        "baseline_mean_map50_95_where_defined": indoor_mean(indoor_normal_base, "map50_95"),
        "baseline_lowlight_mean_map50_95_where_defined": indoor_mean(indoor_dark_base, "map50_95"),
    }
    shared = [name for name in BASELINE_CLASSES if base_score["per_class_map50_95"].get(name) is not None]
    base_mean = mean_shared(base_score, shared)
    cand_mean = mean_shared(cand_score, shared)
    base_dark_mean = mean_shared(base_dark_score, shared)
    cand_dark_mean = mean_shared(cand_dark_score, shared)
    larger = args.candidate.stat().st_size >= baseline.stat().st_size
    better_day = base_mean is not None and cand_mean is not None and cand_mean >= base_mean + 0.005
    lowlight_ok = (
        base_dark_mean is None
        or cand_dark_mean is None
        or cand_dark_mean >= base_dark_mean - 0.01
    )
    ship = bool(larger and better_day and lowlight_ok)
    report = {
        "test_images": len(images),
        "shared_classes_with_ground_truth": shared,
        "baseline": {
            "file": str(baseline),
            "bytes": baseline.stat().st_size,
            "normal": pack(base_score, BASELINE_CLASSES),
            "lowlight": pack(base_dark_score, BASELINE_CLASSES),
            "shared_map50_95": base_mean,
            "shared_lowlight_map50_95": base_dark_mean,
        },
        "candidate": {
            "file": str(args.candidate),
            "bytes": args.candidate.stat().st_size,
            "normal": pack(cand_score, CLASSES),
            "lowlight": pack(cand_dark_score, CLASSES),
            "shared_map50_95": cand_mean,
            "shared_lowlight_map50_95": cand_dark_mean,
            "wall_map50_95": cand_score["per_class_map50_95"].get("wall"),
            "wall_lowlight_map50_95": cand_dark_score["per_class_map50_95"].get("wall"),
        },
        "indoor": indoor,
        "ship": ship,
        "reasons": {
            "candidate_file_is_at_least_as_large": larger,
            "shared_map50_95_improved_by_0.005": better_day,
            "lowlight_shared_map50_95_not_worse_by_more_than_0.01": lowlight_ok,
        },
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2))
    print(json.dumps({
        "ship": ship,
        "shared": [base_mean, cand_mean],
        "lowlight": [base_dark_mean, cand_dark_mean],
        "indoor_map50_95": [indoor["baseline_mean_map50_95_where_defined"], indoor["candidate_mean_map50_95"]],
        "indoor_lowlight_map50_95": [indoor["baseline_lowlight_mean_map50_95_where_defined"], indoor["candidate_lowlight_mean_map50_95"]],
    }, indent=2))
    if ship and args.install:
        shutil.copy(args.candidate, ASSET)
        print(f"installed {ASSET} bytes {ASSET.stat().st_size}", flush=True)
    elif ship:
        print("ship is true. Re-run with --install to copy the file into the app.", flush=True)
    else:
        print("candidate was not installed", flush=True)


if __name__ == "__main__":
    main()
