#!/usr/bin/env python3
"""Fail when Emit*.scala objects are missing from EmitRegistry.

Scans Scala `object Name` declarations in Emit*.scala files (excluding
EmitRegistry.scala) and requires each name to appear in EmitRegistry. This
keeps the discoverable registry from drifting as emitters are added/removed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ELAB = ROOT / "src/main/scala/opengpu/elaboration"
REGISTRY = ELAB / "EmitRegistry.scala"
OBJ_RE = re.compile(r"^\s*object\s+(Emit[A-Za-z0-9_]+)\b", re.M)


def emit_objects() -> list[str]:
    names = []
    for p in sorted(ELAB.rglob("Emit*.scala")):
        if p.name == "EmitRegistry.scala":
            continue
        text=p.read_text(encoding="utf-8")
        for m in OBJ_RE.finditer(text):
            name=m.group(1)
            if re.search(r"\bdef\s+main\b", text) or re.search(r"extends\s+App\b", text):
                names.append(name)
    return sorted(set(names))


def main() -> int:
    if not REGISTRY.is_file():
        print(f"missing registry: {REGISTRY}", file=sys.stderr)
        return 2
    reg = REGISTRY.read_text(encoding="utf-8")
    names = emit_objects()
    missing = [n for n in names if f'"{n}"' not in reg]
    extra = []
    for m in re.finditer(r'e\("([^"]+)"', reg):
        n = m.group(1)
        if n.startswith("Emit") and n not in names:
            if n != "EmitRegistry": extra.append(n)
    ok = True
    if missing:
        ok = False
        print("Emit objects missing from EmitRegistry:", file=sys.stderr)
        for n in missing:
            print(f"  {n}", file=sys.stderr)
    if extra:
        ok = False
        print("EmitRegistry entries with no matching Emit*.scala object:", file=sys.stderr)
        for n in extra:
            print(f"  {n}", file=sys.stderr)
    if ok:
        print(f"EmitRegistry covers {len(names)} emit objects")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
