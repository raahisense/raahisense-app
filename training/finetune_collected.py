#!/usr/bin/env python3
"""Fine-tune the obstacle detector on photos from the app's Collect mode.

Collect mode is not in the app yet. When it is, it should write a JPEG and a
JSON sidecar with the same file stem:

    walk/2026-10-25-001.jpg
    walk/2026-10-25-001.json

The sidecar is:

    {
      "boxes": [
        {"label": "curb", "xmin": 0.10, "ymin": 0.62, "xmax": 0.88, "ymax": 0.80}
      ]
    }

xmin, ymin, xmax, ymax are fractions of the image width and height. The
origin is the top left. These shapes are also accepted:

    {"label": "wall", "x": 0.5, "y": 0.6, "w": 0.4, "h": 0.3}
    {"label": "door", "bbox": [0.1, 0.1, 0.4, 0.8]}

x, y, w, h are the box center and size, also as fractions. bbox is
xmin, ymin, xmax, ymax unless "format" is "xywh".

label must be one of the names in training/class_map.py. An unknown label is
skipped and printed. A sidecar with "background": true and no boxes is a
negative example. Pictures with no sidecar are skipped.

    python3 training/finetune_collected.py --photos /path/to/folder --dry-run
    python3 training/finetune_collected.py --photos /path/to/folder --epochs 25
    python3 training/finetune_collected.py --photos /path/to/folder --export

Weights are resolved in this order: --weights, training/weights/obstacles_v2.pt,
training/runs/obstacles_v2/weights/best.pt. Those .pt files are produced by
training/train_obstacles.py. Passing nothing else starts from YOLO11s COCO
weights, which do not know wall, curb, or rickshaw.

If datasets/obstacles_v2 is still on disk, --replay-images (default 400) mixes
public training pictures back in so the fine-tune does not forget them.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import shutil
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from class_map import CLASSES, CLASS_INDEX

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def clip(xc: float, yc: float, w: float, h: float):
    x0, y0 = max(0.0, xc - w / 2), max(0.0, yc - h / 2)
    x1, y1 = min(1.0, xc + w / 2), min(1.0, yc + h / 2)
    if x1 - x0 <= 0.004 or y1 - y0 <= 0.004:
        return None
    return ((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)


def box_from_item(item: dict) -> tuple[str, tuple] | None:
    label = str(item.get("label") or item.get("class") or item.get("name") or "").strip()
    if label not in CLASS_INDEX:
        return None
    if "xmin" in item and "xmax" in item and "ymin" in item and "ymax" in item:
        x0, x1 = float(item["xmin"]), float(item["xmax"])
        y0, y1 = float(item["ymin"]), float(item["ymax"])
        boxed = clip((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
    elif "bbox" in item:
        values = [float(value) for value in item["bbox"]]
        if len(values) != 4:
            return None
        if str(item.get("format") or "xyxy") == "xywh":
            boxed = clip(*values)
        else:
            x0, y0, x1, y1 = values
            boxed = clip((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
    elif all(key in item for key in ("x", "y", "w", "h")):
        boxed = clip(float(item["x"]), float(item["y"]), float(item["w"]), float(item["h"]))
    else:
        return None
    if boxed is None:
        return None
    return label, boxed


def read_sidecar(path: Path) -> tuple[list[tuple[str, tuple]], bool, list[str]]:
    payload = json.loads(path.read_text())
    unknown = []
    boxes = []
    items = payload.get("boxes") or payload.get("objects") or payload.get("labels") or []
    if isinstance(payload, list):
        items = payload
    for item in items:
        if not isinstance(item, dict):
            continue
        parsed = box_from_item(item)
        if parsed is None:
            label = item.get("label") or item.get("class") or item.get("name")
            if label:
                unknown.append(str(label))
            continue
        boxes.append(parsed)
    background = bool(payload.get("background")) if isinstance(payload, dict) else False
    return boxes, background, unknown


def build_yolo(photos: Path, out: Path, replay: int, base_data: Path | None) -> tuple[int, int]:
    if out.exists():
        shutil.rmtree(out)
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True)
        (out / "labels" / split).mkdir(parents=True)
    written = 0
    skipped = 0
    images = [path for path in photos.rglob("*") if path.suffix.lower() in IMAGE_EXTS]
    images.sort()
    for image_path in images:
        sidecar = image_path.with_suffix(".json")
        if not sidecar.exists():
            skipped += 1
            continue
        boxes, background, unknown = read_sidecar(sidecar)
        if unknown:
            print(f"unknown labels in {sidecar.name}: {sorted(set(unknown))}", flush=True)
        if not boxes and not background:
            skipped += 1
            continue
        # A handful of photos all stay in train. Val is the replay slice or a copy.
        split = "train"
        dest = out / "images" / split / f"collected_{written:05d}{image_path.suffix.lower()}"
        if dest.suffix != ".jpg":
            from PIL import Image

            with Image.open(image_path) as image:
                image.convert("RGB").save(out / "images" / split / f"collected_{written:05d}.jpg", quality=90)
            dest = out / "images" / split / f"collected_{written:05d}.jpg"
        else:
            shutil.copy(image_path, dest)
        label_path = out / "labels" / split / f"{dest.stem}.txt"
        with label_path.open("w") as handle:
            for label, box in boxes:
                handle.write(f"{CLASS_INDEX[label]} {' '.join(f'{value:.6f}' for value in box)}\n")
        written += 1
    if written == 0:
        raise SystemExit(f"no labeled photos in {photos}")
    replayed = 0
    if base_data and base_data.exists() and replay > 0:
        payload = __import__("yaml").safe_load(base_data.read_text())
        root = Path(payload["path"])
        pool = sorted((root / "images/train").glob("*.jpg"))
        random.Random(0).shuffle(pool)
        for image_path in pool[:replay]:
            label = root / "labels/train" / f"{image_path.stem}.txt"
            if not label.exists():
                continue
            dest = out / "images/train" / f"replay_{replayed:05d}.jpg"
            shutil.copy(image_path, dest)
            shutil.copy(label, out / "labels/train" / dest.with_suffix(".txt").name)
            replayed += 1
    # Ultralytics wants a val split. Use replay or collected pictures, never a new download.
    val_images = list((out / "images/train").glob("replay_*.jpg")) or list((out / "images/train").glob("collected_*.jpg"))
    for index, image_path in enumerate(val_images[: max(1, min(80, len(val_images)))]):
        dest = out / "images/val" / image_path.name
        if not dest.exists():
            os.link(image_path, dest) if hasattr(os, "link") else shutil.copy(image_path, dest)
        label = out / "labels/train" / f"{image_path.stem}.txt"
        val_label = out / "labels/val" / f"{image_path.stem}.txt"
        if label.exists() and not val_label.exists():
            os.link(label, val_label) if hasattr(os, "link") else shutil.copy(label, val_label)
    data_yaml = {
        "path": str(out),
        "train": "images/train",
        "val": "images/val",
        "names": {index: name for index, name in enumerate(CLASSES)},
    }
    (out / "data.yaml").write_text(__import__("yaml").safe_dump(data_yaml, sort_keys=False))
    print(f"collected {written} skipped {skipped} replay {replayed}", flush=True)
    return written, replayed


def resolve_weights(explicit: str | None) -> str:
    if explicit:
        return explicit
    candidates = [
        Path("/workspace/training/weights/obstacles_v2.pt"),
        Path("/workspace/training/runs/obstacles_v2/weights/best.pt"),
    ]
    for path in candidates:
        if path.exists():
            return str(path)
    print("no obstacles_v2 checkpoint found. Starting from yolo11n.pt.", flush=True)
    return "yolo11n.pt"


def self_test() -> None:
    import tempfile

    from PIL import Image

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        image = root / "one.jpg"
        Image.new("RGB", (64, 48), (20, 20, 20)).save(image)
        (root / "one.json").write_text(
            json.dumps({"boxes": [{"label": "wall", "xmin": 0.1, "ymin": 0.1, "xmax": 0.9, "ymax": 0.9}]})
        )
        boxes, background, unknown = read_sidecar(root / "one.json")
        assert len(boxes) == 1 and boxes[0][0] == "wall" and not unknown and not background
        (root / "two.json").write_text(json.dumps({"background": True, "boxes": []}))
        assert read_sidecar(root / "two.json")[1] is True
        out = root / "yolo"
        # one.json's image exists; two.json has no jpeg and is ignored.
        written, _replayed = build_yolo(root, out, replay=0, base_data=None)
        assert written == 1
        label = (out / "labels/train").read_text() if False else next((out / "labels/train").glob("*.txt")).read_text()
        assert label.startswith(f"{CLASS_INDEX['wall']} ")
    print("finetune_collected self-test ok")


def main() -> None:
    parser = argparse.ArgumentParser(description="Fine-tune on Collect-mode JPEG + JSON pairs.")
    parser.add_argument("--photos", type=Path, help="Folder of JPEG files and JSON sidecars.")
    parser.add_argument("--weights", default=None)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--replay-images", type=int, default=400)
    parser.add_argument("--out", type=Path, default=Path("/workspace/datasets/collected_yolo"))
    parser.add_argument("--base-data", type=Path, default=Path("/workspace/datasets/obstacles_v2/data.yaml"))
    parser.add_argument("--export", action="store_true", help="Write an int8 TFLite file next to the run.")
    parser.add_argument("--install", action="store_true", help="Copy that TFLite file onto the Android asset.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.photos is None:
        raise SystemExit("pass --photos or --self-test")
    written, replayed = build_yolo(args.photos, args.out, args.replay_images, args.base_data if args.base_data.exists() else None)
    if args.dry_run:
        print(f"dry run only. yaml {args.out / 'data.yaml'} images {written} replay {replayed}")
        return
    from ultralytics import YOLO
    import torch

    weights = resolve_weights(args.weights)
    model = YOLO(weights)
    device = "0" if torch.cuda.is_available() else "cpu"
    run = Path("/workspace/training/runs/obstacles_collected")
    model.train(
        data=str(args.out / "data.yaml"),
        epochs=args.epochs,
        imgsz=320,
        batch=args.batch,
        device=device,
        workers=2,
        project=str(run.parent),
        name=run.name,
        exist_ok=True,
        plots=False,
        pretrained=True,
        lr0=0.002,
        patience=8,
        mosaic=0.3,
    )
    best = run / "weights" / "best.pt"
    print(f"checkpoint {best}", flush=True)
    if args.export and best.exists():
        exported = YOLO(str(best)).export(format="tflite", imgsz=320, int8=True, data=str(args.out / "data.yaml"))
        print(f"exported {exported}", flush=True)
        if args.install:
            tflite_files = list(Path(exported).rglob("*.tflite")) if Path(exported).exists() else []
            if Path(exported).suffix == ".tflite":
                tflite_files = [Path(exported)]
            if tflite_files:
                chosen = max(tflite_files, key=lambda path: path.stat().st_size)
                asset = Path("/workspace/app/src/main/assets/obstacles_v1.tflite")
                shutil.copy(chosen, asset)
                print(f"installed {asset}", flush=True)


if __name__ == "__main__":
    main()
