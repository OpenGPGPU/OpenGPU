#!/usr/bin/env python3
"""Small real-Mesa compute callback/lib ioctl CPU fixture; never starts a GPU."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    if sys.platform != "linux":
        print("Gallium compute lib ioctl fixture: requires Linux sync_file UAPI")
        return
    mesa = Path(os.environ.get("OPENGPU_MESA_DIR", ROOT / "depends/mesa"))
    drm = Path(os.environ.get("OPENGPU_DRM_INCLUDE", ROOT / "depends/aarch64-root/include"))
    if not (mesa / "src/gallium/auxiliary/tgsi/tgsi_parse.c").is_file():
        raise SystemExit("actual Mesa TGSI parser is required")
    compiler = shlex.split(os.environ.get("CC", "cc"))
    incs = ["-I" + str(p) for p in [ROOT / "userspace/gallium", ROOT / "userspace", ROOT / "driver", drm,
            mesa / "src/gallium/include", mesa / "src/gallium/auxiliary", mesa / "src", mesa / "include"]]
    common = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
              "-DHAVE_ENDIAN_H", "-D_POSIX_C_SOURCE=200809L", "-ffunction-sections", "-fdata-sections"]
    primitives = ["-Dioctl=cs_fixture_ioctl", "-Dmmap=cs_fixture_mmap", "-Dmunmap=cs_fixture_munmap",
                  "-Ddup=cs_fixture_dup", "-Dclose=cs_fixture_close"]
    units = [
        (ROOT / "userspace/tests/cs_callback_flow.c", []),
        (ROOT / "userspace/gallium/opengpu_context.c", ["-Wno-sign-compare"]),
        (ROOT / "userspace/gallium/opengpu_screen.c", []),
        (ROOT / "userspace/gallium/opengpu_cs_emit.c", []),
        (ROOT / "userspace/gallium/opengpu_fs_emit.c", []),
        (ROOT / "userspace/gallium/opengpu_vs_emit.c", []),
        (ROOT / "userspace/pipe_opengpu.c", primitives),
        (ROOT / "userspace/opengpu.c", primitives),
        (mesa / "src/gallium/auxiliary/tgsi/tgsi_parse.c", ["-Wno-sign-compare"]),
        (mesa / "src/gallium/auxiliary/tgsi/tgsi_build.c", ["-Wno-sign-compare"]),
    ]
    with tempfile.TemporaryDirectory(prefix="opengpu-cs-") as tmp:
        build = Path(os.environ.get("OPENGPU_COMPUTE_TEST_BUILD", tmp))
        build.mkdir(parents=True, exist_ok=True)
        objects = []
        for source, extra in units:
            obj = build / (source.stem + ".o")
            subprocess.run(compiler + common + incs + extra + ["-MMD", "-MF", str(obj.with_suffix(".d")),
                "-c", str(source), "-o", str(obj)], check=True)
            objects.append(str(obj))
        binary = build / "cs_callback_flow"
        subprocess.run(compiler + ["-Wl,--gc-sections"] + objects + ["-lm", "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
