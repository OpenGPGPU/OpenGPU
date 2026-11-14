#!/usr/bin/env python3
"""Small real-Mesa compute callback/lib ioctl CPU fixture; never starts a GPU."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def dependencies(path):
    tokens = shlex.split(path.read_text().replace("\\\n", " "))
    if not tokens or not tokens[0].endswith(":"):
        raise RuntimeError("unknown compiler dependency syntax")
    return {str(Path(token).resolve(strict=True)) for token in tokens[1:]}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", choices=("compute", "framebuffer-blit"), default="compute")
    parser.add_argument("--artifacts", type=Path,
                        help="export actual validated callback submissions for explicit RTL qualification")
    args = parser.parse_args()
    if sys.platform != "linux" and args.fixture == "compute":
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
              "-D_POSIX_C_SOURCE=200809L", "-ffunction-sections", "-fdata-sections"]
    if sys.platform == "darwin":
        common += ["-D__user=", "-D__aligned_u64=__u64", "-D_DARWIN_C_SOURCE"]
        incs += ["-I" + str(ROOT / "userspace/tests/host_sync_file")]
    else:
        common += ["-DHAVE_ENDIAN_H"]
    if args.fixture == "framebuffer-blit":
        common += ["-O1", "-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    primitives = ["-Dioctl=cs_fixture_ioctl", "-Dmmap=cs_fixture_mmap", "-Dmunmap=cs_fixture_munmap",
                  "-Ddup=cs_fixture_dup", "-Dclose=cs_fixture_close"]
    units = [
        (ROOT / ("userspace/tests/cs_callback_flow.c" if args.fixture == "compute" else
                 "userspace/tests/framebuffer_blit_callback.c"), []),
        (ROOT / "userspace/tests/cs_graphics_unused_stubs.c", []),
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
    temporary = ROOT / ".artifacts"
    temporary.mkdir(exist_ok=True)
    common_git = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute",
        "--git-common-dir"], cwd=ROOT, text=True).strip())
    gpu_artifacts = (common_git.parent / ".artifacts").resolve()
    with tempfile.TemporaryDirectory(prefix="opengpu-cs-", dir=temporary) as tmp:
        env = os.environ.copy()
        env.update(TMPDIR=tmp, XDG_CACHE_HOME=str(Path(tmp) / "cache"), CCACHE_DIR=str(Path(tmp) / "ccache"))
        build = Path(os.environ.get("OPENGPU_COMPUTE_TEST_BUILD", tmp)).resolve()
        if not build.is_relative_to(gpu_artifacts):
            parser.error("CPU build outputs must stay inside the GPU repository .artifacts")
        build.mkdir(parents=True, exist_ok=True)
        objects = []
        source_inputs = {}
        if args.artifacts and args.fixture == "framebuffer-blit":
            for name in (Path(__file__).resolve(), ROOT / "scripts/framebuffer_blit_fixture.py"):
                source_inputs[str(name)] = digest(name)
        compile_commands = []
        compiler_path = Path(shutil.which(compiler[0])).resolve(strict=True)
        compiler_before = dict(path=str(compiler_path), sha256=digest(compiler_path),
            version=subprocess.check_output([str(compiler_path), "--version"], text=True, env=env), flags=compiler[1:])
        for source, extra in units:
            obj = build / (source.stem + ".o")
            dep = obj.with_suffix(".d")
            if args.artifacts and args.fixture == "framebuffer-blit":
                subprocess.run(compiler + common + incs + extra + ["-MM", "-MF", str(dep),
                    "-MT", str(obj), str(source)], check=True, env=env)
                before = dependencies(dep)
                for name in before:
                    value = digest(name)
                    if name in source_inputs and source_inputs[name] != value:
                        raise RuntimeError("CPU capture input changed during compilation: " + name)
                    source_inputs[name] = value
            command = compiler + common + incs + extra + ["-MMD", "-MF", str(dep),
                "-c", str(source), "-o", str(obj)]
            subprocess.run(command, check=True, env=env)
            compile_commands.append(command)
            if args.artifacts and args.fixture == "framebuffer-blit" and dependencies(dep) != before:
                raise RuntimeError("CPU capture include closure changed during compilation")
            objects.append(str(obj))
        binary = build / "cs_callback_flow"
        link = ["-Wl,-dead_strip"] if sys.platform == "darwin" else ["-Wl,--gc-sections"]
        sanitize = ["-fsanitize=address,undefined"] if args.fixture == "framebuffer-blit" else []
        link_command = compiler + sanitize + link + objects + ["-lm", "-o", str(binary)]
        subprocess.run(link_command, check=True, env=env)
        # A fresh directory prevents failed/repeated preparation from silently
        # replacing temporary qualification inputs.
        env.pop("OPENGPU_COMPUTE_ARTIFACTS", None)
        env.pop("OPENGPU_FRAMEBUFFER_BLIT_ARTIFACTS", None)
        if args.artifacts:
            args.artifacts = args.artifacts.resolve()
            if not args.artifacts.is_relative_to(gpu_artifacts):
                parser.error("qualification inputs must stay inside the GPU repository .artifacts")
            args.artifacts.mkdir(parents=True, exist_ok=False)
            env["OPENGPU_COMPUTE_ARTIFACTS" if args.fixture == "compute" else
                "OPENGPU_FRAMEBUFFER_BLIT_ARTIFACTS"] = str(args.artifacts)
        subprocess.run([str(binary)], check=True, env=env)
        if args.artifacts and args.fixture == "framebuffer-blit":
            from framebuffer_blit_fixture import BlitFixture, NAMES
            paths = {path.stem: path for path in args.artifacts.glob("*.blit")}
            if set(paths) != set(NAMES):
                raise RuntimeError("incomplete actual-caller blit capture")
            for name, path in paths.items():
                BlitFixture.read(path.read_bytes())
            if any(digest(name) != value for name, value in source_inputs.items()) or \
                    digest(compiler_path) != compiler_before["sha256"]:
                raise RuntimeError("CPU capture source or compiler changed during preparation")
            manifest = dict(schema="opengpu-framebuffer-blit-inputs-v1", cpuModelOnly=True,
                gpuExecution=False, sourceRoot=str(ROOT), sourceInputs=source_inputs,
                compiler=compiler_before, compileCommands=compile_commands, linkCommand=link_command,
                capturerSha256=digest(binary),
                cases={name: hashlib.sha256(paths[name].read_bytes()).hexdigest() for name in NAMES})
            (args.artifacts / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print("37 actual-caller blit inputs/source dependency closure checked; GPU execution remains pending")
        if args.artifacts and args.fixture == "compute":
            cases = {}
            for name in ("healthy-a", "bounds", "denied", "healthy-b"):
                meta = dict(line.split("=", 1) for line in (args.artifacts / (name + ".meta")).read_text().splitlines())
                assert [int(meta[f"grid.{i}"]) for i in range(3)] == ([2, 1, 1] if name.startswith("healthy") else [1, 1, 1])
                assert [int(meta[f"local.{i}"]) for i in range(3)] == ([5, 5, 5] if name == "healthy-b" else [8, 4, 4])
                assert len({meta[f"binding.{i}.handle"] for i in range(4)}) == 4
                for i in range(4):
                    assert int(meta[f"binding.{i}.bytes"]) == (3 if name == "bounds" and i == 0 else 128 * 1024 * 1024)
                    assert int(meta[f"binding.{i}.offset"]) == 0
                    assert int(meta[f"binding.{i}.access"]) == (1 if i == 0 else 3)
                kernarg = (args.artifacts / (name + ".kernarg")).read_bytes()
                assert kernarg == b"".join(int(meta[f"grid.{i}"]).to_bytes(4, "little") for i in range(3))
                cases[name] = {suffix: hashlib.sha256((args.artifacts / (name + "." + suffix)).read_bytes()).hexdigest()
                               for suffix in ("words", "tgsi", "kernarg", "meta")}
            (args.artifacts / "manifest.json").write_text(json.dumps({"format": 1, "cases": cases,
                "scope": "actual callback/compiler/lib/validator CPU capture; GPU execution not run"}, indent=2) + "\n")
            (args.artifacts / "manifest.properties").write_text("format=1\n" + "".join(
                f"{name}.{suffix}.sha256={digest}\n" for name, hashes in cases.items()
                for suffix, digest in hashes.items()))


if __name__ == "__main__":
    main()
