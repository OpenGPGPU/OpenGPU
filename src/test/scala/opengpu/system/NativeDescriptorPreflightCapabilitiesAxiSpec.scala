package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.graphics.{GraphicsConfig,NativeDescriptorPreflightCapabilities}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeDescriptorPreflightCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((enabled,version,native)<-Seq((true,2,true),(false,2,true),(false,1,true),(false,1,false)))
  it should s"discover independent native descriptor preflight without aliases enabled=$enabled abi=$version native=$native" in {
    val cfg=GpuConfig(lanes=4,warps=2);val modern=version>=2
    simulate(new GpuHostAxi(config=GraphicsConfig(screenWidth=8,screenHeight=8),gpuConfig=cfg,
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,nativeVertexInput=native,
      version=version,computeResources=if(modern)Some(ComputeResourceProfile(cfg,2,8))else None,
      imageAvailable=modern,storageAvailable=modern,computeUniformAvailable=modern,
      computeTextureAvailable=modern,atomicCounterAvailable=modern,nativeDescriptorPreflight=enabled)){d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B);d.io.binDrained.foreach(_.poke(true.B));d.io.graphicsDependencyIdle.foreach(_.poke(true.B))
      def until(done: =>Boolean): Unit={var n=0;while(!done && n<100){d.clock.step();n+=1};assert(done)}
      def read(address: Long,count: Int=1,burst: Int=1,size: Int=2): Vector[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U);d.io.s_axi_arburst.poke(burst.U)
        d.io.s_axi_arsize.poke(size.U);d.io.s_axi_arvalid.poke(true.B);until(d.io.s_axi_arready.peek().litToBoolean)
        d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        Vector.tabulate(count){i=>until(d.io.s_axi_rvalid.peek().litToBoolean)
          val v=d.io.s_axi_rdata.peek().litValue;val r=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 3){d.io.s_axi_rvalid.expect(true.B);d.io.s_axi_rdata.expect(v.U);d.io.s_axi_rresp.expect(r.U)
            d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B);v->r}
      }
      def write(address: Long,strobe: Int): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke(0.U);d.io.s_axi_awsize.poke(2.U)
        d.io.s_axi_awburst.poke(1.U);d.io.s_axi_awvalid.poke(true.B);until(d.io.s_axi_awready.peek().litToBoolean)
        d.clock.step();d.io.s_axi_awvalid.poke(false.B);d.io.s_axi_wdata.poke(0xffffffffL.U)
        d.io.s_axi_wstrb.poke(strobe.U);d.io.s_axi_wlast.poke(true.B);d.io.s_axi_wvalid.poke(true.B)
        until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for(_<-0 until 3){d.io.s_axi_bvalid.expect(true.B);d.io.s_axi_bresp.expect(2.U);d.clock.step()}
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      val canonical=Vector[BigInt](BigInt("474e5001",16),8,512,2048,2048,512,15,0)
      val invalid=BigInt(0)->BigInt(2);val expected=if(enabled)canonical.map(_->BigInt(0))else Vector.fill(8)(invalid)
      val device=read(0);val storage=read(0x420,8);val before=read(0x4dc)
      val frozen=Vector[BigInt](BigInt("47444201",16),8,6,4,0x7fff,0x1f,0x7f,0)
      assert(read(0x400,8)==(if(native)frozen.map(_->BigInt(0))else Vector.fill(8)(invalid)))
      val gcr=read(0x368).head
      if(modern){assert(gcr._2==0 && gcr._1.testBit(16)==enabled)}else assert(gcr==invalid)
      assert(read(0x4e0,8)==expected);assert(read(0x4e0,8,burst=0)==Vector.fill(8)(expected.head))
      assert(read(0x4dc,2)==before++Vector(expected.head));assert(read(0x4e0,9)==expected:+invalid)
      for(i<-0 until 8;s<-Seq(0,1,15)){write(0x4e0+4*i,s);assert(read(0x4e0+4*i)==Vector(expected(i)))}
      for(a<-Seq(0x4e1L,0x500L,0x800L,0x14e0L)){assert(read(a)==Vector(invalid));write(a,15)}
      for(b<-Seq(2,3))assert(read(0x4e0,4,burst=b)==Vector.fill(4)(invalid))
      for(s<-Seq(0,1,3))assert(read(0x4e0,size=s)==Vector(invalid))
      assert(read(0xfffffffcL,2)==Vector.fill(2)(invalid));assert(read(0x420,8)==storage);assert(read(0)==device)
      d.io.vmContextIdle.get.poke(true.B);assert(read(0x4e0,8)==expected)
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x4e0,8)==expected)
    }
  }
}
