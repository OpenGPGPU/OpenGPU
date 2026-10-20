package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeVertexInputCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  for(enabled<-Seq(true,false);samples<-Seq(1,2,4) if enabled || samples==4)
  it should s"discover immutable native input formats through held AXI bursts and retain the legacy map enabled=$enabled samples=$samples" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(maxSampleCount=samples),gpuConfig=GpuConfig(lanes=4,warps=2),unifiedCommandMmio=true,protectVmState=true,
      externalBinnedRender=enabled,nativeVertexInput=enabled)) { d=>
      zero(d.io);d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      d.io.vmContextIdle.get.poke(false.B)
      d.io.binDrained.foreach(_.poke(true.B));d.io.graphicsDependencyIdle.foreach(_.poke(true.B))
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<64){d.clock.step();n+=1};assert(condition)}
      def read(address: Long,count: Int=1,burst: Int=1,size: Int=2): Vector[(BigInt,BigInt)]={
        d.io.s_axi_araddr.poke(address.U);d.io.s_axi_arlen.poke((count-1).U);d.io.s_axi_arsize.poke(2.U)
        d.io.s_axi_arsize.poke(size.U);d.io.s_axi_arburst.poke(burst.U);d.io.s_axi_arvalid.poke(true.B);d.io.s_axi_rready.poke(false.B)
        until(d.io.s_axi_arready.peek().litToBoolean);d.clock.step();d.io.s_axi_arvalid.poke(false.B)
        Vector.tabulate(count) { i=>
          until(d.io.s_axi_rvalid.peek().litToBoolean)
          val data=d.io.s_axi_rdata.peek().litValue;val response=d.io.s_axi_rresp.peek().litValue
          for(_<-0 until 5){d.io.s_axi_rvalid.expect(true.B);d.io.s_axi_rdata.expect(data.U)
            d.io.s_axi_rresp.expect(response.U);d.io.s_axi_rlast.expect((i==count-1).B);d.clock.step()}
          d.io.s_axi_rready.poke(true.B);d.clock.step();d.io.s_axi_rready.poke(false.B)
          data->response
        }
      }
      def write(address: Long,strb: Int,values: Vector[BigInt]=Vector(BigInt("ffffffff",16)),burst: Int=1,size: Int=2,response: Int=2): Unit={
        d.io.s_axi_awaddr.poke(address.U);d.io.s_axi_awlen.poke((values.size-1).U);d.io.s_axi_awsize.poke(size.U)
        d.io.s_axi_awburst.poke(burst.U);d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean);d.clock.step();d.io.s_axi_awvalid.poke(false.B)
        for((value,i)<-values.zipWithIndex){
          d.io.s_axi_wvalid.poke(true.B);d.io.s_axi_wdata.poke(value.U);d.io.s_axi_wstrb.poke(strb.U);d.io.s_axi_wlast.poke((i==values.size-1).B)
          until(d.io.s_axi_wready.peek().litToBoolean);d.clock.step();d.io.s_axi_wvalid.poke(false.B)
        }
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for(_<-0 until 5){d.io.s_axi_bvalid.expect(true.B);d.io.s_axi_bresp.expect(response.U);d.clock.step()}
        d.io.s_axi_bready.poke(true.B);d.clock.step();d.io.s_axi_bready.poke(false.B)
      }
      // Expected words are independent literals, not the RTL capability encoder.
      val values=Vector(BigInt("47564901",16),BigInt(4),BigInt(0x502),BigInt(0x1ffffff))
      val expected=if(enabled)values.map(_->BigInt(0))else Vector.fill(4)(BigInt(0)->BigInt(2))
      assert(read(0x1b0,4)==expected)
      val sampleModes=Map(1->0x100,2->0x300,4->0x700)(samples)
      val fragment=Vector(BigInt("47464901",16),BigInt(4),BigInt(0x1e),BigInt(sampleModes|15))
      val fexpected=if(enabled)fragment.map(_->BigInt(0))else Vector.fill(4)(BigInt(0)->BigInt(2))
      assert(read(0x1c0,4)==fexpected)
      assert(read(0x1c0,5)==fexpected:+(if(enabled)BigInt("47524901",16)->BigInt(0)else BigInt(0)->BigInt(2)))
      assert(read(0x1c0,4,burst=0)==Vector.fill(4)(fexpected.head))
      for(i<-0 until 4;strb<-Seq(0,1,15)){write(0x1c0+4*i,strb);assert(read(0x1c0+4*i)==Vector(fexpected(i)))}
      for(a<-Seq(0x1c1,0x1f0,0x5c0)){assert(read(a)==Vector(BigInt(0)->BigInt(2)));write(a,15)}
      val raster=Vector(BigInt("47524901",16),BigInt(8),BigInt(6),BigInt((sampleModes<<8)|0x3eff),
        BigInt("37804001",16),BigInt("33c00000",16),BigInt(3),BigInt(0))
      val rexpected=if(enabled)raster.map(_->BigInt(0))else Vector.fill(8)(BigInt(0)->BigInt(2))
      assert(read(0x1d0,8)==rexpected)
      assert(read(0x1d0,9)==rexpected:+(BigInt(0)->BigInt(2)))
      assert(read(0x1d0,8,burst=0)==Vector.fill(8)(rexpected.head))
      for(i<-0 until 8;strb<-Seq(0,1,15)){write(0x1d0+4*i,strb);assert(read(0x1d0+4*i)==Vector(rexpected(i)))}
      for(a<-Seq(0x1d1,0x1f0,0x5d0)){assert(read(a)==Vector(BigInt(0)->BigInt(2)));write(a,15)}
      for(i<-0 until 4;strb<-Seq(0,1,15)){write(0x1b0+4*i,strb);assert(read(0x1b0+4*i)==Vector(expected(i)))}
      assert(read(0x1b0,5)==expected:+(if(enabled)BigInt("47464901",16)->BigInt(0)else BigInt(0)->BigInt(2)))
      for(address<-Seq(0x1b1,0x1f0,0x5b0)){assert(read(address)==Vector(BigInt(0)->BigInt(2)));write(address,15)}
      assert(read(0x1b0,4,burst=0)==Vector.fill(4)(expected.head))
      write(0xf4,15,Vector(11,22,33).map(BigInt(_)),response=0)
      assert(read(0xf4,3)==Vector(11,22,33).map(n=>BigInt(n)->BigInt(0)))
      write(0xf4,15,Vector(44,55,66).map(BigInt(_)),burst=0,response=0)
      assert(read(0xf4,3)==Vector(66,22,33).map(n=>BigInt(n)->BigInt(0)))
      assert(read(0xf4,3,burst=0)==Vector.fill(3)(BigInt(66)->BigInt(0)))
      for(burst<-Seq(2,3)){
        assert(read(0xf4,3,burst)==Vector.fill(3)(BigInt(0)->BigInt(2)))
        write(0xf4,15,Vector.fill(3)(BigInt(99)),burst=burst)
      }
      assert(read(0xf4,3,size=1)==Vector.fill(3)(BigInt(0)->BigInt(2)))
      write(0xf4,15,Vector.fill(3)(BigInt(99)),size=1)
      write(0xfffffff0L,15,Vector.fill(65)(BigInt(99)))
      assert(read(0xfffffff0L,65)==Vector.fill(65)(BigInt(0)->BigInt(2)))
      write(0xf4,15,Vector.fill(17)(BigInt(99)),burst=0)
      assert(read(0xf4,17,burst=0)==Vector.fill(17)(BigInt(0)->BigInt(2)))
      assert(read(0xf4,3)==Vector(66,22,33).map(n=>BigInt(n)->BigInt(0)))
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x1b0,4)==expected)
      assert(read(0x1c0,4)==fexpected);assert(read(0x1d0,8)==rexpected)
      d.io.cbMem.req.valid.expect(false.B);d.io.fbMem.req.valid.expect(false.B)
      info(s"NATIVE_VERTEX_CAPS enabled=$enabled GVI1=true GFI1=true GRI1=true rasterVersions1And2=true depthFormats5=true explicitResolutionPolicy=true fragmentVersions1To4=true centroid=true sampleCapacity=$samples version2=true formats25=true heldAXIBursts=true readOnlyAllStrobes=true noAddressAliases=true AXIFixed0Incr1=true invalidBurstsNoMutation=true no4GiBWrap=true VMOwnerIndependent=true resetStable=true legacyUnmapped=${!enabled}")
    }
  }
}
