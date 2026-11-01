package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationHostAxiCommandSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def until(d: GpuHostAxi)(condition: => Boolean): Unit = {
    var n=0;while(!condition && n<128){d.clock.step();n+=1}
    assert(condition,"AXI operation timed out: "+Thread.currentThread.getStackTrace.filter(_.getClassName.contains("TessellationHostAxiCommandSpec")).mkString(" "))
  }
  private def write(d: GpuHostAxi,address: Int,value: Int,response: Int=0): Unit = {
    d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awvalid.poke(true.B)
    until(d)(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
    d.io.s_axi_wdata.poke((value.toLong & 0xffffffffL).U);d.io.s_axi_wstrb.poke(15.U)
    d.io.s_axi_wlast.poke(true.B);d.io.s_axi_wvalid.poke(true.B)
    until(d)(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
    until(d)(d.io.s_axi_bvalid.peek().litToBoolean);d.io.s_axi_bresp.expect(response.U)
    d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
  }
  private def read(d: GpuHostAxi,address: Int): BigInt = {
    d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arvalid.poke(true.B)
    until(d)(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
    until(d)(d.io.s_axi_rvalid.peek().litToBoolean);val value=d.io.s_axi_rdata.peek().litValue
    d.io.s_axi_rresp.expect(0.U);d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B);value
  }
  it should "route a tessellation job through protected host AXI and pin VM state through completion" in {
    simulate(new GpuHostAxi(gpuConfig=GpuConfig(lanes=4,warps=2),unifiedCommandMmio=true,
        protectVmState=true,externalBinnedRender=true,nativeCompletionInfo=true,tessellationHost=true)){d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B);d.clock.step()
      d.io.s_axi_awsize.poke(2.U);d.io.s_axi_awburst.poke(1.U);d.io.s_axi_arsize.poke(2.U);d.io.s_axi_arburst.poke(1.U)
      d.io.vmContextIdle.get.poke(true.B);d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.binDrained.get.poke(true.B);d.io.tessellationDrained.get.poke(true.B)
      d.io.gpuCommand.get.ready.poke(true.B);d.io.binSubmit.get.ready.poke(true.B)
      val r=GpuCommandMmioRegs
      write(d,RenderHostRegs.IRQ,1)
      write(d,r.COMMAND_ID,41);write(d,r.OPCODE,15);write(d,r.SOURCE,0x4000);write(d,r.DESTINATION,0x4200)
      write(d,r.PATTERN,0);write(d,r.BYTES,512);write(d,r.SUBMIT,1)
      until(d)(d.io.tessellationSubmit.get.valid.peek().litToBoolean)
      d.io.tessellationSubmit.get.bits.commandId.expect(41.U);d.io.tessellationSubmit.get.bits.base.expect(0x4000.U)
      d.io.tessellationSubmit.get.bits.limit.expect(0x4200.U);d.io.tessellationSubmit.get.bits.envelopeValid.expect(true.B)
      d.io.binSubmit.get.valid.expect(false.B);write(d,r.VECTOR_SATP,0,response=2)
      d.io.tessellationSubmit.get.ready.poke(true.B);d.clock.step();d.io.tessellationSubmit.get.ready.poke(false.B)
      d.io.tessellationDrained.get.poke(false.B);d.io.graphicsDrained.expect(false.B)
      write(d,r.TLB_FLUSH,1,response=2)
      val reply=d.io.tessellationCompletion.get
      reply.bits.commandId.poke(41.U);reply.bits.status.poke(0.U);reply.bits.appendReadBytes.poke(704.U)
      reply.valid.poke(true.B);until(d)(reply.ready.peek().litToBoolean);d.clock.step();reply.valid.poke(false.B)
      d.io.tessellationDrained.get.poke(true.B)
      until(d)(d.io.m_irq.peek().litToBoolean)
      val meta=read(d,r.COMPLETION);assert((meta&255)==41 && (((meta>>8)&7)|(((meta>>16)&1)<<3))==15)
      assert(read(d,r.COMPLETION_BYTES_LO)==704)
      write(d,r.TLB_FLUSH,1,response=2);write(d,r.COMPLETION_POP,1);write(d,r.TLB_FLUSH,1)
      write(d,r.RESET,1);d.io.tessellationCancel.get.expect(true.B)
    }
  }
}
