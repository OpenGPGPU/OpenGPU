#!/usr/bin/env python3
"""Local CPU qualification using an existing 16-item real H8 runtime build.

Recompile one Mesa object and one control fixture; reuse the app/frontend/
callbacks and every other Mesa object. All outputs are private. No device,
driver, Linux, RTL or guest is built or executed.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time

from stage_mesa_buffer_txf_mask import BEFORE, SOURCE, sha, stage

ROOT = Path(__file__).resolve().parents[1]


def plan_mesa_compile(entry, mesa, mb, private, candidate, cc):
    """Support the pinned Meson argv shape without any shared output path."""
    if not cc:
        raise ValueError("an explicit C compiler executable is required")
    output = Path(entry["output"])
    if output.is_absolute() or ".." in output.parts or output.suffix != ".o":
        raise ValueError("Mesa output must be a relative object path without ..")
    directory = Path(entry["directory"]).resolve()
    if directory != mb or (directory / entry["file"]).resolve() != mesa / SOURCE:
        raise ValueError("unexpected Mesa compile directory or source")
    raw = entry.get("arguments") or shlex.split(entry["command"])
    if not raw or raw[-2:] != ["-c", entry["file"]] or raw.count("-c") != 1:
        raise ValueError("unsupported Mesa source argument shape")
    values = {}
    expected = {"-o": str(output), "-MF": str(output)+".d", "-MQ": str(output)}
    for option, value in expected.items():
        if raw.count(option) != 1:
            raise ValueError("unsupported Mesa output option: " + option)
        i = raw.index(option)+1
        if i >= len(raw)-2 or raw[i] != value:
            raise ValueError("unexpected Mesa output argument: " + option)
        values[i] = option
    for i, word in enumerate(raw[1:-2], 1):
        if (not word.startswith("-") and i not in values) or word.startswith(("--output", "@")):
            raise ValueError("unsupported additional Mesa source/output argument")
        if any(word.startswith(option) and word != option for option in expected):
            raise ValueError("joined Mesa output arguments are not supported")
    if any(word.startswith("-MT") for word in raw) or raw.count("-MD") != 1 or "-MMD" in raw:
        raise ValueError("unsupported Mesa dependency-output shape")
    obj = private / output
    argv = list(cc) + raw[1:]
    offset = len(cc)-1
    for i, option in values.items():
        argv[i+offset] = str(obj)+".d" if option == "-MF" else str(obj)
    argv[-1] = str(candidate)
    return argv, obj, mb / output


def reproduce(args):
    mesa, mb = args.mesa_source.resolve(), args.mesa_build.resolve()
    base, out = args.runtime_build.resolve(), args.out.resolve()
    if out.is_relative_to(mesa) or out.is_relative_to(mb) or out.is_relative_to(base):
        raise ValueError("new output must be independent of all input trees")
    if sha(mesa / SOURCE) != BEFORE:
        raise ValueError("the baseline must use the unmodified pinned Mesa source")
    result = json.loads((base / "result.json").read_text())
    if result["status"] != "PASS_CPU_BUILD_PREFLIGHT" or result.get("qualification_items") != 16:
        raise ValueError("an existing CPU-qualified 16-item H8 runtime build is required")
    original = json.loads((base / "input-before.json").read_text())
    if any(sha(p) != digest for p, digest in original.items()):
        raise ValueError("existing runtime Mesa inputs changed")
    commands = json.loads((base / "commands.json").read_text())
    link = next(c["argv"] for c in commands if c["log"] == "link-guest.log")
    app_compile = next(c["argv"] for c in commands if c["log"] == "compile-gallium_buffer_glsl.log")
    app_obj = next(x for x in link if x.endswith("/gallium_buffer_glsl.o"))
    reused = {x: sha(x) for x in link if x.endswith(".o")}
    out.mkdir(parents=True, exist_ok=False)
    deadline = time.monotonic() + 120
    actual = []

    def run(argv, label, cwd=out, env=None):
        argv = list(map(str, argv))
        record = dict(argv=argv, cwd=str(cwd), log=label + ".log")
        actual.append(record)
        (out / "commands.json").write_text(json.dumps(actual, indent=2) + "\n")
        with (out / (label + ".log")).open("w") as log:
            subprocess.run(argv, cwd=cwd, env=env, check=True, stdout=log,
                           stderr=subprocess.STDOUT, timeout=max(0.01, deadline-time.monotonic()))

    candidate = stage(mesa, out / "source")
    inventory = json.loads((base / "mesa-members.json").read_text())
    archive = mb / "src/gallium/auxiliary/libgallium.a"
    private = out / "mesa-build"
    rows = {str(archive): original[str(archive)]}
    rows.update({p: r["sha256"] for p, r in inventory.items() if r["archive"] == str(archive)})
    for path, digest in rows.items():
        src = Path(path)
        if sha(src) != digest:
            raise ValueError("thin member changed: " + path)
        dst = private / src.relative_to(mb)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        if dst.stat().st_nlink != 1 or (src.stat().st_dev, src.stat().st_ino) == (dst.stat().st_dev, dst.stat().st_ino):
            raise ValueError("thin member copy is not independent")
    entry = next(e for e in json.loads((mb / "compile_commands.json").read_text())
                 if (Path(e["directory"]) / e["file"]).resolve() == mesa / SOURCE)
    cc, cxx = shlex.split(args.cc), shlex.split(args.cxx)
    argv, obj, original_obj = plan_mesa_compile(entry, mesa, mb, private, candidate, cc)
    run(argv, "compile-one-Mesa-object", Path(entry["directory"]))
    def globals_(path):
        text = subprocess.check_output(shlex.split(args.nm)+["-g", "--defined-only", str(path)], text=True)
        return sorted(line.split()[-2:] for line in text.splitlines() if line.split())
    if globals_(original_obj) != globals_(obj):
        raise ValueError("defined globals changed; cannot reuse the thin archive index")
    differences = [p for p, digest in rows.items() if sha(private / Path(p).relative_to(mb)) != digest]
    if differences != [str(original_obj)]:
        raise ValueError("unexpected private thin archive changes")
    old_repo = Path(app_compile[app_compile.index("-c")+1]).parents[2]
    flags = []
    for flag in app_compile[1:app_compile.index("-MMD")]:
        for relative in ("userspace", "driver"):
            prefix = "-I"+str(old_repo/relative)
            if flag == prefix or flag.startswith(prefix+"/"):
                flag = "-I"+str(ROOT/relative)+flag[len(prefix):]
                break
        flags.append(flag)
    fixture = out / "controls.o"
    run(cc + flags + ["-Wno-missing-field-initializers", "-fsanitize=address,undefined",
        "-fno-omit-frame-pointer", "-I"+str(mesa/"src/compiler/nir"), "-I"+str(mb/"src/compiler/nir"),
        "-MMD", "-MF", fixture.with_suffix(".d"), "-c", ROOT/"userspace/tests/mesa_buffer_txf_mask.c", "-o", fixture],
        "compile-control-fixture")
    def relink(target, object_, patched, sanitized=False):
        argv = cxx + link[1:]
        argv[argv.index(app_obj)] = str(object_)
        argv[argv.index("-o")+1] = str(out/target)
        argv = ["-Wl,-Map="+str(out/(target+".map")) if x.startswith("-Wl,-Map=") else x for x in argv]
        if patched:
            argv = [str(private/Path(x).relative_to(mb)) if x == str(archive) else x for x in argv]
        if sanitized:
            argv[len(cxx):len(cxx)] = ["-fsanitize=address,undefined"]
        run(argv, "link-"+target)
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=0:abort_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1", NIR_DEBUG="tgsi")
    for mode in ("baseline", "patched"):
        relink("controls-"+mode, fixture, mode=="patched", True)
        captures=out/(mode+"-controls");captures.mkdir()
        run([out/("controls-"+mode), mode, captures, ROOT/"userspace/tests/glsl_buffer_txf_masks"],
            "controls-"+mode, env=env)
    for name in ("phi-register", "nonbuffer", "graphics", "texture-size", "essl-texture-size"):
        if (out/"baseline-controls"/(name+".tgsi.bin")).read_bytes() != (out/"patched-controls"/(name+".tgsi.bin")).read_bytes():
            raise ValueError("complete fallback TGSI bytes differ: "+name)
    relink("gallium_buffer_glsl", app_obj, True)
    run([out/"gallium_buffer_glsl", "--preflight", out/"frontend-16", base/"glsl"], "actual-six-shader-frontend")
    run([base/"CPU-oracle", out/"frontend-16", "--texture-components", "1"], "actual-eleven-segment-word-oracle", env=env)
    if any(sha(p) != d for p, d in {**original, **reused}.items()):
        raise ValueError("baseline runtime/shared Mesa input changed")
    (out/"result.json").write_text(json.dumps(dict(status="PASS_CPU_BUFFER_TXF_READ_MASK",
        one_Mesa_object_recompiled=True, thin_index_globals_equal=True,
        six_ESSL_controls_and_ten_NIR_controls_per_binary=True, complete_fallback_TGSI_bytes_equal=True,
        six16_item_programs_and_eleven_word_oracle_segments=True,
        GPU_executed=False, guest_executed=False), indent=2)+"\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("mesa-source", "mesa-build", "runtime-build", "out"):
        parser.add_argument("--"+name, type=Path, required=True)
    parser.add_argument("--cc", default="gcc")
    parser.add_argument("--cxx", default="g++")
    parser.add_argument("--nm", default="nm", help="GNU nm for the actual AArch64 objects")
    reproduce(parser.parse_args())


if __name__ == "__main__":
    main()
