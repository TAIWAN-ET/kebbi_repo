"""從 kebbi_3d_model/robot.xml 產生 PyBullet 可載入的 URDF。

- 網格：*.stlx（binary STL，頂點 = (z, x, y) * 0.1）與 *.stl（(x, y, z) * 0.01）轉成正常單位（公尺）的 binary STL
- 關節：10 顆馬達（頭 2、每臂 肩 Z/Y/X + 肘 Y）做成 revolute 並套用官方角度範圍；其餘全部 fixed
- 右臂的 shoulder_x / shoulder_z 軸與左臂相反（實測：同樣的正值，左臂外展、右臂內收），
  所以馬達角度 → 關節角度要乘 MOTOR_SIGN，限位也一起翻

用法：python build_urdf.py   （輸出到 pc/sim/build/kebbi.urdf）
"""

from __future__ import annotations

import math
import re
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODEL_DIR = HERE.parents[2] / "kebbi_3d_model"
OUT_DIR = HERE / "build"

# motor id → (joint name, min deg, max deg, sign, velocityLimit deg/s)
# 範圍來源：RobotMapper / kebbi_3d_model/README.md（NUWA 官方 Motor angle range table）
# 速度上限：官方 SDK ctlMotor 的 setSpeedInDegreePerSec 範圍 0~200（未公布各馬達更細的極限）；頭上下沿用 hardware.xml 的 120
MOTORS = {
    1: ("neck_y", -20, 20, 1, 120),
    2: ("neck_z", -31, 31, 1, 200),
    3: ("right_shoulder_z", -85, 5, -1, 200),
    4: ("right_shoulder_y", -200, 70, 1, 200),
    5: ("right_shoulder_x", -3, 100, -1, 200),
    6: ("right_elbow_y", -80, 0, 1, 200),
    7: ("left_shoulder_z", -85, 5, 1, 200),
    8: ("left_shoulder_y", -200, 70, 1, 200),
    9: ("left_shoulder_x", -3, 100, 1, 200),
    10: ("left_elbow_y", -80, 0, 1, 200),
}
JOINT_TO_MOTOR = {v[0]: k for k, v in MOTORS.items()}

# 依「帶動這個零件的主要關節」上色（與網頁曲線同色）；不受馬達帶動的身體為淺灰
JOINT_COLORS = {
    "neck_y": (0.65, 0.55, 0.98), "neck_z": (0.22, 0.74, 0.97),
    "shoulder_z": (0.98, 0.44, 0.52), "shoulder_y": (0.98, 0.45, 0.09),
    "shoulder_x": (0.98, 0.80, 0.08), "elbow_y": (0.29, 0.87, 0.50),
}
BODY_COLOR = (0.80, 0.82, 0.86)


def color_key(joint_name: str) -> str | None:
    for k in JOINT_COLORS:
        if joint_name.endswith(k):
            return k
    return None


def read_stl(path: Path) -> list[tuple[float, ...]]:
    data = path.read_bytes()
    n = struct.unpack_from("<I", data, 80)[0]
    tris = []
    for i in range(n):
        vals = struct.unpack_from("<12fH", data, 84 + i * 50)
        tris.append(vals[3:12])
    return tris


def write_stl(path: Path, tris: list[tuple[float, ...]]) -> None:
    with open(path, "wb") as f:
        f.write(b"kebbi".ljust(80, b"\0"))
        f.write(struct.pack("<I", len(tris)))
        for t in tris:
            f.write(struct.pack("<12fH", 0, 0, 0, *t, 0))


def convert_mesh(src: Path, dst: Path) -> None:
    tris = read_stl(src)
    out = []
    for t in tris:
        v = []
        for i in range(3):
            x, y, z = t[3 * i: 3 * i + 3]
            v += [z * 0.1, x * 0.1, y * 0.1] if src.suffix == ".stlx" else [x * 0.01, y * 0.01, z * 0.01]
        out.append(tuple(v))
    write_stl(dst, out)


