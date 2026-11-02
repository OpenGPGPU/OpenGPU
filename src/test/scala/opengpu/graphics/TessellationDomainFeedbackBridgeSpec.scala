package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.system.TessellationDomainFeedbackBridge
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TessellationDomainFeedbackBridgeHarness extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val feedback=new TransformFeedbackPipelineIO(cfg)
    val switch=Flipped(Decoupled(Bool()));val switchReply=Decoupled(UInt(3.W))
    val in=Flipped(Vec(2,Decoupled(new TransformFeedbackPrimitive(p))))
    val selected=Output(Bool())
  })
  private val bridge=Module(new TessellationDomainFeedbackBridge(cfg,contexts=2))
  io.feedback<>bridge.io.feedback;bridge.io.switch<>io.switch;io.switchReply<>bridge.io.switchReply
  io.selected:=bridge.io.selected;bridge.io.memoryIdle:=true.B
  private val exporters=Seq.fill(2)(Module(new TransformFeedbackExport(cfg,p,contexts=2)))
  for((exporter,i)<-exporters.zipWithIndex){
    val port=bridge.io.owners(i)
    exporter.io.command<>port.command;port.commandReply<>exporter.io.commandReply
    exporter.io.in<>io.in(i);exporter.io.out.ready:=true.B;exporter.io.result.ready:=true.B
    port.recorded.valid:=exporter.io.result.valid;port.recorded.bits:=exporter.io.result.bits
    port.busy:=exporter.io.busy;port.memoryRequest<>exporter.io.memoryRequest;exporter.io.memoryResponse<>port.memoryResponse
    port.memorySatp:=exporter.io.memorySatp;exporter.io.cancel:=port.cancel;exporter.io.abort:=false.B
    bridge.io.ownerIdle(i):= !exporter.io.busy
  }
}

class TessellationDomainFeedbackBridgeSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "route feedback through the selected drained domain and reset both owners" in {
    simulate(new TessellationDomainFeedbackBridge(GpuConfig(lanes=4,warps=2),contexts=2)){d=>
      zero(d.io);d.io.ownerIdle.foreach(_.poke(true.B));d.io.memoryIdle.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<100){d.clock.step();n+=1};assert(condition,s"$label timeout")}
      d.io.selected.expect(false.B);d.io.feedback.memoryRequest.ready.poke(true.B)
      d.io.owners(0).memoryRequest.valid.poke(true.B);d.io.owners(0).memoryRequest.bits.address.poke("h1200".U)
      d.io.owners(0).memorySatp.poke("h80000030".U);d.io.feedback.memoryRequest.valid.expect(true.B)
      d.io.feedback.memoryRequest.bits.address.expect("h1200".U);d.io.feedback.memorySatp.expect("h80000030".U)
      d.io.owners(0).memoryRequest.ready.expect(true.B);d.io.owners(1).memoryRequest.ready.expect(false.B)
      d.io.owners(0).memoryRequest.valid.poke(false.B)
      d.io.switch.bits.poke(true.B);d.io.switch.valid.poke(true.B);until("switch")(d.io.switch.ready.peek().litToBoolean)
      d.clock.step();d.io.switch.valid.poke(false.B);until("switch reply")(d.io.switchReply.valid.peek().litToBoolean)
      d.io.switchReply.bits.expect(0.U);d.io.switchReply.ready.poke(true.B);d.clock.step();d.io.switchReply.ready.poke(false.B)
      d.io.selected.expect(true.B);d.io.owners(1).memoryRequest.valid.poke(true.B)
      d.io.owners(1).memoryRequest.bits.address.poke("h3400".U);d.io.owners(1).memorySatp.poke("h80000031".U)
      d.io.feedback.memoryRequest.valid.expect(true.B);d.io.feedback.memoryRequest.bits.address.expect("h3400".U)
      d.io.feedback.memorySatp.expect("h80000031".U);d.io.owners(1).memoryRequest.ready.expect(true.B)
      d.io.feedback.memoryResponse.valid.poke(true.B);d.io.owners(1).memoryResponse.valid.expect(true.B)
      d.io.owners(0).memoryResponse.valid.expect(false.B);d.io.owners(1).memoryResponse.ready.poke(true.B)
      d.io.feedback.memoryResponse.ready.expect(true.B);d.io.feedback.memoryResponse.valid.poke(false.B)
      d.io.owners(1).memoryRequest.valid.poke(false.B)
      d.io.feedback.command.valid.poke(true.B);until("command")(d.io.feedback.command.ready.peek().litToBoolean)
      d.clock.step();d.io.feedback.command.valid.poke(false.B);d.io.owners(1).command.valid.expect(true.B)
      d.io.owners(0).command.valid.expect(false.B);d.io.owners(1).command.ready.poke(true.B);d.clock.step()
      d.io.owners(1).command.ready.poke(false.B);d.io.owners(1).commandReply.valid.poke(true.B)
      d.io.feedback.commandReply.ready.poke(true.B);d.io.feedback.commandReply.valid.expect(true.B);d.clock.step()
      d.io.owners(1).commandReply.valid.poke(false.B);d.io.feedback.commandReply.ready.poke(false.B)
      d.io.owners(1).memoryRequest.valid.poke(true.B);d.io.feedback.memoryRequest.ready.poke(true.B)
      d.io.owners(1).memoryRequest.ready.expect(true.B);d.clock.step();d.io.owners(1).memoryRequest.valid.poke(false.B)
      d.io.feedback.cancel.poke(true.B);d.clock.step();d.io.feedback.busy.expect(true.B)
      for(_<-0 until 3){d.io.switch.ready.expect(false.B);d.io.feedback.busy.expect(true.B);d.clock.step()}
      d.io.feedback.memoryResponse.bits.transactionId.poke(0.U);d.io.feedback.memoryResponse.valid.poke(true.B)
      d.io.owners(1).memoryResponse.ready.poke(true.B);d.io.feedback.memoryResponse.ready.expect(true.B)
      d.clock.step();d.io.feedback.memoryResponse.valid.poke(false.B);until("reset drain")(!d.io.feedback.busy.peek().litToBoolean)
      d.io.selected.expect(false.B);d.clock.step(3);d.io.feedback.busy.expect(false.B)
      d.io.feedback.cancel.poke(false.B);d.clock.step();d.io.poisoned.expect(false.B)
    }
  }
  it should "move a live capture between real exporters without losing counters or data" in {
    import WindowRecordTest.Memory
    simulate(new TessellationDomainFeedbackBridgeHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.io.feedback.memoryRequest.bits,d.io.feedback.memoryResponse.bits,
        d.io.feedback.memoryRequest.valid,d.io.feedback.memoryRequest.ready,d.io.feedback.memoryResponse.valid,d.io.feedback.memoryResponse.ready)
      def tick():Unit={m.drive();m.capture();d.clock.step()}
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"$label stalled")}
      def command(action:Int):(BigInt,BigInt,BigInt)={
        val c=d.io.feedback.command.bits;zero(c);c.action.poke(action.U);val state=c.state;val q=state.descriptor
        state.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)}
        d.io.feedback.command.valid.poke(true.B);until("command ready")(d.io.feedback.command.ready.peek().litToBoolean)
        tick();d.io.feedback.command.valid.poke(false.B);until("command reply")(d.io.feedback.commandReply.valid.peek().litToBoolean)
        val r=d.io.feedback.commandReply.bits;r.status.expect(0.U)
        val counts=(r.state.offsets(0).peek().litValue,r.state.primitives.peek().litValue,r.state.vertices.peek().litValue)
        d.io.feedback.commandReply.ready.poke(true.B);tick();d.io.feedback.commandReply.ready.poke(false.B);counts
      }
      def primitive(owner:Int,seed:Int):Unit={
        val q=d.io.in(owner).bits;zero(q);q.context.poke(7.U);q.root.poke(0x30.U);q.program.poke(9.U);q.mode.poke(1.U);q.cookie.poke(1.U)
        q.primitive.kind.poke(1.U);q.primitive.vertexCount.poke(2.U);q.primitive.provokingVertex.poke(1.U)
        q.primitive.vertices(0).position(0).poke(seed.U);q.primitive.vertices(1).position(0).poke((seed+1).U)
        d.io.in(owner).valid.poke(true.B);until("primitive ready")(d.io.in(owner).ready.peek().litToBoolean)
        tick();d.io.in(owner).valid.poke(false.B);until("capture")(d.io.feedback.recorded.valid.peek().litToBoolean)
        d.io.feedback.recorded.bits.status.expect(0.U);tick();until("drain")(!d.io.feedback.busy.peek().litToBoolean)
      }
      command(TransformFeedbackAction.begin);primitive(0,11)
      d.io.switch.bits.poke(true.B);d.io.switch.valid.poke(true.B);until("switch ready")(d.io.switch.ready.peek().litToBoolean)
      tick();d.io.switch.valid.poke(false.B);until("switch reply")(d.io.switchReply.valid.peek().litToBoolean)
      d.io.switchReply.bits.expect(0.U);d.io.selected.expect(true.B);d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B)
      primitive(1,21);assert(command(TransformFeedbackAction.end)==((16,2,4)))
      assert((0 until 4).map(i=>m.word(0x1000+i*4))==Seq[BigInt](11,12,21,22))
    }
  }

}
