"""量 Kebbi 的延遲：Wi-Fi 來回時間、時鐘對時誤差、各馬達「下指令 → 開始動 → 到位」的時間。

    python measure_latency.py                 # 自動搜尋 Kebbi
    python measure_latency.py 192.168.1.50
    python measure_latency.py 192.168.1.50 --motors 1 2 --deg 20 --speed 60 --out out/latency.json

馬達量測在機器人端做（App 自己每 10ms 讀角度），不受 Wi-Fi 影響。
會實際轉動馬達（轉到 +deg 再回 0），請讓 Kebbi 周圍淨空。

量到的「開始動」時間就是腳本要提早送出的量（DanceScriptBuilder 的 lead），
「到位」時間可以和理論值（角度 / 速度）比，看速度設定是不是實際做得到。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

from kebbi_link import KebbiError, KebbiLink

# 與 RobotMotor.java 相同：1 脖子上下、2 脖子左右、4 右肩前後、8 左肩前後
DEFAULT_MOTORS = [1, 2, 4, 8]


def measure_network(link: KebbiLink, rounds: int) -> dict:
    rtts = []
    for _ in range(rounds):
        t0 = time.perf_counter()
        link.request("GET", "/time", timeout=3)
        rtts.append((time.perf_counter() - t0) * 1000.0)
        time.sleep(0.03)
    rtts.sort()
    return {
        "rounds": rounds,
        "minMs": round(rtts[0], 1),
        "medianMs": round(statistics.median(rtts), 1),
        "p95Ms": round(rtts[int(len(rtts) * 0.95) - 1], 1),
        "maxMs": round(rtts[-1], 1),
    }


def measure_clock(link: KebbiLink, repeats: int) -> dict:
    """對時好幾次，看時鐘差穩不穩（漂移 / 抖動會直接變成影片和機器人的錯位）。"""
    offsets = []
    errors = []
    for _ in range(repeats):
        c = link.sync_clock(samples=10)
        offsets.append(c.offset_ms)
        errors.append(c.error_ms)
    spread = max(offsets) - min(offsets)
    return {"repeats": repeats, "offsetSpreadMs": round(spread, 1),
            "worstErrorMs": round(max(errors), 1)}


def measure_motor(link: KebbiLink, motor: int, deg: float, speed: float) -> dict:
    out = link.motor_test(motor, deg, speed)
    back = link.motor_test(motor, 0, speed)
    return {"motor": motor, "out": out, "back": back}


def summarize(result: dict) -> None:
    net = result["network"]
    print(f"\nWi-Fi 來回：最短 {net['minMs']} ms，中位數 {net['medianMs']} ms，"
          f"p95 {net['p95Ms']} ms，最長 {net['maxMs']} ms")
    clock = result["clock"]
    print(f"對時：時鐘差變動 {clock['offsetSpreadMs']} ms，單次誤差最多 ±{clock['worstErrorMs']} ms")
    if result["motors"]:
        print("\n馬達  方向   開始動   到位    理論   (ms)")
        for m in result["motors"]:
            for key, label in (("out", "去"), ("back", "回")):
                r = m[key]
                if not r.get("ok", True):
                    print(f"M{m['motor']:<3} {label}    {r.get('message')}")
                    continue
                print(f"M{m['motor']:<3} {label}    {str(r.get('firstMoveMs')):>6} {str(r.get('reachMs')):>6}"
                      f" {r.get('expectedMs'):>6}")
        starts = [m[k]["firstMoveMs"] for m in result["motors"] for k in ("out", "back")
                  if m[k].get("firstMoveMs") is not None]
        if starts:
            print(f"\n建議馬達提早量（開始動的中位數）：{statistics.median(starts):.0f} ms")
    print("\n影片和機器人同步時，腳本整份先傳到 Kebbi、照對好的時鐘播放，"
          "所以 Wi-Fi 延遲只影響對時誤差，不會一步一步累積。")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Measure Kebbi network / clock / motor latency")
    ap.add_argument("host", nargs="?", help="Kebbi IP (default: auto discover)")
    ap.add_argument("--motors", type=int, nargs="*", default=DEFAULT_MOTORS,
                    help="motor ids to test; give none (--motors) to skip motor test")
    ap.add_argument("--deg", type=float, default=20.0, help="test angle (deg)")
    ap.add_argument("--speed", type=float, default=60.0, help="test speed (deg/s)")
    ap.add_argument("--rounds", type=int, default=50, help="network round trips")
    ap.add_argument("--out", type=Path, default=None, help="save raw result as JSON")
    args = ap.parse_args(argv)

    try:
        link = KebbiLink.connect(args.host)
        print(f"Kebbi: {link.host}:{link.port}")
        status = link.status()
        if status.get("playing"):
            print("Kebbi 正在播放，先停止再量")
            return 1
        print("量 Wi-Fi 來回時間...")
        result = {"host": link.host, "network": measure_network(link, args.rounds)}
        print("量對時誤差...")
        result["clock"] = measure_clock(link, 5)
        result["motors"] = []
        for motor in args.motors:
            print(f"量馬達 M{motor}（轉到 {args.deg:g}° 再回 0°）...")
            result["motors"].append(measure_motor(link, motor, args.deg, args.speed))
    except KebbiError as e:
        print(f"錯誤：{e}", file=sys.stderr)
        return 1

    summarize(result)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n原始資料：{args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
