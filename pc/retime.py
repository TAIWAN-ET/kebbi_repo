"""依實機馬達反應重排 dance_script.json 的送出時間與速度，讓動作和影片對得上。

    python retime.py out/<影片>/dance_script.json                  # 輸出 dance_script.retimed.json，並印出新舊比較
    python retime.py in.json --out out.json --lead-ms 20           # 實機看起來還是慢時，整體再提早 20ms
    python retime.py in.json --eval-only                           # 只評估，不輸出

為什麼需要：DanceScriptBuilder（Java）假設馬達「一收到指令就照指定速度轉」，每一步固定提前 170ms、
速度 = 角度差 ÷ 170ms。實機量到的不是這樣（pc/sim/motor_response.json，由 motor_bench.py 實測）：
  - 靜止時下指令要 145~210ms 才開始動；連續下指令時約 35ms
  - 指令速度 ≠ 實際轉速（例如頭上下指令 200°/s 實際只有 69°/s）
  - 有幾顆馬達的實機限位比官方範圍小
所以這裡逐顆馬達重新規劃：

  1. 從原腳本還原「影片在什麼時間、要到什麼角度」（關鍵點）。
  2. 每一段動作讓機器人和舞者在同一個時間窗內轉動：
       送出時間 = 動作開始時間 − 這顆馬達的起動延遲
       指令速度 = 由「需要的實際轉速」反查實測轉速表
  3. 馬達最快也來不及時，把動作置中在舞者的動作時間上（早一點開始、晚一點結束，而不是整段晚到）；
     下一個動作要反方向而且還是來不及，就縮小幅度。
  4. 角度夾在實機到得了的範圍（build_urdf.REAL_LIMITS）。

輸出仍是同一個 JSON 格式（version 1），App 不用改；只是每顆馬達有自己的送出時間，所以步數會變多。
腳本裡多一個 "planner" 欄位標記已經重排過（重排過的腳本不會再排一次）。

只用到 numpy（motor_model 需要）。評估用的是「延遲＋實測轉速」的運動學模型，不是 PyBullet，也不是實機。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent / "sim"))
import motor_model  # noqa: E402
from build_urdf import MOTORS, REAL_LIMITS  # noqa: E402

PLANNER_TAG = "retime-v1"

# 與 DanceScriptBuilder.REACH_FRACTION 相同：原腳本每一步提前「取樣間隔 × 這個比例」送出
REACH_FRACTION = 0.85
# 指令速度下限（°/s），與 DanceScriptBuilder.MIN_SPEED_DEG_PER_SEC 相同
MIN_CMD_SPEED = 10.0
# 和預測位置差不到這個角度就不送（度）
DEAD_BAND_DEG = 1.0
# 模擬 / 評估的時間解析度（秒）
SIM_DT = 0.005


# ---------------------------------------------------------------------------
# 馬達模型的小工具
# ---------------------------------------------------------------------------

def limits_of(motor: int) -> tuple[float, float]:
    """實機到得了的角度範圍（沒有實測的沿用官方範圍）。"""
    _, lo, hi, _, _ = MOTORS[motor]
    return REAL_LIMITS.get(motor, (lo, hi))


def clamp_deg(motor: int, deg: float) -> float:
    if motor not in MOTORS:
        return deg
    lo, hi = limits_of(motor)
    # 官方下限是負的小角度、實機到不了（例如側舉 -3 → 實際停在 3~5）時，0 仍是合法的休息姿勢
    lo = min(lo, 0.0) if lo > 0 else lo
    return max(lo, min(hi, deg))


def cmd_cap(motor: int) -> float:
    """ctlMotor 能給的最大指令速度（°/s）。"""
    return float(MOTORS[motor][4]) if motor in MOTORS else 200.0


def max_actual_speed(model: dict | None, motor: int) -> float:
    cap = cmd_cap(motor)
    return cap if not model else motor_model.actual_speed(model, motor, cap)


def cmd_speed_for(model: dict | None, motor: int, actual: float) -> float:
    """要馬達「實際」轉這麼快，指令速度該給多少（實測轉速表的反查，取做得到的最小指令）。"""
    cap = cmd_cap(motor)
    m = (model or {}).get(motor)
    if not m:
        return max(MIN_CMD_SPEED, min(cap, actual))
    table = [(float(c), float(a)) for c, a in m["speedTable"] if c <= cap]
    floor = max(MIN_CMD_SPEED, float(m.get("stallBelow", 0)))
    top = max(a for _, a in table)
    c0, a0 = table[0]
    if actual >= top:
        # 表的尾段是平的（再加大指令也不會更快）：取最先到達最高轉速的指令
        cmd = next(c for c, a in table if a >= top)
    elif actual <= a0:
        cmd = actual * c0 / a0 if a0 > 0 else c0
    else:
        cmd = table[-1][0]
        for (c1, a1), (c2, a2) in zip(table, table[1:]):
            if a1 < actual <= a2:
                cmd = c1 + (actual - a1) * (c2 - c1) / (a2 - a1)
                break
    return round(max(floor, min(cap, cmd)), 1)


def start_delay_s(model: dict | None, motor: int, since_last_s: float | None) -> float:
    return 0.0 if not model else motor_model.delay_s(model, motor, since_last_s)


# ---------------------------------------------------------------------------
# 運動學模擬：指令 →（延遲後）以實際轉速轉向目標
# ---------------------------------------------------------------------------

class MotorTrack:
    """一顆馬達的指令序列與預測位置。"""

    def __init__(self, motor: int, model: dict | None):
        self.motor = motor
        self.model = model
        self.cmds: list[tuple[float, float, float]] = []   # (送出時間 s, 目標角度, 指令速度)
        self._events: list[tuple[float, float, float]] = []  # (生效時間 s, 目標角度, 實際轉速)
        self.start_pos: float | None = None

    def preset(self, deg: float) -> None:
        """預備時間就先擺好的姿勢（播放器先送第一步、等 1.5 秒才開始計時）。"""
        self.start_pos = deg

    def add(self, send_s: float, deg: float, cmd_speed: float) -> None:
        since = send_s - self.cmds[-1][0] if self.cmds else None
        self.cmds.append((send_s, deg, cmd_speed))
        actual = cmd_speed if not self.model else motor_model.actual_speed(
            self.model, self.motor, min(cmd_speed, cmd_cap(self.motor)))
        self._events.append((send_s + start_delay_s(self.model, self.motor, since), deg, actual))

    def last_send(self) -> float | None:
        return self.cmds[-1][0] if self.cmds else None

    def pos(self, t: float) -> float:
        """t 秒時的預測角度。"""
        p = self.start_pos or 0.0
        events = sorted(self._events)
        for i, (te, target, speed) in enumerate(events):
            if te > t:
                break
            end = min(t, events[i + 1][0]) if i + 1 < len(events) else t
            step = speed * (end - te)
            p = target if abs(target - p) <= step else p + step * (1 if target > p else -1)
        return p

    def trace(self, times: np.ndarray) -> np.ndarray:
        out = np.empty_like(times)
        p = self.start_pos or 0.0
        events = sorted(self._events)
        k, target, speed, prev = 0, p, 0.0, float(times[0]) if len(times) else 0.0
        for i, t in enumerate(times):
            while k < len(events) and events[k][0] <= t:
                # 先走到這個事件生效的時刻，再換目標
                step = speed * (events[k][0] - prev)
                p = target if abs(target - p) <= step else p + step * (1 if target > p else -1)
                prev = events[k][0]
                _, target, speed = events[k]
                k += 1
            step = speed * (t - prev)
            p = target if abs(target - p) <= step else p + step * (1 if target > p else -1)
            prev = t
            out[i] = p
        return out


def tracks_from_script(script: dict, model: dict | None) -> dict[int, MotorTrack]:
    """把腳本變成每顆馬達的指令序列（和 pybullet_sim.py 一樣：t<=0 的第一步在預備時間先擺好）。"""
    tracks: dict[int, MotorTrack] = {}
    steps = sorted(script["steps"], key=lambda s: s["t"])
    preset = bool(steps) and steps[0]["t"] <= 0
    for k, s in enumerate(steps):
        for c in s["cmds"]:
            tr = tracks.setdefault(c["motor"], MotorTrack(c["motor"], model))
            if preset and k == 0:
                tr.preset(c["deg"])
            else:
                tr.add(s["t"] / 1000.0, c["deg"], c["speed"])
    return tracks


# ---------------------------------------------------------------------------
# 還原關鍵點、重新規劃
# ---------------------------------------------------------------------------

def keyframes(script: dict) -> tuple[dict[int, list[tuple[float, float]]], dict[int, float]]:
    """從原腳本還原每顆馬達的 (影片時間 s, 目標角度)，以及第一步的原始速度。

    原腳本的 t = max(0, 影片時間 − lead)，所以影片時間 = t + lead；t = 0 的那一步是第 0 幀。
    """
    lead = int(script["frameIntervalMs"] * REACH_FRACTION)
    keys: dict[int, list[tuple[float, float]]] = {}
    first_speed: dict[int, float] = {}
    for s in sorted(script["steps"], key=lambda x: x["t"]):
        at = 0.0 if s["t"] <= 0 else (s["t"] + lead) / 1000.0
        for c in s["cmds"]:
            m = c["motor"]
            first_speed.setdefault(m, c["speed"])
            keys.setdefault(m, []).append((at, clamp_deg(m, c["deg"])))
    return keys, first_speed


def reference(keys: list[tuple[float, float]], interval_s: float, times: np.ndarray) -> np.ndarray:
    """舞者的參考軌跡：每個關鍵點之前的一個取樣間隔內線性轉過去，其餘時間停住。"""
    xs, ys = [], []
    prev_t, prev_d = None, 0.0
    for at, deg in keys:
        if prev_t is None:
            if at > 0:                      # 一開始看不清楚的肢體：從歸位姿勢出發
                xs += [max(0.0, at - interval_s), at]
                ys += [0.0, deg]
            else:
                xs.append(0.0)
                ys.append(deg)
        else:
            start = max(prev_t, at - interval_s)
            if start > xs[-1]:
                xs.append(start)
                ys.append(prev_d)
            xs.append(at)
            ys.append(deg)
        prev_t, prev_d = at, deg
    return np.interp(times, xs, ys)


def plan_motor(motor: int, keys: list[tuple[float, float]], interval_s: float, model: dict | None,
               first_speed: float, lead_s: float, stats: dict) -> MotorTrack:
    track = MotorTrack(motor, model)
    vmax = max_actual_speed(model, motor)
    window = motor_model.MOVING_WINDOW_S
    prev_at: float | None = None
    busy_until = 0.0        # 上一段動作預計做完的時間

    for k, (at, deg) in enumerate(keys):
        if prev_at is None and at <= 0:
            track.preset(deg)               # 第 0 幀：預備時間就先擺好
            prev_at = at
            continue
        seg_start = max(0.0, at - interval_s) if prev_at is None else max(prev_at, at - interval_s)
        seg_len = max(1e-3, at - seg_start)
        pos = track.pos(seg_start)
        delta = deg - pos
        if abs(delta) < DEAD_BAND_DEG:
            prev_at = at
            continue

        need = abs(delta) / seg_len
        target = deg
        if need <= vmax:
            start, speed = seg_start, need
        else:
            # 來不及：動作置中在舞者的動作時間上，但不能早於上一段動作做完
            t_need = abs(delta) / vmax
            start = max(busy_until, seg_start - (t_need - seg_len) / 2)
            start = max(0.0, start)
            speed = vmax
            stats["saturated"] += 1
            allowed_end = at + (t_need - seg_len) / 2
            reverses = k + 1 < len(keys) and (keys[k + 1][1] - deg) * delta < 0
            if reverses and start + t_need > allowed_end + 1e-6:
                # 下一個動作要反方向：到不了就縮小幅度，不要拖到下一拍
                pos = track.pos(start)
                reach = vmax * max(0.0, allowed_end - start)
                if reach < abs(deg - pos):
                    target = pos + reach * (1 if deg > pos else -1)
                    stats["shortened"] += 1

        # 起動延遲：距離上一個指令很近時馬達還在動，延遲短很多。先假設短的，不成立再用靜止的
        last = track.last_send()
        delay = start_delay_s(model, motor, 0.0 if last is not None else None)
        send = start - delay - lead_s
        if last is None or send - last >= window:
            delay = start_delay_s(model, motor, None)
            send = start - delay - lead_s
            if last is not None and send - last < window:
                send = last + window        # 剛好落在兩種延遲的交界：取保守的一邊
        send = max(0.0, send)
        if last is not None and send <= last:
            send = last + 0.001
        send = round(send, 3)

        cmd = cmd_speed_for(model, motor, speed)
        if not track.cmds and prev_at is None:
            cmd = max(cmd, min(first_speed, cmd_cap(motor)))
        track.add(send, round(target, 1), cmd)
        actual = motor_model.actual_speed(model, motor, cmd) if model else cmd
        busy_until = send + delay + abs(target - track.pos(send + delay)) / max(actual, 1e-6)
        prev_at = at
        stats["cmds"] += 1
    return track


def retime(script: dict, model: dict | None, lead_ms: float = 0.0) -> tuple[dict, dict]:
    """回傳（重排後的腳本, 統計）。原腳本不會被修改。"""
    if script.get("planner"):
        raise ValueError(f"這份腳本已經重排過（planner={script['planner']}），請用原始腳本")
    interval_s = script["frameIntervalMs"] / 1000.0
    keys, first_speed = keyframes(script)
    stats = {"cmds": 0, "saturated": 0, "shortened": 0}
    by_time: dict[int, list[dict]] = {}
    opening: list[dict] = []
    for motor in sorted(keys):
        track = plan_motor(motor, keys[motor], interval_s, model, first_speed[motor], lead_ms / 1000.0, stats)
        if track.start_pos is not None:
            opening.append({"motor": motor, "deg": round(track.start_pos, 1), "speed": first_speed[motor]})
        for send, deg, speed in track.cmds:
            by_time.setdefault(int(round(send * 1000)), []).append({"motor": motor, "deg": deg, "speed": speed})

    # 事件名稱（顯示用）：沿用原腳本裡時間最接近的那一步
    old = sorted(script["steps"], key=lambda s: s["t"])
    old_t = np.array([s["t"] for s in old]) if old else np.array([0])

    def event_at(t_ms: int) -> str:
        return old[int(np.argmin(np.abs(old_t - t_ms)))].get("event", "") if old else ""

    steps = []
    if opening:
        # 開場姿勢獨立成第一步：播放器會先送它、等機器人就位才開始計時
        steps.append({"t": 0, "event": event_at(0), "cmds": opening})
    for t_ms in sorted(by_time):
        # 開場姿勢之後的指令至少晚 1ms，才不會被當成「預備時間就擺好」
        steps.append({"t": max(t_ms, 1) if opening else t_ms, "event": event_at(t_ms), "cmds": by_time[t_ms]})

    out = dict(script)
    out["steps"] = steps
    out["planner"] = PLANNER_TAG
    stats["steps"] = len(steps)
    return out, stats


# ---------------------------------------------------------------------------
# 評估：模擬出來的馬達角度 vs 舞者的參考軌跡
# ---------------------------------------------------------------------------

def best_lag_ms(ref: np.ndarray, act: np.ndarray, max_lag_s: float = 0.5) -> tuple[float, float]:
    """act 比 ref 晚多少毫秒（正值 = 晚），以及對齊後的相關係數。"""
    n = int(max_lag_s / SIM_DT)
    a, b = ref - ref.mean(), act - act.mean()
    if not a.any() or not b.any():
        return float("nan"), float("nan")
    best = (-2.0, 0)
    for lag in range(-n, n + 1):
        x, y = (a[:len(a) - lag], b[lag:]) if lag >= 0 else (a[-lag:], b[:lag])
        denom = np.linalg.norm(x) * np.linalg.norm(y)
        r = float(x @ y / denom) if denom > 0 else -2.0
        if r > best[0]:
            best = (r, lag)
    return best[1] * SIM_DT * 1000.0, best[0]


def smooth(x: np.ndarray, sec: float = 0.1) -> np.ndarray:
    n = max(1, int(sec / SIM_DT))
    return np.convolve(x, np.ones(n) / n, mode="same")


def evaluate(raw: dict, candidate: dict, model: dict | None) -> dict:
    """candidate（原腳本或重排後的腳本）在馬達模型下，和 raw 的關鍵點差多少。"""
    interval_s = raw["frameIntervalMs"] / 1000.0
    keys, _ = keyframes(raw)
    times = np.arange(0.0, raw["durationMs"] / 1000.0 + 0.5, SIM_DT)
    tracks = tracks_from_script(candidate, model)
    per, ref_speed, act_speed = {}, np.zeros_like(times), np.zeros_like(times)
    for m, ks in sorted(keys.items()):
        ref = reference(ks, interval_s, times)
        act = tracks[m].trace(times) if m in tracks else np.zeros_like(times)
        rv, av = np.abs(np.gradient(ref, times)), np.abs(np.gradient(act, times))
        ref_speed += rv
        act_speed += av
        # 到位誤差：每個關鍵點的那一刻，馬達離目標還差多少
        at_keys = [abs(float(np.interp(at, times, act)) - deg) for at, deg in ks]
        per[m] = {"meanErrDeg": round(float(np.mean(np.abs(act - ref))), 2),
                  "keyErrDeg": round(float(np.mean(at_keys)), 2),
                  "lagMs": round(best_lag_ms(smooth(rv), smooth(av))[0])
                  if rv.any() and av.any() else None}
    lag, r = best_lag_ms(smooth(ref_speed), smooth(act_speed))
    return {"lagMs": round(lag) if lag == lag else None, "r": round(r, 2) if r == r else None,
            "meanErrDeg": round(float(np.mean([v["meanErrDeg"] for v in per.values()])), 2) if per else 0.0,
            "keyErrDeg": round(float(np.mean([v["keyErrDeg"] for v in per.values()])), 2) if per else 0.0,
            "motors": per}


def print_compare(before: dict, after: dict | None) -> None:
    print("                 時間差 ms   相關係數   平均角度誤差°   關鍵點到位誤差°")
    for name, e in (("原腳本", before), ("重排後", after)):
        if e is None:
            continue
        print(f"  {name}        {e['lagMs']!s:>6}     {e['r']!s:>6}       {e['meanErrDeg']:>6}          {e['keyErrDeg']:>6}")
    if after is None:
        return
    print("  各馬達（原 → 重排）：時間差 ms / 平均角度誤差°")
    for m in sorted(before["motors"]):
        b, a = before["motors"][m], after["motors"][m]
        name = MOTORS[m][0] if m in MOTORS else f"motor{m}"
        print(f"    M{m:<2} {name:18s} {b['lagMs']!s:>5} → {a['lagMs']!s:>5}    {b['meanErrDeg']:>6} → {a['meanErrDeg']:>6}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Retime dance_script.json with the measured motor model")
    ap.add_argument("script", type=Path, help="DanceScriptGenerator 產生的原始 dance_script.json")
    ap.add_argument("--out", type=Path, default=None, help="輸出檔（預設 <輸入檔名>.retimed.json）")
    ap.add_argument("--lead-ms", type=float, default=0.0, help="整體再提早送出的毫秒數（實機仍慢時微調）")
    ap.add_argument("--ideal", action="store_true", help="不套馬達模型（指令即時、照指令速度）")
    ap.add_argument("--eval-only", action="store_true", help="只印出原腳本的評估，不輸出")
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    raw = json.loads(a.script.read_text(encoding="utf-8"))
    model = None if a.ideal else motor_model.load()
    if model is None and not a.ideal:
        print("找不到 sim/motor_response.json，改用理想馬達（沒有延遲、照指令速度）")
    before = evaluate(raw, raw, model)
    if a.eval_only:
        print_compare(before, None)
        return 0
    new, stats = retime(raw, model, a.lead_ms)
    out = a.out or a.script.with_suffix(".retimed.json")
    out.write_text(json.dumps(new, ensure_ascii=False), encoding="utf-8")
    print_compare(before, evaluate(raw, new, model))
    print(f"  步數 {len(raw['steps'])} → {stats['steps']}，指令 {stats['cmds']}，"
          f"馬達最快也來不及 {stats['saturated']} 次，其中縮小幅度 {stats['shortened']} 次")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
