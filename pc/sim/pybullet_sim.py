"""用 PyBullet 播放 dance_script.json，檢查動作在物理上可不可行。

    python pybullet_sim.py ../out/rtmlib_demo/dance_script.json            # 無視窗，輸出 sim_result.json
    python pybullet_sim.py ../out/rtmlib_demo/dance_script.json --gui      # 開 PyBullet 視窗即時播放

做了什麼：
1. build_urdf.py 由 robot.xml 產生 URDF（10 顆馬達 revolute，其餘 fixed，套官方角度範圍）
2. 依腳本時間送位置控制（目標角度 + 腳本指定的速度上限，力矩上限 TORQUE_LIMIT）
3. 每 1/30 秒記錄實際角度，並偵測「自碰撞」（排除站姿本來就接觸的零件對）
4. 輸出：追蹤誤差、超過馬達速度上限的指令數、新出現的碰撞

限制：質量、力矩上限是估計值（官方沒公開）；碰撞用凸包近似，凹形零件（手掌與身體之間的空隙）會偏保守。
"""

from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import pybullet as p

from build_urdf import JOINT_COLORS, JOINT_TO_MOTOR, MOTORS, build, color_key

HERE = Path(__file__).resolve().parent
DT = 1.0 / 240.0
LOG_DT = 1.0 / 30.0
TORQUE_LIMIT = 5.0          # N·m，估計值
PENETRATION_M = 0.003       # 穿透超過 3 mm 才算碰撞


def load_robot(gui: bool):
    p.connect(p.GUI if gui else p.DIRECT)
    p.setGravity(0, 0, -9.8)
    p.setTimeStep(DT)
    urdf = build()
    # 動力學不開自碰撞：整個身體的凸包會把手臂包在裡面，一開始就互相推擠而炸掉。
    # 碰撞改用 getClosestPoints 另外檢測（不影響運動）。
    robot = p.loadURDF(str(urdf), useFixedBase=True)
    joints, names = {}, {}
    for i in range(p.getNumJoints(robot)):
        info = p.getJointInfo(robot, i)
        jn, link = info[1].decode(), info[12].decode()
        names[i] = link
        if jn in JOINT_TO_MOTOR:
            joints[JOINT_TO_MOTOR[jn]] = i
    if gui:
        decorate_gui(robot, joints)
    return robot, joints, names


def decorate_gui(robot, joints):
    """GUI：每顆馬達的轉軸畫成同色線（跟著零件動），旁邊放圖例。"""
    p.configureDebugVisualizer(p.COV_ENABLE_GUI, 0)
    p.resetDebugVisualizerCamera(0.7, 35, -15, [0.05, 0, 0.2])
    for m, j in joints.items():
        info = p.getJointInfo(robot, j)
        a = info[13]
        c = JOINT_COLORS[color_key(info[1].decode())]
        p.addUserDebugLine([-0.05 * v for v in a], [0.05 * v for v in a], c, 4,
                           parentObjectUniqueId=robot, parentLinkIndex=j)
    legend = [("neck_y (nod)", "neck_y"), ("neck_z (turn)", "neck_z"), ("shoulder_z (yaw)", "shoulder_z"),
              ("shoulder_y (fwd/up)", "shoulder_y"), ("shoulder_x (side)", "shoulder_x"), ("elbow_y", "elbow_y")]
    for i, (text, key) in enumerate(legend):
        p.addUserDebugText(text, [0.0, 0.32, 0.42 - 0.04 * i], JOINT_COLORS[key], 1.4)


def group_of(link: str) -> str:
    if link.startswith("left_"):
        return "L"
    if link.startswith("right_"):
        return "R"
    if link.startswith(("face", "head", "neck", "led_head", "sensor_")):
        return "H"
    return "B"


def candidate_pairs(robot, names):
    """有網格的零件中，屬於不同群組（身體/頭/左臂/右臂）的配對。"""
    meshes = [i for i in names if p.getCollisionShapeData(robot, i)]
    return [(a, b) for k, a in enumerate(meshes) for b in meshes[k + 1:]
            if group_of(names[a]) != group_of(names[b])]


def contact_pairs(robot, names, pairs, min_depth=0.0):
    out = {}
    for a, b in pairs:
        for c in p.getClosestPoints(robot, robot, 0.0, linkIndexA=a, linkIndexB=b):
            depth = -c[8]
            if depth >= min_depth:
                key = tuple(sorted((names[a], names[b])))
                out[key] = max(out.get(key, 0.0), depth)
    return out


