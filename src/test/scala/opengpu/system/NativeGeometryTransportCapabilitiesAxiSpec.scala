package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.graphics.{GraphicsConfig,NativeGeometryTransportCapabilities}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeGeometryTransportCapabilitiesAxiSpec extends AnyFlatSpec {
  it should "require explicit protected prerequisites while preserving the default GCR words" in {
    val profile=ComputeResourceProfile(GpuConfig(lanes=4,warps=2),2,8)
    val legacy=ComputeResourceCapabilities.words(profile)
    assert(!legacy(2).testBit(19))
    intercept[IllegalArgumentException]{ComputeResourceCapabilities.words(profile,nativeGeometryTransportAvailable=true)}
    val enabled=ComputeResourceCapabilities.words(profile,nativeDescriptorPreflightAvailable=true,
      nativeProtectedSourcesAvailable=true,nativeGeometryTransportAvailable=true)
    assert(enabled.updated(2,legacy(2))==legacy && enabled(2)==(legacy(2)|0xb0000))
    assert(NativeGeometryTransportCapabilities.words==Seq[BigInt](BigInt("47475301",16),8,31,
      BigInt("474e4a02",16),BigInt("474e4603",16),BigInt("474e5902",16),
      BigInt("ff840206",16),BigInt("04001000",16)))
  }
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((enabled,version,native,sources)<-Seq((true,2,true,true),(false,2,true,true),(false,1,true,false),(false,1,false,false)))
  it should s"discover independent geometry/layered transport without aliases enabled=$enabled abi=$version native=$native sources=$sources" in {
    val cfg=GpuConfig(lanes=4,warps=2);val modern=version>=2
    simulate(new GpuHostAxi(config=GraphicsConfig(screenWidth=8,screenHeight=8),gpuConfig=cfg,
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,nativeVertexInput=native,
      version=version,computeResources=if(modern)Some(ComputeResourceProfile(cfg,2,8))else None,
      imageAvailable=modern,storageAvailable=modern,computeUniformAvailable=modern,
      computeTextureAvailable=modern,atomicCounterAvailable=modern,nativeDescriptorPreflight=sources,nativeGeometryTransport=enabled,
      nativeCompletionInfo=sources,nativeUniformResources=sources,shaderExecutionLimits=sources,
      nativeProtectedSources=sources)){d=>
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
      val canonical=Vector[BigInt](BigInt("47475301",16),8,31,BigInt("474e4a02",16),BigInt("474e4603",16),BigInt("474e5902",16),BigInt("ff840206",16),BigInt("04001000",16))
      val invalid=BigInt(0)->BigInt(2);val expected=if(enabled)canonical.map(_->BigInt(0))else Vector.fill(8)(invalid)
      val device=read(0);val storage=read(0x420,8);val before=read(0x4fc)
      val frozen=Vector[BigInt](BigInt("47444201",16),8,6,4,0x7fff,0x1f,0x7f,0)
      assert(read(0x400,8)==(if(native)frozen.map(_->BigInt(0))else Vector.fill(8)(invalid)))
      val gcr=read(0x368).head
      if(modern){assert(gcr._2==0 && gcr._1.testBit(16)==sources && gcr._1.testBit(17)==sources && gcr._1.testBit(19)==enabled)}else assert(gcr==invalid)
      if(sources){
        val gsl=Vector[BigInt](BigInt("47534c01",16),8,31,32,1<<20,BigInt("04051918",16),0x320,0x138)
        assert(read(0x300,8)==gsl.map(_->BigInt(0)))
        assert(read(0x3a0,16)==opengpu.graphics.NativeUniformCapabilities.words.map(_->BigInt(0)).toVector)
      }else assert(read(0x3a0)==Vector(invalid))
      assert(read(0x500,8)==expected);assert(read(0x500,8,burst=0)==Vector.fill(8)(expected.head))
      assert(read(0x4fc,2)==before++Vector(expected.head));assert(read(0x500,9)==expected:+invalid)
      for(i<-0 until 8;s<-Seq(0,1,15)){write(0x500+4*i,s);assert(read(0x500+4*i)==Vector(expected(i)))}
      for(a<-Seq(0x501L,0x520L,0x800L,0x1500L)){assert(read(a)==Vector(invalid));write(a,15)}
      for(b<-Seq(2,3))assert(read(0x500,4,burst=b)==Vector.fill(4)(invalid))
      for(s<-Seq(0,1,3))assert(read(0x500,size=s)==Vector(invalid))
      assert(read(0xfffffffcL,2)==Vector.fill(2)(invalid));assert(read(0x420,8)==storage);assert(read(0)==device)
      d.io.vmContextIdle.get.poke(true.B);assert(read(0x500,8)==expected)
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x500,8)==expected)
    }
  }
}
