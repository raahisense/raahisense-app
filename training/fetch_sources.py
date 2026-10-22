#!/usr/bin/env python3
"""Download obstacles_v1 raw sources into /workspace/datasets/raw.

Paths match convert.py. RDD2022 India is pulled out of the figshare zip
with HTTP ranges so the other countries are not downloaded. Open Images is
filtered to the four class ids convert.py uses.
"""
from __future__ import annotations

import csv
import io
import os
import sys
import time
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = "/workspace/datasets"
RAW = os.path.join(ROOT, "raw")
os.makedirs(RAW, exist_ok=True)

OI_LABELS = {
    "/m/01lynh": "Stairs",
    "/m/033rq4": "Street light",
    "/m/018p4k": "Cart",
    "/m/01xq0k1": "Cattle",
}
BBOX_URLS = [
    "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv",
    "https://storage.googleapis.com/openimages/v5/train-annotations-bbox.csv",
]
META_URLS = [
    "https://storage.googleapis.com/openimages/2018_04/train/train-images-boxable-with-rotation.csv",
]
IMAGE_URL = "https://open-images-dataset.s3.amazonaws.com/train/{}.jpg"
FIGSHARE = "https://ndownloader.figshare.com/files/38030910"
INDIA_DATA_OFF = 609341079
INDIA_SIZE = 526709145


def log(msg: str) -> None:
    print(msg, flush=True)


def hf_one(repo: str, name: str, patterns: list[str] | None) -> None:
    from huggingface_hub import snapshot_download

    dest = os.path.join(RAW, name)
    os.makedirs(dest, exist_ok=True)
    snapshot_download(
        repo,
        repo_type="dataset",
        local_dir=dest,
        allow_patterns=patterns,
        max_workers=8,
    )
    log(f"OK hf {name}")


def signed_figshare() -> str:
    req = urllib.request.Request(FIGSHARE, method="HEAD")
    with urllib.request.urlopen(req, timeout=60) as response:
        return response.geturl()


def download_india() -> None:
    dest = os.path.join(RAW, "rdd2022_india_India.zip")
    done = os.path.getsize(dest) if os.path.exists(dest) else 0
    if done > INDIA_SIZE:
        done = 0
    if done == INDIA_SIZE:
        log("India zip already complete")
    else:
        log(f"India zip resume at {done}/{INDIA_SIZE}")
        while done < INDIA_SIZE:
            url = signed_figshare()
            start = INDIA_DATA_OFF + done
            end = INDIA_DATA_OFF + INDIA_SIZE - 1
            req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=120) as response, open(dest, "ab" if done else "wb") as out:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    if done % (50 * 1024 * 1024) < 1024 * 1024:
                        log(f"India zip {done}/{INDIA_SIZE}")
            if done < INDIA_SIZE:
                log(f"India zip stalled at {done}, retry")
                time.sleep(2)
    out_dir = os.path.join(RAW, "rdd2022_india")
    marker = os.path.join(out_dir, ".extracted")
    if not os.path.exists(marker):
        log("extracting India zip")
        os.makedirs(out_dir, exist_ok=True)
        with zipfile.ZipFile(dest) as zf:
            zf.extractall(out_dir)
        # Zip roots vary: India/... or RDD2022/India/...
        open(marker, "w").write("ok\n")
    # Normalize to raw/rdd2022_india/India/train/...
    for dirpath, _, files in os.walk(out_dir):
        if os.path.basename(dirpath) == "xmls" and "India" in dirpath.replace("\\", "/"):
            log(f"found xmls {dirpath} files {len(files)}")
            break
    log("OK rdd india")


def fetch(url: str, timeout: int = 120):
    req = urllib.request.Request(url, headers={"User-Agent": "obstacle-guide-dataset/1.0"})
    return urllib.request.urlopen(req, timeout=timeout)


