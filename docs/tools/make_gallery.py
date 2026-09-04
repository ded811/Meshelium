"""Rebuild the side-by-side gallery images from the raw screenshots.

The published gallery composites had no generator kept with them, so
re-cutting one meant redrawing it by hand in an image editor. This is
that generator.

Run:
    python docs/tools/make_gallery.py [GALLERY_DIR] [OUT_DIR]

GALLERY_DIR defaults to ../misc/gallery-2026-08-12 relative to the repo,
which is where the 1920x1080 source screenshots and their bench JSON
live. That folder is outside the repo on purpose - it holds 3 MB PNGs -
so this script takes it as a path rather than assuming it is committed.

WHAT CHANGED FROM THE ORIGINALS. The published composites downscaled
each 1920x1080 screenshot to 960 wide, which threw away three quarters
of the pixels before anyone saw them. These use the screenshots at
NATIVE resolution and crop vertically instead, so the terrain is sharp
at full size. Same layout, same wording, same measured numbers.

THE NUMBERS ARE NOT TYPED IN. Every frame rate on these images is the
median of the 600 recorded frame times in the matching JSON, computed at
render time. That is where the published 598 and 104 came from, and
recomputing them here means a caption can never drift from its data.
"""

import json
import statistics
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT_DIR = None
try:
    import matplotlib
    FONT_DIR = Path(matplotlib.__file__).parent / "mpl-data" / "fonts" / "ttf"
except Exception:  # pragma: no cover - matplotlib is present in this repo's env
    pass

BAR_H = 150           # caption strip under each panel
CROP_H = 960          # rows kept from each 1080-row screenshot
DIVIDER = 10          # gap between the two panels
BAR_BG = (17, 21, 30)
DIVIDER_BG = (10, 12, 18)
MESH_BLUE = (77, 155, 255)
OFF_GREY = (170, 178, 189)
SUB_GREY = (154, 164, 178)


def _font(name, size):
    if FONT_DIR is not None:
        p = FONT_DIR / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


def median_fps(gallery: Path, json_name: str, leg: str) -> int:
    """The published number: median of the recorded CPU frame times."""
    data = json.loads((gallery / json_name).read_text(encoding="utf-8"))
    nanos = data[leg]["cpuFrameNanos"]
    return round(1e9 / statistics.median(nanos))


def panel(img_path: Path, fps: int, caption: str, accent) -> Image.Image:
    """One screenshot at native width, cropped, with a caption strip."""
    shot = Image.open(img_path).convert("RGB")
    w, h = shot.size
    # Keep the horizon: crop from a third down rather than centre, which
    # is what the original composites did by eye.
    top = max(0, min(h - CROP_H, (h - CROP_H) // 3))
    shot = shot.crop((0, top, w, top + CROP_H))

    out = Image.new("RGB", (w, CROP_H + BAR_H), BAR_BG)
    out.paste(shot, (0, 0))

    d = ImageDraw.Draw(out)
    d.line([(0, CROP_H), (w, CROP_H)], fill=accent, width=3)
    # Sized against the 1920-wide panel, not the old 960-wide one: the
    # first remake kept the original's point sizes on an image twice as
    # wide, which made every caption look half the size it used to.
    d.text((34, CROP_H + 22), f"{fps} FPS", font=_font("DejaVuSans-Bold.ttf", 70), fill=accent)
    d.text((36, CROP_H + 102), caption, font=_font("DejaVuSans.ttf", 33), fill=SUB_GREY)
    return out


def side_by_side(left: Image.Image, right: Image.Image, path: Path):
    w = left.width + DIVIDER + right.width
    out = Image.new("RGB", (w, left.height), DIVIDER_BG)
    out.paste(left, (0, 0))
    out.paste(right, (left.width + DIVIDER, 0))
    out.save(path, optimize=True)
    print(f"wrote {path}  ({out.width}x{out.height})")


def main(gallery: Path, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1 - the same view, both renderers, render distance 64.
    mesh = median_fps(gallery, "rd64-v11.json", "meshelium")
    van = median_fps(gallery, "rd64-v11.json", "vanilla")
    side_by_side(
        panel(gallery / "rd64-vanilla-v11.png", van,
              "Minecraft's renderer, render distance 64", OFF_GREY),
        panel(gallery / "rd64-meshelium-v11.png", mesh,
              "Meshelium, render distance 64, the same view", MESH_BLUE),
        out_dir / "01-same-view-64-chunks.png")
    print(f"   render distance 64: {mesh} against {van} = {mesh / van:.1f}x")

    # 2 - as far as Minecraft goes, against twice that with the mod.
    van32 = median_fps(gallery, "rd32.json", "vanilla")
    mesh64 = median_fps(gallery, "rd64.json", "meshelium")
    side_by_side(
        panel(gallery / "rd32-vanilla.png", van32,
              "Minecraft at render distance 32, as far as it goes", OFF_GREY),
        panel(gallery / "rd64-meshelium.png", mesh64,
              "Meshelium at 64, twice as far and still faster", MESH_BLUE),
        out_dir / "02-twice-as-far.png")
    print(f"   32 vanilla {van32} against 64 Meshelium {mesh64}")


if __name__ == "__main__":
    repo = Path(__file__).resolve().parents[2]
    gallery = Path(sys.argv[1]) if len(sys.argv) > 1 else repo.parent / "misc" / "gallery-2026-08-12"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else gallery / "remade"
    if not gallery.is_dir():
        sys.exit(f"gallery dir not found: {gallery}")
    main(gallery, out)
