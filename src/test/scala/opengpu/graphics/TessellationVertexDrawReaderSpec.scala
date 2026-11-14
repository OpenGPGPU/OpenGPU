package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.WindowRecordTest._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Literal LE GND1 words are independent of the production Field encoder. */
class TessellationVertexDrawReaderSpec extends AnyFlatSpec {
  private val bindings=Seq(
    Seq(182,183,185,186,187,189),Seq(190,192,194,195,196,198),
    Seq(199,200,202,203,204,206),Seq(207,208,210,211,212,214),
    Seq(215,216,218,219,220,222),Seq(223,224,226,227,228,230),
    Seq(231,232,234,235,236,238),Seq(239,240,242,243,244,246),
    Seq(247,248,250,251,252,254),Seq(255,256,258,259,260,262),
    Seq(263,264,266,267,268,270),Seq(271,272,274,275,276,278),
    Seq(279,280,282,283,284,286),Seq(287,288,290,291,292,294),
    Seq(295,296,298,299,300,302),Seq(303,304,306,307,308,310))
  private def wide(w:Array[BigInt],at:Int,value:BigInt):Unit={w(at)=value&BigInt("ffffffff",16);w(at+1)=value>>32}
  private def scratch(w:Array[BigInt],base:BigInt,bytes:BigInt=136*128*4):Unit={
    w(336)=base;wide(w,337,base+bytes);w(339)=base;wide(w,340,base+bytes)
  }
  private def record():Array[BigInt]={
    val w=Array.fill(512)(BigInt(0))
    Seq(BigInt("474e4401",16),BigInt(2048),BigInt(1),BigInt(16),BigInt(16),BigInt(16),BigInt(4),BigInt(4))
      .zipWithIndex.foreach{case(v,i)=>w(i)=v}
    w(29)=3;w(30)=19;w(31)=1;w(35)=16
    for(i<-0 until 16){
      val a=36+9*i;w(a)=i;w(a+1)=4
      for(c<-0 until 4)w(a+2+c)=BigInt(0x1000+i*4+c)
      w(a+6)=1;w(a+7)=0;w(a+8)=i*4
      val b=bindings(i);val base=BigInt(0x80000+i*0x2000)
      w(b(0))=base;wide(w,b(1),base+4096);w(b(2))=base+64;w(b(3))=i%4
      wide(w,b(4),base+1088);w(b(5))=32+i*4
    }
    w(180)=17;w(181)=BigInt("fffffff9",16);w(311)=20;w(312)=9;w(313)=11
    w(314)=7;w(315)=9;w(316)=8;w(317)=2
    w(318)=0xa4000;wide(w,320,0xa5000);w(322)=0xa4040;wide(w,323,0xa4140)
    w(325)=5;w(327)=2;scratch(w,0x20000)
    w(342)=0x5010;wide(w,343,BigInt("ffffffffffffffff",16));w(345)=16;w(346)=2
    w(506)=1;w(507)=0x6000
    w
  }
  private class Driver(val d:TessellationVertexDrawReader){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def until(done: =>Boolean):Unit={var n=0;while(!done && n<40){d.clock.step();n+=1};assert(done,"GND reader stalled")}
    def request(w:Array[BigInt],base:BigInt=0x4000,privateLimit:BigInt=0x18000):Unit={
      val q=d.io.submit.bits;zero(q)
      q.sourceView.base.poke(0x2000.U);q.sourceView.limit.poke(0x2200.U)
      q.sourceView.allocationBase.poke(0x2000.U);q.sourceView.allocationLimit.poke(0x2400.U)
      q.privateBase.poke(0x1000.U);q.privateLimit.poke(privateLimit.U)
      val r=q.root;r.identity.context.poke(7.U);r.identity.pass.poke(8.U);r.identity.generation.poke(9.U)
      r.sequenceIndex.poke(11.U);r.drawIndex.poke(4.U);r.taskIdBase.poke(100.U);r.patchCount.poke(8.U)
      r.drawBase.poke(base.U);r.drawLimit.poke((base+2048).U)
      r.codeBase.poke(0x5000.U);r.codeLimit.poke(0x5100.U);r.entry.poke(0x5010.U)
      r.sliceVersion.poke(1.U);r.firstOrdinal.poke(3.U);r.patchesPerInstance.poke(4.U)
      r.firstInstance.poke(2.U);r.instanceCount.poke(2.U)
      r.layout.inputVertices.poke(2.U);r.layout.inputComponents.poke(64.U)
      r.layout.outputVertices.poke(32.U);r.layout.outputComponents.poke(60.U);r.layout.patchComponents.poke(120.U)
      r.vertexAbi.poke((w(346)&255).U)
      for(i<-0 until 64){r.components(i).kind.poke(1.U);r.components(i).component.poke(i.U)}
    }
    def begin():Unit={d.io.submit.valid.poke(true.B);until(d.io.submit.ready.peek().litToBoolean)
      d.clock.step();d.io.submit.valid.poke(false.B);zero(d.io.submit.bits)}
    def offer(at:BigInt):Unit={until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect(at.U);d.io.memoryRequest.bits.transactionId.expect(0.U)
      d.io.memoryRequest.bits.isWrite.expect(false.B);d.io.memoryRequest.bits.sizeLog2.expect(6.U)
      d.io.memoryRequest.bits.byteMask.expect(((BigInt(1)<<64)-1).U)}
    def accept():Unit={d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)}
    def reply(w:Array[BigInt],line:Int,fault:Boolean=false):Unit={
      val data=(0 until 16).foldLeft(BigInt(0))((v,i)=>v|(w(16*line+i)<<(32*i)))
      d.io.memoryResponse.bits.readData.poke(data.U);d.io.memoryResponse.bits.fault.poke(fault.B)
      d.io.memoryResponse.valid.poke(true.B);until(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step();d.io.memoryResponse.valid.poke(false.B);zero(d.io.memoryResponse.bits)
    }
    def read(w:Array[BigInt],base:BigInt=0x4000):Unit=for(line<-0 until 32){offer(base+64*line);accept();reply(w,line)}
    def complete(status:Int,bytes:Int=2048):Unit={
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(status.U);d.io.completion.bits.readBytes.expect(bytes.U)
      if(status!=0)assert(d.io.completion.bits.draw.peek().litValue==0)
      val held=d.io.completion.bits.peek().litValue
      d.clock.step(3);assert(d.io.completion.bits.peek().litValue==held);d.io.completion.valid.expect(true.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def original(w:Array[BigInt]):Unit={
      until(d.io.completion.valid.peek().litToBoolean);val v=d.io.completion.bits.draw;val in=v.input
      v.version.expect(w(346).U);v.attributeSlots.expect(16.U);v.varyingSlots.expect(16.U)
      v.varyingMask.expect((w(343)|(w(344)<<32)).U);v.shaderPc.expect(w(342).U)
      in.identity.context.expect(7.U);in.identity.pass.expect(8.U);in.identity.generation.expect(9.U)
      in.count.expect(w(311).U);in.instances.expect(w(325).U);in.firstIndex.expect(w(312).U);in.firstVertex.expect(w(313).U)
      in.baseVertex.expect((-7).S);in.baseInstance.expect(17.U);in.indexFormat.expect(w(317).U);in.version.expect(2.U)
      for(i<-0 until 16){
        val a=in.attributes(i);a.enabled.expect(true.B);a.bindingIndex.expect(i.U);a.components.expect(4.U);a.format.expect(0.U);a.offset.expect((i*4).U)
        for(c<-0 until 4)a.constant(c).expect((0x1000+i*4+c).U)
        val b=in.bindings(i);val at=bindings(i)
        b.allocationBase.expect(w(at(0)).U);b.allocationLimit.expect((w(at(1))|(w(at(1)+1)<<32)).U)
        b.base.expect(w(at(2)).U);b.divisor.expect(w(at(3)).U);b.limit.expect((w(at(4))|(w(at(4)+1)<<32)).U);b.stride.expect(w(at(5)).U)
      }
      in.indices.allocationBase.expect(w(318).U);in.indices.allocationLimit.expect((w(320)|(w(321)<<32)).U)
      in.indices.base.expect(w(322).U);in.indices.limit.expect((w(323)|(w(324)<<32)).U)
      v.scratch.allocationBase.expect(w(336).U);v.scratch.allocationLimit.expect((w(337)|(w(338)<<32)).U)
      v.scratch.base.expect(w(339).U);v.scratch.limit.expect((w(340)|(w(341)<<32)).U)
      v.uniforms.version.expect(w(506).U);v.uniforms.address.expect(w(507).U)
      assert(v.sampler.peek().litValue==0 && v.resources.peek().litValue==0)
      v.reuseOutputs.expect(false.B);in.restart.expect(false.B)
    }
  }
  "Original GND vertex reader" should "preserve full original indexed and instanced views over 32 lines and wide endpoints" in {
    simulate(new TessellationVertexDrawReader(GpuConfig())){d=>
      val x=new Driver(d);val w=record();x.request(w);x.begin();x.read(w);x.original(w);x.complete(0)
      val high=BigInt("fffff800",16)
      x.request(w,base=high,privateLimit=BigInt(1)<<32);x.begin();x.read(w,high);x.original(w);x.complete(0)
      val rx=record();rx(342)=BigInt("ffffff10",16);x.request(rx,privateLimit=BigInt(1)<<32)
      d.io.submit.bits.root.codeBase.poke(BigInt("ffffff00",16).U);d.io.submit.bits.root.codeLimit.poke((BigInt(1)<<32).U)
      d.io.submit.bits.root.entry.poke(rx(342).U);x.begin();x.read(rx);x.original(rx);x.complete(0)
      val arrays=record();Seq(317,318,320,321,322,323,324).foreach(i=>arrays(i)=0)
      x.request(arrays);x.begin();x.read(arrays);x.original(arrays);x.complete(0)
      val v1=record();v1(346)=1;x.request(v1);x.begin();x.read(v1);x.original(v1);x.complete(0)
      x.request(w);d.io.submit.bits.root.components(0).kind.poke(2.U);d.io.submit.bits.root.components(0).component.poke(0.U)
      x.begin();x.read(w);x.original(w);x.complete(0)
    }
  }
  it should "reject canonical, source, layout, complete capacity and metadata alias failures before exposing a draw" in {
    simulate(new TessellationVertexDrawReader(GpuConfig())){d=>
      val x=new Driver(d)
      val mutations=Seq[Array[BigInt]=>Unit](
        w=>w(0)=BigInt("474e4402",16),w=>w(2)=2,w=>w(3)=8,w=>w(15)=1,
        w=>w(319)=1,w=>w(338)=2,w=>w(37)=12,w=>w(329)=32,
        w=>w(314)=8,w=>w(315)=8,w=>w(316)=9,w=>w(346)=3,w=>w(342)=0x5014,
        w=>w(311)=10,w=>w(325)=3,w=>w(343)=BigInt("fffffffe",16),w=>w(344)=BigInt("7fffffff",16),
        w=>w(345)=15,w=>w(326)=1,w=>w(498)=1,w=>w(496)=1,w=>w(497)=0x6000,
        w=>w(499)=1,w=>w(500)=0x80000,w=>w(506)=3,w=>w(507)=0x1ffc0,
        w=>{w(340)=0x20000+136*64*4},w=>w(339)=0x20004,
        w=>w(35)=15,w=>w(43)=31,w=>{val b=bindings(0);w(b(0))=0x20000;wide(w,b(1),0x21000);w(b(2))=0x20040;wide(w,b(4),0x20440)})
      for(mutate<-mutations){val w=record();mutate(w);x.request(w);x.begin();x.read(w);x.complete(1)}
      for(field<-(328 to 335)++(480 to 483)){val w=record();w(field)=1;x.request(w);x.begin();x.read(w);x.complete(1)}
      x.request(record());d.io.submit.bits.root.vertexAbi.poke(1.U);x.begin();x.read(record());x.complete(1)
      for(which<-0 until 4){
        val w=record();x.request(w,privateLimit=0x40000)
        val q=d.io.submit.bits
        if(which==0){q.sourceView.base.poke(0x20000.U);q.sourceView.limit.poke(0x20200.U)
          q.sourceView.allocationBase.poke(0x20000.U);q.sourceView.allocationLimit.poke(0x20400.U)}
        if(which==1){q.root.drawBase.poke(0x20000.U);q.root.drawLimit.poke(0x20800.U)}
        if(which==2){q.root.codeBase.poke(0x20000.U);q.root.codeLimit.poke(0x20100.U);q.root.entry.poke(0x20010.U);w(342)=0x20010}
        if(which==3)w(507)=0x20000
        x.begin();x.read(w,if(which==1)BigInt(0x20000)else BigInt(0x4000));x.complete(1)
      }
      // Root/request bounds fail without making even the first line offer.
      for(which<-0 until 8){val w=record();x.request(w)
        val q=d.io.submit.bits
        which match{
          case 0=>q.sourceView.limit.poke(0x2240.U)
          case 1=>q.root.drawLimit.poke(0x4840.U)
          case 2=>q.root.drawBase.poke(0x4004.U)
          case 3=>q.root.drawBase.poke(BigInt("fffffc00",16).U);q.root.drawLimit.poke((BigInt(1)<<32).U)
          case 4=>q.privateLimit.poke(0x4400.U)
          case 5=>q.root.codeLimit.poke(0x5804.U)
          case 6=>q.root.entry.poke(0x5100.U)
          case 7=>q.privateBase.poke(0x1004.U)
        };x.begin();d.io.memoryRequest.valid.expect(false.B);x.complete(1,0)
      }
      val empty=record();empty(311)=0;empty(325)=0;x.request(empty)
      Seq(d.io.submit.bits.root.patchCount,d.io.submit.bits.root.firstOrdinal,d.io.submit.bits.root.patchesPerInstance,
        d.io.submit.bits.root.firstInstance,d.io.submit.bits.root.instanceCount).foreach(_.poke(0.U))
      x.begin();x.read(empty);x.complete(0)
      scratch(empty,0,0);x.request(empty)
      Seq(d.io.submit.bits.root.patchCount,d.io.submit.bits.root.firstOrdinal,d.io.submit.bits.root.patchesPerInstance,
        d.io.submit.bits.root.firstInstance,d.io.submit.bits.root.instanceCount).foreach(_.poke(0.U))
      x.begin();x.read(empty);x.complete(1)
    }
  }
  it should "retain held offers, drain real responses on cancel, prioritize late faults and reuse without reset" in {
    simulate(new TessellationVertexDrawReader(GpuConfig())){d=>
      val x=new Driver(d);val w=record();x.request(w);x.begin();x.offer(0x4000)
      val held=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.clock.step(3)
      d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held)
      d.io.completion.valid.expect(false.B);x.accept();d.clock.step(3);d.io.completion.valid.expect(false.B)
      x.reply(w,0);x.complete(4,64)
      x.request(w);x.begin()
      for(line<-0 until 31){x.offer(0x4000+64*line);x.accept();x.reply(w,line)}
      x.offer(0x47c0);x.accept();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      x.reply(w,31,fault=true);x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultAddress.expect(0x47c0.U);x.complete(2)
      x.request(w);x.begin();x.read(w);x.original(w);x.complete(0)
    }
  }
}
