#!/usr/bin/env python3
"""Merge downloaded sources into one YOLO detection set.

Class indices match training/class_map.py. Official test splits stay in test.
Open Images and COCO-Stuff are hash-split. A second pass keeps rare classes
(wall, curb, pothole, door, stairs, pole) from being crowded out, and caps
the train split so a CPU run can finish.
"""
from __future__ import annotations

import collections
import csv
import glob
import hashlib
import json
import os
import shutil
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import yaml
from PIL import Image

from class_map import CLASSES, CLASS_INDEX, wall_box

RAW = "/workspace/datasets/raw"
OUT = "/workspace/datasets/obstacles_v2"
TRAIN_CAP = 12000
VAL_CAP = 1600
TEST_CAP = 2400
# Household obstacles and rare street classes are admitted first.
PRIORITY = [
    "shoe",
    "bag",
    "toy",
    "bin",
    "appliance",
    "cabinet",
    "curb",
    "wall",
    "pothole",
    "manhole_open_drain",
    "pole_bollard",
    "door",
    "stairs",
    "chair",
    "couch",
    "dining table",
    "bed",
    "toilet",
    "refrigerator",
    "tv",
    "potted plant",
    "auto_rickshaw",
    "cycle_rickshaw",
    "handcart",
    "barrier",
    "street_furniture",
    "bicycle",
    "motorcycle",
    "bus",
    "truck",
    "cow",
    "dog",
    "cat",
    "tree",
    "person",
    "car",
]
# Image caps per class.
CAPS = {
    "shoe": 500,
    "bag": 500,
    "toy": 400,
    "bin": 450,
    "appliance": 450,
    "cabinet": 400,
    "wall": 1200,
    "curb": 700,
    "pothole": 800,
    "door": 700,
    "stairs": 550,
    "pole_bollard": 550,
    "manhole_open_drain": 450,
    "barrier": 400,
    "auto_rickshaw": 500,
    "cycle_rickshaw": 500,
    "handcart": 300,
    "street_furniture": 450,
    "person": 900,
    "bicycle": 400,
    "motorcycle": 350,
    "car": 700,
    "bus": 280,
    "truck": 280,
    "cow": 280,
    "dog": 350,
    "cat": 280,
    "chair": 650,
    "couch": 450,
    "dining table": 500,
    "bed": 400,
    "toilet": 320,
    "potted plant": 320,
    "refrigerator": 320,
    "tv": 280,
    "tree": 280,
}

records: list[tuple] = []
seen: set[str] = set()
notes: list[str] = []
mapping: list[tuple[str, str, str]] = []


def addmap(src: str, original: str, target: str | None) -> None:
    mapping.append((src, original, target or "(dropped)"))


def hsplit(key: str) -> str:
    bucket = int(hashlib.md5(key.encode()).hexdigest(), 16) % 100
    if bucket < 80:
        return "train"
    if bucket < 90:
        return "val"
    return "test"


