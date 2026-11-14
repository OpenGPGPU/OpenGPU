#!/usr/bin/env python3
"""Typecheck the blit executor against an explicit owner runtime; no RTL model."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-root", required=True, type=Path)
    parser.add_argument("--sample-owner", action="store_true", help="distinct source-selected protected112 TU; same checkout only")
    args = parser.parse_args()
    owner = args.owner_root.resolve(strict=True)
    if args.sample_owner and owner != ROOT:
        parser.error("protected112 typecheck requires its original source checkout")
    gpu = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                      cwd=ROOT, text=True).strip()).parent
    if not owner.is_relative_to(gpu / ".artifacts/worktrees"):
        parser.error("owner source must be an explicit GPU-internal worktree")
    runtime = owner / "scripts/native_owner_runtime.hpp"
    if not runtime.is_file():
        parser.error("selected checkout lacks the complete owner runtime")
    # The owner may add native-only opcode aliases to gpu_abi.h; the blit
    # source consumes the owner's actual header through its runtime. Shared
    # pixel and storage record encodings must still be byte-identical.
    for name in ("driver/opengpu_formats.h", "driver/opengpu_storage_descriptor.h",
                 "driver/opengpu_storage_descriptor_abi.h", "driver/opengpu_storage_command_abi.h"):
        if (ROOT / name).read_bytes() != (owner / name).read_bytes():
            raise RuntimeError("cross-root shared ABI differs: " + name)
    files = [ROOT / "scripts/native_framebuffer_blit_driver.cpp",
             owner / "scripts/native_host_simulation.hpp", runtime]
    own_dependencies = ["scripts/native_framebuffer_blit_lease.hpp", "scripts/native_framebuffer_blit_input.hpp",
                        "scripts/native_framebuffer_blit_simulation.hpp",
                        "driver/opengpu_storage_command_abi.h"]
    owner_dependencies = ["scripts/native_owner_runtime.hpp", "scripts/native_owner_capture.hpp",
        "driver/gpu_abi.h", "driver/opengpu_formats.h", "driver/opengpu_shader_resources.h",
        "scripts/flashsim_native/VGpuHostSystemAxi.h", "scripts/flashsim_native/verilated.h",
        "scripts/native_host_simulation.hpp", "scripts/native_execution_progress.hpp",
        "scripts/native_frame_axi.hpp", "scripts/native_frame_fixture.hpp"]
    if (owner / 'scripts/owner_factory.hpp').is_file():
        owner_dependencies.append('scripts/owner_factory.hpp')
    if args.sample_owner:
        files.insert(0, ROOT / 'scripts/native_framebuffer_blit_sample_driver.cpp')
        own_dependencies.append('scripts/native_framebuffer_blit_driver.cpp')
        owner_dependencies += ['scripts/native_sample_owner_runtime.hpp', 'scripts/native_sample_owner_capture.hpp']
    closure = [files[0], *(ROOT / name for name in own_dependencies), *(owner / name for name in owner_dependencies)]
    before = {path: sha(path) for path in closure}
    (ROOT / ".artifacts").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="blit-type-", dir=ROOT / ".artifacts") as directory:
        tmp = Path(directory)
        env = dict(os.environ, TMPDIR=directory, XDG_CACHE_HOME=str(tmp / "cache"), CCACHE_DIR=str(tmp / "ccache"))
        # A declaration-only port stand-in permits C++ typechecking while the
        # complete DUT generator is still active. It is never linked or run.
        ports = set(re.findall(r"\bio_[a-zA-Z0-9_]+", "\n".join(p.read_text() for p in files)))
        (tmp / "verilated.h").write_text('''#pragma once
#include <cstdint>
class VerilatedContext {public:void commandArgs(int,char**){} void timeInc(uint64_t){} bool gotFinish()const{return false;}};
''')
        (tmp / "VGpuHostSystemAxi.h").write_text('#pragma once\n#include "verilated.h"\n' +
            'class VGpuHostSystemAxi {public:\n' +
            '\n'.join('uint64_t ' + name + ('[8]{};' if name in ("io_m_axi_rdata", "io_m_axi_wdata") else '{};')
                      for name in sorted(ports)) +
            '\nbool macroModelsPresent=true;VGpuHostSystemAxi(VerilatedContext*){} void eval(){} void final(){} };\n')
        (tmp / "dut.h").write_text("// Include validation only; no generated DUT.\n")
        spec = importlib.util.spec_from_file_location("blit_owner_builder", owner / "scripts/build_native_flashsim.py")
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        if builder.ROOT != owner:
            raise RuntimeError("builder belongs to another owner root")
        builder.validate_driver_includes(closure, tmp / "dut.h", tmp)
        command = shlex.split(os.environ.get("CXX", "c++")) + ["-std=c++17", "-fsyntax-only",
            "-Wall", "-Wextra", "-Werror", "-Wno-missing-field-initializers", "-Wno-missing-braces",
            "-Wno-unused-parameter", "-I" + str(tmp), "-I" + str(ROOT / "scripts"),
            "-I" + str(owner / "scripts"), "-I" + str(ROOT / "driver"), str(files[0])]
        subprocess.run(command, check=True, env=env)
    if before != {path: sha(path) for path in closure}:
        raise RuntimeError("owner/driver source changed during typecheck")
    print("Framebuffer host C++ source typecheck PASS profile=" + ('protected112' if args.sample_owner else 'frozen96') +
          "; declaration-only ports, no DUT build or execution")


if __name__ == "__main__":
    main()
