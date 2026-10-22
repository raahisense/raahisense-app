#!/usr/bin/env python3
"""Measure and fine-tune the walking detector on darkened pictures.

The darkened split is the existing test set with gamma, darkening, noise,
and motion blur. Night datasets with a non-commercial or research-only
license are not added. This script does not quantize the depth model.

Numbers are from this CPU. They are not a Helio G99 measurement.
"""
from __future__ import annotations

import json
import os
import shutil
import time
from pathlib import Path

import cv2
import numpy as np
import yaml

ROOT = Path("/workspace")
DATA = ROOT / "datasets/obstacles_v1/data.yaml"
WEIGHTS = ROOT / "training/runs/obstacles19/weights/best.pt"
RUN = ROOT / "training/runs/obstacles_lowlight"
DARK = ROOT / "datasets/obstacles_v1_darktest"
METRICS = ROOT / "training/metrics/lowlight_report.json"
ASSET = ROOT / "app/src/main/assets/obstacles_v1.tflite"


def darken(image: np.ndarray, seed: int) -> np.ndarray:
    """Deterministic low-light view: dim, gamma, noise, horizontal motion blur."""
    rng = np.random.default_rng(seed)
    img = image.astype(np.float32) / 255.0
    img = np.clip(img * 0.35, 0.0, 1.0)
    img = np.power(img, 1.8)
    img = img + rng.normal(0.0, 0.02, img.shape).astype(np.float32)
    img = np.clip(img, 0.0, 1.0)
    kernel = np.zeros((1, 7), np.float32)
    kernel[0, :] = 1.0 / 7.0
    img = cv2.filter2D(img, -1, kernel)
    return np.clip(img * 255.0, 0, 255).astype(np.uint8)


