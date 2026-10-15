#!/usr/bin/env python3
"""Order emitted model symbols by representative LLVM profile call counts."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import platform
import re
import subprocess


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(data)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    darwin = platform.system() == "Darwin"
    nm = ["nm", "-j", "-U" if darwin else "--defined-only", str(args.binary)]
    symbols = Counter(subprocess.check_output(nm, text=True).splitlines())
    profile = subprocess.check_output(
        ["xcrun", "llvm-profdata", "show", "--all-functions", "--counts", str(args.profile)]
        if darwin else ["llvm-profdata", "show", "--all-functions", "--counts", str(args.profile)],
        text=True)
    counts = {}
    for block in re.split(r"\n(?=  \S[^\n]*:\n)", profile):
        name = block.split(":\n", 1)[0].strip()
        count = re.search(r"Function count: (\d+)", block)
        if not count or not int(count[1]):
            continue
        # LLVM prefixes internal linkage names with their source filename.
        symbol = ("_" if darwin else "") + name.rsplit(":", 1)[-1]
        if symbols[symbol] == 1:
            counts[symbol] = counts.get(symbol, 0) + int(count[1])
    if not counts:
        raise SystemExit("No uniquely defined, executed profile symbols match this binary")
    order = sorted(counts, key=lambda symbol: (-counts[symbol], symbol))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(order) + "\n")
    report = {"profile": str(args.profile.resolve()), "profile_sha256": digest(args.profile),
              "binary": str(args.binary.resolve()), "binary_sha256": digest(args.binary),
              "symbols": len(order), "order_sha256": digest(args.output),
              "method": "descending recorded function count; unique defined symbols only",
              "nm": nm}
    args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
