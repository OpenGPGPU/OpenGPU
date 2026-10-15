#!/usr/bin/env python3
"""Verify the active GtkGLArea rectangle in the actual QEMU RGB framebuffer."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re


class ScanoutError(ValueError):
    pass


def active_size(log):
    matches = re.findall(r"OPENGPU GTK FRAME PASS: active[^\r\n]*size=(\d+)x(\d+)", log)
    if not matches:
        raise ScanoutError("missing active client readback and viewport dimensions")
    width, height = map(int, matches[-1])
    if width < 16 or height < 16:
        raise ScanoutError("invalid client viewport dimensions")
    return width, height


def rectangle_size(width, height):
    # The fixture's two triangles fill [-.82,.82] x [-.60,.60]. Ignore one
    # edge pixel on each side to allow raster and compositor quantisation.
    return max(1, math.floor(width * .82) - 2), max(1, math.floor(height * .60) - 2)


def verify(data, expected_size, client_size):
    header = re.match(rb"P6\n(\d+) (\d+)\n255\n", data)
    if header is None:
        raise ScanoutError("invalid RGB framebuffer header")
    width, height = map(int, header.groups())
    if (width, height) != expected_size:
        raise ScanoutError("framebuffer dimensions differ from the selected KMS mode")
    pixels = data[header.end():]
    if len(pixels) != width * height * 3:
        raise ScanoutError("incomplete framebuffer capture")
    wanted_w, wanted_h = rectangle_size(*client_size)
    if wanted_w > width or wanted_h > height:
        raise ScanoutError("client rectangle does not fit the selected output")
    heights = [0] * width
    active_pixels = 0
    found = None
    # A large axis-aligned rectangle must be present, rather than an orange
    # pixel, a partially rendered triangle/tile, or an older colourful frame.
    for y in range(height):
        run = 0
        for x in range(width):
            offset = 3 * (y * width + x)
            active = (abs(pixels[offset] - 242) <= 3
                      and abs(pixels[offset + 1] - 89) <= 3
                      and abs(pixels[offset + 2] - 46) <= 3)
            active_pixels += active
            heights[x] = heights[x] + 1 if active else 0
            run = run + 1 if heights[x] >= wanted_h else 0
            if run >= wanted_w and found is None:
                found = [x - wanted_w + 1, y - wanted_h + 1, wanted_w, wanted_h]
    if found is None:
        raise ScanoutError(f"active {wanted_w}x{wanted_h} GTK rectangle not yet presented "
                           f"({active_pixels} active-colour pixels)")
    return {"passed": True, "framebuffer_size": [width, height],
            "client_size": list(client_size), "active_rectangle": found,
            "active_pixels": active_pixels, "channel_tolerance": 3}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frame", type=Path)
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("client_log", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--save-frame", type=Path,
                        help="freeze the exact bytes that passed verification")
    args = parser.parse_args()
    try:
        data = args.frame.read_bytes()
        result = verify(data, (args.width, args.height),
                        active_size(args.client_log.read_text(errors="replace")))
    except (OSError, ScanoutError) as error:
        parser.exit(1, f"GTK scanout: {error}\n")
    result["capture_sha256"] = hashlib.sha256(data).hexdigest()
    result["source_frame"] = str(args.frame)
    if args.save_frame:
        args.save_frame.write_bytes(data)
        result["saved_frame"] = str(args.save_frame)
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()
