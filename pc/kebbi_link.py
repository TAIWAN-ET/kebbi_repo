"""電腦 ↔ Kebbi 的 Wi-Fi 連線工具（搭配 Kebbi 上的 App；App 一開就會等電腦連線）。

    python kebbi_link.py find                       # 自動找同一個 Wi-Fi 裡的 Kebbi
    python kebbi_link.py status   [IP]              # 狀態（含播放到影片第幾毫秒）
    python kebbi_link.py sync     [IP]              # 量網路來回時間與兩邊時鐘差
    python kebbi_link.py send     [IP] out/xxx/dance_script.json [--play] [--delay 3]
    python kebbi_link.py play     [IP] [--delay 3]  # 重播已載入的腳本
    python kebbi_link.py stop     [IP]
    python kebbi_link.py home     [IP]
    python kebbi_link.py motions  [IP]              # 內建 motion 清單
    python kebbi_link.py motion   [IP] --name 666_DA_Hit --out out/motion.json   # 播一個並取樣馬達角度
    python kebbi_link.py play     [IP] --delay 3 --trace   # 播放時記錄馬達角度，之後用 trace 拿回來
    python kebbi_link.py trace    [IP] --out out/trace.json

IP 可以省略：省略時自動搜尋（只有一台時直接用它）。

也可以當函式庫用（sync_play.py、measure_latency.py 都用這份）：

    link = KebbiLink.connect()          # 或 KebbiLink("192.168.1.50")
    clock = link.sync_clock()
    link.upload(script_bytes)
    link.play_at(time.time() + 3)       # 3 秒後，影片 t=0

只用 Python 標準函式庫，不需要 .venv。
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from send_script import DEFAULT_PORT, call

DISCOVERY_PORT = 8766            # 與 DiscoveryResponder.DEFAULT_PORT 相同
DISCOVERY_PROBE = b"KEBBI_DISCOVER"
DEFAULT_START_DELAY_S = 3.0      # 同步播放：預設排在幾秒後開始（要比 Kebbi 的預備時間 1.5 秒長）


def _local_ipv4() -> list[str]:
    """電腦的 IPv4（排除 127.x）。"""
    ips: set[str] = set()
    try:
        ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    try:
        # 不會真的送封包：只是讓系統選出「預設路由」那張網卡的 IP
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def _broadcast_targets() -> list[str]:
    """255.255.255.255 在 Windows 上只會從其中一張網卡出去（有 VPN、Hyper-V、WSL 虛擬網卡時常常選錯），
    所以每張網卡再送一次該網段的廣播（假設 /24，家用路由器幾乎都是）。"""
    targets = ["255.255.255.255"]
    for ip in _local_ipv4():
        targets.append(ip.rsplit(".", 1)[0] + ".255")
    return targets + ["127.0.0.1"]   # 127.0.0.1：本機的 FakeKebbi


def discover(timeout: float = 2.0) -> list[dict]:
    """廣播搜尋區網中的 Kebbi，回傳 [{"host", "port", "name"}, ...]。"""
    found: dict[str, dict] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.2)
        targets = _broadcast_targets()
        deadline = time.time() + timeout
        next_probe = 0.0
        while time.time() < deadline:
            if time.time() >= next_probe:       # 每 0.5 秒重送一次，Wi-Fi 偶爾會丟廣播
                for target in targets:
                    try:
                        sock.sendto(DISCOVERY_PROBE, (target, DISCOVERY_PORT))
                    except OSError:
                        pass
                next_probe = time.time() + 0.5
            try:
                data, (addr, _) = sock.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                continue
            try:
                info = json.loads(data.decode("utf-8"))
            except ValueError:
                continue
            if info.get("kebbi"):
                # 同一台可能從廣播和 127.0.0.1 各回一次（本機 FakeKebbi）：用 id 去重複，留 127.0.0.1
                key = info.get("id") or addr
                if key in found and found[key]["host"] == "127.0.0.1":
                    continue
                found[key] = {"host": addr, "port": int(info.get("port", DEFAULT_PORT)),
                              "name": info.get("name", "Kebbi")}
    return list(found.values())


@dataclass
class ClockSync:
    """電腦時鐘（time.time()）與 Kebbi 時鐘的換算。"""
    offset_ms: float   # kebbi_ms = pc_ms + offset_ms
    rtt_ms: float      # 用來算 offset 的那一次來回時間（最短的一次）
    rtt_median_ms: float
    rtt_max_ms: float
    samples: int

    def to_kebbi_ms(self, pc_time_s: float) -> int:
        return int(round(pc_time_s * 1000.0 + self.offset_ms))

    def to_pc_time(self, kebbi_ms: float) -> float:
        return (kebbi_ms - self.offset_ms) / 1000.0

    @property
    def error_ms(self) -> float:
        """對時誤差上限：最短來回時間的一半。"""
        return self.rtt_ms / 2.0


class KebbiError(RuntimeError):
    pass


class KebbiLink:
    def __init__(self, host: str, port: int = DEFAULT_PORT):
        # 也接受畫面上照抄的「IP:埠號」
        if host.count(":") == 1:
            host, _, port_text = host.partition(":")
            port = int(port_text)
        self.host = host
        self.port = port
        self.clock: ClockSync | None = None

    @classmethod
    def connect(cls, host: str | None = None, port: int = DEFAULT_PORT) -> "KebbiLink":
        """有給 host 就用它；沒給就自動搜尋，只找到一台時用那台。"""
        if host:
            return cls(host, port)
        robots = discover()
        if not robots:
            raise KebbiError("找不到 Kebbi：確認 App 已開啟、和電腦連同一個 Wi-Fi；"
                             "或直接輸入 Kebbi 畫面上顯示的 IP")
        if len(robots) > 1:
            names = ", ".join(f"{r['host']} ({r['name']})" for r in robots)
            raise KebbiError(f"找到多台 Kebbi，請指定 IP：{names}")
        return cls(robots[0]["host"], robots[0]["port"])

    def request(self, method: str, path: str, body: bytes | None = None, timeout: float = 10.0) -> dict:
        result = call(self.host, self.port, method, path, body, timeout=timeout)
        if not result.get("ok"):
            raise KebbiError(result.get("message", "unknown error"))
        return result

    def status(self) -> dict:
        return self.request("GET", "/status", timeout=3)

    def sync_clock(self, samples: int = 20) -> ClockSync:
        """NTP 式對時：問很多次 Kebbi 時鐘，取來回最快的那次（被排隊拖慢最少）。"""
        best: tuple[float, float] | None = None   # (rtt, offset)
        rtts: list[float] = []
        for _ in range(samples):
            t0 = time.time()
            result = self.request("GET", "/time", timeout=3)
            t1 = time.time()
            rtt = (t1 - t0) * 1000.0
            offset = result["kebbiMs"] - (t0 + t1) * 500.0
            rtts.append(rtt)
            if best is None or rtt < best[0]:
                best = (rtt, offset)
            time.sleep(0.02)
        rtts.sort()
        self.clock = ClockSync(offset_ms=best[1], rtt_ms=best[0], rtt_median_ms=rtts[len(rtts) // 2],
                               rtt_max_ms=rtts[-1], samples=samples)
        return self.clock

    def upload(self, script: bytes) -> dict:
        return self.request("POST", "/script", script, timeout=30)

    def play_at(self, pc_start_time: float, trace: bool = False) -> dict:
        """在電腦時間 pc_start_time（time.time()）對上影片 t=0 開始播放。trace＝同時記錄馬達角度。"""
        clock = self.clock or self.sync_clock()
        extra = "&trace=1" if trace else ""
        return self.request("POST", f"/play?at={clock.to_kebbi_ms(pc_start_time)}{extra}")

    def play_now(self, trace: bool = False) -> dict:
        return self.request("POST", "/play?trace=1" if trace else "/play")

    def motions(self) -> list[str]:
        return self.request("GET", "/motions")["motions"]

    def play_motion(self, name: str) -> dict:
        """播一個內建 motion，回傳 {durationMs, motors, samples: [[ms, d1..d10]]}（播完才回來）。"""
        from urllib.parse import quote
        return self.request("POST", f"/motion?name={quote(name)}", timeout=40)

    def trace(self, since_kebbi_ms: int | None = None) -> dict:
        """腳本播放時記錄的角度：samples [[kebbiMs, d1..d10]]；用 self.clock.to_pc_time() 換成電腦時間。"""
        path = "/trace" if since_kebbi_ms is None else f"/trace?since={since_kebbi_ms}"
        return self.request("GET", path, timeout=30)

    def stop(self) -> dict:
        return self.request("POST", "/stop")

    def home(self) -> dict:
        return self.request("POST", "/home")

    def motor_test(self, motor: int, deg: float, speed: float) -> dict:
        return self.request("POST", f"/motor-test?motor={motor}&deg={deg:g}&speed={speed:g}", timeout=30)


def _print(obj: object) -> None:
    print(json.dumps(obj, ensure_ascii=False, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PC <-> Kebbi Wi-Fi link")
    parser.add_argument("command", choices=["find", "status", "sync", "send", "play", "stop", "home",
                                            "motions", "motion", "trace"])
    parser.add_argument("args", nargs="*", help="[IP] [dance_script.json]")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--play", action="store_true", help="send: play after upload")
    parser.add_argument("--delay", type=float, default=None,
                        help="play: start N seconds from now on a synced clock (default: robot decides)")
    parser.add_argument("--trace", action="store_true", help="play/send: record motor angles while playing")
    parser.add_argument("--name", help="motion: built-in motion name")
    parser.add_argument("--out", type=Path, help="motion / trace: save the JSON here")
    args = parser.parse_args(argv)

    if args.command == "find":
        robots = discover()
        if not robots:
            print("找不到 Kebbi（確認 App 已開啟、同一個 Wi-Fi；有些路由器會擋廣播，這時請直接用 IP）")
            print("電腦的 IP：" + (", ".join(_local_ipv4()) or "(取不到)")
                  + "　→ 前三段要和 Kebbi 畫面上的 IP 一樣")
            return 1
        for r in robots:
            print(f"{r['host']}:{r['port']}  {r['name']}")
        return 0

    rest = list(args.args)
    host = None
    if rest and not rest[0].lower().endswith(".json"):
        host = rest.pop(0)
    script = Path(rest[0]) if rest else None

    try:
        link = KebbiLink.connect(host, args.port)
        print(f"Kebbi: {link.host}:{link.port}", file=sys.stderr)
        if args.command == "status":
            _print(link.status())
        elif args.command == "sync":
            c = link.sync_clock()
            print(f"網路來回：最短 {c.rtt_ms:.1f} ms，中位數 {c.rtt_median_ms:.1f} ms，最長 {c.rtt_max_ms:.1f} ms")
            print(f"時鐘差：{c.offset_ms:.0f} ms（誤差 ±{c.error_ms:.1f} ms）")
        elif args.command == "stop":
            _print(link.stop())
        elif args.command == "home":
            _print(link.home())
        elif args.command == "motions":
            names = link.motions()
            print("\n".join(names))
            print(f"共 {len(names)} 個", file=sys.stderr)
        elif args.command in ("motion", "trace"):
            if args.command == "motion":
                if not args.name:
                    parser.error("motion 需要 --name（名稱用 motions 查）")
                result = link.play_motion(args.name)
                print(f"{args.name}: 動作時間 {result.get('durationMs')} ms，取樣 {len(result['samples'])} 筆",
                      file=sys.stderr)
            else:
                result = link.trace()
                print(f"軌跡 {len(result['samples'])} 筆（記錄中：{result.get('running')}）", file=sys.stderr)
            if args.out:
                args.out.parent.mkdir(parents=True, exist_ok=True)
                args.out.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                print(f"存到 {args.out}", file=sys.stderr)
            else:
                _print({k: v for k, v in result.items() if k != "samples"})
        else:
            if args.command == "send":
                if script is None or not script.is_file():
                    parser.error("send 需要 dance_script.json")
                _print(link.upload(script.read_bytes()))
                if not args.play:
                    return 0
            if args.delay is not None:
                link.sync_clock()
                _print(link.play_at(time.time() + args.delay, trace=args.trace))
            else:
                _print(link.play_now(trace=args.trace))
    except KebbiError as e:
        print(f"錯誤：{e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
