"""Version discovery + execution. New versions are drop-in folders."""
from __future__ import annotations

import importlib.util
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .models import ensure_models

PC_ROOT = Path(__file__).resolve().parent.parent


@dataclass
class Context:
    version_id: str
    video: Path
    out_dir: Path
    params: dict[str, Any]
    models: dict[str, Path]
    log: Callable[[str], None] = print
    extra: dict[str, Any] = field(default_factory=dict)


def discover_versions(pc_root: Path = PC_ROOT) -> list[dict]:
    """Scan versions/*/manifest.json. Folders starting with _ are templates."""
    items: list[dict] = []
    for mf in sorted((pc_root / "versions").glob("*/manifest.json")):
        if mf.parent.name.startswith("_"):
            continue
        try:
            data = json.loads(mf.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - report and continue
            print(f"[versions] skip {mf}: {exc}")
            continue
        data["id"] = str(data.get("id") or mf.parent.name)
        data["_dir"] = mf.parent
        items.append(data)
    return items


def _load_plugin(meta: dict):
    plugin = meta["_dir"] / "plugin.py"
    if not plugin.exists():
        raise FileNotFoundError(f"plugin.py not found: {plugin}")
    spec = importlib.util.spec_from_file_location(f"kebbi_version_{meta['id']}", plugin)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {plugin}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if not hasattr(mod, "run"):
        raise AttributeError(f"{plugin} must define run(ctx)")
    return mod


def run_version(
    meta: dict,
    video: str | Path,
    out_root: str | Path,
    params: dict[str, Any] | None = None,
    gallery: str | Path | None = None,
    log: Callable[[str], None] = print,
) -> dict:
    version_id = meta["id"]
    out_dir = Path(out_root) / version_id
    out_dir.mkdir(parents=True, exist_ok=True)

    merged: dict[str, Any] = dict(meta.get("params") or {})
    merged.update(params or {})
    if gallery:
        merged["gallery"] = str(gallery)

    models = ensure_models(PC_ROOT / "models", log=log)
    ctx = Context(
        version_id=version_id,
        video=Path(video),
        out_dir=out_dir,
        params=merged,
        models=models,
        log=log,
    )

    started = time.time()
    module = _load_plugin(meta)
    result = module.run(ctx)
    if not isinstance(result, dict):
        raise TypeError(f"{version_id}.run(ctx) must return dict, got {type(result)!r}")

    result.setdefault("schema", "kebbi.pc.result/1")
    result["version"] = version_id
    result["versionName"] = meta.get("name", version_id)
    result["generatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    result["elapsedSec"] = round(time.time() - started, 2)

    latest = out_dir / "latest.json"
    latest.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    history = out_dir / "history"
    history.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    (history / f"{stamp}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    log(f"[run] {version_id}: {latest} ({result['elapsedSec']}s)")
    return result
