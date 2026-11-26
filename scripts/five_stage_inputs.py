#!/usr/bin/env python3
"""Shared original five-stage case definitions and CPU input encodings."""
from dataclasses import dataclass
from pathlib import Path
import hashlib
import json
import struct

ROOT = Path(__file__).resolve().parents[1]
CASES = (("full5", 4), ("pointcoord", 4))
NAMES = ("vs", "tcs", "tes", "gs", "fs")
STAGES = (0, 4, 5, 3, 1)
BINDINGS = (
    (5, 8, 0, 10, 1, 7, 6, 9, 2, 11, 4, 3),
    (20, 16, 12, 22, 14, 21, 23, 18, 15, 19, 13, 17),
    (33, 28, 34, 31, 25, 29, 27, 30, 26, 35, 32, 24),
    (40, 45, 41, 36, 37, 38, 39, 44, 43, 42, 47, 46),
    (68, 62, 64, 67, 66, 71, 69, 70, 65, 60, 63, 61),
)
CODE_WORDS = {
    "full5": (308, 478, 362, 289, 401),
    "pointcoord": (308, 478, 362, 289, 427),
}
STAGE_SUMS = (0xEAAF55C1, 0xF3753941, 0xD2F163C1, 0x8B861A41, 0xC6341741)
PRIVATE_ROOTS = (4096, 409600, 409664, 815104, 4160)
PRIVATE_SEGMENTS = ((0x1000, 403200), (0x64000, 403200), (0xC7000, 202496))
PRIVATE_SEGMENT_BYTES = 1008896
PRIVATE_FILE_BYTES = PRIVATE_SEGMENT_BYTES + 44 + 8 * len(PRIVATE_SEGMENTS)
FRAME_PITCH = 192
FRAME_PIXEL_BYTES = 768
FRAME_GUARDED_BYTES = 896
FRAME_STORED_BYTES = 1024
FRAME_CLEAR = tuple(tuple(0x10203040 + 16 * attachment + channel for channel in range(4))
                   for attachment in range(4))
POINT_COLORS = {
    (0, 0): 0x87F90241,
    (1, 0): 0x86D90241,
    (0, 1): 0x86590241,
    (1, 1): 0x87790241,
}
POINT_COORDS = {
    (0, 0): (0x3E000000, 0x3F600000),
    (1, 0): (0x3F200000, 0x3F600000),
    (0, 1): (0x3E000000, 0x3EC00000),
    (1, 1): (0x3F200000, 0x3EC00000),
}


@dataclass(frozen=True)
class CaseData:
    name: str
    directory: Path
    metadata: dict
    geometry: dict
    views: tuple
    sums: tuple
    files: dict


def require(condition, message):
    if not condition:
        raise ValueError(message)


def align(value, alignment):
    require(type(alignment) is int and alignment > 0 and (alignment & (alignment - 1)) == 0,
            "alignment must be a positive power of two")
    return (value + alignment - 1) & -alignment


def read_regular(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), f"regular nonsymlink input required: {path}")
    return path.read_bytes()


def json_object(data, label="JSON"):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"duplicate {label} key: {key}")
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f"nonfinite {label} value: {value}")

    value = json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)
    require(type(value) is dict, f"{label} must be an object")
    return value


def words(data):
    require(len(data) % 4 == 0, "word alignment")
    return struct.unpack("<" + "I" * (len(data) // 4), data)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def artifact_names(name):
    require(name in dict(CASES), "unsupported five-stage case")
    names = [f"{name}.five.json", f"{name}.geometry.json", f"{name}.ognsf",
             f"{name}.uniform-private.bin"]
    names.extend(f"{name}.{stage}.words" for stage in NAMES)
    names.extend(f"{name}.{stage}-uniform-{index}.bin"
                 for stage in NAMES for index in range(13))
    return tuple(names)


def case_directory(build, name):
    require(name in dict(CASES), "unsupported five-stage case")
    build = Path(build)
    if (build / f"{name}.five.json").is_file():
        return build
    nested = build / name
    if (nested / f"{name}.five.json").is_file():
        return nested
    raise ValueError(f"missing five-stage case directory: {name}")


def source_input_bytes(metadata, views):
    require(len(views) == 65, "exactly 65 original views")
    result = bytearray(struct.pack("<8sIIII", b"OG5SRC01", 1, 5, 13, 0))
    for stage in range(5):
        for index in range(13):
            handle, offset, data = views[stage * 13 + index]
            expected = 4096 if index == 0 else 16384
            require(type(handle) is int and 0 < handle <= 0xFFFFFFFF, "source handle")
            require(type(offset) is int and 0 <= offset <= 0xFFFFFFFFFFFFFFFF, "source offset")
            require(len(data) == expected, "source view extent")
            result.extend(struct.pack("<IIQ", handle, len(data), offset))
            result.extend(data)
    return bytes(result)


def write_source_input(path, metadata, views):
    require(metadata["contextId"] == 1, "original context ID")
    Path(path).write_bytes(source_input_bytes(metadata, views))


def main():
    import argparse
    from five_stage_source_preflight import verify_case

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--case", choices=tuple(dict(CASES)), required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    case = verify_case(case_directory(args.build, args.case), args.case)
    output = args.output or (case.directory / f"{case.name}.uniform-source.bin")
    write_source_input(output, case.metadata, case.views)
    print(f"{case.name}: OG5SRC01 65-view input written to {output}")


if __name__ == "__main__":
    main()
