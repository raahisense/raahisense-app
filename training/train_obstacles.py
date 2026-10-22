#!/usr/bin/env python3
"""Fine-tune YOLO11n on CPU and export an int8 TFLite file.

Reports are written under training/metrics. The multi-GB image set is not
copied there. Numbers are from this machine, not a Helio G99 phone.
"""
from __future__ import annotations

import json
import os
import shutil
import time
from pathlib import Path

from ultralytics import YOLO

DATA = "/workspace/datasets/obstacles_v1/data.yaml"
WEIGHTS = "/workspace/training/yolo11n.pt"
RUN = Path("/workspace/training/runs/obstacles19")
METRICS = Path("/workspace/training/metrics")
ASSET = Path("/workspace/app/src/main/assets/obstacles_v1.tflite")


def per_class(metrics) -> list[dict]:
    names = metrics.names if hasattr(metrics, "names") else {}
    maps = getattr(metrics.box, "maps", None)
    rows = []
    if maps is None:
        return rows
    for index, value in enumerate(maps):
        rows.append({"class": names.get(index, str(index)), "map50_95": float(value)})
    return rows


def evaluate(model: YOLO, split: str) -> dict:
    metrics = model.val(data=DATA, split=split, imgsz=320, batch=8, device="cpu", workers=2, plots=False)
    return {
        "split": split,
        "map50": float(metrics.box.map50),
        "map50_95": float(metrics.box.map),
        "per_class_map50_95": per_class(metrics),
    }


def measure_tflite(path: Path) -> dict:
    import numpy as np
    import tensorflow as tf

    cpu = "unknown"
    for line in Path("/proc/cpuinfo").read_text().splitlines():
        if line.startswith("model name"):
            cpu = line.split(":", 1)[1].strip()
            break
    interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    details = interpreter.get_input_details()[0]
    shape = details["shape"]
    if details["dtype"] == np.float32:
        sample = np.random.rand(*shape).astype(np.float32)
    else:
        sample = np.random.randint(0, 256, size=shape, dtype=details["dtype"])
    interpreter.set_tensor(details["index"], sample)
    for _ in range(3):
        interpreter.invoke()
    times = []
    for _ in range(20):
        started = time.perf_counter()
        interpreter.invoke()
        times.append(time.perf_counter() - started)
    out = interpreter.get_output_details()[0]
    return {
        "hardware": cpu,
        "threads": 2,
        "input_shape": [int(v) for v in shape],
        "input_dtype": str(details["dtype"]),
        "output_shape": [int(v) for v in out["shape"]],
        "output_dtype": str(out["dtype"]),
        "mean_ms": 1000 * sum(times) / len(times),
        "min_ms": 1000 * min(times),
        "note": "Python TFLite on this CPU. Not a Helio G99 and not the Android app.",
    }


def main() -> None:
    METRICS.mkdir(parents=True, exist_ok=True)
    if not os.path.exists(WEIGHTS):
        model = YOLO("yolo11n.pt")
        shutil.copy("yolo11n.pt", WEIGHTS)
    else:
        model = YOLO(WEIGHTS)
    started = time.perf_counter()
    model.train(
        data=DATA,
        epochs=int(os.environ.get("OBSTACLE_EPOCHS", "5")),
        imgsz=320,
        batch=int(os.environ.get("OBSTACLE_BATCH", "8")),
        device="cpu",
        workers=2,
        cache=False,
        patience=3,
        project=str(RUN.parent),
        name=RUN.name,
        exist_ok=True,
        plots=False,
        mosaic=0.5,
        pretrained=True,
    )
    train_seconds = time.perf_counter() - started
    best = RUN / "weights" / "best.pt"
    trained = YOLO(str(best))
    report = {
        "hardware": "CPU only. torch.cuda is not available. Not a Helio G99.",
        "base": "yolo11n.pt COCO pretrained",
        "imgsz": 320,
        "epochs_requested": int(os.environ.get("OBSTACLE_EPOCHS", "5")),
        "batch": int(os.environ.get("OBSTACLE_BATCH", "8")),
        "train_seconds": train_seconds,
    }
    report["val"] = evaluate(trained, "val")
    report["test"] = evaluate(trained, "test")
    print("export tflite int8", flush=True)
    export_started = time.perf_counter()
    exported = trained.export(format="tflite", imgsz=320, int8=True, data=DATA)
    report["export_seconds"] = time.perf_counter() - export_started
    exported_path = Path(exported)
    # Ultralytics may write best_saved_model/ or a .tflite next to the weights.
    tflite_files = list(exported_path.parent.rglob("*.tflite")) if exported_path.is_dir() else [exported_path]
    if exported_path.suffix == ".tflite":
        tflite_files = [exported_path]
    else:
        tflite_files = sorted(Path(exported).rglob("*.tflite") if Path(exported).exists() else exported_path.parent.rglob("*.tflite"))
    if not tflite_files:
        tflite_files = list(best.parent.rglob("*.tflite"))
    chosen = max(tflite_files, key=lambda path: path.stat().st_size)
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(chosen, ASSET)
    report["tflite"] = str(ASSET)
    report["tflite_bytes"] = ASSET.stat().st_size
    report["tflite_source"] = str(chosen)
    report["tflite_latency"] = measure_tflite(ASSET)
    (METRICS / "train_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k: report[k] for k in report if "per_class" not in k}, indent=2), flush=True)


if __name__ == "__main__":
    main()
