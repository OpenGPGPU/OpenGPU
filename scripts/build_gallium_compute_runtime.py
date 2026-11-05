#!/usr/bin/env python3
"""Isolated AArch64 headless Gallium executable using real Mesa auxiliaries.

Never stages or rebuilds Mesa, boots a guest, or opens a GPU device. The optional
CPU preflight is a separate executable; its word decoder is not in the guest.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def aarch64_object(path):
    with path.open("rb") as stream:
        header = stream.read(20)
    if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
        raise SystemExit(f"not an actual little-endian AArch64 ELF input: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesa-source", type=Path, required=True)
    parser.add_argument("--mesa-build", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="new isolated output directory")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    parser.add_argument("--run-preflight", action="store_true")
    parser.add_argument("--sanitize-preflight", action="store_true")
    args = parser.parse_args()
    mesa, build, out = args.mesa_source.resolve(), args.mesa_build.resolve(), args.out.resolve()
    if not (mesa / "VERSION").read_text().strip().startswith("22.3.6"):
        raise SystemExit("the pinned actual Mesa 22.3.6 source is required")
    if out.is_relative_to(mesa) or out.is_relative_to(build):
        raise SystemExit("output must be outside the shared Mesa source/build")
    out.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(argv, name):
        commands.append({"argv": [str(x) for x in argv], "log": name + ".log"})
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        env = os.environ.copy()
        if name == "cpu-preflight" and args.sanitize_preflight:
            env.update(ASAN_OPTIONS="detect_leaks=1", UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
        with (out / (name + ".log")).open("w") as log:
            subprocess.run([str(x) for x in argv], check=True, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        return (out / (name + ".log")).read_text()

    # Reuse the actual configured feature definitions and include search paths,
    # not a guessed Mesa configuration or the old staged OpenGPU driver archive.
    database = json.loads((build / "compile_commands.json").read_text())
    config = next(x for x in database if x["output"].endswith("tgsi_tgsi_parse.c.o"))
    configured = shlex.split(config["command"])
    definitions = [x for x in configured if x.startswith(("-D", "-U"))]
    include = ["-I" + str(ROOT / x) for x in ("userspace/gallium", "userspace", "driver")]
    include += ["-I" + str((build / x[2:]).resolve()) for x in configured if x.startswith("-I")]
    flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
             "-Wno-sign-compare", "-fno-strict-aliasing", "-ffunction-sections", "-fdata-sections",
             "-pthread"] + definitions + ["-UNDEBUG"] + include
    cc = shlex.split(args.cc)
    run(cc + ["--version"], "compiler")
    archives = [build / p for p in (
        "src/gallium/auxiliary/libgallium.a", "src/util/libmesa_util.a",
        "src/util/format/libmesa_format.a", "src/c11/impl/libmesa_util_c11.a")]
    members = {}
    for i, archive in enumerate(archives):
        listing = run(["ar", "t", archive], f"archive-{i}").splitlines()
        paths = [Path(x) if Path(x).is_absolute() else archive.parent / x for x in listing]
        for path in paths:
            aarch64_object(path)
            members[str(path.resolve())] = {"archive": str(archive), "sha256": sha(path)}
    (out / "mesa-members.json").write_text(json.dumps(members, indent=2, sort_keys=True) + "\n")
    (out / "mesa-configuration.json").write_text(json.dumps({"entry": config, "effective_flags": flags,
        "archive_sha256": {str(x): sha(x) for x in archives}}, indent=2) + "\n")

    sources = [ROOT / "userspace/examples/gallium_compute.c"] + [ROOT / ("userspace/" + x) for x in (
        "gallium/opengpu_screen.c", "gallium/opengpu_context.c", "gallium/opengpu_cs_emit.c",
        "gallium/opengpu_fs_emit.c", "gallium/opengpu_vs_emit.c", "pipe_opengpu.c", "opengpu.c")]
    objects = []
    for i, source in enumerate(sources):
        obj = out / (source.stem + ".o")
        run(cc + flags + ["-MMD", "-MF", str(obj.with_suffix(".d")), "-c", source, "-o", obj], f"compile-{i}")
        aarch64_object(obj)
        objects.append(obj)
    binary = out / "gallium_compute"
    libs = ["-Wl,--start-group"] + archives + ["-Wl,--end-group", "-lm", "-ldl", "-pthread"]
    run(cc + ["-static", "-Wl,--gc-sections", "-Wl,-t,-t", "-Wl,-Map=" + str(out / "guest.map")]
        + objects + libs + ["-o", binary], "link-guest")
    aarch64_object(binary)
    dynamic = run(["readelf", "-d", binary], "guest-dynamic")
    if "NEEDED" in dynamic:
        raise SystemExit("guest executable unexpectedly has shared library dependencies")
    symbols = run(["nm", "-a", binary], "guest-symbols")
    if "cs_vm_group" in symbols or "cs_fixture_" in symbols:
        raise SystemExit("CPU model/stub symbol in guest executable")
    # u_upload_alloc is not referenced by this production driver and is
    # legitimately removed by --gc-sections; do not retain dead code as proof.
    required = ["u_upload_create_default", "u_upload_destroy", "u_default_buffer_subdata",
                "u_default_texture_subdata", "u_default_transfer_flush_region", "tgsi_dump", "tgsi_build_full_instruction"]
    for symbol in required:
        if not any(line.split()[-1:] == [symbol] for line in symbols.splitlines()):
            raise SystemExit("missing actual Mesa helper: " + symbol)
    selected = {line.strip(): members[line.strip()] for line in (out / "link-guest.log").read_text().splitlines()
                if line.strip() in members}
    if not selected:
        raise SystemExit("no actual Mesa members in the normal linker trace")
    (out / "selected-mesa-members.json").write_text(json.dumps(selected, indent=2, sort_keys=True) + "\n")

    if args.run_preflight:
        if platform.machine().lower() not in ("aarch64", "arm64"):
            raise SystemExit("run-preflight requires native AArch64 userspace; no automatic emulator")
        run([binary, "--preflight", out / "guest-preflight"], "guest-preflight")
        sanitizer = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"] if args.sanitize_preflight else []
        cpu_objects = []
        for i, source in enumerate([ROOT / "userspace/tests/gallium_compute_preflight.c", ROOT / "userspace/gallium/opengpu_cs_emit.c"]):
            obj = out / ("cpu-" + source.stem + ".o")
            run(cc + flags + sanitizer + ["-MMD", "-MF", str(obj.with_suffix(".d")), "-c", source, "-o", obj], f"cpu-compile-{i}")
            cpu_objects.append(obj)
        cpu = out / "cpu_preflight"
        run(cc + sanitizer + ["-Wl,--gc-sections", "-Wl,-Map=" + str(out / "cpu.map")]
            + cpu_objects + libs + ["-o", cpu], "link-cpu")
        run([cpu], "cpu-preflight")
    inputs = {}
    for d in out.glob("*.d"):
        for token in d.read_text().replace("\\\n", " ").split(":", 1)[1].split():
            p = Path(token)
            if not p.is_absolute():
                p = ROOT / p
            inputs[str(p.resolve())] = sha(p)
    for d in out.glob("*.d"):
        if not d.name.startswith("cpu-") and "cs_word_vm.h" in d.read_text():
            raise SystemExit("word decoder in guest dependency set")
    (out / "actual-inputs.json").write_text(json.dumps(inputs, indent=2, sort_keys=True) + "\n")
    result = {"status": "PASS", "binary_sha256": sha(binary), "binary_bytes": binary.stat().st_size,
              "architecture": "AArch64 ELF64 static", "actual_source_units": [str(x) for x in sources],
              "real_Mesa_helpers": required, "CPU_preflight": args.run_preflight,
              "sanitized_CPU_oracle": args.sanitize_preflight, "scope": "stub-free link/CPU evidence; guest and GPU unrun"}
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
