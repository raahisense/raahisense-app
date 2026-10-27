#!/usr/bin/env python3
"""Map the private synth160 batch onto the app class list.

The zip is AI-generated and hand-labeled. It is training-only. Images and
labels stay under /workspace/datasets, which is gitignored, and are never
copied into the app or the public repo.

classes.txt indices 0-28 match training/convert_obstacles.py. Index 29 is
wall. Index 30 is unknown_obstacle. That name is not a detector class: the
app still speaks an unknown object from depth. Those boxes are dropped,
including when the batch used the id for debris, wires, shoes, bags, or
appliances that were not given their own name. An image with no remaining
box is left out rather than trained as an empty background.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import zipfile
from collections import Counter
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from class_map import CLASSES

RAW = Path("/workspace/datasets/raw/synth160")
REAL = Path("/workspace/datasets/obstacles_v2")
OUT = Path("/workspace/datasets/obstacles_v2_synth")
DEFAULT_ZIP = Path("/cursor/stores/self/synth160.zip")


def load_zip(path: Path) -> tuple[list[str], list[tuple[str, bytes, str]]]:
    if not path.exists():
        raise SystemExit(f"missing {path}. Set SYNTH160_ZIP to the private zip. Do not commit it.")
    with zipfile.ZipFile(path) as archive:
        names = archive.read("classes.txt").decode().splitlines()
        names = [name.strip() for name in names if name.strip()]
        rows = []
        for info in archive.infolist():
            if info.is_dir() or not info.filename.startswith("images/"):
                continue
            stem = Path(info.filename).stem
            label_name = f"labels/{stem}.txt"
            if label_name not in archive.namelist():
                continue
            rows.append((stem, archive.read(info.filename), archive.read(label_name).decode()))
    return names, rows


def remap(label_text: str) -> str:
    kept = []
    for line in label_text.splitlines():
        parts = line.split()
        if len(parts) < 5:
            continue
        class_id = int(float(parts[0]))
        if class_id < 0 or class_id >= 30:
            continue
        parts[0] = str(class_id)
        kept.append(" ".join(parts[:5]))
    return ("\n".join(kept) + "\n") if kept else ""


def materialize(zip_path: Path) -> dict:
    names, rows = load_zip(zip_path)
    expected = CLASSES[:30]
    if names[:30] != expected:
        raise SystemExit(f"classes.txt 0-29 do not match the app map: {names[:30]}")
    if len(names) < 31 or names[30] != "unknown_obstacle":
        raise SystemExit(f"expected unknown_obstacle at index 30, got {names[30:]}")
    if RAW.exists():
        shutil.rmtree(RAW)
    (RAW / "images").mkdir(parents=True)
    (RAW / "labels").mkdir(parents=True)
    written = 0
    dropped_images = 0
    dropped_boxes = 0
    kept_boxes = Counter()
    street = household = 0
    for stem, image, label_text in rows:
        original_boxes = [line for line in label_text.splitlines() if line.strip()]
        dropped_boxes += sum(1 for line in original_boxes if int(float(line.split()[0])) == 30)
        mapped = remap(label_text)
        if not mapped:
            dropped_images += 1
            continue
        suffix = ".jpg"
        (RAW / "images" / f"{stem}{suffix}").write_bytes(image)
        (RAW / "labels" / f"{stem}.txt").write_text(mapped)
        written += 1
        if stem.startswith("h"):
            household += 1
        else:
            street += 1
        for line in mapped.splitlines():
            kept_boxes[CLASSES[int(line.split()[0])]] += 1
    summary = {
        "source": "private synth160 zip, not committed",
        "images_in_zip": len(rows),
        "images_kept": written,
        "street_kept": street,
        "household_kept": household,
        "images_dropped_unknown_only": dropped_images,
        "unknown_obstacle_boxes_dropped": dropped_boxes,
        "kept_boxes": dict(kept_boxes),
        "map": {str(index): name for index, name in enumerate(expected)},
        "unknown_obstacle": "dropped. Not a detector class. Depth still speaks an unknown object.",
        "splits": "train only. Absent from val and test.",
    }
    (RAW / "summary.json").write_text(json.dumps(summary, indent=2))
    metrics = Path("/workspace/training/metrics")
    metrics.mkdir(parents=True, exist_ok=True)
    (metrics / "synth160_map.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2), flush=True)
    return summary


def link_tree(src: Path, dest: Path) -> None:
    if dest.is_symlink() or dest.exists():
        if dest.is_symlink() or dest.is_file():
            dest.unlink()
        else:
            shutil.rmtree(dest)
    dest.symlink_to(src, target_is_directory=True)


def build_train_copy() -> None:
    """Real val and test, plus the synthetic images added only to train."""
    if not (REAL / "images/train").is_dir():
        raise SystemExit(f"missing {REAL}. Build the real set first.")
    if not (RAW / "images").is_dir():
        raise SystemExit(f"missing {RAW}. Run this script before building the train copy.")
    if OUT.exists():
        shutil.rmtree(OUT)
    for split in ("train", "val", "test"):
        (OUT / "images" / split).mkdir(parents=True)
        (OUT / "labels" / split).mkdir(parents=True)
    for split in ("val", "test"):
        for kind in ("images", "labels"):
            for path in (REAL / kind / split).iterdir():
                os.link(path, OUT / kind / split / path.name)
    for path in (REAL / "images/train").iterdir():
        os.link(path, OUT / "images/train" / path.name)
    for path in (REAL / "labels/train").iterdir():
        os.link(path, OUT / "labels/train" / path.name)
    added = 0
    for image in sorted((RAW / "images").iterdir()):
        label = RAW / "labels" / f"{image.stem}.txt"
        dest_image = OUT / "images/train" / image.name
        dest_label = OUT / "labels/train" / f"{image.stem}.txt"
        if dest_image.exists() or dest_label.exists():
            raise SystemExit(f"name clash {image.name}")
        os.link(image, dest_image)
        os.link(label, dest_label)
        added += 1
    # Prove the held-out trees contain none of the synthetic file names.
    synth_names = {path.name for path in (RAW / "images").iterdir()}
    for split in ("val", "test"):
        leaked = synth_names & {path.name for path in (OUT / "images" / split).iterdir()}
        if leaked:
            raise SystemExit(f"synthetic images leaked into {split}: {sorted(leaked)[:5]}")
    payload = {
        "path": str(OUT),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": {index: name for index, name in enumerate(CLASSES)},
    }
    import yaml

    (OUT / "data.yaml").write_text(yaml.safe_dump(payload, sort_keys=False))
    print(f"train images {len(list((OUT / 'images/train').iterdir()))} added {added}", flush=True)
    print("val and test are the real-photo splits", flush=True)


def main() -> None:
    zip_path = Path(os.environ.get("SYNTH160_ZIP", str(DEFAULT_ZIP)))
    materialize(zip_path)
    build_train_copy()


if __name__ == "__main__":
    main()
