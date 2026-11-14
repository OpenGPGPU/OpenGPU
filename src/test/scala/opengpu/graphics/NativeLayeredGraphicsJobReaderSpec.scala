package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Full native wire capacities; the image/source peer only acknowledges
  * tuples. Actual shader execution and image bytes have separate owners. */
class NativeLayeredGraphicsJobReaderSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import NativeTypedGraphicsLayout.Field
  private val p=VertexShaderStageConfig()
  private class Harness(preserveChildFaultAfterCancel:Boolean=false) extends Module {
    val r=Module(new NativeBinnedGraphicsJobReader(GpuConfig(lanes=2,warps=2),p=p,engines=4,
      virtualImageViews=true,allowWideTileCount=true,preflightDescriptors=true,
      nativeGeometry=true,nativeLayered=true,preserveChildFaultAfterCancel=preserveChildFaultAfterCancel))
    val io=IO(new Bundle {
      val submit=chiselTypeOf(r.io.submit);val begin=chiselTypeOf(r.io.begin)
      val l=Output(chiselTypeOf(r.io.layeredBegin.get));val geometry=Output(chiselTypeOf(r.io.geometry.get))
      val draw=chiselTypeOf(r.io.draw);val end=chiselTypeOf(r.io.end)
      val dc=chiselTypeOf(r.io.drawCompletion);val pc=chiselTypeOf(r.io.passCompletion)
      val out=chiselTypeOf(r.io.completion);val req=chiselTypeOf(r.io.memoryRequest);val reply=chiselTypeOf(r.io.memoryResponse)
      val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
      val cv=Output(new NativeLayeredCompletionView);val allowed=Output(Bool())
    })
    r.io.submit<>io.submit;io.begin<>r.io.begin;io.l:=r.io.layeredBegin.get;io.geometry:=r.io.geometry.get
    io.draw<>r.io.draw;io.end<>r.io.end;r.io.drawCompletion<>io.dc;r.io.passCompletion<>io.pc
    io.out<>r.io.completion;io.req<>r.io.memoryRequest;r.io.memoryResponse<>io.reply
    r.io.cancel:=io.cancel;io.cancelCore:=r.io.cancelCore;io.busy:=r.io.busy
    io.cv:=r.io.completionView.get;io.allowed:=r.io.completionViewValid.get
  }
  private class Driver(val d:Harness){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val jf=NativeLayeredGraphicsLayout.jobFields(new NativeLayeredGraphicsJobRecord)
    val ff=NativeLayeredGraphicsLayout.frameFields(new NativeLayeredGraphicsFrameRecord)
    val df=NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord)
    val sf=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig()))
    val memory=mutable.Map.empty[BigInt,BigInt];val reads=mutable.ArrayBuffer.empty[BigInt]
    val pending=mutable.ArrayBuffer.empty[(Int,Int,BigInt)]
    var response:Option[(Int,BigInt)]=None;var drawReply:Option[Int]=None
    var label="initial";var cycle=0;var begins=0;var draws=0;var ends=0;var holdBegin=false
    var holdDrawReply=false;var holdPass=false;var drawStatus=0;var passStatus=0
    var fault=Set.empty[BigInt];var held:Option[BigInt]=None
    def id(x:GraphicsWorkIdentity):Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
    def ids(prefix:String)=Map[String,BigInt](prefix+"context"->7,prefix+"pass"->8,prefix+"generation"->9)
    def put(base:BigInt,words:Int,magic:BigInt,version:Int,fields:Seq[Field],v:Map[String,BigInt]):Unit={
      for(w<-0 until words)memory(base+4*w)=0
      memory(base)=magic;memory(base+4)=words*4;memory(base+8)=version
      for(f<-fields;i<-0 until f.words)memory(base+4*(f.word+i))=(v.getOrElse(f.name,BigInt(0))>>(32*i))&0xffffffffL
    }
    def set(base:BigInt,fields:Seq[Field],name:String,value:BigInt):Unit={val f=fields.find(_.name==name).get
      for(i<-0 until f.words)memory(base+4*(f.word+i))=(value>>(32*i))&0xffffffffL}
    def prepare():Unit={
      assert(pending.isEmpty && response.isEmpty && drawReply.isEmpty)
      zero(d.io);memory.clear();reads.clear();fault=Set.empty;begins=0;draws=0;ends=0;held=None;holdBegin=false
      holdDrawReply=false;holdPass=false;drawStatus=0;passStatus=0
      val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
      put(0x1000,128,NativeLayeredGraphicsLayout.jobMagic,2,jf,header++ids("job.identity.")++Map[String,BigInt](
        "job.frameBase"->0x2000,"job.frameLimit"->0x2800,"job.drawBase"->0x4000,"job.drawLimit"->0x6800,
        "job.drawCount"->3,"job.drawStride"->4096,"completion.base"->0xc00000,"completion.limit"->0xc04000,
        "completion.allocationBase"->0xc00000,"completion.allocationLimit"->0xc04000,"completion.writable"->1))
      val frame=header++ids("frame.image.pixels.colors.identity.")++ids("frame.states.identity.")++
        ids("frame.bins.producer.arena.identity.")++ids("frame.bins.bins.pool.identity.")++Map[String,BigInt](
          "frame.version"->3,"frame.bins.bins.tileCount"->65536,"frame.bins.bins.version"->3,
          "layered.version"->1,"layered.layers"->256,"layered.sourcePitch[0]"->4096,"layered.resolvePitch[0]"->8192,
          "frame.bins.producer.arena.base"->0x200000,"frame.bins.producer.arena.limit"->0x210000,
          "frame.bins.bins.pool.base"->0x300000,"frame.bins.bins.pool.limit"->0x310000,
          "frame.bins.bins.directoryBase"->0x400000,"frame.bins.bins.directoryLimit"->0x800000,
          "frame.scratchStride"->4096,"frame.states.version"->1,"frame.states.base"->0x810000,"frame.states.limit"->0x810800,
          "frame.image.pixels.colors.colors[0].enabled"->1)++(0 until 4).flatMap(e=>Seq(
            s"frame.scratch[$e].base"->BigInt(0x500000+e*0x10000),s"frame.scratch[$e].limit"->BigInt(0x510000+e*0x10000))).toMap
      // Directory is distinct from all scratch roles; numeric 65536 does not
      // traverse/render a large frame in this metadata component.
      put(0x2000,512,NativeLayeredGraphicsLayout.frameMagic,3,ff,frame++Map("frame.bins.bins.directoryBase"->BigInt(0xd00000),"frame.bins.bins.directoryLimit"->BigInt(0x1100000)))
      for(i<-0 until 3){val gs=i!=0;val h=i+1;val base=BigInt(0x4000+i*4096)
        val values=header++ids("draw.vertex.geometry.vertex.input.identity.")++Map[String,BigInt](
          "draw.version"->1,"draw.state"->h,"draw.token"->(100+i),"draw.vertex.geometry.topology"->(if(gs)12 else 4),
          "draw.vertex.geometry.vertex.version"->1,"draw.vertex.geometry.vertex.shaderPc"->0x9000,
          "draw.vertex.geometry.vertex.attributeSlots"->1,"draw.vertex.geometry.vertex.varyingSlots"->1,"draw.vertex.geometry.vertex.varyingMask"->15,
          "draw.vertex.geometry.vertex.input.version"->1,"draw.vertex.geometry.vertex.input.count"->6,"draw.vertex.geometry.vertex.input.instances"->1,
          "draw.vertex.geometry.vertex.input.attributes[0].enabled"->1,"draw.vertex.geometry.vertex.input.attributes[0].components"->4,
          "draw.vertex.geometry.vertex.input.bindings[0].base"->0x800000,"draw.vertex.geometry.vertex.input.bindings[0].limit"->0x801000,
          "draw.vertex.geometry.vertex.input.bindings[0].allocationBase"->0x800000,"draw.vertex.geometry.vertex.input.bindings[0].allocationLimit"->0x801000,
          "draw.vertex.geometry.vertex.scratch.base"->0x100000,"draw.vertex.geometry.vertex.scratch.limit"->0x110000,
          "draw.vertex.geometry.vertex.scratch.allocationBase"->0x100000,"draw.vertex.geometry.vertex.scratch.allocationLimit"->0x110000,
          "draw.vertex.viewport.version"->1,"draw.raster.version"->1)
        put(base,512,if(gs)NativeGeometryDrawLayout.magic else NativeBinnedGraphicsLayout.drawMagic,if(gs)2 else 1,df,values)
        if(gs){memory(base+508*4)=1;memory(base+509*4)=0xb00000+i*1024;memory(base+510*4)=0xb00000+(i+1)*1024}
        val state=ids("fragment.identity.")++Map[String,BigInt]("version"->1,"engineCount"->4,"tests.version"->1,"blend.version"->1,
          "fragment.version"->(if(gs)5 else 1),"fragment.shaderPc"->0x9000,"fragment.varyingSlots"->(if(gs)2 else 1),
          "fragment.colorSlots"->1,"fragment.colorMask"->1)++(0 until 4).flatMap(e=>{
          val prefix=if(e==0)"fragment.scratch."else s"scratch[${e-1}].";val s=BigInt(0x500000+e*0x10000+h*4096)
          Seq(prefix+"base"->s,prefix+"limit"->(s+1024),prefix+"allocationBase"->s,prefix+"allocationLimit"->(s+1024))}).toMap
        val fs=BigInt(0x810000+h*512);put(fs,128,TypedFragmentStateLayout.magic,1,sf,state)
        for((w,v)<-Seq(3->4,4->7,5->8,6->9,7->h))memory(fs+w*4)=v
        val node=BigInt(0xb00000+i*1024);val w=Array.fill[BigInt](256)(0)
        for((word,value)<-Seq[(Int,BigInt)](0->BigInt("474e4701",16),1->1024,2->1,3->7,4->8,5->9,
          6->6,7->32,8->2,9->256,10->4,11->4,13->1,14->2,15->1,16->0x900004,
          17->0x900000,18->0x900800,20->0x900000,21->0x900800,23->2,24->0xa00000))w(word)=value
        for(k<-0 until 4)w(32+k)=k
        for(k<-0 until 12){w(96+2*k)=1;w(97+2*k)=k%4}
        for(k<-w.indices)memory(node+k*4)=w(k)
      }
      d.io.submit.bits.commandId.poke(23.U);d.io.submit.bits.base.poke(0x1000.U);d.io.submit.bits.limit.poke(0x1200.U);d.io.submit.bits.envelopeValid.poke(true.B)
    }
    def tick():Unit={
      d.io.req.ready.poke((cycle%3!=0).B)
      if(response.isEmpty){val eligible=pending.indices.filter(i=>pending(i)._1<=cycle)
        if(eligible.nonEmpty){val(_,tag,base)=pending.remove(eligible.last);response=Some(tag->base)}}
      d.io.reply.valid.poke(response.nonEmpty.B)
      response.foreach{case(tag,a)=>d.io.reply.bits.transactionId.poke(tag.U);d.io.reply.bits.fault.poke(fault(a).B)
        d.io.reply.bits.readData.poke((0 until 16).foldLeft(BigInt(0))((x,w)=>x|(memory.getOrElse(a+w*4,BigInt(0))<<(w*32))).U)}
      d.io.begin.ready.poke((!holdBegin).B);d.io.draw.ready.poke(true.B);d.io.end.ready.poke(true.B)
      d.io.dc.valid.poke((drawReply.nonEmpty && !holdDrawReply).B);zero(d.io.dc.bits);id(d.io.dc.bits.identity)
      d.io.dc.bits.status.poke(drawStatus.U)
      d.io.dc.bits.source.source.source.source.faultAddress.poke(0x11112222L.U)
      drawReply.foreach(i=>d.io.dc.bits.ordinal.poke(i.U))
      val cancelled=d.io.cancelCore.peek().litToBoolean
      d.io.pc.valid.poke(((ends>0 || cancelled) && drawReply.isEmpty && !holdPass).B);zero(d.io.pc.bits);id(d.io.pc.bits.identity)
      d.io.pc.bits.status.poke((if(passStatus!=0)passStatus else if(cancelled)4 else 0).U)
      d.io.pc.bits.source.sourceFaultValid.poke((passStatus!=0).B)
      d.io.pc.bits.source.sourceFault.source.source.source.faultAddress.poke(0x33334444L.U)
      if(d.io.req.valid.peek().litToBoolean && d.io.req.ready.peek().litToBoolean){val q=d.io.req.bits
        val a=q.address.peek().litValue;val tag=q.transactionId.peek().litValue.toInt
        assert(!pending.exists(_._2==tag) && !response.exists(_._1==tag));q.isWrite.expect(false.B)
        reads+=a;pending+=((cycle+9-tag%4,tag,a))}
      if(response.nonEmpty && d.io.reply.ready.peek().litToBoolean)response=None
      if(d.io.begin.valid.peek().litToBoolean){val v=d.io.l.peek().litValue;held.foreach(h=>assert(h==v));held=Some(v)
        if(d.io.begin.ready.peek().litToBoolean){begins+=1;held=None}}
      if(d.io.dc.valid.peek().litToBoolean && d.io.dc.ready.peek().litToBoolean)drawReply=None
      if(d.io.draw.valid.peek().litToBoolean && d.io.draw.ready.peek().litToBoolean){assert(drawReply.isEmpty)
        d.io.geometry.valid.expect((draws!=0).B);drawReply=Some(draws);draws+=1}
      if(d.io.end.valid.peek().litToBoolean && d.io.end.ready.peek().litToBoolean)ends+=1
      d.clock.step();cycle+=1
    }
    def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<20000){tick();n+=1};assert(condition,label)}
    def submit():Unit={d.io.submit.valid.poke(true.B);until("submit")(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)}
    def complete(status:Int,expectedBegins:Int=0):Unit={until(s"completion status $status")(d.io.out.valid.peek().litToBoolean)
      assert(d.io.out.bits.status.peek().litValue==status,s"$label status ${d.io.out.bits.status.peek().litValue}, expected $status");assert(begins==expectedBegins,s"$label begins $begins")
      val held=d.io.out.bits.peek().litValue;for(_<-0 until 7){tick();assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);assert(pending.isEmpty && response.isEmpty);d.io.busy.expect(false.B)}
  }
  it should "keep an acknowledged owned-child fault after cancellation only in the explicit feedback route" in {
    for(enabled<-Seq(false,true))simulate(new Harness(enabled)){d=>val t=new Driver(d)
      for(drawFault<-Seq(true,false)){
        t.prepare();t.holdDrawReply=true;t.holdPass=true;t.submit()
        t.until("owned original draw")(t.draws==1)
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        for(_<-0 until 7)t.tick()
        t.drawStatus=if(drawFault)3 else 0;t.holdDrawReply=false
        t.until("original draw reply drain")(t.drawReply.isEmpty)
        t.passStatus=3;t.holdPass=false
        t.until("coarse fault priority result")(d.io.out.valid.peek().litToBoolean)
        if(enabled){d.io.out.bits.status.expect(3.U);d.io.out.bits.faultStage.expect(3.U)
          d.io.out.bits.faultAddress.expect((if(drawFault)0x11112222L else 0x33334444L).U)}
        t.complete(if(enabled)3 else 4,1)
      }
      t.prepare();t.holdPass=true;t.drawStatus=1;t.submit();t.until("first source fault")(t.draws==1)
      t.until("first source reply")(t.drawReply.isEmpty)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.passStatus=3;t.holdPass=false;t.complete(1,1)
      t.prepare();t.submit();t.complete(0,1)
    }
  }
  it should "preflight mixed GND1 GND2 and all GFS before granting GNY2 ownership and preserve all layer metadata" in {
    simulate(new Harness){d=>val t=new Driver(d)
      t.prepare();t.holdBegin=true;t.submit();t.until("all-reference preflight")(d.io.begin.valid.peek().litToBoolean)
      assert(t.begins==0 && t.draws==0);d.io.allowed.expect(false.B)
      d.io.l.image.layers.expect(256.U);d.io.l.bins.bins.tileCount.expect(65536.U)
      d.io.l.image.sourcePitch(0).expect(4096.U);d.io.l.image.resolvePitch(0).expect(8192.U)
      for(i<-1 to 2){assert(t.reads.contains(BigInt(0xb00000+i*1024+960)));assert(t.reads.contains(BigInt(0x810000+(i+1)*512+448)))}
      val held=d.io.l.peek().litValue;for(_<-0 until 11){t.tick();assert(d.io.l.peek().litValue==held);d.io.allowed.expect(false.B)}
      t.holdBegin=false;t.tick();d.io.allowed.expect(true.B);d.io.cv.base.expect(0xc00000.U);t.complete(0,1);assert(t.draws==3)
      for((word,value)<-Seq(0->BigInt("474e4a01",16),2->BigInt(1),48->BigInt(0xc00001),49->BigInt(0xc03fff),50->BigInt(2),54->BigInt(0))){
        t.prepare();t.label=s"job word $word=$value";t.memory(0x1000+word*4)=value;t.submit();t.complete(1);assert(t.draws==0);d.io.allowed.expect(false.B)}
      for(base<-Seq[BigInt](0x1000,0x2000,0x4000,0x100000,0x200000,0x500000,0x810000,0x800000,0x900000,0xa00000)){
        t.prepare();t.label=s"completion alias $base";t.set(0x1000,t.jf,"completion.base",base);t.set(0x1000,t.jf,"completion.limit",base+16384)
        t.set(0x1000,t.jf,"completion.allocationBase",base);t.set(0x1000,t.jf,"completion.allocationLimit",base+16384)
        t.submit();t.complete(1);assert(t.draws==0);d.io.allowed.expect(false.B)
      }
      for((word,value)<-Seq(0->BigInt("474e4601",16),2->BigInt(1),321->BigInt(0),321->BigInt(257))){
        t.prepare();t.label=s"frame word $word=$value";t.memory(0x2000+word*4)=value;t.submit();t.complete(1);assert(t.draws==0)}
      t.prepare();t.label="last GNG bad magic";t.memory(0xb00800)=0;t.submit();t.complete(1);assert(t.draws==0)
      t.prepare();t.label="last GFS bad version";t.set(0x810600,t.sf,"fragment.version",0);t.submit();t.complete(1);assert(t.draws==0)
      t.prepare();t.label="last GNG late read fault";t.fault=Set(0xb00800+960);t.submit();t.complete(3);assert(t.draws==0)
      t.prepare();t.holdBegin=true;t.submit();t.until("offered binding before cancel")(d.io.begin.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      val offered=d.io.l.peek().litValue
      for(_<-0 until 7){t.tick();d.io.begin.valid.expect(true.B);assert(d.io.l.peek().litValue==offered);d.io.allowed.expect(false.B)}
      t.holdBegin=false;t.tick();d.io.allowed.expect(true.B);d.io.cv.base.expect(0xc00000.U);t.complete(4,1)
      t.prepare();t.submit();t.complete(0,1);assert(t.draws==3)
    }
  }
}
