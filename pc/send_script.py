"""把 dance_script.json 經 Wi-Fi 傳給 Kebbi（App 需先按「開啟遠端接收」）。

    python send_script.py 192.168.1.50 out/dream_01/dance_script.json --play
    python send_script.py 192.168.1.50 --status
    python send_script.py 192.168.1.50 --stop
    python send_script.py 192.168.1.50 --home
    python send_script.py 192.168.1.50 --play        # 重播 Kebbi 上已載入的腳本

只用 Python 標準函式庫，不需要 .venv。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_PORT = 8765  # 與 ScriptServer.DEFAULT_PORT 相同


def call(host: str, port: int, method: str, path: str, body: bytes | None = None,
         timeout: float = 10.0) -> dict:
    url = f"http://{host}:{port}{path}"
    request = urllib.request.Request(url, data=body, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 4xx / 5xx 仍然帶有 JSON 錯誤訊息
        try:
            return json.loads(e.read().decode("utf-8"))
        except ValueError:
            return {"ok": False, "message": f"HTTP {e.code}"}
    except (urllib.error.URLError, OSError) as e:
        return {"ok": False, "message": f"cannot reach {url}: {e}"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Send dance_script.json to Kebbi over Wi-Fi")
    parser.add_argument("host", help="Kebbi IP (shown on screen after enabling remote)")
    parser.add_argument("script", nargs="?", type=Path, help="dance_script.json to upload")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--play", action="store_true", help="play after upload (or replay loaded script)")
    parser.add_argument("--stop", action="store_true")
    parser.add_argument("--home", action="store_true")
    parser.add_argument("--status", action="store_true")
    args = parser.parse_args(argv)

    if args.stop:
        result = call(args.host, args.port, "POST", "/stop", b"")
    elif args.home:
        result = call(args.host, args.port, "POST", "/home", b"")
    elif args.status:
        result = call(args.host, args.port, "GET", "/status")
    elif args.script is not None:
        if not args.script.is_file():
            sys.exit(f"找不到 {args.script}")
        path = "/script?play=1" if args.play else "/script"
        result = call(args.host, args.port, "POST", path, args.script.read_bytes())
    elif args.play:
        result = call(args.host, args.port, "POST", "/play", b"")
    else:
        parser.error("give a script to upload, or one of --play / --stop / --home / --status")
        return 2

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
