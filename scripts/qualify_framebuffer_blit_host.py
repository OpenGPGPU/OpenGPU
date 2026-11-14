#!/usr/bin/env python3
"""Check/build/run original blit inputs on a separately pinned complete owner DUT.

Reusing that frozen DUT is functional diagnosis, never final H6 qualification.
No preparation/generation receipt is edited and no RTL is emitted here.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from framebuffer_blit_fixture import checked_inputs, NAMES

ROOT = Path(__file__).resolve().parents[1]
OWN = ("scripts/native_framebuffer_blit_driver.cpp", "scripts/native_framebuffer_blit_lease.hpp",
       "scripts/native_framebuffer_blit_input.hpp", "scripts/native_framebuffer_blit_simulation.hpp",
       "driver/opengpu_storage_command_abi.h")
TOOLS = ("scripts/qualify_framebuffer_blit_host.py", "scripts/framebuffer_blit_fixture.py")
OWNER_CHECKERS = ("scripts/prepare_owner_executor.py", "scripts/build_native_flashsim.py",
    "scripts/prepare_native_owner_flashsim.py", "scripts/qualify_native_product_flashsim.py",
    "scripts/qualify_native_product_compiled.py")


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def compiler_identity():
    selected = shutil.which("c++")
    require(selected, "blit executor requires c++")
    path = Path(selected).resolve(strict=True)
    # The builder invokes this spelling. GCC's version banner can depend on
    # argv[0], so hash its real binary while preserving the actual invocation.
    return dict(path=str(path), sha256=sha(path),
                version=subprocess.check_output(["c++", "--version"], text=True))


def load_owner(root):
    root = Path(root).resolve(strict=True)
    common = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                         cwd=ROOT, text=True).strip())
    require(root.is_relative_to(common.parent / ".artifacts/worktrees"), "owner root must be GPU-internal")
    require(Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                        cwd=root, text=True).strip()) == common, "owner root belongs to another repository")
    require(Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], cwd=root, text=True).strip()) == root,
            "owner root must identify a complete checkout")
    require((root / "scripts/prepare_owner_executor.py").is_file(), "selected source lacks the complete owner checker")
    before = {str(root / name): sha(root / name) for name in OWNER_CHECKERS}
    sys.path.insert(0, str(root / "scripts"))
    spec = importlib.util.spec_from_file_location("blit_selected_owner", root / "scripts/prepare_owner_executor.py")
    owner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(owner)
    for module in (owner, owner.owner, owner.builder, owner.flash, owner.common):
        require(module.ROOT == root and Path(module.__file__).resolve().parent == root / "scripts",
                "owner checker module belongs to another source checkout")
    require(tuple(owner.CHECKERS) == OWNER_CHECKERS and before == {name: sha(name) for name in before},
            "owner checker closure changed during import")
    owner._blit_import_hashes = before
    return owner


def source_identity(owner):
    require(owner._blit_import_hashes == {name: sha(name) for name in owner._blit_import_hashes},
            "loaded owner checker source changed")
    own = {str(ROOT / name): sha(ROOT / name) for name in (*OWN, *TOOLS)}
    dependencies = [owner.ROOT / name for name in (*owner.DEFAULT_DEPENDENCIES, *owner.SHARED)]
    sources = {str(path): sha(path) for path in dependencies}
    sources.update({str(owner.ROOT / name): sha(owner.ROOT / name) for name in owner.CHECKERS})
    return {**own, **sources}


def check_build(record, owner, model, sources, options, compiler):
    """Exact independent kind identity; never label this a compute-owner replay."""
    flags = ["c++", "-std=gnu++17", "-fPIC", "-fPIE", "-w"]
    if "clang" in compiler["version"].lower():
        flags += ["-Wno-parentheses-equality", "-fbracket-depth=4096"]
    flags += ["-I", model["dut_directory"]]
    driver_flags = flags[:-2] + ["-I", str(owner.ROOT / "scripts/flashsim_native"),
                                "-I", str(owner.ROOT / "scripts"), *flags[-2:], "-O1"]
    dependencies = [ROOT / name for name in OWN] + [owner.ROOT / name for name in
        (*owner.DEFAULT_DEPENDENCIES, *owner.SHARED)]
    expected = dict(dut_header_sha256=model["generated"]["files"]["dut.h"],
        dut_sources={name: value for name, value in model["generated"]["files"].items() if name != "dut.h"},
        driver_inputs={str(path): sources[str(path)] for path in dependencies},
        compiler_version=compiler["version"], base_flags=flags,
        driver_flags=driver_flags, environment={key: os.environ.get(key, "") for key in owner.builder.ENVIRONMENT},
        **options)
    require(all(type(record.get(k)) is type(v) and record[k] == v for k, v in expected.items()),
            "blit builder differs from original model, driver closure, compiler or options")
    require(type(record.get("compiler")) is str and str(Path(record["compiler"]).resolve(strict=True)) == compiler["path"],
            "blit builder selected another compiler")
    objects = record.get("objects")
    require(type(objects) is dict and set(objects) == {Path(name).stem + ".o" for name in expected["dut_sources"]}
            and all(type(value) is str and re.fullmatch("[a-f0-9]{64}", value) for value in objects.values()),
            "blit builder lacks the complete original shard set")


def child(command, temporary, cwd):
    process = subprocess.Popen(command, cwd=cwd, start_new_session=True,
        env=dict(os.environ, TMPDIR=str(temporary), XDG_CACHE_HOME=str(temporary / "cache"),
                 CCACHE_DIR=str(temporary / "ccache"), PYTHONDONTWRITEBYTECODE="1"))
    try:
        require(process.wait() == 0, "blit build/execution failed")
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait()


def terminate(_signal, _frame):
    raise KeyboardInterrupt("blit qualification terminated")


def check_result(result):
    required = dict(schema="opengpu-framebuffer-blit-host-v2", resetCount=1, originalCallerInputs=True,
        originalLinuxTrace=False, independentBytes=True, vmAndReadOnlyGuards=True, full96Profile=True,
        unifiedResetCount=2, partialCancellationBytesChecked=True,
        sourceQualificationRequired=True, clockQualified=False, fpsQualified=False)
    require(all(type(result.get(k)) is type(v) and result[k] == v for k, v in required.items()) and
        type(result.get("cases")) is list and len(result["cases"]) == len(NAMES), "incomplete blit host result")
    require(not any(k in result for k in ('full112Profile', 'executorKind', 'profile',
        'ownerResourceVersion', 'ownerResourceStage', 'resourceDiscoveryWords', 'computeFeatureFlags')),
        'frozen owner blit cannot carry a different factory identity')
    check_observations(result)


def check_observations(result):
    """Shared observed cycles/fault/reset census; authorizes no factory/schema."""
    require(type(result.get('cases')) is list and len(result['cases']) == len(NAMES),
            'blit requires all 43 original caller observations')
    for n, case in enumerate(result["cases"]):
        require(type(case) is dict and set(case) == {"inputIndex", "coordinateInvalidateCycles", "computeThroughRetirementCycles",
            "publicationInvalidateCycles"} and type(case["inputIndex"]) is int and case["inputIndex"] == n and
            all(type(case[k]) is int and 0 < case[k] <= 10000000 for k in case if k != "inputIndex"),
            "blit host result lacks complete retirement cycle observations")
    life = result.get("lifecycle")
    require(type(life) is dict and set(life) == {"sourceFaultReads", "sourceFaultRetirementCycles",
        "healthyAfterFaultCycles", "resets"} and
        all(type(life.get(k)) is int and 0 < life[k] <= 10000000 for k in life if k != "resets") and
        type(life.get("resets")) is list and len(life["resets"]) == 2,
        "blit host result lacks actual source fault and healthy reuse")
    for channel, reset in zip(("R", "B"), life["resets"]):
        require(type(reset) is dict and set(reset) == {"heldChannel", "heldResponseCycles",
            "resetThroughRevocationCycles", "healthyIDReuseCycles"} and reset["heldChannel"] == channel and
            all(type(reset[k]) is int and 0 < reset[k] <= 10000000 for k in reset if k != "heldChannel"),
            "blit host result lacks delayed response reset/drain/healthy ID reuse")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("build", "check", "run"), required=True)
    parser.add_argument("--owner-root", type=Path, required=True)
    parser.add_argument("--owner-output", type=Path, required=True)
    parser.add_argument("--flashsim-dir", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--input-manifest-sha256", required=True)
    parser.add_argument("--shards", type=int, default=32)
    parser.add_argument("--shard-opt", choices=("-O0", "-O1", "-O2", "-O3"), default="-O2")
    parser.add_argument("--precompiled-header", action="store_true")
    parser.add_argument("--conservative-alias", action="store_true")
    args = parser.parse_args()
    owner = load_owner(args.owner_root)
    output = args.owner_output.absolute()
    require(output == output.resolve(strict=True) and output.is_relative_to(owner.ROOT / ".artifacts"),
            "owner output is aliased or belongs to another source checkout")
    gpu_artifacts = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                                cwd=ROOT, text=True).strip()).parent / ".artifacts"
    require(args.inputs.resolve(strict=True).is_relative_to(gpu_artifacts), "blit inputs must be GPU-internal")
    checked_inputs(args.inputs, ROOT, args.input_manifest_sha256)
    options = dict(shard_opt=args.shard_opt, commit_opt="-O0", strict_flex_arrays=False,
                   precompiled_header=args.precompiled_header, conservative_alias=args.conservative_alias)
    compiler = compiler_identity()
    (ROOT / ".artifacts").mkdir(exist_ok=True)
    # The same lock protects the shared shard cache and its current executable.
    with (output / ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        model = owner.model_identity(output, args.flashsim_dir.resolve(strict=True), args.shards)
        sources = source_identity(owner)
        build = output / "flashsim-build"
        require(build.resolve() == build, "owner build cache is aliased")
        with tempfile.TemporaryDirectory(prefix="blit-exec-", dir=ROOT / ".artifacts") as directory:
            temporary = Path(directory)
            if args.stage == "build":
                command = [sys.executable, "-B", str(owner.ROOT / "scripts/build_native_flashsim.py"),
                    "--dut-dir", model["dut_directory"], "--build-dir", str(build),
                    "--driver", str(ROOT / OWN[0]), "--jobs", "1", "--cxx", "c++", "--shard-opt=" + args.shard_opt]
                for path in ([ROOT / name for name in OWN[1:]] + [owner.ROOT / name for name in owner.SHARED]):
                    command += ["--driver-dependency", str(path)]
                if args.precompiled_header:
                    command += ["--precompiled-header"]
                if args.conservative_alias:
                    command += ["--conservative-alias"]
                child(command, temporary, owner.ROOT)
            snapshot = build / "native-flashsim-build.json"
            executable = build / "native_product_driver"
            require(snapshot.is_file() and executable.is_file() and not snapshot.is_symlink() and
                    not executable.is_symlink() and os.access(executable, os.X_OK), "blit build is missing or aliased")
            record = json.loads(snapshot.read_text())
            check_build(record, owner, model, sources, options, compiler)
            binary_hash, record_hash = sha(executable), sha(snapshot)
            require(binary_hash == record.get("executable_sha256"), "blit binary differs from its original build")
            if args.stage == "run":
                result_path = temporary / "result.json"
                child([str(executable), str(result_path), *(str(args.inputs / (name + ".blit")) for name in NAMES)],
                      temporary, ROOT)
                result = json.loads(result_path.read_text())
                check_result(result)
            require(model == owner.model_identity(output, args.flashsim_dir.resolve(strict=True), args.shards) and
                sources == source_identity(owner) and compiler == compiler_identity() and
                binary_hash == sha(executable) and record_hash == sha(snapshot), "blit source/tool/model/build changed")
            checked_inputs(args.inputs, ROOT, args.input_manifest_sha256)
    print("BLIT_HOST_" + args.stage.upper() + "_PASS" +
          (f" cases={len(NAMES)} frozenOwnerFunctionalDiagnosis=true" if args.stage == "run" else " gpuExecution=false") +
          " finalProductQualification=false clockQualified=false fpsQualified=false")


if __name__ == "__main__":
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        main()
    finally:
        signal.signal(signal.SIGTERM, previous)
