#!/usr/bin/env python3
"""Fine-tune YOLO11n and export an int8 TFLite file.

The shipped detector is YOLO11n, int8 TFLite. That file cannot be a training
checkpoint, so the run starts from yolo11n.pt, the same architecture, and
keeps input size 320. One new class, wall, extends the head. Epochs and hours
are capped. Ultralytics writes last.pt every epoch and best.pt when val
improves, and save_period copies an extra checkpoint each epoch.

The phone asset is replaced only by evaluate_compare.py, and only when the
exported file beats the current asset on the held-out set, including low light.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lowlight import install_batch_darkening

DATA = os.environ.get("OBSTACLE_DATA", "/workspace/datasets/obstacles_v2/data.yaml")
RUN = Path(os.environ.get("OBSTACLE_RUN", "/workspace/training/runs/obstacles_v2"))
METRICS = Path("/workspace/training/metrics")
WEIGHTS_OUT = Path(os.environ.get("OBSTACLE_WEIGHTS_OUT", "/workspace/training/weights/obstacles_v2.pt"))
TFLITE_NAME = os.environ.get("OBSTACLE_TFLITE", "obstacles_v2_int8.tflite")
REPORT_NAME = os.environ.get("OBSTACLE_REPORT", "train_report_v2.json")


def device_name() -> str:
    import torch

    if torch.cuda.is_available():
        return "0"
    return "cpu"


def per_class(metrics) -> list[dict]:
    names = metrics.names if hasattr(metrics, "names") else {}
    maps = getattr(metrics.box, "maps", None)
    rows = []
    if maps is None:
        return rows
    for index, value in enumerate(maps):
        rows.append({"class": names.get(index, str(index)), "map50_95": float(value)})
    return rows


def main() -> None:
    if not os.path.exists(DATA):
        raise SystemExit(f"missing {DATA}. Run training/fetch_v2.py and training/build_dataset.py first.")
    from ultralytics import YOLO
    import torch

    METRICS.mkdir(parents=True, exist_ok=True)
    install_batch_darkening(0.40)
    device = device_name()
    # yolo11n.pt is the trainable parent of obstacles_v1. The int8 asset is not.
    base = os.environ.get("OBSTACLE_WEIGHTS", "yolo11n.pt")
    model = YOLO(base)
    epochs = int(os.environ.get("OBSTACLE_EPOCHS", "8"))
    hours = float(os.environ.get("OBSTACLE_HOURS", "2.5"))
    batch = int(os.environ.get("OBSTACLE_BATCH", "8"))
    workers = int(os.environ.get("OBSTACLE_WORKERS", "2"))
    started = time.perf_counter()
    train_args = dict(
        data=DATA,
        epochs=epochs,
        imgsz=320,
        batch=batch,
        device=device,
        workers=workers,
        cache=False,
        patience=int(os.environ.get("OBSTACLE_PATIENCE", "8")),
        project=str(RUN.parent),
        name=RUN.name,
        exist_ok=True,
        plots=False,
        mosaic=0.8,
        mixup=0.05,
        fliplr=0.5,
        scale=0.4,
        degrees=5.0,
        close_mosaic=3,
        pretrained=True,
        lr0=0.003,
        lrf=0.01,
        amp=device != "cpu",
        seed=0,
        deterministic=False,
        val=True,
        save=True,
        save_period=1,
    )
    # hours <= 0 leaves the epoch cap in charge. The real-photo run passes 2.5.
    if hours > 0:
        train_args["time"] = hours
    model.train(**train_args)
    train_seconds = time.perf_counter() - started
    best = RUN / "weights" / "best.pt"
    if not best.exists():
        raise SystemExit(f"training did not write {best}")
    WEIGHTS_OUT.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(best, WEIGHTS_OUT)
    trained = YOLO(str(best))
    report = {
        "hardware": "cuda" if torch.cuda.is_available() else "CPU only. No GPU was visible.",
        "base": base,
        "model": "yolo11n",
        "imgsz": 320,
        "epochs_cap": epochs,
        "hours_cap": hours,
        "batch": batch,
        "train_seconds": train_seconds,
        "lowlight_batch_probability": 0.40,
        "weights": str(WEIGHTS_OUT),
    }
    print("val", flush=True)
    metrics = trained.val(data=DATA, split="val", imgsz=320, batch=batch, device=device, workers=workers, plots=False)
    report["val"] = {
        "map50": float(metrics.box.map50),
        "map50_95": float(metrics.box.map),
        "per_class_map50_95": per_class(metrics),
    }
    print("export tflite int8", flush=True)
    export_started = time.perf_counter()
    # quantize=8 is static int8 weights with float32 input and output, which is
    # what the phone decoder already accepts. format=tflite is the LiteRT path.
    exported = trained.export(format="tflite", imgsz=320, quantize=8, data=DATA)
    report["export_seconds"] = time.perf_counter() - export_started
    exported_path = Path(exported)
    if exported_path.suffix == ".tflite":
        tflite_files = [exported_path]
    else:
        tflite_files = sorted(Path(exported).rglob("*.tflite")) if Path(exported).exists() else []
    if not tflite_files:
        tflite_files = list(best.parent.rglob("*.tflite"))
    int8 = [path for path in tflite_files if "int8" in path.name or "integer" in path.name]
    pool = int8 or tflite_files
    chosen = max(pool, key=lambda path: path.stat().st_size)
    staged = METRICS / TFLITE_NAME
    shutil.copy(chosen, staged)
    report["tflite"] = str(staged)
    report["tflite_bytes"] = staged.stat().st_size
    report["tflite_source"] = str(chosen)
    report["data"] = DATA
    (METRICS / REPORT_NAME).write_text(json.dumps(report, indent=2))
    print(json.dumps({key: report[key] for key in report if "per_class" not in str(key)}, indent=2), flush=True)


if __name__ == "__main__":
    main()
