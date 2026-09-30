"""量化「機器人手臂有多貼近影片」。

    python eval_fidelity.py ../out/rtmw3d/raw3d.npz sim_result.json

做法：
- 目標：從 raw3d.npz（未濾波）算每幀左右上臂在身體座標系的方向（前/外/上），與 PoseAnalyzer 同一套算法；
- 實際：PyBullet 模擬輸出的馬達角度（sim_result.json 的 frames），由 Rz(z)·Ry(y)·Rx(x)·(0,0,-1) 算出上臂方向；
- 比較每個取樣點的夾角（度），並比較手肘彎曲（人體 180-肩肘腕夾角 vs 機器人 -肘角）。
只看偵測信心夠的幀（肩、肘、腕、髖分數 >= 0.5）。
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import extract_pose3d as e  # noqa: E402


def rot(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def robot_dir(z, y, x):
    return rot("z", z) @ rot("y", y) @ rot("x", x) @ np.array([0, 0, -1.0])


def unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


def body_arm_dirs(w):
    """w: [17,3] 世界座標 → (左臂, 右臂) 各為 (前, 外, 上)。"""
    left = unit(w[5] - w[6])
    up = (w[5] + w[6]) / 2 - (w[11] + w[12]) / 2
    up = unit(up - up @ left * left)
    fwd = np.cross(left, up)
    out = []
    for sh, el, sign in ((5, 7, 1), (6, 8, -1)):
        d = unit(w[el] - w[sh])
        out.append(np.array([d @ fwd, sign * (d @ left), d @ up]))
    return out


def elbow_flexion(w, sh, el, wr):
    a, b = unit(w[sh] - w[el]), unit(w[wr] - w[el])
    return 180.0 - math.degrees(math.acos(max(-1, min(1, a @ b))))


def evaluate(npz_path: Path, sim_path: Path, zscale: float = 2.0) -> dict:
    npz = dict(np.load(npz_path))
    world = e.to_world(npz, zscale)
    scores = npz["scores"]
    sim = json.loads(sim_path.read_text(encoding="utf-8"))
    st = np.array([f["t"] for f in sim["frames"]])
    res = {"L": [], "R": [], "L2": [], "R2": [], "EL": [], "ER": []}
    kp2 = npz["kp2"]
    for k, t in enumerate(npz["time_ms"]):
        if min(scores[k, [5, 6, 7, 8, 9, 10, 11, 12]]) < 0.5:
            continue
        f = sim["frames"][int(np.argmin(np.abs(st - t)))]["actual"]
        dl, dr = body_arm_dirs(world[k])
        rl = robot_dir(f["7"], f["8"], f["9"])[[0, 1, 2]]
        rr = robot_dir(f["3"], f["4"], f["5"])
        # robot_dir 的第二分量是機器人「左」，左臂外側為正；右臂的馬達慣例相同（外側為正）
        for key, det, rob in (("L", dl, rl), ("R", dr, rr)):
            res[key].append(math.degrees(math.acos(max(-1, min(1, unit(det) @ rob)))))
        # 正面投影（畫面上看到的方向）：偵測的 2D 肩→肘 vs 機器人 (外, 上)；假設人面向鏡頭
        for key, sh, el, sign, rob in (("L2", 5, 7, 1, rl), ("R2", 6, 8, -1, rr)):
            det2 = np.array([sign * (kp2[k, el, 0] - kp2[k, sh, 0]), -(kp2[k, el, 1] - kp2[k, sh, 1])])
            rob2 = np.array([rob[1], rob[2]])
            if np.linalg.norm(det2) > 1e-6 and np.linalg.norm(rob2) > 0.05:
                res[key].append(math.degrees(math.acos(max(-1, min(1, unit(det2) @ unit(rob2))))))
        res["EL"].append(abs(elbow_flexion(world[k], 5, 7, 9) - (-f["10"])))
        res["ER"].append(abs(elbow_flexion(world[k], 6, 8, 10) - (-f["6"])))
    out = {}
    for k, v in res.items():
        v = np.array(v)
        out[k] = {"mean": round(float(v.mean()), 1), "median": round(float(np.median(v)), 1),
                  "p90": round(float(np.percentile(v, 90)), 1), "n": len(v)}
    return out


if __name__ == "__main__":
    r = evaluate(Path(sys.argv[1]), Path(sys.argv[2]))
    names = {"L2": "L arm 2D-projected", "R2": "R arm 2D-projected", "L": "L arm 3D dir", "R": "R arm 3D dir", "EL": "L elbow flex", "ER": "R elbow flex"}
    for k, v in r.items():
        print(f"{names[k]}: mean {v["mean"]} median {v["median"]}  p90 {v['p90']}  (n={v['n']})")
