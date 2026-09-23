"""一鍵產生 Kebbi 舞蹈腳本：影片 → landmark CSV → dance_script.json。

    python make_dance.py ..\\video_push\\dream_01.mp4
    python make_dance.py dream_01.mp4 --max-seconds 10 --out-dir out/dream_01

流程：
1. extract_pose.py：Python + MediaPipe 抽 33 點（normalized + world）
2. 編譯 App 內的純 Java 類別 + pc/java 的 DanceScriptGenerator（javac，只編不打包）
3. 執行 DanceScriptGenerator：PoseAnalyzer → RobotMapper → DanceScriptBuilder → JSON

產出的 dance_script.json 就是要送給 Kebbi 播放的檔案。
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

from extract_pose import DEFAULT_MODEL, PC_DIR, extract

APP_SRC = PC_DIR.parent / "app" / "src" / "main" / "java" / "com" / "example" / "myapplication"
BUILD_DIR = PC_DIR / "build" / "classes"
GENERATOR_CLASS = "com.example.myapplication.pc.DanceScriptGenerator"

# App 裡「沒有 import android.*」、PC 可以直接編譯的類別。
# 新增純 Java 類別且 PC 端要用時，加到這裡。
SHARED_SOURCES = [
    "model/*.java",
    "math/*.java",
    "io/*.java",
    "analysis/PoseAnalyzer.java",
    "robot/RobotMapper.java",
    "robot/RobotMotor.java",
    "robot/MotionPoseMap.java",
    "robot/DanceScriptBuilder.java",
    "robot/DanceScriptPlayer.java",
    "net/*.java",
]


def find_jdk_tool(name: str) -> str:
    """找 javac / java：JAVA_HOME → Gradle 下載的 JDK → Android Studio 內建 → PATH。"""
    exe = name + (".exe" if os.name == "nt" else "")
    candidates: list[Path] = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    candidates += sorted((Path.home() / ".gradle" / "jdks").glob(f"*/bin/{exe}"), reverse=True)
    candidates += [
        Path(r"C:\Program Files\Android\Android Studio\jbr\bin") / exe,
        Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin") / exe,
    ]
    for path in candidates:
        if path.is_file():
            return str(path)
    found = shutil.which(name)
    if found:
        return found
    sys.exit(f"找不到 {name}。請安裝 JDK 17+，或設定 JAVA_HOME。")


def compile_java() -> None:
    sources: list[Path] = []
    for pattern in SHARED_SOURCES:
        sources += sorted(APP_SRC.glob(pattern))
    sources += sorted((PC_DIR / "java").rglob("*.java"))
    sources += sorted((PC_DIR / "stubs").rglob("*.java"))

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    argfile = BUILD_DIR.parent / "sources.txt"
    argfile.write_text("\n".join(f'"{s.as_posix()}"' for s in sources), encoding="utf-8")

    # --release 8：產出的 class 用系統 PATH 上較舊的 java（例如 Java 8）也能直接跑 FakeKebbi
    cmd = [find_jdk_tool("javac"), "--release", "8", "-encoding", "UTF-8", "-nowarn", "-implicit:none",
           "-d", str(BUILD_DIR), f"@{argfile}"]
    print(f"[2/3] javac {len(sources)} files")
    subprocess.run(cmd, check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Video -> Kebbi dance_script.json")
    parser.add_argument("video", type=Path)
    parser.add_argument("--out-dir", type=Path, default=None,
                        help="output folder (default: pc/out/<video name>)")
    parser.add_argument("--name", default=None, help="dance name in the script (default: video name)")
    parser.add_argument("--interval", type=int, default=200, help="sampling interval in ms (default 200)")
    parser.add_argument("--max-seconds", type=float, default=None, help="only analyze the first N seconds")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--skip-extract", action="store_true",
                        help="reuse existing CSVs in --out-dir (only re-run mapping)")
    args = parser.parse_args(argv)
    # 子行程（javac / java）的輸出和這裡的 print 交錯時才不會亂序
    sys.stdout.reconfigure(line_buffering=True)

    out_dir = (args.out_dir or PC_DIR / "out" / args.video.stem).resolve()
    name = args.name or args.video.stem
    norm_csv = out_dir / "pose_landmarks.csv"
    world_csv = out_dir / "pose_world_landmarks.csv"
    script_json = out_dir / "dance_script.json"

    # Step1：MediaPipe（最花時間；只調 RobotMapper 參數時可以 --skip-extract 跳過）
    if args.skip_extract:
        if not norm_csv.is_file():
            sys.exit(f"--skip-extract 但找不到 {norm_csv}")
        print(f"[1/3] skip extract, reuse {norm_csv}")
    else:
        print(f"[1/3] MediaPipe: {args.video}")
        _, _, stats = extract(args.video, out_dir, args.interval, args.max_seconds, args.model)
        print(f"      fps={stats['fps']:.2f} sampled={stats['sampled']} detected={stats['detected']}")
        if stats["detected"] == 0:
            sys.exit("影片裡沒有偵測到人。")

    # Step2：編譯共用的純 Java 程式碼
    compile_java()

    # Step3：產生腳本
    print("[3/3] DanceScriptGenerator")
    world_arg = str(world_csv) if world_csv.is_file() else "-"
    subprocess.run([find_jdk_tool("java"), "-cp", str(BUILD_DIR), GENERATOR_CLASS,
                    str(norm_csv), world_arg, str(script_json), name, str(args.interval)],
                   check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
