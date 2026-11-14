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
        return dict(schema="opengpu-framebuffer-blit-host-v2", resetCount=1, originalCallerInputs=True,
            originalLinuxTrace=False, independentBytes=True, vmAndReadOnlyGuards=True, full96Profile=True,
            unifiedResetCount=2, partialCancellationBytesChecked=True,
            sourceQualificationRequired=True, clockQualified=False, fpsQualified=False,
            lifecycle=dict(sourceFaultReads=1, sourceFaultRetirementCycles=200, healthyAfterFaultCycles=200,
                resets=[dict(heldChannel=c, heldResponseCycles=100, resetThroughRevocationCycles=200,
                             healthyIDReuseCycles=200) for c in ("R", "B")]),
            cases=[dict(inputIndex=n, coordinateInvalidateCycles=100, computeThroughRetirementCycles=200,
                        publicationInvalidateCycles=100) for n in range(len(gate.NAMES))])

    def test_full_result_keeps_functional_scope(self):
        gate.check_result(self.result())

    def test_actual_fault_and_both_reset_channels_are_required(self):
        for change in ("missing-fault", "zero-fault", "no-reuse", "missing-reset", "same-channel", "no-delay"):
            result = self.result()
            life = result["lifecycle"]
            if change == "missing-fault":
                del life["sourceFaultReads"]
            elif change == "zero-fault":
                life["sourceFaultReads"] = 0
            elif change == "no-reuse":
                life["healthyAfterFaultCycles"] = 0
            elif change == "missing-reset":
                life["resets"].pop()
            elif change == "same-channel":
                life["resets"][1]["heldChannel"] = "R"
            else:
                life["resets"][0]["heldResponseCycles"] = 0
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                gate.check_result(result)

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
                      "vmAndReadOnlyGuards", "full96Profile", "sourceQualificationRequired", "clockQualified", "fpsQualified",
                      "unifiedResetCount", "partialCancellationBytesChecked"):
            result = self.result()
            result[field] = result[field] + 1 if field in ("resetCount", "unifiedResetCount") else not result[field]
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                gate.check_result(result)

    def test_owner_root_cannot_fall_back_to_main_or_external_directory(self):
        for root in (gate.ROOT, gate.ROOT.parent):
            with self.subTest(root=root), self.assertRaises(RuntimeError):
                gate.load_owner(root)

    def test_actual_axi_agent_retains_held_responses_and_fault_identity(self):
        # Exercise the production memory agent and executor transport adapter.
        # The clock/port shell executes no RTL and cannot authorize a DUT PASS.
        source = r'''
#include "native_frame_axi.hpp"
#include <cassert>
namespace native_frame {
struct Simulation {
  struct Model {unsigned io_s_axi_aclk=0;} ports;
  Model *model=&ports;Requests request;Responses response;
  Simulation(int,char**){}
  void drive(const Responses &r){response=r;}
  Requests requests()const{return request;}
  void tick(){}
};
}
#include "native_framebuffer_blit_simulation.hpp"
int main() {
  native_frame::Memory memory;memory.ranges.push_back({0x1000,0x2000,0x2a});
  native_frame::Agent agent(memory);framebuffer_blit::Simulation sim(0,nullptr);
  auto step=[&](unsigned n){sim.step(agent,n);};
  sim.fault_begin=0x1000;sim.fault_end=0x1040;sim.hold_reads=true;
  sim.request.arvalid=true;sim.request.araddr=0x1000;sim.request.arid=7;
  sim.request.arlen=1;sim.request.arsize=5;sim.request.arburst=1;step(0);
  sim.request.arvalid=false;sim.request.rready=true;
  for(unsigned n=1;n<8;n++)step(n);
  assert(sim.held_read_cycles && !sim.response.rvalid && !agent.traffic.read_beats && !sim.drained(agent));
  sim.hold_reads=false;step(8);
  assert(sim.response.rvalid && sim.response.rid==7 && sim.response.rresp==2 && !sim.response.rlast);
  step(9);assert(sim.response.rvalid && sim.response.rid==7 && sim.response.rresp==2 && sim.response.rlast);
  assert(sim.drained(agent) && sim.fault_reads==1 && agent.traffic.read_beats==2);
  sim.fault_begin=sim.fault_end=0;sim.hold_writes=true;
  sim.request.awvalid=sim.request.wvalid=true;sim.request.awaddr=0x1040;sim.request.awid=9;
  sim.request.awlen=1;sim.request.awsize=5;sim.request.awburst=1;
  sim.request.wstrb=0xffffffff;sim.request.wdata.fill(0x55);step(10);
  sim.request.awvalid=false;sim.request.wlast=true;step(11);
  sim.request.wvalid=sim.request.wlast=false;sim.request.bready=true;
  for(unsigned n=12;n<23;n++)step(n);
  assert(sim.held_write_cycles && !sim.response.bvalid && !agent.traffic.acknowledgements && !sim.drained(agent));
  for(unsigned n=0;n<64;n++)assert(memory.byte(0x1040+n)==0x55);
  sim.hold_writes=false;step(23);
  assert(sim.response.bvalid && sim.response.bid==9 && !sim.response.bresp);
  assert(sim.drained(agent) && agent.traffic.acknowledgements==1);
  sim.request.arvalid=true;sim.request.araddr=0x1040;sim.request.arid=11;step(24);
  sim.request.arvalid=false;for(unsigned n=25;n<29;n++)step(n);
  assert(sim.drained(agent) && sim.fault_reads==1 && agent.traffic.read_beats==4 && !sim.response.rresp);
}
'''
        (gate.ROOT / ".artifacts").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="blit-transport-", dir=gate.ROOT / ".artifacts") as temporary:
            root = Path(temporary)
            (root / "test.cpp").write_text(source)
            subprocess.run(["c++", "-std=c++17", "-O1", "-Wall", "-Wextra", "-Werror",
                "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-I" + str(gate.ROOT / "scripts"),
                str(root / "test.cpp"), "-o", str(root / "test")], check=True)
            subprocess.run([str(root / "test")], check=True)

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
