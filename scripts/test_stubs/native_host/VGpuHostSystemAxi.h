#pragma once
#include "verilated.h"
#include <cstdint>
#include <vector>

class VGpuHostSystemAxi {
 public:
  struct Event { uint64_t time; bool clock, reset, read_ready, read_valid; };
  explicit VGpuHostSystemAxi(VerilatedContext *context) : context_(context) {}
  void final() {}
  void eval() {
    if (!io_s_axi_aclk) io_m_axi_arvalid = issue_read && io_m_axi_arready;
    events.push_back({context_->time(), bool(io_s_axi_aclk), bool(io_s_axi_aresetn),
                      bool(io_m_axi_arready), bool(io_m_axi_arvalid)});
    if (io_s_axi_aclk && !previous_clock && io_m_axi_arvalid && io_m_axi_arready)
      issue_read = false;
    previous_clock = io_s_axi_aclk;
  }
  VerilatedContext *context_;
  std::vector<Event> events;
  bool previous_clock = false, issue_read = true;
  uint32_t io_s_axi_aclk = 0, io_s_axi_aresetn = 1, macroModelsPresent = 1;
  uint32_t io_m_irq = 0, io_performance_activeComputeUnits = 0;
  uint32_t io_s_axi_awvalid = 0, io_s_axi_wvalid = 0, io_s_axi_wlast = 0, io_s_axi_bready = 0;
  uint32_t io_s_axi_arvalid = 0, io_s_axi_rready = 0, io_s_axi_awaddr = 0, io_s_axi_awlen = 0;
  uint32_t io_s_axi_awsize = 0, io_s_axi_awburst = 0, io_s_axi_wdata = 0, io_s_axi_wstrb = 0;
  uint32_t io_s_axi_awready = 0, io_s_axi_wready = 0, io_s_axi_bvalid = 0, io_s_axi_bresp = 0;
  uint32_t io_s_axi_araddr = 0, io_s_axi_arlen = 0, io_s_axi_arsize = 0, io_s_axi_arburst = 0;
  uint32_t io_s_axi_arready = 0, io_s_axi_rvalid = 0, io_s_axi_rresp = 0, io_s_axi_rdata = 0;
  uint32_t io_m_axi_arready = 0, io_m_axi_rvalid = 0, io_m_axi_rlast = 0;
  uint32_t io_m_axi_awready = 0, io_m_axi_wready = 0, io_m_axi_bvalid = 0;
  uint32_t io_m_axi_bid = 0, io_m_axi_bresp = 0, io_m_axi_rid = 0, io_m_axi_rresp = 0;
  uint32_t io_m_axi_rdata[8] = {};
  uint32_t io_m_axi_arvalid = 0, io_m_axi_araddr = 0x1000, io_m_axi_arid = 7;
  uint32_t io_m_axi_arlen = 1, io_m_axi_arsize = 5, io_m_axi_arburst = 1;
  uint32_t io_m_axi_awvalid = 0, io_m_axi_awaddr = 0, io_m_axi_awid = 0;
  uint32_t io_m_axi_awlen = 0, io_m_axi_awsize = 5, io_m_axi_awburst = 1;
  uint32_t io_m_axi_wvalid = 0, io_m_axi_wlast = 0, io_m_axi_wstrb = 0, io_m_axi_wdata[8] = {};
  uint32_t io_m_axi_rready = 1, io_m_axi_bready = 1;
};
