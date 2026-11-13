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
    with tempfile.TemporaryDirectory(prefix="opengpu-buffer-view-") as directory:
        binary = Path(directory) / "buffer_view"
        subprocess.run(shlex.split(os.environ.get("CC", "cc")) + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-I" + str(ROOT / "driver/tests/compute_submit_stubs"),
            str(ROOT / "driver/tests/opengpu_buffer_view_test.c"), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print("buffer view UAPI layout, complete ranges and request builders: PASS", flush=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_storage_binding.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_native_completion.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_tessellation_schedule.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/generate_compute_texture_format_info.py"), "--check"], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_texture_submit.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_texture_job_records.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_texture_hw_prepare.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_texture_prepare.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_texture_ioctl_flow.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_uniform_flow.py")], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_compute_local_limits.py")], check=True)
    compiler = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(compiler + ["-std=c11", "-Wall", "-Wextra", "-Werror", "-D__KERNEL__",
        "-nostdinc", "-I" + str(ROOT / "driver/tests/tessellation_record_kernel_stubs"),
        "-fsyntax-only", str(ROOT / "driver/tests/opengpu_tessellation_record_kernel_abi_test.c")],
        check=True)
    print("tessellation kernel ABI headers: PASS", flush=True)
    with tempfile.TemporaryDirectory(prefix="opengpu-tests-") as directory:
        for name in ("shared_atomic_validator", "shader_validator", "compute_atomics", "compute_storage_validator", "compute_simt_validator", "compute_image_validator", "compute_image_descriptor", "compute_discovery", "native_preflight_discovery", "native_advanced_blend_discovery", "native_advanced_blend_gfs", "fragment_output_usage", "staging_validator", "backing_identity", "cache_range", "resolve_validator", "depth_validator", "render_extent",
                     "tlb_flush", "kernarg_va", "asid", "mmu", "scheduler", "tessellation_job_validate", "tessellation_state", "shader_resources", "shader_builder", "tile_job_abi", "shader_image", "shader_image_command", "fragment_image_publication_abi", "storage_command", "storage_descriptor", "compute_uniform_command", "compute_uniform_records", "compute_texture_command", "compute_texture_records", "compute_texture_mips", "compute_texture_sampler_validation", "compute_texture_snapshot", "compute_texture_tables", "compute_texture_bound_view", "atomic_counter_command", "compute_resource_capabilities", "raster_capabilities", "context_robustness"):
            include = ROOT / "driver" / ({"mmu": "tests/mmu_stubs", "scheduler": "tests/scheduler_stubs"}.get(name, "."))
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

        binary = Path(directory) / "fs_fma_emit"
        subprocess.run(compiler + ["-std=c11", "-O2", "-ffp-contract=off",
            "-Wall", "-Wextra", "-Werror", "-I" + str(ROOT / "userspace/gallium"),
            "-I" + str(ROOT / "driver"), str(ROOT / "userspace/tests/fs_fma_emit.c"),
            "-lm", "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

        binary = Path(directory) / "fs_dot_emit"
        subprocess.run(compiler + ["-std=c11", "-O2", "-ffp-contract=off",
            "-Wall", "-Wextra", "-Werror", "-DOPENGPU_DOT_RAW_ONLY",
            "-I" + str(ROOT / "userspace/gallium"), "-I" + str(ROOT / "driver"),
            str(ROOT / "userspace/tests/fs_dot_lower.c"), "-lm", "-o", str(binary)],
            check=True)
        subprocess.run([str(binary)], check=True)

        mesa = Path(os.environ.get("OPENGPU_MESA_DIR", ROOT / "depends/mesa"))
        parse = mesa / "src/gallium/auxiliary/tgsi/tgsi_parse.c"
        drm = Path(os.environ.get("OPENGPU_DRM_INCLUDE", ROOT / "depends/aarch64-root/include"))
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
            # The BSD half of drm.h lacks Linux's aligned integer alias.
            # Match the existing CPU fixture flags in test_fragment_mad_cu.
            platform = ["-D__aligned_u64=__u64"] if sys.platform == "darwin" else [
                "-DHAVE_ENDIAN_H", "-D_POSIX_C_SOURCE=200809L"]
            objects = []
            units = [
                (ROOT / "userspace/tests/fs_alu_lower.c", ["-Werror"]),
                (ROOT / "userspace/gallium/opengpu_context.c", ["-Wno-sign-compare"]),
                (ROOT / "userspace/gallium/opengpu_fs_emit.c", ["-Werror"]),
                (ROOT / "userspace/gallium/opengpu_vs_emit.c", ["-Werror"]),
                (ROOT / "userspace/gallium/opengpu_cs_emit.c", ["-Werror"]),
                (parse, ["-Wno-sign-compare"]),
                (mesa / "src/gallium/auxiliary/tgsi/tgsi_build.c", ["-Wno-sign-compare"]),
            ]
            for source, extra in units:
                obj = Path(directory) / (source.stem + ".o")
                subprocess.run(compiler + platform + ["-std=c11", "-O2", "-Wall", "-Wextra",
                    "-Wno-unused-parameter"] + extra + sections + incs +
                    ["-c", str(source), "-o", str(obj)], check=True)
                objects.append(str(obj))
            binary = Path(directory) / "fs_alu_lower"
            gc = ["-Wl,-dead_strip"] if sys.platform == "darwin" else ["-Wl,--gc-sections"]
            subprocess.run(compiler + gc + objects + ["-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

            dot_object = Path(directory) / "fs_dot_lower.o"
            subprocess.run(compiler + platform + ["-std=c11", "-O2", "-ffp-contract=off",
                "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"] +
                sections + incs + ["-c", str(ROOT / "userspace/tests/fs_dot_lower.c"),
                                  "-o", str(dot_object)], check=True)
            binary = Path(directory) / "fs_dot_lower"
            subprocess.run(compiler + gc + [str(dot_object)] + objects[1:] +
                           ["-lm", "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

            prologue_object = Path(directory) / "fs_prologue_lower.o"
            subprocess.run(compiler + platform + ["-std=c11", "-O2", "-ffp-contract=off",
                "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"] +
                sections + incs + ["-c", str(ROOT / "userspace/tests/fs_prologue_lower.c"),
                                  "-o", str(prologue_object)], check=True)
            binary = Path(directory) / "fs_prologue_lower"
            subprocess.run(compiler + gc + [str(prologue_object)] + objects[1:] +
                           ["-lm", "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

            subprocess.run([sys.executable, str(ROOT / "scripts/test_gallium_compute.py")], check=True)

if __name__ == "__main__":
    main()
