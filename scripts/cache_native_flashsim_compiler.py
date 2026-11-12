#!/usr/bin/env python3
"""Reuse verified C++ shards across FlashSim builds with explicit provenance.

Prepare an immutable plan, set CXX to cache_native_flashsim_cxx and OPENGPU_NATIVE_CACHE_PLAN
while using build_native_flashsim.py, then verify every resulting object. This
is build acceleration only; native scene and image qualification is separate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from build_native_flashsim import ENVIRONMENT, generated_digest

PLAN_ENV = "OPENGPU_NATIVE_CACHE_PLAN"
UNRECORDED_ENV = (
    "DEVELOPER_DIR", "TOOLCHAINS", "C_INCLUDE_PATH", "OBJC_INCLUDE_PATH",
    "CCC_OVERRIDE_OPTIONS", "CCC_CXXFLAGS", "LIBRARY_PATH", "SOURCE_DATE_EPOCH",
)
SENSITIVE = re.compile(
    rb"\b(?:__FILE__|__BASE_FILE__|__FILE_NAME__|__builtin_FILE|__builtin_FILE_NAME|"
    rb"__LINE__|__builtin_LINE|__builtin_COLUMN|__DATE__|__TIME__|__TIMESTAMP__|"
    rb"__COUNTER__|__has_include|__has_include_next|__INCLUDE_LEVEL__|__func__|"
    rb"__FUNCTION__|__PRETTY_FUNCTION__|__builtin_FUNCTION|__builtin_source_location|"
    rb"__builtin_return_address|__builtin_frame_address|_Pragma)\b|\bassert\s*\("
)
DIRECTIVE = re.compile(
    rb'^\s*#\s*(?:pragma\s+once|include\s+(?:"dut\.h"|'
    rb'<(?:cstdint|cstring|stdexcept|string|cstdio)>))\s*$'
)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def safe_generated(path: Path) -> bool:
    """Reject context-sensitive tokens and untracked preprocessor state."""
    with path.open("rb") as stream:
        for line in stream:
            if SENSITIVE.search(line) or line.rstrip(b"\r\n").endswith(b"\\"):
                return False
            if line.lstrip().startswith(b"#") and not DIRECTIVE.fullmatch(line):
                return False
    return True


def clean_include_dir(path: Path) -> None:
    for item in path.iterdir():
        if (item.is_symlink() or not item.is_file() or not
                (item.name in ("dut.h", "flashsim-reshard.json") or
                 re.fullmatch(r"dut_\w+\.cpp", item.name))):
            raise ValueError("unexpected generated include-directory entry: " + str(item))


def file_stat(path: Path):
    info = path.stat()
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns]


def check_environment(environment):
    if {key: os.environ.get(key, "") for key in environment} != environment:
        raise ValueError("compiler environment differs from the cache")
    if any(os.environ.get(key) for key in UNRECORDED_ENV):
        raise ValueError("an unrecorded compiler override is set")


def write_new(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as stream:
        json.dump(data, stream, indent=2, sort_keys=True)
        stream.write("\n")


def prepare(dut: Path, build: Path, cache: Path, plan: Path, events: Path) -> dict:
    dut, build, cache, plan, events = (p.resolve() for p in (dut, build, cache, plan, events))
    if (dut == cache or build == cache or any(p == dut or p.is_relative_to(dut) for p in (build, plan, events)) or
            plan == events or plan.is_relative_to(events)):
        raise ValueError("input, build, plan and event paths must be separate")
    if plan.exists() or events.exists():
        raise FileExistsError("plan and event directory must be new")
    previous_path = cache / "native-flashsim-build.json"
    previous = json.loads(previous_path.read_text())
    flags = previous["base_flags"]
    if len(flags) < 3 or flags[-2] != "-I":
        raise ValueError("cached build has unsupported include flags")
    old_dut = Path(flags[-1]).resolve()
    if build == old_dut or build.is_relative_to(old_dut) or plan.is_relative_to(old_dut) or events.is_relative_to(old_dut):
        raise ValueError("cache inputs and new outputs must be separate")
    clean_include_dir(dut)
    clean_include_dir(old_dut)
    header = generated_digest(dut / "dut.h", header=True)
    if (header != previous["dut_header_sha256"] or header != digest(old_dut / "dut.h") or
            not safe_generated(dut / "dut.h")):
        raise ValueError("header differs or uses unsupported path-dependent syntax")
    compiler = Path(previous["compiler"])
    if not compiler.is_file():
        raise ValueError("cached compiler is unavailable")
    with compiler.open("rb") as stream:
        if stream.read(2) == b"#!":
            raise ValueError("cached compiler must be a compiler binary")
    if subprocess.check_output([str(compiler), "--version"], text=True) != previous["compiler_version"]:
        raise ValueError("compiler version differs from the cache")
    check_environment(previous["environment"])
    candidates = {}
    for source in sorted(dut.glob("dut_*.cpp")):
        name = source.name
        source_hash = generated_digest(source)
        if source_hash != previous["dut_sources"].get(name) or not safe_generated(source):
            continue
        old_source = old_dut / name
        obj = cache / (source.stem + ".o")
        metadata = cache / (source.stem + ".json")
        if not old_source.is_file() or not obj.is_file() or not metadata.is_file():
            continue
        prior = json.loads(metadata.read_text())
        context = prior["context"]
        opt = context["optimization"]
        effective = (flags[:-2] + ["-fno-strict-aliasing"] + flags[-2:]
                     if previous["conservative_alias"] and opt != "-O0" else flags)
        expected = {"compiler": str(compiler), "compiler_version": previous["compiler_version"],
                    "flags": effective, "header_sha256": header, "environment": previous["environment"],
                    "source_sha256": source_hash, "optimization": opt}
        with source.open("rb") as stream:
            if previous["precompiled_header"] and stream.readline() == b'#include "dut.h"\n':
                expected["precompiled_header"] = True
        if (context != expected or digest(old_source) != source_hash or
                prior["output_sha256"] != previous["objects"].get(obj.name) or
                digest(obj) != prior["output_sha256"]):
            raise ValueError("cached source, compiler context or object differs: " + name)
        candidates[name] = {"source_sha256": source_hash, "object": str(obj),
                            "object_sha256": prior["output_sha256"], "metadata": str(metadata),
                            "metadata_sha256": digest(metadata), "compiled_context": context}
    helper = Path(__file__).resolve()
    script = helper.with_name("cache_native_flashsim_cxx")
    record = {"version": 1, "scope": "object reuse plan; not a model qualification",
              "dut": str(dut), "old_dut": str(old_dut), "build": str(build), "events": str(events),
              "compiler": str(compiler), "compiler_sha256": digest(compiler),
              "compiler_stat": file_stat(compiler), "compiler_version": previous["compiler_version"],
              "environment": previous["environment"], "header_sha256": header,
              "header_stat": file_stat(dut / "dut.h"), "old_header_sha256": digest(old_dut / "dut.h"),
              "reference_manifest": str(previous_path), "reference_manifest_sha256": digest(previous_path),
              "script": str(script), "script_sha256": digest(script),
              "helper": str(helper), "helper_sha256": digest(helper), "candidates": candidates}
    events.mkdir(parents=True, exist_ok=False)
    try:
        write_new(plan, record)
    except BaseException:
        events.rmdir()
        raise
    return record


def load_plan(path: Path) -> dict:
    data = json.loads(path.read_text())
    helper = Path(__file__).resolve()
    if (data.get("version") != 1 or data.get("helper") != str(helper) or
            data.get("script") != str(helper.with_name("cache_native_flashsim_cxx"))):
        raise ValueError("cache plan belongs to another compiler adapter")
    if digest(helper) != data["helper_sha256"] or digest(Path(data["script"])) != data["script_sha256"]:
        raise ValueError("compiler adapter changed after planning")
    compiler = Path(data["compiler"])
    if file_stat(compiler) != data["compiler_stat"]:
        raise ValueError("compiler binary changed after planning")
    check_environment(data["environment"])
    return data


def candidate_matches(args, source: Path, target: Path, plan: dict, candidate: dict,
                      pch_must_exist=True) -> bool:
    context = candidate["compiled_context"]
    flags = context["flags"][1:-1] + [plan["dut"]]
    if context.get("precompiled_header"):
        index = len(flags)
        if args[index:index + 1] != ["-include-pch"] or len(args) <= index + 1:
            return False
        pch = Path(args[index + 1]).resolve()
        if ((pch_must_exist and not pch.is_file()) or
                not pch.is_relative_to(Path(plan["build"])) or
                pch.name != context["optimization"][1:] + ".pch"):
            return False
        flags += ["-include-pch", str(pch)]
    return args == [*flags, context["optimization"], "-c", str(source), "-o", str(target)]


def compile_adapter(args) -> int:
    if os.environ.pop("OPENGPU_CACHE_PARENT_ENV", None) != "1":
        raise ValueError("compiler adapter requires the environment-preserving launcher")
    # Some system Python launchers inject SDKROOT and CPATH before Python
    # starts. Restore the values captured by the shell in the builder process.
    for name in (*ENVIRONMENT, *UNRECORDED_ENV):
        value = os.environ.pop("OPENGPU_CACHE_PARENT_" + name)
        if value.startswith("x"):
            os.environ[name] = value[1:]
        elif value == "":
            os.environ.pop(name, None)
        else:
            raise ValueError("invalid compiler environment snapshot")
    location = os.environ.get(PLAN_ENV)
    if not location:
        raise ValueError(PLAN_ENV + " must identify a prepared cache plan")
    plan_path = Path(location).resolve()
    plan = load_plan(plan_path)
    compiler = plan["compiler"]
    if "--version" in args or "-c" not in args or "-o" not in args:
        os.execv(compiler, [compiler, *args])
    try:
        source = Path(args[args.index("-c") + 1]).resolve()
        target = Path(args[args.index("-o") + 1]).resolve()
    except (IndexError, ValueError):
        os.execv(compiler, [compiler, *args])
    dut, build = Path(plan["dut"]), Path(plan["build"])
    if source.parent != dut or target != build / (source.stem + ".o.tmp") or not re.fullmatch(r"dut_\w+\.cpp", source.name):
        os.execv(compiler, [compiler, *args])
    if file_stat(dut / "dut.h") != plan["header_stat"]:
        raise ValueError("DUT header changed after cache planning")
    candidate = plan["candidates"].get(source.name)
    reused = bool(candidate and candidate_matches(args, source, target, plan, candidate))
    if reused:
        if (digest(source) != candidate["source_sha256"] or
                digest(Path(candidate["metadata"])) != candidate["metadata_sha256"] or
                digest(Path(candidate["object"])) != candidate["object_sha256"]):
            raise ValueError("cache input changed before reuse: " + source.name)
        shutil.copyfile(candidate["object"], target)
        if digest(target) != candidate["object_sha256"]:
            raise ValueError("copied cache object differs")
    else:
        result = subprocess.run([compiler, *args])
        if result.returncode:
            return result.returncode
    event = {"version": 1, "mode": "reused" if reused else "compiled",
             "plan_sha256": digest(plan_path), "source": str(source),
             "source_sha256": digest(source), "header_sha256": plan["header_sha256"],
             "object_sha256": digest(target), "compiler": compiler,
             "compiler_sha256": plan["compiler_sha256"], "arguments": args}
    if reused:
        event["cache"] = candidate
    event_path = Path(plan["events"]) / (source.stem + ".json")
    temporary = event_path.with_suffix(".json." + str(os.getpid()) + ".tmp")
    temporary.write_text(json.dumps(event, indent=2, sort_keys=True) + "\n")
    temporary.replace(event_path)
    return 0


def verify(plan_path: Path, witness: Path) -> dict:
    plan_path, witness = plan_path.resolve(), witness.resolve()
    if witness.exists():
        raise FileExistsError("witness must be new")
    plan = load_plan(plan_path)
    dut, build, events = map(Path, (plan["dut"], plan["build"], plan["events"]))
    manifest_path = build / "native-flashsim-build.json"
    manifest = json.loads(manifest_path.read_text())
    if (manifest["compiler"] != plan["script"] or manifest["compiler_version"] != plan["compiler_version"] or
            manifest["environment"] != plan["environment"] or manifest["dut_header_sha256"] != plan["header_sha256"] or
            digest(dut / "dut.h") != plan["header_sha256"] or digest(Path(plan["old_dut"]) / "dut.h") != plan["header_sha256"] or
            digest(Path(plan["compiler"])) != plan["compiler_sha256"] or
            subprocess.check_output([plan["compiler"], "--version"], text=True) != plan["compiler_version"] or
            digest(Path(plan["reference_manifest"])) != plan["reference_manifest_sha256"]):
        raise ValueError("build, compiler, header or cache identity differs")
    clean_include_dir(dut)
    clean_include_dir(Path(plan["old_dut"]))
    sources = manifest["dut_sources"]
    if {p.name for p in dut.glob("dut_*.cpp")} != set(sources):
        raise ValueError("generated source set differs from the built sources")
    if set(manifest["objects"]) != {Path(name).stem + ".o" for name in sources}:
        raise ValueError("builder object set differs from its sources")
    event_files = list(events.iterdir())
    if (any(p.is_symlink() or not p.is_file() for p in event_files) or
            {p.name for p in event_files} != {Path(name).stem + ".json" for name in sources}):
        raise ValueError("cache events do not cover exactly the built sources")
    counts = {"reused": 0, "compiled": 0}
    for name, source_hash in sources.items():
        source = dut / name
        obj = build / (source.stem + ".o")
        event = json.loads((events / (source.stem + ".json")).read_text())
        metadata = json.loads((build / (source.stem + ".json")).read_text())
        context = metadata["context"]
        if name == "dut_commit.cpp":
            optimization = "-O0"
        elif name == "dut_commit_chunks.cpp" or re.fullmatch(r"dut_commit_part\d+\.cpp", name):
            optimization = manifest["commit_opt"]
        else:
            optimization = manifest["shard_opt"]
        flags = manifest["base_flags"]
        if manifest["conservative_alias"] and optimization != "-O0":
            flags = flags[:-2] + ["-fno-strict-aliasing"] + flags[-2:]
        expected_context = {"compiler": plan["script"], "compiler_version": plan["compiler_version"],
                            "flags": flags, "header_sha256": plan["header_sha256"],
                            "environment": plan["environment"], "source_sha256": source_hash,
                            "optimization": optimization}
        with source.open("rb") as stream:
            if manifest["precompiled_header"] and stream.readline() == b'#include "dut.h"\n':
                expected_context["precompiled_header"] = True
        args = event.get("arguments", [])
        expected_args = flags[1:]
        if expected_context.get("precompiled_header"):
            index = len(expected_args)
            if args[index:index + 1] != ["-include-pch"] or len(args) <= index + 1:
                raise ValueError("missing precompiled header argument: " + name)
            pch = Path(args[index + 1]).resolve()
            if not pch.is_relative_to(build) or pch.name != optimization[1:] + ".pch":
                raise ValueError("unexpected precompiled header path: " + name)
            expected_args += ["-include-pch", str(pch)]
        expected_args += [optimization, "-c", str(source), "-o", str(build / (source.stem + ".o.tmp"))]
        if (event.get("version") != 1 or event.get("mode") not in counts or
                event.get("plan_sha256") != digest(plan_path) or event.get("source") != str(source) or
                event.get("source_sha256") != source_hash or generated_digest(source) != source_hash or
                event.get("header_sha256") != plan["header_sha256"] or event.get("compiler") != plan["compiler"] or
                event.get("compiler_sha256") != plan["compiler_sha256"] or
                context != expected_context or args != expected_args or
                event.get("object_sha256") != manifest["objects"][obj.name] or digest(obj) != event["object_sha256"] or
                metadata["output_sha256"] != event["object_sha256"] or context["compiler"] != plan["script"] or
                context["header_sha256"] != plan["header_sha256"] or context["source_sha256"] != source_hash):
            raise ValueError("built object or event differs: " + name)
        if event["mode"] == "reused":
            candidate = plan["candidates"].get(name)
            if (not candidate or event.get("cache") != candidate or
                    not candidate_matches(args, source, build / (source.stem + ".o.tmp"),
                                          plan, candidate, pch_must_exist=False) or
                    digest(Path(plan["old_dut"]) / name) != candidate["source_sha256"] or
                    digest(Path(candidate["metadata"])) != candidate["metadata_sha256"] or
                    digest(Path(candidate["object"])) != candidate["object_sha256"] or
                    event["object_sha256"] != candidate["object_sha256"]):
                raise ValueError("reused object provenance differs: " + name)
        counts[event["mode"]] += 1
    result = {"status": "pass", "scope": "audited model build only; no image or FPS qualification",
              "plan_sha256": digest(plan_path), "builder_sha256": digest(manifest_path),
              "executable_sha256": manifest["executable_sha256"], **counts}
    if digest(build / "native_product_driver") != result["executable_sha256"]:
        raise ValueError("built executable differs")
    write_new(witness, result)
    return result


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--compiler-adapter":
        raise SystemExit(compile_adapter(sys.argv[2:]))
    if len(sys.argv) > 1 and sys.argv[1] in ("prepare", "verify"):
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("operation", choices=("prepare", "verify"))
        parser.add_argument("--plan", type=Path, required=True)
        parser.add_argument("--dut-dir", type=Path)
        parser.add_argument("--build-dir", type=Path)
        parser.add_argument("--cache-build", type=Path)
        parser.add_argument("--events-dir", type=Path)
        parser.add_argument("--witness", type=Path)
        args = parser.parse_args()
        if args.operation == "prepare":
            if not all((args.dut_dir, args.build_dir, args.cache_build, args.events_dir)) or args.witness:
                parser.error("prepare needs DUT, build, cache and events paths")
            result = prepare(args.dut_dir, args.build_dir, args.cache_build, args.plan, args.events_dir)
            print("verified cache candidates:", len(result["candidates"]))
        else:
            if not args.witness or any((args.dut_dir, args.build_dir, args.cache_build, args.events_dir)):
                parser.error("verify needs only the plan and witness paths")
            print(json.dumps(verify(args.plan, args.witness), sort_keys=True))
        return
    raise SystemExit("use prepare or verify, or invoke cache_native_flashsim_cxx as the compiler")


if __name__ == "__main__":
    main()
