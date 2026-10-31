package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class ControlledIsolineViewportCuHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new ControlledIsolineSharedCuViewport(config,VertexShaderStageConfig(),4,1))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io);val task=Flipped(Decoupled(new ControlledIsolineSharedCuViewportTask(config,VertexShaderStageConfig(),4)))
    val opened=Decoupled(UInt(2.W));val sealInput=Flipped(Decoupled(Bool()));val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val finish=Flipped(Decoupled(UInt(5.W)));val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new WindowPrimitive(VertexShaderStageConfig()));val completion=Decoupled(new ControlledIsolineSharedCuViewportCompletion)
    val cancel=Input(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(client.io);io.system<>gpu.io;client.io.task<>io.task;io.opened<>client.io.opened
  client.io.sealInput<>io.sealInput;client.io.access<>io.access;client.io.finish<>io.finish;io.control<>client.io.control
  client.io.inspect<>io.inspect;io.reply<>client.io.reply;io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  val g=gpu.io.graphicsKernels.get;g.launch<>client.io.launch;g.geometryTask.get:=client.io.geometryTask
  client.io.kernelCompletion<>g.completion;client.io.event<>g.geometryEvent.get
  client.io.owner:=g.owned;client.io.taskIds:=g.taskIds;g.geometryCancel.get:=client.io.cancelCu
}

class ControlledIsolineSharedCuViewportQualificationSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "transform clipped control-patch isolines evaluated on the real shared CU" in {
    val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new ControlledIsolineViewportCuHarness(cfg)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f0001b7",16),BigInt("00300213",16),geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var cancelling=false
      val traps=mutable.ArrayBuffer.empty[(BigInt,BigInt)]
      def tick():Unit={sys.memoryRequest.ready.poke((cycle%3!=0).B);val r=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(r.nonEmpty.B);r.foreach(x=>{sys.memoryResponse.bits.transactionId.poke(x._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(r.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        if(sys.graphicsKernels.get.trap.valid.peek().litToBoolean){
          assert(cancelling,s"unexpected shader trap at cycle $cycle")
          traps += ((sys.graphicsKernels.get.trap.bits.event.cause.peek().litValue,
            sys.graphicsKernels.get.trap.bits.event.pc.peek().litValue))
        };d.clock.step();cycle+=1}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"$label stalled at $cycle")}
      val view=d.io.task.bits.viewport;view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.farDepth.poke("h3f800000".U)
      val t=d.io.task.bits.source;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U);t.renderer.outputComponents.poke(4.U); for(i<-Seq(0,1,3)){t.renderer.componentSources(i).valid.poke(true.B);t.renderer.componentSources(i).bits.poke(i.U)}
      t.renderer.componentSources(2).valid.poke(true.B);t.renderer.componentSources(2).bits.poke(0.U)
      t.renderer.patch.context.taskId.poke(19.U);t.renderer.patch.context.layout.inputVertices.poke(1.U);t.renderer.patch.context.layout.outputVertices.poke(1.U)
      t.renderer.patch.context.layout.patchComponents.poke(1.U);d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean)
      tick();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean)
      d.io.opened.bits.expect(0.U);tick();d.io.opened.ready.poke(false.B);d.io.sealInput.valid.poke(true.B)
      until("seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
      def write(index:Int,value:BigInt,level:Boolean):Unit={val a=d.io.access.bits.access
        a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(level.B);a.write.poke(true.B);a.component.poke(index.U);a.data.poke(value.U)
        d.io.access.valid.poke(true.B);until("write")(d.io.access.ready.peek().litToBoolean);tick();d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("write reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.status.expect(0.U)
        tick();d.io.reply.ready.poke(false.B)}
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true);write(0,0x1234,false)
      d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean);tick();d.io.finish.valid.poke(false.B)
      d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(0.U)
      tick();d.io.control.ready.poke(false.B)
      until("evaluation ownership")(sys.graphicsKernels.get.owned.peek().litValue != 0)
      val a=d.io.inspect.bits;a.output.poke(true.B);a.perPatch.poke(true.B);a.component.poke(0.U)
      d.io.inspect.valid.poke(true.B);until("inspect")(d.io.inspect.ready.peek().litToBoolean);tick();d.io.inspect.valid.poke(false.B)
      d.io.reply.ready.poke(true.B);until("inspect reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.data.expect(0x1234.U)
      tick();d.io.reply.ready.poke(false.B)
      until("evaluated line")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.vertices(0).screenX.expect(512.S);d.io.out.bits.vertices(1).screenX.expect(1024.S)
      d.io.out.bits.clipped.clipped.expect(true.B);d.io.out.bits.clipped.primitive.vertexCount.expect(2.U)
      d.io.out.bits.clipped.primitive.vertices(0).position(0).expect(0.U);d.io.out.bits.clipped.primitive.vertices(1).position(0).expect("h3f000000".U)
      d.io.completion.valid.expect(false.B)
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);until("completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.source.source.published.expect(1.U);assert(pending.isEmpty)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)

      // Cancel after the actual shared CU has taken ownership, then drain the
      // accepted shader work before allowing the patch to complete.
      d.io.task.valid.poke(true.B);until("second task")(d.io.task.ready.peek().litToBoolean)
      tick();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B)
      until("second open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
      tick();d.io.opened.ready.poke(false.B);d.io.sealInput.valid.poke(true.B)
      until("second seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true)
      d.io.finish.valid.poke(true.B);until("second finish")(d.io.finish.ready.peek().litToBoolean)
      tick();d.io.finish.valid.poke(false.B);d.io.control.ready.poke(true.B)
      until("second control")(d.io.control.valid.peek().litToBoolean);tick();d.io.control.ready.poke(false.B)
      d.io.out.ready.poke(true.B)
      until("CU ownership")(sys.graphicsKernels.get.owned.peek().litValue != 0)
      cancelling=true;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      until("cancelled completion")(d.io.completion.valid.peek().litToBoolean)
      // The geometry worker reports a cancelled scalar operation through the
      // existing failed-operation trap path; the enclosing owner drains it.
      d.io.completion.bits.status.expect(4.U);assert(pending.isEmpty)
      assert(traps.toSeq == Seq((BigInt(5),BigInt(0x1008))),s"cancellation traps: $traps")
      sys.graphicsKernels.get.owned.expect(0.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);cancelling=false

      // A subsequent patch must acquire the same CU and produce valid output.
      d.io.task.valid.poke(true.B);until("recovery task")(d.io.task.ready.peek().litToBoolean)
      tick();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B)
      until("recovery open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
      tick();d.io.opened.ready.poke(false.B);d.io.sealInput.valid.poke(true.B)
      until("recovery seal")(d.io.sealInput.ready.peek().litToBoolean);tick();d.io.sealInput.valid.poke(false.B)
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true)
      d.io.finish.valid.poke(true.B);until("recovery finish")(d.io.finish.ready.peek().litToBoolean)
      tick();d.io.finish.valid.poke(false.B);d.io.control.ready.poke(true.B)
      until("recovery control")(d.io.control.valid.peek().litToBoolean);tick();d.io.control.ready.poke(false.B)
      d.io.out.ready.poke(false.B);until("recovery line")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.clipped.clipped.expect(true.B);d.io.out.bits.clipped.primitive.vertices(0).position(0).expect(0.U)
      d.io.out.bits.clipped.primitive.vertices(1).position(0).expect("h3f000000".U)
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
      until("recovery completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.source.source.published.expect(1.U);assert(pending.isEmpty)
    }
  }
}
