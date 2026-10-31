package opengpu.graphics

import opengpu.system._

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

private class GeometryBinGpuHarness(config:GpuConfig,layered:Boolean) extends Module {
  val p=VertexShaderStageConfig()
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  private val paged=if(layered)16 else 0
  private val transactions=VertexBinPipeline.binTransactions(2,2,paged)
  val client=Module(new GeometryWindowBinPass(config,p,4,1,g,engines=2,pageBytes=128,writeCredits=2,tileSlots=2,pagedCapacity=paged,layered=layered,rendererCredits=layered))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val layerCount=if(layered)Some(Input(UInt(16.W)))else None
    val rendererPermit=if(layered)Some(Input(Vec(2,Bool())))else None
    val task=Flipped(Decoupled(new GeometryWindowBinTask(config,p,4)))
    val taskCompletion=Decoupled(new GeometryWindowBinTaskCompletion);val end=Flipped(Decoupled(Bool()))
    val out=Vec(2,Decoupled(new WindowPrimitiveReplay(p)));val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new GeometryWindowBinCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val texture=Vec(1,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,4,1))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  io.system<>gpu.io;client.io.shared<>gpu.io.graphicsKernels.get
  client.io.begin<>io.begin;client.io.task<>io.task;io.taskCompletion<>client.io.taskCompletion
  if(layered){client.io.layerCount.get:=io.layerCount.get;client.io.rendererPermit.get:=io.rendererPermit.get}
  client.io.end<>io.end;io.out<>client.io.out;io.tileDone<>client.io.tileDone;io.completion<>client.io.completion
  io.memoryRequest<>client.io.memoryRequest;client.io.memoryResponse<>io.memoryResponse;client.io.cancel:=io.cancel
  io.texture<>client.io.texture;io.trap<>client.io.trap;client.io.invalidate<>io.invalidate;io.invalidateDone<>client.io.invalidateDone
}

class GeometryWindowBinPassSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  for(layered<-Seq(false,true)) it should s"bin real shared-CU geometry and replay metadata with layered=$layered" in {
    val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometryBinGpuHarness(cfg,layered)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B);d.io.invalidateDone.ready.poke(true.B)
      val program=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,0),BigInt("00100213",16),geometry(33,0,4,0),
        BigInt("00200213",16),geometry(33,0,4,3),BigInt("00300213",16),BigInt("3f800337",16),
        geometry(33,0,4,6),BigInt("05d00293",16),geometry(38,0,5),BigInt("00900393",16),geometry(34,0,7),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum
      case class Request(tag:BigInt,address:BigInt,write:Boolean,data:BigInt,mask:BigInt,due:Int)
      val gpuReplies=mutable.Queue.empty[(BigInt,BigInt,Int)]
      val binReplies=mutable.Queue.empty[Request];val bytes=mutable.Map.empty[BigInt,Int]
      val seen=mutable.ArrayBuffer.empty[(Int,Int,Int,Int)]
      var cycle=0;var outputsReady=false;var faultRecord=false;var allowTrap=false
      var held=Array.fill[Option[BigInt]](2)(None)
      def line(address:BigInt):BigInt=(0 until 64).foldLeft(BigInt(0))((a,i)=>a|(BigInt(bytes.getOrElse(address+i,0))<<(8*i)))
      def tick():Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val gr=gpuReplies.headOption.filter(_._3<=cycle)
        sys.memoryResponse.valid.poke(gr.nonEmpty.B);gr.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(r._2.U)})
        if(gr.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)gpuReplies.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          val a=sys.memoryRequest.bits.address.peek().litValue;assert(a==0x1000 || a==0x1040)
          gpuReplies.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,(code>>((a-0x1000).toInt*8))&((BigInt(1)<<512)-1),cycle+3))}
        d.io.memoryRequest.ready.poke((cycle%4!=0).B);val br=binReplies.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(br.nonEmpty.B)
        d.io.memoryResponse.bits.fault.poke(br.exists(r=>faultRecord && r.write && r.address==0x10000).B)
        br.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.tag.U)
          d.io.memoryResponse.bits.readData.poke(line(r.address).U)})
        if(br.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=binReplies.dequeue()
          if(r.write)for(i<-0 until 64 if r.mask.testBit(i))bytes(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){val q=d.io.memoryRequest.bits
          binReplies.enqueue(Request(q.transactionId.peek().litValue,q.address.peek().litValue,q.isWrite.peek().litToBoolean,
            q.writeData.peek().litValue,q.byteMask.peek().litValue,cycle+3))}
        for(e<-0 until 2){val o=d.io.out(e);o.ready.poke((outputsReady && cycle%3!=e).B)
          val valid=o.valid.peek().litToBoolean;held(e).foreach(x=>assert(valid && o.bits.peek().litValue==x))
          held(e)=if(valid && !o.ready.peek().litToBoolean)Some(o.bits.peek().litValue)else None
          if(valid && o.ready.peek().litToBoolean){o.bits.record.geometry.valid.expect(true.B)
            seen+=((o.bits.record.sequence.peek().litValue.toInt,o.bits.record.geometry.bits.inputPrimitiveId.peek().litValue.toInt,
              o.bits.record.geometry.bits.layer.peek().litValue.toInt,o.bits.source.tile.peek().litValue.toInt))}}
        d.io.tileDone.ready.poke((cycle%3!=0).B)
        if(d.io.trap.valid.peek().litToBoolean){val e=d.io.trap.bits.event
          assert(allowTrap && e.cause.peek().litValue==5,s"shader trap at cycle=$cycle pc=${e.pc.peek().litValue} cause=${e.cause.peek().litValue} tval=${e.tval.peek().litValue}")}
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<100000){tick();n+=1};assert(condition,s"$label stalled at $cycle")}
      val begin=d.io.begin.bits;begin.producer.version.poke(1.U);begin.producer.arena.version.poke(1.U)
      begin.producer.arena.base.poke(0x10000.U);begin.producer.arena.limit.poke(0x20000.U)
      begin.producer.screenWidth.poke(4.U);begin.producer.screenHeight.poke(4.U);begin.producer.tileLog2.poke(3.U)
      begin.bins.version.poke((if(layered)2 else 1).U);begin.bins.tileCount.poke((if(layered)10 else 1).U)
      if(layered){d.io.layerCount.get.poke(10.U);begin.bins.directoryBase.poke(0x80000.U);begin.bins.directoryLimit.poke((0x80000+10*64).U)}
      begin.bins.pool.version.poke(1.U);begin.bins.pool.base.poke(0x40000.U);begin.bins.pool.limit.poke(0x44000.U);begin.bins.pool.pageSizeLog2.poke(7.U)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      for(id<-Seq(27,28)){val t=d.io.task.bits;val x=t.geometry.geometry.primitive;val q=x.input
        x.commandId.poke(3.U);x.kernel.kernelPc.poke(0x1000.U);x.firstSequence.poke((20+id-27).U)
        q.taskId.poke((19+id-27).U);q.primitive.primitiveId.poke(id.U);q.primitive.kind.poke(2.U)
        q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(1.U);q.primitive.vertices(2).position(0).poke("h3f000000".U)
        q.inputComponents.poke(1.U);q.invocations.poke(1.U);q.outputTopology.poke(0.U);q.maxVertices.poke(1.U)
        q.outputComponents.poke(4.U);q.builtinOutputMask.poke(3.U)
        for(c<-0 until 4){x.outputSources(c).valid.poke(true.B);x.outputSources(c).bits.poke(c.U)}
        t.geometry.viewport.version.poke(1.U);t.geometry.viewport.extentX.poke(4.U);t.geometry.viewport.extentY.poke(4.U)
        t.geometry.viewport.farDepth.poke("h3f800000".U);t.raster.version.poke(1.U);t.raster.pointSize.poke("h3f800000".U)
        t.state.poke((100+id).U);t.token.poke((200+id).U)
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        until("task completion")(d.io.taskCompletion.valid.peek().litToBoolean)
        d.io.taskCompletion.bits.status.expect(0.U);d.io.taskCompletion.bits.admitted.expect(1.U)
        d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      }
      d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      if(layered){
        for(_<-0 until 200){
          assert(!d.io.out.map(_.valid.peek().litToBoolean).exists(identity))
          assert(!d.io.tileDone.valid.peek().litToBoolean && !d.io.completion.valid.peek().litToBoolean)
          tick()
        }
        for(e<-0 until 2)d.io.rendererPermit.get(e).poke(true.B)
      }
      until("held replay")(d.io.out.map(_.valid.peek().litToBoolean).exists(identity));for(_<-0 until 4)tick()
      outputsReady=true;until("pass completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.tasks.expect(2.U);d.io.completion.bits.nextSequence.expect(2.U)
      val tile=if(layered)9 else 0
      assert(seen.toSeq==Seq((0,27,9,tile),(1,28,9,tile)));assert(gpuReplies.isEmpty && binReplies.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      d.io.begin.valid.poke(true.B);until("second begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.task.bits.geometry.geometry.primitive.input.primitive.identity.context.poke(1.U)
      d.io.task.valid.poke(true.B);until("mismatched task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      until("mismatch report")(d.io.taskCompletion.valid.peek().litToBoolean)
      d.io.taskCompletion.bits.status.expect(1.U);d.io.taskCompletion.bits.admitted.expect(0.U)
      d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      until("failed pass drain")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.completion.bits.tasks.expect(1.U)
      assert(seen.size==2 && gpuReplies.isEmpty && binReplies.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      d.io.begin.valid.poke(true.B);until("fault begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.task.bits.geometry.geometry.primitive.input.primitive.identity.context.poke(0.U)
      faultRecord=true;allowTrap=true
      d.io.task.valid.poke(true.B);until("fault task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      until("fault task report")(d.io.taskCompletion.valid.peek().litToBoolean)
      assert(Set(BigInt(0),BigInt(4)).contains(d.io.taskCompletion.bits.status.peek().litValue))
      d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      until("fault pass drain")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(3.U);assert(gpuReplies.isEmpty && binReplies.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      faultRecord=false;allowTrap=false
      d.io.begin.valid.poke(true.B);until("recovery begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.end.valid.poke(true.B);until("recovery end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until("recovery complete")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
    }
  }
}