def md5f(path: str) -> str:
    digest = hashlib.md5()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clip(xc: float, yc: float, w: float, h: float):
    x0, y0 = max(0.0, xc - w / 2), max(0.0, yc - h / 2)
    x1, y1 = min(1.0, xc + w / 2), min(1.0, yc + h / 2)
    if x1 - x0 <= 0.004 or y1 - y0 <= 0.004:
        return None
    return ((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)


def add(src: str, split: str, img: str, boxes: list, key: str | None = None) -> None:
    if not boxes or not os.path.exists(img):
        return
    try:
        digest = md5f(img)
    except OSError:
        return
    if digest in seen:
        return
    seen.add(digest)
    records.append((src, split, img, boxes, key or digest))


def accept_box(target: str, xc: float, yc: float, w: float, h: float):
    if target == "wall" and not wall_box(w, h, yc):
        return None
    return clip(xc, yc, w, h)


def yolo_dir(src: str, root: str, splits: list[tuple[str, str]], names: list[str], cmap: dict[str, str | None]) -> None:
    if not os.path.isdir(root):
        notes.append(f"missing {src}")
        return
    for original in names:
        addmap(src, original, cmap.get(original))
    selected = None
    selected_path = os.path.join(root, "selected_images.txt")
    if os.path.exists(selected_path):
        selected = set(line.strip() for line in open(selected_path) if line.strip())
    for folder, split in splits:
        labels = glob.glob(f"{root}/{folder}/labels/**/*.txt", recursive=True)
        for label_path in labels:
            stem = os.path.splitext(os.path.basename(label_path))[0]
            if selected is not None:
                rels = [f"{folder}/images/{stem}{ext}" for ext in (".jpg", ".jpeg", ".png")]
                if not any(rel in selected for rel in rels):
                    continue
            image = None
            for ext in (".jpg", ".jpeg", ".png", ".JPG", ".webp"):
                path = os.path.join(root, folder, "images", stem + ext)
                if os.path.exists(path):
                    image = path
                    break
            if image is None:
                continue
            boxes = []
            for line in open(label_path):
                values = line.split()
                if len(values) < 5:
                    continue
                index = int(float(values[0]))
                if index < 0 or index >= len(names):
                    continue
                target = cmap.get(names[index])
                if target is None:
                    continue
                if len(values) == 5:
                    xc, yc, w, h = map(float, values[1:5])
                else:
                    xs = list(map(float, values[1::2]))
                    ys = list(map(float, values[2::2]))
                    if len(xs) != len(ys) or len(xs) < 3:
                        continue
                    xc = (min(xs) + max(xs)) / 2
                    yc = (min(ys) + max(ys)) / 2
                    w = max(xs) - min(xs)
                    h = max(ys) - min(ys)
                boxed = accept_box(target, xc, yc, w, h)
                if boxed:
                    boxes.append((CLASS_INDEX[target],) + boxed)
            add(src, split, image, boxes, key=f"{src}:{stem}")


def coco_file(src: str, json_path: str, split: str, cmap: dict[str, str | None]) -> None:
    if not os.path.exists(json_path):
        notes.append(f"missing coco {src} {json_path}")
        return
    data = json.load(open(json_path))
    base = os.path.dirname(json_path)
    cats = {cat["id"]: cat["name"] for cat in data["categories"]}
    for name in sorted(set(cats.values())):
        addmap(src, name, cmap.get(name) or cmap.get(name.lower()))
    images = {image["id"]: image for image in data["images"]}
    by = collections.defaultdict(list)
    for ann in data["annotations"]:
        if ann.get("iscrowd"):
            continue
        raw_name = cats.get(ann["category_id"], "")
        target = cmap.get(raw_name)
        if target is None:
            target = cmap.get(raw_name.lower())
        if target is None:
            continue
        image = images.get(ann["image_id"])
        if image is None:
            continue
        x, y, w, h = ann["bbox"]
        width, height = image.get("width") or 0, image.get("height") or 0
        if not width or not height:
            continue
        boxed = accept_box(target, (x + w / 2) / width, (y + h / 2) / height, w / width, h / height)
        if boxed:
            by[ann["image_id"]].append((CLASS_INDEX[target],) + boxed)
    for image_id, boxes in by.items():
        file_name = images[image_id]["file_name"]
        path = file_name if os.path.isabs(file_name) else os.path.join(base, file_name)
        if not os.path.exists(path):
            alt = os.path.join(base, os.path.basename(file_name))
            path = alt if os.path.exists(alt) else path
        add(src, split, path, boxes, key=f"{src}:{file_name}")


def find_coco(root: str) -> list[tuple[str, str]]:
    found = []
    for path in glob.glob(f"{root}/**/_annotations.coco.json", recursive=True):
        low = path.lower()
        if "/test/" in low or low.endswith("/test/_annotations.coco.json"):
            split = "test"
        elif "/valid/" in low or "/val/" in low:
            split = "val"
        else:
            split = "train"
        found.append((path, split))
    return found


def vehicle_target(name: str) -> str | None:
    key = name.strip().lower().replace("_", " ").replace("-", " ")
    table = {
        "tuk tuk": "auto_rickshaw",
        "tuktuk": "auto_rickshaw",
        "auto": "auto_rickshaw",
        "auto rickshaw": "auto_rickshaw",
        "autorickshaw": "auto_rickshaw",
        "cng": "auto_rickshaw",
        "rickshaw": "cycle_rickshaw",
        "cycle rickshaw": "cycle_rickshaw",
        "car": "car",
        "bus": "bus",
        "truck": "truck",
        "lorry": "truck",
        "motorcycle": "motorcycle",
        "bike": "bicycle",
        "bicycle": "bicycle",
        "person": "person",
    }
    if key in table:
        return table[key]
    if "tuk" in key or "auto" in key and "rick" in key:
        return "auto_rickshaw"
    if "rickshaw" in key and "auto" not in key and "tuk" not in key:
        return "cycle_rickshaw"
    if "auto" in key or "tuk" in key:
        return "auto_rickshaw"
    return None


def load_sources() -> None:
    rod_names_path = f"{RAW}/rod/data.yaml"
    if os.path.exists(rod_names_path):
        rod_names = yaml.safe_load(open(rod_names_path))["names"]
    else:
        rod_names = []
    rod_map = {
        "Bike": "bicycle",
        "Building": "wall",
        "Car": "car",
        "Person": "person",
        "Stairs": "stairs",
        "Traffic sign": "street_furniture",
        "Electrical Pole": "pole_bollard",
        "Motorcycle": "motorcycle",
        "Dustbin": "bin",
        "Dog": "dog",
        "Manhole": "manhole_open_drain",
        "Tree": "tree",
        "Guard rail": "barrier",
        "Truck": "truck",
        "Bus": "bus",
        "Bench": "street_furniture",
        "Traffic Cone": "barrier",
        "Fire hydrant": "street_furniture",
        "Traffic Barrel": "barrier",
        "Teraffic Barrel": "barrier",
        "Plant Pot": "potted plant",
        "Electrical Box": "street_furniture",
        "Chair": "chair",
        "Bicycle Rack": "street_furniture",
    }
    yolo_dir(
        "ROD",
        f"{RAW}/rod",
        [("train", "train"), ("valid", "val"), ("test", "test")],
        rod_names,
        rod_map,
    )

    assist_yaml = f"{RAW}/assisteye_obstacles/unified_obstacles/data.yaml"
    if os.path.exists(assist_yaml):
        assist_names = yaml.safe_load(open(assist_yaml))["names"]
    else:
        assist_names = ["animal", "open_manhole", "waste_container", "pothole", "bump", "fence", "hole", "pole"]
    # `bump` is бордюр / protrusion in the publisher's class table, so it is curb.
    # `hole` is a crack (трещина), which this app does not speak.
    assist_map = {
        "open_manhole": "manhole_open_drain",
        "waste_container": "bin",
        "pothole": "pothole",
        "bump": "curb",
        "fence": "barrier",
        "pole": "pole_bollard",
    }
    yolo_dir(
        "AssistEye-unified-obstacles",
        f"{RAW}/assisteye_obstacles/unified_obstacles",
        [("train", "train"), ("valid", "val")],
        assist_names,
        assist_map,
    )
    # No official test split. Move a hash slice of what we just added into test
    # so curb and pole are scored. Done after all adds via restamp below.

    pothole_map = {"pothole": "pothole", "potholes": "pothole", "Pothole": "pothole", "0": "pothole"}
    for json_path, split in find_coco(f"{RAW}/pothole_keremberke"):
        coco_file("Roboflow-pothole-keremberke", json_path, split, pothole_map)

    label_map = {}
    map_path = f"{RAW}/openimages/label_map.csv"
    if os.path.exists(map_path):
        for row in csv.DictReader(open(map_path)):
            label_map[row["LabelName"]] = row["app_class"]
            addmap("OpenImagesV6", row["DisplayName"], row["app_class"])
    boxes_csv = f"{RAW}/openimages/selected_v2_boxes.csv"
    if os.path.exists(boxes_csv) and label_map:
        by = collections.defaultdict(list)
        for row in csv.DictReader(open(boxes_csv)):
            target = label_map.get(row["LabelName"])
            if target is None or target not in CLASS_INDEX:
                continue
            x0, x1, y0, y1 = (float(row[key]) for key in ("XMin", "XMax", "YMin", "YMax"))
            boxed = accept_box(target, (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
            if boxed:
                by[row["ImageID"]].append((CLASS_INDEX[target],) + boxed)
        for image_id, boxes in by.items():
            add(
                "OpenImagesV6",
                hsplit(image_id),
                f"{RAW}/openimages/images/{image_id}.jpg",
                boxes,
                key=f"oi:{image_id}",
            )
        notes.append(f"Open Images images with boxes {len(by)}")
    else:
        notes.append("missing Open Images v2 boxes")

    stuff_csv = f"{RAW}/coco/wall_boxes.csv"
    if os.path.exists(stuff_csv):
        by = collections.defaultdict(list)
        meta = {}
        for row in csv.DictReader(open(stuff_csv)):
            target = row["app_class"]
            if target not in CLASS_INDEX:
                continue
            x, y, w, h = (float(row[key]) for key in ("x", "y", "w", "h"))
            boxed = accept_box(target, x + w / 2, y + h / 2, w, h)
            if not boxed:
                continue
            key = f"{row['split']}/{row['file_name']}"
            by[key].append((CLASS_INDEX[target],) + boxed)
            meta[key] = (row["split"], row["file_name"])
        addmap("COCO-Stuff", "wall-*", "wall")
        addmap("COCO-Stuff", "fence", "barrier")
        for key, boxes in by.items():
            subset, file_name = meta[key]
            add(
                "COCO-Stuff",
                hsplit(key),
                f"{RAW}/coco/{subset}/{file_name}",
                boxes,
                key=f"stuff:{key}",
            )
        notes.append(f"COCO-Stuff images with wall or fence boxes {len(by)}")
    else:
        notes.append("missing COCO-Stuff boxes")

    tuk_root = f"{RAW}/tuktuk_shravya11"
    for json_path, split in find_coco(tuk_root):
        if "google_search" in json_path:
            continue
        data = json.load(open(json_path))
        cmap = {}
        for cat in data["categories"]:
            target = vehicle_target(cat["name"])
            cmap[cat["name"]] = target
            addmap("tuk-tuk-rickshaw", cat["name"], target)
        coco_file("tuk-tuk-rickshaw", json_path, split, cmap)

    addmap("Datacluster-auto-rickshaw", "auto", "auto_rickshaw")
    for xml_path in glob.glob(f"{tuk_root}/datacluster_auto/**/*.xml", recursive=True):
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        width = int(float(root.findtext("size/width") or 0))
        height = int(float(root.findtext("size/height") or 0))
        filename = root.findtext("filename") or ""
        image = ""
        for candidate in glob.glob(f"{tuk_root}/datacluster_auto/**/{glob.escape(filename)}", recursive=True):
            image = candidate
            break
        if not image:
            stem = os.path.splitext(os.path.basename(xml_path))[0]
            found = glob.glob(f"{tuk_root}/datacluster_auto/**/{glob.escape(stem)}.*", recursive=True)
            image = next((path for path in found if not path.endswith(".xml")), "")
        if not image or not os.path.exists(image):
            continue
        if (width == 0 or height == 0):
            with Image.open(image) as im:
                width, height = im.size
        boxes = []
        for obj in root.findall("object"):
            name = obj.findtext("name") or ""
            target = vehicle_target(name) or "auto_rickshaw"
            box = obj.find("bndbox")
            if box is None or target not in CLASS_INDEX:
                continue
            x0, y0, x1, y1 = [float(box.findtext(key) or 0) for key in ("xmin", "ymin", "xmax", "ymax")]
            boxed = accept_box(target, (x0 + x1) / 2 / width, (y0 + y1) / 2 / height, (x1 - x0) / width, (y1 - y0) / height)
            if boxed:
                boxes.append((CLASS_INDEX[target],) + boxed)
        add("Datacluster-auto-rickshaw", hsplit(xml_path), image, boxes, key=xml_path)

    restamp_assisteye()
    notes.append("skipped OAODD: the Hub repo has labels and no images")
    notes.append("skipped google-search folders in tuk-tuk: unlabeled, license unknown")
    notes.append("skipped RDD2022: CC BY-SA share-alike may encumber shipped weights")
    notes.append("AssistEye hole (crack) dropped; bump mapped to curb")


def restamp_assisteye() -> None:
    """Give the curb/pole source a test slice. It has no official test split."""
    changed = 0
    for index, (src, _split, img, boxes, key) in enumerate(records):
        if src != "AssistEye-unified-obstacles":
            continue
        records[index] = (src, hsplit("assist:" + key), img, boxes, key)
        changed += 1
    notes.append(f"AssistEye images hash-split {changed}")


def select(split: str, rows: list, cap: int) -> list:
    chosen = []
    chosen_keys = set()
    counts: dict[int, int] = collections.Counter()
    for name in PRIORITY:
        class_id = CLASS_INDEX[name]
        group = [row for row in rows if any(box[0] == class_id for box in row[3]) and row[4] not in chosen_keys]
        group.sort(key=lambda row: hashlib.md5(row[4].encode()).hexdigest())
        for row in group:
            if counts[class_id] >= CAPS[name]:
                break
            if len(chosen) >= cap:
                return chosen
            chosen.append(row)
            chosen_keys.add(row[4])
            for class_id_here in {box[0] for box in row[3]}:
                counts[class_id_here] += 1
    return chosen


def write_set() -> None:
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    for split in ("train", "val", "test"):
        os.makedirs(f"{OUT}/images/{split}")
        os.makedirs(f"{OUT}/labels/{split}")
    by_split = collections.defaultdict(list)
    for row in records:
        by_split[row[1]].append(row)
    kept_rows = []
    for split, cap in (("train", TRAIN_CAP), ("val", VAL_CAP), ("test", TEST_CAP)):
        picked = select(split, by_split[split], cap)
        notes.append(f"{split} records {len(by_split[split])} kept {len(picked)}")
        kept_rows.extend(picked)
    count_img = collections.defaultdict(lambda: collections.Counter())
    count_box = collections.defaultdict(lambda: collections.Counter())
    src_cnt = collections.Counter()
    manifest = []
    kept = 0
    for index, (src, split, img, boxes, _key) in enumerate(kept_rows):
        name = f"{index:06d}"
        try:
            with Image.open(img) as image:
                image = image.convert("RGB")
                if max(image.size) > 640:
                    image.thumbnail((640, 640))
                image.save(f"{OUT}/images/{split}/{name}.jpg", quality=85)
                width, height = image.size
        except Exception as exc:  # noqa: BLE001
            print("bad", img, exc, flush=True)
            continue
        with open(f"{OUT}/labels/{split}/{name}.txt", "w") as handle:
            for box in boxes:
                handle.write("%d %.6f %.6f %.6f %.6f\n" % box)
        src_cnt[src] += 1
        manifest.append((f"images/{split}/{name}.jpg", src, img, width, height, split))
        for cls in {box[0] for box in boxes}:
            count_img[split][CLASSES[cls]] += 1
        for box in boxes:
            count_box[split][CLASSES[box[0]]] += 1
        kept += 1
        if kept % 500 == 0:
            print("wrote", kept, flush=True)
    stats = {
        "classes": CLASSES,
        "notes": notes,
        "mapping": mapping,
        "src_cnt": src_cnt,
        "images_per_class": {split: dict(counter) for split, counter in count_img.items()},
        "boxes_per_class": {split: dict(counter) for split, counter in count_box.items()},
        "images_written": kept,
    }
    json.dump(stats, open(f"{OUT}/stats.json", "w"), indent=1, default=dict)
    with open(f"{OUT}/manifest.csv", "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["file", "source", "original_path", "width", "height", "split"])
        writer.writerows(manifest)
    payload = {
        "path": OUT,
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": {index: name for index, name in enumerate(CLASSES)},
    }
    with open(f"{OUT}/data.yaml", "w") as handle:
        yaml.safe_dump(payload, handle, sort_keys=False)
    os.makedirs("/workspace/training/metrics", exist_ok=True)
    shutil.copy(f"{OUT}/stats.json", "/workspace/training/metrics/dataset_v2_stats.json")
    print(dict(src_cnt), flush=True)
    print("NOTES", notes, flush=True)
    print("wrote", kept, "to", OUT, flush=True)


def main() -> None:
    load_sources()
    print("records", len(records), flush=True)
    write_set()


if __name__ == "__main__":
    main()
