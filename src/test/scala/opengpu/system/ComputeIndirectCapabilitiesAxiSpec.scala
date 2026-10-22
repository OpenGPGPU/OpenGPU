package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeIndirectCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  for((enabled,lanes,warps,localItems)<-Seq((true,4,2,8),(true,16,8,128),(false,4,2,8)))
  it should s"discover only implemented indirect compute controls while preserving co-enabled GTO1 GSL and query owners enabled=$enabled lanes=$lanes warps=$warps" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(),gpuConfig=GpuConfig(lanes=lanes,warps=warps),
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,
      nativeCompletionInfo=true,nativeVertexInput=true,visibilityQueries=true,
      transformFeedback=true,primitiveQueries=true,shaderExecutionLimits=true,
      enableIndirectCompute=enabled)) { d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.binDrained.get.poke(true.B);d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      def read(address: Long,count: Int=1,burst: Int=1,size: Int=2): Vector[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U)
        d.io.s_axi_arsize.poke(size.U);d.io.s_axi_arburst.poke(burst.U);d.io.s_axi_arvalid.poke(true.B)
        until(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        Vector.tabulate(count){i=>
          until(d.io.s_axi_rvalid.peek().litToBoolean)
          val value=d.io.s_axi_rdata.peek().litValue;val response=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 5){d.io.s_axi_rvalid.expect(true.B);d.io.s_axi_rdata.expect(value.U);d.io.s_axi_rresp.expect(response.U);d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B)
          value->response
        }
      }
      def write(address: Int,value: BigInt=BigInt("ffffffff",16),strobe: Int=15,response: Int=2): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke(0.U)
        d.io.s_axi_awsize.poke(2.U);d.io.s_axi_awburst.poke(1.U);d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
        d.io.s_axi_wdata.poke(value.U);d.io.s_axi_wstrb.poke(strobe.U);d.io.s_axi_wlast.poke(true.B);d.io.s_axi_wvalid.poke(true.B)
        until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for(_<-0 until 5){d.io.s_axi_bvalid.expect(true.B);d.io.s_axi_bresp.expect(response.U);d.clock.step()}
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      // Independent literals, including the two physical local-item limits.
      val words=Vector[BigInt](BigInt("47434901",16),8,0x7f,14,12,4,BigInt("ffffffff",16),localItems)
      val expected=if(enabled)words.map(_->BigInt(0))else Vector.fill(8)(BigInt(0)->BigInt(2))
      val gto=Vector[BigInt](BigInt("47544f01",16),16,1,0x7c,0x7c,0xff,15,7,7,3,32,16,15,64,0x3f,0x0007fff8)
      val gsl=Vector[BigInt](BigInt("47534c01",16),8,31,32,1048576,BigInt("04051918",16),0x320,0x138)
      val queries=Vector[BigInt](BigInt("47515601",16),8,BigInt("0b0a0908",16),BigInt("47514f01",16),64,8,BigInt("01001001",16),31,
        BigInt("47464301",16),8,BigInt("1003010c",16),BigInt("47544601",16),512,BigInt("04400408",16),69,255,
        BigInt("47515001",16),8,BigInt("0010040d",16),BigInt("47504f01",16),64,BigInt("00204008",16),0x8c88,63)
      assert(read(0x340,8)==expected)
      assert(read(0x340,9)==expected:+(BigInt(0)->BigInt(2)))
      assert(read(0x33c,2)==Vector(BigInt(0)->BigInt(2),expected.head))
      assert(read(0x340,8,burst=0)==Vector.fill(8)(expected.head))
      assert(read(0x2c0,24)==(gto++gsl).map(_->BigInt(0)))
      assert(read(0x240,24)==queries.map(_->BigInt(0)))
      for(idle<-Seq(false,true)) {
        d.io.vmContextIdle.get.poke(idle.B)
        val legacy=read(0x140,7)
        for(i<-0 until 8;strobe<-Seq(0,1,15))write(0x340+i*4,strobe=strobe)
        assert(read(0x340,8)==expected)
        assert(read(0x140,7)==legacy,"read-only indirect discovery must never forward into mutable legacy state")
        assert(read(0x2c0,24)==(gto++gsl).map(_->BigInt(0)))
        assert(read(0x240,24)==queries.map(_->BigInt(0)))
      }
      d.io.vmContextIdle.get.poke(false.B);write(0x320,17)
      assert(read(0x320)==Vector(BigInt(1048576)->BigInt(0)))
      d.io.vmContextIdle.get.poke(true.B);write(0x320,17,response=0)
      write(0x320,0,strobe=1);write(0x320,0,strobe=0,response=0)
      d.io.shaderInstructionLimit.get.expect(17.U)
      for(address<-Seq(0x341,0x360,0x740,0x1340)) {
        assert(read(address)==Vector(BigInt(0)->BigInt(2)));write(address)
      }
      for(burst<-Seq(2,3))assert(read(0x340,4,burst=burst)==Vector.fill(4)(BigInt(0)->BigInt(2)))
      for(size<-Seq(0,1,3))assert(read(0x340,size=size)==Vector(BigInt(0)->BigInt(2)))
      assert(read(0xfffffff0L,65)==Vector.fill(65)(BigInt(0)->BigInt(2)))
      assert(read(0x340,8)==expected)
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x340,8)==expected)
      assert(read(0x320)==Vector(BigInt(1048576)->BigInt(0)))
      d.io.cbMem.req.valid.expect(false.B);d.io.fbMem.req.valid.expect(false.B)
      info(s"COMPUTE_INDIRECT_AXI enabled=$enabled lanes=$lanes warps=$warps localItems=$localItems GCI1=true independentWords=true heldFIXEDandINCR=true readOnlyAllStrobes=true busyAndIdleNoLegacyMutation=true GTOGSLQueriesCoenabled=true protectedQuotaRetained=true noAliasesOrWrap=true resetStable=true")
    }
  }
}
