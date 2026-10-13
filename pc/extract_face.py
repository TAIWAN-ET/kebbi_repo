"""影片 → MediaPipe Face Landmarker（52 個 blendshape 表情分數）→ face_blendshapes.csv。

表情辨識的「量測」這一半：每個取樣點輸出臉部 blendshape 分數（0~1，例如嘴角上揚、皺眉、張嘴）。
「判斷是什麼表情、何時換成 Kebbi 的哪張臉」在 Java 端做
（`FaceExpressionAnalyzer` → `FaceScriptBuilder`），和姿態一樣 PC / App 共用同一份程式碼。

舞蹈影片裡的臉通常很小，整張畫面丟給模型常常抓不到。所以有姿態 CSV 時，
先用姿態的臉部點（鼻子、眼睛、耳朵、嘴角）框出頭部、放大後再偵測；
框不到或偵測失敗才退回整張畫面。

取樣方式與 extract_pose.py 相同（每 interval 毫秒一幀、同樣的時間戳），
兩邊的 time_ms 才對得起來。

輸出 CSV：

    time_ms,detected,source,<52 個 blendshape 名稱...>

- detected：1＝有偵測到臉；0＝沒有（其餘欄位為空）
- source：head＝用姿態框出的頭部偵測；full＝整張畫面；空＝沒偵測到

用法：
    python extract_face.py video.mp4 --out-dir out/video                 # 有 pose_landmarks.csv 會自動用來框頭
    python extract_face.py video.mp4 --out-dir out/video --pose-csv -    # 不用姿態，整張畫面

第一次執行會自動下載 face_landmarker.task（約 3.7 MB）到 pc/models/。
"""

from __future__ import annotations

import argparse
import csv
import sys
import urllib.request
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

PC_DIR = Path(__file__).resolve().parent
DEFAULT_FACE_MODEL = PC_DIR / "models" / "face_landmarker.task"
FACE_MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/face_landmarker/"
                  "face_landmarker/float16/1/face_landmarker.task")
FACE_CSV_NAME = "face_blendshapes.csv"

# MediaPipe Pose 33 點中的臉部點：0 鼻、1~6 眼、7~8 耳、9~10 嘴角
POSE_FACE_POINTS = range(0, 11)
POSE_EARS_EYES = (2, 5, 7, 8)
POSE_VIS_THRESHOLD = 0.3
# 頭部框邊長＝臉部點範圍 × 這個倍數（要含額頭和下巴）
HEAD_BOX_SCALE = 2.4
# 框出的頭部放大到至少這個邊長（像素）再偵測；Face Landmarker 輸入是 192~256
HEAD_MIN_SIZE_PX = 256


def ensure_model(model: Path) -> Path:
    if model.is_file():
        return model
    if model != DEFAULT_FACE_MODEL:
        raise FileNotFoundError(f"face model not found: {model}")
    model.parent.mkdir(parents=True, exist_ok=True)
    print(f"      downloading {FACE_MODEL_URL}", flush=True)
    tmp = model.with_suffix(".part")
    urllib.request.urlretrieve(FACE_MODEL_URL, tmp)
    tmp.replace(model)
    return model


