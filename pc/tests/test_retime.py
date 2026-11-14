"""retime.py 的測試：不需要機器人、影片或 PyBullet。

    python -m pytest pc/tests            # 或直接 python pc/tests/test_retime.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import retime  # noqa: E402
from retime import MOTORS, motor_model  # noqa: E402

MODEL = motor_model.load()


def synthetic_script(seconds: float = 20.0, interval_ms: int = 200, tempo: float = 1.0) -> dict:
    """仿 DanceScriptBuilder 的輸出：10 顆馬達各自做不同頻率的擺動，含停頓與快動作。"""
    lead = int(interval_ms * retime.REACH_FRACTION)
    waves = {1: (10, 0.5, 0), 2: (22, 0.4, 1), 3: (-35, 0.3, 2), 4: (-70, 0.6, 0), 5: (40, 0.5, 1),
             6: (-40, 0.8, 2), 7: (-35, 0.3, 3), 8: (-70, 0.6, 1.5), 9: (40, 0.5, 2.5), 10: (-40, 0.8, 0.5)}
    last, steps = {}, []
    for k in range(int(seconds * 1000 / interval_ms) + 1):
        t = k * interval_ms
        cmds = []
        for m, (amp, hz, phase) in waves.items():
            s = math.sin(2 * math.pi * hz * tempo * t / 1000 + phase)
            if 8000 <= t < 10000:       # 中間停 2 秒
                s = 0.0
            deg = round(amp * (0.5 + 0.5 * s), 1)
            if m in last:
                delta = abs(deg - last[m])
                if delta < 1.5:
                    continue
                speed = max(10.0, min(MOTORS[m][4], delta * 1000 / (interval_ms * retime.REACH_FRACTION)))
            else:
                speed = 60.0
            cmds.append({"motor": m, "deg": deg, "speed": round(speed, 1)})
            last[m] = deg
        if cmds:
            steps.append({"t": max(0, t - lead), "event": "", "cmds": cmds})
    return {"version": 1, "name": "synthetic", "frameIntervalMs": interval_ms,
            "durationMs": int(seconds * 1000) + interval_ms, "sourceFrameCount": len(steps),
            "skippedFrameCount": 0, "steps": steps, "faces": []}


def test_model_loaded():
    assert MODEL, "pc/sim/motor_response.json 應該存在"


def test_speed_inverse_roundtrip():
    for m in MOTORS:
        top = retime.max_actual_speed(MODEL, m)
        for want in (15.0, 40.0, 80.0, top * 0.9):
            cmd = retime.cmd_speed_for(MODEL, m, want)
            got = motor_model.actual_speed(MODEL, m, cmd)
            assert cmd <= MOTORS[m][4]
            # 低速區馬達轉得比指令快、又有「推不動」的下限，所以只要求不比需要的慢太多
            assert got >= min(want, top) - 1.0, (m, want, cmd, got)


def test_retimed_script_is_valid():
    raw = synthetic_script()
    new, stats = retime.retime(raw, MODEL)
    assert new["planner"] == retime.PLANNER_TAG
    assert new["durationMs"] == raw["durationMs"] and new["frameIntervalMs"] == raw["frameIntervalMs"]
    ts = [s["t"] for s in new["steps"]]
    assert ts == sorted(ts) and len(set(ts)) == len(ts) and ts[0] == 0
    for s in new["steps"]:
        assert s["cmds"]
        for c in s["cmds"]:
            lo, hi = retime.limits_of(c["motor"])
            assert min(lo, 0) - 1e-6 <= c["deg"] <= hi + 1e-6, c
            assert 0 < c["speed"] <= MOTORS[c["motor"]][4], c
    # 第一步是完整的開場姿勢（播放器先擺好才開始計時）
    assert {c["motor"] for c in new["steps"][0]["cmds"]} == set(MOTORS)
    assert stats["cmds"] > 0


def test_refuses_to_retime_twice():
    new, _ = retime.retime(synthetic_script(5), MODEL)
    try:
        retime.retime(new, MODEL)
    except ValueError:
        return
    raise AssertionError("重排過的腳本不應該再排一次")


def test_retime_is_closer_to_the_video():
    for tempo in (0.6, 1.0, 1.5):
        raw = synthetic_script(tempo=tempo)
        before = retime.evaluate(raw, raw, MODEL)
        after = retime.evaluate(raw, retime.retime(raw, MODEL)[0], MODEL)
        assert abs(after["lagMs"]) <= 40, (tempo, after["lagMs"])
        assert abs(after["lagMs"]) <= abs(before["lagMs"]), (tempo, before["lagMs"], after["lagMs"])
        assert after["meanErrDeg"] <= before["meanErrDeg"], (tempo, before["meanErrDeg"], after["meanErrDeg"])


def test_limits_are_applied():
    raw = synthetic_script(3)
    raw["steps"][0]["cmds"] = [{"motor": 4, "deg": 70.0, "speed": 60.0}, {"motor": 9, "deg": 100.0, "speed": 60.0}]
    new, _ = retime.retime(raw, MODEL)
    first = {c["motor"]: c["deg"] for c in new["steps"][0]["cmds"]}
    assert first[4] == 28 and first[9] == 81


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
