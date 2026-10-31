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

private class TypedGeometryImageHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),2);val f=FragmentShaderConfig(2,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  private val slots=GpuSystem.totalMemoryTransactions(1,4,16)
  private val stage=Module(new TypedTileImagePipeline(cfg,p,f,SharedCuShaderConfig(1,taskIdWidth=4),g,
    preparedGeometry=true,geometryInput=true))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,enableGraphicsKernels=true,
    graphicsHostTransactions=16,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTileImageDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(p,f)));val primitive=Input(new WindowPrimitive(p));val geometry=Input(Valid(UInt(32.W)))
    val drawCompletion=Decoupled(new TypedTileRenderDrawCompletion);val completion=Decoupled(new TypedTileImageCompletion)
    val out=Decoupled(new TypedTilePixelQuadCompletion)
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
  stage.io.begin<>io.begin;stage.io.end<>io.end;stage.io.draw<>io.draw
  stage.io.primitive.get:=io.primitive;stage.io.geometry.get:=io.geometry
  io.drawCompletion<>stage.io.drawCompletion;io.completion<>stage.io.completion;io.out<>stage.io.out;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class TypedGeometryImageQualificationSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  import WindowRecordTest.Memory
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "publish geometry fragment builtins through a typed image attachment" in {
    simulate(new TypedGeometryImageHarness){d=>
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
      val b=d.io.begin.bits;b.version.poke(1.U);b.pixels.version.poke(1.U);val c=b.pixels.colors;c.version.poke(1.U)
      c.identity.context.poke(5.U);c.identity.pass.poke(6.U);c.identity.generation.poke(7.U)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U)
      val color=c.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA32UI").U)
      color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U)
      val view=b.views(0);view.base.poke(0x400000.U);view.limit.poke(0x400100.U)
      view.allocationBase.poke(0x400000.U);view.allocationLimit.poke(0x401000.U)
      view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(64.U);view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      val q=d.io.draw.bits;q.version.poke(1.U);q.shaders.version.poke(1.U);q.tests.version.poke(1.U)
      primitive(d.io.primitive,base);state(q.shaders.vertex.raster,Settings(width=4,height=4,scissor=true,maxX=2,maxY=4))
      val f=q.shaders.fragment;f.version.poke(FragmentInputAbi.geometry.U);f.identity.context.poke(5.U);f.identity.pass.poke(6.U);f.identity.generation.poke(7.U)
      f.shaderPc.poke(0x2000.U);f.varyingSlots.poke(2.U);f.colorSlots.poke(1.U);f.colorMask.poke(1.U);f.colorTypes(0).poke(1.U)
      val prefix=d.f.prefixBytes(8,2,1,FragmentInputAbi.geometry);f.scratch.base.poke(0x100000.U);f.scratch.limit.poke((0x100000+prefix).U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke((0x100000+prefix).U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      d.io.geometry.valid.poke(true.B);d.io.geometry.bits.poke(9.U)
      d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      d.io.geometry.bits.poke(99.U)
      until("draw completion")(d.io.drawCompletion.valid.peek().litToBoolean);d.io.drawCompletion.bits.status.expect(0.U)
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B)
      d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until("image completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      assert(outputs>0 && memory.word(0x400000)==17 && memory.word(0x400004)==9)
      assert(memory.word(0x400020)==0,"fragment escaped the selected tile scissor")
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
  }
}
