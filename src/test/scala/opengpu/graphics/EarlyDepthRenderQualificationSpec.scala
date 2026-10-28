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
import scala.collection.mutable

private class EarlyDepthRenderHarness extends Module {
  val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val vertex=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1)
  val fragment=FragmentShaderConfig(1,4)
  val graphics=GraphicsConfig(screenWidth=GraphicsTestResolution.width.min(8),screenHeight=GraphicsTestResolution.height.min(8))
  val storage=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,nativeSamples=true,streamingWordPorts=8)
  private val transactions=GpuSystem.totalMemoryTransactions(1,4,8)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTilePixelDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(vertex,fragment)));val primitive=Input(new WindowPrimitive(vertex))
    val drawCompletion=Decoupled(new TypedTileRenderDrawCompletion);val completion=Decoupled(new TypedTileRenderCompletion)
    val out=Decoupled(new TypedTilePixelQuadCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val maintenance=Input(Bool());val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(storage)))
    val reply=Decoupled(new TypedTileColorMemoryReply);val cancel=Input(Bool());val launched=Output(Bool());val idle=Output(Bool())
  })
  private val stage=Module(new TypedTileRenderPipeline(config,vertex,fragment,SharedCuShaderConfig(1),graphics,
    storage=storage,preparedGeometry=true))
  private val system=Module(new GpuSystem(config,numComputeUnits=1,enableGraphicsKernels=true,enableFpuBackend=false,
    graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.begin<>io.begin;stage.io.end<>io.end;stage.io.draw<>io.draw;stage.io.primitive.get:=io.primitive
  io.drawCompletion<>stage.io.drawCompletion;io.completion<>stage.io.completion;io.out<>stage.io.out
  stage.io.maintenance:=io.maintenance;stage.io.memory<>io.memory;io.reply<>stage.io.reply;stage.io.cancel:=io.cancel
  stage.io.refill.ready:=false.B;stage.io.refillReply.valid:=false.B;stage.io.refillReply.bits:=0.U.asTypeOf(stage.io.refillReply.bits)
  system.io.graphicsKernels.get<>stage.io.shared;io.launched:=stage.io.shared.launch.fire
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

/** Actual CU/raster/native-owner qualification stays local, outside PR/nightly
  * resource budgets. The bounded numeric, shader-owner and ABI tests run in CI.
  */
class EarlyDepthRenderQualificationSpec extends AnyFlatSpec {
  require(sys.props.get("opengpu.earlyDepthQualification").contains("1"),"run explicitly with -Dopengpu.earlyDepthQualification=1")
  require(!sys.env.get("CI").exists(_.equalsIgnoreCase("true")),"early depth rendering qualification is local-only")
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.fp
  private val scratch=BigInt(0x100000);private val prefix=384
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|0x27
  private val controls: Vector[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for((offset,array)<-Seq((prefix,10),(prefix+4,11)))code++=Seq(addi(13,1,offset),
      (BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,array*32),vm(6,1))
    code+=BigInt("30500073",16);code.toVector
  }
  private val sideEffect=Vector[BigInt]((BigInt(0x300)<<12)|(BigInt(5)<<7)|0x37,addi(6,0,0x123),
    (BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|0x23,BigInt("30500073",16))
  private case class DrawResult(logical: Vector[Vector[BigInt]],words: Vector[BigInt],launches: Int,shaded: BigInt,rejected: BigInt)
  private class Driver(val d: EarlyDepthRenderHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.putWord(0x1000,BigInt("30500073",16))
    controls.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+4*i,w)}
    sideEffect.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+4*i,w)}
    var launches=0;var mode=0;var generation=0;var outputs=Vector.empty[Vector[BigInt]]
    def drawScratch(ordinal: Int): BigInt=scratch+generation*0x10000+ordinal*0x1000
    def tick(): Unit={
      memory.drive();d.io.out.ready.poke((memory.cycle%5!=0).B)
      if(d.io.launched.peek().litToBoolean)launches+=1
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){val q=d.io.out.bits
        q.status.expect(0.U)
        outputs :+=Vector(q.sequence,q.primitiveId,q.instanceId,q.partIndex,q.status,q.testedSamples,q.passingSamples,
          q.stencilRejected,q.depthRejected,q.depthUpdates,q.stencilUpdates,q.colors.touchedSamples,q.colors.completedLocations,
          q.colors.wordWrites,q.colors.writtenBits).map(_.peek().litValue)
      }
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean): Unit={var cycles=0;while(!condition && cycles<200000){tick();cycles+=1}
      assert(condition,s"early/late renderer stalled at ${memory.cycle}, launches=$launches, outputs=${outputs.size}")}
    def identity(id: GraphicsWorkIdentity): Unit={id.context.poke(5.U);id.pass.poke(6.U);id.generation.poke(7.U)}
    def begin(sampleMode: Int): Unit={
      generation+=1;mode=sampleMode;val q=d.io.begin.bits;zero(q);q.version.poke(1.U);q.colors.version.poke(1.U);identity(q.colors.identity)
      q.colors.tileLog2.poke(3.U);q.colors.tileWidth.poke(8.U);q.colors.tileHeight.poke(8.U);q.colors.sampleMode.poke(mode.U)
      val bytes=256<<mode;q.colors.reservedBytes.poke(bytes.U)
      q.depth.format.poke(TypedDepthFormat.d24s8.U);q.depth.loadOp.poke(AttachmentLoadOp.clear.U)
      q.depth.clearDepth.poke(fp(1).U);q.depth.clearStencil.poke(5.U)
      val color=q.colors.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U)
      color.offset.poke(bytes.U);color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U)
      color.clear.foreach(_.poke(fp(1).U))
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.draw.ready.peek().litToBoolean)
    }
    def snapshot(): Vector[BigInt]={
      val result=Vector.newBuilder[BigInt];d.io.maintenance.poke(true.B)
      for(address<-0 until (64<<mode)){
        zero(d.io.memory.bits);d.io.memory.bits.address.poke(address.U);d.io.memory.valid.poke(true.B)
        until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
        until(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.available.expect(true.B);d.io.reply.bits.fault.expect(false.B)
        result+=d.io.reply.bits.data.peek().litValue
        d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B)
      }
      d.io.maintenance.poke(false.B);until(d.io.draw.ready.peek().litToBoolean);result.result()
    }
    def draw(ordinal: Int,safe: Boolean,depth: Double,pc: Int=0x1000,overrideDepth: Boolean=false,stencil: Boolean=false,partial: Boolean=false): DrawResult={
      outputs=Vector.empty;val before=launches
      val values=FragmentInterpolationReference.Data(Vector.fill(3)(Fp32ViewportReference.decode(fp(1))),Vector.fill(3)(fp(depth)),Vector.fill(3)(Vector.empty),0,0)
      val coords=if(partial)Vector((256,0),(8*256,256),(0,8*256))else Vector((0,0),(16*256,0),(0,16*256))
      val triangle=TriangleRasterReference.Triangle(coords.map{case(x,y)=>(BigInt(x),BigInt(y))},values)
      val expected=TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=8,height=8,mode=mode),d.graphics)
      TriangleRasterReference.primitive(d.io.primitive,triangle,sequence=ordinal)
      val q=d.io.draw.bits;zero(q);q.version.poke(1.U);q.shaders.version.poke(1.U)
      TriangleRasterReference.state(q.shaders.vertex.raster,TriangleRasterReference.Settings(width=8,height=8,mode=mode))
      val f=q.shaders.fragment;f.version.poke(1.U);identity(f.identity);f.shaderPc.poke(pc.U)
      val privateView=drawScratch(ordinal)
      f.scratch.base.poke(privateView.U);f.scratch.limit.poke((privateView+512).U)
      f.scratch.allocationBase.poke(privateView.U);f.scratch.allocationLimit.poke((privateView+512).U)
      f.colorSlots.poke(1.U);f.colorMask.poke(1.U);f.sampleMode.poke(mode.U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      f.earlyDepthSafe.poke(safe.B);f.allowDepthOverride.poke(overrideDepth.B)
      val t=q.tests;t.version.poke(1.U);t.depthEnable.poke(true.B);t.depthWrite.poke(true.B);t.depthFunction.poke(0.U)
      t.stencilEnable.poke(stencil.B)
      for(face<-Seq(t.front,t.back)){face.function.poke(6.U);face.readMask.poke(255.U);face.writeMask.poke(255.U);face.depthFail.poke(5.U)}
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      until(d.io.drawCompletion.valid.peek().litToBoolean);val r=d.io.drawCompletion.bits
      r.status.expect(0.U);r.admittedQuads.expect(expected.size.U);r.retiredQuads.expect(expected.size.U);r.droppedQuads.expect(0.U)
      val shaded=r.source.fragment.shadedLanes.peek().litValue;val rejected=r.source.fragment.earlyRejectedQuads.peek().litValue
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B)
      assert(outputs.size==expected.size);val logical=outputs;val words=snapshot()
      DrawResult(logical,words,launches-before,shaded,rejected)
    }
    def finish(): Unit={
      d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
  }
  it should "match actual late rendering and stencil outcomes while removing only certified occluded shader work" in {
    simulate(new EarlyDepthRenderHarness){d=>
      val h=new Driver(d)
      for(mode<-Seq(0,2)){
        def run(early: Boolean): Vector[DrawResult]={
          h.begin(mode);h.memory.putWord(0x300000,0)
          val near=h.draw(0,safe=early,depth=0.25)
          val far=h.draw(1,safe=early,depth=0.75,stencil=true)
          // Publish immutable uniforms in fresh private views. Direct mutation
          // of backing RAM is not a coherent CPU write to a cached GPU line.
          h.memory.putWord(h.drawScratch(2)+prefix,fp(0.125));h.memory.putWord(h.drawScratch(2)+prefix+4,0)
          val discard=h.draw(2,safe=false,depth=0.75,pc=0x2000,stencil=true)
          h.memory.putWord(h.drawScratch(3)+prefix,fp(0.125));h.memory.putWord(h.drawScratch(3)+prefix+4,7)
          val overrideDepth=h.draw(3,safe=early,depth=0.75,pc=0x2000,overrideDepth=true)
          val effect=h.draw(4,safe=false,depth=0.75,pc=0x3000)
          val partial=h.draw(5,safe=early,depth=0.75,stencil=true,partial=true)
          h.finish();assert(h.memory.word(0x300000)==0x123,"side-effect shader was skipped")
          Vector(near,far,discard,overrideDepth,effect,partial)
        }
        val late=run(false);val early=run(true)
        for((a,b)<-late.zip(early)){assert(a.logical==b.logical,"early/late sample retirement differs");assert(a.words==b.words,"early/late canonical image/depth/stencil differs")}
        assert(late(1).launches>0 && late(1).shaded>0 && late(1).rejected==0)
        assert(early(1).launches==0 && early(1).shaded==0 && early(1).rejected==16)
        for(i<-Seq(2,3,4)){assert(early(i).launches>0 && early(i).shaded>0 && early(i).rejected==0,"unsafe shader bypassed late admission")}
        assert(early(5).launches==0 && early(5).shaded==0 && early(5).rejected==early(5).logical.size)
        assert(early(5).logical.map(_(5)).sum>0 && early(5).logical.map(_(5)).sum<(64<<mode))
        assert(early(1).logical.map(_(8)).sum==(64<<mode))
        assert(early(1).logical.map(_(10)).sum==(64<<mode))
        assert(early(2).logical.map(_(5)).sum==0,"discard must precede late DS/stencil")
        assert(early(3).logical.map(_(9)).sum==(64<<mode),"shader depth override must reach late testing")
        info(s"EARLY_LATE_RENDER mode=$mode samples=${64<<mode} rejectedQuads=${early(1).rejected} removedShaderLaunches=${late(1).launches} removedShadedLanes=${late(1).shaded} exactCanonicalWords=true stencilDepthFail=true discardFallback=true overrideFallback=true sideEffectFallback=true")
      }
    }
  }
}
