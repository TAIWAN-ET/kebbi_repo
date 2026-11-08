"""實機馬達反應模型：ctlMotor 指令 →（起動延遲後）以「實測的實際轉速」轉過去。

    python motor_model.py fit ../out/motor_bench_run1/bench_raw.json     # 由 motor_bench 實測資料產生 motor_response.json

模型（每顆馬達）：
  delayMs      靜止時下指令到開始動的時間（實測中位數）；連續下指令時改用 MOVING_DELAY_MS
  speedTable   [[指令 °/s, 實際平均 °/s], ...]，中間線性內插；實際平均 = 行程 10%~80% 的平均速度
  stallBelow   指令低於這個速度時馬達推不動（側舉慢速抬不起來），模擬裡不動

資料來源：pc/motor_bench.py 在實機上用 /motor-test 量的角度曲線（App 端每 10ms 讀 getMotorPresentPositionInDegree）。
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RESPONSE_FILE = HERE / "motor_response.json"


def load() -> dict | None:
    if not RESPONSE_FILE.is_file():
        return None
    data = json.loads(RESPONSE_FILE.read_text(encoding="utf-8"))
    return {int(k): v for k, v in data["motors"].items()}


def actual_speed(model: dict, motor: int, cmd_speed: float) -> float:
    m = model.get(motor)
    if not m:
        return cmd_speed
    if cmd_speed < m.get("stallBelow", 0):
        return 0.0
    table = np.array(m["speedTable"], dtype=float)
    if cmd_speed <= table[0, 0]:
        return float(cmd_speed * table[0, 1] / table[0, 0])
    return float(np.interp(cmd_speed, table[:, 0], table[:, 1]))


# 連續下指令時（上一個指令在這段時間內）延遲短很多：跳舞腳本每 200ms 一步，鏡頭量到的實機只比「無延遲」晚約 30ms，
# 套靜止起動的 ~170ms 反而比實機晚 130ms。由 dance_test_20s 鏡頭資料掃出來（camera_check.py，見 MOVING_DELAY_MS）。
MOVING_WINDOW_S = 0.4
MOVING_DELAY_MS = 35


def delay_s(model: dict, motor: int, since_last_cmd_s: float | None = None) -> float:
    """since_last_cmd_s：距離這顆馬達上一個指令多久（None = 第一個指令 / 靜止起動）。"""
    m = model.get(motor)
    if not m:
        return 0.0
    if since_last_cmd_s is not None and since_last_cmd_s < MOVING_WINDOW_S:
        return m.get("movingDelayMs", MOVING_DELAY_MS) / 1000.0
    return m["delayMs"] / 1000.0


def _avg_speed(samples, start: float, target: float) -> tuple[float | None, int | None, float]:
    s = np.array(samples, dtype=float)
    travel = target - start
    frac = (s[:, 1] - start) / travel
    first = np.argmax(np.abs(s[:, 1] - start) >= 1) if (np.abs(s[:, 1] - start) >= 1).any() else None
    reached = float(frac[-1])
    if not ((frac >= 0.1).any() and (frac >= 0.8).any()):
        return None, None if first is None else int(s[first, 0]), reached
    i10, i80 = np.argmax(frac >= 0.1), np.argmax(frac >= 0.8)
    if i80 <= i10:
        return None, None if first is None else int(s[first, 0]), reached
    v = 0.7 * abs(travel) / ((s[i80, 0] - s[i10, 0]) / 1000)
    return v, None if first is None else int(s[first, 0]), reached


def fit(raw_path: Path) -> dict:
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    per = {}
    for e in raw["log"]:
        if e["tag"] not in ("speed", "limit", "limit-back") or "result" not in e:
            continue
        r = e["result"]
        if not r.get("samples") or abs(r.get("targetDeg", 0) - r.get("startDeg", 0)) < 8:
            continue
        v, first, reached = _avg_speed(r["samples"], r["startDeg"], r["targetDeg"])
        d = per.setdefault(e["motor"], {"delays": [], "speeds": {}, "stall": []})
        if first is not None and reached > 0.3:
            d["delays"].append(first)
        if e["tag"] == "speed":
            if reached < 0.3:
                d["stall"].append(e["speed"])
            elif v is not None:
                d["speeds"].setdefault(e["speed"], []).append(v)
    motors = {}
    for m, d in sorted(per.items()):
        table = [[sp, round(statistics.median(vs), 1)] for sp, vs in sorted(d["speeds"].items())]
        # 實際速度不會因為指令變大而變慢：取累積最大，避免單次雜訊讓曲線往下彎
        best = 0.0
        for row in table:
            best = max(best, row[1])
            row[1] = best
        motors[str(m)] = {"delayMs": int(statistics.median(d["delays"])) if d["delays"] else 0,
                          "speedTable": table,
                          "stallBelow": (max(d["stall"]) + 1) if d["stall"] else 0}
    return {"source": str(raw_path), "note": "pc/motor_bench.py 實機量測（/motor-test）", "motors": motors}


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fit":
        out = fit(Path(sys.argv[2]))
        RESPONSE_FILE.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
        for m, v in out["motors"].items():
            print(f"M{m}: 延遲 {v['delayMs']} ms，轉速 {v['speedTable']}，" +
                  (f"低於 {v['stallBelow']}°/s 推不動" if v["stallBelow"] else ""))
        print("wrote", RESPONSE_FILE)
    else:
        print(__doc__)
