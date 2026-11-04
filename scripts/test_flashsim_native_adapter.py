"""The native driver adapter packs AXI words and ticks only on rising edges."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


def check_flashsim_native_adapter_clock_and_axi_words(tmp_path, *, assertions=False):
    compiler = shutil.which("c++")
    if not compiler:
        raise unittest.SkipTest("C++ compiler unavailable")
    adapter = Path(__file__).resolve().parent / "flashsim_native"
    methods = """eval_io_s_axi_awready eval_io_s_axi_wready eval_io_s_axi_bvalid
eval_io_s_axi_bresp eval_io_s_axi_arready eval_io_s_axi_rvalid eval_io_s_axi_rresp
eval_io_s_axi_rdata eval_io_m_axi_arvalid eval_io_m_axi_araddr eval_io_m_axi_arid
eval_io_m_axi_arlen eval_io_m_axi_arsize eval_io_m_axi_arburst eval_io_m_axi_awvalid
eval_io_m_axi_awaddr eval_io_m_axi_awid eval_io_m_axi_awlen eval_io_m_axi_awsize
eval_io_m_axi_awburst eval_io_m_axi_wvalid eval_io_m_axi_wlast eval_io_m_axi_wstrb
eval_io_m_axi_wdata eval_io_m_axi_rready eval_io_m_axi_bready eval_io_m_irq
eval_macroModelsPresent eval_io_performance_system_cycles
eval_io_performance_fragmentPhaseWorkerCycles_0 eval_io_performance_fragmentPhaseWorkerCycles_1
eval_io_performance_fragmentPhaseWorkerCycles_2 eval_io_performance_fragmentPhaseWorkerCycles_3
eval_io_performance_fragmentPhaseWorkerCycles_4 eval_io_performance_fragmentPhaseWorkerCycles_5""".split()
    progress_methods = """eval_io_performance_system_activeCuCycles
eval_io_performance_stagingOutstandingCycles eval_io_performance_stagingBlockedCycles
eval_io_performance_fragmentOutputBlockedCycles eval_io_performance_payloadSpills""".split()
    clock_state = "uint8_t io_s_axi_aclk = 0; bool bad = false;" if assertions else ""
    tick = """if (!io_s_axi_aclk || bad)
   throw std::runtime_error("FlashSim assertion bound.check: before NBA");
 """ if assertions else ""
    (tmp_path / "dut.h").write_text("""#pragma once
#include <cstdint>
#include <stdexcept>
struct GpuHostSystemAxiDut {
 """ + clock_state + """
 uint64_t io_m_axi_rdata[4] = {}, io_m_axi_wdata[4] = {};
 unsigned ticks = 0, pokes = 0, evaluations = 0;
 void tick() { """ + tick + """++ticks; }
 void poke_inputs() { ++pokes; }
""" + "".join(f" void {name}() {{ ++evaluations; }}\n" for name in methods + progress_methods) + "};\n")
    source = tmp_path / "check.cpp"
    source.write_text("""#include "VGpuHostSystemAxi.h"
#include <cassert>
#include <cstdint>
#include <string>
int main() {
 VerilatedContext context;
 VGpuHostSystemAxi d(&context);
 for (unsigned i=0;i<8;i++) d.io_m_axi_rdata[i] = 0x10203040u+i;
 for (unsigned i=0;i<4;i++) d.GpuHostSystemAxiDut::io_m_axi_wdata[i] =
     uint64_t(0x88776650u+2*i) | (uint64_t(0x88776651u+2*i)<<32);
 d.io_s_axi_aclk=0; d.eval();
 assert(d.ticks==0 && d.pokes==1);
 for (unsigned i=0;i<4;i++) {
   assert(d.GpuHostSystemAxiDut::io_m_axi_rdata[i] ==
          (uint64_t(0x10203040u+2*i) | (uint64_t(0x10203041u+2*i)<<32)));
   assert(d.io_m_axi_wdata[2*i]==0x88776650u+2*i);
   assert(d.io_m_axi_wdata[2*i+1]==0x88776651u+2*i);
 }
 d.io_s_axi_aclk=1; d.eval(); assert(d.ticks==1 && d.pokes==1);
 d.eval(); assert(d.ticks==1 && d.pokes==2);
 d.io_s_axi_aclk=0; d.eval(); assert(d.ticks==1 && d.pokes==3);
 d.io_s_axi_aclk=1; d.eval(); assert(d.ticks==2 && d.pokes==3);
 assert(d.evaluations==5u*""" + str(len(methods)) + """u);
 d.sample_performance();
 assert(d.ticks==2 && d.pokes==3);
 assert(d.evaluations==5u*""" + str(len(methods)) + "+" + str(len(progress_methods)) + """u);
 assert(!context.gotFinish()); d.final();
""" + ("""
 assert(d.GpuHostSystemAxiDut::io_s_axi_aclk==1);
 d.bad=true; d.io_s_axi_aclk=0; d.eval();
 assert(d.GpuHostSystemAxiDut::io_s_axi_aclk==0);
 d.io_s_axi_aclk=1;
 bool caught=false;
 try { d.eval(); }
 catch (const std::runtime_error &error) {
   caught=std::string(error.what())=="FlashSim assertion bound.check: before NBA";
 }
 assert(caught && d.ticks==2);
""" if assertions else "") + "}\n")
    binary = tmp_path / "check"
    subprocess.run([compiler, "-std=c++17", "-O1", "-I", str(tmp_path),
                    "-I", str(adapter), str(source), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)


class FlashSimNativeAdapterTest(unittest.TestCase):
    def test_clock_and_axi_words(self):
        with tempfile.TemporaryDirectory() as directory:
            check_flashsim_native_adapter_clock_and_axi_words(Path(directory))

    def test_bound_assertion_clock_and_failure_before_nba(self):
        with tempfile.TemporaryDirectory() as directory:
            check_flashsim_native_adapter_clock_and_axi_words(Path(directory), assertions=True)


if __name__ == "__main__":
    unittest.main()
