#!/usr/bin/env python3
"""Build obstacles_v1.

Walking classes come first. Indoor COCO classes and Open Images doors are
appended, so a chair and a pothole share one model. speed_bump and
low_branch are omitted. Images are resized so the long side is at most 640.

curbs_zhonghua is skipped: the zip is 100 pairs of RGB photos and has no
boxes. manhole_delima87 is skipped: it is a manhole-versus-void photo
sort with no boxes. Full-frame boxes would teach the wrong shape.
"""
from __future__ import annotations

import collections
import csv
import glob
import hashlib
import json
import os
import shutil
import xml.etree.ElementTree as ET

from PIL import Image

RAW = "/workspace/datasets/raw"
OUT = "/workspace/datasets/obstacles_v1"
CLASSES = [
    "person",
    "bicycle",
    "motorcycle",
    "car",
    "bus",
    "truck",
    "cow",
    "dog",
    "auto_rickshaw",
    "cycle_rickshaw",
    "handcart",
    "pothole",
    "manhole_open_drain",
    "curb",
    "stairs",
    "pole_bollard",
    "barrier",
    "street_furniture",
    "tree",
    "cat",
    "chair",
    "couch",
    "dining table",
    "bed",
    "toilet",
    "potted plant",
    "refrigerator",
    "tv",
    "door",
]
CI = {name: index for index, name in enumerate(CLASSES)}
STREET_ONLY_CAP = 2000
seen: set[str] = set()
records: list[tuple] = []
mapping_rows: list[tuple] = []
notes: list[str] = []


def addmap(src: str, orig: str, tgt: str | None) -> None:
    mapping_rows.append((src, orig, tgt or "(dropped)"))


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


def add(src: str, split: str, img: str, boxes: list, allow_empty: bool = False) -> None:
    if not os.path.exists(img):
        return
    if not boxes and not allow_empty:
        return
    digest = md5f(img)
    if digest in seen:
        return
    seen.add(digest)
    records.append((src, split, img, boxes))


