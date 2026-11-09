"""v0_face: face detection + optional identity + emotion.

Input : ctx.video
Output: ctx.out_dir/annotated.mp4 + returned result dict (runner writes latest.json)

Drop-in contract: define run(ctx) and return a dict. Read settings from
ctx.params (manifest.json defaults + --param overrides).
"""
from __future__ import annotations

from collections import Counter

import cv2

from core.face import FaceEngine


def _as_bool(value, default: bool = True) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() not in {"0", "false", "no", "off"}


def run(ctx):
    params = ctx.params
    sample_hz = max(0.5, float(params.get("sampleHz", 5.0)))
    min_face_px = int(params.get("minFacePx", 40))
    det_threshold = float(params.get("detThreshold", 0.7))
    max_seconds = float(params.get("maxSeconds", 0) or 0)
    write_video = _as_bool(params.get("annotated", "true"))
    gallery = params.get("gallery")

    engine = FaceEngine(
        ctx.models,
        det_threshold=det_threshold,
        min_face_px=min_face_px,
        gallery_dir=gallery,
        log=ctx.log,
    )

    cap = cv2.VideoCapture(str(ctx.video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {ctx.video}")

    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    step = max(1, int(round(fps / sample_hz)))

    writer = None
    if write_video:
        out_video = ctx.out_dir / "annotated.mp4"
        # Sampled-rate preview (e.g. 5fps): much faster than re-encoding every frame.
        out_fps = fps / step
        writer = cv2.VideoWriter(
            str(out_video), cv2.VideoWriter_fourcc(*"mp4v"), out_fps, (width, height)
        )
        if not writer.isOpened():
            raise RuntimeError(f"cannot open VideoWriter: {out_video}")

    frames = []
    last_faces = []
    frame_index = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        if frame_index % step == 0:
            last_faces = engine.analyze(frame)
            frames.append(
                {
                    "frame": frame_index,
                    "t": round(frame_index / fps, 3),
                    "faces": [f.to_dict(width, height) for f in last_faces],
                }
            )
            if max_seconds > 0 and frame_index / fps >= max_seconds:
                break

        if writer is not None and frame_index % step == 0:
            engine.draw(frame, last_faces)
            writer.write(frame)

        frame_index += 1

    cap.release()
    if writer is not None:
        writer.release()

    names = Counter()
    emotions = Counter()
    total_faces = 0
    for entry in frames:
        for face in entry["faces"]:
            names[face["name"]] += 1
            emotions[face["emotion"]] += 1
            total_faces += 1

    return {
        "video": {
            "path": str(ctx.video),
            "width": width,
            "height": height,
            "fps": round(fps, 3),
            "frames": total_frames,
            "durationSec": round(total_frames / fps, 3) if total_frames else None,
        },
        "params": {
            "sampleHz": sample_hz,
            "minFacePx": min_face_px,
            "detThreshold": det_threshold,
            "maxSeconds": max_seconds,
            "annotated": write_video,
            "gallery": gallery,
        },
        "frames": frames,
        "summary": {
            "sampledFrames": len(frames),
            "faceFrames": sum(1 for entry in frames if entry["faces"]),
            "totalFaces": total_faces,
            "uniqueNames": sorted(names),
            "nameCounts": dict(names),
            "emotionCounts": dict(emotions),
        },
        "artifacts": {"annotated": "annotated.mp4" if write_video else None},
    }
