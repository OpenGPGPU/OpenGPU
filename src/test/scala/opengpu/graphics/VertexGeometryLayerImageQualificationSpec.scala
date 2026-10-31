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

private class VertexGeometryLayerImageHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(1,1),1);val f=FragmentShaderConfig(1,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  private val stage=Module(new VertexGeometryBinImagePipeline(cfg,p,f,SharedCuShaderConfig(1,taskIdWidth=4),g,
    engines=1,pageBytes=128,writeCredits=2,tileSlots=2,pagedCapacity=16))
  private val slots=GpuSystem.totalMemoryTransactions(1,4,stage.memoryTransactions)
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,enableGraphicsKernels=true,
    geometryAvailable=true,graphicsHostTransactions=stage.memoryTransactions,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new opengpu.system.LayeredGeometryBinImageDescriptor))
    val draw=Flipped(Decoupled(new VertexGeometryBinDraw(cfg,p,4)))
    val drawCompletion=Decoupled(new opengpu.system.GeometryWindowBinDrawResult);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion);val tileDone=Decoupled(new LayeredBinTileCompletion)
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new opengpu.system.LayeredGeometryBinImageCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S;case _=>()
  }
  tie(system.io);tie(stage.io)
  stage.io.begin<>io.begin;stage.io.draw<>io.draw;io.drawCompletion<>stage.io.drawCompletion;stage.io.end<>io.end
  io.primitiveDone<>stage.io.primitiveDone(0);io.tileDone<>stage.io.tileDone(0);io.out<>stage.io.out(0)
  io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  stage.io.geometryTrap.ready:=true.B;stage.io.geometryInvalidateDone.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class VertexGeometryLayerImageQualificationSpec extends AnyFlatSpec {
  import TriangleRasterReference._
  import WindowRecordTest.Memory
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "execute vertex, geometry and fragment shaders through layered bins and publish the image" in {
    def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
      (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
    simulate(new VertexGeometryLayerImageHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      val gs=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,0),BigInt("00100213",16),geometry(33,0,4,0),
        BigInt("00200213",16),geometry(33,0,4,3),BigInt("00300213",16),BigInt("3f800337",16),
        geometry(33,0,4,6),BigInt("05d00293",16),geometry(38,0,5),BigInt("00900393",16),geometry(34,0,7),
        geometry(35),geometry(36),BigInt("30500073",16))
      gs.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+4*i,w)}
      val vs=scala.collection.mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
      for(i<-0 until 7)vs++=Seq(addi(5,7,i*32),vmem(5,1,false),addi(6,7,(7+i)*32),vmem(6,1,true))
      for(i<-0 until 3)vs++=Seq(addi(5,7,i*32),vmem(5,1,false),addi(6,7,(11+i)*32),vmem(6,1,true))
      vs++=Seq(addi(13,1,480),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
        addi(5,7,4*32),vmem(5,1,false),(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
        addi(6,7,14*32),vmem(6,1,true),BigInt("30500073",16))
      vs.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+4*i,w)}
      for(i<-0 until 3;c<-0 until 4)memory.putWord(0x4000+i*16+c*4,if(c==3)BigInt("3f800000",16)else BigInt(0))
      memory.putWord(0x180000+480,17)
      val stride=32;val inputs=14
      val fs=scala.collection.mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,(inputs-2+i)*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+4*i,w)}
      val stateBase=BigInt(0x200000);val scratch=BigInt(0x100000)
      for(w<-0 until 128)memory.putWord(stateBase+w*4,0)
      for((v,w)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,5,6,7,0).zipWithIndex)memory.putWord(stateBase+w*4,v)
      val prefix=d.f.prefixBytes(8,1,1,FragmentInputAbi.geometry)
      val values=Map[String,BigInt]("version"->1,"engineCount"->1,"fragment.version"->FragmentInputAbi.geometry,
        "fragment.identity.context"->5,"fragment.identity.pass"->6,"fragment.identity.generation"->7,
        "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->1,"fragment.colorSlots"->1,"fragment.colorMask"->1,
        "fragment.colorTypes[0]"->1,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->scratch,"fragment.scratch.limit"->(scratch+prefix),
        "fragment.scratch.allocationBase"->scratch,"fragment.scratch.allocationLimit"->(scratch+prefix),"tests.version"->1)
      for(field<-TypedFragmentStateLayout.fields(new TypedFragmentState(d.f));word<-0 until field.words){
        val bits=values.getOrElse(field.name,BigInt(0));memory.putWord(stateBase+(field.word+word)*4,(bits>>(32*word))&0xffffffffL)}
      var tiles=0;var shaded=0;var taskReports=0;var allowFault=false
      def tick():Unit={memory.drive();d.io.drawCompletion.ready.poke(true.B);d.io.primitiveDone.ready.poke(true.B)
        d.io.tileDone.ready.poke(true.B);d.io.out.ready.poke(true.B)
        if(d.io.drawCompletion.valid.peek().litToBoolean){if(!allowFault)d.io.drawCompletion.bits.status.expect(0.U);taskReports+=1}
        if(d.io.primitiveDone.valid.peek().litToBoolean){val r=d.io.primitiveDone.bits
          if(!allowFault)assert(r.status.peek().litValue==0,s"primitive status=${r.status.peek().litValue} stage=${r.faultStage.peek().litValue} stateLines=${r.stateReadLines.peek().litValue} tile=${r.source.tile.peek().litValue}")
          if(r.renderStarted.peek().litToBoolean)shaded+=1}
        if(d.io.tileDone.valid.peek().litToBoolean){if(!allowFault)d.io.tileDone.bits.status.expect(0.U);tiles+=1}
        memory.capture();d.clock.step()}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<200000){tick();n+=1}
        assert(p,s"$label stalled at ${memory.cycle} tiles=$tiles shaded=$shaded")}
      val dsc=d.io.begin.bits;val b=dsc.bins.producer;val bins=dsc.bins.bins;val req=dsc.image;val t=req.template
      def ident(x:GraphicsWorkIdentity):Unit={x.context.poke(5.U);x.pass.poke(6.U);x.generation.poke(7.U)}
      b.version.poke(1.U);b.arena.version.poke(1.U);ident(b.arena.identity);b.arena.base.poke(0x10000.U);b.arena.limit.poke(0x20000.U)
      b.screenWidth.poke(4.U);b.screenHeight.poke(4.U);b.tileLog2.poke(3.U)
      bins.version.poke(3.U);bins.tileCount.poke(10.U);bins.directoryBase.poke(0x80000.U);bins.directoryLimit.poke(0x80280.U)
      bins.pool.version.poke(1.U);ident(bins.pool.identity);bins.pool.base.poke(0x40000.U);bins.pool.limit.poke(0x44000.U);bins.pool.pageSizeLog2.poke(7.U)
      req.screenWidth.poke(4.U);req.screenHeight.poke(4.U);req.layerCount.poke(10.U);req.viewStride(0).poke(0x10000.U)
      t.states.version.poke(1.U);ident(t.states.identity);t.states.base.poke(stateBase.U);t.states.limit.poke((stateBase+512).U)
      t.scratchStride.poke(4096.U);t.scratch(0).base.poke(scratch.U);t.scratch(0).limit.poke((scratch+4096).U)
      t.image.version.poke(1.U);t.image.pixels.version.poke(1.U);val c=t.image.pixels.colors;c.version.poke(1.U);ident(c.identity)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U)
      c.colors(0).enabled.poke(true.B);c.colors(0).format.poke(TypedColorFormat.id("RGBA32UI").U)
      c.colors(0).channelMask.poke(15.U);c.colors(0).loadOp.poke(AttachmentLoadOp.clear.U)
      val view=t.image.views(0);view.base.poke(0x400000.U);view.limit.poke(0x400100.U);view.allocationBase.poke(0x400000.U)
      view.allocationLimit.poke(0x4a0000.U);view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(64.U)
      view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      val draw=d.io.draw.bits;val task=draw.geometry;val x=task.geometry.geometry.primitive;val input=x.input
      val v=draw.vertex.vertex;draw.vertex.topology.poke(4.U);v.version.poke(1.U);v.shaderPc.poke(0x1000.U)
      v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U)
      v.input.version.poke(1.U);ident(v.input.identity);v.input.count.poke(3.U);v.input.instances.poke(1.U)
      v.input.bindings(0).base.poke(0x4000.U);v.input.bindings(0).limit.poke(0x4030.U)
      v.input.bindings(0).allocationBase.poke(0x4000.U);v.input.bindings(0).allocationLimit.poke(0x4040.U)
      v.input.bindings(0).stride.poke(16.U);v.input.attributes(0).enabled.poke(true.B)
      v.input.attributes(0).components.poke(4.U);v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      v.scratch.base.poke(0x180000.U);v.scratch.limit.poke(0x1801e4.U)
      v.scratch.allocationBase.poke(0x180000.U);v.scratch.allocationLimit.poke(0x180200.U)
      v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x3000.U);x.varyingSlots.poke(1.U);input.taskId.poke(19.U)
      ident(input.primitive.identity);input.primitive.primitiveId.poke(27.U);input.primitive.kind.poke(2.U)
      input.primitive.vertexCount.poke(3.U);input.primitive.varyingSlots.poke(1.U)
      input.inputComponents.poke(1.U);input.invocations.poke(1.U);input.outputTopology.poke(0.U);input.maxVertices.poke(1.U)
      input.outputComponents.poke(4.U);input.builtinOutputMask.poke(3.U)
      for(i<-0 until 4){x.outputSources(i).valid.poke(true.B);x.outputSources(i).bits.poke(i.U)}
      task.geometry.viewport.version.poke(1.U);task.geometry.viewport.extentX.poke(4.U);task.geometry.viewport.extentY.poke(4.U)
      task.geometry.viewport.farDepth.poke("h3f800000".U);task.raster.version.poke(1.U);task.raster.pointSize.poke("h3f800000".U)
      task.state.poke(0.U);task.token.poke(99.U)
      d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      until("draw report")(taskReports==1);d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
      until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until("completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.sourceStarted.expect(true.B);assert(tiles==10 && shaded==1)
      val pixels=(0 until 4).flatMap(y=>(0 until 4).map(x=>BigInt(0x490000+y*64+x*16)))
      assert(pixels.exists(a=>memory.word(a)==93 && memory.word(a+4)==9),"geometry shader result not published to layer 9")
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until("idle")(d.io.idle.peek().litToBoolean)
      // A draw with mismatched vertex and geometry identity must fail the pass
      // without launching a vertex task, and its report must remain visible.
      allowFault=true;val reportsBefore=taskReports
      d.io.begin.valid.poke(true.B);until("invalid begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.draw.bits.vertex.vertex.input.identity.context.poke(8.U);d.io.draw.valid.poke(true.B)
      until("invalid draw")(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      until("invalid draw report")(taskReports>reportsBefore);until("invalid pass completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
  }

}
