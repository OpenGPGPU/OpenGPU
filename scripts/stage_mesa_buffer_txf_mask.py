#!/usr/bin/env python3
"""Stage the exact Mesa22.3.6 compute buffer-TXF mask patch privately.

The source and configured Mesa build stay unchanged. This does not compile,
install, open a GPU device or start a guest.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("src/gallium/auxiliary/nir/nir_to_tgsi.c")
BEFORE = "a58fb50693733bd1cd29b8a716fdf968cfd3f811bbe45a0dc8033e8ed022c003"
AFTER = "c120a040c6b0d356806b88741220015bbb6c558f9f1d1bea6a4b39c1f3923aae"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stage(mesa, out):
    mesa, out = Path(mesa).resolve(strict=True), Path(out).resolve()
    if (mesa / "VERSION").read_text().strip() != "22.3.6":
        raise ValueError("the exact Mesa22.3.6 source is required")
    if out.is_relative_to(mesa.parent):
        raise ValueError("private output must be outside the shared Mesa source/build area")
    actual = sha(mesa / SOURCE)
    if actual not in (BEFORE, AFTER):
        raise ValueError("refusing unknown Mesa nir_to_tgsi.c changes: " + actual)
    out.mkdir(parents=True, exist_ok=False)
    target = out / SOURCE
    target.parent.mkdir(parents=True)
    shutil.copyfile(mesa / SOURCE, target)
    shutil.copyfile(mesa / "VERSION", out / "VERSION")
    if actual == BEFORE:
        env = os.environ.copy()
        for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"):
            env.pop(key, None)
        env["GIT_CEILING_DIRECTORIES"] = str(out.parent)
        patch = ROOT / "scripts/patches/mesa-22.3.6-compute-buffer-txf-read-mask.patch"
        for args in (["--check"], []):
            subprocess.run(["git", "apply", "--no-index"] + args + [str(patch)],
                           cwd=out, env=env, check=True)
    if sha(target) != AFTER:
        raise ValueError("private Mesa TXF patch postimage differs")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesa", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="new private source directory")
    parser.add_argument("--check", action="store_true", help="check an existing private stage without writing")
    args = parser.parse_args()
    if args.check:
        if sha(args.out / SOURCE) != AFTER:
            raise SystemExit("private Mesa TXF stage differs")
    else:
        stage(args.mesa, args.out)
    print("Mesa compute buffer TXF mask: exact private postimage")


if __name__ == "__main__":
    main()
