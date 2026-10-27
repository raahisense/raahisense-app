#!/usr/bin/env python3
"""Download labeled sources for the v2 obstacle set.

Only sources whose license allows commercial use and redistribution of a
trained model are fetched. See training/DATASETS.md. Images stay under
/workspace/datasets, which is gitignored.

Stages: hf, oi, coco, all.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed

RAW = "/workspace/datasets/raw"
os.makedirs(RAW, exist_ok=True)

# Display name -> (app class, max images that contain this Open Images label).
OI_KEEP = {
    "Person": ("person", 700),
    "Man": ("person", 350),
    "Woman": ("person", 350),
    "Boy": ("person", 250),
    "Girl": ("person", 250),
    "Bicycle": ("bicycle", 500),
    "Motorcycle": ("motorcycle", 450),
    "Car": ("car", 800),
    "Taxi": ("car", 200),
    "Ambulance": ("car", 120),
    "Bus": ("bus", 400),
    "Truck": ("truck", 400),
    "Van": ("truck", 200),
    "Cattle": ("cow", 350),
    "Bull": ("cow", 150),
    "Dog": ("dog", 450),
    "Cat": ("cat", 350),
    "Chair": ("chair", 800),
    "Couch": ("couch", 500),
    "Studio couch": ("couch", 200),
    "Sofa bed": ("couch", 150),
    "Table": ("dining table", 400),
    "Coffee table": ("dining table", 250),
    "Desk": ("dining table", 300),
    "Kitchen & dining room table": ("dining table", 350),
    "Bed": ("bed", 450),
    "Infant bed": ("bed", 150),
    "Toilet": ("toilet", 400),
    "Houseplant": ("potted plant", 450),
    "Refrigerator": ("refrigerator", 400),
    "Television": ("tv", 400),
    "Door": ("door", 900),
    "Footwear": ("shoe", 500),
    "Boot": ("shoe", 250),
    "Sandal": ("shoe", 200),
    "High heels": ("shoe", 120),
    "Handbag": ("bag", 400),
    "Backpack": ("bag", 400),
    "Luggage and bags": ("bag", 250),
    "Suitcase": ("bag", 250),
    "Briefcase": ("bag", 150),
    "Toy": ("toy", 400),
    "Doll": ("toy", 250),
    "Teddy bear": ("toy", 250),
    "Microwave oven": ("appliance", 250),
    "Oven": ("appliance", 250),
    "Washing machine": ("appliance", 250),
    "Dishwasher": ("appliance", 180),
    "Toaster": ("appliance", 150),
    "Gas stove": ("appliance", 150),
    "Kitchen appliance": ("appliance", 200),
    "Chest of drawers": ("cabinet", 250),
    "Wardrobe": ("cabinet", 300),
    "Bookcase": ("cabinet", 250),
    "Filing cabinet": ("cabinet", 150),
    "Nightstand": ("cabinet", 180),
    "Stairs": ("stairs", 700),
    "Street light": ("pole_bollard", 700),
    "Bench": ("street_furniture", 400),
    "Waste container": ("bin", 600),
    "Fire hydrant": ("street_furniture", 200),
    "Traffic sign": ("street_furniture", 350),
    "Tree": ("tree", 320),
    "Palm tree": ("tree", 150),
    "Cart": ("handcart", 400),
    "Building": ("wall", 900),
    "Office building": ("wall", 350),
    "Billboard": ("wall", 250),
}

BBOX_URL = "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv"
CLASS_URL = "https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions-boxable.csv"
IMAGE_URL = "https://open-images-dataset.s3.amazonaws.com/train/{}.jpg"
STUFF_ZIP = "http://images.cocodataset.org/annotations/stuff_annotations_trainval2017.zip"
COCO_IMAGE = "http://images.cocodataset.org/{}/{}"
WALL_IMAGE_CAP = 1600
FENCE_IMAGE_CAP = 400

HF_FULL = [
    ("keremberke/pothole-segmentation", "pothole_keremberke", None),
    ("Dderedor/obstacles_on_the_road", "assisteye_obstacles", None),
]


def log(msg: str) -> None:
    print(msg, flush=True)


def fetch(url: str, timeout: int = 180):
    import urllib.request

    req = urllib.request.Request(url, headers={"User-Agent": "raahisense-obstacles/2.0"})
    return urllib.request.urlopen(req, timeout=timeout)


def download_file(url: str, dest: str, min_bytes: int = 1000) -> None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    have = os.path.getsize(dest) if os.path.exists(dest) else 0
    if have >= min_bytes and not url.endswith(".csv"):
        # Completed image-like files are left in place. CSV/zip use the size check below.
        pass
    tmp = dest + ".part"
    done = os.path.getsize(tmp) if os.path.exists(tmp) else 0
    if os.path.exists(dest) and os.path.getsize(dest) > min_bytes and not os.path.exists(tmp):
        log(f"have {dest}")
        return
    headers = {"User-Agent": "raahisense-obstacles/2.0"}
    if done:
        headers["Range"] = f"bytes={done}-"
    import urllib.request

    req = urllib.request.Request(url, headers=headers)
    mode = "ab" if done else "wb"
    with urllib.request.urlopen(req, timeout=300) as response, open(tmp, mode) as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)
            done += len(chunk)
            if done % (80 * 1024 * 1024) < 1024 * 1024:
                log(f"{os.path.basename(dest)} {done // (1024 * 1024)} MB")
    os.replace(tmp, dest)
    log(f"saved {dest} bytes {os.path.getsize(dest)}")


def download_images(pairs: list[tuple[str, str]], workers: int = 16) -> None:
    missing = [(url, dest) for url, dest in pairs if not (os.path.exists(dest) and os.path.getsize(dest) > 800)]
    log(f"images to fetch {len(missing)} of {len(pairs)}")

    def one(item: tuple[str, str]) -> str:
        url, dest = item
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        tmp = dest + ".part"
        try:
            with fetch(url, timeout=60) as response, open(tmp, "wb") as handle:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            if os.path.getsize(tmp) < 800:
                os.remove(tmp)
                return "fail"
            os.replace(tmp, dest)
            return "ok"
        except Exception:
            if os.path.exists(tmp):
                os.remove(tmp)
            return "fail"

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, item) for item in missing]
        for index, future in enumerate(as_completed(futures), start=1):
            if future.result() == "ok":
                ok += 1
            else:
                fail += 1
            if index % 200 == 0 or index == len(missing):
                log(f"images {index}/{len(missing)} ok {ok} fail {fail}")


def hf_snapshot(repo: str, name: str, patterns: list[str] | None) -> None:
    from huggingface_hub import snapshot_download

    dest = os.path.join(RAW, name)
    os.makedirs(dest, exist_ok=True)
    snapshot_download(repo, repo_type="dataset", local_dir=dest, allow_patterns=patterns, max_workers=8)
    log(f"OK hf {name}")


def hf_files(repo: str, dest_root: str, filenames: list[str]) -> None:
    from huggingface_hub import hf_hub_download

    def one(filename: str) -> str:
        try:
            hf_hub_download(repo, filename=filename, repo_type="dataset", local_dir=dest_root)
            return "ok"
        except Exception as exc:  # noqa: BLE001
            log(f"miss {filename} {exc}")
            return "fail"

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(one, name) for name in filenames]
        for index, future in enumerate(as_completed(futures), start=1):
            if future.result() == "ok":
                ok += 1
            else:
                fail += 1
            if index % 250 == 0 or index == len(filenames):
                log(f"{repo} files {index}/{len(filenames)} ok {ok} fail {fail}")


def stage_hf() -> None:
    for repo, name, patterns in HF_FULL:
        try:
            hf_snapshot(repo, name, patterns)
        except Exception as exc:  # noqa: BLE001
            log(f"FAIL {name} {exc}")
    unzip_potholes()
    stage_tuktuk()
    stage_rod()


def stage_tuktuk() -> None:
    """Annotated CC BY 4.0 exports only. Skip the unlabeled web-search folders.

    The rickshaw export is thousands of pictures. Keep a capped slice so the
    CPU training set is not mostly one class.
    """
    import json

    repo = "shravya11/tuk-tuk-detection"
    dest = os.path.join(RAW, "tuktuk_shravya11")
    hf_snapshot(
        repo,
        "tuktuk_shravya11",
        [
            "train/**",
            "valid/**",
            "test/**",
            "datacluster_auto/**",
            "rickshaw/**/_annotations.coco.json",
            "README*",
            "*.md",
            "*.txt",
            "*.yaml",
        ],
    )
    cap = 900
    chosen = []
    for json_path in sorted(glob_json(dest)):
        if len(chosen) >= cap:
            break
        data = json.load(open(json_path))
        base = os.path.dirname(json_path)
        rel_base = os.path.relpath(base, dest)
        images = data.get("images") or []
        images = sorted(images, key=lambda image: hashlib.md5(str(image["file_name"]).encode()).hexdigest())
        for image in images:
            if len(chosen) >= cap:
                break
            chosen.append(os.path.join(rel_base, image["file_name"]).replace("\\", "/"))
    rickshaw = [name for name in chosen if name.startswith("rickshaw/")]
    log(f"rickshaw images to fetch {len(rickshaw)}")
    hf_files(repo, dest, rickshaw)


def glob_json(dest: str) -> list[str]:
    import glob

    return glob.glob(f"{dest}/rickshaw/**/_annotations.coco.json", recursive=True)


def unzip_potholes() -> None:
    root = os.path.join(RAW, "pothole_keremberke")
    data = os.path.join(root, "data")
    if not os.path.isdir(data):
        return
    for name in os.listdir(data):
        if not name.endswith(".zip"):
            continue
        dest_marker = os.path.join(data, "." + name + ".ok")
        if os.path.exists(dest_marker):
            continue
        log(f"unzip {name}")
        with zipfile.ZipFile(os.path.join(data, name)) as archive:
            archive.extractall(data)
        open(dest_marker, "w").write("ok\n")


ROD_REPO = "Abtinzandi/Obstacle-Detection-Dataset-YOLO"
ROD_PRIORITY = [
    "Manhole",
    "Stairs",
    "Electrical Pole",
    "Building",
    "Dog",
    "Traffic Cone",
    "Guard rail",
    "Teraffic Barrel",
    "Traffic Barrel",
    "Dustbin",
    "Bench",
    "Fire hydrant",
    "Plant Pot",
    "Electrical Box",
    "Chair",
    "Bicycle Rack",
    "Bike",
    "Motorcycle",
    "Bus",
    "Truck",
    "Person",
    "Car",
    "Tree",
    "Traffic sign",
]
ROD_CAPS = {
    "Manhole": 700,
    "Stairs": 600,
    "Electrical Pole": 500,
    "Building": 800,
    "Dog": 350,
    "Traffic Cone": 350,
    "Guard rail": 350,
    "Teraffic Barrel": 250,
    "Traffic Barrel": 250,
    "Dustbin": 300,
    "Bench": 300,
    "Fire hydrant": 180,
    "Plant Pot": 200,
    "Electrical Box": 200,
    "Chair": 250,
    "Bicycle Rack": 160,
    "Bike": 400,
    "Motorcycle": 300,
    "Bus": 250,
    "Truck": 250,
    "Person": 700,
    "Car": 500,
    "Tree": 250,
    "Traffic sign": 250,
}


def stage_rod() -> None:
    """Boxes already live in the metadata CSVs, so skip the 24k-label snapshot.

    A full label snapshot trips the Hub rate limit. This writes the YOLO txt
    files locally and downloads only the balanced JPEG subset.
    """
    dest = os.path.join(RAW, "rod")
    os.makedirs(dest, exist_ok=True)
    marker = os.path.join(dest, ".images_ok")
    if os.path.exists(marker):
        log("ROD images already materialized")
        return
    ensure_rod_metadata(dest)
    jobs = materialize_rod_labels(dest)
    log(f"ROD images selected {len(jobs)}")
    fail = download_rod_images(jobs)
    if fail:
        log(f"ROD jpeg failures {fail}; re-run training/fetch_v2.py rod to resume")
        return
    open(marker, "w").write(f"{len(jobs)}\n")
    log("OK rod")


def ensure_rod_metadata(dest: str) -> None:
    from huggingface_hub import hf_hub_download

    names = [
        "data.yaml",
        "class_distribution.csv",
        "images_manifest.csv",
        "rename_map.csv",
        "README.md",
        "train/metadata.csv",
        "valid/metadata.csv",
        "test/metadata.csv",
    ]
    for name in names:
        path = os.path.join(dest, name)
        if os.path.exists(path) and os.path.getsize(path) > 40:
            continue
        hf_hub_download(ROD_REPO, filename=name, repo_type="dataset", local_dir=dest)


def materialize_rod_labels(dest: str) -> list[tuple[str, str]]:
    """Write selected label files. Return (repo path, local jpeg path)."""
    rows = []
    for folder in ("train", "valid", "test"):
        path = os.path.join(dest, folder, "metadata.csv")
        with open(path, newline="") as handle:
            for row in csv.DictReader(handle):
                names = {part.strip() for part in row["class_names"].split(",") if part.strip()}
                if not names:
                    continue
                file_name = row["file_name"].replace("\\", "/")
                stem = os.path.splitext(os.path.basename(file_name))[0]
                rows.append(
                    {
                        "folder": folder,
                        "stem": stem,
                        "repo": f"{folder}/{file_name}",
                        "names": names,
                        "boxes": row["bboxes_yolo"],
                    }
                )
    selected = []
    selected.extend(row for row in rows if row["folder"] == "test")
    val = [row for row in rows if row["folder"] == "valid"]
    val.sort(key=lambda row: hashlib.md5(row["stem"].encode()).hexdigest())
    selected.extend(val[:900])
    train = [row for row in rows if row["folder"] == "train"]
    chosen = set()
    counts: dict[str, int] = {}
    for class_name in ROD_PRIORITY:
        cap = ROD_CAPS.get(class_name, 0)
        if cap <= 0:
            continue
        group = [row for row in train if class_name in row["names"] and row["stem"] not in chosen]
        group.sort(key=lambda row: hashlib.md5(row["stem"].encode()).hexdigest())
        for row in group:
            if counts.get(class_name, 0) >= cap:
                break
            chosen.add(row["stem"])
            for name in row["names"]:
                counts[name] = counts.get(name, 0) + 1
    chosen_rows = [row for row in train if row["stem"] in chosen]
    chosen_rows.sort(key=lambda row: row["stem"])
    selected.extend(chosen_rows)
    log(f"ROD train class image counts { {key: counts[key] for key in sorted(counts)} }")
    jobs = []
    listed = []
    seen = set()
    for row in selected:
        key = (row["folder"], row["stem"])
        if key in seen:
            continue
        seen.add(key)
        label_dir = os.path.join(dest, row["folder"], "labels")
        image_dir = os.path.join(dest, row["folder"], "images")
        os.makedirs(label_dir, exist_ok=True)
        os.makedirs(image_dir, exist_ok=True)
        lines = []
        for box in json.loads(row["boxes"]):
            lines.append(
                f"{int(box['class_id'])} {float(box['x_center']):.6f} "
                f"{float(box['y_center']):.6f} {float(box['width']):.6f} {float(box['height']):.6f}"
            )
        label_path = os.path.join(label_dir, row["stem"] + ".txt")
        with open(label_path, "w") as handle:
            handle.write("\n".join(lines) + ("\n" if lines else ""))
        local = os.path.join(image_dir, row["stem"] + ".jpg")
        jobs.append((row["repo"], local))
        listed.append(f"{row['folder']}/images/{row['stem']}.jpg")
    with open(os.path.join(dest, "selected_images.txt"), "w") as handle:
        handle.write("\n".join(listed) + "\n")
    return jobs


def download_rod_images(jobs: list[tuple[str, str]], workers: int = 4) -> int:
    missing = [(repo, local) for repo, local in jobs if not (os.path.exists(local) and os.path.getsize(local) > 800)]
    log(f"ROD jpeg missing {len(missing)} of {len(jobs)}")
    if not missing:
        return 0

    def one(item: tuple[str, str]) -> str:
        repo, local = item
        url = f"https://huggingface.co/datasets/{ROD_REPO}/resolve/main/{repo}?download=true"
        tmp = local + ".part"
        delay = 2.0
        for _attempt in range(6):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "raahisense-train"})
                with urllib.request.urlopen(request, timeout=90) as response:
                    data = response.read()
                if len(data) < 800:
                    return "fail"
                with open(tmp, "wb") as handle:
                    handle.write(data)
                os.replace(tmp, local)
                return "ok"
            except urllib.error.HTTPError as exc:
                if os.path.exists(tmp):
                    os.remove(tmp)
                if exc.code == 429:
                    time.sleep(delay)
                    delay = min(delay * 2, 60)
                    continue
                return "fail"
            except Exception:
                if os.path.exists(tmp):
                    os.remove(tmp)
                time.sleep(delay)
                delay = min(delay * 2, 30)
        return "fail"

    ok = fail = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, item) for item in missing]
        for index, future in enumerate(as_completed(futures), start=1):
            if future.result() == "ok":
                ok += 1
            else:
                fail += 1
            if index % 200 == 0 or index == len(missing):
                log(f"ROD jpeg {index}/{len(missing)} ok {ok} fail {fail}")
    return fail


def select_rod_images(dest: str) -> list[str]:
    import glob

    import yaml

    data_yaml = os.path.join(dest, "data.yaml")
    if not os.path.exists(data_yaml):
        log("ROD data.yaml missing")
        return []
    with open(data_yaml) as handle:
        names = yaml.safe_load(handle)["names"]
    # Rare sidewalk classes first so the cap does not fill up with cars.
    priority = [
        "Manhole",
        "Stairs",
        "Electrical Pole",
        "Building",
        "Dog",
        "Traffic Cone",
        "Guard rail",
        "Teraffic Barrel",
        "Traffic Barrel",
        "Dustbin",
        "Bench",
        "Fire hydrant",
        "Plant Pot",
        "Electrical Box",
        "Chair",
        "Bicycle Rack",
        "Bike",
        "Motorcycle",
        "Bus",
        "Truck",
        "Person",
        "Car",
        "Tree",
        "Traffic sign",
    ]
    caps = {
        "Manhole": 700,
        "Stairs": 600,
        "Electrical Pole": 500,
        "Building": 800,
        "Dog": 350,
        "Traffic Cone": 350,
        "Guard rail": 350,
        "Teraffic Barrel": 250,
        "Traffic Barrel": 250,
        "Dustbin": 300,
        "Bench": 300,
        "Fire hydrant": 180,
        "Plant Pot": 200,
        "Electrical Box": 200,
        "Chair": 250,
        "Bicycle Rack": 160,
        "Bike": 400,
        "Motorcycle": 300,
        "Bus": 250,
        "Truck": 250,
        "Person": 700,
        "Car": 500,
        "Tree": 250,
        "Traffic sign": 250,
    }
    records = []
    for split, folder in (("train", "train"), ("val", "valid"), ("test", "test")):
        for label_path in glob.glob(f"{dest}/{folder}/labels/**/*.txt", recursive=True):
            classes = set()
            for line in open(label_path):
                parts = line.split()
                if len(parts) < 5:
                    continue
                index = int(float(parts[0]))
                if 0 <= index < len(names):
                    classes.add(names[index])
            if not classes:
                continue
            stem = os.path.splitext(os.path.basename(label_path))[0]
            rel = f"{folder}/images/{stem}.jpg"
            records.append((split, rel, classes))
    selected = []
    # Every official test image with a box stays out of training and is scored.
    selected.extend(rel for split, rel, _classes in records if split == "test")
    val = [item for item in records if item[0] == "val"]
    val.sort(key=lambda item: hashlib.md5(item[1].encode()).hexdigest())
    selected.extend(rel for _split, rel, _classes in val[:900])
    train = [item for item in records if item[0] == "train"]
    chosen = set()
    counts: dict[str, int] = {}
    for class_name in priority:
        cap = caps.get(class_name, 0)
        if cap <= 0:
            continue
        group = [item for item in train if class_name in item[2] and item[1] not in chosen]
        group.sort(key=lambda item: hashlib.md5(item[1].encode()).hexdigest())
        for _split, rel, classes in group:
            if counts.get(class_name, 0) >= cap:
                break
            chosen.add(rel)
            for name in classes:
                counts[name] = counts.get(name, 0) + 1
    selected.extend(sorted(chosen))
    log(f"ROD train class image counts { {k: counts[k] for k in sorted(counts)} }")
    # Unique, preserve order.
    seen = set()
    unique = []
    for rel in selected:
        if rel not in seen:
            seen.add(rel)
            unique.append(rel)
    list_path = os.path.join(dest, "selected_images.txt")
    with open(list_path, "w") as handle:
        handle.write("\n".join(unique) + "\n")
    return unique


def stage_oi() -> None:
    oi = os.path.join(RAW, "openimages")
    os.makedirs(oi, exist_ok=True)
    class_csv = os.path.join(oi, "class-descriptions.csv")
    if not os.path.exists(class_csv):
        download_file(CLASS_URL, class_csv, min_bytes=1000)
    name_to_id = {}
    with open(class_csv, newline="") as handle:
        for row in csv.reader(handle):
            if len(row) < 2 or row[0] == "LabelName":
                continue
            name_to_id[row[1]] = row[0]
    wanted = {}
    for display, (target, cap) in OI_KEEP.items():
        label_id = name_to_id.get(display)
        if label_id is None:
            log(f"Open Images has no class {display}")
            continue
        wanted[label_id] = (display, target, cap)
    log(f"Open Images labels {len(wanted)}")
    boxes_path = os.path.join(oi, "train-annotations-bbox.csv")
    download_file(BBOX_URL, boxes_path, min_bytes=1_000_000_000)
    ids_by_label: dict[str, set[str]] = {label: set() for label in wanted}
    log("scanning Open Images boxes for image ids")
    with open(boxes_path, newline="") as handle:
        for row in csv.DictReader(handle):
            label = row.get("LabelName")
            if label not in wanted:
                continue
            if row.get("IsGroupOf") == "1" or row.get("IsDepiction") == "1":
                continue
            ids_by_label[label].add(row["ImageID"])
    selected: set[str] = set()
    for label, (_display, _target, cap) in wanted.items():
        ids = sorted(ids_by_label[label], key=lambda image_id: hashlib.md5(image_id.encode()).hexdigest())
        chosen = ids[:cap]
        selected.update(chosen)
        log(f"OI {_display} images {len(ids)} cap {len(chosen)}")
    log(f"OI unique images {len(selected)}")
    kept_rows = os.path.join(oi, "selected_v2_boxes.csv")
    # Always rewrite. A previous file would hide newly added household labels.
    log("second scan, writing selected boxes")
    tmp_rows = kept_rows + ".tmp"
    with open(boxes_path, newline="") as handle, open(tmp_rows, "w", newline="") as out:
        reader = csv.DictReader(handle)
        writer = csv.DictWriter(
            out,
            fieldnames=["ImageID", "LabelName", "XMin", "XMax", "YMin", "YMax"],
        )
        writer.writeheader()
        kept = 0
        for row in reader:
            if row.get("ImageID") not in selected or row.get("LabelName") not in wanted:
                continue
            if row.get("IsGroupOf") == "1" or row.get("IsDepiction") == "1":
                continue
            writer.writerow(
                {
                    "ImageID": row["ImageID"],
                    "LabelName": row["LabelName"],
                    "XMin": row["XMin"],
                    "XMax": row["XMax"],
                    "YMin": row["YMin"],
                    "YMax": row["YMax"],
                }
            )
            kept += 1
    os.replace(tmp_rows, kept_rows)
    log(f"kept boxes {kept}")
    img_dir = os.path.join(oi, "images")
    pairs = [(IMAGE_URL.format(image_id), os.path.join(img_dir, f"{image_id}.jpg")) for image_id in sorted(selected)]
    download_images(pairs, workers=24)
    id_map = os.path.join(oi, "label_map.csv")
    with open(id_map, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["LabelName", "DisplayName", "app_class"])
        for label, (display, target, _cap) in wanted.items():
            writer.writerow([label, display, target])
    open(os.path.join(oi, ".v2_ok"), "w").write(f"{len(selected)}\n")
    log("OK open images")


def stage_coco() -> None:
    import ijson

    coco = os.path.join(RAW, "coco")
    os.makedirs(coco, exist_ok=True)
    zip_path = os.path.join(coco, "stuff_annotations_trainval2017.zip")
    download_file(STUFF_ZIP, zip_path, min_bytes=1_100_000_000)
    jsons = []
    with zipfile.ZipFile(zip_path) as archive:
        for name in archive.namelist():
            if name.endswith("stuff_train2017.json") or name.endswith("stuff_val2017.json"):
                target = os.path.join(coco, os.path.basename(name))
                if not os.path.exists(target):
                    log(f"extract {name}")
                    archive.extract(name, coco)
                    extracted = os.path.join(coco, name)
                    if extracted != target and os.path.exists(extracted):
                        os.replace(extracted, target)
                jsons.append(target)
    if not jsons:
        raise RuntimeError("COCO-Stuff json was not in the zip")
    pairs = []
    summary = os.path.join(coco, "wall_boxes.csv")
    with open(summary, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["split", "file_name", "app_class", "x", "y", "w", "h", "image_w", "image_h"])
        for json_path in jsons:
            subset = "train2017" if "train" in os.path.basename(json_path) else "val2017"
            log(f"scan {json_path}")
            with open(json_path, "rb") as handle_json:
                categories = {
                    item["id"]: item["name"]
                    for item in ijson.items(handle_json, "categories.item")
                }
            wall_ids = {cid for cid, name in categories.items() if name.startswith("wall")}
            fence_ids = {cid for cid, name in categories.items() if name == "fence" or name.startswith("fence")}
            log(f"wall categories {[categories[cid] for cid in sorted(wall_ids)]}")
            images = {}
            with open(json_path, "rb") as handle_json:
                for image in ijson.items(handle_json, "images.item"):
                    images[image["id"]] = image
            by_image: dict[int, list[tuple]] = {}
            with open(json_path, "rb") as handle_json:
                for ann in ijson.items(handle_json, "annotations.item"):
                    cid = ann.get("category_id")
                    if cid in wall_ids:
                        target = "wall"
                    elif cid in fence_ids:
                        target = "barrier"
                    else:
                        continue
                    if ann.get("iscrowd"):
                        continue
                    image = images.get(ann["image_id"])
                    if image is None:
                        continue
                    x, y, w, h = ann["bbox"]
                    width, height = image["width"], image["height"]
                    if not width or not height:
                        continue
                    by_image.setdefault(ann["image_id"], []).append(
                        (target, x / width, y / height, w / width, h / height, width, height, image["file_name"])
                    )
            wall_ids_sorted = sorted(
                (image_id for image_id, boxes in by_image.items() if any(box[0] == "wall" for box in boxes)),
                key=lambda image_id: hashlib.md5(str(image_id).encode()).hexdigest(),
            )
            fence_ids_sorted = sorted(
                (
                    image_id
                    for image_id, boxes in by_image.items()
                    if any(box[0] == "barrier" for box in boxes) and image_id not in set(wall_ids_sorted[:WALL_IMAGE_CAP])
                ),
                key=lambda image_id: hashlib.md5(str(image_id).encode()).hexdigest(),
            )
            keep = set(wall_ids_sorted[:WALL_IMAGE_CAP]) | set(fence_ids_sorted[:FENCE_IMAGE_CAP])
            log(f"{subset} stuff images with wall/fence {len(by_image)} kept {len(keep)}")
            for image_id in keep:
                image = images[image_id]
                for target, x, y, w, h, width, height, file_name in by_image[image_id]:
                    writer.writerow([subset, file_name, target, f"{x:.6f}", f"{y:.6f}", f"{w:.6f}", f"{h:.6f}", width, height])
                dest = os.path.join(coco, subset, image["file_name"])
                pairs.append((COCO_IMAGE.format(subset, image["file_name"]), dest))
    download_images(pairs, workers=16)
    open(os.path.join(coco, ".stuff_ok"), "w").write(f"{len(pairs)}\n")
    log("OK coco-stuff")


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    started = time.time()
    if which == "rod":
        stage_rod()
    if which in ("hf", "all"):
        stage_hf()
    if which in ("oi", "all"):
        stage_oi()
    if which in ("coco", "all"):
        stage_coco()
    log(f"fetch done {which} seconds {time.time() - started:.0f}")


if __name__ == "__main__":
    main()
