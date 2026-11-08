"""測 Kebbi 內建標準動作（666_BA_*）與 10 顆同時動，和 PyBullet 模擬對照。

    python motion_bench.py run 192.168.50.143                    # 會轉動馬達
    python motion_bench.py run 192.168.50.143 --match RArm Look  # 只跑名稱含這些字的
    python motion_bench.py report out/motion_bench_<時間>        # 不動馬達，重算報告

需要新版 Kebbi Link（/motions、/motion、/play?trace=1）。
會讓機器人走動或轉身的動作（Move / Turn / Patrol）預設不跑，避免從桌上掉下去。

每個動作量：
  - 每顆馬達的角度範圍、最高轉速（跨 60ms 的斜率）
  - 同一時間最多幾顆在動（轉速 > 15°/s）
  - 有沒有超出 App 的限位（RobotMapper）與實測到得了的範圍（motor_bench 的結果）
  - 把實機軌跡丟進模擬：模擬的關節限位會不會截掉、會不會自碰撞
10 顆同時動：一段腳本讓 10 顆同時轉，/trace 記錄實機角度，和模擬同一段腳本逐顆對照。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

from kebbi_link import KebbiError, KebbiLink
from motor_bench import MOTOR_INFO, Sim

HERE = Path(__file__).resolve().parent
SKIP_WORDS = ("Move", "Turn", "Patrol")
# motor_bench 實測：指令轉得到、但實機到不了的範圍（肩前後 +70 只到 ~28 等）
MEASURED = {1: (None, 17.8), 4: (-200, 28.0), 5: (5.1, 79.7), 8: (-200, 27.0), 9: (2.8, 77.8)}
MOVING_SPEED = 15.0


def speed_series(t_ms: np.ndarray, d: np.ndarray, win_ms: float = 60) -> np.ndarray:
    """每個取樣點往後 win_ms 的平均角速度（°/s）。"""
    out = np.zeros(len(t_ms))
    for i in range(len(t_ms)):
        j = np.searchsorted(t_ms, t_ms[i] + win_ms)
        if j < len(t_ms) and not np.isnan(d[i]) and not np.isnan(d[j]):
            out[i] = abs(d[j] - d[i]) / ((t_ms[j] - t_ms[i]) / 1000)
    return out


def analyze_samples(samples: list, motors: list[int]) -> dict:
    s = np.array([[np.nan if v is None else v for v in row] for row in samples], dtype=float)
    if len(s) < 3:
        return {"motors": {}, "maxConcurrent": 0}
    t = s[:, 0]
    res, moving = {}, []
    for k, m in enumerate(motors):
        d = s[:, k + 1]
        if np.all(np.isnan(d)):
            continue
        sp = speed_series(t, d)
        moving.append(sp > MOVING_SPEED)
        lo, hi = float(np.nanmin(d)), float(np.nanmax(d))
        app_lo, app_hi = MOTOR_INFO[m][1], MOTOR_INFO[m][2]
        r = {"min": round(lo, 1), "max": round(hi, 1), "range": round(hi - lo, 1), "peakSpeed": round(float(sp.max()), 1)}
        if lo < app_lo - 1 or hi > app_hi + 1:
            r["outsideAppLimit"] = True
        mlo, mhi = MEASURED.get(m, (None, None))
        if (mlo is not None and lo < mlo - 2) or (mhi is not None and hi > mhi + 2):
            r["outsideMeasured"] = True
        res[m] = r
    conc = np.sum(moving, axis=0) if moving else np.zeros(1)
    active = [m for m, r in res.items() if r["range"] > 3]
    return {"motors": res, "active": active, "maxConcurrent": int(conc.max()),
            "durationMs": int(t[-1] - t[0])}


def sim_replay(sim: Sim, samples: list, motors: list[int]) -> dict:
    """實機軌跡當目標丟進模擬：看模擬的關節限位會不會截掉、會不會自碰撞。"""
    p = sim.p
    s = np.array([[np.nan if v is None else v for v in row] for row in samples], dtype=float)
    start = {m: float(s[0, k + 1]) for k, m in enumerate(motors) if not np.isnan(s[0, k + 1])}
    sim.reset(start)
    worst, clip = {}, {}
    t_end = s[-1, 0] / 1000
    t = 0.0
    k = 0
    from build_urdf import HW_MAX_VEL
    cols = {m: (s[:, 0][~np.isnan(s[:, i + 1])], s[:, i + 1][~np.isnan(s[:, i + 1])]) for i, m in enumerate(motors)}
    while t <= t_end:
        # 取樣點之間線性內插，不然 30ms 一階的目標本身就會造成 300°/s × 30ms ≈ 10° 的假誤差
        row = [t * 1000] + [float(np.interp(t * 1000, *cols[m])) if len(cols[m][0]) else np.nan for m in motors]
        for i, m in enumerate(motors):
            if not np.isnan(row[i + 1]):
                p.setJointMotorControl2(sim.robot, sim.joints[m], p.POSITION_CONTROL,
                                        targetPosition=sim.rad(m, row[i + 1]), force=50,
                                        maxVelocity=np.radians(HW_MAX_VEL[m]))
        p.stepSimulation()
        if k % 8 == 0:
            for i, m in enumerate(motors):
                if not np.isnan(row[i + 1]):
                    err = abs(sim.deg(m) - row[i + 1])
                    clip[m] = max(clip.get(m, 0.0), err)
            for pair, depth in sim._contact(sim.robot, sim.names, sim.pairs, 0.003).items():
                if pair not in sim.baseline:
                    key = " x ".join(pair)
                    worst[key] = max(worst.get(key, 0), round(depth * 1000, 1))
        t += sim.DT
        k += 1
    return {"collisions": worst, "maxTrackErr": {m: round(v, 1) for m, v in clip.items() if v > 3}}


def all10_script() -> dict:
    out = [{"motor": m, "deg": float(MOTOR_INFO[m][3]) * (0.5 if m in (4, 8) else 1.0), "speed": 120.0}
           for m in MOTOR_INFO]
    back = [{"motor": m, "deg": 0.0, "speed": 120.0} for m in MOTOR_INFO]
    return {"version": 1, "name": "bench_all10", "frameIntervalMs": 200, "durationMs": 3000,
            "sourceFrameCount": 2, "skippedFrameCount": 0,
            "steps": [{"t": 0, "event": "", "cmds": out}, {"t": 1500, "event": "", "cmds": back}], "faces": []}


def run(args) -> int:
    from camera_check import Camera
    out = args.out or (HERE / "out" / time.strftime("motion_bench_%Y%m%d_%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    link = KebbiLink.connect(None if args.host == "auto" else args.host)
    names = [n for n in link.motions() if n.startswith(args.prefix)]
    names = sorted(n for n in names if not any(w in n for w in SKIP_WORDS))
    if args.match:
        names = [n for n in names if any(w in n for w in args.match)]
    print(f"要跑 {len(names)} 個動作：{', '.join(names)}")
    cam = None if args.no_camera else Camera(args.cam, out, 1280, 720)
    if cam:
        cam.start()
    raw = {"host": link.host, "motions": [], "all10": None}
    try:
        link.home()
        time.sleep(2.5)
        clock = link.sync_clock()
        for i, name in enumerate(names, 1):
            t0 = time.time()
            r = link.play_motion(name)
            raw["motions"].append({"name": name, "tSend": t0, "result": r})
            a = analyze_samples(r.get("samples") or [], r.get("motors") or list(range(1, 11)))
            print(f"[{i}/{len(names)}] {name}: {a.get('durationMs')} ms，動到 {a.get('active')}，"
                  f"同時最多 {a['maxConcurrent']} 顆")
            link.home()
            time.sleep(1.5)
        if not args.no_all10:
            print("10 顆同時動（腳本 + trace）")
            link.upload(json.dumps(all10_script()).encode("utf-8"))
            clock = link.sync_clock()
            start = time.time() + 2.0
            link.play_at(start, trace=True)
            time.sleep(6.5)
            tr = link.trace()
            raw["all10"] = {"start": start, "clock": clock.__dict__, "trace": tr}
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
        (out / "motion_raw.json").write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")
    return report(argparse.Namespace(bench_dir=out))


def report(args) -> int:
    d = args.bench_dir
    raw = json.loads((d / "motion_raw.json").read_text(encoding="utf-8"))
    sim = Sim()
    rows = []
    for e in raw["motions"]:
        r = e["result"]
        motors = r.get("motors") or list(range(1, 11))
        a = analyze_samples(r.get("samples") or [], motors)
        a["sim"] = sim_replay(sim, r.get("samples") or [], motors) if r.get("samples") else {}
        rows.append({"name": e["name"], "aborted": r.get("aborted"), **a})

    all10 = None
    if raw.get("all10") and raw["all10"]["trace"].get("samples"):
        tr = raw["all10"]["trace"]
        motors = tr.get("motors") or list(range(1, 11))
        offset = raw["all10"]["clock"]["offset_ms"]
        start = raw["all10"]["start"]
        s = np.array([[np.nan if v is None else v for v in row] for row in tr["samples"]], dtype=float)
        t_rel = ((s[:, 0] - offset) / 1000 - start) * 1000   # 腳本時間（ms）
        # 播放器在預備時間就先把馬達擺到第一步的姿勢，所以第一步不算；量第二步（t=1500，10 顆同時回 0）
        script = all10_script()
        pose = {c["motor"]: c["deg"] for c in script["steps"][0]["cmds"]}
        step_t = script["steps"][1]["t"]
        cmds = {c["motor"]: (c["deg"], c["speed"]) for c in script["steps"][1]["cmds"]}
        import motor_model
        simr = sim.move(cmds, start=pose, seconds=1.5, model=motor_model.load())
        all10 = {"motors": {}, "stepMs": step_t}
        t_rel = t_rel - step_t
        for k, m in enumerate(motors):
            d_ = s[:, k + 1]
            target = cmds[m][0]
            seg = (t_rel >= -100) & (t_rel < 1500)
            if not seg.any() or np.all(np.isnan(d_[seg])):
                continue
            dd, tt = d_[seg], t_rel[seg]
            start_deg = dd[0]
            moved = np.abs(dd - start_deg) >= 1
            first = float(tt[np.argmax(moved)]) if moved.any() else None
            near = np.abs(dd - target) <= 2
            reach = float(tt[np.argmax(near)]) if near.any() else None
            sm = np.array(simr["samples"][m])
            sim_reach = sm[np.argmax(np.abs(sm[:, 1] - target) <= 2), 0] if (np.abs(sm[:, 1] - target) <= 2).any() else None
            all10["motors"][m] = {"target": target, "firstMoveMs": first, "reachMs": reach,
                                  "final": round(float(np.nanmedian(dd[-5:])), 1),
                                  "peakSpeed": round(float(speed_series(tt, dd).max()), 1),
                                  "simReachMs": None if sim_reach is None else round(float(sim_reach))}
        moving = []
        for k, m in enumerate(motors):
            moving.append(speed_series(t_rel, s[:, k + 1]) > MOVING_SPEED)
        all10["maxConcurrent"] = int(np.sum(moving, axis=0).max())
        all10["sampleIntervalMs"] = round(float(np.median(np.diff(s[:, 0]))), 1)

    (d / "motion_report.json").write_text(json.dumps({"motions": rows, "all10": all10}, ensure_ascii=False,
                                                     indent=2, default=float), encoding="utf-8")
    lines = ["# 標準動作（實機 /motion 取樣）",
             "動作 | 時間 ms | 動到的馬達 | 同時最多 | 最快 °/s（馬達） | 超出 App 限位 | 超出實測範圍 | 模擬碰撞 | 模擬限位截掉"]
    for r in rows:
        ms = r["motors"]
        fastest = max(ms.items(), key=lambda kv: kv[1]["peakSpeed"]) if ms else None
        out_app = [f"M{m}({v['min']}~{v['max']})" for m, v in ms.items() if v.get("outsideAppLimit")]
        out_meas = [f"M{m}({v['min']}~{v['max']})" for m, v in ms.items() if v.get("outsideMeasured")]
        simc = r.get("sim", {}).get("collisions") or {}
        simclip = r.get("sim", {}).get("maxTrackErr") or {}
        lines.append(f"{r['name']} | {r.get('durationMs')} | {','.join(f'M{m}' for m in r.get('active', []))} | "
                     f"{r['maxConcurrent']} | {fastest[1]['peakSpeed'] if fastest else '-'}"
                     f"{'（M%d）' % fastest[0] if fastest else ''} | {' '.join(out_app) or '-'} | "
                     f"{' '.join(out_meas) or '-'} | {', '.join(f'{k} {v}mm' for k, v in simc.items()) or '-'} | "
                     f"{', '.join(f'M{k} {v}°' for k, v in simclip.items()) or '-'}")
    if all10:
        lines += ["", f"# 10 顆同時動（腳本 + trace，取樣間隔約 {all10['sampleIntervalMs']} ms，"
                      f"同時最多 {all10['maxConcurrent']} 顆在動）",
                  "馬達 | 目標 | 實際停在 | 開始動 ms | 到位 ms | 模擬到位 ms | 最快 °/s"]
        for m, v in all10["motors"].items():
            lines.append(f"M{m} | {v['target']} | {v['final']} | {v['firstMoveMs']} | {v['reachMs']} | "
                         f"{v['simReachMs']} | {v['peakSpeed']}")
    (d / "motion_summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    plot(d, rows)
    return 0


def plot(d: Path, rows: list) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft JhengHei", "Microsoft YaHei", "DejaVu Sans"]
    if not rows:
        return
    motors = list(MOTOR_INFO)
    rng = np.array([[r["motors"].get(m, {}).get("range", 0) for m in motors] for r in rows])
    spd = np.array([[r["motors"].get(m, {}).get("peakSpeed", 0) for m in motors] for r in rows])
    fig, axes = plt.subplots(1, 2, figsize=(16, max(4, 0.32 * len(rows) + 1.5)))
    for ax, data, title, unit in ((axes[0], rng, "角度範圍", "°"), (axes[1], spd, "最快轉速", "°/s")):
        im = ax.imshow(data, aspect="auto", cmap="viridis")
        ax.set_xticks(range(len(motors)), [f"M{m}\n{MOTOR_INFO[m][0].split()[0]}" for m in motors], fontsize=7)
        ax.set_yticks(range(len(rows)), [r["name"].replace("666_BA_", "") for r in rows], fontsize=7)
        for i in range(data.shape[0]):
            for j in range(data.shape[1]):
                if data[i, j] > 3:
                    ax.text(j, i, f"{data[i, j]:.0f}", ha="center", va="center", fontsize=6,
                            color="w" if data[i, j] < data.max() * 0.6 else "k")
        ax.set_title(f"{title}（{unit}）")
        fig.colorbar(im, ax=ax, fraction=0.03)
    fig.tight_layout()
    fig.savefig(d / "motions.png", dpi=110)
    plt.close(fig)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Kebbi built-in motion bench vs PyBullet")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("host")
    r.add_argument("--prefix", default="666_BA_")
    r.add_argument("--match", nargs="*")
    r.add_argument("--no-all10", action="store_true")
    r.add_argument("--cam", type=int, default=0)
    r.add_argument("--no-camera", action="store_true")
    r.add_argument("--out", type=Path)
    p = sub.add_parser("report")
    p.add_argument("bench_dir", type=Path)
    a = ap.parse_args(argv)
    return run(a) if a.cmd == "run" else report(a)


if __name__ == "__main__":
    sys.exit(main())
