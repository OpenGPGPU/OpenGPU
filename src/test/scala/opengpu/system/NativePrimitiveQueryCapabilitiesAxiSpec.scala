package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativePrimitiveQueryCapabilitiesAxiSpec extends AnyFlatSpec {
  // Local input initialization avoids depending on a graphics-private helper.
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if chisel3.reflect.DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if chisel3.reflect.DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(enabled<-Seq(true,false))
  it should s"discover immutable primitive-query resources without changing disabled address decoding enabled=$enabled" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(),gpuConfig=GpuConfig(lanes=4,warps=2),
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,nativeVertexInput=true,
      transformFeedback=enabled,transformFeedbackSourceWords=13,primitiveQueries=enabled)){d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B)
      def until(c: =>Boolean): Unit={var n=0;while(!c && n<100){d.clock.step();n+=1};assert(c)}
      def read(address: BigInt,count: Int=1): Seq[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U)
        d.io.s_axi_arsize.poke(2.U);d.io.s_axi_arburst.poke(1.U);d.io.s_axi_arvalid.poke(true.B)
        until(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        (0 until count).map{i=>until(d.io.s_axi_rvalid.peek().litToBoolean)
          val v=d.io.s_axi_rdata.peek().litValue;val r=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 4){d.io.s_axi_rdata.expect(v.U);d.io.s_axi_rresp.expect(r.U);d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B);v->r
        }
      }
      def write(address: Int,strobe: Int): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke(0.U)
        d.io.s_axi_awsize.poke(2.U);d.io.s_axi_awburst.poke(1.U);d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
        d.io.s_axi_wdata.poke(0xffffffffL.U);d.io.s_axi_wstrb.poke(strobe.U)
        d.io.s_axi_wlast.poke(true.B);d.io.s_axi_wvalid.poke(true.B)
        until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        until(d.io.s_axi_bvalid.peek().litToBoolean);d.io.s_axi_bresp.expect(2.U)
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      val words=Seq[BigInt](BigInt("47515001",16),8,BigInt("0010040d",16),BigInt("47504f01",16),64,BigInt("00204008",16),0x8c88,63)
      val expected=if(enabled)words.map(_->BigInt(0))else Seq.fill(8)(BigInt(0)->BigInt(2))
      assert(read(0x280,8)==expected)
      assert(read(0x280,9)==expected:+(BigInt(0)->BigInt(2)))
      for(i<-0 until 8;strobe<-Seq(0,1,15)){write(0x280+4*i,strobe);assert(read(0x280+4*i)==Seq(expected(i)))}
      for(a<-Seq(0x281,0x2a0,0x680))assert(read(a)==Seq(BigInt(0)->BigInt(2)))
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x280,8)==expected)
    }
  }
}
