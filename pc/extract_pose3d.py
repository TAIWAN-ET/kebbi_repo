"""影片 → rtmlib Wholebody3d（RTMW3D，有深度）→ Kebbi 管線用的 landmark CSV。

和 extract_pose.py（MediaPipe）輸出相同格式，可直接接 make_dance.py --skip-extract：

    python extract_pose3d.py ../video_push/xxx.mp4 --out-dir out/rtmw3d --interval 200
    .venv/Scripts/python make_dance.py ../video_push/xxx.mp4 --skip-extract --out-dir out/rtmw3d

輸出：
- raw3d.npz                 每個取樣點的原始輸出（之後可用 --from-npz 換參數重轉，不必重跑模型）
- pose_landmarks.csv        normalized 影像座標（0~1，來自 keypoints_2d），判斷「誰比誰高」用
- pose_world_landmarks.csv  3D 座標（單位＝影像高度，原點在髖中心，x 右、y 下、z 遠離鏡頭），算角度與手臂方向用

座標說明：
- RTMW3D 的 x/y 是裁切框像素，z 是相對深度。x/y 改用 keypoints_2d（影像像素，等比例），
  z 換算成同單位：z_img = (z_simcc - 半個輸入寬) × (框寬 / 輸入寬) × zscale。
- zscale 預設 2.0（由模型單位換算，見 --zscale 說明）；--zscale 0 改用骨長穩定度估計，但實測很平坦，不可靠。
- 只用 COCO 前 17 點（與 COCO-WholeBody 前 17 點相同），轉成 MediaPipe 33 點排列。

需要：pip install rtmlib onnxruntime opencv-python numpy（第一次執行會自動下載模型，RTMW3D-x 約數百 MB）。
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import numpy as np

CSV_HEADER = ["time_ms", "pose_index", "landmark_index", "x", "y", "z", "visibility", "presence"]
COCO_TO_MP = {0: 0, 1: 2, 2: 5, 3: 7, 4: 8, 5: 11, 6: 12, 7: 13, 8: 14, 9: 15, 10: 16,
              11: 23, 12: 24, 13: 25, 14: 26, 15: 27, 16: 28}
# rtmlib 沒有的 MediaPipe 點：借最近的點，可信度設 0（表示沒有量測）
MP_FILL_FROM = {1: 2, 3: 2, 4: 5, 6: 5, 9: 0, 10: 0, 17: 15, 19: 15, 21: 15, 18: 16, 20: 16,
                22: 16, 29: 27, 31: 27, 30: 28, 32: 28}
Z_CENTER = 192  # rtmlib: z 以輸入高度的一半為零點（model_input_size[-1] / 2）


def extract(video: Path, out_dir: Path, interval_ms: int, max_seconds: float | None) -> Path:
    import cv2
    from rtmlib import Wholebody3d

    out_dir.mkdir(parents=True, exist_ok=True)
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise SystemExit(f"cannot open {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frame_ms = 1000.0 / fps
    width, height = int(cap.get(3)), int(cap.get(4))

    model = Wholebody3d(mode="balanced", backend="onnxruntime", device="cpu")
    times, kp3, kp2, sc, sim = [], [], [], [], []
    next_ms, idx = 0, 0
    while True:
        ok, bgr = cap.read()
        if not ok:
            break
        t = idx * frame_ms
        idx += 1
        if max_seconds is not None and next_ms > max_seconds * 1000:
            break
        if t + frame_ms / 2 < next_ms:
            continue
        time_ms = next_ms
        next_ms += interval_ms

        k3, s, ksim, k2 = model(bgr)
        if len(k3) == 0:
            continue
        person = int(np.argmax(s[:, :17].mean(axis=1)))  # 取身體 17 點平均分數最高的人
        times.append(time_ms)
        kp3.append(k3[person, :17]); kp2.append(k2[person, :17])
        sc.append(s[person, :17]); sim.append(ksim[person, :17])
        if len(times) % 20 == 0:
            print(f"  {time_ms / 1000:6.1f}s  detected={len(times)}", flush=True)

    npz = out_dir / "raw3d.npz"
    np.savez(npz, time_ms=np.array(times), kp3=np.array(kp3), kp2=np.array(kp2), scores=np.array(sc),
             simcc=np.array(sim), width=width, height=height)
    print(f"wrote {npz} ({len(times)} frames)")
    return npz


def _crop_scale(kp3, kp2):
    """框寬 / 輸入寬：由左右肩的 x 差比出（keypoints_2d 是影像像素，kp3 是裁切像素）。"""
    d3 = np.abs(kp3[:, 5, 0] - kp3[:, 6, 0])
    d2 = np.abs(kp2[:, 5, 0] - kp2[:, 6, 0])
    ok = d3 > 1e-3
    return np.where(ok, d2 / np.maximum(d3, 1e-3), np.nan)


def to_world(npz: dict, zscale: float):
    """回傳 world[N,17,3]，單位＝影像高度；原點在髖中心。"""
    kp2, simcc = npz["kp2"], npz["simcc"]
    kp3 = simcc.copy()
    kp3[..., :2] = simcc[..., :2]
    ratio = _crop_scale(kp3, kp2)
    ratio = np.where(np.isnan(ratio), np.nanmedian(ratio), ratio)
    z = (simcc[..., 2] - Z_CENTER) * ratio[:, None] * zscale
    height = float(npz["height"])
    world = np.stack([kp2[..., 0], kp2[..., 1], z], axis=-1) / height
    hip = (world[:, 11] + world[:, 12]) / 2
    return world - hip[:, None, :]


def estimate_zscale(npz: dict) -> float:
    """挑一個 zscale 讓上臂/前臂的 3D 骨長在整段影片中最穩定（變異係數最小）。"""
    best, best_cv = 1.0, 1e9
    for zs in np.linspace(0.2, 3.0, 57):
        w = to_world(npz, zs)
        cv = 0.0
        for a, b in ((5, 7), (7, 9), (6, 8), (8, 10)):
            length = np.linalg.norm(w[:, a] - w[:, b], axis=1)
            cv += length.std() / max(length.mean(), 1e-6)
        if cv < best_cv:
            best, best_cv = float(zs), cv
    return best


def median_filter(world: np.ndarray, size: int) -> np.ndarray:
    """沿時間軸做中值濾波，去掉單幀的深度尖峰（size<=1 不濾）。"""
    if size <= 1:
        return world
    half = size // 2
    out = world.copy()
    for f in range(len(world)):
        lo, hi = max(0, f - half), min(len(world), f + half + 1)
        out[f] = np.median(world[lo:hi], axis=0)
    return out


def write_csvs(npz: dict, out_dir: Path, zscale: float, median: int = 3) -> None:
    times, kp2, scores = npz["time_ms"], npz["kp2"], np.clip(npz["scores"], 0.0, 1.0)
    width, height = float(npz["width"]), float(npz["height"])
    world = median_filter(to_world(npz, zscale), median)
    with open(out_dir / "pose_landmarks.csv", "w", newline="", encoding="utf-8") as nf, \
            open(out_dir / "pose_world_landmarks.csv", "w", newline="", encoding="utf-8") as wf:
        nw, ww = csv.writer(nf, lineterminator="\n"), csv.writer(wf, lineterminator="\n")
        nw.writerow(CSV_HEADER); ww.writerow(CSV_HEADER)
        for f, t in enumerate(times):
            pts = {}
            for coco_i, mp_i in COCO_TO_MP.items():
                pts[mp_i] = (kp2[f, coco_i, 0] / width, kp2[f, coco_i, 1] / height, world[f, coco_i], scores[f, coco_i])
            for mp_i, src in MP_FILL_FROM.items():
                x, y, w, _ = pts[src]
                pts[mp_i] = (x, y, w, 0.0)
            for i in range(33):
                x, y, w, c = pts[i]
                nw.writerow([t, 0, i, f"{x:f}", f"{y:f}", f"{w[2]:f}", f"{c:f}", f"{c:f}"])
                ww.writerow([t, 0, i, f"{w[0]:f}", f"{w[1]:f}", f"{w[2]:f}", f"{c:f}", f"{c:f}"])


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Video -> rtmlib Wholebody3d -> landmark CSV")
    ap.add_argument("video", type=Path, nargs="?")
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--interval", type=int, default=200)
    ap.add_argument("--max-seconds", type=float, default=None)
    ap.add_argument("--zscale", type=float, default=2.0,
                    help="深度縮放（預設 2.0：z 範圍 ±2.17 公尺對應 ±192 輸入像素，人身高約 300 輸入像素，換算約 2）；0＝用骨長穩定度自動估計（不太可靠）")
    ap.add_argument("--median", type=int, default=3, help="3D 座標時間中值濾波的視窗（幀數），1＝不濾")
    ap.add_argument("--from-npz", action="store_true", help="沿用 out-dir/raw3d.npz，只重新轉 CSV")
    a = ap.parse_args(argv)

    npz_path = a.out_dir / "raw3d.npz"
    if not a.from_npz:
        if a.video is None:
            ap.error("需要影片路徑（或用 --from-npz）")
        npz_path = extract(a.video, a.out_dir, a.interval, a.max_seconds)
    npz = dict(np.load(npz_path))
    zs = a.zscale or estimate_zscale(npz)
    write_csvs(npz, a.out_dir, zs, a.median)
    print(f"zscale={zs:.2f}  wrote CSVs in {a.out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
