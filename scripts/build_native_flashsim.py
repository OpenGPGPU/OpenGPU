#!/usr/bin/env python3
"""Build the native product driver against an already emitted FlashSim DUT."""
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
ADAPTER = ROOT / "scripts/flashsim_native"
ENVIRONMENT = ("SDKROOT", "CPATH", "CPLUS_INCLUDE_PATH", "MACOSX_DEPLOYMENT_TARGET")
INCLUDE = re.compile(rb"(?m)^[ \t]*#[ \t]*include\b[^\r\n]*")
GENERATED_STANDARD_HEADERS = ("cstdint", "cstdio", "cstring", "stdexcept", "string")


def compiler_version(executable):
    # GCC includes the invoked executable name in its version output.
    return subprocess.check_output([str(Path(executable).resolve()), "--version"], text=True)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def model_library_path(build, record):
    if record is None:
        return None
    if (not isinstance(record, dict) or not isinstance(record.get("name"), str)
            or not re.fullmatch(r"native_dut_[0-9a-f]{32}\.dylib", record["name"])
            or not isinstance(record.get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"])):
        raise ValueError("invalid native FlashSim model library identity")
    return build / record["name"]


def generated_digest(path, *, header=False):
    """Hash a self-contained FlashSim file and reject untracked includes."""
    value = hashlib.sha256()
    tail = b""
    allowed = {("<" + name + ">").encode() for name in GENERATED_STANDARD_HEADERS}
    if not header:
        allowed.add(b'"dut.h"')

    def check(data):
        for match in INCLUDE.finditer(data):
            directive = re.sub(rb"^[ \t]*#[ \t]*include[ \t]*", b"", match[0]).strip()
            directive = directive.split(b"//", 1)[0].strip()
            if directive not in allowed:
                raise ValueError(f"unsupported generated include in {path}: {directive!r}")

    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
            data = tail + block
            last = data.rfind(b"\n")
            if last < 0:
                tail = data
            else:
                check(data[:last + 1])
                tail = data[last + 1:]
    check(tail)
    return value.hexdigest()


def validate_driver_includes(paths, header, dut):
    """Keep the driver's recorded local dependencies complete and unambiguous."""
    known = {}
    for path in paths[1:]:
        if path.name in known and known[path.name] != path.resolve():
            raise ValueError(f"ambiguous driver dependency: {path.name}")
        known[path.name] = path.resolve()
    known["dut.h"] = header.resolve()
    search = (ADAPTER, ROOT / "scripts", dut)
    for path in paths:
        data = path.read_bytes()
        for match in INCLUDE.finditer(data):
            directive = re.sub(rb"^[ \t]*#[ \t]*include[ \t]*", b"", match[0]).strip()
            directive = directive.split(b"//", 1)[0].strip()
            parsed = re.fullmatch(rb'([<"])([^>"]+)[>"]', directive)
            if not parsed:
                raise ValueError(f"unsupported driver include in {path}: {directive!r}")
            name = os.fsdecode(parsed[2])
            if parsed[1] == b'"':
                expected = known.get(Path(name).name)
                candidates = (path.parent, *search)
                actual = next(((directory / name).resolve() for directory in candidates
                               if (directory / name).is_file()), None)
                if expected is None or actual != expected:
                    raise ValueError(f"unsupported or shadowed driver include in {path}: {name}")
            elif any((directory / name).exists() for directory in search):
                raise ValueError(f"local file shadows a standard driver include: {name}")


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


class BuildProgress:
    """Publish observations separately from build and cache identities."""

    def __init__(self, output):
        self.path = output / "native-flashsim-progress.json"
        self.lock = threading.Lock()
        self.started = time.monotonic()
        self.active = {}
        self.value = {"schema_version": 1, "pid": os.getpid(), "stage": "checking-inputs",
                      "total_shards": 0, "hashed_shards": 0, "compiled_shards": 0,
                      "cached_shards": 0}
        self.update()

    def _publish(self):
        now = time.monotonic()
        write_json(self.path, {**self.value,
                              "updated_at": datetime.now(timezone.utc).isoformat(),
                              "elapsed_seconds": now - self.started,
                              "active_shards": [
                                  {**value, "elapsed_seconds": now - started}
                                  for _, (started, value) in sorted(self.active.items())]})

    def update(self, **values):
        with self.lock:
            self.value.update(values)
            self._publish()

    def start_shard(self, source, pid, optimization):
        with self.lock:
            self.active[source.name] = (time.monotonic(), {
                "name": source.name, "pid": pid, "optimization": optimization})
            self._publish()

    def finish_shard(self, source, *, cached=False):
        with self.lock:
            self.active.pop(source.name, None)
            self.value["cached_shards" if cached else "compiled_shards"] += 1
            self._publish()


def build(dut, output, driver, compiler, jobs, shard_opt, commit_opt, conservative_alias,
          strict_flex_arrays, progress, precompiled_header=False, driver_dependencies=(),
          shard_cache=None, link_flags=(), shared_model=False):
    header = dut / "dut.h"
    sources = sorted(dut.glob("dut_*.cpp"), key=lambda path: path.stat().st_size, reverse=True)
    if not header.is_file() or not sources:
        raise ValueError("DUT directory needs dut.h and split dut_*.cpp files")
    if any((dut / name).exists() for name in GENERATED_STANDARD_HEADERS):
        raise ValueError("DUT directory shadows a required standard header")
    dependencies = [driver, ADAPTER / "VGpuHostSystemAxi.h", ADAPTER / "verilated.h",
                    ROOT / "scripts/native_host_simulation.hpp", ROOT / "scripts/native_execution_progress.hpp",
                    ROOT / "scripts/native_frame_axi.hpp", ROOT / "scripts/native_frame_fixture.hpp",
                    *driver_dependencies]
    if any(not path.is_file() for path in dependencies):
        raise ValueError("driver dependency does not exist")
    validate_driver_includes(dependencies, header, dut)
    driver_inputs = {str(path): digest(path) for path in dependencies}
    executable = shutil.which(compiler)
    if not executable:
        raise ValueError("C++ compiler not found: " + compiler)
    version = compiler_version(executable)
    flags = [compiler, "-std=gnu++17", "-fPIC", "-fPIE", "-w"]
    if "clang" in version.lower():
        flags += ["-Wno-parentheses-equality", "-fbracket-depth=4096"]
    elif precompiled_header:
        raise ValueError("precompiled headers require Clang")
    if precompiled_header:
        with header.open("rb") as stream:
            if stream.readline() != b"#pragma once\n":
                raise ValueError("precompiled headers require a guarded dut.h")
    if strict_flex_arrays:
        flags.append("-fstrict-flex-arrays=1")
    flags += ["-I", str(dut)]
    context = {"compiler": executable, "compiler_version": version, "flags": flags,
               "header_sha256": generated_digest(header, header=True),
               "environment": {key: os.environ.get(key, "") for key in ENVIRONMENT}}
    progress.update(stage="hashing-inputs", total_shards=len(sources))
    source_hashes = {}
    for source in sources:
        source_hashes[source] = generated_digest(source)
        progress.update(hashed_shards=len(source_hashes))
    progress.update(stage="compiling-shards")
    active = set()
    lock = threading.Lock()
    stopping = threading.Event()

    def optimization(source):
        if source.name == "dut_commit.cpp":
            return "-O0"
        if source.name == "dut_commit_chunks.cpp" or re.fullmatch(r"dut_commit_part\d+\.cpp", source.name):
            return commit_opt
        return shard_opt

    def source_uses_pch(source):
        if not precompiled_header:
            return False
        with source.open("rb") as stream:
            return stream.readline() == b'#include "dut.h"\n'

    pch_sources = {source: source_uses_pch(source) for source in sources}
    pch_dir = None

    def compile_context(source):
        opt = optimization(source)
        effective = (flags[:-2] + ["-fno-strict-aliasing"] + flags[-2:]
                     if conservative_alias and opt != "-O0" else flags)
        expected = {**context, "flags": effective, "source_sha256": source_hashes[source], "optimization": opt}
        if pch_sources[source]:
            expected["precompiled_header"] = True
        return opt, effective, expected

    def cached_object(directory, source, expected, pinned=None):
        obj = directory / (source.stem + ".o")
        metadata = directory / (source.stem + ".json")
        if any(path.is_symlink() or not path.is_file() for path in (obj, metadata)):
            return None
        try:
            prior = json.loads(metadata.read_text())
            if prior.get("context") != expected:
                return None
            saved = prior.get("output_sha256")
            if pinned is not None and pinned.get(obj.name) != saved:
                return None
            return obj if saved == digest(obj) else None
        except (OSError, ValueError, AttributeError):
            return None

    def reusable_object(source, expected):
        local = cached_object(output, source, expected)
        if local is not None or shard_cache is None:
            return local
        directory, pinned = shard_cache
        return cached_object(directory, source, expected, pinned)

    def compile_one(source):
        opt, effective, expected = compile_context(source)
        obj = output / (source.stem + ".o")
        metadata = output / (source.stem + ".json")
        temporary = output / (source.stem + ".o.tmp")
        cached = reusable_object(source, expected)
        if cached is not None:
            progress.finish_shard(source, cached=True)
            suffix = " from shared build" if cached.parent != output else ""
            print(source.name + ": cached" + suffix, flush=True)
            return cached
        if pch_sources[source] and (pch_dir is None or not (pch_dir / (opt[1:] + ".pch")).is_file()):
            raise RuntimeError("cached shard changed after precompiled-header planning: " + source.name)
        pch_flags = ["-include-pch", str(pch_dir / (opt[1:] + ".pch"))] if pch_sources[source] else []
        command = [*effective, *pch_flags, opt, "-c", str(source), "-o", str(temporary)]
        log = output / (source.stem + ".log")
        started = time.monotonic()
        with log.open("wb") as stream:
            with lock:
                if stopping.is_set():
                    raise RuntimeError("build stopped after another shard failed")
                proc = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                        start_new_session=True)
                active.add(proc)
                progress.start_shard(source, proc.pid, opt)
                print(f"{source.name}: compiling (pid {proc.pid}, {opt})", flush=True)
            code = proc.wait()
            with lock:
                active.discard(proc)
        if code:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"compile failed: {source.name}; see {log}")
        temporary.replace(obj)
        elapsed = time.monotonic() - started
        write_json(metadata, {"context": expected, "output_sha256": digest(obj),
                              "compile_elapsed_seconds": elapsed})
        progress.finish_shard(source)
        print(f"{source.name}: compiled ({elapsed:.3f}s)", flush=True)
        return obj

    def run_final(command, log, stage):
        with log.open("wb") as stream:
            proc = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            try:
                progress.update(stage=stage, child_pid=proc.pid)
                code = proc.wait()
            except BaseException:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                proc.wait()
                raise
        if code:
            raise RuntimeError(f"build command failed; see {log}")

    def compile_shards():
        progress.update(stage="compiling-shards", child_pid=None)
        remaining = iter(sources)
        objects = []
        with ThreadPoolExecutor(max_workers=jobs) as executor:
            tasks = {executor.submit(compile_one, source) for source in
                     [next(remaining, None) for _ in range(jobs)] if source is not None}
            try:
                while tasks:
                    done, tasks = wait(tasks, timeout=5, return_when=FIRST_COMPLETED)
                    progress.update()
                    objects.extend(task.result() for task in done)
                    for _ in done:
                        source = next(remaining, None)
                        if source is not None:
                            tasks.add(executor.submit(compile_one, source))
            except BaseException:
                for task in tasks:
                    task.cancel()
                with lock:
                    stopping.set()
                    for proc in active:
                        try:
                            os.killpg(proc.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                raise
        return objects

    # A verified object needs no compiler, so avoid reparsing a potentially
    # huge header for optimization levels whose shards are all cache hits.
    # compile_one rechecks each object before reuse; a concurrent cache change
    # cannot silently fall back to a missing or mismatched PCH.
    pch_opts = {optimization(source) for source in sources
                if pch_sources[source] and not reusable_object(source, compile_context(source)[2])}
    if pch_opts:
        with tempfile.TemporaryDirectory(prefix=".native-pch-", dir=output) as temporary_dir:
            pch_dir = Path(temporary_dir)
            for opt in sorted(pch_opts):
                effective = (flags[:-2] + ["-fno-strict-aliasing"] + flags[-2:]
                             if conservative_alias and opt != "-O0" else flags)
                run_final([*effective, opt, "-x", "c++-header", str(header),
                           "-o", str(pch_dir / (opt[1:] + ".pch"))],
                          output / ("native-pch-" + opt[1:] + ".log"), "compiling-pch")
            objects = compile_shards()
    else:
        objects = compile_shards()

    objects.sort(key=lambda path: path.name)
    driver_obj = output / "native-driver.o"
    driver_tmp = output / "native-driver.o.tmp"
    # The adapter must precede the DUT include directory so a colocated
    # Verilator header cannot accidentally select a different backend.
    driver_flags = flags[:-2] + ["-I", str(ADAPTER), "-I", str(ROOT / "scripts"), *flags[-2:]]
    result = output / "native_product_driver"
    temporary = output / "native_product_driver.tmp"
    receipt = output / "native-flashsim-build.json"
    previous_library = None
    try:
        previous = json.loads(receipt.read_text())
        if isinstance(previous, dict):
            previous_library = model_library_path(output, previous.get("model_library"))
    except (OSError, ValueError):
        # Unrecognized output has no builder-owned library eligible for cleanup.
        pass
    library = None
    library_identity = None
    try:
        run_final([*driver_flags, "-O1", "-c", str(driver), "-o", str(driver_tmp)],
                  output / "native-driver.log", "compiling-driver")
        driver_tmp.replace(driver_obj)
        link_inputs = objects
        if shared_model:
            library = output / f"native_dut_{uuid.uuid4().hex}.dylib"
            install_name = f"-Wl,-install_name,@loader_path/{library.name}"
            run_final([compiler, "-dynamiclib", install_name, *map(str, objects), "-pthread",
                       *link_flags, "-o", str(library)], output / "native-model-link.log", "linking-model")
            library_identity = {"name": library.name, "sha256": digest(library)}
            link_inputs = [library]
        run_final([compiler, str(driver_obj), *map(str, link_inputs), "-pthread", *link_flags,
                   "-o", str(temporary)], output / "native-link.log", "linking")
        progress.update(stage="checking-final-inputs", child_pid=None)
        if (digest(header) != context["header_sha256"] or
                any(digest(path) != value for path, value in source_hashes.items()) or
                any(digest(Path(path)) != value for path, value in driver_inputs.items())):
            raise RuntimeError("DUT or driver input changed during the build")
        object_hashes = {path.name: digest(path) for path in objects}
        if shard_cache is not None:
            cache_dir, pinned = shard_cache
            if any(object_hashes[path.name] != pinned[path.name]
                   for path in objects if path.parent == cache_dir):
                raise RuntimeError("shared shard changed during the build")
        executable_digest = digest(temporary)
    except BaseException:
        if library is not None:
            library.unlink(missing_ok=True)
        temporary.unlink(missing_ok=True)
        raise
    finally:
        driver_tmp.unlink(missing_ok=True)
    try:
        temporary.replace(result)
    except OSError:
        if library is not None:
            library.unlink(missing_ok=True)
        temporary.unlink(missing_ok=True)
        raise
    write_json(receipt, {
        "executable_sha256": executable_digest, "dut_header_sha256": context["header_sha256"],
        "dut_sources": {path.name: value for path, value in source_hashes.items()},
        "objects": object_hashes, "model_library": library_identity,
        "driver_source": str(driver), "driver_inputs": driver_inputs,
        "compiler": executable, "compiler_version": version, "base_flags": flags,
        "driver_flags": driver_flags + ["-O1"], "link_flags": list(link_flags),
        "environment": context["environment"],
        "shard_opt": shard_opt,
        "commit_opt": commit_opt, "conservative_alias": conservative_alias,
        "strict_flex_arrays": strict_flex_arrays, "precompiled_header": precompiled_header,
        "scope": "Native FlashSim build identity; assertion coverage and product qualification require separate validation"})
    if previous_library is not None and previous_library != library:
        previous_library.unlink(missing_ok=True)
    progress.update(stage="built", child_pid=None)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dut-dir", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--driver", type=Path, default=ROOT / "scripts/native_product_driver.cpp")
    parser.add_argument("--driver-dependency", type=Path, action="append", default=[],
                        help="explicit extra quoted include; recorded and checked before/after build")
    parser.add_argument("--shard-cache", type=Path,
                        help="read-only build directory and pinned object hashes in a JSON file")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--link-flag", action="append", default=[],
                        help="extra compiler-driver flag for linking only (repeatable)")
    parser.add_argument("--shared-model", action="store_true",
                        help="link the Darwin DUT shards as a library next to the executor")
    parser.add_argument("--jobs", type=int, default=1)
    parser.add_argument("--shard-opt", choices=("-O0", "-O1", "-O2", "-O3"), default="-O2")
    parser.add_argument("--commit-opt", choices=("-O0", "-O1", "-O2", "-O3"), default="-O0")
    parser.add_argument("--conservative-alias", action="store_true")
    parser.add_argument("--strict-flex-arrays", action="store_true",
                        help="use -fstrict-flex-arrays=1 with a supporting compiler")
    parser.add_argument("--precompiled-header", action="store_true",
                        help="compile guarded DUT shards with Clang precompiled headers")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    if args.shared_model and sys.platform != "darwin":
        parser.error("--shared-model requires Darwin")
    dut, output, driver = args.dut_dir.resolve(), args.build_dir.resolve(), args.driver.resolve()
    if not driver.is_file():
        parser.error("driver source does not exist")
    shard_cache = None
    if args.shard_cache is not None:
        record = json.loads(args.shard_cache.read_text())
        cache_dir = Path(record["directory"]).resolve(strict=True)
        cache_lock = cache_dir / ".native-flashsim-build.lock"
        if (cache_dir == output or not cache_lock.is_file() or cache_lock.is_symlink()
                or type(record["objects"]) is not dict):
            parser.error("--shard-cache requires a distinct existing build directory and object hashes")
        shard_cache = cache_dir, record["objects"]
    output.mkdir(parents=True, exist_ok=True)
    def terminate(_signum, _frame):
        raise KeyboardInterrupt("build terminated")

    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        with ExitStack() as stack:
            lock = stack.enter_context((output / ".native-flashsim-build.lock").open("w"))
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if shard_cache is not None:
                read_lock = stack.enter_context(cache_lock.open("rb"))
                fcntl.flock(read_lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
            progress = BuildProgress(output)
            try:
                result = build(dut, output, driver, args.cxx, args.jobs, args.shard_opt,
                               args.commit_opt, args.conservative_alias, args.strict_flex_arrays,
                               progress, args.precompiled_header,
                               tuple(path.resolve() for path in args.driver_dependency), shard_cache,
                               link_flags=args.link_flag, shared_model=args.shared_model)
            except BaseException as exc:
                with progress.lock:
                    progress.active.clear()
                progress.update(stage="interrupted" if isinstance(exc, KeyboardInterrupt) else "failed",
                                child_pid=None, error=str(exc))
                raise
    finally:
        signal.signal(signal.SIGTERM, previous)
    print("built " + str(result), flush=True)


if __name__ == "__main__":
    main()
