#!/usr/bin/env python3
"""Kebbi PC analysis entry point.

Examples:
  python run.py --list
  python run.py --version v0_face --video ..\\video_push\\37421255165-1-192.mp4
  python run.py --all --video ..\\video_push\\37421255165-1-192.mp4
  python run.py --version v0_face --video clip.mp4 --gallery gallery
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from core.runner import discover_versions, run_version  # noqa: E402


def parse_params(items: list[str]) -> dict:
    params: dict[str, str] = {}
    for item in items:
        if "=" not in item:
            raise SystemExit(f"--param needs key=value, got: {item}")
        key, value = item.split("=", 1)
        params[key.strip()] = value.strip()
    return params


def print_versions(versions: list[dict]) -> None:
    if not versions:
        print("no versions found under pc/versions/")
        return
    print("available versions:")
    for v in versions:
        print(f"  {v['id']:<16} {v.get('name', '')}")
        if v.get("description"):
            print(f"      {v['description']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Kebbi PC analysis (versioned)")
    parser.add_argument("--list", action="store_true", help="list drop-in versions")
    parser.add_argument("--version", help="version id, e.g. v0_face")
    parser.add_argument("--all", action="store_true", help="run every version")
    parser.add_argument("--video", help="input video path")
    parser.add_argument("--out", default=str(HERE / "output"), help="output root")
    parser.add_argument("--gallery", help="face gallery root (subfolder = person name)")
    parser.add_argument("--param", action="append", default=[], help="override manifest param: key=value")
    args = parser.parse_args()

    versions = discover_versions(HERE)

    if args.list or (not args.version and not args.all):
        print_versions(versions)
        if not args.list:
            print("\nuse --version <id> --video <file> to run")
        return 0

    if args.all:
        targets = versions
    else:
        targets = [v for v in versions if v["id"] == args.version]
        if not targets:
            print(f"version not found: {args.version}")
            print_versions(versions)
            return 2

    if not args.video:
        print("--video is required")
        return 2
    if not Path(args.video).exists():
        print(f"video not found: {args.video}")
        return 2

    params = parse_params(args.param)
    gallery = Path(args.gallery) if args.gallery else None

    failed = 0
    for meta in targets:
        print(f"\n=== {meta['id']} ===")
        missing = [
            module for module in meta.get("requires", [])
            if importlib.util.find_spec(module) is None
        ]
        if missing:
            failed += 1
            print(f"[error] {meta['id']} missing Python modules: {', '.join(missing)}")
            print("        run: python -m pip install -r requirements.txt")
            continue
        try:
            run_version(meta, args.video, Path(args.out), params=params, gallery=gallery)
        except Exception as exc:  # noqa: BLE001 - keep running other versions
            failed += 1
            print(f"[error] {meta['id']}: {exc}")
            traceback.print_exc()
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
