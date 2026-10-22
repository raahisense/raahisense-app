#!/usr/bin/env python3
"""Wait for indoor picture downloads, rebuild the set, and train."""
from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path

COCO_LOG = Path("/tmp/fetch-coco.log")
DOOR_LOG = Path("/tmp/fetch-door.log")
DEADLINE = time.time() + 3 * 60 * 60


def ready(path: Path, token: str) -> bool:
    if not path.exists():
        return False
    return token in path.read_text(errors="replace")


def main() -> None:
    print("waiting for indoor downloads", flush=True)
    while time.time() < DEADLINE:
        coco_ok = ready(COCO_LOG, "OK coco")
        door_ok = ready(DOOR_LOG, "OK doors")
        print(f"coco={coco_ok} door={door_ok}", flush=True)
        if coco_ok and door_ok:
            break
        time.sleep(30)
    else:
        print("deadline, converting with what is on disk", flush=True)
    print("convert", flush=True)
    subprocess.check_call(["python3", "-u", "/workspace/training/convert_obstacles.py"])
    print("train", flush=True)
    subprocess.call(["/workspace/gradlew", "--stop"], cwd="/workspace")
    env = os.environ.copy()
    env["OBSTACLE_EPOCHS"] = "4"
    env["OBSTACLE_BATCH"] = "8"
    subprocess.check_call(["python3", "-u", "/workspace/training/train_obstacles.py"], env=env)
    print("PIPELINE_OK", flush=True)


if __name__ == "__main__":
    main()
