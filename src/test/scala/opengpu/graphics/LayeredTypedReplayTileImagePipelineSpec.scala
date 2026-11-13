package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig,TaggedGraphicsTrap}
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** A real typed-image/CU component, starting from published window records.
  * It intentionally does not pretend to execute the preceding GS producer or
  * the protected native submit/dispatcher. Those are separate integration gates.
  */
private class LayeredTypedReplayImageHarness extends Module {
  val config=GpuConfig(lanes=4,warps=1,l2Sets=8,l2Ways=2)
  val vertices=VertexShaderStageConfig()
  val fragments=FragmentShaderConfig()
  val graphics=GraphicsConfig(screenWidth=math.min(1,GraphicsTestResolution.width),
    screenHeight=math.min(1,GraphicsTestResolution.height))
  private val slots=GpuSystem.totalMemoryTransactions(2,4,64)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredTypedReplayTileDescriptor))
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion)
    val out=Decoupled(new LayeredTypedPixelQuadCompletion)
    val layerDone=Decoupled(new LayeredTypedImageLayerCompletion)
    val completion=Decoupled(new LayeredTypedReplayTileCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool());val launched=Output(Bool());val owners=Output(UInt(2.W))
    val trap=Output(Valid(new TaggedGraphicsTrap(config,8,2)))
  })
  private val owner=Module(new LayeredTypedReplayTileImagePipeline(config,vertices,fragments,
    SharedCuShaderConfig(2),graphics,NativeBinnedGraphicsConfig.fourEngine.storage,pageBytes=128))
  private val system=Module(new GpuSystem(config,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=64,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private def tie(x:Data):Unit=x match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U;case _=>()
  }
  tie(system.io)
  owner.io.begin<>io.begin;io.primitiveDone<>owner.io.primitiveDone;io.out<>owner.io.out
  io.layerDone<>owner.io.layerDone;io.completion<>owner.io.completion;owner.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>owner.io.shared
  system.io.graphicsHostRequest<>owner.io.memoryRequest;owner.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !owner.io.busy
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle && !owner.io.busy
  io.launched:=owner.io.shared.launch.fire;io.owners:=owner.io.shared.owned
  io.trap.valid:=owner.io.shared.trap.fire;io.trap.bits:=owner.io.shared.trap.bits
}

class LayeredTypedReplayTileImagePipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.{fp,encode}
  private val names=Vector("RGBA32F","RGBA32UI","RGBA32UI","RGBA8")
  private val sizes=Vector(16,16,16,4,4,1)
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base:Int,v:Int,write:Boolean):BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def program(state:Int):Vector[BigInt]={
    val inputs=if(state==0)70 else 74
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    def store(array:Int):Unit=code++=Seq(addi(6,7,array*16),vm(6,1,true))
    def constant(bits:BigInt,array:Int):Unit={
      val high=((bits+0x800)>>12)&0xfffff;val low=(bits&4095).toInt
      code++=Seq((high<<12)|(BigInt(13)<<7)|0x37,addi(13,13,low),
        (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57)
      store(array)
    }
    def copy(input:Int,output:Int):Unit={code++=Seq(addi(5,7,input*16),vm(5,1,false));store(output)}
    for(c<-0 until 4)constant(fp(Vector(0.25,0.375,0.5)(state)),inputs+c)
    for(location<-1 to 2;c<-0 until 4){
      if(c==0 && state!=0)copy(if(location==1)72 else 73,inputs+location*4+c)
      else constant(if(c==0){if(location==1)93 else 0}else 10*location+c,inputs+location*4+c)
    }
    for(c<-0 until 4)constant(fp(Vector(0.25,0.5,0.75,1.0)(c)),inputs+12+c)
    copy(2,inputs+16);constant(61,inputs+17)
    code+=BigInt("30500073",16);require(code.size<=512);code.toVector
  }
  private case class Plane(location:Int,resolve:Boolean,allocation:BigInt,base:BigInt,end:BigInt) {
    val sampleCount=if(resolve)1 else 4
    def address(layer:Int,sample:Int):BigInt=base+layer*128+sample*sizes(location)
  }
  private class Driver(val d:LayeredTypedReplayImageHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    private val fields=WindowPrimitiveRecordLayout.fields(new WindowPrimitiveRecord(d.vertices))
    val recordBytes=WindowPrimitiveRecordLayout.lineCount(new WindowPrimitiveRecord(d.vertices))*64
    private val stateFields=TypedFragmentStateLayout.fields(new TypedFragmentState(d.fragments))
    val planes=mutable.ArrayBuffer.empty[Plane]
    val immutable=mutable.Map.empty[BigInt,Int]
    val scratchGuards=mutable.Map.empty[BigInt,Int]
    var empty=false;var loads=false;var launches=0;var primitives=0;var pixels=0;var layers=0
    var holdPixels=false;var holdLayer=false;var holdPrimitives=false
    var heldPixel=Option.empty[BigInt];var heldLayer=Option.empty[BigInt];var heldPrimitive=Option.empty[BigInt]
    var ownerMask=BigInt(0);var expectedStatus=0;var heldLastStore=false;var trapLastStore=false;var traps=0
    memory.beforeRequest=r=>{
      val last=planes.find(p=> !p.resolve && p.location==5).map(p=>(p.address(255,0)& ~BigInt(63)))
      if(r.write && last.contains(r.address) && trapLastStore){heldLastStore=true;memory.holdResponses=true}
    }
    private def identity(x:GraphicsWorkIdentity):Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
    private def snapshot(first:BigInt,end:BigInt):Unit=for(a<-first until end)immutable(a)=memory.bytes.getOrElse(a,0)
    private def putFields(base:BigInt,count:Int,fs:Seq[(String,Int,Int)],values:Map[String,BigInt]):Unit={
      assert(values.keySet.subsetOf(fs.map(_._1).toSet),s"unknown source fields ${values.keySet--fs.map(_._1).toSet}")
      for(w<-0 until count)memory.putWord(base+w*4,0)
      for((name,word,words)<-fs){val raw=values.getOrElse(name,BigInt(0))&((BigInt(1)<<(words*32))-1)
        for(w<-0 until words)memory.putWord(base+(word+w)*4,(raw>>(w*32))&0xffffffffL)}
    }
    private def extended(prefix:String,value:Double):Map[String,BigInt]={
      val e=Fp32ViewportReference.decode(fp(value))
      Map(s"$prefix.negative"->BigInt(if(e.sign)1 else 0),s"$prefix.exponent"->BigInt(e.exponent),s"$prefix.significand"->e.sig)
    }
    def install():Unit={
      for(h<-0 until 3){
        val base=BigInt(0x200000+h*recordBytes)
        val root="primitive.clipped.primitive"
        val values=Map[String,BigInt]("sequence"->(10*(h+1)),"state"->h,"token"->(200+h),
          s"$root.identity.context"->7,s"$root.identity.pass"->8,s"$root.identity.generation"->9,
          s"$root.primitiveId"->(93+h),s"$root.kind"->0,s"$root.topology"->0,s"$root.vertexCount"->1,
          s"$root.varyingSlots"->16,s"$root.vertices[0].identity.context"->7,
          s"$root.vertices[0].identity.pass"->8,s"$root.vertices[0].identity.generation"->9,
          s"$root.vertices[0].pointSizeWritten"->1,s"$root.vertices[0].pointSize"->fp(1),
          s"$root.vertices[0].position[3]"->fp(1),"primitive.clipped.partCount"->1,
          "primitive.vertices[0].screenX"->128,"primitive.vertices[0].screenY"->128,
          "primitive.vertices[0].fixedValid"->1,"primitive.vertices[0].windowDepth"->fp(Vector(0.75,0.5,0.25)(h)),
          "raster.version"->1,"raster.screenW"->1,"raster.screenH"->1,"raster.sampleMode"->2,
          "raster.frontFaceCCW"->1,"raster.pointSize"->fp(1))++
          extended("primitive.vertices[0].localX",0.5)++extended("primitive.vertices[0].localY",0.5)++
          extended("primitive.vertices[0].reciprocalW",1)
        putFields(base,recordBytes/4,fields.map(f=>(f.name,f.word,f.words)),values)
        val header=Vector[BigInt](WindowPrimitiveRecordLayout.magic,recordBytes,if(h==0)1 else 2,16,7,8,9,h,
          if(h==0)0 else 100+h,if(h==0)0 else h,if(h==1)255 else 0)
        for((word,i)<-header.zipWithIndex)memory.putWord(base+i*4,word)
        val inputs=if(h==0)70 else 74;val prefix=(inputs+18)*16
        val scratch=BigInt(0x600000+h*4096)
        for(a<-scratch until scratch+4096){memory.bytes(a)=0x6d;if(a>=scratch+prefix)scratchGuards(a)=0x6d}
        val state=Map[String,BigInt]("version"->2,"engineCount"->1,"fragment.version"->(if(h==0)1 else 5),
          "fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
          "fragment.shaderPc"->(0x1000+h*4096),"fragment.varyingSlots"->16,"fragment.colorSlots"->4,
          "fragment.colorMask"->15,"fragment.sampleMode"->2,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
          "fragment.scratch.base"->scratch,"fragment.scratch.limit"->(scratch+prefix),
          "fragment.scratch.allocationBase"->scratch,"fragment.scratch.allocationLimit"->(scratch+((prefix+63)& ~63)),
          "tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,"tests.depthFunction"->1,
          "tests.stencilEnable"->1,"blend.version"->2)++
          (0 until 4).map(i=>s"fragment.colorTypes[$i]"->BigInt(if(i==1 || i==2)1 else 0))++
          (0 until 4).map{i=>s"blend.buffers[$i]"->BigInt((15<<23)|(if(h==2 && i==0)(1<<1)|(1<<5)|(1<<9)|(1<<13)|1 else 0))}++
          Seq("front","back").flatMap(face=>Seq(s"tests.$face.function"->BigInt(6),s"tests.$face.reference"->BigInt(35+h),
            s"tests.$face.readMask"->BigInt(255),s"tests.$face.writeMask"->BigInt(255),s"tests.$face.depthPass"->BigInt(2)))
        putFields(BigInt(0x700000+h*512),128,stateFields.map(f=>(f.name,f.word,f.words)),state)
        // GFS record header remains1; its indexed draw/state payload is2.
        for((word,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,h).zipWithIndex)
          memory.putWord(0x700000+h*512+i*4,word)
        program(h).zipWithIndex.foreach{case(word,i)=>memory.putWord(0x1000+h*4096+i*4,word)}
        snapshot(0x1000+h*4096,0x1000+h*4096+program(h).size*4)
        snapshot(base,base+recordBytes);snapshot(0x700000+h*512,0x700000+(h+1)*512)
      }
      for((word,i)<-Vector[BigInt](PrimitiveBinPageLayout.magic,0,3,7,8,9,0,0).zipWithIndex)memory.putWord(0x400000+i*4,word)
      for(h<-0 until 3;(word,i)<-Vector[BigInt](h,h,10*(h+1),200+h).zipWithIndex)memory.putWord(0x400020+h*16+i*4,word)
      snapshot(0x400000,0x400080)
      for(resolve<-Seq(false,true);i<-0 until 6){
        val allocation=BigInt(if(resolve)0x2000000 else 0x1000000)+i*0x10000
        val base=allocation+17;val end=(base+255*128+(if(resolve)1 else 4)*sizes(i)+63)& ~BigInt(63)
        planes+=Plane(i,resolve,allocation,base,end)
        for(a<-allocation until end)memory.bytes(a)=0xa7
      }
    }
    def descriptor(noReferences:Boolean=false,load:Boolean=false):Unit={
      empty=noReferences;loads=load;launches=0;primitives=0;pixels=0;layers=0;ownerMask=0;traps=0
      heldPixel=None;heldLayer=None;heldPrimitive=None
      val q=d.io.begin.bits;zero(q);q.version.poke(1.U);q.image.version.poke(1.U);q.image.layers.poke(256.U)
      for(a<-Seq(q.records,q.states)){a.version.poke(1.U);identity(a.identity)}
      q.records.base.poke(0x200000.U);q.records.limit.poke((0x200000+3*recordBytes).U)
      q.states.base.poke(0x700000.U);q.states.limit.poke((0x700000+3*512).U)
      q.pool.version.poke(1.U);identity(q.pool.identity);q.pool.base.poke(0x400000.U);q.pool.limit.poke(0x400080.U);q.pool.pageSizeLog2.poke(7.U)
      q.directory.base.poke(0x500000.U);q.directory.limit.poke(0x500040.U)
      identity(q.chain.identity);q.chain.prefixUsable.poke(true.B);q.chain.headValid.poke((!empty).B)
      q.chain.head.poke((if(empty)0 else 0x400000).U);q.chain.tail.poke((if(empty)0 else 0x400000).U);q.chain.committedCount.poke((if(empty)0 else 3).U)
      q.engines.poke(1.U);q.scratchStride.poke(4096.U);q.scratch(0).base.poke(0x600000.U);q.scratch(0).limit.poke(0x603000.U)
      val im=q.image.image;im.version.poke(1.U);im.pixels.version.poke(1.U)
      val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity);c.tileLog2.poke(3.U)
      c.tileWidth.poke(1.U);c.tileHeight.poke(1.U);c.sampleMode.poke(2.U);c.reservedBytes.poke(1280.U)
      for(i<-0 until 4){val a=c.colors(i);a.enabled.poke(true.B);a.format.poke(TypedColorFormat.id(names(i)).U)
        a.offset.poke((1280+i*4096).U);a.channelMask.poke((if(i==3)5 else 15).U)
        a.loadOp.poke((if(load)AttachmentLoadOp.load else AttachmentLoadOp.clear).U)
        a.clear.zip(clear(i)).foreach{case(x,v)=>x.poke(v.U)}}
      im.pixels.depth.format.poke(TypedDepthFormat.d32f.U);im.pixels.depth.clearDepth.poke(fp(1).U)
      im.pixels.stencil.format.poke(TypedDepthFormat.s8.U);im.pixels.stencil.offset.poke(1024.U);im.pixels.stencil.clearStencil.poke(7.U)
      for(v<-Seq(im.pixels.depth,im.pixels.stencil))v.loadOp.poke((if(load)AttachmentLoadOp.load else AttachmentLoadOp.clear).U)
      for(p<-planes){val v=if(p.resolve){im.resolves(p.location).enable.poke(true.B);im.resolves(p.location).destination}else im.views(p.location)
        v.base.poke(p.base.U);v.limit.poke((p.base+255*128+p.sampleCount*sizes(p.location)).U)
        v.allocationBase.poke(p.allocation.U);v.allocationLimit.poke(p.end.U)
        v.imageWidth.poke(1.U);v.imageHeight.poke(1.U);v.rowPitch.poke((p.sampleCount*sizes(p.location)).U)
        v.readable.poke((!p.resolve).B);v.writable.poke(true.B);v.store.poke(true.B)
        (if(p.resolve)q.image.resolvePitch(p.location)else q.image.sourcePitch(p.location)).poke(128.U)
      }
    }
    private def stable(valid:Boolean,ready:Boolean,bits: =>BigInt,old:Option[BigInt],name:String):Option[BigInt]={
      val current=if(valid || old.nonEmpty)bits else BigInt(0)
      old.foreach(v=>assert(valid && current==v,s"held $name changed during owner drain"))
      if(valid && !ready)Some(current)else None
    }
    def tick():Unit={
      memory.drive();d.io.out.ready.poke((!holdPixels && memory.cycle%5!=0).B)
      d.io.layerDone.ready.poke((!holdLayer && memory.cycle%7!=0).B)
      d.io.primitiveDone.ready.poke((!holdPrimitives && memory.cycle%3!=0).B)
      heldPixel=stable(d.io.out.valid.peek().litToBoolean,d.io.out.ready.peek().litToBoolean,d.io.out.bits.peek().litValue,heldPixel,"pixel")
      heldLayer=stable(d.io.layerDone.valid.peek().litToBoolean,d.io.layerDone.ready.peek().litToBoolean,d.io.layerDone.bits.peek().litValue,heldLayer,"layer")
      heldPrimitive=stable(d.io.primitiveDone.valid.peek().litToBoolean,d.io.primitiveDone.ready.peek().litToBoolean,d.io.primitiveDone.bits.peek().litValue,heldPrimitive,"primitive")
      if(d.io.launched.peek().litToBoolean)launches+=1
      if(d.io.trap.valid.peek().litToBoolean){
        assert(expectedStatus==5,"unexpected actual CU trap")
        d.io.trap.bits.event.pc.expect(0x9000.U);d.io.trap.bits.event.cause.expect(2.U);d.io.trap.bits.event.tval.expect(0.U)
        traps+=1
      }
      ownerMask|=d.io.owners.peek().litValue
      if(d.io.primitiveDone.valid.peek().litToBoolean && d.io.primitiveDone.ready.peek().litToBoolean){
        val r=d.io.primitiveDone.bits;val h=Vector(0,2,1)(primitives)
        r.source.ordinal.expect(h.U);r.source.reference.primitive.expect(h.U)
        r.source.reference.sequence.expect((10*(h+1)).U);r.source.reference.state.expect(h.U);r.source.reference.token.expect((200+h).U)
        if(expectedStatus==0)r.status.expect(0.U);primitives+=1
      }
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
        if(expectedStatus==0){val h=Vector(0,2,1)(pixels);d.io.out.bits.layer.expect((if(h==1)255 else 0).U)
          d.io.out.bits.pixels.primitiveId.expect((93+h).U);d.io.out.bits.pixels.status.expect(0.U)}
        pixels+=1
      }
      if(d.io.layerDone.valid.peek().litToBoolean && d.io.layerDone.ready.peek().litToBoolean){
        val r=d.io.layerDone.bits;r.layer.expect(layers.U)
        if(expectedStatus==0){r.image.status.expect(0.U);r.image.completed.expect((if(empty)0 else if(layers==0)2 else if(layers==255)1 else 0).U)}
        layers+=1
      }
      memory.capture();d.clock.step()
    }
    def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<200000){tick();n+=1}
      assert(condition,s"$label stalled cycle=${memory.cycle} layers=$layers launches=$launches primitives=$primitives pixels=$pixels")}
    def start():Unit={d.io.begin.valid.poke(true.B);until("layered tile begin")(d.io.begin.ready.peek().litToBoolean)
      tick();d.io.begin.valid.poke(false.B);zero(d.io.begin.bits)}
    private def clear(location:Int):Vector[BigInt]=location match {
      case 0=>Vector.fill(4)(fp(0.125));case 1|2=>Vector(0,1,2,3).map(BigInt(_))
      case _=>Vector(0.125,0.25,0.375,0.5).map(fp)
    }
    private def packed(location:Int,layer:Int):BigInt={
      val populated=layer==0 || layer==255
      if(location==4){if(populated)fp(if(layer==0)0.25 else 0.5)else fp(1)}
      else if(location==5){if(populated){if(layer==0)37 else 36}else 7}
      else {
        val rgba=if(!populated)clear(location)else location match {
          case 0=>Vector.fill(4)(fp(if(layer==0)0.75 else 0.375))
          case 1=>Vector(if(layer==0)95 else 94,11,12,13).map(BigInt(_))
          case 2=>Vector(layer,21,22,23).map(BigInt(_))
          case _=>Vector(0.25,0.25,0.75,0.5).map(fp)
        }
        encode(names(location),rgba)._1
      }
    }
    def checkBacking():Unit={
      // Enumerate actual addresses from the independent per-layer attachment
      // oracle. Guard bytes include all row/layer/line padding and both ends.
      for(p<-planes){val expected=mutable.Map.empty[BigInt,Int]
        for(layer<-0 until 256;s<-0 until p.sampleCount;b<-0 until sizes(p.location))
          expected(p.address(layer,s)+b)=((packed(p.location,layer)>>(8*b))&255).toInt
        for(a<-p.allocation until p.end)assert(memory.bytes(a)==expected.getOrElse(a,0xa7),
          s"layered attachment mismatch plane=${p.location} resolve=${p.resolve} address=0x${a.toString(16)} got=${memory.bytes(a)} expected=${expected.getOrElse(a,0xa7)}")
      }
      for((a,v)<-immutable)assert(memory.bytes.getOrElse(a,0)==v,s"immutable source changed 0x${a.toString(16)}")
      for((a,v)<-scratchGuards)assert(memory.bytes(a)==v,s"private FS prefix escaped its admitted view 0x${a.toString(16)}")
    }
    def illegalShader(enable:Boolean):Unit={
      val pc=stateFields.find(_.name=="fragment.shaderPc").get
      memory.putWord(0x700000+pc.word*4,if(enable)0x9000 else 0x1000)
      memory.putWord(0x9000,0)
    }
    def finish(status:Int=0,scanStage:Option[Int]=None,imageStage:Option[Int]=None):Unit={
      until("layered tile completion")(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;r.status.expect(status.U)
      scanStage.foreach(n=>r.replay.faultStage.expect(n.U));imageStage.foreach(n=>r.faultStage.expect(n.U))
      if(status==0){r.replay.completedLayers.expect(256.U);assert(layers==256)
        r.accepted.expect((if(empty)0 else 3).U);r.issued.expect((if(empty)0 else 3).U);r.completed.expect((if(empty)0 else 3).U)
        assert(primitives==(if(empty)0 else 3) && pixels==(if(empty)0 else 3) && launches==(if(empty)0 else 3))
        assert((memory.pending.isEmpty && memory.reply.isEmpty));checkBacking()}
      val held=r.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 7){tick();assert(r.peek().litValue==held);d.io.idle.expect(false.B)}
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until("all memory and CU ownership drained")(d.io.idle.peek().litToBoolean)
      assert(memory.pending.isEmpty && memory.reply.isEmpty && d.io.owners.peek().litValue==0)
    }
    def allLayers():Unit={for(i<-0 until 256)until(s"image layer $i retirement")(layers>i);finish()}
  }
  it should "execute mixed ordinary and GS records once through all 256 real typed image layers including empty clears and resolves" in {
    simulate(new LayeredTypedReplayImageHarness){d=>val t=new Driver(d);t.install();t.descriptor();t.trapLastStore=true;t.start()
      // Delay the real memory acknowledgement, rather than an unrelated
      // synthetic completion. The tile and final image layer remain owned.
      for(i<-0 until 255)t.until(s"image layer $i retirement")(t.layers>i);t.until("last layer store acknowledgement offered")(t.heldLastStore)
      for(_<-0 until 11){t.tick();assert(t.layers==255);d.io.completion.valid.expect(false.B)}
      t.trapLastStore=false;t.memory.holdResponses=false;t.allLayers()
      // A second empty pass must LOAD all six real planes and resolve them,
      // retaining populated layers and all guards without rerunning any FS.
      t.descriptor(noReferences=true,load=true);t.start();t.allLayers()
      t.descriptor();t.start();t.allLayers()
    }
  }
  it should "drain held real image results and acknowledged memory across cancel and source faults before no-reset reuse" in {
    simulate(new LayeredTypedReplayImageHarness){d=>val t=new Driver(d);t.install()
      t.holdPrimitives=true;t.expectedStatus=4;t.descriptor();t.start()
      t.until("held original primitive ownership")(d.io.primitiveDone.valid.peek().litToBoolean)
      for(_<-0 until 9){t.tick();assert(t.launches==1 && t.primitives==0)}
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){t.tick();assert(t.launches==1);d.io.completion.valid.expect(false.B)}
      t.holdPrimitives=false;t.finish(4)
      t.holdPixels=true;t.descriptor();t.start()
      t.until("held pixel output")(d.io.out.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdPixels=false;t.finish(4)
      t.holdLayer=true;t.descriptor();t.start();t.until("held layer completion")(d.io.layerDone.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdLayer=false;t.finish(4)
      for(pageFault<-Seq(true,false)){
        t.expectedStatus=3;t.memory.faultAt=Set(if(pageFault)BigInt(0x400000)else BigInt(0x200000+t.recordBytes))
        t.descriptor();t.start();t.finish(3,scanStage=Some(if(pageFault)2 else 3));t.memory.faultAt=Set.empty
      }
      // A physical image-store fault follows two actual FS invocations in
      // layer0. Its primary image fault must survive scanner/child cancel4.
      t.memory.faultAt=Set(t.planes.find(p=> !p.resolve && p.location==0).get.base& ~BigInt(63))
      t.descriptor();t.start();t.finish(3,imageStage=Some(3));assert(t.launches==2);t.memory.faultAt=Set.empty
      // Real illegal-instruction trap2 at an otherwise unused shader PC.
      // The resulting primitive shader-fault5 must be locked before child
      // cancellation/drain, rather than replaced by its cancelled4 result.
      t.expectedStatus=5;t.illegalShader(true);t.descriptor();t.start();t.finish(5,imageStage=Some(3))
      assert(t.launches==1 && t.traps==1);t.illegalShader(false)
      t.expectedStatus=0;t.descriptor();t.start();t.allLayers()
    }
  }
}
