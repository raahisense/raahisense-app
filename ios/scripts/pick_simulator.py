#!/usr/bin/env python3
"""Print an xcodebuild destination for an available iPhone simulator."""
import json
import subprocess
import sys

raw = subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"])
devices = json.loads(raw).get("devices", {})
sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"]).decode().strip()
found = []
for runtime, items in devices.items():
    if "iOS" not in runtime:
        continue
    version = runtime.rsplit(".", 1)[-1].replace("-", ".")
    for device in items:
        name = device.get("name", "")
        if not device.get("isAvailable", False):
            continue
        if not name.startswith("iPhone"):
            continue
        found.append((version, name, device["udid"]))

if not found:
    print("No iPhone simulator is installed.", file=sys.stderr)
    sys.exit(1)

def version_key(version: str):
    parts = []
    for piece in version.split("."):
        try:
            parts.append(int(piece))
        except ValueError:
            parts.append(0)
    return parts

sdk_key = version_key(sdk)[:2]
compatible = [item for item in found if version_key(item[0])[:2] <= sdk_key]
if compatible:
    found = compatible

found.sort(key=lambda item: (0 if "iPhone 16" in item[1] else 1, [-part for part in version_key(item[0])]))
_version, name, udid = found[0]
print(f"platform=iOS Simulator,id={udid},name={name}")
