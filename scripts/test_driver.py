#!/usr/bin/env python3
"""Compile and execute all kernel-free driver tests using the host C compiler."""
import os
import sys
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    compiler = shlex.split(os.environ.get("CC", "cc"))
    with tempfile.TemporaryDirectory(prefix="opengpu-tests-") as directory:
        for name in ("shader_validator", "compute_atomics", "compute_discovery", "fragment_output_usage", "staging_validator", "resolve_validator", "depth_validator", "render_extent",
                     "tlb_flush", "kernarg_va", "asid", "mmu", "shader_resources", "shader_builder", "tile_job_abi"):
            include = ROOT / "driver" / ("tests/mmu_stubs" if name == "mmu" else ".")
            binary = Path(directory) / name
            subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                "-I" + str(include), str(ROOT / "driver/tests" / f"opengpu_{name}_test.c"),
                "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)
            print(f"{name}: PASS", flush=True)
        binary = Path(directory) / "shader_abi"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/shader_abi.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        print("runtime shader ABI: PASS", flush=True)

        binary = Path(directory) / "fs_alu"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/fs_alu.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

        binary = Path(directory) / "vs_alu"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/vs_alu.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"),
            str(ROOT / "userspace/tests/shader_color.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        binary = Path(directory) / "shader_modulate_white"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"),
            str(ROOT / "userspace/tests/shader_modulate_white.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

        binary = Path(directory) / "sampler_format"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/sampler_format.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        binary = Path(directory) / "texture_layout"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/gallium"),
            str(ROOT / "userspace/tests/texture_layout.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        print("texture row layout: PASS", flush=True)
        binary = Path(directory) / "pixel_format"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/pixel_format.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

        binary = Path(directory) / "offload"
        subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "userspace/libopengpu"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/libopengpu/tests/opengpu_offload_test.c"),
            str(ROOT / "userspace/libopengpu/opengpu_offload.c"),
            "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        print("offload container reader: PASS", flush=True)

        mesa = ROOT / "depends/mesa"
        parse = mesa / "src/gallium/auxiliary/tgsi/tgsi_parse.c"
        drm = ROOT / "depends/aarch64-root/include"
        if parse.is_file() and (drm / "drm/drm.h").is_file():
            incs = [
                "-I" + str(ROOT / "userspace/gallium"),
                "-I" + str(ROOT / "userspace"),
                "-I" + str(ROOT / "driver"),
                "-I" + str(drm),
                "-I" + str(mesa / "src/gallium/include"),
                "-I" + str(mesa / "src/gallium/auxiliary"),
                "-I" + str(mesa / "src"),
                "-I" + str(mesa / "include"),
            ]
            sections = ["-ffunction-sections", "-fdata-sections"]
            objects = []
            units = [
                (ROOT / "userspace/tests/fs_alu_lower.c", ["-Werror"]),
                (ROOT / "userspace/gallium/opengpu_context.c", ["-Wno-sign-compare"]),
                (ROOT / "userspace/gallium/opengpu_fs_emit.c", ["-Werror"]),
                (ROOT / "userspace/gallium/opengpu_vs_emit.c", ["-Werror"]),
                (parse, ["-Wno-sign-compare"]),
                (mesa / "src/gallium/auxiliary/tgsi/tgsi_build.c", ["-Wno-sign-compare"]),
            ]
            for source, extra in units:
                obj = Path(directory) / (source.stem + ".o")
                subprocess.run(compiler + ["-std=c11", "-O2", "-Wall", "-Wextra",
                    "-Wno-unused-parameter"] + extra + sections + incs +
                    ["-c", str(source), "-o", str(obj)], check=True)
                objects.append(str(obj))
            binary = Path(directory) / "fs_alu_lower"
            gc = ["-Wl,-dead_strip"] if sys.platform == "darwin" else ["-Wl,--gc-sections"]
            subprocess.run(compiler + gc + objects + ["-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

if __name__ == "__main__":
    main()
