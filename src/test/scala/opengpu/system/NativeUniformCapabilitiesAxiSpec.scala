package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{GpuConfig,ComputeResourceProfile}
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeUniformCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((native,uniform,external,lanes,warps,compute)<-Seq(
      (true,true,true,16,8,true),(true,true,true,4,2,false),
      (true,false,true,4,2,false),(false,false,true,4,2,false),(false,false,false,4,2,false)))
  it should s"discover checked uniform loads through immutable AXI words native=$native uniform=$uniform external=$external lanes=$lanes warps=$warps compute=$compute" in {
    val gpu=GpuConfig(lanes=lanes,warps=warps)
    simulate(new GpuHostAxi(config=GraphicsConfig(screenWidth=8,screenHeight=8),gpuConfig=gpu,
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=external,
      nativeVertexInput=native,nativeCompletionInfo=native,nativeUniformResources=uniform,
      visibilityQueries=native,transformFeedback=native,primitiveQueries=native,shaderExecutionLimits=native,
      computeResources=if(compute)Some(ComputeResourceProfile(gpu,4,32))else None)){d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B)
      d.io.binDrained.foreach(_.poke(true.B));d.io.graphicsDependencyIdle.foreach(_.poke(true.B))
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<100){d.clock.step();n+=1};assert(condition)}
      def read(address: Long,count: Int=1,burst: Int=1,size: Int=2): Vector[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U);d.io.s_axi_arsize.poke(size.U)
        d.io.s_axi_arburst.poke(burst.U);d.io.s_axi_arvalid.poke(true.B);d.io.s_axi_rready.poke(false.B)
        until(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        Vector.tabulate(count){i=>
          until(d.io.s_axi_rvalid.peek().litToBoolean)
          val value=d.io.s_axi_rdata.peek().litValue;val response=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 5){d.io.s_axi_rvalid.expect(true.B);d.io.s_axi_rdata.expect(value.U)
            d.io.s_axi_rresp.expect(response.U);d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B);value->response
        }
      }
      def write(address: Long,strobe: Int=15,count: Int=1,burst: Int=1,size: Int=2): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke((count-1).U);d.io.s_axi_awsize.poke(size.U)
        d.io.s_axi_awburst.poke(burst.U);d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
        for(i<-0 until count){
          d.io.s_axi_wvalid.poke(true.B);d.io.s_axi_wdata.poke(0xffffffffL.U);d.io.s_axi_wstrb.poke(strobe.U)
          d.io.s_axi_wlast.poke((i==count-1).B);until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        }
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for(_<-0 until 5){d.io.s_axi_bvalid.expect(true.B);d.io.s_axi_bresp.expect(2.U);d.clock.step()}
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      // Literal ISA/ABI contract, independent of the RTL record encoder.
      val words=Vector[BigInt](BigInt("47554301",16),16,1,127,3,12,24,16384,1024,1024,256,64,
        BigInt("0e00000b",16),BigInt("1c00002b",16),BigInt("1e00002b",16),31)
      val expected=if(uniform)words.map(_->BigInt(0))else Vector.fill(16)(BigInt(0)->BigInt(2))
      val unmapped=BigInt(0)->BigInt(2)
      assert(read(0x3a0,16)==expected);assert(read(0x3a0,16,burst=0)==Vector.fill(16)(expected.head))
      assert(read(0x3a0,17)==expected:+unmapped)
      val lastCompute=if(compute)BigInt(4)->BigInt(0)else unmapped
      assert(read(0x39c,2)==Vector(lastCompute,expected.head))
      if(compute){
        val gcr=Vector[BigInt](BigInt("47435201",16),16,15,4,16,8,128,128,BigInt("ffffffff",16),16384,16,8,
          BigInt("10000000",16),32,65536,4).map(_->BigInt(0))
        assert(read(0x360,32)==gcr++expected)
      }
      val texture=Vector[BigInt](BigInt("47544f01",16),16,1,0x7c,0x7c,0xff,15,7,7,3,32,16,15,64,0x3f,0x0007fff8)
      val textureExpected=if(native)texture.map(_->BigInt(0))else Vector.fill(16)(unmapped)
      assert(read(0x2c0,16)==textureExpected)
      for(word<-0 until 16;strobe<-Seq(0,1,15)){write(0x3a0+word*4,strobe);assert(read(0x3a0+word*4)==Vector(expected(word)))}
      write(0x3a0,count=16);assert(read(0x3a0,16)==expected)
      for(address<-Seq(0x3a1L,0x3e0L,0x7a0L,0x13a0L)){assert(read(address)==Vector(unmapped));write(address)}
      for(burst<-Seq(2,3)){assert(read(0x3a0,4,burst=burst)==Vector.fill(4)(unmapped));write(0x3a0,count=4,burst=burst)}
      for(size<-Seq(0,1,3)){assert(read(0x3a0,size=size)==Vector(unmapped));write(0x3a0,size=size)}
      assert(read(0x3a0,17,burst=0)==Vector.fill(17)(unmapped))
      assert(read(0xfffffff0L,65)==Vector.fill(65)(unmapped))
      d.io.vmContextIdle.get.poke(true.B);assert(read(0x3a0,16)==expected)
      assert(read(0x2c0,16)==textureExpected)
    }
  }
}
