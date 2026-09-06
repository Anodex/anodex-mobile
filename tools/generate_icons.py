"""Regenerate the launcher icon and the in-app mark from Anodex's real logo.

The art is never redrawn by hand. The source of truth is the desktop repo's
`src/renderer/assets/title-logo.png` — the bare "A" that `AnodexLogo`'s 'mark' variant shows on the
app's own chrome. This script only trims, scales and pads it to Android's geometry.

Adaptive icons are 108dp square with a 72dp safe zone: launcher masks crop anything outside it and
the system parallaxes the layers, so the mark is fitted to 60% of the canvas — inside the safe zone
with room to breathe.

Usage, from the repo root:

    python tools/generate_icons.py --source ../Anodex4/src/renderer/assets/title-logo.png

Requires Pillow (`pip install pillow`). Commit the regenerated PNGs.
"""

from __future__ import annotations

import argparse
import os

from PIL import Image

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO_ROOT, "app", "src", "main", "res")

#: Adaptive-icon layers are 108dp square.
ADAPTIVE_DP = 108
#: Fraction of that canvas the mark's longest side occupies. Inside the 72dp safe zone.
MARK_FRACTION = 0.60
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
        foreground = fit_into(art, size, MARK_FRACTION)
        write(foreground, "mipmap-" + density, "ic_launcher_foreground.png")
        write(monochrome_of(foreground), "mipmap-" + density, "ic_launcher_monochrome.png")

    print("\nin-app mark:")
    for density, factor in DENSITIES.items():
        # No safe-zone padding: this one is placed by a layout, never masked.
        write(fit_into(art, round(MARK_DP * factor), 1.0), "drawable-" + density, "anodex_mark.png")


if __name__ == "__main__":
    main()
