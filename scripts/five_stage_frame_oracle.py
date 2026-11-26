#!/usr/bin/env python3
"""Independent original-ESSL 4x4 point raster and MRT frame oracle."""
import argparse
from fractions import Fraction
from pathlib import Path
import struct

from five_stage_inputs import (CASES, FRAME_CLEAR, FRAME_GUARDED_BYTES,
                               FRAME_PITCH, FRAME_PIXEL_BYTES, FRAME_STORED_BYTES,
                               POINT_COLORS, POINT_COORDS, STAGE_SUMS, case_directory, require)
from five_stage_source_preflight import verify_case


def bits(value):
    return struct.unpack("<I", struct.pack("<f", float(value)))[0]


def source_sums(build, name):
    return verify_case(case_directory(build, name), name).sums


def points(name):
    require(name in dict(CASES), "only full5 and pointcoord are accepted")
    return ((6, Fraction(-3, 8), Fraction(-3, 8), 0),)


def image(name, sums, attachment):
    require(name in dict(CASES) and dict(CASES)[name] == 4, "only the two 4x4 cases are accepted")
    require(tuple(sums) == STAGE_SUMS, "independent stage sums")
    require(type(attachment) is int and 0 <= attachment < 4, "MRT attachment")
    pitch = FRAME_PITCH
    expected = bytearray([0xA5] * FRAME_GUARDED_BYTES)
    clear = FRAME_CLEAR[attachment]
    for y in range(4):
        for x in range(4):
            struct.pack_into("<4I", expected, 64 + y * pitch + x * 16, *clear)
    occupied = {}
    coordinates = {}
    if name == "pointcoord":
        first, xclip, yclip, primitive_id = points(name)[0]
        require(first % 2 == 0 and primitive_id == 0, "original point tuple")
        center_x = (xclip + 1) * 2
        center_y = (yclip + 1) * 2
        base = 0
        for value in sums:
            base ^= value
        base ^= primitive_id
        for y in range(4):
            for x in range(4):
                sample_x = Fraction(2 * x + 1, 2)
                sample_y = Fraction(2 * y + 1, 2)
                if center_x - 1 <= sample_x < center_x + 1 and center_y - 1 <= sample_y < center_y + 1:
                    u = (sample_x - center_x) / 2 + Fraction(1, 2)
                    v = (center_y - sample_y) / 2 + Fraction(1, 2)
                    u_bits, v_bits = bits(u), bits(v)
                    color = base ^ u_bits ^ v_bits
                    require((x, y) not in occupied, "independent points overlap")
                    require((u_bits, v_bits) == POINT_COORDS[x, y], "PointCoord oracle")
                    require(color == POINT_COLORS[x, y], "original point color oracle")
                    occupied[x, y] = color
                    coordinates[x, y] = (u_bits, v_bits)
                    struct.pack_into("<4I", expected, 64 + y * pitch + x * 16, *([color] * 4))
        require(set(occupied) == {(0, 0), (1, 0), (0, 1), (1, 1)}, "one point covers exactly four pixels")
    stored = bytes(expected) + bytes([0xA5]) * (FRAME_STORED_BYTES - FRAME_GUARDED_BYTES)
    require(len(stored) == FRAME_STORED_BYTES and FRAME_PIXEL_BYTES == 4 * pitch,
            "frame byte extents")
    return stored, occupied, coordinates, pitch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--case", choices=tuple(dict(CASES)))
    args = parser.parse_args()
    for name, width in CASES:
        if args.case and args.case != name:
            continue
        sums = source_sums(args.build, name)
        for attachment in range(4):
            stored, occupied, coordinates, pitch = image(name, sums, attachment)
            require(stored[:64] == bytes([0xA5]) * 64 and
                    stored[FRAME_GUARDED_BYTES:] == bytes([0xA5]) * 128,
                    "outer guards")
            for y in range(4):
                row = 64 + y * pitch
                require(stored[row + 64:row + pitch] == bytes([0xA5]) * 128, "row padding")
            if name == "pointcoord":
                require(coordinates == POINT_COORDS and
                        occupied == POINT_COLORS, "point oracle")
        print(f"{name}: independent {width}x{width}/MRT4 frame oracle PASS; "
              f"pixels={len(occupied)} pitch={pitch} guarded={FRAME_GUARDED_BYTES} "
              f"stored={FRAME_STORED_BYTES} GPU=false")


if __name__ == "__main__":
    main()
