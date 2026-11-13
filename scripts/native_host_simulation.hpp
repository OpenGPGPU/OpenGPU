#pragma once
#include "native_frame_fixture.hpp"
#include "VGpuHostSystemAxi.h"
#include "verilated.h"
#include <memory>

namespace native_frame {
class Simulation {
public:
  VerilatedContext context;
  std::unique_ptr<VGpuHostSystemAxi> model;
  // Driver-observed rising edges, including reset and control transactions.
  uint64_t clock_cycles = 0;
  Simulation(int argc, char **argv) {
    context.commandArgs(argc, argv);
    model = std::make_unique<VGpuHostSystemAxi>(&context);
    model->io_s_axi_aclk = 0;
  }
  void settle() { model->eval(); }
  void tick() {
    model->io_s_axi_aclk = 0; settle();
    advance_settled();
  }
  void pause_memory() {
    auto &d = *model;
    d.io_m_axi_arready = d.io_m_axi_rvalid = d.io_m_axi_rlast = 0;
    d.io_m_axi_awready = d.io_m_axi_wready = d.io_m_axi_bvalid = 0;
    settle();
  }
  void reset() {
    auto &d = *model;
    d.io_s_axi_awvalid = d.io_s_axi_wvalid = d.io_s_axi_wlast = d.io_s_axi_bready = 0;
    d.io_s_axi_arvalid = d.io_s_axi_rready = 0;
    d.io_m_axi_bid = d.io_m_axi_bresp = d.io_m_axi_rid = d.io_m_axi_rresp = 0;
    for (unsigned w = 0; w < 8; ++w) d.io_m_axi_rdata[w] = 0;
    pause_memory(); d.io_s_axi_aresetn = 0; tick(); d.io_s_axi_aresetn = 1; tick();
    require(d.macroModelsPresent, "characterized SRAM simulation resources missing");
  }
  void write(uint32_t address, uint32_t value) {
    auto &d = *model;
    d.io_s_axi_awaddr = address; d.io_s_axi_awlen = 0; d.io_s_axi_awsize = 2;
    d.io_s_axi_awburst = 0; d.io_s_axi_awvalid = 1;
    d.io_s_axi_wdata = value; d.io_s_axi_wstrb = 15; d.io_s_axi_wlast = 1;
    d.io_s_axi_wvalid = 1; d.io_s_axi_bready = 1;
    bool aw = false, w = false, b = false;
    for (unsigned n = 0; !b && n < 64; ++n) {
      settle();
      if (!aw && d.io_s_axi_awready) aw = true;
      if (!w && d.io_s_axi_wready) w = true;
      if (d.io_s_axi_bvalid) { require(d.io_s_axi_bresp == 0, "control write fault"); b = true; }
      tick(); if (aw) d.io_s_axi_awvalid = 0; if (w) d.io_s_axi_wvalid = 0;
    }
    require(b, "control write timed out");
    d.io_s_axi_awvalid = d.io_s_axi_wvalid = d.io_s_axi_wlast = d.io_s_axi_bready = 0;
    settle();
  }
  uint32_t read(uint32_t address) {
    auto &d = *model;
    d.io_s_axi_araddr = address; d.io_s_axi_arlen = 0; d.io_s_axi_arsize = 2;
    d.io_s_axi_arburst = 0; d.io_s_axi_arvalid = d.io_s_axi_rready = 1;
    bool ar = false, r = false; uint32_t result = 0;
    for (unsigned n = 0; !r && n < 64; ++n) {
      settle(); if (!ar && d.io_s_axi_arready) ar = true;
      if (d.io_s_axi_rvalid) {
        require(d.io_s_axi_rresp == 0, "control read fault"); result = d.io_s_axi_rdata; r = true;
      }
      tick(); if (ar) d.io_s_axi_arvalid = 0;
    }
    require(r, "control read timed out"); d.io_s_axi_arvalid = d.io_s_axi_rready = 0;
    settle(); return result;
  }
  void drive(const Responses &r) {
    auto &d = *model;
    d.io_m_axi_arready = r.arready; d.io_m_axi_awready = r.awready; d.io_m_axi_wready = r.wready;
    d.io_m_axi_rvalid = r.rvalid; d.io_m_axi_rlast = r.rlast;
    if (r.rvalid) {
      d.io_m_axi_rid = r.rid; d.io_m_axi_rresp = r.rresp;
      for (unsigned w = 0; w < 8; ++w) {
        uint32_t value = 0;
        for (unsigned b = 0; b < 4; ++b) value |= uint32_t(r.rdata[w * 4 + b]) << (b * 8);
        d.io_m_axi_rdata[w] = value;
      }
    }
    d.io_m_axi_bvalid = r.bvalid; d.io_m_axi_bid = r.bid; d.io_m_axi_bresp = r.bresp;
    settle();
  }
  Requests requests() const {
    const auto &d = *model; Requests r;
    r.arvalid = d.io_m_axi_arvalid; r.araddr = d.io_m_axi_araddr; r.arid = d.io_m_axi_arid;
    r.arlen = d.io_m_axi_arlen; r.arsize = d.io_m_axi_arsize; r.arburst = d.io_m_axi_arburst;
    r.awvalid = d.io_m_axi_awvalid; r.awaddr = d.io_m_axi_awaddr; r.awid = d.io_m_axi_awid;
    r.awlen = d.io_m_axi_awlen; r.awsize = d.io_m_axi_awsize; r.awburst = d.io_m_axi_awburst;
    r.wvalid = d.io_m_axi_wvalid; r.wlast = d.io_m_axi_wlast; r.wstrb = d.io_m_axi_wstrb;
    for (unsigned w = 0; w < 8; ++w)
      for (unsigned b = 0; b < 4; ++b) r.wdata[w * 4 + b] = uint8_t(d.io_m_axi_wdata[w] >> (b * 8));
    r.rready = d.io_m_axi_rready; r.bready = d.io_m_axi_bready;
    return r;
  }
  // drive() settles the low phase before requests are observed. No model
  // inputs change between that snapshot and the rising edge.
  void step(Agent &agent, uint64_t cycle) {
    require(model->io_s_axi_aclk == 0, "native AXI step requires a low clock");
    const auto response = agent.drive(cycle);
    drive(response);
    const auto request = requests();
    agent.observe(cycle, request, response);
    advance_settled();
  }

private:
  void advance_settled() {
    context.timeInc(5);
    model->io_s_axi_aclk = 1; settle(); context.timeInc(5);
    ++clock_cycles;
    model->io_s_axi_aclk = 0; settle();
    require(!context.gotFinish(), "RTL simulation stopped before qualification completed");
  }
};
} // namespace native_frame
