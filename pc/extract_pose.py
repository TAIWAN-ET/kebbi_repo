"""影片 → MediaPipe Pose Landmarker → landmark CSV（電腦端）。

輸出兩個 CSV，格式和 App 的 dance_pose_landmarks.csv 完全相同，
可以直接用 App 的 PoseIO.readCsv() 讀：

    time_ms,pose_index,landmark_index,x,y,z,visibility,presence

- pose_landmarks.csv        normalized 影像座標（0~1），判斷「誰比誰高」用
- pose_world_landmarks.csv  world 公制座標（公尺，原點在髖中心），算角度用

取樣方式與 VideoAnalyzer 一致：每 interval 毫秒取一幀、VIDEO 模式、
只追蹤一個人、三個信心門檻都是 0.5、用同一個 pose_landmarker_lite.task 模型。

用法：
    python extract_pose.py video.mp4 --out-dir out/video
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

PC_DIR = Path(__file__).resolve().parent
DEFAULT_MODEL = PC_DIR.parent / "app" / "src" / "main" / "assets" / "pose_landmarker_lite.task"
CSV_HEADER = ["time_ms", "pose_index", "landmark_index", "x", "y", "z", "visibility", "presence"]
LANDMARK_COUNT = 33


def _conf(value: float | None) -> float:
    # 和 VideoAnalyzer.toPoseFrame 一樣：模型沒回報可信度時當成 1.0，
    # 否則 PoseAnalyzer 的可信度檢查會把每一幀都判成不可信
    return 1.0 if value is None else float(value)


def _row(time_ms: int, index: int, x: float, y: float, z: float, vis: float, pres: float) -> list[str]:
    return [str(time_ms), "0", str(index), f"{x:f}", f"{y:f}", f"{z:f}", f"{vis:f}", f"{pres:f}"]


def extract(video: Path, out_dir: Path, interval_ms: int = 200, max_seconds: float | None = None,
            model: Path = DEFAULT_MODEL) -> tuple[Path, Path, dict]:
    """分析影片並寫出兩個 CSV，回傳 (landmarks_csv, world_csv, stats)。"""
    if not video.is_file():
        raise FileNotFoundError(f"video not found: {video}")
    if not model.is_file():
        raise FileNotFoundError(f"model not found: {model}")
    out_dir.mkdir(parents=True, exist_ok=True)
    norm_path = out_dir / "pose_landmarks.csv"
    world_path = out_dir / "pose_world_landmarks.csv"

    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0
    frame_ms = 1000.0 / fps
    limit_ms = None if max_seconds is None else int(max_seconds * 1000)

    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    sampled = 0
    detected = 0
    next_sample_ms = 0
    frame_index = 0

    with vision.PoseLandmarker.create_from_options(options) as landmarker, \
            open(norm_path, "w", newline="", encoding="utf-8") as norm_file, \
            open(world_path, "w", newline="", encoding="utf-8") as world_file:
        norm_writer = csv.writer(norm_file, lineterminator="\n")
        world_writer = csv.writer(world_file, lineterminator="\n")
        norm_writer.writerow(CSV_HEADER)
        world_writer.writerow(CSV_HEADER)

        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            frame_time_ms = frame_index * frame_ms
            frame_index += 1

            if limit_ms is not None and next_sample_ms > limit_ms:
                break
            # 順序讀幀，第一個「時間到了或過了取樣點半幀以內」的幀當作這個取樣點
            # （等同 Android 的 getFrameAtTime(OPTION_CLOSEST)，但不用反覆 seek）
            if frame_time_ms + frame_ms / 2 < next_sample_ms:
                continue

            time_ms = next_sample_ms
            next_sample_ms += interval_ms
            sampled += 1

            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            # VIDEO 模式要求時間戳單調遞增；time_ms 每次 +interval，一定遞增
            result = landmarker.detect_for_video(image, time_ms)
            if not result.pose_landmarks:
                continue
            detected += 1

            norm = result.pose_landmarks[0]
            world = result.pose_world_landmarks[0] if result.pose_world_landmarks else None
            for i in range(min(LANDMARK_COUNT, len(norm))):
                lm = norm[i]
                vis, pres = _conf(lm.visibility), _conf(lm.presence)
                norm_writer.writerow(_row(time_ms, i, lm.x, lm.y, lm.z, vis, pres))
                if world is not None and i < len(world):
                    w = world[i]
                    # world 點沿用同一個點的 normalized 可信度（與 VideoAnalyzer 相同）
                    world_writer.writerow(_row(time_ms, i, w.x, w.y, w.z, vis, pres))

            if sampled % 25 == 0:
                print(f"  {time_ms / 1000:6.1f}s  sampled={sampled} detected={detected}", flush=True)

    cap.release()
    stats = {
        "fps": fps,
        "sampled": sampled,
        "detected": detected,
        "duration_ms": frame_index * frame_ms,
    }
    return norm_path, world_path, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Video -> MediaPipe pose landmark CSV")
    parser.add_argument("video", type=Path)
    parser.add_argument("--out-dir", type=Path, default=None,
                        help="output folder (default: pc/out/<video name>)")
    parser.add_argument("--interval", type=int, default=200, help="sampling interval in ms (default 200)")
    parser.add_argument("--max-seconds", type=float, default=None, help="only analyze the first N seconds")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    args = parser.parse_args(argv)

    out_dir = args.out_dir or PC_DIR / "out" / args.video.stem
    norm_path, world_path, stats = extract(args.video, out_dir, args.interval, args.max_seconds, args.model)
    print(f"fps={stats['fps']:.2f} sampled={stats['sampled']} detected={stats['detected']}")
    print(f"wrote {norm_path}")
    print(f"wrote {world_path}")
    return 0 if stats["detected"] > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
