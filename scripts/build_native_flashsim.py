#!/usr/bin/env python3
"""Build the native product driver against an already emitted FlashSim DUT."""
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import threading


ROOT = Path(__file__).resolve().parents[1]
ADAPTER = ROOT / "scripts/flashsim_native"
ENVIRONMENT = ("SDKROOT", "CPATH", "CPLUS_INCLUDE_PATH", "MACOSX_DEPLOYMENT_TARGET")
INCLUDE = re.compile(rb"(?m)^[ \t]*#[ \t]*include\b[^\r\n]*")
GENERATED_STANDARD_HEADERS = ("cstdint", "cstring", "stdexcept", "string")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


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
    known = {path.name: path.resolve() for path in paths[1:]}
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
                expected = known.get(name)
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


def build(dut, output, driver, compiler, jobs, shard_opt, commit_opt, conservative_alias,
          strict_flex_arrays):
    header = dut / "dut.h"
    sources = sorted(dut.glob("dut_*.cpp"), key=lambda path: path.stat().st_size, reverse=True)
    if not header.is_file() or not sources:
        raise ValueError("DUT directory needs dut.h and split dut_*.cpp files")
    if any((dut / name).exists() for name in GENERATED_STANDARD_HEADERS):
        raise ValueError("DUT directory shadows a required standard header")
    executable = shutil.which(compiler)
    if not executable:
        raise ValueError("C++ compiler not found: " + compiler)
    version = subprocess.check_output([compiler, "--version"], text=True)
    flags = [compiler, "-std=gnu++17", "-fPIC", "-fPIE", "-w"]
    if "clang" in version.lower():
        flags += ["-Wno-parentheses-equality", "-fbracket-depth=4096"]
    if strict_flex_arrays:
        flags.append("-fstrict-flex-arrays=1")
    flags += ["-I", str(dut)]
    context = {"compiler": executable, "compiler_version": version, "flags": flags,
               "header_sha256": generated_digest(header, header=True),
               "environment": {key: os.environ.get(key, "") for key in ENVIRONMENT}}
    source_hashes = {source: generated_digest(source) for source in sources}
    active = set()
    lock = threading.Lock()
    stopping = threading.Event()

    def optimization(source):
        if source.name == "dut_commit.cpp":
            return "-O0"
        if source.name == "dut_commit_chunks.cpp" or re.fullmatch(r"dut_commit_part\d+\.cpp", source.name):
            return commit_opt
        return shard_opt

    def compile_one(source):
        opt = optimization(source)
        effective = (flags[:-2] + ["-fno-strict-aliasing"] + flags[-2:]
                     if conservative_alias and opt != "-O0" else flags)
        expected = {**context, "flags": effective, "source_sha256": source_hashes[source], "optimization": opt}
        obj = output / (source.stem + ".o")
        metadata = output / (source.stem + ".json")
        temporary = output / (source.stem + ".o.tmp")
        if obj.is_file() and metadata.is_file():
            try:
                prior = json.loads(metadata.read_text())
                if prior.get("context") == expected and prior.get("output_sha256") == digest(obj):
                    print(source.name + ": cached", flush=True)
                    return obj
            except (OSError, ValueError):
                pass
        command = [*effective, opt, "-c", str(source), "-o", str(temporary)]
        log = output / (source.stem + ".log")
        with log.open("wb") as stream:
            with lock:
                if stopping.is_set():
                    raise RuntimeError("build stopped after another shard failed")
                proc = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                        start_new_session=True)
                active.add(proc)
            code = proc.wait()
            with lock:
                active.discard(proc)
        if code:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"compile failed: {source.name}; see {log}")
        temporary.replace(obj)
        write_json(metadata, {"context": expected, "output_sha256": digest(obj)})
        print(source.name + ": compiled", flush=True)
        return obj

    remaining = iter(sources)
    objects = []
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        tasks = {executor.submit(compile_one, source) for source in
                 [next(remaining, None) for _ in range(jobs)] if source is not None}
        try:
            while tasks:
                done, tasks = wait(tasks, return_when=FIRST_COMPLETED)
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

    def run_final(command, log):
        with log.open("wb") as stream:
            proc = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            try:
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

    driver_obj = output / "native-driver.o"
    driver_tmp = output / "native-driver.o.tmp"
    # The adapter must precede the DUT include directory so a colocated
    # Verilator header cannot accidentally select a different backend.
    driver_flags = flags[:-2] + ["-I", str(ADAPTER), "-I", str(ROOT / "scripts"), *flags[-2:]]
    result = output / "native_product_driver"
    temporary = output / "native_product_driver.tmp"
    dependencies = [driver, ADAPTER / "VGpuHostSystemAxi.h", ADAPTER / "verilated.h",
                    ROOT / "scripts/native_host_simulation.hpp", ROOT / "scripts/native_execution_progress.hpp",
                    ROOT / "scripts/native_frame_axi.hpp", ROOT / "scripts/native_frame_fixture.hpp"]
    validate_driver_includes(dependencies, header, dut)
    driver_inputs = {str(path): digest(path) for path in dependencies}
    try:
        run_final([*driver_flags, "-O1", "-c", str(driver), "-o", str(driver_tmp)],
                  output / "native-driver.log")
        driver_tmp.replace(driver_obj)
        run_final([compiler, str(driver_obj), *map(str, sorted(objects)), "-pthread",
                   "-o", str(temporary)], output / "native-link.log")
        if (digest(header) != context["header_sha256"] or
                any(digest(path) != value for path, value in source_hashes.items()) or
                any(digest(Path(path)) != value for path, value in driver_inputs.items())):
            raise RuntimeError("DUT or driver input changed during the build")
        temporary.replace(result)
    finally:
        driver_tmp.unlink(missing_ok=True)
        temporary.unlink(missing_ok=True)
    write_json(output / "native-flashsim-build.json", {
        "executable_sha256": digest(result), "dut_header_sha256": context["header_sha256"],
        "dut_sources": {path.name: value for path, value in source_hashes.items()},
        "objects": {path.name: digest(path) for path in sorted(objects)},
        "driver_inputs": driver_inputs,
        "compiler": executable, "compiler_version": version, "base_flags": flags,
        "driver_flags": driver_flags + ["-O1"], "environment": context["environment"],
        "shard_opt": shard_opt,
        "commit_opt": commit_opt, "conservative_alias": conservative_alias,
        "strict_flex_arrays": strict_flex_arrays,
        "scope": "FlashSim screen; verification assertions are not included"})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dut-dir", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--driver", type=Path, default=ROOT / "scripts/native_product_driver.cpp")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--jobs", type=int, default=1)
    parser.add_argument("--shard-opt", choices=("-O0", "-O1", "-O2", "-O3"), default="-O2")
    parser.add_argument("--commit-opt", choices=("-O0", "-O1", "-O2", "-O3"), default="-O0")
    parser.add_argument("--conservative-alias", action="store_true")
    parser.add_argument("--strict-flex-arrays", action="store_true",
                        help="use -fstrict-flex-arrays=1 with a supporting compiler")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    dut, output, driver = args.dut_dir.resolve(), args.build_dir.resolve(), args.driver.resolve()
    if not driver.is_file():
        parser.error("driver source does not exist")
    output.mkdir(parents=True, exist_ok=True)
    def terminate(_signum, _frame):
        raise KeyboardInterrupt("build terminated")

    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        with (output / ".native-flashsim-build.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = build(dut, output, driver, args.cxx, args.jobs, args.shard_opt,
                           args.commit_opt, args.conservative_alias, args.strict_flex_arrays)
    finally:
        signal.signal(signal.SIGTERM, previous)
    print("built " + str(result), flush=True)


if __name__ == "__main__":
    main()
