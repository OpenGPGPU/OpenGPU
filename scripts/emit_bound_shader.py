#!/usr/bin/env python3
"""Write one checked-in clang shader array as a raw instruction image."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def header_array(name):
    text = (ROOT / "userspace/gallium/opengpu_shaders.h").read_text()
    start = text.find(f"static const uint32_t {name}[]")
    if start < 0:
        raise SystemExit(f"{name}: missing from opengpu_shaders.h")
    body = text[start:text.find("};", start)]
    words = [int(token, 16) for token in re.findall(r"0x[0-9a-fA-F]+", body)]
    if not words or words[-1] != 0x30500073:
        raise SystemExit(f"{name}: shader image does not end in opengpu.cease")
    return words


def main():
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} <array-name> <output.bin>")
    words = header_array(sys.argv[1])
    Path(sys.argv[2]).write_bytes(b"".join(word.to_bytes(4, "little") for word in words))
    print(f"{sys.argv[1]}: {len(words)} words -> {sys.argv[2]}")


if __name__ == "__main__":
    main()
