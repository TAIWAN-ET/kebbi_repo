"""Face detection / recognition / emotion, shared by all PC versions."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import cv2
import numpy as np
import onnxruntime as ort

# FER+ output order (ONNX model zoo). "contempt" is folded into Neutral so the
# project keeps its 7-class vocabulary.
FERPLUS_RAW = ["neutral", "happiness", "surprise", "sadness", "anger", "disgust", "fear", "contempt"]
FERPLUS_7 = ["Neutral", "Happy", "Surprise", "Sad", "Angry", "Disgust", "Fear", "Neutral"]

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


@dataclass
class FaceResult:
    bbox: tuple[int, int, int, int]  # x, y, w, h (pixels)
    score: float
    name: str
    identity_score: float
    emotion: str
    emotion_score: float

    def to_dict(self, video_w: int, video_h: int) -> dict:
        x, y, w, h = self.bbox
        return {
            "bbox": [x, y, w, h],
            "bboxNorm": [
                round(x / max(1, video_w), 5),
                round(y / max(1, video_h), 5),
                round(w / max(1, video_w), 5),
                round(h / max(1, video_h), 5),
            ],
            "score": round(self.score, 4),
            "name": self.name,
            "identityScore": round(max(0.0, min(1.0, self.identity_score)), 4),
            "emotion": self.emotion,
            "emotionScore": round(self.emotion_score, 4),
        }


class FaceEngine:
    def __init__(
        self,
        models: dict[str, Path],
        det_threshold: float = 0.7,
        min_face_px: int = 40,
        gallery_dir: str | Path | None = None,
        identity_threshold: float = 0.363,
        log: Callable[[str], None] = print,
    ) -> None:
        self.det_threshold = float(det_threshold)
        self.min_face_px = int(min_face_px)
        self.identity_threshold = float(identity_threshold)
        self.log = log

        self.detector = cv2.FaceDetectorYN.create(
            str(models["yunet"]), "", (320, 320), self.det_threshold, 0.3, 5000
        )
        self.recognizer = cv2.FaceRecognizerSF.create(str(models["sface"]), "")
        self.emotion_sess = ort.InferenceSession(
            str(models["ferplus"]), providers=["CPUExecutionProvider"]
        )
        self.emotion_input = self.emotion_sess.get_inputs()[0].name
        self.gallery = self._load_gallery(gallery_dir) if gallery_dir else {}

    # ---------------------------------------------------------------- detection
    def _detect(self, frame: np.ndarray) -> list[np.ndarray]:
        h, w = frame.shape[:2]
        self.detector.setInputSize((w, h))
        _, faces = self.detector.detect(frame)
        if faces is None:
            return []
        keep = []
        for f in faces:
            x, y, bw, bh = [int(v) for v in f[:4]]
            score = float(f[-1])
            if score < self.det_threshold:
                continue
            if bw < self.min_face_px or bh < self.min_face_px:
                continue
            if bw <= 0 or bh <= 0:
                continue
            keep.append(f)
        keep.sort(key=lambda f: int(f[2]) * int(f[3]), reverse=True)
        return keep

    # ---------------------------------------------------------------- emotion
    def _emotion(self, aligned_bgr: np.ndarray) -> tuple[str, float]:
        gray = cv2.cvtColor(aligned_bgr, cv2.COLOR_BGR2GRAY)
        # FER+ accepts raw 0..255 grayscale (normalization is inside the graph).
        gray = cv2.resize(gray, (64, 64)).astype(np.float32)
        blob = gray.reshape(1, 1, 64, 64)
        logits = self.emotion_sess.run(None, {self.emotion_input: blob})[0].ravel().astype(np.float64)
        exp = np.exp(logits - logits.max())
        probs = exp / max(1e-12, exp.sum())
        idx = int(probs.argmax())
        return FERPLUS_7[idx], float(probs[idx])

    # ---------------------------------------------------------------- identity
    def _identify(self, feat: np.ndarray) -> tuple[str, float]:
        if not self.gallery:
            return "Unknown", 0.0
        feat = feat.ravel()
        norm = float(np.linalg.norm(feat)) + 1e-9
        best_name = "Unknown"
        best = -1.0
        for name, vec in self.gallery.items():
            sim = float(np.dot(feat, vec) / (norm * (float(np.linalg.norm(vec)) + 1e-9)))
            if sim > best:
                best, best_name = sim, name
        if best >= self.identity_threshold:
            return best_name, best
        return "Unknown", best

    # ---------------------------------------------------------------- gallery
    def _load_gallery(self, gallery_dir: str | Path) -> dict[str, np.ndarray]:
        root = Path(gallery_dir)
        if not root.exists():
            self.log(f"[face] gallery not found: {root}")
            return {}
        gallery: dict[str, np.ndarray] = {}
        for person_dir in sorted(p for p in root.iterdir() if p.is_dir()):
            vectors: list[np.ndarray] = []
            for img_path in sorted(p for p in person_dir.iterdir() if p.suffix.lower() in IMAGE_EXT):
                img = cv2.imread(str(img_path))
                if img is None:
                    continue
                faces = self._detect(img)
                if not faces:
                    self.log(f"[face] gallery: no face in {img_path.name}")
                    continue
                aligned = self.recognizer.alignCrop(img, faces[0])
                vectors.append(self.recognizer.feature(aligned).ravel())
            if vectors:
                vec = np.mean(vectors, axis=0)
                gallery[person_dir.name] = vec / (float(np.linalg.norm(vec)) + 1e-9)
                self.log(f"[face] gallery: {person_dir.name} ({len(vectors)} images)")
        return gallery

    # ---------------------------------------------------------------- analyze
    def analyze(self, frame: np.ndarray) -> list[FaceResult]:
        results: list[FaceResult] = []
        for f in self._detect(frame):
            x, y, bw, bh = [int(v) for v in f[:4]]
            aligned = self.recognizer.alignCrop(frame, f)
            if self.gallery:
                feat = self.recognizer.feature(aligned)
                name, sim = self._identify(feat)
            else:
                name, sim = "Unknown", 0.0
            emotion, emo_score = self._emotion(aligned)
            results.append(
                FaceResult(
                    bbox=(x, y, bw, bh),
                    score=float(f[-1]),
                    name=name,
                    identity_score=sim,
                    emotion=emotion,
                    emotion_score=emo_score,
                )
            )
        return results

    # ---------------------------------------------------------------- drawing
    @staticmethod
    def draw(frame: np.ndarray, faces: Iterable[FaceResult]) -> np.ndarray:
        height, width = frame.shape[:2]
        scale = max(0.8, min(1.6, width / 1280.0 * 1.1))
        thickness = max(2, int(round(scale * 2)))
        pad = max(4, int(round(scale * 4)))
        font = cv2.FONT_HERSHEY_SIMPLEX

        for r in faces:
            x, y, w, h = r.bbox
            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), thickness)
            label = f"{r.name} {r.emotion} {r.emotion_score:.2f}"
            (tw, th), _ = cv2.getTextSize(label, font, scale, thickness)

            # Prefer above the face; move below it when there is no room at the top.
            if y - pad - th >= 0:
                y_text = y - pad
            else:
                y_text = min(height - pad, y + h + th + pad)

            x_text = max(0, min(x, width - tw - pad * 2))
            cv2.rectangle(
                frame,
                (x_text, max(0, y_text - th - pad)),
                (min(width - 1, x_text + tw + pad * 2), min(height - 1, y_text + pad)),
                (0, 0, 0),
                -1,
            )
            cv2.putText(
                frame, label, (x_text + pad, y_text),
                font, scale, (0, 255, 0), thickness, cv2.LINE_AA,
            )
        return frame
