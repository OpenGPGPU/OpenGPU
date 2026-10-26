package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsConfig,NativeDrawBufferBlendCapabilities}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeDrawBufferBlendCapabilitiesAxiSpec extends AnyFlatSpec {
  require(NativeDrawBufferBlendCapabilities.base==0x400 && NativeDrawBufferBlendCapabilities.wordCount==8)
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((native,lanes,warps)<-Seq((true,4,2),(true,16,8),(false,4,2)))
  it should s"discover read-only indexed blend state without register aliases native=$native lanes=$lanes warps=$warps" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(screenWidth=8,screenHeight=8),
      gpuConfig=GpuConfig(lanes=lanes,warps=warps),unifiedCommandMmio=true,protectVmState=true,
      externalBinnedRender=true,nativeVertexInput=native)){d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B);d.io.binDrained.foreach(_.poke(true.B))
      d.io.graphicsDependencyIdle.foreach(_.poke(true.B))
      def until(done: =>Boolean): Unit={var n=0;while(!done && n<100){d.clock.step();n+=1};assert(done)}
      def read(address: Long,count: Int=1,burst: Int=1,size: Int=2): Vector[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U)
        d.io.s_axi_arburst.poke(burst.U);d.io.s_axi_arsize.poke(size.U);d.io.s_axi_arvalid.poke(true.B)
        until(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        Vector.tabulate(count){i=>
          until(d.io.s_axi_rvalid.peek().litToBoolean)
          val value=d.io.s_axi_rdata.peek().litValue;val response=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 4){d.io.s_axi_rvalid.expect(true.B);d.io.s_axi_rdata.expect(value.U)
            d.io.s_axi_rresp.expect(response.U);d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B);value->response
        }
      }
      def write(address: Long,strobe: Int): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke(0.U);d.io.s_axi_awsize.poke(2.U)
        d.io.s_axi_awburst.poke(1.U);d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
        d.io.s_axi_wdata.poke(0xffffffffL.U);d.io.s_axi_wstrb.poke(strobe.U);d.io.s_axi_wlast.poke(true.B)
        d.io.s_axi_wvalid.poke(true.B);until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for(_<-0 until 4){d.io.s_axi_bresp.expect(2.U);d.io.s_axi_bvalid.expect(true.B);d.clock.step()}
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      // Literal driver contract, independent of the RTL capability encoder.
      val words=Vector[BigInt](BigInt("47444201",16),8,6,4,0x7fff,0x1f,0x7f,0)
      val unmapped=BigInt(0)->BigInt(2)
      val expected=if(native)words.map(_->BigInt(0))else Vector.fill(8)(unmapped)
      val device=read(0)
      assert(read(0x400,8)==expected);assert(read(0x400,8,burst=0)==Vector.fill(8)(expected.head))
      assert(read(0x3fc,2)==Vector(unmapped,expected.head));assert(read(0x400,9)==expected:+unmapped)
      for(i<-0 until 8;strobe<-Seq(0,1,15)){write(0x400+i*4,strobe);assert(read(0x400+i*4)==Vector(expected(i)))}
      for(address<-Seq(0x401L,0x420L,0x800L,0x1400L)){assert(read(address)==Vector(unmapped));write(address,15)}
      for(burst<-Seq(2,3))assert(read(0x400,4,burst=burst)==Vector.fill(4)(unmapped))
      for(size<-Seq(0,1,3))assert(read(0x400,size=size)==Vector(unmapped))
      assert(read(0xfffffffcL,2)==Vector.fill(2)(unmapped));assert(read(0)==device)
      d.io.vmContextIdle.get.poke(true.B);assert(read(0x400,8)==expected)
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x400,8)==expected)
    }
  }
}
