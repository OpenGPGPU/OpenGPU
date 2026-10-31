package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class ControlledIsolineTypedTileGpuHarness extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2);val p=FragmentShaderConfig(1,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height),maxSampleCount=4)
  val storage=TypedTileColorConfig(bytes=1024)
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,graphicsHostTransactions=4,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private val client=Module(new ControlledIsolineTypedTilePipeline(cfg,p,SharedCuShaderConfig(1,taskIdWidth=4),g,
    storage=storage,interpolationContexts=2))
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new ControlledIsolineTypedTileTask(cfg,p,4,None)))
    val opened=Decoupled(UInt(2.W));val sealInput=Flipped(Decoupled(Bool()))
    val access=Flipped(Decoupled(new TessellationInvocationAccess));val finish=Flipped(Decoupled(UInt(5.W)))
    val control=Decoupled(new TessellationBarrierResult);val replyPatch=Decoupled(new TessellationContextReply)
    val out=Decoupled(new TypedTilePixelQuadCompletion);val end=Flipped(Decoupled(Bool()))
    val completion=Decoupled(new ControlledIsolineTypedTileCompletion);val readyForStore=Output(Bool())
    val tileMemory=Flipped(Decoupled(new TypedTileColorMemoryRequest(storage)));val tileReply=Decoupled(new TypedTileColorMemoryReply)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,GpuSystem.totalMemoryTransactions(1,4,4)))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,GpuSystem.totalMemoryTransactions(1,4,4))))
    val cancel=Input(Bool());val trap=Output(Bool());val refill=Output(Bool());val busy=Output(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie)
    case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(system.io);tie(client.io)
  client.io.task<>io.task;io.out<>client.io.out;client.io.end<>io.end;io.completion<>client.io.completion
  io.opened<>client.io.opened;client.io.sealInput<>io.sealInput;client.io.access<>io.access
  client.io.finish<>io.finish;io.control<>client.io.control;io.replyPatch<>client.io.replyPatch

  io.readyForStore:=client.io.readyForStore;client.io.memory<>io.tileMemory;io.tileReply<>client.io.reply
  client.io.cancel:=io.cancel;client.io.shared<>system.io.graphicsKernels.get
  system.io.graphicsHostRequest<>client.io.memoryRequest;client.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !client.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  client.io.geometryTrap.ready:=true.B;io.trap:=client.io.geometryTrap.valid
  client.io.geometryInvalidateDone.ready:=true.B;client.io.refill.ready:=true.B;io.refill:=client.io.refill.valid
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse;io.busy:=client.io.busy
}

class ControlledIsolineTypedTileQualificationSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,imm:Int):BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def vmem(base:Int,reg:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  it should "store a controlled shared-CU shaded isoline in a resident typed tile" in {
    simulate(new ControlledIsolineTypedTileGpuHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val mem=mutable.Map.empty[BigInt,BigInt]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)mem(a+i)=(v>>(8*i))&255
      val gs=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
        geometry(33,0,4,5),geometry(35),geometry(36),BigInt("30500073",16))
      gs.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
      val stride=32;val inputs=6
      val fs=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
        add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
      for(i<-0 until 2){fs+=addi(5,7,i*stride);fs+=vmem(5,1,false);fs+=addi(6,7,(inputs+i)*stride);fs+=vmem(6,1,true)}
      fs+=BigInt("30500073",16);fs.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
      val pending=mutable.ArrayBuffer.empty[(BigInt,BigInt,Int)];var cycle=0;var pixelQuads=0
      def tick():Unit={
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);val ready=pending.indexWhere(_._3<=cycle)
        d.io.memoryResponse.valid.poke((ready>=0).B)
        if(ready>=0){val r=pending(ready);d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(r._2.U)}
        if(ready>=0 && d.io.memoryResponse.ready.peek().litToBoolean)pending.remove(ready)
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          if(write){val mask=r.byteMask.peek().litValue;val bits=r.writeData.peek().litValue
            for(i<-0 until 64 if mask.testBit(i))mem(a+i)=(bits>>(8*i))&255}
          val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(mem.getOrElse(a+i,BigInt(0))<<(8*i)))
          pending+=((r.transactionId.peek().litValue,if(write)BigInt(0)else data,cycle+3))
        }
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){d.io.out.bits.status.expect(0.U);pixelQuads+=1}
        d.io.trap.expect(false.B);d.io.refill.expect(false.B);d.clock.step();cycle+=1
      }
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100000){
        assert(!d.io.completion.valid.peek().litToBoolean,s"$label ended early status=${d.io.completion.bits.status.peek().litValue}")
        tick();n+=1};assert(p,s"$label stalled at $cycle")}
      val t=d.io.task.bits;t.version.poke(1.U);t.tests.version.poke(1.U);t.pixels.version.poke(1.U)
      val tile=t.pixels.colors;tile.version.poke(1.U);tile.tileLog2.poke(3.U);tile.tileWidth.poke(4.U);tile.tileHeight.poke(4.U)
      val color=tile.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U)
      color.channelMask.poke(15.U);color.loadOp.poke(1.U)
      val draw=t.draw;val raster=draw.raster;val view=raster.source.viewport
      view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.farDepth.poke("h3f800000".U)
      raster.raster.version.poke(1.U);raster.raster.screenW.poke(math.min(4,GraphicsTestResolution.width).U);raster.raster.screenH.poke(math.min(4,GraphicsTestResolution.height).U)
      val x=raster.source.source;x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x1000.U)
      val r=x.renderer;r.patch.context.taskId.poke(19.U);r.patch.context.layout.inputVertices.poke(1.U);r.patch.context.layout.outputVertices.poke(1.U);r.patch.spacing.poke(TessellationSpacing.equal.U)
      r.firstSequence.poke(10.U);r.firstPrimitiveId.poke(12.U);r.outputComponents.poke(4.U)
      for(i<-Seq(0,1,3)){r.componentSources(i).valid.poke(true.B);r.componentSources(i).bits.poke(i.U)}
      val f=draw.fragment;f.version.poke(FragmentInputAbi.center.U);f.shaderPc.poke(0x2000.U)
      f.colorSlots.poke(1.U);f.colorMask.poke(1.U);val prefix=d.p.prefixBytes(8,0,1,FragmentInputAbi.center)
      f.scratch.base.poke(0x100000.U);f.scratch.limit.poke((0x100000+prefix).U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke((0x100000+prefix).U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      def controlPatch():Unit={d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U);tick();d.io.opened.ready.poke(false.B);d.io.sealInput.valid.poke(true.B)
        until("seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
        for(i<-0 until 2){val a=d.io.access.bits.access;a.output.poke(true.B);a.perPatch.poke(true.B)
          a.tessLevel.poke(true.B);a.write.poke(true.B);a.component.poke(i.U);a.data.poke("h3f800000".U)
          d.io.access.valid.poke(true.B);until("write level")(d.io.access.ready.peek().litToBoolean)
          tick();d.io.access.valid.poke(false.B);d.io.replyPatch.ready.poke(true.B)
          until("level reply")(d.io.replyPatch.valid.peek().litToBoolean);d.io.replyPatch.bits.result.status.expect(0.U)
          tick();d.io.replyPatch.ready.poke(false.B)}
        d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean);tick();d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean)
        d.io.control.bits.status.expect(0.U);tick();d.io.control.ready.poke(false.B)}
      d.io.out.ready.poke(true.B);d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean)
      tick();d.io.task.valid.poke(false.B);controlPatch();until("tile ready")(d.io.readyForStore.peek().litToBoolean)
      assert(pixelQuads>0 && pending.isEmpty)
      var nonzero=0
      for(y<-0 until 4;x<-Seq(0,2)){
        d.io.tileMemory.bits.address.poke(((y*8+x)/2).U);d.io.tileMemory.bits.write.poke(false.B)
        d.io.tileMemory.valid.poke(true.B);until("tile request")(d.io.tileMemory.ready.peek().litToBoolean)
        tick();d.io.tileMemory.valid.poke(false.B);until("tile reply")(d.io.tileReply.valid.peek().litToBoolean)
        d.io.tileReply.bits.available.expect(true.B);d.io.tileReply.bits.fault.expect(false.B)
        val word=d.io.tileReply.bits.data.peek().litValue
        for(shift<-Seq(0,32)){val px=(word>>shift)&0xffffffffL;if(px!=0){assert(px==0x0000ffffL);nonzero+=1}}
        d.io.tileReply.ready.poke(true.B);tick();d.io.tileReply.ready.poke(false.B)
      }
      assert(nonzero>0)
      // A simultaneous maintenance request must drain before the held end.
      d.io.tileMemory.bits.address.poke(0.U);d.io.tileMemory.valid.poke(true.B)
      d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B)
      until("concurrent tile request")(d.io.tileMemory.ready.peek().litToBoolean);d.io.end.ready.expect(false.B)
      tick();d.io.tileMemory.valid.poke(false.B);until("concurrent tile reply")(d.io.tileReply.valid.peek().litToBoolean)
      d.io.end.ready.expect(false.B);d.io.tileReply.ready.poke(true.B);tick();d.io.tileReply.ready.poke(false.B)
      until("tile end")(d.io.end.ready.peek().litToBoolean)
      tick();d.io.end.valid.poke(false.B);until("completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);assert(d.io.completion.bits.admitted.peek().litValue>0)
      d.io.completion.bits.retired.expect(d.io.completion.bits.admitted.peek());assert(pending.isEmpty)
    }
  }
}
