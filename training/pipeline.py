#!/usr/bin/env python3
"""Wait until the source downloads finish, then convert and train."""
from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path

ROD_LOG = Path("/tmp/fetch-rod.log")
REST_LOG = Path("/tmp/fetch-rest2.log")
OI_LOG = Path("/tmp/fetch-oi2.log")
TUK_LOG = Path("/tmp/fetch-tuk.log")
DEADLINE = time.time() + 3 * 60 * 60


def tail_has(path: Path, text: str) -> bool:
    if not path.exists():
        return False
    data = path.read_text(errors="replace")
    return text in data


def rod_done() -> bool:
    return tail_has(ROD_LOG, "OK rod")


def rest_done() -> bool:
    if not REST_LOG.exists():
        return False
    text = REST_LOG.read_text(errors="replace")
    return (
        "OK /workspace/datasets/raw/assisteye_obstacles" in text
        or "FAIL /workspace/datasets/raw/assisteye_obstacles" in text
    )


def oi_done() -> bool:
    return tail_has(OI_LOG, "OK open images")


def tuk_done() -> bool:
    if not TUK_LOG.exists():
        return False
    text = TUK_LOG.read_text(errors="replace")
    return "OK tuk" in text or "FAIL" in text


def main() -> None:
    print("waiting for downloads", flush=True)
    rod_ready_at = None
    while time.time() < DEADLINE:
        if rod_done() and rod_ready_at is None:
            rod_ready_at = time.time()
        ready = rod_done() and rest_done() and oi_done() and tuk_done()
        print(
            f"rod={rod_done()} rest={rest_done()} oi={oi_done()} tuk={tuk_done()}",
            flush=True,
        )
        if ready:
            break
        time.sleep(60)
    else:
        print("deadline reached, converting with whatever is on disk", flush=True)
    print("convert", flush=True)
    subprocess.check_call(["python3", "-u", "/workspace/training/convert_obstacles.py"])
    print("train", flush=True)
    subprocess.call(["/workspace/gradlew", "--stop"], cwd="/workspace")
    env = os.environ.copy()
    env.setdefault("OBSTACLE_EPOCHS", "4")
    env.setdefault("OBSTACLE_BATCH", "8")
    subprocess.check_call(["python3", "-u", "/workspace/training/train_obstacles.py"], env=env)
    print("PIPELINE_OK", flush=True)


if __name__ == "__main__":
    main()
