package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationNativeJobImageSharedHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)
  val hostSlots=TessellationNativeJobMemoryFabric.transactions(TessellationNativeRecordMemoryFabric.transactions(TessellationInputMemoryFabric.transactions(TessellationSystemIsolineImagePipeline.memoryTransactions(2,2,8))))
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val frame=Input(new TypedVertexBinImageDescriptor)
    val template=Input(new TessellationPatchSequenceDescriptor(cfg,p,4))
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(4)))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(2,Decoupled(new TypedVertexBinImageTileCompletion))
    val done=Decoupled(new TessellationNativeJobCompletion(4,p));def completion=done
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val context=Input(UInt(32.W))
    val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
  })
  private val stage=Module(new TessellationNativeJobImage(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),
    g=g,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=Some(payload)))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,tessellationAvailable=true,graphicsHostTransactions=hostSlots,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=Some(payload),producerPayloadPins=TessellationSystemIsolineImagePipeline.payloadPins(2)))
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io);tie(stage.io)
  stage.io.submit<>io.submit
  stage.io.inspect<>io.inspect;io.reply<>stage.io.reply
  io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out
  io.tileDone<>stage.io.tileDone;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  private val hostQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,hostSlots),2,pipe=true))
  hostQueue.io.enq<>stage.io.memoryRequest;system.io.graphicsHostRequest<>hostQueue.io.deq;stage.io.memoryResponse<>system.io.graphicsHostResponse
  private val lowerQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lowerQueue.io.enq<>system.io.memoryRequest;io.memoryRequest<>lowerQueue.io.deq;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsDrained:= !stage.io.busy && hostQueue.io.count===0.U
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  val management=system.io.payloadManagement.get
  for(i<-0 until 3){val port=stage.io.payload.get(i)
    management.control(i)<>port.control;port.controlResponse<>management.controlResponse(i)
    management.request(i)<>port.request;port.response<>management.response(i)
    port.context:=io.context;port.cancel:=false.B}
  system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
  stage.io.satp:="h80000030".U;stage.io.flush.valid:=false.B;stage.io.flush.bits:=0.U.asTypeOf(stage.io.flush.bits)
  io.reserved:=management.reservedBytes;io.poolDrained:=management.drained;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class TessellationNativeJobImageSharedSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.fp
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,rd:Int=0,vs1:Int=0,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base:Int,v:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val controlProgram=Vector(BigInt("0104f057",16),vector(23,3,2,0),vector(23,3,4,0),
    patch(21,rd=10,vs1=2,vs2=4),patch(20,vs1=10),vector(23,3,4,7),patch(22,vs1=2,vs2=4),
    vector(23,3,4,15),patch(22,vs1=2,vs2=4),vector(23,3,4,3),patch(22,vs1=2,vs2=4),
    BigInt("0000000b",16),BigInt("30500073",16))
  private val isolineProgram=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
    BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
    BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
    geometry(33,0,4,5),BigInt("00300113",16),geometry(32,3,1,2),BigInt("00400213",16),
    geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private val fragmentProgram={
    val stride=32;val inputs=10
    val code=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
    code+=addi(5,7,6*stride);code+=vm(5,1,false);code+=addi(6,7,inputs*stride);code+=vm(6,1,true)
    code+=BigInt("30500073",16);code.toVector
  }
  it should "shade controlled isoline patches into typed images on shared CUs" in {
    simulate(new TessellationNativeJobImageSharedHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      memory.putWord(0x30000,BigInt(0xc7));memory.putWord(0x30004,(BigInt(0x800)<<10)|0xc7)
      for(base<-Seq(0x8f7000,0x8f7100,0x8f7200,0x8f7300,0x8f7400)){memory.putWord(base,BigInt("3f800000",16));memory.putWord(base+0x20,BigInt("3f800000",16))}
      controlProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
      isolineProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+i*4,w)}
      fragmentProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+i*4,w)}
      val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(2,4)))
      val clear=Vector(0.1,0.2,0.3,0.4).map(fp)
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      var holdInput=false;var holdJob=false
      def tick():Unit={
        memory.drive()
        if(d.io.memoryRequest.valid.peek().litToBoolean && ((holdInput && d.io.memoryRequest.bits.address.peek().litValue==0x8f7200) || (holdJob && d.io.memoryRequest.bits.address.peek().litValue==0x8d0000)))
          d.io.memoryRequest.ready.poke(false.B)
        for(e<-0 until 2){d.io.out(e).ready.poke(true.B);d.io.primitiveDone(e).ready.poke(true.B);d.io.tileDone(e).ready.poke(true.B)}
        memory.capture();d.clock.step()
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<200000){tick();n+=1}
        assert(condition,s"$label stalled at ${memory.cycle} pending=${memory.pending.size}")}
      val stateBase=0x8000;for(w<-0 until 128)memory.putWord(stateBase+w*4,0)
      for((v,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,0).zipWithIndex)memory.putWord(stateBase+i*4,v)
      val scratch0=0x100000;val scratch1=0x110000
      val prefix=FragmentShaderConfig(2,4).prefixBytes(8,1,1,FragmentInputAbi.center)
      val values=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->FragmentInputAbi.center,"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
        "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->1,"fragment.colorSlots"->1,"fragment.colorMask"->1,"fragment.sampleMode"->0,
        "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->scratch0,"fragment.scratch.limit"->(scratch0+prefix),"fragment.scratch.allocationBase"->scratch0,"fragment.scratch.allocationLimit"->(scratch0+prefix),
        "scratch[0].base"->scratch1,"scratch[0].limit"->(scratch1+prefix),"scratch[0].allocationBase"->scratch1,"scratch[0].allocationLimit"->(scratch1+prefix),
        "tests.version"->1,"tests.depthEnable"->0,"tests.front.function"->6,"tests.back.function"->6,"blend.version"->1)
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(stateBase+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}

      val dsc=d.io.frame;zero(dsc);dsc.version.poke(1.U)
      val a=dsc.bins.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+32*d.recordBytes).U)
      a.screenWidth.poke(4.U);a.screenHeight.poke(4.U);a.tileLog2.poke(3.U)
      val b=dsc.bins.bins;b.version.poke(3.U);b.tileCount.poke(1.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500080.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(7.U)
      dsc.states.version.poke(1.U);identity(dsc.states.identity);dsc.states.base.poke(0x8000.U);dsc.states.limit.poke(0x8400.U);dsc.scratchStride.poke(4096.U)
      for(e<-0 until 2){dsc.scratch(e).base.poke((0x100000+e*0x10000).U);dsc.scratch(e).limit.poke((0x102000+e*0x10000).U)}
      val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U);c.reservedBytes.poke(4096.U)
      val color=c.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U);color.offset.poke(4096.U)
      color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U);color.clear.zip(clear).foreach{case(x,y)=>x.poke(y.U)}
      val imageView=im.views(0);imageView.base.poke(0x600000.U);imageView.limit.poke(0x600040.U)
      imageView.allocationBase.poke(0x600000.U);imageView.allocationLimit.poke(0x600040.U)
      imageView.imageWidth.poke(4.U);imageView.imageHeight.poke(4.U);imageView.rowPitch.poke(16.U)
      imageView.readable.poke(true.B);imageView.writable.poke(true.B);imageView.store.poke(true.B)
      for(a<-0xa00000 until 0xa00040)memory.bytes(a)=0
      d.io.context.poke(101.U)
        zero(d.io.template);d.io.template.count.poke(2.U);d.io.template.patchStride.poke(32.U)
        val task=d.io.template.template;val t=task.source;val view=t.viewport;val src=view.system.patch;val ctl=src.control
        identity(view.identity)
        view.viewport.version.poke(1.U);view.viewport.extentX.poke(4.U);view.viewport.extentY.poke(4.U);view.viewport.farDepth.poke("h3f800000".U)
        src.commandId.poke(0.U);src.evaluationKernel.kernelPc.poke(0x3000.U);src.outputComponents.poke(5.U)
        src.patchInputCount.poke(1.U);src.patchInputs(0).perPatch.poke(true.B)
        for(i<-Seq(0,1,3)){view.componentSources(i).valid.poke(true.B);view.componentSources(i).bits.poke(i.U)}
        view.varyingSlots.poke(1.U);view.componentSources(4).valid.poke(true.B);view.componentSources(4).bits.poke(4.U)
        ctl.patch.context.taskId.poke(19.U);ctl.patch.context.layout.inputVertices.poke(1.U);ctl.patch.context.layout.inputComponents.poke(1.U)
        ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U)
        ctl.kernel.kernelPc.poke(0x1000.U);for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
        ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U)
        val inputBase=0x4f7000
        task.input.allocationBase.poke(inputBase.U);task.input.allocationLimit.poke((inputBase+0x40).U)
        task.input.base.poke(inputBase.U);task.input.limit.poke((inputBase+0x40).U);task.stride.poke(4.U)
        t.raster.version.poke(1.U);t.raster.pointSize.poke("h3f800000".U);t.raster.screenW.poke(4.U);t.raster.screenH.poke(4.U)
        t.state.poke(0.U);t.token.poke(219.U)

      def value(d:Data):BigInt=d match {case b:Bool=>b.peek().litValue;case u:UInt=>u.peek().litValue;case s:SInt=>s.peek().litValue}
      val drawFields=TessellationNativeSequenceLayout.fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val drawValues=WindowPrimitiveRecordLayout.leaves(d.io.template).toMap
      for(i<-0 until TessellationNativeSequenceLayout.words)memory.putWord(0x8e0000+i*4,0)
      for((v,i)<-Seq(TessellationNativeSequenceLayout.magic,BigInt(TessellationNativeSequenceLayout.bytes),BigInt(1)).zipWithIndex)memory.putWord(0x8e0000+i*4,v)
      for(field<-drawFields){val raw=drawValues.get(field.name).map(value).getOrElse(BigInt(0)) & ((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(0x8e0000+(field.word+w)*4,(raw>>(32*w))&0xffffffffL)}
      val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
      val job=header++Map[String,BigInt]("job.identity.context"->7,"job.identity.pass"->8,"job.identity.generation"->9,
        "job.frameBase"->0x4d1000,"job.frameLimit"->0x4d1800,"job.drawBase"->0x4e0000,"job.drawLimit"->0x4e0800,"job.drawCount"->1,"job.drawStride"->2048)
      val frameValues=header++WindowPrimitiveRecordLayout.leaves(d.io.frame).map{case(n,v)=>("frame."+n)->value(v)}.toMap
      def put(base:Int,words:Int,magic:BigInt,fields:Seq[NativeTypedGraphicsLayout.Field],values:Map[String,BigInt]):Unit={
        for(w<-0 until words)memory.putWord(base+w*4,0);memory.putWord(base,magic);memory.putWord(base+4,words*4);memory.putWord(base+8,1)
        for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0)) & ((BigInt(1)<<(field.words*32))-1)
          for(w<-0 until field.words)memory.putWord(base+(field.word+w)*4,(raw>>(32*w))&0xffffffffL)}}
      put(0x8d0000,NativeBinnedGraphicsLayout.jobWords,TessellationNativeJobLayout.magic,NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),job)
      put(0x8d1000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),frameValues)
      d.io.submit.bits.commandId.poke(3.U);d.io.submit.bits.base.poke(0x4d0000.U)
      d.io.submit.bits.limit.poke((0x4d0000+NativeBinnedGraphicsLayout.jobBytes).U);d.io.submit.bits.envelopeValid.poke(true.B)
      def submit():Unit={d.io.submit.valid.poke(true.B);until("job submit")(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)}
      def finish(status:Int):Unit={until("job completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);val held=d.io.completion.bits.peek().litValue
        for(_<-0 until 4){tick();d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        until("idle")(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B)
        assert(memory.pending.isEmpty && memory.reply.isEmpty)}
      submit();until("render completion")(d.io.completion.valid.peek().litToBoolean)
      val done=d.io.completion.bits;done.status.expect(0.U);done.issuedDraws.expect(1.U);done.completedDraws.expect(1.U)
      done.frame.img.source.tasks.expect(2.U);assert(done.frame.img.primitives.peek().litValue>=2);done.frame.img.tiles.expect(1.U)
      finish(0)
      val reds=(0 until 16).map(i=>memory.bytes.getOrElse(0xa00000+i*4,0))
      assert(reds.contains(255) && reds.contains(26),s"expected shaded red and clear pixels, got $reds")
      memory.faultAt=Set(BigInt(0x8d0000));submit();finish(3);memory.faultAt=Set.empty
      holdJob=true;submit();until("held job request")(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.address.peek().litValue==0x8d0000)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);tick()}
      holdJob=false;finish(4)
      submit();finish(0)
      assert(Seq(0x8d0000,0x8d1000,0x8e0000,0x8f7000,0xa00000).forall(a=>memory.seen.exists(_.address==a)))
      assert(!memory.seen.exists(r=>Set[BigInt](0x4d0000,0x4d1000,0x4e0000,0x4f7000,0x600000)(r.address)),"virtual job or image address escaped translation")
      info(s"NATIVE_TESSELLATION_JOB_IMAGE draws=1 patches=2 translatedRecords=true cancellation=true memoryFault=true recovery=true cycles=${memory.cycle}")
    }
  }
}
