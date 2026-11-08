"""用筆電鏡頭拍 Kebbi，量「影片 / 模擬 / 實機」三條時間線有沒有對上。

    python camera_check.py record 192.168.50.143 out/dance_test_20s/dance_script.json uploads/dance_test_20s.mp4
    python camera_check.py analyze out/dance_test_20s/camera_<時間>

record（會轉動馬達）：
  1. 開鏡頭錄影，每一幀用電腦時鐘 time.time() 打時間戳（frames.csv）
  2. 校正：頭、右臂、左臂各用 /motor-test 轉一次。機器人端回報的實際角度曲線 + 對好的時鐘
     → 知道「馬達真的在動」的電腦時間，和鏡頭看到動的時間相減 = 鏡頭本身的延遲；
     同時找出畫面裡哪些格子是頭 / 右臂 / 左臂（跟著那顆馬達動的格子）
  3. 和 sync_play.py 一樣：上傳腳本、對時、瀏覽器播放影片，Kebbi 同一刻開始
  4. 播完回 home，停止錄影，寫 run.json

analyze（不動馬達）：
  - 實機：鏡頭畫面在頭 / 左右臂格子裡的動作量（幀差），扣掉鏡頭延遲
  - 模擬：sim_result.json（pybullet_sim.py）各部位角速度
  - 影片：pose_landmarks.csv 舞者手臂速度
  兩兩做互相關，找出時間差（正值 = 前者比後者晚）與相關係數（動作形狀像不像），
  再每 4 秒算一次看有沒有越播越偏。輸出 report.json、report.png、compare.mp4（影片｜模擬｜實機並排）。

只量得到「什麼時候動、動多少」，量不到絕對角度（單鏡頭、Kebbi 不是人，人體骨架模型套不上去）。
"""

from __future__ import annotations

import argparse
import csv
import json
import queue
import sys
import threading
import time
import webbrowser
from http.server import ThreadingHTTPServer
from pathlib import Path

import cv2
import numpy as np

from kebbi_link import KebbiError, KebbiLink

HERE = Path(__file__).resolve().parent

# 部位 → 馬達（與 RobotMotor.java / sim/build_urdf.py 相同）
PARTS = {"head": [1, 2], "right_arm": [3, 4, 5, 6], "left_arm": [7, 8, 9, 10]}
PART_LABEL = {"head": "頭", "right_arm": "右臂", "left_arm": "左臂"}
# 校正動作：(部位, 馬達, 角度, 速度)
CALIBRATION = [("head", 2, 25.0, 90.0), ("right_arm", 4, -40.0, 90.0), ("left_arm", 8, -40.0, 90.0)]
# MediaPipe：舞者左臂 11/13/15、右臂 12/14/16，頭 0
DANCER = {"left": [11, 13, 15], "right": [12, 14, 16], "head": [0, 7, 8]}

GRID_W, GRID_H = 32, 18      # 分析用的格子數（畫面縮成 128x72，每格 4x4 像素）
RATE = 100.0                 # 分析時間線取樣率（Hz）


# ---------------------------------------------------------------------------
# record
# ---------------------------------------------------------------------------