def load_pose_face_points(pose_csv: Path) -> dict[int, list[tuple[int, float, float, float]]]:
    """讀 pose_landmarks.csv 的臉部點：{time_ms: [(index, x, y, visibility), ...]}（normalized）。"""
    points: dict[int, list[tuple[int, float, float, float]]] = {}
    with open(pose_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("pose_index", "0") != "0":
                continue
            index = int(row["landmark_index"])
            if index not in POSE_FACE_POINTS:
                continue
            points.setdefault(int(row["time_ms"]), []).append(
                (index, float(row["x"]), float(row["y"]), float(row["visibility"])))
    return points


def head_box(points: list[tuple[int, float, float, float]], width: int, height: int
             ) -> tuple[int, int, int, int] | None:
    """由姿態臉部點算出正方形頭部框 (x0, y0, x1, y1)，像素；點太少回傳 None。"""
    visible = [(i, x * width, y * height) for i, x, y, v in points if v >= POSE_VIS_THRESHOLD]
    # 至少要有兩個眼 / 耳點才能估臉的大小
    if sum(1 for i, _, _ in visible if i in POSE_EARS_EYES) < 2:
        return None
    xs = [x for _, x, _ in visible]
    ys = [y for _, _, y in visible]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    side = max(max(xs) - min(xs), max(ys) - min(ys), 8.0) * HEAD_BOX_SCALE
    x0, y0 = int(max(0, cx - side / 2)), int(max(0, cy - side / 2))
    x1, y1 = int(min(width, cx + side / 2)), int(min(height, cy + side / 2))
    if x1 - x0 < 8 or y1 - y0 < 8:
        return None
    return x0, y0, x1, y1


def _detect(landmarker, rgb) -> dict[str, float] | None:
    result = landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
    if not result.face_blendshapes:
        return None
    return {c.category_name: float(c.score) for c in result.face_blendshapes[0]}


def extract(video: Path, out_dir: Path, interval_ms: int = 200, max_seconds: float | None = None,
            model: Path = DEFAULT_FACE_MODEL, pose_csv: Path | None = None) -> tuple[Path, dict]:
    """分析影片並寫出 face_blendshapes.csv，回傳 (csv_path, stats)。"""
    if not video.is_file():
        raise FileNotFoundError(f"video not found: {video}")
    model = ensure_model(model)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / FACE_CSV_NAME
    pose_points = load_pose_face_points(pose_csv) if pose_csv and pose_csv.is_file() else {}

    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 30.0
    frame_ms = 1000.0 / fps
    width, height = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    limit_ms = None if max_seconds is None else int(max_seconds * 1000)

    # IMAGE 模式：頭部框每幀位置不同，VIDEO 模式的追蹤會被框的跳動干擾
    options = vision.FaceLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=0.4,
        min_face_presence_confidence=0.4,
        output_face_blendshapes=True,
    )

    stats = {"fps": fps, "sampled": 0, "detected": 0, "head": 0, "full": 0}
    names: list[str] | None = None
    rows: list[tuple[int, str, dict[str, float] | None]] = []
    next_sample_ms = 0
    frame_index = 0

    with vision.FaceLandmarker.create_from_options(options) as landmarker:
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            frame_time_ms = frame_index * frame_ms
            frame_index += 1
            if limit_ms is not None and next_sample_ms > limit_ms:
                break
            # 與 extract_pose.py 相同的取樣規則，time_ms 才對得上姿態 CSV
            if frame_time_ms + frame_ms / 2 < next_sample_ms:
                continue
            time_ms = next_sample_ms
            next_sample_ms += interval_ms
            stats["sampled"] += 1

            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            scores, source = None, ""

            # Step1：有姿態就先框頭放大，小臉才抓得到
            box = head_box(pose_points[time_ms], width, height) if time_ms in pose_points else None
            if box is not None:
                x0, y0, x1, y1 = box
                crop = rgb[y0:y1, x0:x1]
                side = max(crop.shape[0], crop.shape[1])
                if side < HEAD_MIN_SIZE_PX:
                    f = HEAD_MIN_SIZE_PX / side
                    crop = cv2.resize(crop, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
                scores = _detect(landmarker, crop.copy())
                source = "head" if scores else ""

            # Step2：框不到或沒偵測到 → 整張畫面
            if scores is None:
                scores = _detect(landmarker, rgb)
                source = "full" if scores else ""

            if scores:
                stats["detected"] += 1
                stats[source] += 1
                if names is None:
                    names = list(scores.keys())
            rows.append((time_ms, source, scores))

            if stats["sampled"] % 25 == 0:
                print(f"  {time_ms / 1000:6.1f}s  sampled={stats['sampled']} face={stats['detected']}",
                      flush=True)
    cap.release()

    names = names or []
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["time_ms", "detected", "source"] + names)
        for time_ms, source, scores in rows:
            if scores:
                writer.writerow([time_ms, 1, source] + [f"{scores.get(n, 0.0):.4f}" for n in names])
            else:
                writer.writerow([time_ms, 0, ""] + [""] * len(names))
    return out_path, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Video -> MediaPipe face blendshape CSV")
    parser.add_argument("video", type=Path)
    parser.add_argument("--out-dir", type=Path, default=None,
                        help="output folder (default: pc/out/<video name>)")
    parser.add_argument("--interval", type=int, default=200, help="sampling interval in ms (default 200)")
    parser.add_argument("--max-seconds", type=float, default=None, help="only analyze the first N seconds")
    parser.add_argument("--model", type=Path, default=DEFAULT_FACE_MODEL)
    parser.add_argument("--pose-csv", default=None,
                        help="pose_landmarks.csv used to crop the head (default: <out-dir>/pose_landmarks.csv; "
                             "'-' = full frame only)")
    args = parser.parse_args(argv)

    out_dir = args.out_dir or PC_DIR / "out" / args.video.stem
    pose_csv = None if args.pose_csv == "-" else Path(args.pose_csv or out_dir / "pose_landmarks.csv")
    out_path, stats = extract(args.video, out_dir, args.interval, args.max_seconds, args.model, pose_csv)
    print(f"fps={stats['fps']:.2f} sampled={stats['sampled']} face={stats['detected']} "
          f"(head crop {stats['head']}, full frame {stats['full']})")
    print(f"wrote {out_path}")
    return 0 if stats["detected"] > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