def run(script_path: Path, gui: bool, out_path: Path) -> dict:
    script = json.loads(script_path.read_text(encoding="utf-8"))
    robot, joints, names = load_robot(gui)

    def deg_to_rad(motor, deg):
        return math.radians(deg) * MOTORS[motor][3]

    def rad_to_deg(motor, rad):
        return math.degrees(rad) * MOTORS[motor][3]

    for j in joints.values():
        p.resetJointState(robot, j, 0.0)
    p.stepSimulation()
    pairs = candidate_pairs(robot, names)
    baseline = set(contact_pairs(robot, names, pairs))  # 站姿本來就接觸的零件對

    steps = sorted(script["steps"], key=lambda s: s["t"])
    duration = script["durationMs"] / 1000.0
    target = {m: 0.0 for m in joints}
    frames, err_sum, err_max, err_n = [], {m: 0.0 for m in joints}, {m: 0.0 for m in joints}, 0
    over_speed = {}
    collisions = {}
    si, t, next_log = 0, 0.0, 0.0
    t0 = time.time()

    while t <= duration + 1.0:
        while si < len(steps) and steps[si]["t"] / 1000.0 <= t:
            for c in steps[si]["cmds"]:
                m = c["motor"]
                if m not in joints:
                    continue
                target[m] = c["deg"]
                limit = MOTORS[m][4]
                if c["speed"] > limit:
                    over_speed[m] = over_speed.get(m, 0) + 1
                p.setJointMotorControl2(robot, joints[m], p.POSITION_CONTROL,
                                        targetPosition=deg_to_rad(m, c["deg"]),
                                        force=TORQUE_LIMIT,
                                        maxVelocity=math.radians(min(c["speed"], limit)))
            si += 1
        p.stepSimulation()
        if gui:
            time.sleep(max(0.0, t - (time.time() - t0)))
        t += DT
        if t >= next_log:
            next_log += LOG_DT
            actual = {m: rad_to_deg(m, p.getJointState(robot, j)[0]) for m, j in joints.items()}
            frames.append({"t": round(t * 1000), "actual": {str(m): round(a, 2) for m, a in actual.items()}})
            for m in joints:
                e = abs(actual[m] - target[m])
                err_sum[m] += e
                err_max[m] = max(err_max[m], e)
            err_n += 1
            for pair, depth in contact_pairs(robot, names, pairs, PENETRATION_M).items():
                if pair in baseline:
                    continue
                d = collisions.setdefault(pair, {"frames": 0, "max_depth_mm": 0.0, "first_ms": round(t * 1000)})
                d["frames"] += 1
                d["max_depth_mm"] = max(d["max_depth_mm"], round(depth * 1000, 1))

    p.disconnect()
    result = {
        "script": script.get("name"), "duration_ms": script["durationMs"],
        "tracking": {MOTORS[m][0]: {"motor": m, "mean_abs_err_deg": round(err_sum[m] / max(1, err_n), 2),
                                    "max_err_deg": round(err_max[m], 1)} for m in joints},
        "commands_over_velocity_limit": {MOTORS[m][0]: n for m, n in over_speed.items()},
        "new_collisions": [{"pair": list(k), **v} for k, v in sorted(collisions.items(), key=lambda kv: -kv[1]["frames"])],
        "baseline_contact_pairs": len(baseline),
        "torque_limit_nm": TORQUE_LIMIT,
        "frames": frames,
    }
    out_path.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return result


def main() -> None:
    ap = argparse.ArgumentParser(description="PyBullet 播放 dance_script.json")
    ap.add_argument("script", type=Path)
    ap.add_argument("--gui", action="store_true")
    ap.add_argument("--out", type=Path, default=HERE / "sim_result.json")
    a = ap.parse_args()
    r = run(a.script, a.gui, a.out)
    print(f"simulated {r['duration_ms'] / 1000:.1f}s, frames={len(r['frames'])}")
    for name, s in r["tracking"].items():
        print(f"  {name:18s} mean err {s['mean_abs_err_deg']:6.2f}°  max {s['max_err_deg']:6.1f}°")
    print("over velocity limit:", r["commands_over_velocity_limit"] or "none")
    print(f"new collisions: {len(r['new_collisions'])} (baseline pairs ignored: {r['baseline_contact_pairs']})")
    for c in r["new_collisions"][:8]:
        print(f"  {c['pair'][0]} x {c['pair'][1]}: {c['frames']} frames, max {c['max_depth_mm']} mm, first {c['first_ms']} ms")
    print("wrote", a.out)


if __name__ == "__main__":
    main()