def build(out_dir: Path = OUT_DIR) -> Path:
    xml = (MODEL_DIR / "robot.xml").read_text(encoding="utf-8")
    xml = re.sub(r"<!--.*?-->", "", xml, flags=re.S)

    def attr(body: str, tag: str, name: str, default: str) -> str:
        m = re.search(rf"<{tag}\b[^>]*\b{name}=\"([^\"]*)\"", body)
        return m.group(1) if m else default

    joints = []
    for m in re.finditer(r'<joint\s+name="([^"]+)"[^>]*>(.*?)</joint>', xml, flags=re.S):
        b = m.group(2)
        joints.append(dict(name=m.group(1), parent=attr(b, "parent", "link", ""), child=attr(b, "child", "link", ""),
                           xyz=attr(b, "origin", "xyz", "0 0 0"), rpy=attr(b, "origin", "rpy", "0 0 0"),
                           axis=attr(b, "axis", "xyz", "0 0 1")))
    links = {}
    for m in re.finditer(r'<link\s+name="([^"]+)"\s*>(.*?)</link>', xml, flags=re.S):
        vis = re.search(r"<visual>(.*?)</visual>", m.group(2), flags=re.S)
        mesh = attr(vis.group(1), "mesh", "filename", "") if vis else ""
        links[m.group(1)] = mesh
    # 自閉合 <link name="base_link"/> 也算
    for m in re.finditer(r'<link\s+name="([^"]+)"\s*/>', xml):
        links.setdefault(m.group(1), "")

    mesh_dir = out_dir / "meshes"
    mesh_dir.mkdir(parents=True, exist_ok=True)
    joints = [j for j in joints if j["parent"] and j["child"]]
    used = set()
    for j in joints:
        used.add(j["parent"]); used.add(j["child"])

    # 每個零件由哪顆馬達帶動：沿 parent 往上找最近的馬達關節
    parent_joint = {j["child"]: j for j in joints}

    def driver(link: str):
        while link in parent_joint:
            j = parent_joint[link]
            if j["name"] in JOINT_TO_MOTOR:
                return color_key(j["name"])
            link = j["parent"]
        return None

    lines = ['<?xml version="1.0"?>', '<robot name="kebbi">']
    for name in sorted(used):
        mesh = links.get(name, "")
        has = bool(mesh)
        lines.append(f'  <link name="{name}">')
        lines.append('    <inertial><origin xyz="0 0 0" rpy="0 0 0"/>'
                     f'<mass value="{0.05 if has else 0.001}"/>'
                     '<inertia ixx="0.0001" ixy="0" ixz="0" iyy="0.0001" iyz="0" izz="0.0001"/></inertial>')
        if has:
            stl = mesh_dir / (Path(mesh).stem + ".stl")
            convert_mesh(MODEL_DIR / mesh, stl)
            rel = f"meshes/{stl.name}"
            g = f'<geometry><mesh filename="{rel}"/></geometry>'
            key = driver(name)
            r, gr, b = JOINT_COLORS[key] if key else BODY_COLOR
            lines.append(f'    <visual>{g}<material name="m_{name}"><color rgba="{r} {gr} {b} 1"/></material></visual>')
            lines.append(f"    <collision>{g}</collision>")
        lines.append("  </link>")

    for j in joints:
        mid = JOINT_TO_MOTOR.get(j["name"])
        if mid is None:
            lines.append(f'  <joint name="{j["name"]}" type="fixed"><parent link="{j["parent"]}"/>'
                         f'<child link="{j["child"]}"/><origin xyz="{j["xyz"]}" rpy="{j["rpy"]}"/></joint>')
            continue
        _, lo, hi, sign, vel = MOTORS[mid]
        rlo, rhi = (lo, hi) if sign == 1 else (-hi, -lo)
        lines.append(
            f'  <joint name="{j["name"]}" type="revolute"><parent link="{j["parent"]}"/><child link="{j["child"]}"/>'
            f'<origin xyz="{j["xyz"]}" rpy="{j["rpy"]}"/><axis xyz="{j["axis"]}"/>'
            f'<limit lower="{math.radians(rlo):.6f}" upper="{math.radians(rhi):.6f}" effort="5" '
            f'velocity="{math.radians(vel):.6f}"/></joint>')
    lines.append("</robot>")
    urdf = out_dir / "kebbi.urdf"
    urdf.write_text("\n".join(lines), encoding="utf-8")
    return urdf


if __name__ == "__main__":
    print("wrote", build())
