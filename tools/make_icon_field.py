"""Reconstruct the launcher icon's background field from the desktop's app icon.

`src/renderer/assets/app-icon.png` in the desktop repo is a *finished square icon*: the faceted
navy field with the A already sitting on it, filling about 63% of the width. That is right for an
icon that is never masked, and wrong as an Android adaptive-icon layer — Android crops to a circle
roughly 72dp across, and at full bleed that A needs 97dp. Using the artwork as-is would clip the
feet off exactly the way the old icon did.

Adaptive icons want the two things separately, so this takes them apart:

  * the **field**, produced here by painting the A out of the artwork and letting the facets flow
    back across the gap;
  * the **mark**, which already exists on its own as `title-logo.png` and is placed by
    `generate_icons.py` inside the safe circle.

The A is found by colour rather than by a hand-drawn mask: it is the only strongly saturated thing
in the picture, the field being near-neutral navy throughout. That keeps this working if the mark
is ever redrawn.

Usage, from the repo root:

    python tools/make_icon_field.py --source ../Anodex4/src/renderer/assets/app-icon.png

Writes `app/src/main/res/mipmap-*/ic_launcher_field.png`. Commit the result.
"""

from __future__ import annotations

import argparse
import os

from PIL import Image, ImageFilter

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO_ROOT, "app", "src", "main", "res")

ADAPTIVE_DP = 108
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

#: How saturated and how bright a pixel must be to count as part of the mark.
SATURATION = 55
BRIGHTNESS = 90

#: Wide enough to carry the field's large facets across the hole the mark leaves.
BLUR_RADIUS = 48

#: How far past the canvas the field is grown, to push the source's own border out of view.
OVERSCAN = 1.12


def mark_mask(art: Image.Image) -> Image.Image:
    """White where the mark is, black where the field is."""
    pixels = art.load()
    width, height = art.size
    mask = Image.new("L", art.size, 0)
    out = mask.load()

    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a < 200:
                continue
            if max(r, g, b) > BRIGHTNESS and (max(r, g, b) - min(r, g, b)) > SATURATION:
                out[x, y] = 255

    # Grown slightly, then softened: the mark has an antialiased edge, and a mask cut exactly at
    # its bounds leaves a violet fringe behind that no amount of blurring afterwards removes.
    return mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(6))


def field_of(art: Image.Image) -> Image.Image:
    """The artwork with the mark painted out and the facets closed over the gap."""
    mask = mark_mask(art)

    # The average of everything that is *not* the mark, computed over the field alone.
    #
    # Averaging the whole picture instead is the obvious shortcut and it fails quietly: the mark is
    # a third of the canvas and strongly violet, so the "field colour" comes out purple and the
    # blur paints a ghost of the A back into the middle of the field it was supposed to erase.
    rgb = art.convert("RGB")
    pixels = rgb.load()
    mask_px = mask.load()
    total_r = total_g = total_b = count = 0
    for y in range(0, art.size[1], 4):
        for x in range(0, art.size[0], 4):
            if mask_px[x, y] > 32:
                continue
            r, g, b = pixels[x, y]
            total_r += r
            total_g += g
            total_b += b
            count += 1
    average = (total_r // count, total_g // count, total_b // count)

    stats = rgb.copy()
    stats.paste(Image.new("RGB", art.size, average), (0, 0), mask)

    filled = stats.filter(ImageFilter.GaussianBlur(BLUR_RADIUS))
    field = rgb.copy()
    field.paste(filled, (0, 0), mask)

    # Corners are transparent in the source, because the artwork carries its own rounding. A
    # background layer must be opaque edge to edge — Android does the rounding itself, and a
    # transparent corner shows through as a notch.
    opaque = Image.new("RGB", art.size, average)
    opaque.paste(field, (0, 0), art.getchannel("A"))

    # Overscanned, so the artwork's own rounded border falls outside what any mask keeps.
    # Left in place it draws a faint rounded rectangle *inside* the launcher's own rounding —
    # two different roundings a few pixels apart, which reads as a rendering fault rather than
    # a design. A background layer is meant to bleed; this is it bleeding.
    grown = round(art.size[0] * OVERSCAN)
    inset = (grown - art.size[0]) // 2
    return opaque.resize((grown, grown), Image.LANCZOS).crop(
        (inset, inset, inset + art.size[0], inset + art.size[1])
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        default=os.path.join(REPO_ROOT, "..", "Anodex4", "src", "renderer", "assets", "app-icon.png"),
    )
    args = parser.parse_args()

    art = Image.open(args.source).convert("RGBA")
    print("source: %s (%dx%d)" % (args.source, *art.size))

    field = field_of(art)
    for density, factor in DENSITIES.items():
        size = round(ADAPTIVE_DP * factor)
        path = os.path.join(RES, "mipmap-" + density, "ic_launcher_field.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        field.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)
        print("  mipmap-%-8s ic_launcher_field.png  %4dpx" % (density, size))


if __name__ == "__main__":
    main()
