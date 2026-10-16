"""影片和 Kebbi 同步播放：腳本先整份傳到 Kebbi，兩邊對好時鐘，從同一刻開始。

    python sync_play.py 192.168.1.50 out/dream_01/dance_script.json dream_01.mp4
    python sync_play.py auto out/dream_01/dance_script.json dream_01.mp4 --delay 4
    python sync_play.py auto out/dream_01/dance_script.json dream_01.mp4 --video-offset-ms 120

流程：
  1. 上傳 dance_script.json（只傳一次，播放中不靠 Wi-Fi 一步一步送）
  2. 對時：量出電腦和 Kebbi 時鐘差（誤差約為最短 Wi-Fi 來回時間的一半）
  3. 選一個 --delay 秒後的時間點當影片 t=0，告訴 Kebbi
  4. 瀏覽器開一頁播放影片（有聲音），在同一刻開始；播放中持續校正影片進度，不會越播越偏

--video-offset-ms：正值讓影片晚一點（機器人動作看起來慢半拍時，把影片往後挪）。
腳本本身已經扣掉馬達反應時間（DanceScriptBuilder 的提早量），量實際延遲用 measure_latency.py。

只用 Python 標準函式庫。
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from kebbi_link import DEFAULT_START_DELAY_S, KebbiError, KebbiLink

PAGE = """<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><title>Kebbi 同步播放</title>
<style>
 body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;text-align:center}
 video{width:100vw;max-height:88vh;background:#000}
 #info{padding:8px;font-size:18px}
 button{font-size:18px;margin:0 8px}
</style></head><body>
<video id="v" src="/video" preload="auto" playsinline muted></video>
<div id="info">準備中…</div>
<div><button id="unmute">開聲音</button><button id="stop">停止</button></div>
<script>
const v = document.getElementById('v'), info = document.getElementById('info');
let plan = null, clockOffset = 0, stopped = false;
// 瀏覽器時鐘對齊 sync_play.py（本機，誤差 < 1ms）
async function syncLocal() {
  let best = null;
  for (let i = 0; i < 8; i++) {
    const t0 = Date.now(); const r = await (await fetch('/now')).json(); const t1 = Date.now();
    if (!best || t1 - t0 < best.rtt) best = {rtt: t1 - t0, off: r.nowMs - (t0 + t1) / 2};
  }
  clockOffset = best.off;
}
const now = () => Date.now() + clockOffset;   // 對齊後的電腦時鐘（ms）
async function main() {
  await syncLocal();
  plan = await (await fetch('/plan')).json();
  v.load();
  tick();
}
function tick() {
  if (stopped) return;
  const t = (now() - plan.videoZeroMs) / 1000;      // 影片應該播到第幾秒
  if (t < 0) {
    info.textContent = '倒數 ' + (-t).toFixed(1) + ' 秒（Kebbi ' + plan.host + '，對時誤差 ±' + plan.clockErrorMs.toFixed(0) + 'ms）';
    if (!v.paused) v.pause();
    if (v.currentTime !== 0) v.currentTime = 0;
  } else if (t > v.duration) {
    info.textContent = '播放完畢';
    return;
  } else {
    if (v.paused) { v.currentTime = t; v.play().catch(() => { info.textContent = '瀏覽器擋住自動播放，請點一下頁面'; }); }
    const drift = v.currentTime - t;
    // 偏太多直接跳；小偏差用播放速度慢慢追，聲音才不會一直斷
    if (Math.abs(drift) > 0.15) v.currentTime = t;
    else v.playbackRate = 1 - Math.max(-0.05, Math.min(0.05, drift * 0.5));
    info.textContent = '播放中 ' + t.toFixed(1) + 's　影片偏差 ' + (drift * 1000).toFixed(0) + 'ms';
  }
  setTimeout(tick, t < -0.5 ? 100 : 20);
}
// 瀏覽器只允許靜音自動播放；按一下開聲音，不影響同步
document.getElementById('unmute').onclick = () => { v.muted = false; };
document.getElementById('stop').onclick = async () => { stopped = true; v.pause(); await fetch('/stop', {method: 'POST'}); info.textContent = '已停止'; };
main();
</script></body></html>
"""


def make_handler(video: Path, plan: dict, link: KebbiLink, done: threading.Event):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _json(self, obj: dict) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/":
                body = PAGE.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif self.path == "/now":
                self._json({"nowMs": time.time() * 1000.0})
            elif self.path == "/plan":
                self._json(plan)
            elif self.path == "/video":
                self._video()
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path == "/stop":
                try:
                    link.stop()
                except KebbiError:
                    pass
                self._json({"ok": True})
                done.set()
            else:
                self.send_error(404)

        def _video(self):
            # 支援 Range：瀏覽器要能跳到任意位置（校正進度用）
            size = video.stat().st_size
            start, end = 0, size - 1
            header = self.headers.get("Range")
            if header and header.startswith("bytes="):
                first, _, last = header[6:].partition("-")
                start = int(first) if first else 0
                end = min(int(last), size - 1) if last else size - 1
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", mimetypes.guess_type(video.name)[0] or "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with video.open("rb") as f:
                f.seek(start)
                remaining = end - start + 1
                try:
                    while remaining > 0:
                        chunk = f.read(min(1 << 16, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass

    return Handler


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Play video on PC in sync with Kebbi")
    ap.add_argument("host", help="Kebbi IP, or 'auto' to discover")
    ap.add_argument("script", type=Path, help="dance_script.json")
    ap.add_argument("video", type=Path, help="the dance video the script was made from")
    ap.add_argument("--delay", type=float, default=DEFAULT_START_DELAY_S,
                    help="seconds from now until video t=0 (default %(default)s)")
    ap.add_argument("--video-offset-ms", type=float, default=0.0,
                    help="positive = video starts later than the robot")
    ap.add_argument("--http-port", type=int, default=8810, help="local page port")
    ap.add_argument("--no-browser", action="store_true", help="only print the page URL")
    args = ap.parse_args(argv)

    for path in (args.script, args.video):
        if not path.is_file():
            sys.exit(f"找不到 {path}")
    script_bytes = args.script.read_bytes()
    duration_ms = json.loads(script_bytes.decode("utf-8")).get("durationMs", 0)

    # 本機網頁先開好，瀏覽器載入影片的時間算在 delay 裡
    try:
        link = KebbiLink.connect(None if args.host in ("", "auto", "{host}") else args.host)
        print(f"Kebbi: {link.host}:{link.port}")
        print("上傳腳本...", link.upload(script_bytes).get("message"))
        clock = link.sync_clock()
        print(f"對時：Wi-Fi 來回最短 {clock.rtt_ms:.1f} ms，誤差 ±{clock.error_ms:.1f} ms")
    except KebbiError as e:
        print(f"錯誤：{e}", file=sys.stderr)
        return 1

    start = time.time() + max(args.delay, 2.0)
    plan = {"videoZeroMs": start * 1000.0 + args.video_offset_ms, "host": link.host,
            "clockErrorMs": clock.error_ms}
    done = threading.Event()
    server = ThreadingHTTPServer(("127.0.0.1", args.http_port), make_handler(args.video, plan, link, done))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{args.http_port}/"
    print(f"影片頁面：{url}")
    if not args.no_browser:
        webbrowser.open(url)

    try:
        print(link.play_at(start).get("message"))
    except KebbiError as e:
        print(f"錯誤：{e}", file=sys.stderr)
        server.shutdown()
        return 1

    # 播放中每 5 秒回報 Kebbi 的進度，順便檢查有沒有偏
    end = start + duration_ms / 1000.0 + 3.0
    try:
        while time.time() < end and not done.is_set():
            done.wait(5.0)
            try:
                status = link.status()
            except KebbiError:
                continue
            pos = status.get("positionMs")
            expected = (time.time() - start) * 1000.0
            if pos is not None and expected >= 0:
                print(f"  t={expected / 1000:6.1f}s  Kebbi 進度差 {pos - expected:+.0f} ms")
            elif not status.get("playing") and expected > 0:
                break
    except KeyboardInterrupt:
        try:
            link.stop()
        except KebbiError:
            pass
    print("結束")
    server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
