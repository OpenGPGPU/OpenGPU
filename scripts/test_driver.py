#!/usr/bin/env python3
"""Compile and execute all kernel-free driver tests using the host C compiler."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    compiler = shlex.split(os.environ.get("CC", "cc"))
    with tempfile.TemporaryDirectory(prefix="opengpu-tests-") as directory:
        for name in ("shader_validator", "fragment_output_usage", "staging_validator", "resolve_validator", "depth_validator", "render_extent",
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

        binary = Path(directory) / "shader_color"
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

if __name__ == "__main__":
    main()