def download_openimages() -> None:
    oi = os.path.join(RAW, "openimages")
    img_dir = os.path.join(oi, "images")
    os.makedirs(img_dir, exist_ok=True)
    boxes_path = os.path.join(oi, "selected_boxes.csv")
    if not os.path.exists(boxes_path) or os.path.getsize(boxes_path) < 1000:
        last_error = None
        for url in BBOX_URLS:
            try:
                log(f"streaming boxes {url}")
                kept = 0
                with fetch(url, timeout=180) as response, open(boxes_path, "w", newline="") as handle:
                    text = io.TextIOWrapper(response, encoding="utf-8", newline="")
                    reader = csv.DictReader(text)
                    writer = csv.DictWriter(
                        handle,
                        fieldnames=["ImageID", "LabelName", "XMin", "XMax", "YMin", "YMax"],
                    )
                    writer.writeheader()
                    for row in reader:
                        if row.get("LabelName") not in OI_LABELS:
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
                log(f"kept {kept} boxes from {url}")
                if kept > 0:
                    break
            except Exception as exc:  # noqa: BLE001
                last_error = exc
                log(f"bbox fail {url} {exc}")
                if os.path.exists(boxes_path):
                    os.remove(boxes_path)
        if not os.path.exists(boxes_path):
            raise RuntimeError(f"open images boxes failed: {last_error}")
    ids = []
    with open(boxes_path, newline="") as handle:
        for row in csv.DictReader(handle):
            ids.append(row["ImageID"])
    unique = sorted(set(ids))
    log(f"open images ids {len(unique)}")
    missing = [image_id for image_id in unique if not os.path.exists(os.path.join(img_dir, f"{image_id}.jpg"))]

    def one(image_id: str) -> str:
        path = os.path.join(img_dir, f"{image_id}.jpg")
        tmp = path + ".part"
        try:
            with fetch(IMAGE_URL.format(image_id), timeout=60) as response, open(tmp, "wb") as out:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp, path)
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
            if index % 250 == 0:
                log(f"open images {index}/{len(missing)} ok {ok} fail {fail}")
    log(f"OK open images downloaded {ok} failed {fail} already {len(unique) - len(missing)}")
    attr_path = os.path.join(oi, "image_attributions.csv")
    if not os.path.exists(attr_path):
        wanted = set(unique)
        for url in META_URLS:
            try:
                log(f"streaming attributions {url}")
                found = 0
                with fetch(url, timeout=180) as response, open(attr_path, "w", newline="") as handle:
                    text = io.TextIOWrapper(response, encoding="utf-8", newline="")
                    reader = csv.DictReader(text)
                    fields = reader.fieldnames or []
                    writer = csv.DictWriter(handle, fieldnames=fields)
                    writer.writeheader()
                    for row in reader:
                        if row.get("ImageID") in wanted:
                            writer.writerow(row)
                            found += 1
                log(f"attributions {found}")
                break
            except Exception as exc:  # noqa: BLE001
                log(f"attr fail {url} {exc}")
                if os.path.exists(attr_path):
                    os.remove(attr_path)


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("hf-rod", "all"):
        hf_one("Abtinzandi/Obstacle-Detection-Dataset-YOLO", "rod", None)
    if which in ("hf-rest", "all"):
        jobs = [
            ("keremberke/pothole-segmentation", "pothole_keremberke", None),
            ("Ryukijano/Pothole-detection-Yolov8", "pothole_ryukijano", None),
            ("delima87/manhole_covers_dataset", "manhole_delima87", None),
            ("Zhonghua/Curbs_dataset", "curbs_zhonghua", None),
            (
                "shravya11/tuk-tuk-detection",
                "tuktuk_shravya11",
                ["train/*", "valid/*", "test/*", "rickshaw/*", "datacluster_auto/*", "README*"],
            ),
            ("Dderedor/obstacles_on_the_road", "assisteye_obstacles", None),
        ]
        for repo, name, patterns in jobs:
            try:
                hf_one(repo, name, patterns)
            except Exception as exc:  # noqa: BLE001
                log(f"FAIL {name} {exc}")
    if which in ("rdd", "all"):
        download_india()
    if which in ("oi", "all"):
        download_openimages()


if __name__ == "__main__":
    main()
