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
  for((enabled,external,lanes,warps,queries,limits)<-Seq((true,true,4,2,false,false),(true,true,4,2,true,false),(true,true,16,8,true,false),(false,true,4,2,false,false),(false,false,4,2,false,false),(true,true,16,8,true,true)))
  it should s"discover resource texture operations with immutable bounded AXI access native=$enabled external=$external lanes=$lanes warps=$warps queries=$queries limits=$limits" in {
    simulate(new GpuHostAxi(config=GraphicsConfig(),gpuConfig=GpuConfig(lanes=lanes,warps=warps),
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=external,
      nativeCompletionInfo=enabled,nativeVertexInput=enabled,
      visibilityQueries=queries,transformFeedback=queries,primitiveQueries=queries,
      shaderExecutionLimits=limits)){d=>
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
      assert(read(0x2c0,16)==expected)
      val afterOperations=if(limits)BigInt("47534c01",16)->BigInt(0)else BigInt(0)->BigInt(2)
      assert(read(0x2c0,17)==expected:+afterOperations)
      assert(read(0x2c0,16,burst=0)==Vector.fill(16)(expected.head))
      val boundary=if(enabled)Vector(BigInt(0)->BigInt(2),words.head->BigInt(0))else Vector.fill(2)(BigInt(0)->BigInt(2))
      assert(read(0x2bc,2)==boundary)
      for(i<-0 until 16;strb<-Seq(0,1,15)){
        write(0x2c0+4*i,strb);assert(read(0x2c0+4*i)==Vector(expected(i)))
      }
      write(0x2c0,15,Vector.fill(16)(BigInt("12345678",16)))
      assert(read(0x2c0,16)==expected)
      for(address<-Seq(0x2c1,0x6c0,0x12c0)++(if(limits)Seq.empty else Seq(0x300))){
        assert(read(address)==Vector(BigInt(0)->BigInt(2)));write(address,15)
      }
      for(burst<-Seq(2,3)){assert(read(0x2c0,4,burst=burst)==Vector.fill(4)(BigInt(0)->BigInt(2)));write(0x2c0,15,Vector.fill(4)(BigInt(0)),burst=burst)}
      for(size<-Seq(0,1,3)){assert(read(0x2c0,size=size)==Vector(BigInt(0)->BigInt(2)));write(0x2c0,15,size=size)}
      assert(read(0x2c0,17,burst=0)==Vector.fill(17)(BigInt(0)->BigInt(2)))
      assert(read(0xfffffff0L,65)==Vector.fill(65)(BigInt(0)->BigInt(2)))
      assert(read(0x2c0,16)==expected)
      // Independent literals prove GTO1 cannot shadow any co-enabled query block.
      val queryWords=Vector[BigInt](BigInt("47515601",16),8,BigInt("0b0a0908",16),BigInt("47514f01",16),64,8,BigInt("01001001",16),31,
        BigInt("47464301",16),8,BigInt("1003010c",16),BigInt("47544601",16),512,BigInt("04400408",16),69,255,
        BigInt("47515001",16),8,BigInt("0010040d",16),BigInt("47504f01",16),64,BigInt("00204008",16),0x8c88,63)
      val queryExpected=if(queries)queryWords.map(_->BigInt(0))else Vector.fill(24)(BigInt(0)->BigInt(2))
      assert(read(0x240,24)==queryExpected)
      for(i<-queryWords.indices;strb<-Seq(0,1,15))write(0x240+i*4,strb)
      assert(read(0x240,24)==queryExpected)
      assert(read(0x2a0,8)==Vector.fill(8)(BigInt(0)->BigInt(2)))
      assert(read(0x2c0,16)==expected)
      if(limits){
        // The next word belongs to GSL1, not an unmapped GTO1 overflow. Keep
        // the independent literal contract across held reads and every strobe.
        val gsl=Vector[BigInt](BigInt("47534c01",16),8,31,32,1048576,BigInt("04051918",16),0x320,0x138)
        assert(read(0x300,8)==gsl.map(_->BigInt(0)))
        for(i<-gsl.indices;strb<-Seq(0,1,15))write(0x300+i*4,strb)
        assert(read(0x300,8)==gsl.map(_->BigInt(0)))
        write(0x320,15,Vector(BigInt(17))) // busy VM still owns quota policy
        assert(read(0x320)==Vector(BigInt(1048576)->BigInt(0)))
        d.io.vmContextIdle.get.poke(true.B)
        write(0x320,1,Vector(BigInt(0x55)),response=0)
        assert(read(0x320)==Vector(BigInt(0x100055)->BigInt(0)))
        write(0x320,4,Vector(BigInt(0)),response=0)
        assert(read(0x320)==Vector(BigInt(0x55)->BigInt(0)))
        write(0x320,1,Vector(BigInt(0))) // partial write cannot create zero
        assert(read(0x320)==Vector(BigInt(0x55)->BigInt(0)))
        assert(read(0x2c0,24)==expected++gsl.map(_->BigInt(0)))
        assert(read(0x240,24)==queryExpected)
        d.io.vmContextIdle.get.poke(false.B)
      }
      // Read-only discovery remains available while the VM owner is busy.
      assert(read(0x14c)==Vector(BigInt(0)->BigInt(0)))
      d.io.s_axi_aresetn.poke(false.B);d.clock.step(3);d.io.s_axi_aresetn.poke(true.B)
      assert(read(0x2c0,16)==expected)
      d.io.cbMem.req.valid.expect(false.B);d.io.fbMem.req.valid.expect(false.B)
      info(s"NATIVE_TEXTURE_OPERATION_AXI native=$enabled external=$external lanes=$lanes warps=$warps GTO1=true base2c0=true queryBlocksDisjoint=$queries GSLcoenabled=$limits words16=true heldBursts=true readOnlyAllStrobes=true noAliases=true boundedMap=true legacyUnmapped=${!enabled} busyVMReadable=true resetStable=true noGPUReads=true")
    }
  }
}
