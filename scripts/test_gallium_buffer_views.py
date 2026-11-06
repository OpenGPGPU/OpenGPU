#!/usr/bin/env python3
"""Explicit CPU-only actual Gallium buffer-view callback/lifetime checks.

Uses existing Mesa headers/parser and models OS/GEM services. No Mesa build,
shader submission, GPU, guest, API capability or new routine CI lane.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build", type=Path, required=True, help="fresh private output directory")
    ap.add_argument("--sanitize", action="store_true")
    args = ap.parse_args()
    if sys.platform != "linux":
        raise SystemExit("actual callback fixture requires the Linux sync_file UAPI")
    out = args.build.resolve()
    out.mkdir(parents=True, exist_ok=False)
    mesa = Path(os.environ.get("OPENGPU_MESA_DIR", ROOT / "depends/mesa")).resolve()
    drm = Path(os.environ.get("OPENGPU_DRM_INCLUDE", ROOT / "depends/aarch64-root/include")).resolve()
    if (mesa / "VERSION").read_text().strip() != "22.3.6":
        raise SystemExit("the actual pinned Mesa22.3.6 headers/parser are required")
    cc = shlex.split(os.environ.get("CC", "cc"))
    incs = ["-I" + str(p) for p in (ROOT / "userspace/tests", ROOT / "userspace/gallium", ROOT / "userspace",
        ROOT / "driver", drm, mesa / "src/gallium/include", mesa / "src/gallium/auxiliary", mesa / "src", mesa / "include")]
    common = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
        "-DHAVE_ENDIAN_H", "-D_POSIX_C_SOURCE=200809L", "-ffunction-sections", "-fdata-sections"]
    if args.sanitize:
        common += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    primitives = ["-D" + name + "=cs_fixture_" + name for name in ("ioctl", "mmap", "munmap", "dup", "close")]
    units = [(ROOT / "userspace/tests/cs_buffer_views.c", [])]
    units += [(ROOT / "userspace/gallium" / name, ["-Wno-sign-compare"] if name == "opengpu_context.c" else [])
              for name in ("opengpu_context.c", "opengpu_screen.c", "opengpu_cs_emit.c", "opengpu_fs_emit.c", "opengpu_vs_emit.c")]
    units += [(ROOT / "userspace" / name, primitives) for name in ("pipe_opengpu.c", "opengpu.c")]
    units += [(mesa / "src/gallium/auxiliary/tgsi" / name, ["-Wno-sign-compare"]) for name in ("tgsi_parse.c", "tgsi_build.c")]
    commands = []

    def run(argv, label, env=None):
        commands.append({"argv": list(map(str, argv)), "cwd": str(out)})
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        with (out / (label + ".log")).open("x") as log:
            subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT, env=env, check=True)

    objects = []
    for source, extra in units:
        obj = out / (source.stem + ".o")
        run(cc + common + incs + extra + ["-MMD", "-MF", str(obj.with_suffix(".d")), "-c", str(source), "-o", str(obj)], "compile-" + source.stem)
        objects.append(str(obj))
    binary = out / "cs_buffer_views"
    run(cc + (["-fsanitize=address,undefined"] if args.sanitize else []) + ["-Wl,--gc-sections"] + objects + ["-lm", "-o", str(binary)], "link")
    env = os.environ.copy()
    env.pop("OPENGPU_COMPUTE_ARTIFACTS", None)
    if args.sanitize:
        env["ASAN_OPTIONS"] = "detect_leaks=1:abort_on_error=1"
        env["UBSAN_OPTIONS"] = "halt_on_error=1:print_stacktrace=1"
    run([str(binary)], "actual-callbacks", env)
    result = {"status": "PASS", "GPU_execution": False, "shader_submissions": 0,
        "GL_API_buffer_capability": False, "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "scope": "actual callbacks/lib/native request constructors; modeled OS/GEM, no compiler lowering or data execution"}
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Gallium buffer view callbacks CPU PASS: " + str(out))


if __name__ == "__main__":
    main()
