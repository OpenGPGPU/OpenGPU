package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeBinnedGraphicsJobReaderSpec extends AnyFlatSpec {
  import NativeBinnedGraphicsLayout._
  import NativeTypedGraphicsLayout.Field
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  // Keep the complete nested result while avoiding the simulator's DPI name
  // length limit. The production interface and all result fields are preserved.
  private class Harness(virtualImages: Boolean=false,reuseEntries: Int=0,productInputs: Boolean=false,wideTiles: Boolean=false,preflight: Boolean=false,advanced: Boolean=false,geometryEnabled: Boolean=false) extends Module {
    val core=Module(new NativeBinnedGraphicsJobReader(config=if(preflight || advanced || geometryEnabled)opengpu.config.GpuConfig(lanes=2,warps=2)else opengpu.config.GpuConfig(),p=if(productInputs)VertexShaderStageConfig()else p.copy(postTransformEntries=reuseEntries),virtualImageViews=virtualImages,allowWideTileCount=wideTiles,preflightDescriptors=preflight,
      f=FragmentShaderConfig(varyings=2),nativeAdvanced=advanced,nativeGeometry=geometryEnabled))
    val io=IO(new Bundle {
      val submit=chiselTypeOf(core.io.submit);val begin=chiselTypeOf(core.io.begin)
      val draw=chiselTypeOf(core.io.draw);val end=chiselTypeOf(core.io.end)
      val geometry=if(geometryEnabled)Some(chiselTypeOf(core.io.geometry.get))else None
      val drawCompletion=chiselTypeOf(core.io.drawCompletion)
      val pc=chiselTypeOf(core.io.passCompletion);def passCompletion=pc
      val done=chiselTypeOf(core.io.completion);def completion=done
      val memoryRequest=chiselTypeOf(core.io.memoryRequest);val memoryResponse=chiselTypeOf(core.io.memoryResponse)
      val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
    })
    io.submit<>core.io.submit;io.begin<>core.io.begin;io.draw<>core.io.draw;io.end<>core.io.end
    io.geometry.foreach(_:=core.io.geometry.get)
    io.drawCompletion<>core.io.drawCompletion;io.pc<>core.io.passCompletion;io.done<>core.io.completion
    io.memoryRequest<>core.io.memoryRequest;io.memoryResponse<>core.io.memoryResponse
    core.io.cancel:=io.cancel;io.cancelCore:=core.io.cancelCore;io.busy:=core.io.busy
  }
  private def zero(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(zero);case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U);case _=>()
  }
  private class Driver(val d: Harness){
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    val jf=jobFields(new NativeBinnedGraphicsJobRecord)
    val ff=frameFields(new NativeBinnedGraphicsFrameRecord)
    val df=drawFields(new NativeBinnedGraphicsDrawRecord)
    val memory=mutable.Map.empty[BigInt,BigInt];val requests=mutable.ArrayBuffer.empty[(Int,BigInt)]
    val pending=mutable.ArrayBuffer.empty[(Int,Int,BigInt)];var response: Option[(Int,BigInt)]=None
    var cycle=0;var peak=0;var reordered=false;var lastAddress=BigInt(0)
    var blockMemory=false;var blockReply=false;var blockBegin=false;var blockDraw=false;var blockEnd=false;var holdPass=false
    var begun=false;var ended=false;var cancelled=false;var passOffered=false;var drawOffered=false
    var drawReply: Option[(Int,Int)]=None;var acceptedBegins=0;var acceptedDraws=0;var acceptedEnds=0
    var childDrawFault=0;var childPassFault=0;var faultAt=Set.empty[BigInt]
    var heldGeometry: Option[BigInt]=None
    var heldMemory: Option[BigInt]=None;var heldBegin: Option[BigInt]=None;var heldDraw: Option[BigInt]=None;var heldEnd=false
    var jobBase=BigInt(0x1000);var frameBase=BigInt(0x2000);var drawBase=BigInt(0x4000);var count=2
    var jobValues=Map.empty[String,BigInt];var frameValues=Map.empty[String,BigInt];var drawValues=Map.empty[String,BigInt]
    var orderedDrawValues=Vector.empty[Map[String,BigInt]]
    val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
    def ids(prefix: String): Map[String,BigInt]=Map(prefix+"context"->BigInt(7),prefix+"pass"->BigInt(8),prefix+"generation"->BigInt(9))
    def identity(x: GraphicsWorkIdentity): Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
    def put(base: BigInt,words: Int,magic: BigInt,fields: Seq[Field],values: Map[String,BigInt]): Unit={
      for(w<-0 until words)memory(base+w*4)=0
      memory(base)=magic;memory(base+4)=words*4;memory(base+8)=1
      for(f<-fields){val bits=values.getOrElse(f.name,BigInt(0))&((BigInt(1)<<(f.words*32))-1)
        for(w<-0 until f.words)memory(base+(f.word+w)*4)=(bits>>(w*32))&0xffffffffL}
    }
    def set(base: BigInt,fields: Seq[Field],name: String,value: BigInt): Unit={val f=fields.find(_.name==name).get
      for(w<-0 until f.words)memory(base+(f.word+w)*4)=(value>>(w*32))&0xffffffffL}
    def prepare(draws: Int=2,jb: BigInt=0x1000,fb: BigInt=0x2000,db: BigInt=0x4000): Unit={
      assert(pending.isEmpty && response.isEmpty && drawReply.isEmpty && !passOffered)
      zero(d.io);memory.clear();requests.clear();peak=0;reordered=false;lastAddress=0
      begun=false;ended=false;cancelled=false;acceptedBegins=0;acceptedDraws=0;acceptedEnds=0
      heldGeometry=None;heldMemory=None;heldBegin=None;heldDraw=None;heldEnd=false
      jobBase=jb;frameBase=fb;drawBase=db;count=draws
      orderedDrawValues=Vector.empty
      jobValues=header++ids("job.identity.")++Map("job.frameBase"->fb,"job.frameLimit"->(fb+frameBytes),
        "job.drawBase"->db,"job.drawLimit"->(db+(draws-1).max(0)*4096+drawBytes),"job.drawCount"->BigInt(draws),"job.drawStride"->BigInt(4096))
      frameValues=header++ids("frame.image.pixels.colors.identity.")++ids("frame.states.identity.")++
        ids("frame.bins.producer.arena.identity.")++ids("frame.bins.bins.pool.identity.")++Map[String,BigInt](
        "frame.version"->1,"frame.bins.producer.arena.base"->0x200000,"frame.bins.producer.arena.limit"->0x210000,
        "frame.bins.bins.pool.base"->0x300000,"frame.bins.bins.pool.limit"->0x310000,
        "frame.bins.bins.directoryBase"->0x400000,"frame.bins.bins.directoryLimit"->0x410000,
        "frame.scratch[0].base"->0x500000,"frame.scratch[0].limit"->0x510000,
        "frame.scratch[1].base"->0x600000,"frame.scratch[1].limit"->0x610000,
        "frame.scratchStride"->4096,"frame.states.base"->0x700000,"frame.states.limit"->0x700400,
        "frame.image.pixels.colors.originX"-> -17,"frame.image.pixels.colors.colors[3].clear[2]"->BigInt("7fc12345",16))
      drawValues=header++ids("draw.vertex.geometry.vertex.input.identity.")++Map[String,BigInt](
        "draw.version"->1,"draw.state"->1,"draw.token"->0x98765432L,"draw.vertex.geometry.topology"->4,
        "draw.vertex.geometry.flatMask"->8,"draw.vertex.geometry.vertex.attributeSlots"->1,
        "draw.vertex.geometry.vertex.varyingSlots"->1,"draw.vertex.geometry.vertex.varyingMask"->15,
        "draw.vertex.geometry.vertex.input.attributes[0].enabled"->1,"draw.vertex.geometry.vertex.input.attributes[0].components"->4,
        "draw.vertex.geometry.vertex.input.baseVertex"-> -17,"draw.vertex.geometry.vertex.sampler.bias"-> -5,
        "draw.vertex.geometry.vertex.scratch.base"->0x100000,"draw.vertex.geometry.vertex.scratch.limit"->0x110000,
        "draw.vertex.viewport.x"-> -123,"draw.vertex.viewport.y"->97,"draw.raster.scissorMaxX"->111)
      put(jb,jobWords,jobMagic,jf,jobValues);put(fb,frameWords,frameMagic,ff,frameValues)
      for(i<-0 until draws)put(db+i*4096,drawWords,drawMagic,df,drawValues)
      d.io.submit.bits.commandId.poke(23.U);d.io.submit.bits.base.poke(jb.U);d.io.submit.bits.limit.poke((jb+jobBytes).U);d.io.submit.bits.envelopeValid.poke(true.B)
    }
    def check(prefix: String,x: Data,values: Map[String,BigInt]): Unit=for((name,leaf)<-WindowPrimitiveRecordLayout.leaves(x)){
      val width=leaf.getWidth;val raw=values.getOrElse(prefix+name,BigInt(0))&((BigInt(1)<<width)-1)
      val expected=leaf match{case _: SInt if raw.testBit(width-1)=>raw-(BigInt(1)<<width);case _=>raw}
      assert(leaf.peek().litValue==expected,s"field $prefix$name observed=${leaf.peek().litValue} expected=$expected")
    }
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockMemory && cycle%3!=0).B)
      if(response.isEmpty && !blockReply){val eligible=pending.indices.filter(i=>pending(i)._1<=cycle)
        if(eligible.nonEmpty){val(_,tag,address)=pending.remove(eligible.last);response=Some((tag,address))}}
      d.io.memoryResponse.valid.poke(response.nonEmpty.B)
      response.foreach{case(tag,address)=>d.io.memoryResponse.bits.transactionId.poke(tag.U);d.io.memoryResponse.bits.fault.poke(faultAt(address).B)
        d.io.memoryResponse.bits.readData.poke((0 until 16).foldLeft(BigInt(0))((x,w)=>x|(memory.getOrElse(address+w*4,BigInt(0))<<(w*32))).U)}
      d.io.begin.ready.poke((!blockBegin && cycle%4!=0).B);d.io.draw.ready.poke((!blockDraw && cycle%4!=1).B);d.io.end.ready.poke((!blockEnd && cycle%4!=2).B)
      if(d.io.cancelCore.peek().litToBoolean)cancelled=true
      if(!drawOffered && drawReply.exists(_._1<=cycle))drawOffered=true
      d.io.drawCompletion.valid.poke(drawOffered.B)
      if(drawOffered){zero(d.io.drawCompletion.bits);identity(d.io.drawCompletion.bits.identity)
        d.io.drawCompletion.bits.ordinal.poke(drawReply.get._2.U);d.io.drawCompletion.bits.status.poke((if(childDrawFault!=0)childDrawFault else if(cancelled)4 else 0).U)
        d.io.drawCompletion.bits.source.source.source.source.faultAddress.poke(0xdeadbeefL.U)}
      if(!passOffered && begun && (ended || cancelled) && drawReply.isEmpty && !holdPass)passOffered=true
      d.io.passCompletion.valid.poke(passOffered.B)
      if(passOffered){zero(d.io.passCompletion.bits);val x=d.io.passCompletion.bits;identity(x.identity)
        x.status.poke((if(childPassFault!=0)childPassFault else if(cancelled)4 else 0).U)
        x.tiles.poke(6.U);x.primitives.poke(12.U);x.source.draws.poke(acceptedDraws.U)
        x.faultTileValid.poke((childPassFault!=0).B);x.faultTile.poke(3.U);x.storedBytes.poke(4567.U);x.resolvedBytes.poke(890.U)}
      if(d.io.memoryRequest.valid.peek().litToBoolean){val bits=d.io.memoryRequest.bits.peek().litValue;heldMemory.foreach(x=>assert(x==bits));heldMemory=Some(bits)
        if(d.io.memoryRequest.ready.peek().litToBoolean){val r=d.io.memoryRequest.bits;val tag=r.transactionId.peek().litValue.toInt;val address=r.address.peek().litValue
          assert(!pending.exists(_._2==tag) && !response.exists(_._1==tag));r.isWrite.expect(false.B);r.sizeLog2.expect(6.U)
          requests+=((tag,address));pending+=((cycle+18-4*tag,tag,address));heldMemory=None}}
      if(response.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val address=response.get._2;if(address<lastAddress)reordered=true;lastAddress=address;response=None}
      def stable(valid: Boolean,value: => BigInt,old: Option[BigInt]): Option[BigInt]=if(valid){val bits=value;old.foreach(x=>assert(x==bits));Some(bits)}else None
      heldBegin=stable(d.io.begin.valid.peek().litToBoolean,d.io.begin.bits.peek().litValue,heldBegin)
      heldDraw=stable(d.io.draw.valid.peek().litToBoolean,d.io.draw.bits.peek().litValue,heldDraw)
      d.io.geometry.foreach{o=>
        heldGeometry=stable(d.io.draw.valid.peek().litToBoolean,o.peek().litValue,heldGeometry)
        if(d.io.draw.valid.peek().litToBoolean){
          val gs=memory.getOrElse(drawBase+acceptedDraws*4096,BigInt(0))==NativeGeometryDrawLayout.magic
          o.valid.expect(gs.B)
          if(gs){o.bits.inputVertices.expect(6.U);o.bits.invocations.expect(32.U);o.bits.maxVertices.expect(256.U)
            o.bits.vertexVaryingSlots.expect(1.U);o.bits.fragmentVaryingSlots.expect(2.U);o.bits.uniforms.version.expect(2.U)}
        }
      }
      if(heldEnd)d.io.end.valid.expect(true.B)
      heldEnd=d.io.end.valid.peek().litToBoolean && !d.io.end.ready.peek().litToBoolean
      if(d.io.begin.valid.peek().litToBoolean && d.io.begin.ready.peek().litToBoolean){check("frame.",d.io.begin.bits,frameValues);begun=true;acceptedBegins+=1;heldBegin=None}
      if(d.io.draw.valid.peek().litToBoolean && d.io.draw.ready.peek().litToBoolean){check("draw.",d.io.draw.bits,if(orderedDrawValues.nonEmpty)orderedDrawValues(acceptedDraws)else drawValues);assert(drawReply.isEmpty)
        drawReply=Some((cycle+7,acceptedDraws));acceptedDraws+=1;heldDraw=None;heldGeometry=None}
      if(d.io.end.valid.peek().litToBoolean && d.io.end.ready.peek().litToBoolean){ended=true;acceptedEnds+=1}
      if(drawOffered && d.io.drawCompletion.ready.peek().litToBoolean){drawOffered=false;drawReply=None}
      if(passOffered && d.io.passCompletion.ready.peek().litToBoolean){passOffered=false;begun=false}
      peak=peak.max(pending.size+response.size);assert(peak<=4);d.clock.step();cycle+=1
    }
    def until(test: => Boolean): Unit={var n=0;while(!test && n<20000){tick();n+=1};assert(test,s"reader stalled cycle=$cycle requests=${requests.size}")}
    def submit(): Unit={d.io.submit.valid.poke(true.B);until(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)}
    def complete(code: Int,draws: Int=0): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.commandId.expect(23.U);r.status.expect(code.U);r.issuedDraws.expect(draws.U);r.completedDraws.expect(draws.U)
      val drawRequests=requests.count{case(_,a)=>count>0 && a>=drawBase && a<drawBase+(count-1)*4096+drawBytes}
      r.drawReadBytes.expect((drawRequests*64).U);r.descriptorReadBytes.expect(((requests.size-drawRequests)*64).U)
      if(acceptedBegins>0){r.frame.tiles.expect(6.U);r.frame.storedBytes.expect(4567.U);r.frame.resolvedBytes.expect(890.U)}
      assert(pending.isEmpty && response.isEmpty && drawReply.isEmpty && !passOffered)
      val bits=r.peek().litValue;for(_<-0 until 5){tick();assert(r.peek().litValue==bits);d.io.submit.ready.expect(false.B);d.io.busy.expect(true.B)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def healthy(): Unit={prepare();submit();complete(0,2);assert(acceptedBegins==1 && acceptedEnds==1 && peak==4 && reordered)}
  }

  it should "preflight every GND2 GNG and GFS before any VS and retain their paired draw ownership" in {
    // Metadata-only component: the child acknowledges accepted tuples; it
    // executes no VS/GS/FS and cannot establish native GS execution support.
    simulate(new Harness(geometryEnabled=true)){d=>val t=new Driver(d)
      val stateBase=BigInt(0x700000);val nodeBase=BigInt(0xb00000)
      val sf=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(varyings=2)))
      def prepare(mixed: Boolean=false): Unit={
        t.prepare(3)
        t.frameValues++=Map[String,BigInt]("frame.states.version"->1,"frame.states.limit"->(stateBase+2048),
          "frame.image.pixels.colors.colors[0].enabled"->1)
        t.put(t.frameBase,frameWords,frameMagic,t.ff,t.frameValues)
        t.drawValues++=Map[String,BigInt]("draw.vertex.geometry.vertex.version"->1,
          "draw.vertex.geometry.vertex.shaderPc"->0x9000,"draw.vertex.geometry.vertex.input.version"->1,
          "draw.vertex.viewport.version"->1,"draw.raster.version"->1,
          "draw.vertex.geometry.vertex.input.count"->6,"draw.vertex.geometry.vertex.input.instances"->1,
          "draw.vertex.geometry.vertex.input.bindings[0].base"->0x800000,"draw.vertex.geometry.vertex.input.bindings[0].limit"->0x801000,
          "draw.vertex.geometry.vertex.input.bindings[0].allocationBase"->0x800000,
          "draw.vertex.geometry.vertex.input.bindings[0].allocationLimit"->0x801000,
          "draw.vertex.geometry.vertex.scratch.allocationBase"->0x100000,"draw.vertex.geometry.vertex.scratch.allocationLimit"->0x110000)
        t.orderedDrawValues=(0 until 3).map{i=>t.drawValues++Map("draw.state"->BigInt(i+1),
          "draw.vertex.geometry.topology"->BigInt(if(mixed && i==0)4 else 12))}.toVector
        for(i<-0 until 3){
          val gs= !mixed || i!=0;val draw=t.drawBase+i*4096;val handle=i+1
          t.put(draw,drawWords,if(gs)NativeGeometryDrawLayout.magic else drawMagic,t.df,t.orderedDrawValues(i))
          if(gs){t.memory(draw+8)=2;t.memory(draw+508*4)=1;t.memory(draw+509*4)=nodeBase+i*1024;t.memory(draw+510*4)=nodeBase+(i+1)*1024}
          val state=t.ids("fragment.identity.")++Map[String,BigInt]("version"->1,"engineCount"->2,"tests.version"->1,"blend.version"->1,
            "fragment.version"->1,"fragment.shaderPc"->0x9000,"fragment.varyingSlots"->(if(gs)2 else 1),
            "fragment.colorSlots"->1,"fragment.colorMask"->1,
            "fragment.scratch.base"->(0x500000+handle*4096),"fragment.scratch.limit"->(0x500000+handle*4096+1024),
            "fragment.scratch.allocationBase"->(0x500000+handle*4096),"fragment.scratch.allocationLimit"->(0x500000+handle*4096+1024),
            "scratch[0].base"->(0x600000+handle*4096),"scratch[0].limit"->(0x600000+handle*4096+1024),
            "scratch[0].allocationBase"->(0x600000+handle*4096),"scratch[0].allocationLimit"->(0x600000+handle*4096+1024))
          t.put(stateBase+handle*512,128,TypedFragmentStateLayout.magic,sf,state)
          for((word,value)<-Seq(3->4,4->7,5->8,6->9,7->handle))t.memory(stateBase+handle*512+word*4)=value
          // Independent literal wire oracle, including full adjacency and the
          // separate maxVertices * totalComponents == 1024 boundary.
          val w=Array.fill[BigInt](256)(0)
          for((word,value)<-Seq[(Int,BigInt)](0->BigInt("474e4701",16),1->1024,2->1,3->7,4->8,5->9,
              6->6,7->32,8->2,9->256,10->4,11->4,13->1,14->2,15->1,
              16->0x900004,17->0x900000,18->0x900800,20->0x900000,21->0x900800,23->2,24->0xa00000))w(word)=value
          for(k<-0 until 4)w(32+k)=k
          for(k<-0 until 12){w(96+2*k)=1;w(97+2*k)=k%4}
          for(k<-w.indices)t.memory(nodeBase+i*1024+k*4)=w(k)
        }
      }
      def rejected(code: Int): Unit={t.submit();t.complete(code);assert(t.acceptedBegins==0 && t.acceptedDraws==0)}
      prepare();t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean)
      assert(t.requests.count{case(_,a)=>a>=nodeBase && a<nodeBase+3072}==48)
      t.blockBegin=false;t.complete(0,3)
      prepare(mixed=true);t.submit();t.complete(0,3)
      for((word,value,code)<-Seq[(Int,BigInt,Int)]((0,BigInt(0),1),(3,BigInt(99),7),(7,BigInt(33),1),(9,BigInt(257),1),(11,BigInt(5),1),
            (13,BigInt(2),1),(14,BigInt(1),1),(23,BigInt(1),1),(255,BigInt(1),1))){
        prepare();t.memory(nodeBase+2048+word*4)=value;rejected(code)
      }
      for(word<-Seq(0,2,508,509,510,511)){
        prepare();val address=t.drawBase+8192+word*4;t.memory(address)=if(word==510)t.memory(address)-1 else t.memory(address)^1;rejected(1)
      }
      prepare(mixed=true);t.memory(t.drawBase+508*4)=1;rejected(1)
      for(base<-Seq[BigInt](0x100000,0x200000,0x300000,0x400000,0x500000,0x600000)){
        prepare();t.memory(t.drawBase+8192+509*4)=base;t.memory(t.drawBase+8192+510*4)=base+1024;rejected(1)
      }
      for(line<-0 until 16){prepare();t.faultAt=Set(nodeBase+2048+line*64);rejected(3);t.faultAt=Set.empty}
      prepare();t.submit();t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.address.peek().litValue>=nodeBase)
      t.blockMemory=true;val offered=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered)}
      t.blockMemory=false;t.complete(4);assert(t.acceptedBegins==0)
      prepare();t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      val paired=d.io.geometry.get.peek().litValue
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.draw.valid.expect(true.B);assert(d.io.geometry.get.peek().litValue==paired)}
      t.blockDraw=false;t.complete(4,1)
      prepare();t.submit();t.complete(0,3)
    }
  }

  for(advanced<-Seq(false,true))
  it should s"preflight every referenced draw and state before any child begins advanced=$advanced" in {
    // advanced=true deliberately leaves the explicit preflight flag false:
    // raw GNJ1 advanced transport must not bypass the mandatory scan.
    simulate(new Harness(preflight= !advanced,advanced=advanced)){d=>val t=new Driver(d)
      val sf=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(varyings=2)))
      val stateBase=BigInt(0x700000)
      def state(handle: Int): Unit={
        val values=t.ids("fragment.identity.")++Map[String,BigInt](
          "version"->(if(advanced)3 else 1),"engineCount"->2,"tests.version"->1,
          "blend.version"->(if(advanced)3 else 1),
          "blend.buffers[0]"->(if(advanced)BigInt(1)|(BigInt(15)<<23)|(BigInt(1)<<27)else BigInt(0)),
          "fragment.version"->1,"fragment.shaderPc"->0x9000,"fragment.varyingSlots"->1,
          "fragment.colorSlots"->1,"fragment.colorMask"->1,
          "fragment.scratch.base"->(0x500000+handle*4096),"fragment.scratch.limit"->(0x500000+handle*4096+1024),
          "fragment.scratch.allocationBase"->(0x500000+handle*4096),"fragment.scratch.allocationLimit"->(0x500000+handle*4096+1024),
          "scratch[0].base"->(0x600000+handle*4096),"scratch[0].limit"->(0x600000+handle*4096+1024),
          "scratch[0].allocationBase"->(0x600000+handle*4096),"scratch[0].allocationLimit"->(0x600000+handle*4096+1024))
        val base=stateBase+handle*512
        t.put(base,128,TypedFragmentStateLayout.magic,sf,values)
        t.memory(base+12)=4;t.memory(base+16)=7;t.memory(base+20)=8;t.memory(base+24)=9;t.memory(base+28)=handle
      }
      def prepare(n: Int=3): Unit={
        t.prepare(n)
        t.frameValues++=Map[String,BigInt]("frame.states.version"->1,"frame.states.limit"->(stateBase+(n+1)*512),
          "frame.image.pixels.colors.colors[0].enabled"->1)
        t.put(t.frameBase,frameWords,frameMagic,t.ff,t.frameValues)
        t.drawValues++=Map[String,BigInt]("draw.vertex.geometry.vertex.version"->1,
          "draw.vertex.geometry.vertex.shaderPc"->0x9000,"draw.vertex.geometry.vertex.input.version"->1,
          "draw.vertex.viewport.version"->1,"draw.raster.version"->1,
          "draw.vertex.geometry.vertex.input.count"->3,"draw.vertex.geometry.vertex.input.instances"->1,
          "draw.vertex.geometry.vertex.input.bindings[0].base"->0x800000,
          "draw.vertex.geometry.vertex.input.bindings[0].limit"->0x801000,
          "draw.vertex.geometry.vertex.input.bindings[0].allocationBase"->0x800000,
          "draw.vertex.geometry.vertex.input.bindings[0].allocationLimit"->0x801000,
          "draw.vertex.geometry.vertex.scratch.allocationBase"->0x100000,
          "draw.vertex.geometry.vertex.scratch.allocationLimit"->0x110000)
        t.orderedDrawValues=(0 until n).map{i=>t.drawValues+("draw.state"->BigInt(i+1))}.toVector
        for(i<-0 until n){t.put(t.drawBase+i*4096,drawWords,drawMagic,t.df,t.orderedDrawValues(i));state(i+1)}
      }
      def rejected(code: Int): Unit={t.submit();t.complete(code);assert(t.acceptedBegins==0 && t.acceptedDraws==0 && t.acceptedEnds==0)}
      // A malformed last record/state must prevent even the first draw from
      // reaching the child, despite earlier valid records and reordered reads.
      // Existing profiles cannot interpret GND2 as an ordinary draw. The
      // legacy GND1 extension words stay reserved even with GNP/GAB enabled.
      prepare();val geometryDraw=t.drawBase+2*4096
      t.memory(geometryDraw)=BigInt("474e4402",16);t.memory(geometryDraw+8)=2
      t.memory(geometryDraw+508*4)=1;t.memory(geometryDraw+509*4)=0x880000
      t.memory(geometryDraw+510*4)=0x880400;rejected(1)
      for(word<-508 until 512){prepare();t.memory(t.drawBase+2*4096+word*4)=1;rejected(1)}
      prepare();t.memory(t.drawBase+2*4096)=0;rejected(1)
      prepare();t.set(t.drawBase+2*4096,t.df,"draw.vertex.geometry.vertex.shaderPc",3);rejected(1)
      prepare();t.set(t.drawBase+2*4096,t.df,"draw.raster.version",0);rejected(1)
      prepare();t.set(t.drawBase+2*4096,t.df,"draw.vertex.viewport.version",0);rejected(1)
      prepare();t.set(t.drawBase+2*4096,t.df,"draw.vertex.geometry.vertex.input.attributes[0].components",0);rejected(1)
      prepare();t.set(stateBase+3*512,sf,"fragment.shaderPc",3);rejected(1)
      prepare();t.memory(stateBase+3*512+28)=99;rejected(7)
      prepare();t.faultAt=Set(stateBase+3*512+128);rejected(3);t.faultAt=Set.empty
      if(advanced){
        prepare();t.set(t.frameBase,t.ff,"frame.image.pixels.colors.colors[0].format",9)
        t.set(stateBase+3*512,sf,"fragment.colorTypes[0]",1);rejected(1)
      }
      prepare();t.blockMemory=true;t.submit();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val offered=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered)}
      t.blockMemory=false;t.complete(4);assert(t.acceptedBegins==0)
      prepare();t.submit();t.until(d.io.memoryRequest.valid.peek().litToBoolean &&
        d.io.memoryRequest.bits.address.peek().litValue>=stateBase &&
        d.io.memoryRequest.bits.address.peek().litValue<stateBase+2048)
      t.blockMemory=true;val heldState=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==heldState)}
      t.blockMemory=false;t.complete(4);assert(t.acceptedBegins==0)
      prepare();t.submit();t.until(t.requests.exists{case(_,a)=>a>=stateBase && a<stateBase+2048})
      t.blockReply=true;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.completion.valid.expect(false.B);assert(t.acceptedBegins==0)}
      t.blockReply=false;t.complete(4);assert(t.acceptedBegins==0)
      prepare();t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean)
      val heldAfterScan=d.io.begin.bits.peek().litValue
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();d.io.begin.valid.expect(true.B);assert(d.io.begin.bits.peek().litValue==heldAfterScan)}
      t.blockBegin=false;t.complete(4);assert(t.acceptedBegins==1 && t.acceptedDraws==0)
      prepare();t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean)
      assert(t.acceptedDraws==0)
      for(i<-0 until 3){
        assert(t.requests.exists{case(_,a)=>a==t.drawBase+i*4096+drawBytes-64})
        assert(t.requests.exists{case(_,a)=>a==stateBase+(i+1)*512+448})
      }
      for(_<-0 until 5)t.tick()
      t.blockBegin=false;t.complete(0,3)
      assert(t.acceptedBegins==1 && t.acceptedDraws==3 && t.acceptedEnds==1)
    }
  }

  for(wide<-Seq(false,true))
  it should s"version the complete tile-count word and preserve legacy padding rejection wide=$wide" in {
    simulate(new Harness(wideTiles=wide)){d=>val t=new Driver(d)
      def prepare(version: Int,count: Int): Unit={
        t.prepare(0)
        t.frameValues++=Map[String,BigInt]("frame.version"->version,"frame.bins.bins.tileCount"->count)
        t.set(t.frameBase,t.ff,"frame.version",version)
        t.set(t.frameBase,t.ff,"frame.bins.bins.tileCount",count)
      }
      if(wide){
        prepare(2,65536);t.blockBegin=true;t.submit();t.until(d.io.begin.valid.peek().litToBoolean)
        d.io.begin.bits.bins.bins.tileCount.expect(65536.U)
        t.set(t.frameBase,t.ff,"frame.bins.bins.tileCount",1)
        for(_<-0 until 5)t.tick()
        d.io.begin.bits.bins.bins.tileCount.expect(65536.U)
        t.blockBegin=false;t.complete(0);assert(t.acceptedBegins==1 && t.acceptedEnds==1)
      }
      for((version,count)<-Seq((1,65536),(1,65537),(2,0),(2,65537))++(if(wide)Seq.empty else Seq((2,1),(2,65536)))){
        prepare(version,count);t.submit();t.complete(1);assert(t.acceptedBegins==0 && t.acceptedDraws==0)
      }
      prepare(1,16384);t.submit();t.complete(0);assert(t.acceptedBegins==1)
      info(s"NATIVE_WIDE_TILE_COUNT wide=$wide framePayloadV2=true exact65536Snapshot=$wide heldCapture=true legacyHighBitsRejected=true invalidCountBeforeChild=true legacyReuse=true")
    }
  }

  it should "snapshot fixed indirect parameter slots and reject malformed mode padding or index selection" in {
    simulate(new Harness){d=>val t=new Driver(d)
      val prefix="draw.vertex.geometry.indirect."
      for(mode<-Seq(1,2)){
        t.prepare(1)
        t.drawValues++=Map[String,BigInt](prefix+"mode"->mode,prefix+"view.base"->0x803c,
          prefix+"view.limit"->(0x803c+(if(mode==1)16 else 20)),prefix+"view.allocationBase"->0x8000,
          prefix+"view.allocationLimit"->0x8080,"draw.vertex.geometry.vertex.input.indexFormat"->(if(mode==1)0 else 2))
        for((word,value)<-Seq(499->mode,500->0x803c,501->(0x803c+(if(mode==1)16 else 20)),503->0x8000,504->0x8080))t.memory(t.drawBase+word*4)=value
        t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.input.indexFormat",if(mode==1)0 else 2)
        t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
        t.memory(t.drawBase+499*4)=0;t.memory(t.drawBase+500*4)=0
        for(_<-0 until 5)t.tick()
        d.io.draw.bits.vertex.geometry.indirect.mode.expect(mode.U);t.blockDraw=false;t.complete(0,1)
      }
      for(change<-Seq[(Int,BigInt)](499->BigInt(3),499->BigInt(4),499->BigInt(2),500->BigInt(0x803c))){
        t.prepare(1);t.memory(t.drawBase+change._1*4)=change._2;t.submit();t.complete(1)
      }
      t.prepare(1);t.submit();t.complete(0,1)
    }
  }
  for(entries<-Seq(0,16))
  it should s"snapshot opt-in vertex output reuse and reject unsupported capacity or padding entries=$entries" in {
    simulate(new Harness(reuseEntries=entries)){d=>val t=new Driver(d)
      val name="draw.vertex.geometry.vertex.reuseOutputs"
      t.prepare(1);t.drawValues+=(name->BigInt(1));t.set(t.drawBase,t.df,name,1)
      if(entries>0)t.blockDraw=true
      t.submit()
      if(entries>0){
        t.until(d.io.draw.valid.peek().litToBoolean);t.set(t.drawBase,t.df,name,0)
        for(_<-0 until 5)t.tick()
        d.io.draw.bits.vertex.geometry.vertex.reuseOutputs.expect(true.B);t.blockDraw=false
      }
      t.complete(if(entries>0)0 else 1,if(entries>0)1 else 0)
      t.prepare(1);t.set(t.drawBase,t.df,name,2);t.submit();t.complete(1)
      t.prepare(1);t.submit();t.complete(0,1)
    }
  }
  it should "snapshot appended polygon raster fields and reject noncanonical enables before offering a draw" in {
    simulate(new Harness()){d=>val t=new Driver(d);t.prepare(1);t.blockDraw=true
      t.drawValues++=Map[String,BigInt]("draw.raster.version"->2,"draw.raster.polygonOffsetEnable"->1,
        "draw.raster.polygonOffsetFactor"->Fp32ViewportReference.fp(2.5),"draw.raster.polygonOffsetUnits"->Fp32ViewportReference.fp(-4))
      t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      d.io.draw.bits.raster.version.expect(2.U);d.io.draw.bits.raster.polygonOffsetUnits.expect(Fp32ViewportReference.fp(-4).U)
      val held=d.io.draw.bits.peek().litValue;t.set(t.drawBase,t.df,"draw.raster.polygonOffsetFactor",0)
      for(_<-0 until 8){t.tick();assert(d.io.draw.bits.peek().litValue==held)}
      t.blockDraw=false;t.complete(0,1)
      t.prepare(1);t.set(t.drawBase,t.df,"draw.raster.polygonOffsetEnable",2);t.submit();t.complete(1);assert(t.acceptedDraws==0)
      t.healthy()
      info("POLYGON_NATIVE_BINNED_READER appendedFields=true rasterVersion2=true signedFactorUnits=true immutableOffer=true narrowPaddingRejected=true legacyReuse=true")
    }
  }
  it should "use distinct record magic values without reusing legacy bin typed-image or fragment-state ABI identifiers" in {
    val current=Set(jobMagic,frameMagic,drawMagic)
    val legacy=Set(TileBinJobMemoryLayout.magic,NativeTypedGraphicsLayout.jobMagic,NativeTypedGraphicsLayout.passMagic,
      NativeTypedGraphicsLayout.drawMagic,TypedFragmentStateLayout.magic)
    assert(current.size==3 && (current&legacy).isEmpty)
  }
  it should "protect descriptor state bin and staging roles from virtual image aliases while permitting shared read-only descriptor data" in {
    simulate(new Harness(true)){d=>val t=new Driver(d)
      def image(base: BigInt,store: Boolean=true): Unit={
        val values=Map[String,BigInt]("frame.image.pixels.colors.colors[0].enabled"->1,
          "frame.image.pixels.colors.colors[0].loadOp"->(if(store)1 else 0),
          "frame.image.views[0].store"->(if(store)1 else 0),"frame.image.views[0].allocationBase"->base,
          "frame.image.views[0].allocationLimit"->(base+256))
        t.frameValues++=values
        for((n,v)<-values)t.set(t.frameBase,t.ff,n,v)
      }
      val aliases=Seq[BigInt](0x1000,0x2000,0x4000,0x200000,0x300000,0x400000,0x500000,0x700000)
      for(base<-aliases){t.prepare();image(base);t.submit();t.complete(1);assert(t.acceptedBegins==0 && t.acceptedDraws==0)}
      t.prepare();image(0x200000,store=false);t.submit();t.complete(1);assert(t.acceptedBegins==0)
      t.prepare();image(0x100000);t.submit();t.complete(1);assert(t.acceptedBegins==1 && t.acceptedDraws==0)
      t.prepare(0);image(0x1000,store=false);t.submit();t.complete(0);assert(t.acceptedBegins==1 && t.acceptedEnds==1)
      t.healthy()
      info("NATIVE_GNC2_ADMISSION rejectedAliases=10 mutableDescriptorStateProtected=true binScratchRolesProtected=true readOnlyDescriptorAliasAllowed=true noInvalidDraw=true healthyReuse=true")
    }
  }
  it should "snapshot three canonical records through four reordered tags and preserve fixed-capacity fields at the four GiB boundary" in {
    simulate(new Harness){d=>val t=new Driver(d);t.healthy()
      for(kind<-0 until 3){val end=BigInt(1)<<32
        t.prepare(1,jb=if(kind==0)end-jobBytes else BigInt(0x1000),fb=if(kind==1)end-frameBytes else BigInt(0x2000),db=if(kind==2)end-drawBytes else BigInt(0x4000))
        t.submit();t.complete(0,1);assert(t.requests.exists(_._2==end-64))}
      t.prepare(0);t.submit();t.complete(0);assert(t.acceptedBegins==1 && t.acceptedEnds==1)
      info("NATIVE_BINNED_READER layouts=3 reorderedTags=4 exactSnapshots=true signedFields=true exclusive4GiB=true emptyFrameBeginEnd=true heldCompletion=true")
    }
  }
  it should "reject malformed envelopes capacities padding bounds and writable descriptor aliases before exposing invalid work" in {
    simulate(new Harness){d=>val t=new Driver(d)
      val changes=Seq[()=>Unit](
        ()=>d.io.submit.bits.envelopeValid.poke(false.B),()=>d.io.submit.bits.base.poke(0x1001.U),
        ()=>t.memory.update(t.jobBase,jobMagic^1),()=>t.memory.update(t.jobBase+8*4,1),
        ()=>t.set(t.jobBase,t.jf,"header.attributes",15),()=>t.set(t.jobBase,t.jf,"job.drawStride",2047),
        ()=>t.set(t.jobBase,t.jf,"job.drawBase",BigInt("fffff800",16)),
        ()=>t.set(t.frameBase,t.ff,"header.engineViews",3),()=>t.set(t.frameBase,t.ff,"frame.states.identity.context",10),
        ()=>t.set(t.frameBase,t.ff,"frame.scratch[0].base",t.jobBase),
        ()=>t.set(t.frameBase,t.ff,"frame.scratch[1].base",t.frameBase),
        ()=>t.set(t.frameBase,t.ff,"frame.bins.producer.arena.base",t.drawBase),
        ()=>t.set(t.frameBase,t.ff,"frame.bins.bins.pool.base",t.drawBase),
        ()=>t.set(t.frameBase,t.ff,"frame.bins.bins.directoryBase",t.drawBase),
        ()=>t.set(t.drawBase,t.df,"header.varyings",8),()=>t.set(t.drawBase,t.df,"draw.version",257),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.input.attributes[0].enabled",3),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.input.attributes[15].enabled",1),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.input.attributes[0].bindingIndex",2),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.varyingSlots",3),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.varyingMask",16),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.flatMask",16),
        ()=>t.set(t.drawBase,t.df,"draw.vertex.geometry.vertex.scratch.base",t.jobBase))
      for(change<-changes){t.prepare();change();t.submit();t.complete(1);assert(t.acceptedDraws==0)}
      t.healthy();info(s"NATIVE_BINNED_ADMISSION cases=${changes.size} fixedCapacityFit=true allAttributesChecked=true canonicalPadding=true immutableDescriptorSpans=true noInvalidDraw=true healthyReuse=true")
    }
  }
  it should "retain descriptor memory and nested child faults through cancellation drain and exact healthy reuse" in {
    simulate(new Harness){d=>val t=new Driver(d)
      for(address<-Seq(BigInt(0x1040),BigInt(0x2080),BigInt(0x40c0))){t.prepare();t.faultAt=Set(address);t.submit();t.complete(3)
        d.io.completion.bits.faultStage.expect(2.U);t.faultAt=Set.empty;t.healthy()}
      t.prepare();t.childDrawFault=5;t.submit();t.complete(5,1);d.io.completion.bits.faultAddress.expect(0xdeadbeefL.U)
      t.childDrawFault=0;t.healthy()
      t.prepare();t.childPassFault=3;t.submit();t.complete(3,2);d.io.completion.bits.frame.faultTileValid.expect(true.B);d.io.completion.bits.frame.faultTile.expect(3.U)
      t.childPassFault=0;t.healthy()
      info("NATIVE_BINNED_FAULTS recordKinds=3 primaryMemoryFault=true nestedVertexFault=true fullFrameTileAttribution=true drain=true exactHealthyReuse=true")
    }
  }
  it should "retain offered memory begin draw end and child publication ownership across cancellation" in {
    simulate(new Harness){d=>val t=new Driver(d)
      for(kind<-0 until 5){t.prepare(1)
        if(kind==0)t.blockMemory=true else if(kind==1)t.blockReply=true else if(kind==2)t.blockBegin=true else if(kind==3)t.blockDraw=true else t.blockEnd=true
        t.submit();t.until(kind match{case 0=>d.io.memoryRequest.valid.peek().litToBoolean;case 1=>t.pending.size==4;case 2=>d.io.begin.valid.peek().litToBoolean;case 3=>d.io.draw.valid.peek().litToBoolean;case _=>d.io.end.valid.peek().litToBoolean})
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)}
        t.blockMemory=false;t.blockReply=false;t.blockBegin=false;t.blockDraw=false;t.blockEnd=false
        if(kind>=2){t.holdPass=true;t.until(t.cancelled);for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)};t.holdPass=false}
        t.complete(4,if(kind>=3)1 else 0);t.healthy()
      }
      info("NATIVE_BINNED_CANCEL phases=5 heldOffers=true liveDescriptorTags=true noEarlyChildCancel=true heldPublication=true noEarlyCompletion=true exactHealthyReuse=true")
    }
  }

  it should "snapshot versioned binned sampler extension words and reject malformed policies before draw offers" in {
    simulate(new Harness()){d=>val t=new Driver(d)
      val prefix="draw.vertex.geometry.vertex.sampler."
      val mapped=t.df.map(x=>x.name->x.word).toMap
      assert(mapped(prefix+"policyVersion")==480 && mapped(prefix+"wrapU")==481 && mapped(prefix+"wrapV")==482 && mapped(prefix+"signedCoordinates")==483)
      t.prepare(1);t.drawValues++=Map(prefix+"policyVersion"->BigInt(1),prefix+"wrapU"->BigInt(2),prefix+"wrapV"->BigInt(1),prefix+"signedCoordinates"->BigInt(1))
      t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      val held=d.io.draw.bits.peek().litValue
      d.io.draw.bits.vertex.geometry.vertex.sampler.policyVersion.expect(1.U)
      d.io.draw.bits.vertex.geometry.vertex.sampler.wrapU.expect(2.U)
      for(_<-0 until 9){t.tick();assert(d.io.draw.bits.peek().litValue==held)}
      t.blockDraw=false;t.complete(0,1)
      for(bad<-Seq(Map("policyVersion"->2),Map("wrapU"->1),Map("signedCoordinates"->1),
        Map("policyVersion"->1,"wrapU"->3),Map("policyVersion"->1,"wrapV"->3),
        Map("policyVersion"->1,"clamp"->1),Map("policyVersion"->1,"wrapU"->4),Map("policyVersion"->1,"format"->5))){
        t.prepare(1);t.drawValues++=bad.map{case(k,v)=>(prefix+k)->BigInt(v)}
        t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.submit();t.complete(1)
        assert(t.acceptedDraws==0)
      }
      t.prepare(1);t.submit();t.complete(0,1)
      info("NATIVE_TEXTURE_POLICY_READER binned=true extensionWords480to483=true immutableOffer=true invalidPolicies=8 noInvalidChild=true legacyReuse=true")
    }
  }
  it should "retain binned vertex texture root references without moving legacy descriptor words" in {
    simulate(new Harness()){d=>val t=new Driver(d);val prefix="draw.vertex.geometry.vertex.resources."
      val mapped=t.df.map(f=>f.name->f.word).toMap;assert(mapped(prefix+"version")==496 && mapped(prefix+"address")==497)
      t.prepare(1);t.drawValues++=Map(prefix+"version"->BigInt(1),prefix+"address"->BigInt(0x9000))
      t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      d.io.draw.bits.vertex.geometry.vertex.resources.version.expect(1.U);d.io.draw.bits.vertex.geometry.vertex.resources.address.expect(0x9000.U)
      val held=d.io.draw.bits.peek().litValue;for(_<-0 until 9){t.tick();assert(d.io.draw.bits.peek().litValue==held)}
      t.blockDraw=false;t.complete(0,1)
      for((version,address)<-Seq((2,BigInt(0)),(0,BigInt(64)),(1,BigInt(1)),(1,(BigInt(1)<<32)-64))){
        t.prepare(1);t.drawValues++=Map(prefix+"version"->BigInt(version),prefix+"address"->address)
        t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.submit();t.complete(1);assert(t.acceptedDraws==0)
      }
      t.healthy();info("NATIVE_TEXTURE_ROOT_READER binned=true words496And497=true immutableOffer=true invalidRoots4BeforeChild=true legacyReuse=true")
    }
  }

  it should "retain binned vertex uniform root references without moving legacy descriptor words" in {
    simulate(new Harness()){d=>val t=new Driver(d);val prefix="draw.vertex.geometry.vertex.uniforms."
      val mapped=t.df.map(f=>f.name->f.word).toMap;assert(mapped(prefix+"version")==506 && mapped(prefix+"address")==507)
      t.prepare(1);t.drawValues++=Map(prefix+"version"->BigInt(1),prefix+"address"->BigInt(0x9000))
      t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
      d.io.draw.bits.vertex.geometry.vertex.uniforms.version.expect(1.U);d.io.draw.bits.vertex.geometry.vertex.uniforms.address.expect(0x9000.U)
      val held=d.io.draw.bits.peek().litValue;for(_<-0 until 9){t.tick();assert(d.io.draw.bits.peek().litValue==held)}
      t.blockDraw=false;t.complete(0,1)
      for((version,address)<-Seq((2,BigInt(0)),(0,BigInt(64)),(1,BigInt(1)),(1,(BigInt(1)<<32)-32))){
        t.prepare(1);t.drawValues++=Map(prefix+"version"->BigInt(version),prefix+"address"->address)
        t.put(t.drawBase,drawWords,drawMagic,t.df,t.drawValues);t.submit();t.complete(1);assert(t.acceptedDraws==0)
      }
      t.healthy();info("NATIVE_UNIFORM_ROOT_READER binned=true words506And507=true immutableOffer=true invalidRoots4BeforeChild=true legacyReuse=true")
    }
  }

  // The product-sized input model and six workload records are a separate
  // local qualification; routine CI exercises the bounded reader cases above.
  if (sys.props.get("opengpu.productDescriptorQualification").contains("1"))
  it should "read all six complete product frame bindings and preserve ordered draw state" in {
    simulate(new Harness(productInputs=true)){d=>val t=new Driver(d)
      for(scene<-NativeProductWorkloads.suite(32,32)){
        val inputs=NativeProductFrameInputs.encode(scene)
        t.prepare(scene.draws.size,inputs.jobBase,inputs.job("job.frameBase"),inputs.job("job.drawBase"))
        t.memory.clear();t.jobValues=inputs.job;t.frameValues=inputs.frame;t.orderedDrawValues=inputs.draws
        val records=Vector((BigInt(inputs.jobBase),jobBytes),(inputs.job("job.frameBase"),frameBytes))++
          inputs.draws.indices.map(i=>(inputs.job("job.drawBase")+i*drawBytes,drawBytes))
        for((base,bytes)<-records;i<-0 until bytes by 4)t.memory(base+i)=inputs.memory.readWord((base+i).toLong)
        t.blockDraw=true;t.submit();t.until(d.io.draw.valid.peek().litToBoolean)
        val held=d.io.draw.bits.peek().litValue
        for(_<-0 until 9){t.tick();d.io.draw.valid.expect(true.B);assert(d.io.draw.bits.peek().litValue==held)}
        t.blockDraw=false;t.complete(0,scene.draws.size)
        assert(t.acceptedBegins==1 && t.acceptedEnds==1 && t.acceptedDraws==scene.draws.size)
        assert(t.peak>1 && t.reordered)
      }
    }
  }

}
