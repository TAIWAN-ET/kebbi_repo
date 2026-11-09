"""Template plugin.

1. Copy pc/versions/_template -> pc/versions/v1_my_idea
2. Edit manifest.json (id/name/description/params)
3. Implement run(ctx) below.
4. Run: python run.py --version v1_my_idea --video clip.mp4

ctx fields:
  ctx.video     Path
  ctx.out_dir   Path (already created; put artifacts here)
  ctx.params    dict (manifest params + --param overrides)
  ctx.models    {"yunet": Path, "sface": Path, "ferplus": Path}
  ctx.log       callable(str)
"""
from __future__ import annotations


def run(ctx):
    ctx.log(f"[template] video={ctx.video}")
    return {
        "video": {"path": str(ctx.video)},
        "frames": [],
        "summary": {"note": "template only"},
    }
