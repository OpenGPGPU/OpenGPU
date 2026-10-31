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

private class ControlledIsolineWindowBinHarness(config:GpuConfig) extends Module {
  val p=VertexShaderStageConfig()
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new ControlledIsolineWindowBinPass(config,p,4,1,g,engines=2,pageBytes=128,writeCredits=2,tileSlots=2))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new ControlledIsolineWindowBinTask(config,p,4)))
    val taskCompletion=Decoupled(new ControlledIsolineWindowBinTaskCompletion);val end=Flipped(Decoupled(Bool()))
    val out=Vec(2,Decoupled(new WindowPrimitiveReplay(p)));val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new ControlledIsolineWindowBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val sealInput=Flipped(Decoupled(Bool()))
    val access=Flipped(Decoupled(new TessellationInvocationAccess));val finish=Flipped(Decoupled(UInt(5.W)))
    val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,7))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,7)))
    val cancel=Input(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(client.io);io.system<>gpu.io
  client.io.begin<>io.begin;client.io.task<>io.task;io.taskCompletion<>client.io.taskCompletion
  client.io.end<>io.end;io.out<>client.io.out;io.tileDone<>client.io.tileDone;io.completion<>client.io.completion
  io.opened<>client.io.opened;client.io.sealInput<>io.sealInput;client.io.access<>io.access
  client.io.finish<>io.finish;io.control<>client.io.control;client.io.inspect<>io.inspect;io.reply<>client.io.reply
  io.memoryRequest<>client.io.memoryRequest;client.io.memoryResponse<>io.memoryResponse;client.io.cancel:=io.cancel
  val gcu=gpu.io.graphicsKernels.get;gcu.launch<>client.io.launch;gcu.geometryTask.get:=client.io.geometryTask
  client.io.kernelCompletion<>gcu.completion;client.io.event<>gcu.geometryEvent.get
  client.io.owner:=gcu.owned;client.io.taskIds:=gcu.taskIds;gcu.geometryCancel.get:=client.io.cancelCu
}

class ControlledIsolineWindowBinPassSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "bin real shared-CU isolines and preserve pass order without geometry metadata" in {
    val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new ControlledIsolineWindowBinHarness(cfg)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f0001b7",16),BigInt("00300213",16),geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum
      case class Request(tag:BigInt,address:BigInt,write:Boolean,data:BigInt,mask:BigInt,due:Int)
      val gpuReplies=mutable.Queue.empty[(BigInt,BigInt,Int)]
      val binReplies=mutable.Queue.empty[Request];val bytes=mutable.Map.empty[BigInt,Int]
      val seen=mutable.ArrayBuffer.empty[Int]
      var cycle=0;var outputsReady=false;var faultRecord=false;var allowTrap=false
      var held=Array.fill[Option[BigInt]](2)(None)
      def line(address:BigInt):BigInt=(0 until 64).foldLeft(BigInt(0))((a,i)=>a|(BigInt(bytes.getOrElse(address+i,0))<<(8*i)))
      def tick():Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val gr=gpuReplies.headOption.filter(_._3<=cycle)
        sys.memoryResponse.valid.poke(gr.nonEmpty.B);gr.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(r._2.U)})
        if(gr.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)gpuReplies.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U)
          gpuReplies.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,code,cycle+3))}
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
          if(valid && o.ready.peek().litToBoolean){o.bits.record.geometry.valid.expect(false.B)
            seen+=o.bits.record.sequence.peek().litValue.toInt}}
        d.io.tileDone.ready.poke((cycle%3!=0).B)
        if(sys.graphicsKernels.get.trap.valid.peek().litToBoolean){val e=sys.graphicsKernels.get.trap.bits.event
          assert(allowTrap && e.cause.peek().litValue==5,s"shader trap at cycle=$cycle pc=${e.pc.peek().litValue}")}
        d.clock.step();cycle+=1
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<100000){tick();n+=1};assert(condition,s"$label stalled at $cycle")}
      def write(index:Int,value:BigInt,level:Boolean):Unit={val a=d.io.access.bits.access
        a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(level.B);a.write.poke(true.B);a.component.poke(index.U);a.data.poke(value.U)
        d.io.access.valid.poke(true.B);until("write")(d.io.access.ready.peek().litToBoolean);tick();d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("write reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.status.expect(0.U)
        tick();d.io.reply.ready.poke(false.B)}
      def drivePatch(taskId:Int):Unit={
        val t=d.io.task.bits;val view=t.viewport;val src=view.source
        view.viewport.version.poke(1.U);view.viewport.extentX.poke(4.U);view.viewport.extentY.poke(4.U);view.viewport.farDepth.poke("h3f800000".U)
        src.commandId.poke(3.U);src.kernel.kernelPc.poke(0x1000.U);src.renderer.outputComponents.poke(4.U)
        for(i<-Seq(0,1,3)){src.renderer.componentSources(i).valid.poke(true.B);src.renderer.componentSources(i).bits.poke(i.U)}
        src.renderer.componentSources(2).valid.poke(true.B);src.renderer.componentSources(2).bits.poke(0.U)
        src.renderer.patch.context.taskId.poke(taskId.U)
        src.renderer.patch.context.layout.inputVertices.poke(1.U);src.renderer.patch.context.layout.outputVertices.poke(1.U)
        src.renderer.patch.context.layout.patchComponents.poke(1.U)
        t.raster.version.poke(1.U);t.raster.pointSize.poke("h3f800000".U)
        t.state.poke((100+taskId).U);t.token.poke((200+taskId).U)
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
        tick();d.io.opened.ready.poke(false.B);d.io.sealInput.valid.poke(true.B)
        until("seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
        write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true)
        d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean);tick();d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(0.U)
        tick();d.io.control.ready.poke(false.B)
        until("task completion")(d.io.taskCompletion.valid.peek().litToBoolean)
        d.io.taskCompletion.bits.status.expect(0.U);d.io.taskCompletion.bits.admitted.expect(1.U)
        d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      }
      val begin=d.io.begin.bits;begin.producer.version.poke(1.U);begin.producer.arena.version.poke(1.U)
      begin.producer.arena.base.poke(0x10000.U);begin.producer.arena.limit.poke(0x20000.U)
      begin.producer.screenWidth.poke(4.U);begin.producer.screenHeight.poke(4.U);begin.producer.tileLog2.poke(3.U)
      begin.bins.version.poke(1.U);begin.bins.tileCount.poke(1.U)
      begin.bins.pool.version.poke(1.U);begin.bins.pool.base.poke(0x40000.U);begin.bins.pool.limit.poke(0x44000.U);begin.bins.pool.pageSizeLog2.poke(7.U)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      drivePatch(19);drivePatch(20)
      d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
      until("held replay")(d.io.out.map(_.valid.peek().litToBoolean).exists(identity));for(_<-0 until 4)tick()
      outputsReady=true;until("pass completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.tasks.expect(2.U);d.io.completion.bits.nextSequence.expect(2.U)
      assert(seen.toSeq==Seq(0,1),s"replay sequences: $seen");assert(gpuReplies.isEmpty && binReplies.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)

      d.io.begin.valid.poke(true.B);until("second begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.task.bits.viewport.source.renderer.identity.context.poke(1.U)
      d.io.task.valid.poke(true.B);until("mismatched task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      until("mismatch report")(d.io.taskCompletion.valid.peek().litToBoolean)
      d.io.taskCompletion.bits.status.expect(1.U);d.io.taskCompletion.bits.admitted.expect(0.U)
      d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      until("failed pass drain")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.completion.bits.tasks.expect(1.U)
      assert(seen.size==2 && gpuReplies.isEmpty && binReplies.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)

      d.io.begin.valid.poke(true.B);until("fault begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.task.bits.viewport.source.renderer.identity.context.poke(0.U)
      faultRecord=true;allowTrap=true
      d.io.task.valid.poke(true.B);until("fault task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
      d.io.opened.ready.poke(true.B);until("fault open")(d.io.opened.valid.peek().litToBoolean);tick();d.io.opened.ready.poke(false.B)
      d.io.sealInput.valid.poke(true.B);until("fault seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true)
      d.io.finish.valid.poke(true.B);until("fault finish")(d.io.finish.ready.peek().litToBoolean);tick();d.io.finish.valid.poke(false.B)
      d.io.control.ready.poke(true.B);until("fault control")(d.io.control.valid.peek().litToBoolean);tick();d.io.control.ready.poke(false.B)
      until("fault task report")(d.io.taskCompletion.valid.peek().litToBoolean)
      assert(Set(BigInt(0),BigInt(3),BigInt(4)).contains(d.io.taskCompletion.bits.status.peek().litValue))
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