class Camera(threading.Thread):
    """抓幀執行緒只負責讀幀 + 打時間戳；寫檔交給另一個執行緒，避免寫檔拖慢讀幀。"""

    def __init__(self, index: int, out_dir: Path, width: int, height: int):
        super().__init__(daemon=True)
        self.cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
        if not self.cap.isOpened():
            raise RuntimeError(f"開不了鏡頭 {index}")
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        ok, frame = self.cap.read()
        if not ok:
            raise RuntimeError("鏡頭讀不到畫面")
        h, w = frame.shape[:2]
        self.writer = cv2.VideoWriter(str(out_dir / "camera.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), 30.0, (w, h))
        self.stamps: list[float] = []
        self.q: queue.Queue = queue.Queue(maxsize=600)
        self.running = True
        self.writer_thread = threading.Thread(target=self._write, daemon=True)
        self.writer_thread.start()

    def run(self) -> None:
        while self.running:
            ok, frame = self.cap.read()
            t = time.time()
            if not ok:
                continue
            self.stamps.append(t)
            self.q.put(frame)

    def _write(self) -> None:
        while True:
            frame = self.q.get()
            if frame is None:
                break
            self.writer.write(frame)

    def close(self) -> None:
        self.running = False
        self.join(timeout=2)
        self.q.put(None)
        self.writer_thread.join()
        self.writer.release()
        self.cap.release()


def record(args) -> int:
    script_bytes = args.script.read_bytes()
    script = json.loads(script_bytes.decode("utf-8"))
    out = args.out or (args.script.parent / time.strftime("camera_%Y%m%d_%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    link = KebbiLink.connect(None if args.host == "auto" else args.host)
    if link.status().get("playing"):
        print("Kebbi 正在播放，先停止")
        link.stop()

    cam = Camera(args.cam, out, args.width, args.height)
    cam.start()
    run = {"host": link.host, "script": str(args.script.resolve()), "video": str(args.video.resolve()),
           "calibration": [], "status": []}
    server = None
    try:
        print("回到 home...")
        link.home()
        time.sleep(2.5)
        clock = link.sync_clock()
        run["clockBefore"] = clock.__dict__
        print(f"對時：來回最短 {clock.rtt_ms:.1f} ms")
        for part, motor, deg, speed in CALIBRATION:
            for target in (deg, 0.0):
                time.sleep(0.8)
                t_send = time.time()
                r = link.motor_test(motor, target, speed)
                t_recv = time.time()
                run["calibration"].append({"part": part, "motor": motor, "target": target, "speed": speed,
                                           "tSend": t_send, "tRecv": t_recv, "result": r})
                print(f"校正 {PART_LABEL[part]} M{motor} → {target:g}°：開始動 {r.get('firstMoveMs')} ms，"
                      f"到位 {r.get('reachMs')} ms")
        time.sleep(1.0)

        print("上傳腳本...", link.upload(script_bytes).get("message"))
        clock = link.sync_clock()
        run["clock"] = clock.__dict__
        start = time.time() + args.delay
        run["start"] = start
        if not args.no_video:
            import sync_play
            plan = {"videoZeroMs": start * 1000.0, "host": link.host, "clockErrorMs": clock.error_ms}
            server = ThreadingHTTPServer(("127.0.0.1", 8810),
                                         sync_play.make_handler(args.video, plan, link, threading.Event()))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            webbrowser.open("http://127.0.0.1:8810/")
        print(link.play_at(start).get("message"))
        end = start + script["durationMs"] / 1000.0 + 2.0
        while time.time() < end:
            time.sleep(1.0)
            try:
                s = link.status()
            except KebbiError:
                continue
            run["status"].append({"t": time.time(), "positionMs": s.get("positionMs"), "playing": s.get("playing")})
            if s.get("positionMs") is not None:
                print(f"  t={time.time() - start:5.1f}s  Kebbi 進度差 "
                      f"{s['positionMs'] - (time.time() - start) * 1000:+.0f} ms")
        run["clockAfter"] = link.sync_clock().__dict__
        link.home()
        time.sleep(2.0)
    except KeyboardInterrupt:
        print("中斷，停止 Kebbi")
        link.stop()
    finally:
        cam.close()
        if server:
            server.shutdown()
        with (out / "frames.csv").open("w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["frame", "pc_time"])
            for i, t in enumerate(cam.stamps):
                w.writerow([i, f"{t:.6f}"])
        (out / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    fps = (len(cam.stamps) - 1) / (cam.stamps[-1] - cam.stamps[0])
    print(f"錄了 {len(cam.stamps)} 幀（{fps:.1f} fps）→ {out}")
    print(f"分析：python camera_check.py analyze {out}")
    return 0


# ---------------------------------------------------------------------------
# analyze
# ---------------------------------------------------------------------------

def motion_energy(video: Path, stamps: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """每格的幀差（每秒），時間取兩幀中間。回傳 (times, energy[N, GRID_H*GRID_W])。"""
    cap = cv2.VideoCapture(str(video))
    prev, energy = None, []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        g = cv2.GaussianBlur(cv2.cvtColor(cv2.resize(frame, (GRID_W * 4, GRID_H * 4), interpolation=cv2.INTER_AREA),
                                          cv2.COLOR_BGR2GRAY).astype(np.float32), (3, 3), 0)
        if prev is not None:
            d = np.abs(g - prev).reshape(GRID_H, 4, GRID_W, 4).mean(axis=(1, 3)).ravel()
            energy.append(d)
        prev = g
    n = min(len(energy), len(stamps) - 1)
    energy = np.array(energy[:n])
    dt = np.diff(stamps)[:n]
    times = (stamps[:-1] + stamps[1:])[:n] / 2
    return times, energy / np.maximum(dt, 1e-3)[:, None]


def resample(t_src: np.ndarray, y: np.ndarray, t: np.ndarray) -> np.ndarray:
    return np.interp(t, t_src, y, left=np.nan, right=np.nan)


def speed_from_angles(t: np.ndarray, deg: np.ndarray) -> np.ndarray:
    return np.abs(np.gradient(deg, t))


def best_lag(a: np.ndarray, b: np.ndarray, lags_s: np.ndarray) -> tuple[float, float, np.ndarray]:
    """b 對 a 的時間差：b(t + lag) 最像 a(t) 的 lag（正值 = b 比 a 晚）。回傳 (lag, r, r_by_lag)。"""
    rs = []
    for lag in lags_s:
        k = int(round(lag * RATE))
        if k >= 0:
            x, y = a[:len(a) - k] if k else a, b[k:]
        else:
            x, y = a[-k:], b[:len(b) + k]
        m = ~(np.isnan(x) | np.isnan(y))
        rs.append(np.corrcoef(x[m], y[m])[0, 1] if m.sum() > 20 and x[m].std() > 0 and y[m].std() > 0 else np.nan)
    rs = np.array(rs)
    if np.all(np.isnan(rs)):
        return float("nan"), float("nan"), rs
    i = int(np.nanargmax(rs))
    # 拋物線內插到比取樣間隔更細
    lag = lags_s[i]
    if 0 < i < len(rs) - 1 and not np.isnan(rs[i - 1]) and not np.isnan(rs[i + 1]):
        den = rs[i - 1] - 2 * rs[i] + rs[i + 1]
        if den < 0:
            lag += 0.5 * (rs[i - 1] - rs[i + 1]) / den * (lags_s[1] - lags_s[0])
    return float(lag), float(rs[i]), rs


def smooth(x: np.ndarray, sec: float) -> np.ndarray:
    k = max(1, int(sec * RATE))
    w = np.ones(k) / k
    m = np.isnan(x)
    y = np.convolve(np.where(m, 0, x), w, mode="same") / np.maximum(np.convolve(~m, w, mode="same"), 1e-6)
    y[m] = np.nan
    return y


def calibrate(run: dict, t_cam: np.ndarray, energy: np.ndarray) -> dict:
    """校正動作：找每個部位的格子遮罩，和鏡頭延遲。"""
    one_way = 1.5 * run["clockBefore"]["rtt_ms"] / 1000.0   # TCP 握手 1 趟 + 請求半趟
    out = {}
    for part, motor, _, _ in CALIBRATION:
        tests = [c for c in run["calibration"] if c["motor"] == motor]
        t_parts, robot_parts, cell_parts = [], [], []
        for c in tests:
            s = np.array(c["result"].get("samples") or [], dtype=float)
            if len(s) < 3:
                continue
            t0 = c["tSend"] + one_way
            t = np.arange(c["tSend"] - 0.4, c["tRecv"] + 0.6, 1 / RATE)
            ts = t0 + s[:, 0] / 1000.0
            deg = np.interp(t, np.r_[ts[0] - 1, ts, ts[-1] + 2], np.r_[s[0, 1], s[:, 1], s[-1, 1]])
            robot_parts.append(smooth(speed_from_angles(t, deg), 0.05))
            cell_parts.append(np.stack([resample(t_cam, energy[:, j], t) for j in range(energy.shape[1])], 1))
            t_parts.append(t)
        if not robot_parts:
            continue
        robot = np.concatenate(robot_parts)
        cells = np.concatenate(cell_parts)
        # 每格：鏡頭延遲 0~400ms 內的最大相關
        lags = np.arange(0.0, 0.401, 0.01)
        score = np.full(cells.shape[1], np.nan)
        for j in range(cells.shape[1]):
            if np.nanstd(cells[:, j]) > 0:
                score[j] = np.nanmax(best_lag(robot, cells[:, j], lags)[2])
        best = np.nanmax(score)
        mask = score >= max(0.35, 0.6 * best)
        signal = np.nanmean(cells[:, mask], axis=1)
        lag, r, _ = best_lag(robot, signal, lags)
        out[part] = {"motor": motor, "mask": mask.tolist(), "cells": int(mask.sum()), "bestCellR": float(best),
                     "cameraLagS": lag, "r": r,
                     "robotFirstMoveMs": [c["result"].get("firstMoveMs") for c in tests],
                     "robotReachMs": [c["result"].get("reachMs") for c in tests],
                     "expectedMs": [c["result"].get("expectedMs") for c in tests]}
    return out


def sim_part_speed(sim: dict, t: np.ndarray) -> dict:
    ft = np.array([f["t"] for f in sim["frames"]]) / 1000.0
    res = {}
    for part, motors in PARTS.items():
        total = np.zeros_like(t)
        for m in motors:
            deg = np.array([f["actual"].get(str(m), 0.0) for f in sim["frames"]])
            total += np.nan_to_num(resample(ft, speed_from_angles(ft, deg), t))
        res[part] = smooth(total, 0.1)
    return res


def sim_angles(sim: dict):
    ft = np.array([f["t"] for f in sim["frames"]]) / 1000.0
    return ft, {m: np.array([f["actual"].get(str(m), 0.0) for f in sim["frames"]]) for m in range(1, 11)}


def dancer_speed(pose_csv: Path, t: np.ndarray) -> dict:
    rows = {}
    with pose_csv.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["pose_index"] != "0":
                continue
            rows.setdefault(int(r["time_ms"]), {})[int(r["landmark_index"])] = (float(r["x"]), float(r["y"]))
    times = np.array(sorted(rows)) / 1000.0
    res = {}
    for side, ids in DANCER.items():
        pts = np.array([[rows[int(round(tt * 1000))].get(i, (np.nan, np.nan)) for i in ids] for tt in times])
        # 相對肩膀 / 鼻子的位置，扣掉整個人移動
        rel = pts - pts[:, :1] if side != "head" else pts
        v = np.linalg.norm(np.gradient(rel, times, axis=0), axis=2).sum(axis=1)
        res[side] = smooth(resample(times, v, t), 0.1)
    return res


def windows(a: np.ndarray, b: np.ndarray, t: np.ndarray, lags: np.ndarray, win: float = 4.0) -> list:
    out = []
    for s in np.arange(0, t[-1] - win + 0.01, win):
        m = (t >= s) & (t < s + win)
        lag, r, _ = best_lag(np.where(m, a, np.nan), b, lags)
        out.append({"from": round(float(s), 1), "lagMs": None if np.isnan(lag) else round(lag * 1000),
                    "r": None if np.isnan(r) else round(r, 2)})
    return out


def analyze(args) -> int:
    d = args.run_dir
    run = json.loads((d / "run.json").read_text(encoding="utf-8"))
    script_path = Path(run["script"])
    result_dir = script_path.parent
    sim_path = result_dir / "sim_result.json"
    if not sim_path.is_file():
        sys.exit(f"沒有 {sim_path}，先跑 sim/pybullet_sim.py {script_path} --out {sim_path}")
    sim = json.loads(sim_path.read_text(encoding="utf-8"))
    script = json.loads(script_path.read_text(encoding="utf-8"))
    stamps = np.loadtxt(d / "frames.csv", delimiter=",", skiprows=1)[:, 1]
    print(f"讀鏡頭畫面（{len(stamps)} 幀，{(len(stamps) - 1) / (stamps[-1] - stamps[0]):.1f} fps）...")
    t_cam, energy = motion_energy(d / "camera.mp4", stamps)

    cal = calibrate(run, t_cam, energy)
    cam_lags = [c["cameraLagS"] for c in cal.values() if c["r"] > 0.5]
    cam_lag = float(np.median(cam_lags)) if cam_lags else 0.0
    print(f"鏡頭延遲：{cam_lag * 1000:.0f} ms（" +
          "，".join(f"{PART_LABEL[p]} {c['cameraLagS'] * 1000:.0f} ms r={c['r']:.2f} {c['cells']} 格"
                   for p, c in cal.items()) + "）")

    start = run["start"]
    dur = script["durationMs"] / 1000.0
    t = np.arange(0, dur, 1 / RATE)           # 影片時間（t=0 = 影片開頭 = 腳本 t=0）
    real = {}
    for part, c in cal.items():
        mask = np.array(c["mask"])
        sig = np.nanmean(energy[:, mask], axis=1)
        real[part] = smooth(resample(t_cam - start - cam_lag, sig, t), 0.1)
    simv = sim_part_speed(sim, t)
    pose_csv = result_dir / "pose_landmarks.csv"
    dancer = dancer_speed(pose_csv, t) if pose_csv.is_file() else {}

    lags = np.arange(-0.6, 0.801, 0.01)
    report = {"cameraLagMs": round(cam_lag * 1000), "calibration": {p: {k: v for k, v in c.items() if k != "mask"}
                                                                     for p, c in cal.items()},
              "clockRttMs": run["clock"]["rtt_ms"], "parts": {}}
    # 舞者左右手對 Kebbi 哪隻手：兩種都試，取相關高的
    side_map = {}
    if dancer:
        straight = best_lag(simv["left_arm"], dancer["left"], lags)[1] + best_lag(simv["right_arm"], dancer["right"], lags)[1]
        mirror = best_lag(simv["left_arm"], dancer["right"], lags)[1] + best_lag(simv["right_arm"], dancer["left"], lags)[1]
        side_map = ({"left_arm": "left", "right_arm": "right"} if straight >= mirror
                    else {"left_arm": "right", "right_arm": "left"})
        side_map["head"] = "head"
        report["dancerSideMap"] = side_map

    print("\n部位    實機比模擬晚     模擬比影片晚     實機比影片晚    (ms, 相關係數)")
    for part in PARTS:
        row = {}
        if part in real:
            lag, r, _ = best_lag(simv[part], real[part], lags)
            row["realVsSim"] = {"lagMs": round(lag * 1000), "r": round(r, 2)}
            row["realVsSimWindows"] = windows(simv[part], real[part], t, lags)
        if dancer:
            lag, r, _ = best_lag(dancer[side_map[part]], simv[part], lags)
            row["simVsVideo"] = {"lagMs": round(lag * 1000), "r": round(r, 2)}
            if part in real:
                lag, r, _ = best_lag(dancer[side_map[part]], real[part], lags)
                row["realVsVideo"] = {"lagMs": round(lag * 1000), "r": round(r, 2)}
        report["parts"][part] = row

        def fmt(k):
            v = row.get(k)
            return f"{v['lagMs']:+5d} ({v['r']:.2f})" if v else "      -     "
        print(f"{PART_LABEL[part]:4s}   {fmt('realVsSim'):>14s}   {fmt('simVsVideo'):>14s}   {fmt('realVsVideo'):>14s}")

    # Kebbi 自己回報的播放進度 vs 電腦時鐘
    drift = [s["positionMs"] - (s["t"] - start) * 1000 for s in run["status"] if s.get("positionMs") is not None]
    if drift:
        report["kebbiProgressMinusPcMs"] = {"median": round(float(np.median(drift))), "min": round(min(drift)),
                                            "max": round(max(drift))}
    (d / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    plot(d, t, simv, real, dancer, side_map, report)
    if not args.no_video:
        compare_video(d, run, stamps, sim, t_cam, cam_lag, report)
    print(f"\n→ {d / 'report.json'}, report.png" + ("" if args.no_video else ", compare.mp4"))
    return 0


def plot(d: Path, t, simv, real, dancer, side_map, report) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft JhengHei", "Microsoft YaHei", "SimHei", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    fig, axes = plt.subplots(3, 1, figsize=(14, 9), sharex=True)
    norm = lambda x: x / np.nanpercentile(x, 98) if np.nanpercentile(x, 98) > 0 else x
    for ax, part in zip(axes, PARTS):
        if dancer:
            ax.plot(t, norm(dancer[side_map[part]]), color="#999", lw=1, label="影片舞者")
        ax.plot(t, norm(simv[part]), color="#2a7", lw=1.4, label="模擬")
        if part in real:
            ax.plot(t, norm(real[part]), color="#d40", lw=1.4, label="實機（鏡頭，已扣鏡頭延遲）")
        row = report["parts"][part]
        title = PART_LABEL[part]
        if "realVsSim" in row:
            title += f"　實機比模擬晚 {row['realVsSim']['lagMs']} ms（r={row['realVsSim']['r']}）"
        if "simVsVideo" in row:
            title += f"　模擬比影片晚 {row['simVsVideo']['lagMs']} ms（r={row['simVsVideo']['r']}）"
        ax.set_title(title, fontsize=11, loc="left")
        ax.set_ylim(0, 1.3)
        ax.set_ylabel("動作速度（正規化）")
        ax.legend(loc="upper right", fontsize=8)
    axes[-1].set_xlabel("影片時間（秒）")
    fig.tight_layout()
    fig.savefig(d / "report.png", dpi=110)
    plt.close(fig)


def compare_video(d: Path, run: dict, stamps, sim, t_cam, cam_lag, report) -> None:
    """影片｜模擬｜實機並排，同一個影片時間。實機那格用鏡頭原始時間（沒扣延遲），眼睛看到的就是真實情況。"""
    sys.path.insert(0, str(HERE / "sim"))
    import pybullet as p
    from build_urdf import JOINT_TO_MOTOR, MOTORS, build
    p.connect(p.DIRECT)
    robot = p.loadURDF(str(build()), useFixedBase=True)
    joints = {}
    n = p.getNumJoints(robot)
    # TinyRenderer 畫這個模型全黑（網格沒有法向量），改用分割遮罩自己上色：右臂紅、左臂綠
    lut = np.full((n + 2, 3), (200, 200, 205), np.uint8)
    for i in range(n):
        info = p.getJointInfo(robot, i)
        link = info[12].decode()
        if link.startswith("left_"):
            lut[i + 1] = (80, 200, 80)
        elif link.startswith("right_"):
            lut[i + 1] = (60, 60, 230)
        if info[1].decode() in JOINT_TO_MOTOR:
            joints[JOINT_TO_MOTOR[info[1].decode()]] = i
    view = p.computeViewMatrixFromYawPitchRoll([0.0, 0, 0.2], 0.75, args_yaw[0], -10, 0, 2)
    projm = p.computeProjectionMatrixFOV(45, 1.0, 0.05, 5)
    ft, ang = sim_angles(sim)

    H = 360
    src = cv2.VideoCapture(run["video"])
    cam = cv2.VideoCapture(str(d / "camera.mp4"))
    cam_frames_t = stamps
    fps = 15.0
    start = run["start"]
    dur = sim["duration_ms"] / 1000.0
    writer = None
    cam_idx, cam_frame = -1, None
    for k in range(int(dur * fps)):
        tv = k / fps
        src.set(cv2.CAP_PROP_POS_MSEC, tv * 1000)
        ok, vf = src.read()
        vf = vf if ok else np.zeros((H, H, 3), np.uint8)
        for m, j in joints.items():
            deg = float(np.interp(tv, ft, ang[m]))
            p.resetJointState(robot, j, np.radians(deg) * MOTORS[m][3])
        _, _, _, depth, seg = p.getCameraImage(H, H, view, projm, renderer=p.ER_TINY_RENDERER,
                                               flags=p.ER_SEGMENTATION_MASK_OBJECT_AND_LINKINDEX)
        seg = np.reshape(np.array(seg), (H, H))
        depth = np.reshape(np.array(depth), (H, H))
        m = seg >= 0
        link = np.where(m, seg >> 24, -2) + 1
        sf = np.full((H, H, 3), 255, np.uint8)
        if m.any():
            shade = 1 - np.clip((depth - depth[m].min()) / (np.ptp(depth[m]) + 1e-9), 0, 1) * 0.5
            sf[m] = (lut[link[m]] * shade[m, None]).astype(np.uint8)
            sf[cv2.Canny(link.astype(np.uint8) * 7, 1, 1) > 0] = (40, 40, 40)
        want = start + tv
        target = int(np.searchsorted(cam_frames_t, want))
        while cam_idx < target:
            ok, f = cam.read()
            if not ok:
                break
            cam_idx += 1
            cam_frame = f
        cf = cam_frame if cam_frame is not None else np.zeros((H, H, 3), np.uint8)
        panels = [cv2.resize(x, (int(x.shape[1] * H / x.shape[0]), H)) for x in (vf, sf, cf)]
        for img_, label in zip(panels, ("video", "sim", "real (camera)")):
            cv2.putText(img_, label, (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
        frame = np.hstack(panels)
        cv2.putText(frame, f"t={tv:5.2f}s  camera lag ~{cam_lag * 1000:.0f}ms (not removed in this panel)",
                    (8, H - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        if writer is None:
            writer = cv2.VideoWriter(str(d / "compare.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), fps,
                                     (frame.shape[1], frame.shape[0]))
        writer.write(frame)
    if writer:
        writer.release()
    p.disconnect()


args_yaw = [90.0]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Check video / sim / real Kebbi sync with the laptop camera")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("record")
    r.add_argument("host")
    r.add_argument("script", type=Path)
    r.add_argument("video", type=Path)
    r.add_argument("--cam", type=int, default=0)
    r.add_argument("--width", type=int, default=1280)
    r.add_argument("--height", type=int, default=720)
    r.add_argument("--delay", type=float, default=4.0)
    r.add_argument("--out", type=Path)
    r.add_argument("--no-video", action="store_true", help="不開瀏覽器播放影片")
    a = sub.add_parser("analyze")
    a.add_argument("run_dir", type=Path)
    a.add_argument("--no-video", action="store_true", help="不輸出 compare.mp4")
    a.add_argument("--sim-yaw", type=float, default=90.0, help="模擬畫面的鏡頭方位角")
    args = ap.parse_args(argv)
    if args.cmd == "record":
        return record(args)
    args_yaw[0] = args.sim_yaw
    return analyze(args)


if __name__ == "__main__":
    sys.exit(main())