def clip(xc: float, yc: float, w: float, h: float):
    x0, y0 = max(0, xc - w / 2), max(0, yc - h / 2)
    x1, y1 = min(1, xc + w / 2), min(1, yc + h / 2)
    if x1 - x0 <= 0.002 or y1 - y0 <= 0.002:
        return None
    return ((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)


def yolo_src(src, root, splits, names, cmap) -> None:
    if not os.path.isdir(root):
        notes.append(f"missing {src} at {root}")
        return
    for original, target in zip(names, [cmap.get(name) for name in names]):
        addmap(src, original, target)
    for sp_src, sp in splits:
        imgidx = {}
        for path in glob.glob(f"{root}/{sp_src}/images/**/*.*", recursive=True):
            imgidx[os.path.splitext(os.path.basename(path))[0]] = path
        for label_path in glob.glob(f"{root}/{sp_src}/labels/**/*.txt", recursive=True):
            stem = os.path.splitext(os.path.basename(label_path))[0]
            if stem not in imgidx:
                continue
            boxes = []
            for line in open(label_path):
                values = line.split()
                if len(values) < 5:
                    continue
                index = int(float(values[0]))
                target = cmap.get(names[index]) if index < len(names) else None
                if target is None:
                    continue
                if len(values) == 5:
                    boxed = clip(*map(float, values[1:5]))
                else:
                    xs = list(map(float, values[1::2]))
                    ys = list(map(float, values[2::2]))
                    if len(xs) != len(ys) or len(xs) < 3:
                        continue
                    boxed = clip(
                        (min(xs) + max(xs)) / 2,
                        (min(ys) + max(ys)) / 2,
                        max(xs) - min(xs),
                        max(ys) - min(ys),
                    )
                if boxed:
                    boxes.append((CI[target],) + boxed)
            add(src, sp, imgidx[stem], boxes)


def coco_src(src, jsonpath, split, cmap, skip_crowd=True) -> None:
    if not os.path.exists(jsonpath):
        notes.append(f"missing coco {src} {jsonpath}")
        return
    data = json.load(open(jsonpath))
    base = os.path.dirname(jsonpath)
    cats = {cat["id"]: cat["name"] for cat in data["categories"]}
    for name in sorted(set(cats.values())):
        addmap(src, name, cmap.get(name))
    images = {image["id"]: image for image in data["images"]}
    by = collections.defaultdict(list)
    for ann in data["annotations"]:
        if skip_crowd and ann.get("iscrowd"):
            continue
        target = cmap.get(cats[ann["category_id"]])
        if target is None:
            continue
        image = images[ann["image_id"]]
        x, y, w, h = ann["bbox"]
        width, height = image["width"], image["height"]
        if not width or not height:
            continue
        boxed = clip((x + w / 2) / width, (y + h / 2) / height, w / width, h / height)
        if boxed:
            by[ann["image_id"]].append((CI[target],) + boxed)
    for image_id, boxes in by.items():
        add(src, split, os.path.join(base, images[image_id]["file_name"]), boxes)


def find_image(stem_dir: str, stem: str) -> str | None:
    for ext in (".jpg", ".jpeg", ".png", ".JPG", ".webp"):
        path = os.path.join(stem_dir, stem + ext)
        if os.path.exists(path):
            return path
    return None


def load_sources() -> None:
    rod_names = [
        "Bike", "Building", "Car", "Person", "Stairs", "Traffic sign", "Electrical Pole",
        "Road", "Motorcycle", "Dustbin", "Dog", "Manhole", "Tree", "Guard rail",
        "Pedestrian crosswalk", "Truck", "Bus", "Bench", "Traffic Cone", "Fire hydrant",
        "Traffic Barrel", "Plant Pot", "Electrical Box", "Chair", "Bicycle Rack",
    ]
    rod_map = {
        "Bike": "bicycle",
        "Car": "car",
        "Person": "person",
        "Stairs": "stairs",
        "Electrical Pole": "pole_bollard",
        "Motorcycle": "motorcycle",
        "Dustbin": "street_furniture",
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
        "Plant Pot": "street_furniture",
        "Electrical Box": "street_furniture",
        "Chair": "street_furniture",
        "Bicycle Rack": "street_furniture",
    }
    yolo_src("ROD", f"{RAW}/rod", [("train", "train"), ("valid", "val"), ("test", "test")], rod_names, rod_map)

    for name, target in [("D00", None), ("D10", None), ("D20", None), ("D40", "pothole")]:
        addmap("RDD2022-India", name, target)
    background = 0
    xml_root = f"{RAW}/rdd2022_india/India/train/annotations/xmls"
    image_root = f"{RAW}/rdd2022_india/India/train/images"
    for xml_path in sorted(glob.glob(f"{xml_root}/*.xml")):
        root = ET.parse(xml_path).getroot()
        width = int(float(root.findtext("size/width") or 0))
        height = int(float(root.findtext("size/height") or 0))
        boxes = []
        if width and height:
            for obj in root.findall("object"):
                if (obj.findtext("name") or "").strip() != "D40":
                    continue
                box = obj.find("bndbox")
                if box is None:
                    continue
                x0, y0, x1, y1 = [float(box.findtext(key) or 0) for key in ("xmin", "ymin", "xmax", "ymax")]
                boxed = clip((x0 + x1) / 2 / width, (y0 + y1) / 2 / height, (x1 - x0) / width, (y1 - y0) / height)
                if boxed:
                    boxes.append((CI["pothole"],) + boxed)
        stem = os.path.splitext(os.path.basename(xml_path))[0]
        image = find_image(image_root, stem)
        if image is None:
            continue
        if not boxes and background < 300 and root.find("object") is None:
            background += 1
            add("RDD2022-India", hsplit(stem), image, [], allow_empty=True)
        else:
            add("RDD2022-India", hsplit(stem), image, boxes)

    oi_names = {"Stairs": "stairs", "Street light": "pole_bollard", "Cart": "handcart", "Cattle": "cow"}
    for key, value in oi_names.items():
        addmap("OpenImagesV7", key, value)
    lab = {"/m/01lynh": "Stairs", "/m/033rq4": "Street light", "/m/018p4k": "Cart", "/m/01xq0k1": "Cattle"}
    boxes_csv = f"{RAW}/openimages/selected_boxes.csv"
    by = collections.defaultdict(list)
    if os.path.exists(boxes_csv):
        for row in csv.DictReader(open(boxes_csv)):
            label = lab.get(row["LabelName"])
            if label is None:
                continue
            x0, x1, y0, y1 = [float(row[key]) for key in ("XMin", "XMax", "YMin", "YMax")]
            boxed = clip((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
            if boxed:
                by[row["ImageID"]].append((CI[oi_names[label]],) + boxed)
        pole = CI["pole_bollard"]
        street_only = [image_id for image_id, boxes in by.items() if boxes and all(box[0] == pole for box in boxes)]
        others = [image_id for image_id in by if image_id not in set(street_only)]
        street_only.sort(key=lambda image_id: hashlib.md5(image_id.encode()).hexdigest())
        kept_street = street_only[:STREET_ONLY_CAP]
        notes.append(
            f"Open Images street-light-only images {len(street_only)} capped to {len(kept_street)}; "
            f"other Open Images images kept {len(others)}"
        )
        for image_id in others + kept_street:
            add("OpenImagesV7", hsplit(image_id), f"{RAW}/openimages/images/{image_id}.jpg", by[image_id])
    else:
        notes.append("missing Open Images boxes")

    for split_name, split in [("train", "train"), ("valid", "val"), ("test", "test")]:
        coco_src(
            "Roboflow-pothole-detection-irkz9",
            f"{RAW}/pothole_keremberke/data/{split_name}/_annotations.coco.json",
            split,
            {"pothole": "pothole", "potholes": "pothole"},
        )
    yolo_src(
        "Roboflow-potholes-detection-d4rma",
        f"{RAW}/pothole_ryukijano",
        [("train", "train"), ("valid", "val"), ("test", "test")],
        ["0"],
        {"0": "pothole"},
    )

    tuk = {
        "tuktuk": "auto_rickshaw",
        "auto": "auto_rickshaw",
        "auto-rickshaw": "auto_rickshaw",
        "rickshaw": "cycle_rickshaw",
        "Rickshaw": "cycle_rickshaw",
        "car": "car",
        "Car": "car",
        "bus": "bus",
        "truck": "truck",
        "lorry": "truck",
        "motorcycle": "motorcycle",
        "bicycle": "bicycle",
    }
    for split_name, split in [("train", "train"), ("valid", "val"), ("test", "test")]:
        coco_src("Roboflow-tuk-tuk-detection", f"{RAW}/tuktuk_shravya11/{split_name}/_annotations.coco.json", split, tuk)
        coco_src(
            "Roboflow-rickshaw-export",
            f"{RAW}/tuktuk_shravya11/rickshaw/{split_name}/_annotations.coco.json",
            split,
            tuk,
        )
    addmap("Datacluster-auto-rickshaw", "auto (any VOC name)", "auto_rickshaw")
    for xml_path in glob.glob(f"{RAW}/tuktuk_shravya11/datacluster_auto/Annotations/Annotations/*.xml"):
        root = ET.parse(xml_path).getroot()
        width = int(float(root.findtext("size/width") or 0))
        height = int(float(root.findtext("size/height") or 0))
        filename = root.findtext("filename") or ""
        image = f"{RAW}/tuktuk_shravya11/datacluster_auto/auto/auto/{filename}"
        if not os.path.exists(image):
            stem = os.path.splitext(os.path.basename(xml_path))[0]
            found = glob.glob(f"{RAW}/tuktuk_shravya11/datacluster_auto/auto/auto/{glob.escape(stem)}.*")
            image = found[0] if found else image
        if (width == 0 or height == 0) and os.path.exists(image):
            with Image.open(image) as im:
                width, height = im.size
        boxes = []
        if width and height:
            for obj in root.findall("object"):
                box = obj.find("bndbox")
                if box is None:
                    continue
                x0, y0, x1, y1 = [float(box.findtext(key) or 0) for key in ("xmin", "ymin", "xmax", "ymax")]
                boxed = clip((x0 + x1) / 2 / width, (y0 + y1) / 2 / height, (x1 - x0) / width, (y1 - y0) / height)
                if boxed:
                    boxes.append((CI["auto_rickshaw"],) + boxed)
        add("Datacluster-auto-rickshaw", hsplit(xml_path), image, boxes)

    assist = ["animal", "open_manhole", "waste_container", "pothole", "bump", "fence", "hole", "pole"]
    yolo_src(
        "AssistEye-unified-obstacles",
        f"{RAW}/assisteye_obstacles/unified_obstacles",
        [("train", "train"), ("valid", "val")],
        assist,
        {
            "open_manhole": "manhole_open_drain",
            "waste_container": "street_furniture",
            "pothole": "pothole",
            "bump": "curb",
            "fence": "barrier",
            "pole": "pole_bollard",
        },
    )
    coco_keep = {
        "person": "person",
        "dog": "dog",
        "cat": "cat",
        "chair": "chair",
        "couch": "couch",
        "dining table": "dining table",
        "bed": "bed",
        "toilet": "toilet",
        "potted plant": "potted plant",
        "refrigerator": "refrigerator",
        "tv": "tv",
    }
    for name in coco_keep:
        addmap("COCO2017", name, coco_keep[name])
    indoor_json = f"{RAW}/coco/indoor_instances.json"
    if os.path.exists(indoor_json):
        data = json.load(open(indoor_json))
        base = os.path.dirname(indoor_json)
        cats = {cat["id"]: cat["name"] for cat in data["categories"]}
        images = {image["id"]: image for image in data["images"]}
        by = collections.defaultdict(list)
        for ann in data["annotations"]:
            target = coco_keep.get(cats.get(ann["category_id"]))
            if target is None:
                continue
            image = images[ann["image_id"]]
            x, y, w, h = ann["bbox"]
            width, height = image["width"], image["height"]
            if not width or not height:
                continue
            boxed = clip((x + w / 2) / width, (y + h / 2) / height, w / width, h / height)
            if boxed:
                by[ann["image_id"]].append((CI[target],) + boxed)
        for image_id, boxes in by.items():
            image = images[image_id]
            add(
                "COCO2017",
                hsplit(image["file_name"]),
                os.path.join(base, image["file_name"]),
                boxes,
            )
    else:
        notes.append("missing COCO indoor json")

    addmap("OpenImagesV7", "Door", "door")
    door_csv = f"{RAW}/openimages/door_boxes.csv"
    if os.path.exists(door_csv):
        door_by = collections.defaultdict(list)
        for row in csv.DictReader(open(door_csv)):
            x0, x1, y0, y1 = [float(row[key]) for key in ("XMin", "XMax", "YMin", "YMax")]
            boxed = clip((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
            if boxed:
                door_by[row["ImageID"]].append((CI["door"],) + boxed)
        for image_id, boxes in door_by.items():
            add("OpenImages-Door", hsplit(image_id), f"{RAW}/openimages/images/{image_id}.jpg", boxes)
        notes.append(f"Open Images doors grouped into {len(door_by)} images")
    else:
        notes.append("missing Open Images door boxes")

    notes.append("skipped curbs_zhonghua: RGB pairs, no boxes")
    notes.append("skipped manhole_delima87: class folders, no boxes")
    notes.append("dropped speed_bump and low_branch: no images in the source list")


def write_set() -> None:
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    for split in ("train", "val", "test"):
        os.makedirs(f"{OUT}/images/{split}")
        os.makedirs(f"{OUT}/labels/{split}")
    count_img = collections.defaultdict(lambda: collections.Counter())
    count_box = collections.defaultdict(lambda: collections.Counter())
    src_cnt = collections.Counter()
    manifest = []
    kept = 0
    for index, (src, split, img, boxes) in enumerate(records):
        name = f"{src.split('-')[0].lower()[:10]}_{index:06d}"
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
        manifest.append((f"images/{split}/{name}.jpg", src, img, width, height))
        for cls in {box[0] for box in boxes}:
            count_img[split][cls] += 1
        for box in boxes:
            count_box[split][box[0]] += 1
        kept += 1
        if kept % 500 == 0:
            print("wrote", kept, flush=True)
    stats = {
        "classes": CLASSES,
        "notes": notes,
        "mapping": mapping_rows,
        "src_cnt": src_cnt,
        "cnt_img": {split: dict(counter) for split, counter in count_img.items()},
        "cnt_box": {split: dict(counter) for split, counter in count_box.items()},
        "images_written": kept,
    }
    json.dump(stats, open(f"{OUT}/stats.json", "w"), indent=1)
    with open(f"{OUT}/manifest.csv", "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["file", "source", "original_path", "width", "height"])
        writer.writerows(manifest)
    yaml_names = "\n".join(f"  {index}: {name}" for index, name in enumerate(CLASSES))
    open(f"{OUT}/data.yaml", "w").write(
        f"path: {OUT}\ntrain: images/train\nval: images/val\ntest: images/test\n"
        f"nc: {len(CLASSES)}\nnames:\n{yaml_names}\n"
    )
    os.makedirs("/workspace/training/metrics", exist_ok=True)
    shutil.copy(f"{OUT}/stats.json", "/workspace/training/metrics/dataset_stats.json")
    print(dict(src_cnt), flush=True)
    print("NOTES", notes, flush=True)
    print("wrote", kept, "to", OUT, flush=True)


def main() -> None:
    load_sources()
    print("records", len(records), flush=True)
    write_set()


if __name__ == "__main__":
    main()
