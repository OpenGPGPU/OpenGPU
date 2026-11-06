#!/usr/bin/env python3
"""Stage the six real H8 shaders with an explicit local qualification size."""
import argparse
from pathlib import Path
import re
import shutil

PROGRAMS = ("producer", "textures-0", "textures-1", "textures-2", "images", "fault")


def stage_sources(source, out, items=16):
    if items not in (16, 128):
        raise ValueError("qualification items must be 16 or 128")
    source = Path(source).resolve(strict=True)
    out = Path(out).resolve()
    if out.is_relative_to(source):
        raise ValueError("staged shaders must be outside the input source directory")
    shaders = {}
    for name in PROGRAMS:
        text = (source / (name + ".comp")).read_text()
        if not text.startswith("#version 310 es\n"):
            raise ValueError("expected real ESSL310 source: " + name)
        default = "#ifndef GB_QUALIFICATION_ITEMS\n#define GB_QUALIFICATION_ITEMS 16\n#endif\n"
        normalized = text.replace("\\\n", "")
        normalized = re.sub(r"/\*.*?\*/|//[^\n]*", "", normalized, flags=re.S)
        definitions = re.findall(r"^\s*#\s*(define|undef)\s+GB_QUALIFICATION_ITEMS\b", normalized, re.M)
        if text.count(default) != 1 or definitions != ["define"]:
            raise ValueError("refusing an injected/overridden qualification profile: " + name)
        first, rest = text.split("\n", 1)
        shaders[name] = first + "\n#define GB_QUALIFICATION_ITEMS " + str(items) + "\n" + rest
    shutil.copytree(source, out)
    for name, text in shaders.items():
        (out / (name + ".comp")).write_text(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="new independent source directory")
    parser.add_argument("--items", type=int, choices=(16, 128), default=16)
    args = parser.parse_args()
    stage_sources(args.source, args.out, args.items)


if __name__ == "__main__":
    main()
