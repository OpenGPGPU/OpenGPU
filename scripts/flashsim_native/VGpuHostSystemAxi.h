#pragma once
#define OPENGPU_FLASHSIM_NATIVE 1
// FlashSim-to-Verilator driver interface. Bound checks require a model
// generated with FlashSim's --assertions option and separate qualification.
#include "dut.h"
#include "verilated.h"
#include <cstdint>
class VGpuHostSystemAxi : public GpuHostSystemAxiDut {
  uint8_t last_clock = 0;
  template<class T>
  static auto drive_model_clock(T &dut, uint8_t value, int)
      -> decltype(dut.io_s_axi_aclk = value, void()) { dut.io_s_axi_aclk = value; }
  // Older implicit-clock models omit the clock member entirely.
  template<class T> static void drive_model_clock(T &, uint8_t, long) {}
public:
  uint8_t io_s_axi_aclk = 0;
  uint32_t io_m_axi_rdata[8] = {};
  uint32_t io_m_axi_wdata[8] = {};
  explicit VGpuHostSystemAxi(VerilatedContext *) {}
  // Evaluate lazy monitoring outputs without advancing the clock.
  void sample_performance() {
    eval_io_performance_system_activeCuCycles();
    eval_io_performance_stagingOutstandingCycles();
    eval_io_performance_stagingBlockedCycles();
    eval_io_performance_fragmentOutputBlockedCycles();
    eval_io_performance_payloadSpills();
  }
  void eval() {
    static_assert(sizeof(GpuHostSystemAxiDut::io_m_axi_rdata) == 32, "H6 read width");
    static_assert(sizeof(GpuHostSystemAxiDut::io_m_axi_wdata) == 32, "H6 write width");
    drive_model_clock(static_cast<GpuHostSystemAxiDut &>(*this), io_s_axi_aclk, 0);
    for (unsigned i = 0; i < 4; ++i)
      GpuHostSystemAxiDut::io_m_axi_rdata[i] = uint64_t(io_m_axi_rdata[2*i]) |
                                               (uint64_t(io_m_axi_rdata[2*i+1]) << 32);
    if (!last_clock && io_s_axi_aclk) tick();
    else poke_inputs();
    last_clock = io_s_axi_aclk;
    eval_io_s_axi_awready();
    eval_io_s_axi_wready();
    eval_io_s_axi_bvalid();
    eval_io_s_axi_bresp();
    eval_io_s_axi_arready();
    eval_io_s_axi_rvalid();
    eval_io_s_axi_rresp();
    eval_io_s_axi_rdata();
    eval_io_m_axi_arvalid();
    eval_io_m_axi_araddr();
    eval_io_m_axi_arid();
    eval_io_m_axi_arlen();
    eval_io_m_axi_arsize();
    eval_io_m_axi_arburst();
    eval_io_m_axi_awvalid();
    eval_io_m_axi_awaddr();
    eval_io_m_axi_awid();
    eval_io_m_axi_awlen();
    eval_io_m_axi_awsize();
    eval_io_m_axi_awburst();
    eval_io_m_axi_wvalid();
    eval_io_m_axi_wlast();
    eval_io_m_axi_wstrb();
    eval_io_m_axi_wdata();
    eval_io_m_axi_rready();
    eval_io_m_axi_bready();
    eval_io_m_irq();
    eval_macroModelsPresent();
    eval_io_performance_system_cycles();
    eval_io_performance_fragmentPhaseWorkerCycles_0();
    eval_io_performance_fragmentPhaseWorkerCycles_1();
    eval_io_performance_fragmentPhaseWorkerCycles_2();
    eval_io_performance_fragmentPhaseWorkerCycles_3();
    eval_io_performance_fragmentPhaseWorkerCycles_4();
    eval_io_performance_fragmentPhaseWorkerCycles_5();
    for (unsigned i = 0; i < 4; ++i) {
      io_m_axi_wdata[2*i] = uint32_t(GpuHostSystemAxiDut::io_m_axi_wdata[i]);
      io_m_axi_wdata[2*i+1] = uint32_t(GpuHostSystemAxiDut::io_m_axi_wdata[i] >> 32);
    }
  }
  void final() {}
};
