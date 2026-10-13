"""產生 data.js（供 index.html 使用）：腳本步驟 + 每幀事件。用法：python build_report.py"""
import csv, json, pathlib
here = pathlib.Path(__file__).resolve().parent
script = json.loads((here.parent / "out/rtmw3d/dance_script.json").read_text(encoding="utf-8"))
csv_path = pathlib.Path(r"D:\下載\專題打包_20260928\VIDEOTEST\output\rtmlib.csv")  # 33 幀出貨版
demo_csv = here.parent / "out/rtmlib_demo/pose_landmarks.csv"
data = {"script": script, "shipped_counts": {"pixel": {"LEAN": 26, "HAND_UP": 7, "NEUTRAL": 0},
                                              "normalized": {"LEAN": 1, "HAND_UP": 6, "NEUTRAL": 26}}}
pose_csv = here.parent / "out/rtmw3d/pose_landmarks.csv"
if pose_csv.exists():  # 偵測到的 2D 骨架（疊在影片上）：nose, 肩 11/12, 肘 13/14, 腕 15/16, 髖 23/24
    keep = [0, 11, 12, 13, 14, 15, 16, 23, 24]
    frames = {}
    for r in csv.DictReader(open(pose_csv, encoding="utf-8")):
        i = int(r["landmark_index"])
        if i in keep:
            frames.setdefault(int(r["time_ms"]), {})[i] = [round(float(r["x"]), 4), round(float(r["y"]), 4), round(float(r["visibility"]), 2)]
    data["pose"] = [{"t": t, "p": [f[i] for i in keep]} for t, f in sorted(frames.items())]
sim_path = here.parent / "sim/sim_result.json"
if sim_path.exists():
    data["sim"] = json.loads(sim_path.read_text(encoding="utf-8"))
(here / "data.js").write_text("const DATA = " + json.dumps(data, ensure_ascii=False) + ";", encoding="utf-8")
print("ok", len(script["steps"]), "steps")
