#!/usr/bin/env python3
"""Download COCO indoor pictures and Open Images doors.

COCO annotations are CC BY 4.0. The photos come from Flickr and are not
all cleared for every commercial use; the About screen says so.
Open Images door boxes are CC BY 4.0 and the photos are CC BY 2.0.

Per-class caps keep the download small enough to train on this machine.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

RAW = "/workspace/datasets/raw"
COCO = os.path.join(RAW, "coco")
OI = os.path.join(RAW, "openimages")
ANNO_ZIP = os.path.join(COCO, "annotations_trainval2017.zip")
ANNO_URL = "http://images.cocodataset.org/annotations/annotations_trainval2017.zip"
IMAGE_URL = "http://images.cocodataset.org/train2017/{}"
BBOX_URL = "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv"
CLASS_URL = "https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions-boxable.csv"
CLASS_URL_FALLBACK = "https://storage.googleapis.com/openimages/v5/class-descriptions-boxable.csv"

CAPS = {
    "toilet": 2000,
    "refrigerator": 2000,
    "couch": 1600,
    "tv": 1600,
    "bed": 1600,
    "cat": 1600,
    "potted plant": 1600,
    "dog": 1000,
    "chair": 1600,
    "dining table": 1600,
}
DOOR_CAP = 2000


def log(msg: str) -> None:
    print(msg, flush=True)


def fetch(url: str, timeout: int = 180):
    req = urllib.request.Request(url, headers={"User-Agent": "obstacle-guide-dataset/1.0"})
    return urllib.request.urlopen(req, timeout=timeout)


def download_file(url: str, dest: str) -> None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        log(f"have {dest}")
        return
    log(f"get {url}")
    tmp = dest + ".part"
    with fetch(url, timeout=300) as response, open(tmp, "wb") as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)
    os.replace(tmp, dest)


def coco() -> None:
    os.makedirs(COCO, exist_ok=True)
    download_file(ANNO_URL, ANNO_ZIP)
    json_path = os.path.join(COCO, "annotations", "instances_train2017.json")
    if not os.path.exists(json_path):
        log("extract annotations")
        with zipfile.ZipFile(ANNO_ZIP) as archive:
            archive.extract("annotations/instances_train2017.json", COCO)
    log("load annotations")
    data = json.load(open(json_path))
    names = {cat["id"]: cat["name"] for cat in data["categories"]}
    wanted_ids = {cid for cid, name in names.items() if name in CAPS or name == "person"}
    images = {image["id"]: image for image in data["images"]}
    by_class = defaultdict(list)
    classes_in = defaultdict(set)
    anns_of = defaultdict(list)
    for ann in data["annotations"]:
        name = names.get(ann["category_id"])
        if ann["category_id"] not in wanted_ids:
            continue
        if ann.get("iscrowd"):
            continue
        anns_of[ann["image_id"]].append(ann)
        if name in CAPS:
            classes_in[ann["image_id"]].add(name)
    for image_id, names_here in classes_in.items():
        for name in names_here:
            by_class[name].append(image_id)
    selected: set[int] = set()
    rare_first = sorted(CAPS, key=lambda name: len(by_class[name]))
    for name in rare_first:
        have = 0
        ids = sorted(by_class[name], key=lambda image_id: hashlib.md5(str(image_id).encode()).hexdigest())
        for image_id in ids:
            if have >= CAPS[name]:
                break
            if image_id in selected:
                have += 1
                continue
            selected.add(image_id)
            have += 1
        log(f"coco {name} images available {len(by_class[name])} kept toward cap {CAPS[name]}")
    log(f"coco selected images {len(selected)}")
    kept_images = []
    kept_anns = []
    for image_id in selected:
        image = images[image_id]
        kept_images.append(image)
        kept_anns.extend(anns_of[image_id])
    slim = {
        "images": [
            {
                "id": image["id"],
                "file_name": f"train2017/{image['file_name']}",
                "width": image["width"],
                "height": image["height"],
            }
            for image in kept_images
        ],
        "annotations": kept_anns,
        "categories": [cat for cat in data["categories"] if cat["id"] in wanted_ids],
    }
    slim_path = os.path.join(COCO, "indoor_instances.json")
    json.dump(slim, open(slim_path, "w"))
    log(f"wrote {slim_path} images {len(kept_images)} boxes {len(kept_anns)}")

    img_dir = os.path.join(COCO, "train2017")
    os.makedirs(img_dir, exist_ok=True)
    missing = []
    for image in kept_images:
        path = os.path.join(img_dir, image["file_name"])
        if not os.path.exists(path) or os.path.getsize(path) < 1000:
            missing.append(image["file_name"])

    def one(file_name: str) -> str:
        dest = os.path.join(img_dir, file_name)
        tmp = dest + ".part"
        try:
            with fetch(IMAGE_URL.format(file_name), timeout=60) as response, open(tmp, "wb") as handle:
                handle.write(response.read())
            if os.path.getsize(tmp) < 1000:
                os.remove(tmp)
                return "fail"
            os.replace(tmp, dest)
            return "ok"
        except Exception:
            if os.path.exists(tmp):
                os.remove(tmp)
            return "fail"

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures = [pool.submit(one, name) for name in missing]
        for index, future in enumerate(as_completed(futures), start=1):
            if future.result() == "ok":
                ok += 1
            else:
                fail += 1
            if index % 250 == 0:
                log(f"coco images {index}/{len(missing)} ok {ok} fail {fail}")
    log(f"OK coco downloaded {ok} failed {fail} already {len(kept_images) - len(missing)}")


def door_mid() -> str:
    for url in (CLASS_URL, CLASS_URL_FALLBACK):
        try:
            with fetch(url, timeout=60) as response:
                text = response.read().decode("utf-8", "replace")
        except Exception as exc:
            log(f"class list fail {url} {exc}")
            continue
        for line in text.splitlines():
            parts = line.split(",")
            if len(parts) < 2:
                continue
            mid, name = parts[0].strip(), parts[1].strip().strip('"')
            if name == "Door":
                log(f"door label {mid} from {url}")
                return mid
    raise SystemExit("Door label was not in the Open Images class list")


def doors() -> None:
    os.makedirs(os.path.join(OI, "images"), exist_ok=True)
    mid = door_mid()
    boxes_path = os.path.join(OI, "door_boxes.csv")
    grouped: dict[str, list[tuple[str, str, str, str]]] = defaultdict(list)
    if os.path.exists(boxes_path) and os.path.getsize(boxes_path) > 1000:
        with open(boxes_path) as handle:
            for row in csv.DictReader(handle):
                grouped[row["ImageID"]].append((row["XMin"], row["XMax"], row["YMin"], row["YMax"]))
        log(f"door boxes cached images {len(grouped)}")
    else:
        log("stream door boxes")
        with fetch(BBOX_URL, timeout=300) as response:
            text = io.TextIOWrapper(response, encoding="utf-8", newline="")
            reader = csv.DictReader(text)
            for row in reader:
                if row.get("LabelName") != mid:
                    continue
                if row.get("Confidence") not in (None, "", "1"):
                    continue
                if row.get("IsGroupOf") == "1" or row.get("IsDepiction") == "1":
                    continue
                grouped[row["ImageID"]].append((row["XMin"], row["XMax"], row["YMin"], row["YMax"]))
        ids = sorted(grouped, key=lambda image_id: hashlib.md5(image_id.encode()).hexdigest())
        log(f"door images before cap {len(ids)}")
        ids = ids[:DOOR_CAP]
        with open(boxes_path, "w", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["ImageID", "XMin", "XMax", "YMin", "YMax"])
            for image_id in ids:
                for box in grouped[image_id]:
                    writer.writerow((image_id, *box))
        grouped = {image_id: grouped[image_id] for image_id in ids}
        log(f"door images kept {len(ids)}")

    missing = []
    for image_id in grouped:
        path = os.path.join(OI, "images", f"{image_id}.jpg")
        if not os.path.exists(path) or os.path.getsize(path) < 1000:
            missing.append(image_id)

    def one(image_id: str) -> str:
        dest = os.path.join(OI, "images", f"{image_id}.jpg")
        tmp = dest + ".part"
        url = f"https://open-images-dataset.s3.amazonaws.com/train/{image_id}.jpg"
        try:
            with fetch(url, timeout=60) as response, open(tmp, "wb") as handle:
                handle.write(response.read())
            if os.path.getsize(tmp) < 1000:
                os.remove(tmp)
                return "fail"
            os.replace(tmp, dest)
            return "ok"
        except Exception:
            if os.path.exists(tmp):
                os.remove(tmp)
            return "fail"

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures = [pool.submit(one, image_id) for image_id in missing]
        for index, future in enumerate(as_completed(futures), start=1):
            if future.result() == "ok":
                ok += 1
            else:
                fail += 1
            if index % 200 == 0:
                log(f"door images {index}/{len(missing)} ok {ok} fail {fail}")
    log(f"OK doors downloaded {ok} failed {fail} already {len(grouped) - len(missing)}")


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("coco", "all"):
        coco()
    if which in ("door", "all"):
        doors()


if __name__ == "__main__":
    main()
