"""Kebbi 馬達實測，和 PyBullet 模擬對照：轉速、角度限位、多關節同時動。

    python motor_bench.py run 192.168.50.143                 # 會轉動馬達，約 5 分鐘
    python motor_bench.py run 192.168.50.143 --only speed    # 只跑某一項：speed / limits / multi
    python motor_bench.py report out/motor_bench_<時間>      # 不動馬達，重算報告

量測來源：
  - 實機角度：/motor-test 回傳的 samples（App 在機器人上每 10ms 讀 getMotorPresentPositionInDegree，約每 30ms 記一筆）
  - 鏡頭：整段錄影，限位姿勢各截一張圖，和模擬同姿勢並排
  - 模擬：同樣的指令（目標角度 + 速度上限）丟進 PyBullet（sim/build_urdf.py 的模型）

測試項目：
  speed   每顆馬達，同一個角度差用 30/60/120/200 °/s 去、回各一次 → 實際平均轉速、最高轉速、開始動、到位
  limits  每顆馬達轉到 App 限位的最小 / 最大值（RobotMapper），看實際停在哪、有沒有過衝；
          模擬先檢查這個姿勢會不會自碰撞，會撞的就不在實機上跑
  multi   同時對 1/2/3 顆馬達下 /motor-test（App 最多 4 條連線，留一條給 /stop），
          看同時動時每顆的開始時間和轉速有沒有變差；再用一段腳本讓 10 顆同時動，鏡頭確認
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

import numpy as np

from kebbi_link import KebbiError, KebbiLink

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "sim"))

# motor → (名稱, App 限位 min, max, 速度測試的角度)
MOTOR_INFO = {
    1: ("頭上下 neck_y", -20, 20, 15),
    2: ("頭左右 neck_z", -31, 31, 25),
    3: ("右肩 Z", -85, 5, -45),
    4: ("右肩前後 Y", -200, 70, -60),
    5: ("右肩側舉 X", -3, 100, 60),
    6: ("右肘", -80, 0, -60),
    7: ("左肩 Z", -85, 5, -45),
    8: ("左肩前後 Y", -200, 70, -60),
    9: ("左肩側舉 X", -3, 100, 60),
    10: ("左肘", -80, 0, -60),
}
SPEEDS = [30, 60, 120, 200]
LIMIT_SPEED = 60
MULTI_SETS = [[4], [4, 8], [4, 8, 2], [5, 9, 1]]


# ---------------------------------------------------------------------------
# 模擬
# ---------------------------------------------------------------------------

class Sim:
    DT = 1 / 240

    def __init__(self):
        import pybullet as p
        from build_urdf import JOINT_TO_MOTOR, MOTORS, build
        self.p, self.MOTORS = p, MOTORS
        p.connect(p.DIRECT)
        p.setGravity(0, 0, -9.8)
        p.setTimeStep(self.DT)
        self.robot = p.loadURDF(str(build()), useFixedBase=True)
        self.joints, self.names = {}, {}
        for i in range(p.getNumJoints(self.robot)):
            info = p.getJointInfo(self.robot, i)
            self.names[i] = info[12].decode()
            if info[1].decode() in JOINT_TO_MOTOR:
                self.joints[JOINT_TO_MOTOR[info[1].decode()]] = i
        from pybullet_sim import TORQUE_LIMIT, candidate_pairs, contact_pairs
        self.torque = TORQUE_LIMIT
        self._contact = contact_pairs
        self.pairs = candidate_pairs(self.robot, self.names)
        self.reset({})
        self.baseline = set(self._contact(self.robot, self.names, self.pairs))

    def rad(self, m, deg):
        return np.radians(deg) * self.MOTORS[m][3]

    def deg(self, m):
        return float(np.degrees(self.p.getJointState(self.robot, self.joints[m])[0]) * self.MOTORS[m][3])

    def reset(self, pose: dict):
        for m, j in self.joints.items():
            self.p.resetJointState(self.robot, j, self.rad(m, pose.get(m, 0.0)))
            self.p.setJointMotorControl2(self.robot, j, self.p.POSITION_CONTROL,
                                         targetPosition=self.rad(m, pose.get(m, 0.0)), force=self.torque)
        self.p.stepSimulation()

    def move(self, cmds: dict, start: dict | None = None, seconds: float = 3.0, model: dict | None = None) -> dict:
        """cmds: {motor: (deg, speed)} 同時下指令。回傳每顆 [[ms, deg]] 與碰撞。
        model：sim/motor_model.py 的實機馬達模型（延遲 + 實測轉速）；None = 指令即時、照指令速度。"""
        import motor_model
        p = self.p
        self.reset(start or {})
        pending = []
        for m, (deg, speed) in cmds.items():
            lim = self.MOTORS[m][4]
            if model:
                pending.append((motor_model.delay_s(model, m), m, deg,
                                max(0.1, motor_model.actual_speed(model, m, min(speed, lim)))))
            else:
                pending.append((0.0, m, deg, min(speed, lim)))
        out = {m: [] for m in cmds}
        worst = {}
        for k in range(int(seconds / self.DT)):
            for item in [x for x in pending if x[0] <= k * self.DT]:
                _, m, deg, speed = item
                p.setJointMotorControl2(self.robot, self.joints[m], p.POSITION_CONTROL,
                                        targetPosition=self.rad(m, deg), force=self.torque,
                                        maxVelocity=np.radians(speed))
                pending.remove(item)
            p.stepSimulation()
            if k % 4 == 0:
                for m in cmds:
                    out[m].append([k * self.DT * 1000, self.deg(m)])
            if k % 24 == 0:
                for pair, depth in self._contact(self.robot, self.names, self.pairs, 0.003).items():
                    if pair not in self.baseline:
                        worst[" x ".join(pair)] = max(worst.get(" x ".join(pair), 0), round(depth * 1000, 1))
        return {"samples": out, "collisions": worst}

    def render(self, pose: dict, size: int = 300) -> np.ndarray:
        import cv2
        p = self.p
        self.reset(pose)
        n = p.getNumJoints(self.robot)
        lut = np.full((n + 2, 3), (200, 200, 205), np.uint8)
        for i in range(n):
            if self.names[i].startswith("left_"):
                lut[i + 1] = (80, 200, 80)
            elif self.names[i].startswith("right_"):
                lut[i + 1] = (60, 60, 230)
        view = p.computeViewMatrixFromYawPitchRoll([0.0, 0, 0.2], 0.8, 90, -10, 0, 2)
        proj = p.computeProjectionMatrixFOV(45, 1.0, 0.05, 5)
        _, _, _, depth, seg = p.getCameraImage(size, size, view, proj, renderer=p.ER_TINY_RENDERER,
                                               flags=p.ER_SEGMENTATION_MASK_OBJECT_AND_LINKINDEX)
        seg = np.reshape(np.array(seg), (size, size))
        depth = np.reshape(np.array(depth), (size, size))
        m = seg >= 0
        link = np.where(m, seg >> 24, -2) + 1
        img = np.full((size, size, 3), 255, np.uint8)
        shade = 1 - np.clip((depth - depth[m].min()) / (np.ptp(depth[m]) + 1e-9), 0, 1) * 0.5
        img[m] = (lut[link[m]] * shade[m, None]).astype(np.uint8)
        img[cv2.Canny(link.astype(np.uint8) * 7, 1, 1) > 0] = (40, 40, 40)
        return img


# ---------------------------------------------------------------------------
# 量測指標
# ---------------------------------------------------------------------------

def metrics(samples, start_deg: float, target: float, cmd_speed: float) -> dict:
    s = np.array(samples, dtype=float)
    if len(s) < 3:
        return {}
    t, d = s[:, 0], s[:, 1]
    travel = target - start_deg
    res = {"startDeg": round(start_deg, 1), "finalDeg": round(float(d[-1]), 1),
           "finalErrDeg": round(float(d[-1] - target), 1)}
    if abs(travel) < 1:
        return res
    frac = (d - start_deg) / travel
    res["overshootDeg"] = round(float(max(0.0, (frac.max() - 1) * abs(travel))), 1)
    res["reachedFrac"] = round(float(frac[-1]), 2)
    i10 = np.argmax(frac >= 0.1) if (frac >= 0.1).any() else None
    i90 = np.argmax(frac >= 0.9) if (frac >= 0.9).any() else None
    first = np.argmax(np.abs(d - start_deg) >= 1) if (np.abs(d - start_deg) >= 1).any() else None
    res["firstMoveMs"] = None if first is None else int(t[first])
    if i10 is not None and i90 is not None and i90 > i10:
        res["avgSpeed"] = round(float(0.8 * abs(travel) / ((t[i90] - t[i10]) / 1000)), 1)
        res["t10_90Ms"] = int(t[i90] - t[i10])
    # 最高轉速：跨至少 60ms 的斜率
    best = 0.0
    for i in range(len(t)):
        j = np.searchsorted(t, t[i] + 60)
        if j < len(t):
            best = max(best, abs(d[j] - d[i]) / ((t[j] - t[i]) / 1000))
    res["peakSpeed"] = round(best, 1)
    reach = np.argmax(np.abs(d - target) <= 2) if (np.abs(d - target) <= 2).any() else None
    res["reachMs"] = None if reach is None else int(t[reach])
    res["cmdSpeed"] = cmd_speed
    res["theoryMs"] = int(abs(travel) / cmd_speed * 1000)
    return res


# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------

def motor_test(link: KebbiLink, motor: int, deg: float, speed: float, log: list, tag: str) -> dict:
    t_send = time.time()
    r = link.motor_test(motor, deg, speed)
    entry = {"tag": tag, "motor": motor, "target": deg, "speed": speed, "tSend": t_send, "tRecv": time.time(),
             "result": r}
    log.append(entry)
    return entry


def run_speed(link, log, motors):
    for m in motors:
        name, _, _, amp = MOTOR_INFO[m]
        for sp in SPEEDS:
            e = motor_test(link, m, amp, sp, log, "speed")
            r = e["result"]
            print(f"  M{m:<2} {name:10s} {sp:3d}°/s → {amp:+g}°  開始動 {r.get('firstMoveMs')} ms，到位 {r.get('reachMs')} ms"
                  f"（理論 {r.get('expectedMs')} ms）")
            motor_test(link, m, 0, sp, log, "speed")
            time.sleep(0.3)


def run_limits(link, log, motors, sim: Sim, shots: list):
    for m in motors:
        name, lo, hi, _ = MOTOR_INFO[m]
        for target in (lo, hi):
            if target == 0:
                continue
            simr = sim.move({m: (target, LIMIT_SPEED)}, seconds=abs(target) / LIMIT_SPEED + 1.0)
            if simr["collisions"]:
                print(f"  M{m:<2} {name:10s} → {target:+g}°  模擬會自碰撞 {simr['collisions']}，實機跳過")
                log.append({"tag": "limit", "motor": m, "target": target, "skipped": "sim collision",
                            "simCollisions": simr["collisions"]})
                continue
            e = motor_test(link, m, target, LIMIT_SPEED, log, "limit")
            e["simCollisions"] = simr["collisions"]
            shots.append({"motor": m, "target": target, "t": time.time() - 0.15,
                          "actual": e["result"].get("samples", [[0, None]])[-1][1]})
            r = e["result"]
            last = (r.get("samples") or [[0, None]])[-1][1]
            print(f"  M{m:<2} {name:10s} → {target:+g}°  停在 {last}°，到位 {r.get('reachMs')} ms")
            time.sleep(0.5)
            motor_test(link, m, 0, LIMIT_SPEED, log, "limit-back")
            time.sleep(0.3)


def run_multi(link, log):
    for group in MULTI_SETS:
        for target_sign in (1, 0):
            results, threads = [], []
            t0 = time.time()

            def one(m):
                amp = MOTOR_INFO[m][3] * target_sign
                results.append(motor_test(link, m, amp, 120, log, f"multi{len(group)}"))
            for m in group:
                th = threading.Thread(target=one, args=(m,))
                th.start()
                threads.append(th)
            for th in threads:
                th.join()
            if target_sign:
                desc = "，".join(f"M{e['motor']} 開始動 {e['result'].get('firstMoveMs')} ms（送出 +"
                                f"{(e['tSend'] - t0) * 1000:.0f} ms）" for e in sorted(results, key=lambda e: e["motor"]))
                print(f"  同時 {len(group)} 顆：{desc}")
            time.sleep(0.5)


def run_all_motors_script(link, log):
    """10 顆同時動：用腳本（沒有角度回報，靠鏡頭看）。"""
    cmds_out = [{"motor": m, "deg": float(MOTOR_INFO[m][3]) * (0.5 if m in (4, 8) else 1.0), "speed": 120.0}
                for m in MOTOR_INFO]
    cmds_back = [{"motor": m, "deg": 0.0, "speed": 120.0} for m in MOTOR_INFO]
    script = {"version": 1, "name": "bench_all10", "frameIntervalMs": 200, "durationMs": 3000,
              "sourceFrameCount": 2, "skippedFrameCount": 0,
              "steps": [{"t": 0, "event": "", "cmds": cmds_out}, {"t": 1500, "event": "", "cmds": cmds_back}],
              "faces": []}
    link.upload(json.dumps(script).encode("utf-8"))
    link.sync_clock()
    start = time.time() + 2.0
    link.play_at(start)
    log.append({"tag": "all10", "start": start, "script": script})
    print("  10 顆同時動（腳本），等 5 秒...")
    time.sleep(5.0)


def run(args) -> int:
    from camera_check import Camera
    out = args.out or (HERE / "out" / time.strftime("motor_bench_%Y%m%d_%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    link = KebbiLink.connect(None if args.host == "auto" else args.host)
    if link.status().get("playing"):
        link.stop()
    sim = Sim()
    cam = None if args.no_camera else Camera(args.cam, out, 1280, 720)
    if cam:
        cam.start()
    log, shots = [], []
    only = set(args.only or ["speed", "limits", "multi"])
    motors = args.motors or list(MOTOR_INFO)
    try:
        link.home()
        time.sleep(2.5)
        link.sync_clock()
        if "speed" in only:
            print("轉速測試")
            run_speed(link, log, motors)
        if "limits" in only:
            print("限位測試")
            run_limits(link, log, motors, sim, shots)
        if "multi" in only:
            print("多關節同時動")
            run_multi(link, log)
            run_all_motors_script(link, log)
        link.home()
        time.sleep(2.0)
    except KeyboardInterrupt:
        print("中斷，停止 Kebbi")
        link.stop()
    except KebbiError as e:
        print(f"錯誤：{e}")
        link.stop()
    finally:
        if cam:
            cam.close()
            np.savetxt(out / "frames.csv", np.array(cam.stamps), fmt="%.6f")
        (out / "bench_raw.json").write_text(json.dumps({"host": link.host, "log": log, "shots": shots},
                                                       ensure_ascii=False), encoding="utf-8")
    print(f"原始資料 → {out}")
    return report(argparse.Namespace(bench_dir=out, sim=sim))


# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------

def report(args) -> int:
    import cv2
    d = args.bench_dir
    raw = json.loads((d / "bench_raw.json").read_text(encoding="utf-8"))
    sim = getattr(args, "sim", None) or Sim()
    rows = {"speed": [], "limits": [], "multi": []}

    for e in raw["log"]:
        r = e.get("result") or {}
        if e["tag"] == "speed" and e["target"] != 0:
            real = metrics(r.get("samples") or [], r.get("startDeg", 0), r.get("targetDeg", e["target"]), e["speed"])
            s = sim.move({e["motor"]: (e["target"], e["speed"])}, seconds=abs(e["target"]) / e["speed"] + 0.8)
            simm = metrics(s["samples"][e["motor"]], 0.0, e["target"], e["speed"])
            rows["speed"].append({"motor": e["motor"], "target": e["target"], "cmdSpeed": e["speed"],
                                  "real": real, "sim": simm})
        elif e["tag"] == "limit":
            if e.get("skipped"):
                rows["limits"].append({"motor": e["motor"], "target": e["target"], "skipped": e["skipped"],
                                       "simCollisions": e.get("simCollisions")})
                continue
            samples = r.get("samples") or []
            real = metrics(samples, r.get("startDeg", 0), e["target"], e["speed"])
            peak = max((abs(x[1]) for x in samples), default=None)
            rows["limits"].append({"motor": e["motor"], "target": e["target"], "commanded": r.get("targetDeg"),
                                   "real": real, "maxAbsDeg": peak, "simCollisions": e.get("simCollisions")})
        elif e["tag"].startswith("multi") and e["target"] != 0:
            rows["multi"].append({"group": int(e["tag"][5:]), "motor": e["motor"], "tSend": e["tSend"],
                                  "real": metrics(r.get("samples") or [], r.get("startDeg", 0),
                                                  r.get("targetDeg", e["target"]), e["speed"])})
    (d / "bench_report.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    # 轉速圖：實際平均轉速 vs 指令
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft JhengHei", "Microsoft YaHei", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    if rows["speed"]:
        fig, axes = plt.subplots(2, 5, figsize=(18, 7), sharex=True, sharey=True)
        for ax, m in zip(axes.ravel(), MOTOR_INFO):
            rs = [r for r in rows["speed"] if r["motor"] == m]
            cs = [r["cmdSpeed"] for r in rs]
            ax.plot([0, 210], [0, 210], color="#ccc", lw=1, ls="--", label="指令 = 實際")
            ax.plot(cs, [r["sim"].get("avgSpeed") or np.nan for r in rs], "o-", color="#2a7", label="模擬 平均")
            ax.plot(cs, [r["real"].get("avgSpeed") or np.nan for r in rs], "o-", color="#d40", label="實機 平均")
            ax.plot(cs, [r["real"].get("peakSpeed") or np.nan for r in rs], "^:", color="#d40", alpha=0.6,
                    label="實機 最高")
            ax.set_title(f"M{m} {MOTOR_INFO[m][0]}", fontsize=10)
            ax.grid(alpha=0.3)
        axes[0, 0].legend(fontsize=7)
        for ax in axes[1]:
            ax.set_xlabel("指令速度 (°/s)")
        for ax in axes[:, 0]:
            ax.set_ylabel("實際速度 (°/s)")
        fig.tight_layout()
        fig.savefig(d / "speed.png", dpi=100)
        plt.close(fig)

    # 限位：鏡頭截圖｜模擬同姿勢
    if raw["shots"] and (d / "camera.mp4").is_file():
        stamps = np.loadtxt(d / "frames.csv")
        cap = cv2.VideoCapture(str(d / "camera.mp4"))
        tiles = []
        for s in raw["shots"]:
            idx = int(np.clip(np.searchsorted(stamps, s["t"]), 0, len(stamps) - 1))
            cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
            ok, f = cap.read()
            f = cv2.resize(f, (400, 225)) if ok else np.zeros((225, 400, 3), np.uint8)
            simg = cv2.resize(sim.render({s["motor"]: s["target"]}), (225, 225))
            tile = np.hstack([f, simg])
            cv2.putText(tile, f"M{s['motor']} cmd {s['target']:+g} real {s['actual']}", (6, 20),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 255), 2)
            tiles.append(tile)
        cols = 3
        while len(tiles) % cols:
            tiles.append(np.zeros_like(tiles[0]))
        sheet = np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])
        cv2.imwrite(str(d / "limits.jpg"), sheet)

    # 文字摘要
    lines = ["# 轉速（平均 = 10%~90% 行程；模擬用同樣的指令）", "馬達 | 指令°/s | 實機平均 | 實機最高 | 模擬平均 | 實機開始動 ms | 實機到位 ms | 理論 ms"]
    for r in rows["speed"]:
        re_, si = r["real"], r["sim"]
        lines.append(f"M{r['motor']} | {r['cmdSpeed']} | {re_.get('avgSpeed')} | {re_.get('peakSpeed')} | "
                     f"{si.get('avgSpeed')} | {re_.get('firstMoveMs')} | {re_.get('reachMs')} | {re_.get('theoryMs')}")
    lines += ["", "# 限位（60°/s）", "馬達 | 指令 | 實際停在 | 誤差 | 過衝 | 模擬碰撞"]
    for r in rows["limits"]:
        if r.get("skipped"):
            lines.append(f"M{r['motor']} | {r['target']} | 跳過（{r['skipped']}）| | | {r.get('simCollisions')}")
        else:
            lines.append(f"M{r['motor']} | {r['target']} | {r['real'].get('finalDeg')} | {r['real'].get('finalErrDeg')} | "
                         f"{r['real'].get('overshootDeg')} | {r.get('simCollisions') or '無'}")
    lines += ["", "# 同時動（120°/s）", "幾顆 | 馬達 | 開始動 ms | 平均°/s | 到位 ms"]
    for r in rows["multi"]:
        lines.append(f"{r['group']} | M{r['motor']} | {r['real'].get('firstMoveMs')} | {r['real'].get('avgSpeed')} | "
                     f"{r['real'].get('reachMs')}")
    (d / "bench_summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"\n→ {d}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Kebbi motor bench vs PyBullet")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("host")
    r.add_argument("--only", nargs="*", choices=["speed", "limits", "multi"])
    r.add_argument("--motors", type=int, nargs="*")
    r.add_argument("--cam", type=int, default=0)
    r.add_argument("--no-camera", action="store_true")
    r.add_argument("--out", type=Path)
    p = sub.add_parser("report")
    p.add_argument("bench_dir", type=Path)
    a = ap.parse_args(argv)
    return run(a) if a.cmd == "run" else report(a)


if __name__ == "__main__":
    sys.exit(main())
