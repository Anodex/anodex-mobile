"""Regenerate the launcher icon and the in-app mark from Anodex's real logo.

The art is never redrawn by hand. The source of truth is the desktop repo's
`src/renderer/assets/title-logo.png` — the bare "A" that `AnodexLogo`'s 'mark' variant shows on the
app's own chrome. This script only trims, scales and pads it to Android's geometry.

Adaptive icons are 108dp square and every launcher mask is round or nearly round, so what
survives is a **circle** roughly 72dp across. That is the whole subtlety here, and getting it
wrong is what clipped the feet off the A: fitting the mark's *longest side* to 60% of the canvas
sounds safe, but the mark is nearly square, so its bounding box diagonal came to 88dp — the
corners sat well outside the circle, and the A's two feet live exactly in those corners.

So the mark is scaled by its **diagonal**, not its longest side. Whatever the art, its bounding
box then fits inside the safe circle whichever way round it is.

Usage, from the repo root:

    python tools/generate_icons.py --source ../Anodex4/src/renderer/assets/title-logo.png

Requires Pillow (`pip install pillow`). Commit the regenerated PNGs.
"""

from __future__ import annotations

import argparse
import math
import os

from PIL import Image

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO_ROOT, "app", "src", "main", "res")

#: Adaptive-icon layers are 108dp square.
ADAPTIVE_DP = 108
#: Diameter of the circle every launcher mask keeps, centred on that canvas.
# 68 rather than the full 72: masks vary slightly between launchers, and the mark looks
# better with a little air around it than pressed against the edge of the circle.
SAFE_CIRCLE_DP = 68
#: The in-app mark is drawn at up to 48dp — identity rows, headers.
MARK_DP = 48

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def fit_into(art: Image.Image, canvas_px: int, fraction: float) -> Image.Image:
    """Centre `art` on a transparent square canvas, longest side at `fraction` of it."""
    target = max(1, round(canvas_px * fraction))
    width, height = art.size
    scale = target / float(max(width, height))
    scaled_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    scaled = art.resize(scaled_size, Image.LANCZOS)

    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.paste(
        scaled,
        ((canvas_px - scaled_size[0]) // 2, (canvas_px - scaled_size[1]) // 2),
        scaled,
    )
    return canvas


def fit_inside_circle(art: Image.Image, canvas_px: int, circle_px: float) -> Image.Image:
    """Centre `art` on a transparent square canvas, its whole bounding box inside a circle.

    Scaled by the diagonal rather than the longest side. A bounding box only fits inside a circle
    when its *diagonal* is the diameter — scaling the longest side instead leaves the four corners
    outside, which for this mark meant the A's feet were masked off by every round launcher.
    """
    width, height = art.size
    diagonal = math.hypot(width, height)
    scale = circle_px / diagonal
    scaled_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    scaled = art.resize(scaled_size, Image.LANCZOS)

    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.paste(
        scaled,
        ((canvas_px - scaled_size[0]) // 2, (canvas_px - scaled_size[1]) // 2),
        scaled,
    )
    return canvas


def monochrome_of(layer: Image.Image) -> Image.Image:
    """A flat white silhouette taken from the alpha channel.

    Themed icons are tinted by the system from the user's wallpaper, so this layer must carry shape
    only — a violet-to-blue gradient survives that badly. Using the alpha preserves the cut-out
    counter of the A, which is what makes the mark recognisable at a glance.
    """
    mono = Image.new("RGBA", layer.size, (255, 255, 255, 0))
    mono.putalpha(layer.getchannel("A"))
    return mono


def write(image: Image.Image, *parts: str) -> None:
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    print("  %-52s %4dpx" % ("/".join(parts), image.size[0]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    default_source = os.path.join(
        REPO_ROOT, "..", "Anodex4", "src", "renderer", "assets", "title-logo.png"
    )
    parser.add_argument(
        "--source",
        default=default_source,
        help="Path to the desktop repo's title-logo.png.",
    )
    args = parser.parse_args()

    source = Image.open(args.source).convert("RGBA")
    art = source.crop(source.getbbox())
    print("source: %s (content %dx%d)" % (args.source, art.size[0], art.size[1]))

    print("\nlauncher icon:")
    for density, factor in DENSITIES.items():
        size = round(ADAPTIVE_DP * factor)
        foreground = fit_inside_circle(art, size, SAFE_CIRCLE_DP * factor)
        write(foreground, "mipmap-" + density, "ic_launcher_foreground.png")
        write(monochrome_of(foreground), "mipmap-" + density, "ic_launcher_monochrome.png")

    print("\nin-app mark:")
    for density, factor in DENSITIES.items():
        # No safe-zone padding: this one is placed by a layout, never masked.
        write(fit_into(art, round(MARK_DP * factor), 1.0), "drawable-" + density, "anodex_mark.png")


if __name__ == "__main__":
    main()
