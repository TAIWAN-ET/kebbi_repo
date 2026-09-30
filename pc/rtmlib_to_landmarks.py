"""VIDEOTEST 的 rtmlib.csv → Kebbi 管線用的 landmark CSV。

讓專題包（rtmlib，COCO 17 點）可以接到 make_dance.py 之後的流程：

    python rtmlib_to_landmarks.py <VIDEOTEST>/output/rtmlib.csv --out-dir out/rtmlib_demo
    .venv/Scripts/python make_dance.py <影片> --skip-extract --out-dir out/rtmlib_demo

輸入：修正單位後的 rtmlib.csv（x/y 為 0~1 normalized，最後兩欄 image_w / image_h）。
輸出（格式與 extract_pose.py 相同，App 的 PoseIO.readCsv() 可直接讀）：
- pose_landmarks.csv        normalized 影像座標，判斷「誰比誰高」用（PoseAnalyzer 的 0.05 / 0.03 門檻）
- pose_world_landmarks.csv  等比例座標，算角度用

世界座標說明：rtmlib Body 只有 2D，沒有 MediaPipe 那種公制 3D。這裡用「以髖中心為原點、
單位＝影像高度、z=0」的等比例座標代替。normalized 的 x/寬、y/高 長寬比不同（16:9），
直接拿去算夾角會被拉歪；換成等比例後角度才正確。代價是沒有深度：手臂朝鏡頭伸直時
2D 投影會看起來比較彎。

只用標準函式庫。
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

CSV_HEADER = ["time_ms", "pose_index", "landmark_index", "x", "y", "z", "visibility", "presence"]

# COCO 17 → MediaPipe 33
COCO_NAMES = [
    "nose", "left_eye", "right_eye", "left_ear", "right_ear",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_hip", "right_hip",
    "left_knee", "right_knee", "left_ankle", "right_ankle",
]
COCO_TO_MP = {0: 0, 1: 2, 2: 5, 3: 7, 4: 8, 5: 11, 6: 12, 7: 13, 8: 14, 9: 15, 10: 16,
              11: 23, 12: 24, 13: 25, 14: 26, 15: 27, 16: 28}
# rtmlib 沒有的 MediaPipe 點：借最近的 COCO 點座標，但可信度設 0（表示「沒有量測」）
MP_FILL_FROM = {1: 2, 3: 2, 4: 5, 6: 5, 9: 0, 10: 0,
                17: 15, 19: 15, 21: 15, 18: 16, 20: 16, 22: 16,
                29: 27, 31: 27, 30: 28, 32: 28}


def convert(rtmlib_csv: Path, out_dir: Path, width: int | None, height: int | None) -> tuple[Path, Path, int]:
    with open(rtmlib_csv, "r", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        raise SystemExit(f"{rtmlib_csv} 是空的")

    out_dir.mkdir(parents=True, exist_ok=True)
    norm_path = out_dir / "pose_landmarks.csv"
    world_path = out_dir / "pose_world_landmarks.csv"

    with open(norm_path, "w", newline="", encoding="utf-8") as nf, \
            open(world_path, "w", newline="", encoding="utf-8") as wf:
        nw = csv.writer(nf, lineterminator="\n")
        ww = csv.writer(wf, lineterminator="\n")
        nw.writerow(CSV_HEADER)
        ww.writerow(CSV_HEADER)

        for row in rows:
            w = width or int(row.get("image_w") or 0)
            h = height or int(row.get("image_h") or 0)
            if w <= 0 or h <= 0:
                raise SystemExit("rtmlib.csv 沒有 image_w / image_h 欄，請用 --width / --height 指定影像尺寸")
            aspect = w / h

            pts = []  # COCO 順序的 (x, y, conf)，x/y 為 normalized
            for name in COCO_NAMES:
                x, y = float(row[f"{name}_x"]), float(row[f"{name}_y"])
                if max(abs(x), abs(y)) > 2.0:
                    raise SystemExit(f"{rtmlib_csv} 的座標像是像素（{x:.0f}, {y:.0f}），"
                                     "請先用修正後的 video_analyzer.py 產生 normalized 版本")
                pts.append((x, y, min(1.0, max(0.0, float(row[f"{name}_conf"])))))

            # 等比例座標：x 乘上寬高比，讓 1 單位在 x、y 方向長度相同；以髖中心為原點
            hip_x = (pts[11][0] + pts[12][0]) / 2 * aspect
            hip_y = (pts[11][1] + pts[12][1]) / 2

            mp: dict[int, tuple[float, float, float]] = {}
            for coco_i, mp_i in COCO_TO_MP.items():
                mp[mp_i] = pts[coco_i]
            for mp_i, src_mp in MP_FILL_FROM.items():
                x, y, _ = mp[src_mp]
                mp[mp_i] = (x, y, 0.0)

            t = row["time_ms"]
            for i in range(33):
                x, y, c = mp[i]
                nw.writerow([t, 0, i, f"{x:f}", f"{y:f}", "0.000000", f"{c:f}", f"{c:f}"])
                ww.writerow([t, 0, i, f"{x * aspect - hip_x:f}", f"{y - hip_y:f}", "0.000000",
                             f"{c:f}", f"{c:f}"])
    return norm_path, world_path, len(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="VIDEOTEST rtmlib.csv -> Kebbi landmark CSV")
    parser.add_argument("rtmlib_csv", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--width", type=int, default=None, help="影像寬（rtmlib.csv 沒有 image_w 時才需要）")
    parser.add_argument("--height", type=int, default=None, help="影像高")
    args = parser.parse_args(argv)
    norm_path, world_path, n = convert(args.rtmlib_csv, args.out_dir, args.width, args.height)
    print(f"converted {n} frames")
    print(f"wrote {norm_path}")
    print(f"wrote {world_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
