package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeTypedGraphicsJobReaderSpec extends AnyFlatSpec {
  import NativeTypedGraphicsLayout._
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);private val f=FragmentShaderConfig(2,4)
  private def zero(d: Data): Unit=d match{
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private class Driver(val d: NativeTypedGraphicsJobReader){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[Int,BigInt];val requests=mutable.ArrayBuffer.empty[(Int,Int)]
    val pending=mutable.ArrayBuffer.empty[(Int,Int,Int)];var reply: Option[(Int,Int)]=None
    var cycle=0;var blockMemory=false;var blockBegin=false;var blockDraw=false;var blockEnd=false;var holdPass=false
    var faultAt=Set.empty[Int];var begun=false;var ended=false;var cancelled=false;var passOffered=false
    var drawReply: Option[(Int,Int)]=None;var drawOffered=false;var drawOrdinal=0
    var acceptedBegins=0;var acceptedDraws=0;var acceptedEnds=0;var peak=0;var reorder=false;var lastReplyAddress=0
    var heldMemory: Option[BigInt]=None;var heldBegin: Option[BigInt]=None;var heldDraw: Option[BigInt]=None;var heldEnd=false
    var passValues=Map.empty[String,BigInt];var drawValues=Map.empty[String,BigInt]
    def put(base: Int,words: Int,magic: BigInt,fs: Seq[Field],values: Map[String,BigInt]): Unit={
      for(w<-0 until words)memory(base+w*4)=0
      memory(base)=magic;memory(base+4)=words*4;memory(base+8)=1
      for(field<-fs){val bits=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory(base+(field.word+w)*4)=(bits>>(32*w))&0xffffffffL}
    }
    def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
    def prepare(count: Int=2): Unit={
      assert(pending.isEmpty && reply.isEmpty && drawReply.isEmpty && !passOffered)
      zero(d.io);memory.clear();requests.clear();drawOrdinal=0;acceptedBegins=0;acceptedDraws=0;acceptedEnds=0
      begun=false;ended=false;cancelled=false;peak=0;reorder=false;lastReplyAddress=0
      heldMemory=None;heldBegin=None;heldDraw=None;heldEnd=false
      val j=Vector[BigInt](jobMagic,jobBytes,1,7,8,9,0x2000,0x2000+passBytes,0,0x4000,0x4000+(count-1).max(0)*4096+drawBytes,0,count,4096)
      for(w<-0 until jobWords)memory(0x1000+w*4)=j.lift(w).getOrElse(BigInt(0))
      passValues=Map("version"->1,"pixelVersion"->1,"colorVersion"->1,"identity.context"->7,"identity.pass"->8,"identity.generation"->9,
        "tileLog2"->3,"tileWidth"->4,"tileHeight"->4,"sampleMode"->2,"reservedBytes"->1024,
        "depth.format"->4,"depth.loadOp"->1,"depth.clearDepth"->BigInt("3f000000",16),"depth.clearStencil"->5,
        "color0.enabled"->1,"color0.format"->TypedColorFormat.id("RGBA8"),"color0.offset"->1024,"color0.channelMask"->15,
        "color0.loadOp"->1,"color0.clearR"->BigInt("3e800000",16),"color0.clearA"->BigInt("3f800000",16)).map{case(k,v)=>(k,BigInt(v.toString))}
      put(0x2000,passWords,passMagic,passFields(d.io.begin.bits),passValues)
      drawValues=Map[String,BigInt]("version"->1,"shaderVersion"->1,"topology"->4,"flatMask"->8,"vertex.version"->2,"vertex.pc"->0x9000,
        "vertex.attributeSlots"->1,"vertex.varyingSlots"->1,"vertex.varyingMask"->15,"vertex.sampler.bias"-> -5,
        "vertex.scratch.base"->0x100000,"vertex.scratch.limit"->0x110000,"vertex.scratch.allocationBase"->0x100000,"vertex.scratch.allocationLimit"->0x110000,
        "input.version"->1,"input.identity.context"->7,"input.identity.pass"->8,"input.identity.generation"->9,"input.count"->12,
        "input.instances"->2,"input.baseVertex"-> -17,"input.baseInstance"->3,"input.indexFormat"->2,"input.restart"->1,
        "input.indices.base"->0x7000,"input.indices.limit"->(BigInt(1)<<32),"input.indices.allocationLimit"->(BigInt(1)<<32),
        "attribute0.enabled"->1,"attribute0.components"->4,"attribute0.constantW"->BigInt("3f800000",16),"binding0.stride"->16,"binding0.divisor"->3,
        "viewport.version"->1,"viewport.x"-> -123,"viewport.y"->97,"viewport.extentX"->1280,"viewport.extentY"->720,"viewport.farDepth"->BigInt("3f800000",16),
        "raster.version"->1,"raster.screenW"->1280,"raster.screenH"->720,"raster.frontCCW"->1,"raster.sampleMode"->2,"raster.scissorEnable"->1,
        "raster.scissorMinX"->11,"raster.scissorMaxX"->111,"raster.scissorMaxY"->72,"fragment.version"->2,
        "fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,"fragment.pc"->0xa000,
        "fragment.varyingSlots"->1,"fragment.colorSlots"->4,"fragment.colorMask"->15,"fragment.allowDepthOverride"->1,
        "fragment.sampleMode"->2,"fragment.sampler.bias"-> -3,"tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,
        "fragment.scratch.base"->0x200000,"fragment.scratch.limit"->0x210000,"fragment.scratch.allocationBase"->0x200000,"fragment.scratch.allocationLimit"->0x210000,
        "tests.stencilEnable"->1,"tests.front.function"->6,"tests.back.function"->6,"blend.enable"->1,"blend.version"->1,"blend.srcRGB"->1)
      for(i<-0 until count)put(0x4000+i*4096,drawWords,drawMagic,drawFields(d.io.draw.bits),drawValues)
      d.io.submit.bits.commandId.poke(23.U);d.io.submit.bits.base.poke(0x1000.U);d.io.submit.bits.limit.poke((0x1000+jobBytes).U);d.io.submit.bits.envelopeValid.poke(true.B)
    }
    def check(fs: Seq[Field],values: Map[String,BigInt]): Unit=for(field<-fs){
      val width=field.width;val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<width)-1)
      val expected=field.value match{case _: SInt if raw.testBit(width-1)=>raw-(BigInt(1)<<width);case _=>raw}
      assert(field.value.peek().litValue==expected,s"native field ${field.name} word=${field.word} observed=${field.value.peek().litValue} expected=$expected")
    }
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockMemory && cycle%3!=0).B)
      if(reply.isEmpty){val candidates=pending.indices.filter(i=>pending(i)._1<=cycle)
        if(candidates.nonEmpty){val n=candidates.last;val(_,tag,address)=pending.remove(n);reply=Some((tag,address))}}
      d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
      reply.foreach{case(tag,address)=>d.io.memoryResponse.bits.transactionId.poke(tag.U)
        d.io.memoryResponse.bits.fault.poke(faultAt(address).B)
        val data=(0 until 16).foldLeft(BigInt(0))((v,w)=>v|(memory.getOrElse(address+w*4,BigInt(0))<<(w*32)))
        d.io.memoryResponse.bits.readData.poke(data.U)}
      d.io.begin.ready.poke((!blockBegin && cycle%4!=0).B);d.io.draw.ready.poke((!blockDraw && cycle%4!=1).B);d.io.end.ready.poke((!blockEnd && cycle%4!=2).B)
      if(d.io.cancelCore.peek().litToBoolean)cancelled=true
      if(!drawOffered && drawReply.exists(_._1<=cycle))drawOffered=true
      d.io.drawCompletion.valid.poke(drawOffered.B)
      if(drawOffered){zero(d.io.drawCompletion.bits);identity(d.io.drawCompletion.bits.identity)
        d.io.drawCompletion.bits.ordinal.poke(drawReply.get._2.U);d.io.drawCompletion.bits.status.poke((if(cancelled)4 else 0).U)}
      if(!passOffered && begun && (ended || cancelled) && drawReply.isEmpty && !holdPass)passOffered=true
      d.io.passCompletion.valid.poke(passOffered.B)
      if(passOffered){zero(d.io.passCompletion.bits);identity(d.io.passCompletion.bits.identity)
        d.io.passCompletion.bits.status.poke((if(cancelled)4 else 0).U);d.io.passCompletion.bits.renderer.draws.poke(acceptedDraws.U)}
      if(d.io.memoryRequest.valid.peek().litToBoolean){val bits=d.io.memoryRequest.bits.peek().litValue
        heldMemory.foreach(v=>assert(v==bits));heldMemory=Some(bits)
        if(d.io.memoryRequest.ready.peek().litToBoolean){val a=d.io.memoryRequest.bits.address.peek().litValue.toInt;val tag=d.io.memoryRequest.bits.transactionId.peek().litValue.toInt
          assert(!pending.exists(_._2==tag) && !reply.exists(_._1==tag));requests+=((tag,a));pending+=((cycle+14-3*tag,tag,a));heldMemory=None}}
      if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val address=reply.get._2;if(address<lastReplyAddress)reorder=true;lastReplyAddress=address;reply=None}
      def stable(valid: Boolean,bits: => BigInt,old: Option[BigInt]): Option[BigInt]={if(valid){val b=bits;old.foreach(v=>assert(v==b));Some(b)}else None}
      heldBegin=stable(d.io.begin.valid.peek().litToBoolean,d.io.begin.bits.peek().litValue,heldBegin)
      heldDraw=stable(d.io.draw.valid.peek().litToBoolean,d.io.draw.bits.peek().litValue,heldDraw)
      if(d.io.begin.valid.peek().litToBoolean && d.io.begin.ready.peek().litToBoolean){check(passFields(d.io.begin.bits),passValues);begun=true;acceptedBegins+=1;heldBegin=None}
      if(d.io.draw.valid.peek().litToBoolean && d.io.draw.ready.peek().litToBoolean){check(drawFields(d.io.draw.bits),drawValues);acceptedDraws+=1
        assert(drawReply.isEmpty);drawReply=Some((cycle+5,drawOrdinal));drawOrdinal+=1;heldDraw=None}
      if(d.io.end.valid.peek().litToBoolean && d.io.end.ready.peek().litToBoolean){ended=true;acceptedEnds+=1}
      if(drawOffered && d.io.drawCompletion.ready.peek().litToBoolean){drawOffered=false;drawReply=None}
      if(passOffered && d.io.passCompletion.ready.peek().litToBoolean){passOffered=false;begun=false}
      peak=math.max(peak,pending.size+reply.size);assert(peak<=4)
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<20000){tick();n+=1};assert(condition,s"native reader stalled at$cycle requests=${requests.size}")}
    def submit(): Unit={d.io.submit.valid.poke(true.B);until(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)}
    def complete(status: Int,draws: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.commandId.expect(23.U);r.status.expect(status.U)
      r.issuedDraws.expect(draws.U);r.completedDraws.expect(draws.U)
      val drawBase=memory(0x1000+9*4);val count=memory(0x1000+12*4);val stride=memory(0x1000+13*4)
      val drawEnd=drawBase+(count-1).max(BigInt(0))*stride+NativeTypedGraphicsLayout.drawBytes
      val records=requests.count{case(_,a)=>val address=BigInt(a.toLong&0xffffffffL);count>0 && address>=drawBase && address<drawEnd}*64
      val descriptor=requests.size*64-records
      r.descriptorReadBytes.expect(descriptor.U);r.drawReadBytes.expect(records.U)
      assert(pending.isEmpty && reply.isEmpty && !drawOffered && drawReply.isEmpty && !passOffered)
      val bits=r.peek().litValue;for(_<-0 until 7){tick();assert(r.peek().litValue==bits);d.io.busy.expect(true.B);d.io.submit.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def cancel(): Unit={d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)}
  }
  it should "decode fixed native layouts with out of order replies and retain complete pass draw and publication ownership" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d);t.prepare();t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean)
      val held=d.io.begin.bits.peek().litValue;for(_<-0 until 9){t.tick();assert(d.io.begin.bits.peek().litValue==held)}
      t.blockBegin=false;t.holdPass=true;t.until(t.acceptedEnds==1);for(_<-0 until 9){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdPass=false;t.complete(0,2);assert(t.acceptedBegins==1 && t.acceptedEnds==1 && t.peak==4 && t.reorder)
      t.prepare(0);t.submit();t.complete(0)
      info("NATIVE_TYPED_READER layouts=3 fixedSlotABI=true scalarWords=true signedPadding=true limit33=true tags=4 reorderedReplies=true orderedDraws=2 heldBegin=true heldPublication=true immutableCompletion=true clearOnlyPass=true")
    }
  }
  it should "snapshot centroid native words and reject unsupported masks before a child draw offer" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d)
      for(version<-Seq(3,4)){
        t.prepare(1);t.drawValues++=Map("fragment.version"->BigInt(version),"fragment.centroidMask"->BigInt(5))
        t.put(0x4000,drawWords,drawMagic,drawFields(d.io.draw.bits),t.drawValues)
        t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
        d.io.draw.bits.shaders.fragment.version.expect(version.U);d.io.draw.bits.shaders.fragment.centroidMask.expect(5.U)
        val held=d.io.draw.bits.peek().litValue;for(_<-0 until 9){t.tick();assert(held==d.io.draw.bits.peek().litValue)}
        t.blockDraw=false;t.complete(0,1)
      }
      for((version,mask)<-Seq((1,BigInt(1)),(2,BigInt(1)),(3,BigInt(16)),(4,BigInt(1)<<63),(5,BigInt(0)))){
        t.prepare(1);t.drawValues++=Map("fragment.version"->BigInt(version),"fragment.centroidMask"->mask)
        t.put(0x4000,drawWords,drawMagic,drawFields(d.io.draw.bits),t.drawValues);t.submit();t.complete(1)
        assert(t.acceptedDraws==0)
      }
      t.prepare(1);t.submit();t.complete(0,1)
      info("CENTROID_NATIVE_READER fixedWords538And539=true versions3And4=true immutableOffer=true highMaskRejectedBeforeAdapt=true oldVersionsRequireZero=true healthyReuse=true")
    }
  }
  it should "reject malformed bounds encodings and capacity adaptation before issuing invalid child work" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d)
      for(change<-Seq[()=>Unit](()=>d.io.submit.bits.envelopeValid.poke(false.B),()=>d.io.submit.bits.base.poke(0x1004.U),()=>d.io.submit.bits.limit.poke(0x11ff.U))){
        t.prepare();change();t.submit();t.complete(1);assert(t.requests.isEmpty && t.acceptedBegins==0)}
      val changes=Seq[(Driver=>Unit,Boolean)](
        ((x: Driver)=>x.memory(0x1000)=0,false),((x: Driver)=>x.memory(0x1000+127*4)=1,false),
        ((x: Driver)=>x.memory(0x1000+7*4)=0x2001,false),((x: Driver)=>x.memory(0x1000+13*4)=64,false),
        ((x: Driver)=>x.memory(0x1000+10*4)=0x4001,false),((x: Driver)=>x.memory(0x2000+16*4)=257,false),
        ((x: Driver)=>x.memory(0x4000+(96+15*16)*4)=1,true),((x: Driver)=>x.memory(0x4000+37*4)=1,true),
        ((x: Driver)=>x.memory(0x4000+(48+7)*4)=27,true),((x: Driver)=>x.memory(0x4000+639*4)=1,true),
        ((x: Driver)=>x.memory(0x4000+40*4)=0x1000,true),((x: Driver)=>x.memory(0x4000+520*4)=0x5000,true))
      for((change,began)<-changes){t.prepare();change(t);t.submit();t.complete(1);assert((t.acceptedBegins==1)==began && t.acceptedDraws==0)}
      t.prepare(1);t.submit();t.complete(0,1)
      info("NATIVE_TYPED_READER invalidDescriptors=15 noOutOfBoundsRead=true noMalformedDraw=true reservedAndSignPadding=true noTruncatedCapacity=true scratchCannotOverwriteCommands=true healthyReuse=true")
    }
  }
  it should "drain held descriptor offers child controls and physical publication after cancellation or memory faults" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d)
      for(address<-Seq(0x1000,0x2040,0x5000+64)){t.prepare();t.faultAt=Set(address);t.submit();t.complete(3,if(address>=0x5000)1 else 0);t.faultAt=Set.empty}
      t.prepare();t.blockMemory=true;t.submit();t.until(d.io.memoryRequest.valid.peek().litToBoolean);val request=d.io.memoryRequest.bits.peek().litValue;t.cancel()
      for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==request);d.io.completion.valid.expect(false.B)}
      t.blockMemory=false;t.complete(4)
      t.prepare();t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean);t.cancel();for(_<-0 until 5){t.tick();d.io.begin.valid.expect(true.B);d.io.cancelCore.expect(false.B)}
      t.blockBegin=false;t.complete(4)
      t.prepare();t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean);t.cancel();for(_<-0 until 5){t.tick();d.io.draw.valid.expect(true.B);d.io.cancelCore.expect(false.B)}
      t.blockDraw=false;t.complete(4,1)
      t.prepare(1);t.blockEnd=true;t.submit();t.until(d.io.end.valid.peek().litToBoolean);t.cancel();for(_<-0 until 5){t.tick();d.io.end.valid.expect(true.B);d.io.cancelCore.expect(false.B)}
      t.blockEnd=false;t.complete(4,1)
      t.prepare(1);t.submit();t.complete(0,1)
      info("NATIVE_TYPED_READER readFaults=3 acknowledgedPrefixes=true heldMemoryCancel=true heldBeginCancel=true heldDrawCancel=true heldEndCancel=true noEarlyVMRelease=true healthyReuse=true")
    }
  }
  it should "read each native record through the highest physical bytes with a four GiB exclusive limit" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d)
      def relocate(old: Int,words: Int): BigInt={val base=(BigInt(1)<<32)-words*4
        for(w<-0 until words)t.memory((base+w*4).toInt)=t.memory(old+w*4)
        base}
      t.prepare(0);val header=relocate(0x1000,jobWords)
      d.io.submit.bits.base.poke(header.U);d.io.submit.bits.limit.poke((BigInt(1)<<32).U)
      t.submit();t.complete(0);assert(t.requests.exists(_._2== -64))
      t.prepare(0);val pass=relocate(0x2000,passWords)
      t.memory(0x1000+6*4)=pass;t.memory(0x1000+7*4)=0;t.memory(0x1000+8*4)=1
      t.submit();t.complete(0);assert(t.requests.exists(_._2== -64))
      t.prepare(1);val draw=relocate(0x4000,drawWords)
      t.memory(0x1000+9*4)=draw;t.memory(0x1000+10*4)=0;t.memory(0x1000+11*4)=1
      t.submit();t.complete(0,1);assert(t.requests.exists(_._2== -64))
      info("NATIVE_TYPED_READER highestRecords=3 headerPassDraw=true exclusive4GiB=true noAddressWrap=true countersClassifyHighDraws=true")
    }
  }

  it should "snapshot versioned native sampler policies without shifting legacy words and reject malformed policies" in {
    simulate(new NativeTypedGraphicsJobReader(p=p,f=f)){d=>val t=new Driver(d)
      val fs=drawFields(d.io.draw.bits).map(x=>x.name->x.word).toMap
      for((prefix,base)<-Seq("vertex.sampler."->56,"fragment.sampler."->552)){
        assert(fs(prefix+"policyVersion")==base && fs(prefix+"wrapU")==base+1 && fs(prefix+"wrapV")==base+2 && fs(prefix+"signedCoordinates")==base+3)
      }
      t.prepare(1);t.drawValues++=Map("vertex.sampler.policyVersion"->BigInt(1),"vertex.sampler.wrapU"->BigInt(2),
        "vertex.sampler.wrapV"->BigInt(1),"vertex.sampler.signedCoordinates"->BigInt(1),
        "fragment.sampler.policyVersion"->BigInt(1),"fragment.sampler.wrapU"->BigInt(0),"fragment.sampler.wrapV"->BigInt(2))
      t.put(0x4000,drawWords,drawMagic,drawFields(d.io.draw.bits),t.drawValues)
      t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      val held=d.io.draw.bits.peek().litValue;t.check(drawFields(d.io.draw.bits),t.drawValues)
      t.put(0x4000,drawWords,drawMagic,drawFields(d.io.draw.bits),Map.empty)
      for(_<-0 until 9){t.tick();assert(d.io.draw.bits.peek().litValue==held)}
      t.blockDraw=false;t.complete(0,1)
      for(prefix<-Seq("vertex.sampler.","fragment.sampler.");bad<-Seq(
        Map("policyVersion"->2),Map("wrapU"->1),Map("signedCoordinates"->1),
        Map("policyVersion"->1,"wrapU"->3),Map("policyVersion"->1,"wrapV"->3),
        Map("policyVersion"->1,"clamp"->1),Map("policyVersion"->1,"wrapU"->4))){
        t.prepare(1);t.drawValues++=bad.map{case(k,v)=>(prefix+k)->BigInt(v)}
        t.put(0x4000,drawWords,drawMagic,drawFields(d.io.draw.bits),t.drawValues);t.submit();t.complete(1)
        assert(t.acceptedDraws==0)
      }
      t.prepare(1);t.submit();t.complete(0,1)
      info("NATIVE_TEXTURE_POLICY_READER typed=true extensionWords56And552=true immutableOffer=true invalidPolicies=14 noInvalidChild=true legacyReuse=true")
    }
  }
}
