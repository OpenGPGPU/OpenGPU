#!/usr/bin/env python3
"""Apply the exact Mesa 22.3.6 typed-uniform index fix, or verify it.

Accept only the upstream file or our complete postimage. Unknown local changes
are never overwritten. This does not configure, compile or install Mesa.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("src/compiler/nir/nir_lower_io.c")
BEFORE = "a18d5b8aa9fbffec3665d2ab7d66c887d327b6919d1b459401269d3e5c4466f6"
AFTER = "4ef90c40ace97a0e5e9f8f24e7a9bd9680d4a069d2d2d588b531c695f0a2d694"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesa", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="require the patched postimage without writing")
    args = parser.parse_args()
    mesa = args.mesa.resolve(strict=True)
    if (mesa / "VERSION").read_text().strip() != "22.3.6":
        raise SystemExit("checked uniform indices require the pinned Mesa 22.3.6 source")
    source = mesa / SOURCE
    actual = sha(source.read_bytes())
    if actual == AFTER:
        print("Mesa typed uniform index patch: exact postimage")
        return
    if actual != BEFORE:
        raise SystemExit("refusing unknown Mesa nir_lower_io.c changes: " + actual)
    if args.check:
        raise SystemExit("Mesa typed uniform index patch has not been staged")
    patch = ROOT / "scripts/patches/mesa-22.3.6-checked-uniform-index.patch"
    env = os.environ.copy()
    for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"):
        env.pop(name, None)
    # A source archive can sit inside GPU/.artifacts without its own .git.
    # Do not let git discover and apply the patch to the enclosing GPU repo.
    env["GIT_CEILING_DIRECTORIES"] = str(mesa.parent)
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=mesa, env=env, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=mesa, env=env, check=True)
    if sha(source.read_bytes()) != AFTER:
        raise SystemExit("Mesa typed uniform index patch postimage mismatch")
    print("Mesa typed uniform index patch: applied exact upstream preimage")


if __name__ == "__main__":
    main()
