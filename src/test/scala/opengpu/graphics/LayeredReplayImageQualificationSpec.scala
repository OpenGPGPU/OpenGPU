package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.{GpuSim,GraphicsTestResolution}
import GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class LayeredReplayImageHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2);val f=FragmentShaderConfig(2,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  private val slots=GpuSystem.totalMemoryTransactions(1,4,32)
  private val stage=Module(new TypedReplayTileImagePipeline(cfg,p,f,SharedCuShaderConfig(1,taskIdWidth=4),g,geometryInput=true))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,enableGraphicsKernels=true,
    graphicsHostTransactions=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedReplayTileDescriptor));val selectedLayer=Input(UInt(32.W))
    val in=Flipped(Decoupled(new WindowPrimitiveReplay(p)));val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion)
    val end=Flipped(Decoupled(Bool()));val completion=Decoupled(new TypedReplayTileCompletion);val out=Decoupled(new TypedTilePixelQuadCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(system.io);tie(stage.io)
  stage.io.begin<>io.begin;stage.io.selectedLayer.get:=io.selectedLayer;stage.io.in<>io.in
  io.primitiveDone<>stage.io.primitiveDone;stage.io.end<>io.end;io.completion<>stage.io.completion;io.out<>stage.io.out;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class LayeredReplayImageQualificationSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  import WindowRecordTest.Memory
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "shade each replay only into the caller-selected layer view" in {
    simulate(new LayeredReplayImageHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      val stride=32;val inputs=18
      val fs=scala.collection.mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,(inputs-2+i)*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+4*i,w)}
      var outputs=0
      def tick():Unit={memory.drive();d.io.out.ready.poke((memory.cycle%5!=0).B)
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){d.io.out.bits.status.expect(0.U);outputs+=1}
        memory.capture();d.clock.step()}
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<100000){tick();n+=1};assert(condition,s"$label stalled at ${memory.cycle} outputs=$outputs")}
      val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(d.f))
      def putState(address:BigInt,scratch:BigInt):Unit={
        for(w<-0 until 128)memory.putWord(address+w*4,0)
        for((v,w)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,5,6,7,0).zipWithIndex)memory.putWord(address+w*4,v)
        val prefix=d.f.prefixBytes(8,2,1,FragmentInputAbi.geometry)
        val values=Map[String,BigInt]("version"->1,"engineCount"->1,"fragment.version"->FragmentInputAbi.geometry,
          "fragment.identity.context"->5,"fragment.identity.pass"->6,"fragment.identity.generation"->7,
          "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->2,"fragment.colorSlots"->1,"fragment.colorMask"->1,
          "fragment.colorTypes[0]"->1,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
          "fragment.scratch.base"->scratch,"fragment.scratch.limit"->(scratch+prefix),
          "fragment.scratch.allocationBase"->scratch,"fragment.scratch.allocationLimit"->(scratch+prefix),"tests.version"->1)
        for(field<-fields;word<-0 until field.words){val bits=values.getOrElse(field.name,BigInt(0));memory.putWord(address+(field.word+word)*4,(bits>>(32*word))&0xffffffffL)}
      }
      for((layer,index)<-Seq((9,0),(4,1))){val stateBase=BigInt(0x200000+index*0x1000);val scratch=BigInt(0x100000+index*0x1000);val image=BigInt(0x400000+index*0x10000)
        putState(stateBase,scratch);zero(d.io.begin.bits);val b=d.io.begin.bits
        b.states.version.poke(1.U);b.states.identity.context.poke(5.U);b.states.identity.pass.poke(6.U);b.states.identity.generation.poke(7.U)
        b.states.base.poke(stateBase.U);b.states.limit.poke((stateBase+512).U);b.engines.poke(1.U);b.scratchStride.poke(4096.U)
        b.scratch(0).base.poke(scratch.U);b.scratch(0).limit.poke((scratch+4096).U)
        b.image.version.poke(1.U);b.image.pixels.version.poke(1.U);val c=b.image.pixels.colors;c.version.poke(1.U)
        c.identity.context.poke(5.U);c.identity.pass.poke(6.U);c.identity.generation.poke(7.U)
        c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U)
        val color=c.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA32UI").U)
        color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U)
        val view=b.image.views(0);view.base.poke(image.U);view.limit.poke((image+256).U);view.allocationBase.poke(image.U);view.allocationLimit.poke((image+4096).U)
        view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(64.U);view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
        d.io.selectedLayer.poke(layer.U);d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
        d.io.selectedLayer.poke((if(layer==9)4 else 9).U)
        for((recordLayer,ordinal)<-Seq((4,0),(9,1))){zero(d.io.in.bits);val q=d.io.in.bits
          primitive(q.record.primitive,base);state(q.record.raster,Settings(width=4,height=4))
          q.source.identity.context.poke(5.U);q.source.identity.pass.poke(6.U);q.source.identity.generation.poke(7.U)
          q.source.ordinal.poke(ordinal.U);q.source.reference.sequence.poke(ordinal.U);q.record.sequence.poke(ordinal.U)
          q.record.geometry.valid.poke(true.B);q.record.geometry.bits.inputPrimitiveId.poke(27.U)
          q.record.geometry.bits.invocationId.poke(3.U);q.record.geometry.bits.layer.poke(recordLayer.U)
          d.io.in.valid.poke(true.B);until("record")(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
          until("record completion")(d.io.primitiveDone.valid.peek().litToBoolean)
          d.io.primitiveDone.bits.status.expect(0.U);d.io.primitiveDone.bits.layerSkipped.expect((recordLayer!=layer).B)
          d.io.primitiveDone.bits.renderStarted.expect((recordLayer==layer).B)
          if(recordLayer!=layer)d.io.primitiveDone.bits.stateReadLines.expect(0.U)
          d.io.primitiveDone.ready.poke(true.B);tick();d.io.primitiveDone.ready.poke(false.B)
        }
        d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
        until("pass completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
        d.io.completion.bits.accepted.expect(2.U);d.io.completion.bits.issued.expect(1.U);d.io.completion.bits.completed.expect(1.U)
        assert(memory.word(image)==17 && memory.word(image+4)==layer && memory.pending.isEmpty && memory.reply.isEmpty)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until("idle")(d.io.idle.peek().litToBoolean)
      }
      assert(outputs>0)
    }
  }
}
