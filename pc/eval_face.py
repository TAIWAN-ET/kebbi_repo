"""標註過的人臉照片 → 帶 label 欄的 blendshape CSV，給 FaceExpressionEval 量表情辨識準確度。

照片依表情放在子資料夾（資料夾名稱就是標註）：

    faces/
      happy/*.jpg     （也接受 happiness、surprise、anger、sadness 等常見寫法）
      surprised/*.jpg
      angry/*.jpg
      sad/*.jpg
      neutral/*.jpg

    python eval_face.py faces/ --out out/face_eval.csv
    python make_dance.py <影片> --skip-extract        # 會順便編譯 Java
    java -cp build/classes com.example.myapplication.pc.FaceExpressionEval out/face_eval.csv

印出混淆矩陣後，到 app/.../analysis/FaceExpressionAnalyzer.java 調門檻，再跑一次 FaceExpressionEval。
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

from extract_face import DEFAULT_FACE_MODEL, HEAD_MIN_SIZE_PX, ensure_model

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Labelled face photos -> blendshape CSV with label column")
    parser.add_argument("root", type=Path, help="folder with one sub-folder per expression")
    parser.add_argument("--out", type=Path, default=Path("out/face_eval.csv"))
    parser.add_argument("--model", type=Path, default=DEFAULT_FACE_MODEL)
    args = parser.parse_args(argv)

    options = vision.FaceLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(ensure_model(args.model))),
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=0.3,
        output_face_blendshapes=True,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    names: list[str] | None = None
    rows: list[list[str]] = []
    seen = detected = 0
    with vision.FaceLandmarker.create_from_options(options) as landmarker:
        for folder in sorted(p for p in args.root.iterdir() if p.is_dir()):
            for image_path in sorted(folder.iterdir()):
                if image_path.suffix.lower() not in IMAGE_SUFFIXES:
                    continue
                seen += 1
                bgr = cv2.imread(str(image_path))
                if bgr is None:
                    continue
                # 小照片放大到和 extract_face.py 頭部框相同的尺寸
                side = max(bgr.shape[:2])
                if side < HEAD_MIN_SIZE_PX:
                    f = HEAD_MIN_SIZE_PX / side
                    bgr = cv2.resize(bgr, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                result = landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
                if not result.face_blendshapes:
                    continue
                detected += 1
                scores = {c.category_name: float(c.score) for c in result.face_blendshapes[0]}
                names = names or list(scores)
                rows.append([folder.name, image_path.name] + [f"{scores.get(n, 0.0):.4f}" for n in names])

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["label", "image"] + (names or []))
        writer.writerows(rows)
    print(f"images={seen} faces={detected}")
    print(f"wrote {args.out}")
    return 0 if detected else 1


if __name__ == "__main__":
    sys.exit(main())