def ensure_dark_split() -> Path:
    with DATA.open() as handle:
        source = yaml.safe_load(handle)
    test_dir = Path(source["path"]) / source["test"]
    if not test_dir.is_dir():
        test_dir = Path(source["path"]) / "images/test"
    label_dir = Path(str(test_dir).replace("/images/", "/labels/"))
    out_images = DARK / "images/test"
    out_labels = DARK / "labels/test"
    out_images.mkdir(parents=True, exist_ok=True)
    out_labels.mkdir(parents=True, exist_ok=True)
    names = sorted(p for p in test_dir.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    for index, path in enumerate(names):
        dest = out_images / path.name
        if not dest.is_file() or dest.stat().st_size == 0:
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                continue
            cv2.imwrite(str(dest), darken(image, seed=index + 1))
        label = label_dir / f"{path.stem}.txt"
        link = out_labels / f"{path.stem}.txt"
        if label.is_file() and not link.exists():
            link.symlink_to(label)
    dark_yaml = DARK / "data.yaml"
    payload = {
        "path": str(DARK),
        "train": "images/test",
        "val": "images/test",
        "test": "images/test",
        "names": source["names"],
    }
    dark_yaml.write_text(yaml.safe_dump(payload, sort_keys=False))
    return dark_yaml


def per_class(metrics) -> list[dict]:
    names = metrics.names if hasattr(metrics, "names") else {}
    maps = getattr(metrics.box, "maps", None)
    rows = []
    if maps is None:
        return rows
    for index, value in enumerate(maps):
        rows.append({"class": names.get(index, str(index)), "map50_95": float(value)})
    return rows


def evaluate(model, data: Path) -> dict:
    metrics = model.val(
        data=str(data),
        split="test",
        imgsz=320,
        batch=8,
        device="cpu",
        workers=2,
        plots=False,
        verbose=False,
    )
    return {
        "map50": float(metrics.box.map50),
        "map50_95": float(metrics.box.map),
        "per_class_map50_95": per_class(metrics),
    }


def install_batch_darkening():
    """Darken, gamma, noise, and motion blur on about half of each training batch.

    Ultralytics does not pass the batch into on_train_batch_start, so this wraps
    the detector trainer after it has scaled images to 0..1.
    """
    import torch
    from ultralytics.models.yolo.detect.train import DetectionTrainer

    original = DetectionTrainer.preprocess_batch

    def preprocess_batch(self, batch):
        batch = original(self, batch)
        imgs = batch.get("img")
        if imgs is None or not torch.is_tensor(imgs):
            return batch
        if imgs.dtype not in (torch.float16, torch.float32):
            return batch
        count = imgs.shape[0]
        pick = torch.rand(count, device=imgs.device) < 0.55
        if not bool(pick.any()):
            return batch
        chosen = imgs[pick]
        scale = 0.25 + 0.35 * torch.rand(chosen.shape[0], 1, 1, 1, device=imgs.device)
        gamma = 1.4 + 1.2 * torch.rand(chosen.shape[0], 1, 1, 1, device=imgs.device)
        darkened = torch.clamp(chosen * scale, 0, 1).pow(gamma)
        noise = torch.randn_like(darkened) * 0.03
        darkened = torch.clamp(darkened + noise, 0, 1)
        kernel = torch.tensor([0.1, 0.2, 0.4, 0.2, 0.1], device=imgs.device, dtype=darkened.dtype)
        weight = kernel.view(1, 1, 1, 5).repeat(darkened.shape[1], 1, 1, 1)
        blurred = torch.nn.functional.conv2d(darkened, weight, padding=(0, 2), groups=darkened.shape[1])
        imgs[pick] = blurred
        return batch

    DetectionTrainer.preprocess_batch = preprocess_batch


def main() -> None:
    os.environ.setdefault("YOLO_VERBOSE", "false")
    started = time.time()
    dark_yaml = ensure_dark_split()
    from ultralytics import YOLO

    saved = json.loads(METRICS.read_text()) if METRICS.is_file() else {}
    before = saved.get("darkened_test_before")
    if not before:
        before_model = YOLO(str(WEIGHTS))
        before = evaluate(before_model, dark_yaml)
        before["weights"] = str(WEIGHTS)
    else:
        print("REUSE_BEFORE", json.dumps({"map50": before["map50"], "map50_95": before["map50_95"]}))
    report = {
        "darkened_test_before": before,
        "night_datasets_included": [],
        "night_datasets_skipped": [
            "ExDark: dataset terms require written permission for commercial use, and the photos are third-party",
            "BDD100K data: educational, research, and not-for-profit unless separately licensed",
            "nuScenes: CC BY-NC-SA, non-commercial",
            "Roboflow Universe night-vision project lists CC BY 4.0, but the photos are a reupload with no confirmed source license, so they were not added",
            "FLIR thermal sets are a different sensor from the phone camera and were not added",
        ],
        "augmentation": "training batches: darken, gamma, gaussian noise, horizontal motion blur",
        "note": "Measured on this CPU. Not a phone and not a Helio G99.",
    }
    METRICS.parent.mkdir(parents=True, exist_ok=True)
    METRICS.write_text(json.dumps(report, indent=2))
    print("BEFORE", json.dumps({"map50": before["map50"], "map50_95": before["map50_95"]}))

    install_batch_darkening()
    model = YOLO(str(WEIGHTS))
    print("TRAIN_START", flush=True)
    model.train(
        data=str(DATA),
        epochs=1,
        imgsz=320,
        batch=8,
        device="cpu",
        workers=2,
        project=str(RUN.parent),
        name=RUN.name,
        exist_ok=True,
        plots=False,
        val=True,
        hsv_v=0.6,
        mosaic=0.5,
        fliplr=0.5,
        lr0=0.001,
        patience=0,
    )
    after_weights = Path(model.trainer.best) if getattr(model, "trainer", None) else RUN / "weights/best.pt"
    if not after_weights.is_file():
        after_weights = RUN / "weights/best.pt"
    after_model = YOLO(str(after_weights))
    after = evaluate(after_model, dark_yaml)
    after["weights"] = str(after_weights)
    report["darkened_test_after"] = after
    report["train_seconds"] = time.time() - started
    report["shipped_tflite"] = False
    improved = after["map50_95"] > before["map50_95"] + 0.005
    report["improved_or_tied"] = improved
    report["decision"] = (
        "Shipped only when darkened-test mAP50-95 rises by more than 0.005. "
        "A drop on the normal validation split means the packaged detector stays as it was."
    )
    METRICS.write_text(json.dumps(report, indent=2))
    print("AFTER", json.dumps({"map50": after["map50"], "map50_95": after["map50_95"], "improved": improved}))
    if not improved:
        print("KEEP_ORIGINAL_TFLITE")
        return

    try:
        export = after_model.export(format="tflite", imgsz=320, int8=True, data=str(DATA))
    except Exception as error:
        report["export_error"] = str(error)
        METRICS.write_text(json.dumps(report, indent=2))
        print("EXPORT_FAILED", error)
        return
    exported = Path(str(export))
    candidates = [exported, exported.with_suffix(".tflite"), after_weights.with_name("best_int8.tflite")]
    chosen = next((path for path in candidates if path.is_file()), None)
    if chosen is None:
        matches = list(after_weights.parent.glob("*int8*.tflite")) + list(after_weights.parent.glob("*.tflite"))
        chosen = matches[0] if matches else None
    if chosen is None or not chosen.is_file():
        print("EXPORT_MISSING")
        return
    shutil.copyfile(chosen, ASSET)
    report["shipped_tflite"] = True
    report["tflite_bytes"] = ASSET.stat().st_size
    report["tflite_source"] = str(chosen)
    METRICS.write_text(json.dumps(report, indent=2))
    print("SHIPPED", ASSET.stat().st_size)


if __name__ == "__main__":
    main()
