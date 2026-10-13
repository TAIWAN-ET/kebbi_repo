"""Kebbi 舞蹈工具的網頁操作介面（本機用）。

    cd pc
    .venv/Scripts/python -m pip install -r requirements.txt -r webui/requirements.txt
    .venv/Scripts/python webui/app.py            # 開啟 http://127.0.0.1:8800

只是把現有的命令列工具包起來：
- 分析：make_dance.py（MediaPipe）或 extract_pose3d.py + make_dance.py --skip-extract（RTMW3D）
- 模擬：sim/pybullet_sim.py
- 傳送：send_script.py 的 call()（和 App 的 ScriptServer 溝通）
- 本機測試：FakeKebbi（和 App 相同的接收器與播放器，只印出指令）

每個工作是一串子行程，輸出即時顯示在網頁上。要加新工具（例如延遲量測、影片同步），
把腳本放進 pc/，在 EXTRA_TOOLS 加一筆即可，網頁會自動出現按鈕。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
import webbrowser
from pathlib import Path

from flask import Flask, abort, jsonify, request, send_file, send_from_directory

HERE = Path(__file__).resolve().parent
PC_DIR = HERE.parent
REPO_DIR = PC_DIR.parent
OUT_DIR = PC_DIR / "out"
UPLOAD_DIR = PC_DIR / "uploads"
VIDEO_DIRS = [UPLOAD_DIR, REPO_DIR / "video_push"]
VIDEO_EXT = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"}
META_FILE = "webui_meta.json"  # 記錄這份結果是哪支影片、用什麼參數產生的

sys.path.insert(0, str(PC_DIR))
from send_script import DEFAULT_PORT, call as kebbi_call  # noqa: E402

PY = sys.executable

# 其他命令列工具：檔案存在才會在網頁上啟用。args 裡的 {host} {port} {script} {video} 會被替換。
EXTRA_TOOLS = [
    {"id": "measure_latency", "label": "延遲量測", "file": "measure_latency.py",
     "args": ["{host}"], "desc": "量測 Kebbi 馬達反應與傳輸延遲"},
    {"id": "check_arm_axes", "label": "手臂軸向檢查", "file": "check_arm_axes.py",
     "args": ["{host}"], "desc": "逐一轉動手臂馬達，確認方向"},
    {"id": "sync_play", "label": "影片同步播放", "file": "sync_play.py",
     "args": ["{host}", "{script}", "{video}"], "desc": "腳本與影片同時開始播放"},
]

app = Flask(__name__, static_folder=str(HERE / "static"), static_url_path="/static")


# ---------------------------------------------------------------------------
# 工作（子行程）管理
# ---------------------------------------------------------------------------

class Job:
    def __init__(self, kind: str, title: str, steps: list, result: str | None = None):
        """steps：每一步是指令清單，或回傳指令清單的函式（要等前一步完成才知道的參數）。"""
        self.id = uuid.uuid4().hex[:8]
        self.kind = kind
        self.title = title
        self.steps = steps
        self.result = result
        self.lines: list[str] = []
        self.state = "running"  # running / done / failed / cancelled
        self.started = time.time()
        self.ended: float | None = None
        self.proc: subprocess.Popen | None = None
        self.cancelled = False
        self.on_success = None
        threading.Thread(target=self._run, daemon=True).start()

    def log(self, text: str) -> None:
        self.lines.append(text.rstrip("\r\n"))

    def _run(self) -> None:
        env = dict(os.environ, PYTHONUNBUFFERED="1", PYTHONIOENCODING="utf-8")
        try:
            for i, cmd in enumerate(self.steps, 1):
                if self.cancelled:
                    break
                if callable(cmd):
                    cmd = cmd()
                self.log(f"$ {' '.join(_short(c) for c in cmd)}")
                self.proc = subprocess.Popen(cmd, cwd=PC_DIR, env=env, stdout=subprocess.PIPE,
                                             stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                             errors="replace", bufsize=1)
                for line in self.proc.stdout:
                    self.log(line)
                code = self.proc.wait()
                if self.cancelled:
                    break
                if code != 0:
                    self.log(f"✗ 第 {i} 步失敗（exit {code}）")
                    self.state = "failed"
                    return
            if self.cancelled:
                self.state = "cancelled"
                self.log("已取消")
                return
            if self.on_success:
                self.on_success()
            self.state = "done"
            self.log("✓ 完成")
        except Exception as e:  # 例如找不到執行檔
            self.log(f"✗ {e}")
            self.state = "failed"
        finally:
            self.ended = time.time()

    def cancel(self) -> None:
        self.cancelled = True
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()

    def to_dict(self, since: int = 0) -> dict:
        return {"id": self.id, "kind": self.kind, "title": self.title, "state": self.state,
                "result": self.result, "started": self.started, "ended": self.ended,
                "lineCount": len(self.lines), "lines": self.lines[since:]}


JOBS: dict[str, Job] = {}
FAKE_KEBBI: Job | None = None


def _short(arg: str) -> str:
    try:
        return str(Path(arg).relative_to(PC_DIR)) if Path(arg).is_absolute() else arg
    except ValueError:
        return arg


def _busy() -> Job | None:
    return next((j for j in JOBS.values() if j.state == "running" and j.kind != "fakekebbi"), None)


# ---------------------------------------------------------------------------
# 路徑工具（只允許存取專案裡的影片與 pc/out 底下的結果）
# ---------------------------------------------------------------------------

def _video_path(path: str) -> Path:
    p = Path(path).resolve()
    if p.suffix.lower() not in VIDEO_EXT or not p.is_file():
        abort(404, "找不到影片")
    if not any(p.is_relative_to(d.resolve()) for d in VIDEO_DIRS):
        abort(403, "只能使用 pc/uploads 或 video_push 裡的影片")
    return p


def _result_dir(name: str) -> Path:
    d = (OUT_DIR / name).resolve()
    if not d.is_relative_to(OUT_DIR.resolve()) or not d.is_dir():
        abort(404, "找不到結果")
    return d


def _safe_name(name: str) -> str:
    keep = "".join(c if c.isalnum() or c in "-_." else "_" for c in name).strip("._")
    return keep or "video"


def _read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------

@app.get("/")
def index():
    return send_from_directory(HERE / "static", "index.html")


@app.get("/api/videos")
def list_videos():
    videos = []
    for d in VIDEO_DIRS:
        if d.is_dir():
            for p in sorted(d.iterdir()):
                if p.suffix.lower() in VIDEO_EXT and p.is_file():
                    videos.append({"path": str(p), "name": p.name, "folder": d.name,
                                   "size": p.stat().st_size, "mtime": p.stat().st_mtime})
    videos.sort(key=lambda v: -v["mtime"])
    return jsonify(videos)


@app.post("/api/videos")
def upload_video():
    f = request.files.get("file")
    if not f or not f.filename:
        abort(400, "沒有檔案")
    name = _safe_name(Path(f.filename).stem) + Path(f.filename).suffix.lower()
    if Path(name).suffix not in VIDEO_EXT:
        abort(400, "不支援的影片格式")
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    dest = UPLOAD_DIR / name
    f.save(dest)
    return jsonify({"path": str(dest), "name": dest.name})


@app.get("/media/video")
def media_video():
    return send_file(_video_path(request.args.get("path", "")), conditional=True)


@app.get("/api/results")
def list_results():
    results = []
    if OUT_DIR.is_dir():
        for d in OUT_DIR.iterdir():
            script = d / "dance_script.json"
            if not script.is_file():
                continue
            data = _read_json(script) or {}
            meta = _read_json(d / META_FILE) or {}
            results.append({"name": d.name, "danceName": data.get("name"),
                            "durationMs": data.get("durationMs"), "steps": len(data.get("steps", [])),
                            "faces": len(data.get("faces", [])), "mtime": script.stat().st_mtime,
                            "video": meta.get("video"), "hasSim": (d / "sim_result.json").is_file()})
    results.sort(key=lambda r: -r["mtime"])
    return jsonify(results)


@app.get("/api/results/<name>")
def get_result(name: str):
    d = _result_dir(name)
    script = _read_json(d / "dance_script.json")
    if script is None:
        abort(404, "dance_script.json 讀不到")
    meta = _read_json(d / META_FILE) or {}
    sim = _read_json(d / "sim_result.json")
    sim_summary = None
    if sim:
        sim_summary = {"tracking": sim.get("tracking"),
                       "overVelocity": sim.get("commands_over_velocity_limit"),
                       "collisions": sim.get("new_collisions", [])[:10]}
    video = meta.get("video")
    if video and not Path(video).is_file():
        video = None
    return jsonify({"name": name, "script": script, "meta": meta, "video": video, "sim": sim_summary,
                    "files": sorted(p.name for p in d.iterdir() if p.is_file())})


@app.get("/api/results/<name>/file/<path:filename>")
def result_file(name: str, filename: str):
    return send_from_directory(_result_dir(name), filename, as_attachment=True)


@app.post("/api/results/<name>/video")
def set_result_video(name: str):
    """沒有記錄來源影片的舊結果，可以手動指定一支影片來預覽。"""
    d = _result_dir(name)
    video = str(_video_path(request.json.get("video", "")))
    meta = _read_json(d / META_FILE) or {}
    meta["video"] = video
    (d / META_FILE).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return jsonify({"ok": True})


@app.post("/api/analyze")
def analyze():
    if (job := _busy()) is not None:
        abort(409, f"還有工作在執行：{job.title}")
    body = request.json or {}
    video = _video_path(body.get("video", ""))
    engine = body.get("engine", "mediapipe")
    interval = int(body.get("interval") or 200)
    max_seconds = body.get("maxSeconds")
    name = _safe_name(body.get("outName") or video.stem)
    out = OUT_DIR / name
    common = ["--interval", str(interval)]
    if max_seconds:
        common += ["--max-seconds", str(float(max_seconds))]
    make = [PY, str(PC_DIR / "make_dance.py"), str(video), "--out-dir", str(out), "--name", name] + common
    if body.get("noFace"):
        make.append("--no-face")

    if body.get("skipExtract"):
        if not (out / "pose_landmarks.csv").is_file():
            abort(400, "沒有上次的姿態資料，不能只重跑映射")
        steps = [make + ["--skip-extract"]]
    elif engine == "rtmw3d":
        steps = [[PY, str(PC_DIR / "extract_pose3d.py"), str(video), "--out-dir", str(out)] + common,
                 make + ["--skip-extract"]]
    else:
        steps = [make]

    job = Job("analyze", f"分析 {video.name}", steps, result=name)
    meta = {"video": str(video), "engine": engine, "interval": interval, "maxSeconds": max_seconds,
            "noFace": bool(body.get("noFace")), "created": time.time()}

    def save_meta() -> None:
        (out / META_FILE).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    job.on_success = save_meta
    JOBS[job.id] = job
    return jsonify(job.to_dict())


@app.post("/api/results/<name>/simulate")
def simulate(name: str):
    if (job := _busy()) is not None:
        abort(409, f"還有工作在執行：{job.title}")
    d = _result_dir(name)
    job = Job("simulate", f"模擬 {name}", [[PY, str(PC_DIR / "sim" / "pybullet_sim.py"),
                                           str(d / "dance_script.json"), "--out", str(d / "sim_result.json")]],
              result=name)
    JOBS[job.id] = job
    return jsonify(job.to_dict())


@app.get("/api/jobs")
def list_jobs():
    return jsonify([j.to_dict(len(j.lines)) for j in sorted(JOBS.values(), key=lambda j: -j.started)][:20])


@app.get("/api/jobs/<job_id>")
def get_job(job_id: str):
    job = JOBS.get(job_id) or abort(404)
    return jsonify(job.to_dict(int(request.args.get("since", 0))))


@app.post("/api/jobs/<job_id>/cancel")
def cancel_job(job_id: str):
    job = JOBS.get(job_id) or abort(404)
    job.cancel()
    return jsonify(job.to_dict(len(job.lines)))


# ---- Kebbi（App 的 ScriptServer） ----

def _target(body: dict) -> tuple[str, int]:
    host = (body.get("host") or "").strip()
    if not host:
        abort(400, "請輸入 Kebbi IP")
    return host, int(body.get("port") or DEFAULT_PORT)


@app.post("/api/kebbi/<action>")
def kebbi(action: str):
    body = request.json or {}
    host, port = _target(body)
    t0 = time.perf_counter()
    if action == "status":
        result = kebbi_call(host, port, "GET", "/status", timeout=3)
    elif action in ("stop", "home", "play"):
        result = kebbi_call(host, port, "POST", "/" + action, b"")
    elif action == "upload":
        script = _result_dir(body.get("result", "")) / "dance_script.json"
        path = "/script?play=1" if body.get("play") else "/script"
        result = kebbi_call(host, port, "POST", path, script.read_bytes())
    else:
        abort(404)
    result["roundTripMs"] = round((time.perf_counter() - t0) * 1000, 1)
    return jsonify(result)


@app.get("/api/fakekebbi")
def fakekebbi_status():
    job = FAKE_KEBBI
    return jsonify(job.to_dict(int(request.args.get("since", 0))) if job else None)


@app.post("/api/fakekebbi/start")
def fakekebbi_start():
    global FAKE_KEBBI
    if FAKE_KEBBI and FAKE_KEBBI.state == "running":
        return jsonify(FAKE_KEBBI.to_dict())
    port = str(int((request.json or {}).get("port") or DEFAULT_PORT))
    # 先編譯（和 make_dance.py 同一份程式碼），再啟動 FakeKebbi
    compile_cmd = [PY, "-c", "import make_dance; make_dance.compile_java()"]

    def run_cmd() -> list[str]:
        import make_dance  # 會載入 MediaPipe，所以等到要用時才 import
        return [make_dance.find_jdk_tool("java"), "-cp", str(make_dance.BUILD_DIR),
                "com.example.myapplication.pc.FakeKebbi", port]
    FAKE_KEBBI = Job("fakekebbi", "FakeKebbi（本機模擬機器人）", [compile_cmd, run_cmd])
    JOBS[FAKE_KEBBI.id] = FAKE_KEBBI
    return jsonify(FAKE_KEBBI.to_dict())


@app.post("/api/fakekebbi/stop")
def fakekebbi_stop():
    if FAKE_KEBBI:
        FAKE_KEBBI.cancel()
    return jsonify({"ok": True})


# ---- 其他工具 ----

@app.get("/api/tools")
def tools():
    return jsonify([dict(t, available=(PC_DIR / t["file"]).is_file()) for t in EXTRA_TOOLS])


@app.post("/api/tools/<tool_id>")
def run_tool(tool_id: str):
    tool = next((t for t in EXTRA_TOOLS if t["id"] == tool_id), None) or abort(404)
    if not (PC_DIR / tool["file"]).is_file():
        abort(404, f"還沒有 {tool['file']}")
    if (job := _busy()) is not None:
        abort(409, f"還有工作在執行：{job.title}")
    body = request.json or {}
    values = {"host": body.get("host", ""), "port": str(body.get("port") or DEFAULT_PORT), "script": "", "video": ""}
    if body.get("result"):
        d = _result_dir(body["result"])
        values["script"] = str(d / "dance_script.json")
        values["video"] = (_read_json(d / META_FILE) or {}).get("video") or ""
    args = [a.format(**values) for a in tool["args"]] + body.get("extraArgs", "").split()
    job = Job("tool", tool["label"], [[PY, str(PC_DIR / tool["file"])] + args], result=body.get("result"))
    JOBS[job.id] = job
    return jsonify(job.to_dict())


@app.errorhandler(400)
@app.errorhandler(403)
@app.errorhandler(404)
@app.errorhandler(409)
def json_error(e):
    return jsonify({"ok": False, "message": getattr(e, "description", str(e))}), e.code


def main() -> None:
    ap = argparse.ArgumentParser(description="Kebbi dance tool web UI")
    ap.add_argument("--port", type=int, default=8800)
    ap.add_argument("--host", default="127.0.0.1", help="0.0.0.0 讓同網段的其他電腦也能開（沒有密碼）")
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()
    url = f"http://127.0.0.1:{a.port}/"
    print(f"Kebbi web UI: {url}")
    if not a.no_browser:
        threading.Timer(1.0, webbrowser.open, [url]).start()
    app.run(host=a.host, port=a.port, threaded=True)


if __name__ == "__main__":
    main()
