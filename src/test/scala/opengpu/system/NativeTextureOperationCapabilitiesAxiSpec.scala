package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeTextureOperationCapabilitiesAxiSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  for((enabled,external,lanes,warps)<-Seq((true,true,4,2),(true,true,16,8),(false,true,4,2),(false,false,4,2)))
  it should s"discover resource texture operations with immutable bounded AXI access native=$enabled external=$external lanes=$lanes warps=$warps" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(),gpuConfig=GpuConfig(lanes=lanes,warps=warps),
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=external,
      nativeCompletionInfo=enabled,nativeVertexInput=enabled)){d=>
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
      // Independent literal contract,not the RTL table encoder.
      val words=Vector[BigInt](BigInt("47544f01",16),16,1,0x7c,0x7c,0xff,15,7,7,3,32,16,15,64,0x3f,0x0007fff8)
      val expected=if(enabled)words.map(_->BigInt(0))else Vector.fill(16)(BigInt(0)->BigInt(2))
      val discovery=read(0x20c).head
      if(enabled)assert(discovery==(BigInt(0x7ff)->BigInt(0)) && (discovery._1&1024)!=0)
      else assert(discovery==(BigInt(0)->BigInt(2)))
      assert(read(0x240,16)==expected)
      assert(read(0x240,17)==expected:+(BigInt(0)->BigInt(2)))
      assert(read(0x240,16,burst=0)==Vector.fill(16)(expected.head))
      val boundary=if(enabled)Vector(BigInt(1)->BigInt(0),words.head->BigInt(0))else Vector.fill(2)(BigInt(0)->BigInt(2))
      assert(read(0x23c,2)==boundary)
      for(i<-0 until 16;strb<-Seq(0,1,15)){
        write(0x240+4*i,strb);assert(read(0x240+4*i)==Vector(expected(i)))
      }
      write(0x240,15,Vector.fill(16)(BigInt("12345678",16)))
      assert(read(0x240,16)==expected)
      for(address<-Seq(0x241,0x280,0x640,0x1240)){
        assert(read(address)==Vector(BigInt(0)->BigInt(2)));write(address,15)
      }
      for(burst<-Seq(2,3)){assert(read(0x240,4,burst=burst)==Vector.fill(4)(BigInt(0)->BigInt(2)));write(0x240,15,Vector.fill(4)(BigInt(0)),burst=burst)}
      for(size<-Seq(0,1,3)){assert(read(0x240,size=size)==Vector(BigInt(0)->BigInt(2)));write(0x240,15,size=size)}
      assert(read(0x240,17,burst=0)==Vector.fill(17)(BigInt(0)->BigInt(2)))
      assert(read(0xfffffff0L,65)==Vector.fill(65)(BigInt(0)->BigInt(2)))
      assert(read(0x240,16)==expected)
      // Read-only discovery remains available while the VM owner is busy.
      assert(read(0x14c)==Vector(BigInt(0)->BigInt(0)))
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x240,16)==expected)
      d.io.cbMem.req.valid.expect(false.B);d.io.fbMem.req.valid.expect(false.B)
      info(s"NATIVE_TEXTURE_OPERATION_AXI native=$enabled external=$external lanes=$lanes warps=$warps GTO1=true words16=true heldBursts=true readOnlyAllStrobes=true noAliases=true boundedMap=true legacyUnmapped=${!enabled} busyVMReadable=true resetStable=true noGPUReads=true")
    }
  }
}
