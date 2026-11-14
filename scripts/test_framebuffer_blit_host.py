#!/usr/bin/env python3
"""CPU rejection tests for blit model/build/result identity; no DUT executes."""
import copy
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import types
import unittest

import qualify_framebuffer_blit_host as gate


class HostGateTests(unittest.TestCase):
    def setUp(self):
        self.owner = types.SimpleNamespace(ROOT=Path("/owner"), DEFAULT_DEPENDENCIES=("scripts/adapter.h",),
            SHARED=("scripts/runtime.h",), builder=types.SimpleNamespace(ENVIRONMENT=("SDKROOT",)))
        self.model = dict(dut_directory="/owner/.artifacts/dut", generated=dict(files={"dut.h": "1" * 64,
            "dut_0.cpp": "2" * 64, "dut_commit.cpp": "3" * 64}))
        self.compiler = dict(path=str(Path(shutil.which("c++")).resolve(strict=True)), version="Clang fixture")
        self.options = dict(shard_opt="-O2", commit_opt="-O0", strict_flex_arrays=False,
                            precompiled_header=True, conservative_alias=True)
        paths = [gate.ROOT / name for name in gate.OWN] + [self.owner.ROOT / name for name in
            (*self.owner.DEFAULT_DEPENDENCIES, *self.owner.SHARED)]
        self.sources = {str(path): "4" * 64 for path in paths}
        flags = ["c++", "-std=gnu++17", "-fPIC", "-fPIE", "-w", "-Wno-parentheses-equality", "-fbracket-depth=4096"]
        self.record = dict(self.options, dut_header_sha256="1" * 64,
            dut_sources={"dut_0.cpp": "2" * 64, "dut_commit.cpp": "3" * 64}, driver_inputs=self.sources.copy(),
            compiler=shutil.which("c++"), compiler_version=self.compiler["version"],
            base_flags=flags + ["-I", self.model["dut_directory"]],
            driver_flags=flags + ["-I", "/owner/scripts/flashsim_native", "-I", "/owner/scripts",
                "-I", self.model["dut_directory"], "-O1"], environment={"SDKROOT": os.environ.get("SDKROOT", "")},
            objects={"dut_0.o": "5" * 64, "dut_commit.o": "6" * 64})

    def check(self, record):
        gate.check_build(record, self.owner, self.model, self.sources, self.options, self.compiler)

    def test_separate_model_and_driver_roots_are_accepted(self):
        self.check(self.record)

    def test_wrong_model_or_incomplete_shards_are_rejected(self):
        for field in ("dut_header_sha256", "dut_sources", "objects"):
            record = copy.deepcopy(self.record)
            record[field] = "0" * 64 if field.endswith("sha256") else {}
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.check(record)

    def test_wrong_driver_root_or_source_is_rejected(self):
        for change in ("missing", "changed", "wrong-root"):
            record = copy.deepcopy(self.record)
            name = next(iter(record["driver_inputs"]))
            if change == "changed":
                record["driver_inputs"][name] = "0" * 64
            else:
                value = record["driver_inputs"].pop(name)
                if change == "wrong-root":
                    record["driver_inputs"]["/owner/scripts/native_framebuffer_blit_driver.cpp"] = value
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self.check(record)

    def test_flags_compiler_and_environment_are_strict(self):
        for field, value in (("shard_opt", "-O0"), ("commit_opt", "-O2"), ("precompiled_header", 1),
                             ("driver_flags", []), ("base_flags", []), ("compiler", "/missing/compiler"),
                             ("compiler_version", "GCC fixture"), ("environment", {})):
            record = copy.deepcopy(self.record)
            record[field] = value
            with self.subTest(field=field), self.assertRaises((RuntimeError, FileNotFoundError)):
                self.check(record)

    @staticmethod
    def result():
        return dict(schema="opengpu-framebuffer-blit-host-v1", resetCount=1, originalCallerInputs=True,
            originalLinuxTrace=False, independentBytes=True, vmAndReadOnlyGuards=True, full96Profile=True,
            sourceQualificationRequired=True, clockQualified=False, fpsQualified=False,
            cases=[dict(inputIndex=n, coordinateInvalidateCycles=100, computeThroughRetirementCycles=200,
                        publicationInvalidateCycles=100) for n in range(37)])

    def test_full_result_keeps_functional_scope(self):
        gate.check_result(self.result())

    def test_incomplete_or_reordered_result_is_rejected(self):
        for change in ("missing", "reordered", "duplicate", "no-publication", "boolean-cycle", "over-budget"):
            result = self.result()
            if change == "missing":
                result["cases"].pop()
            elif change == "reordered":
                result["cases"].reverse()
            elif change == "duplicate":
                result["cases"][1]["inputIndex"] = 0
            elif change == "no-publication":
                del result["cases"][0]["publicationInvalidateCycles"]
            else:
                result["cases"][0]["computeThroughRetirementCycles"] = True if change == "boolean-cycle" else 10000001
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                gate.check_result(result)

    def test_gpu_clock_and_linux_claims_cannot_be_promoted(self):
        for field in ("resetCount", "originalCallerInputs", "originalLinuxTrace", "independentBytes",
                      "vmAndReadOnlyGuards", "full96Profile", "sourceQualificationRequired", "clockQualified", "fpsQualified"):
            result = self.result()
            result[field] = 2 if field == "resetCount" else not result[field]
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                gate.check_result(result)

    def test_owner_root_cannot_fall_back_to_main_or_external_directory(self):
        for root in (gate.ROOT, gate.ROOT.parent):
            with self.subTest(root=root), self.assertRaises(RuntimeError):
                gate.load_owner(root)

    def test_interrupt_joins_owned_process_group(self):
        (gate.ROOT / ".artifacts").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="blit-signal-", dir=gate.ROOT / ".artifacts") as temporary:
            root = Path(temporary)
            script = '''import os,signal,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import qualify_framebuffer_blit_host as gate
signal.signal(signal.SIGTERM,gate.terminate)
try:
    gate.child([sys.executable,'-B','-c',
        "import os,time;from pathlib import Path;Path('owned.pid').write_text(str(os.getpid()));time.sleep(30)"],
        Path(sys.argv[2]),Path(sys.argv[2]))
except KeyboardInterrupt:
    pass
'''
            process = subprocess.Popen([sys.executable, "-B", "-c", script, str(gate.ROOT / "scripts"), temporary],
                cwd=root, env=dict(os.environ, TMPDIR=temporary), start_new_session=True)
            try:
                deadline = time.monotonic() + 5
                while not (root / "owned.pid").is_file():
                    self.assertIsNone(process.poll())
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(0.01)
                pid = int((root / "owned.pid").read_text())
                process.send_signal(signal.SIGTERM)
                self.assertEqual(process.wait(timeout=5), 0)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        if (root / "owned.pid").is_file():
                            try:
                                os.killpg(int((root / "owned.pid").read_text()), signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                        process.kill()
                        process.wait()


if __name__ == "__main__":
    unittest.main()
