"""Deterministic low-light view used for scoring and for training batches.

Night datasets with a research-only or non-commercial license are not added.
This darkening is the held-out low-light test: dim, gamma, noise, motion blur.
"""
from __future__ import annotations

import numpy as np


def darken(image: np.ndarray, seed: int) -> np.ndarray:
    """Return a uint8 BGR or RGB image. The seed makes the view repeatable."""
    rng = np.random.default_rng(seed)
    img = image.astype(np.float32) / 255.0
    img = np.clip(img * 0.35, 0.0, 1.0)
    img = np.power(img, 1.8)
    img = img + rng.normal(0.0, 0.02, img.shape).astype(np.float32)
    img = np.clip(img, 0.0, 1.0)
    kernel = np.zeros((1, 7), np.float32)
    kernel[0, :] = 1.0 / 7.0
    try:
        import cv2
    except ImportError:
        cv2 = None
    if cv2 is not None:
        img = cv2.filter2D(img, -1, kernel)
    else:
        pad = np.pad(img, ((0, 0), (3, 3), (0, 0)), mode="edge")
        acc = np.zeros_like(img)
        for offset, weight in enumerate(kernel[0]):
            acc += pad[:, offset : offset + img.shape[1]] * weight
        img = acc
    return np.clip(img * 255.0, 0, 255).astype(np.uint8)


def install_batch_darkening(probability: float = 0.40) -> None:
    """Darken part of each training batch after Ultralytics scales images to 0..1."""
    import torch
    from ultralytics.models.yolo.detect.train import DetectionTrainer

    if getattr(DetectionTrainer, "_raahi_dark", False):
        return
    original = DetectionTrainer.preprocess_batch

    def preprocess_batch(self, batch):
        batch = original(self, batch)
        imgs = batch.get("img")
        if imgs is None or not torch.is_tensor(imgs):
            return batch
        if imgs.dtype not in (torch.float16, torch.float32):
            return batch
        count = imgs.shape[0]
        pick = torch.rand(count, device=imgs.device) < probability
        if not bool(pick.any()):
            return batch
        chosen = imgs[pick]
        scale = 0.22 + 0.38 * torch.rand(chosen.shape[0], 1, 1, 1, device=imgs.device)
        gamma = 1.35 + 1.15 * torch.rand(chosen.shape[0], 1, 1, 1, device=imgs.device)
        darkened = torch.clamp(chosen * scale, 0, 1).pow(gamma)
        noise = torch.randn_like(darkened) * 0.025
        darkened = torch.clamp(darkened + noise, 0, 1)
        kernel = torch.tensor([0.1, 0.2, 0.4, 0.2, 0.1], device=imgs.device, dtype=darkened.dtype)
        weight = kernel.view(1, 1, 1, 5).repeat(darkened.shape[1], 1, 1, 1)
        blurred = torch.nn.functional.conv2d(darkened, weight, padding=(0, 2), groups=darkened.shape[1])
        imgs[pick] = blurred
        return batch

    DetectionTrainer.preprocess_batch = preprocess_batch
    DetectionTrainer._raahi_dark = True
