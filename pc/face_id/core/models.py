"""Model registry + auto-download.

Only the environment needs to be prepared once. If a model is missing, the first
run downloads it into pc/models/.
"""
from __future__ import annotations

import urllib.request
from pathlib import Path
from typing import Callable

MODELS = {
    "yunet": {
        "file": "yunet.onnx",
        "url": "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        "min_bytes": 100_000,
        "note": "YuNet face detection (OpenCV Zoo)",
    },
    "sface": {
        "file": "sface.onnx",
        "url": "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx",
        "min_bytes": 30_000_000,
        "note": "SFace face recognition (OpenCV Zoo)",
    },
    "ferplus": {
        "file": "ferplus.onnx",
        "url": "https://github.com/onnx/models/raw/main/validated/vision/body_analysis/emotion_ferplus/model/emotion-ferplus-8.onnx",
        "min_bytes": 30_000_000,
        "note": "FER+ emotion (ONNX model zoo)",
    },
}


def _download(url: str, dst: Path, log: Callable[[str], None]) -> None:
    tmp = dst.with_suffix(dst.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "kebbi-pc/1.0"})
    log(f"[models] download {dst.name} ...")
    with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as fh:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            fh.write(chunk)
            done += len(chunk)
            if total and done % (1024 * 1024 * 8) < 1024 * 256:
                log(f"[models]   {dst.name}: {done / 1048576:.1f}/{total / 1048576:.1f} MB")
    tmp.replace(dst)
    log(f"[models] saved {dst}")


def ensure_models(models_dir: Path, log: Callable[[str], None] = print) -> dict[str, Path]:
    """Return {key: path}; download only what is missing or too small."""
    models_dir.mkdir(parents=True, exist_ok=True)
    out: dict[str, Path] = {}
    for key, meta in MODELS.items():
        path = models_dir / meta["file"]
        if not path.exists() or path.stat().st_size < meta["min_bytes"]:
            _download(meta["url"], path, log)
        out[key] = path
    return out
