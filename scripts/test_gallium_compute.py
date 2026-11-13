#!/usr/bin/env python3
"""Small real-Mesa compute callback/lib ioctl CPU fixture; never starts a GPU."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", choices=("compute", "framebuffer-blit"), default="compute")
    parser.add_argument("--artifacts", type=Path,
                        help="export four real validated callback submissions for explicit RTL qualification")
    args = parser.parse_args()
    if args.artifacts and args.fixture != "compute":
        parser.error("--artifacts is only supported for the compute fixture")
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
    with tempfile.TemporaryDirectory(prefix="opengpu-cs-", dir=temporary) as tmp:
        build = Path(os.environ.get("OPENGPU_COMPUTE_TEST_BUILD", tmp))
        build.mkdir(parents=True, exist_ok=True)
        objects = []
        for source, extra in units:
            obj = build / (source.stem + ".o")
            subprocess.run(compiler + common + incs + extra + ["-MMD", "-MF", str(obj.with_suffix(".d")),
                "-c", str(source), "-o", str(obj)], check=True)
            objects.append(str(obj))
        binary = build / "cs_callback_flow"
        link = ["-Wl,-dead_strip"] if sys.platform == "darwin" else ["-Wl,--gc-sections"]
        sanitize = ["-fsanitize=address,undefined"] if args.fixture == "framebuffer-blit" else []
        subprocess.run(compiler + sanitize + link + objects + ["-lm", "-o", str(binary)], check=True)
        env = os.environ.copy()
        # Only this explicit mode adds four CPU submissions. A fresh directory
        # prevents a failed/repeated preparation from silently replacing pins.
        env.pop("OPENGPU_COMPUTE_ARTIFACTS", None)
        if args.artifacts:
            args.artifacts = args.artifacts.resolve()
            args.artifacts.mkdir(parents=True, exist_ok=False)
            env["OPENGPU_COMPUTE_ARTIFACTS"] = str(args.artifacts)
        subprocess.run([str(binary)], check=True, env=env)
        if args.artifacts:
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
