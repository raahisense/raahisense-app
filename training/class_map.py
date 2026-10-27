"""Class order for the walking detector.

Indices 0..28 match the shipped obstacles_v1 head. Later names are appended
so those indices do not move. `wall` is a large flat obstacle. `shoe`,
`bag`, `toy`, `bin`, `appliance`, and `cabinet` are household obstacles
on the floor or in the walking path. `unknown object` is not a trained
class. The depth logic still speaks it when nothing in this list explains
a close region.
"""
from __future__ import annotations

CLASSES: list[str] = [
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
    "wall",
    "shoe",
    "bag",
    "toy",
    "bin",
    "appliance",
    "cabinet",
]

CLASS_INDEX = {name: index for index, name in enumerate(CLASSES)}
# The shipped head ends at door. Wall and the household names are new.
BASELINE_CLASSES = CLASSES[:29]
# Spoken indoor obstacles. Reported on their own in the held-out metrics.
INDOOR_CLASSES = [
    "chair",
    "couch",
    "dining table",
    "bed",
    "toilet",
    "potted plant",
    "refrigerator",
    "tv",
    "door",
    "stairs",
    "wall",
    "shoe",
    "bag",
    "toy",
    "bin",
    "appliance",
    "cabinet",
]


def wall_box(width: float, height: float, center_y: float) -> bool:
    """Keep a wall or building box only when it is a large flat obstacle.

    Tiny distant facades and full-frame boxes are dropped. The box has to
    reach into the lower half of the frame, or be tall, so a skyline is
    not labeled as something in the walking path.
    """
    area = width * height
    if area < 0.10 or area > 0.92:
        return False
    if height < 0.32 or width < 0.18:
        return False
    bottom = center_y + height / 2
    if bottom < 0.45 and height < 0.70:
        return False
    return True
