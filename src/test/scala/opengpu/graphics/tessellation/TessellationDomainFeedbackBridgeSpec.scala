package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.system.{TessellationDomainFeedbackAdmission,TessellationDomainFeedbackBridge}
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
    val selected=Output(Bool());val poisoned=Output(Bool());val injectError=Input(Bool())
  })
  private val bridge=Module(new TessellationDomainFeedbackBridge(cfg,contexts=2))
  io.feedback<>bridge.io.feedback;bridge.io.switch<>io.switch;io.switchReply<>bridge.io.switchReply
  io.selected:=bridge.io.selected;io.poisoned:=bridge.io.poisoned;bridge.io.memoryIdle:=true.B
  private val exporters=Seq.fill(2)(Module(new TransformFeedbackExport(cfg,p,contexts=2)))
  for((exporter,i)<-exporters.zipWithIndex){
    val port=bridge.io.owners(i)
    exporter.io.command<>port.command
    port.commandReply.valid:=exporter.io.commandReply.valid;port.commandReply.bits:=exporter.io.commandReply.bits
    when(io.injectError){port.commandReply.bits.status:=2.U}
    exporter.io.commandReply.ready:=port.commandReply.ready
    exporter.io.in<>io.in(i);exporter.io.out.ready:=true.B;exporter.io.result.ready:=true.B
    port.recorded.valid:=exporter.io.result.valid;port.recorded.bits:=exporter.io.result.bits
    port.busy:=exporter.io.busy;port.memoryRequest<>exporter.io.memoryRequest;exporter.io.memoryResponse<>port.memoryResponse
    port.memorySatp:=exporter.io.memorySatp;exporter.io.cancel:=port.cancel;exporter.io.abort:=false.B
    bridge.io.ownerIdle(i):= !exporter.io.busy
  }
}

private class TessellationDomainFeedbackRoutingHarness extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val feedback=new TransformFeedbackPipelineIO(cfg)
    val submit=Flipped(Vec(2,Decoupled(new TileJobDescriptorRequest(4))))
    val render=Vec(2,Decoupled(new TileJobDescriptorRequest(4)))
    val failed=Decoupled(new Bundle {val commandId=UInt(4.W);val status=UInt(3.W);val triangle=Bool()})
    val in=Flipped(Vec(2,Decoupled(new TransformFeedbackPrimitive(p))))
    val selected=Output(Bool());val poisoned=Output(Bool());val injectError=Input(Bool())
  })
  private val bridge=Module(new TessellationDomainFeedbackBridgeHarness)
  bridge.io.injectError:=io.injectError;io.poisoned:=bridge.io.poisoned
  private val admission=Module(new TessellationDomainFeedbackAdmission(4))
  io.feedback<>bridge.io.feedback;io.in<>bridge.io.in
  admission.io.submit<>io.submit;io.render<>admission.io.render;io.failed<>admission.io.failed
  bridge.io.switch<>admission.io.switch;admission.io.switchReply<>bridge.io.switchReply
  admission.io.selected:=bridge.io.selected;admission.io.poisoned:=bridge.io.poisoned
  admission.io.cancel:=io.feedback.cancel;io.selected:=bridge.io.selected
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

  it should "admit a new domain only after its live feedback capture transfers" in {
    import WindowRecordTest.Memory
    simulate(new TessellationDomainFeedbackRoutingHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.io.feedback.memoryRequest.bits,d.io.feedback.memoryResponse.bits,
        d.io.feedback.memoryRequest.valid,d.io.feedback.memoryRequest.ready,d.io.feedback.memoryResponse.valid,d.io.feedback.memoryResponse.ready)
      def tick():Unit={m.drive();m.capture();d.clock.step()}
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"$label stalled")}
      def command(action:Int):(BigInt,BigInt,BigInt)={val c=d.io.feedback.command.bits;zero(c);c.action.poke(action.U)
        val state=c.state;val q=state.descriptor;state.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)}
        d.io.feedback.command.valid.poke(true.B);until("command ready")(d.io.feedback.command.ready.peek().litToBoolean)
        tick();d.io.feedback.command.valid.poke(false.B);until("reply")(d.io.feedback.commandReply.valid.peek().litToBoolean)
        val r=d.io.feedback.commandReply.bits;r.status.expect(0.U)
        val values=(r.state.offsets(0).peek().litValue,r.state.primitives.peek().litValue,r.state.vertices.peek().litValue)
        d.io.feedback.commandReply.ready.poke(true.B);tick();d.io.feedback.commandReply.ready.poke(false.B);values}
      def job(i:Int):Unit={val s=d.io.submit(i);s.bits.commandId.poke((i+1).U);s.bits.envelopeValid.poke(true.B);s.valid.poke(true.B)
        until("submit")(s.ready.peek().litToBoolean);tick();s.valid.poke(false.B);d.io.render(i).ready.poke(true.B)
        until("domain admission"){if(d.io.render(i).valid.peek().litToBoolean){d.io.selected.expect((i==1).B);true}else false}
        d.io.render(i).bits.commandId.expect((i+1).U);tick();d.io.render(i).ready.poke(false.B)}
      def primitive(i:Int,seed:Int):Unit={val q=d.io.in(i).bits;zero(q);q.context.poke(7.U);q.root.poke(0x30.U)
        q.program.poke(9.U);q.mode.poke(1.U);q.cookie.poke(1.U);q.primitive.kind.poke(1.U);q.primitive.vertexCount.poke(2.U)
        q.primitive.provokingVertex.poke(1.U);q.primitive.vertices(0).position(0).poke(seed.U);q.primitive.vertices(1).position(0).poke((seed+1).U)
        d.io.in(i).valid.poke(true.B);until("primitive")(d.io.in(i).ready.peek().litToBoolean);tick();d.io.in(i).valid.poke(false.B)
        until("captured")(d.io.feedback.recorded.valid.peek().litToBoolean);d.io.feedback.recorded.bits.status.expect(0.U);tick()
        until("drained")(!d.io.feedback.busy.peek().litToBoolean)}
      command(TransformFeedbackAction.begin);job(0);primitive(0,11);job(1);primitive(1,21)
      assert(command(TransformFeedbackAction.end)==((16,2,4)))
      assert((0 until 4).map(i=>m.word(0x1000+i*4))==Seq[BigInt](11,12,21,22))
      d.io.failed.valid.expect(false.B)
    }
  }

  it should "reject both domains after a real transfer failure until feedback reset" in {
    simulate(new TessellationDomainFeedbackRoutingHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(condition: =>Boolean):Unit={var n=0
        while(!condition && n<1000){d.clock.step();n+=1};assert(condition,s"$label stalled")}
      val c=d.io.feedback.command.bits;c.action.poke(TransformFeedbackAction.begin.U)
      c.state.root.poke(0x30.U);val q=c.state.descriptor
      q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U);q.kind.poke(1.U)
      q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(1.U)
      q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)
      d.io.feedback.command.valid.poke(true.B);until("begin")(d.io.feedback.command.ready.peek().litToBoolean)
      d.clock.step();d.io.feedback.command.valid.poke(false.B)
      until("begin reply")(d.io.feedback.commandReply.valid.peek().litToBoolean)
      d.io.feedback.commandReply.bits.status.expect(0.U);d.io.feedback.commandReply.ready.poke(true.B)
      d.clock.step();d.io.feedback.commandReply.ready.poke(false.B)
      def submit(i:Int,id:Int):Unit={val s=d.io.submit(i);s.bits.commandId.poke(id.U);s.valid.poke(true.B)
        until("submit")(s.ready.peek().litToBoolean);d.clock.step();s.valid.poke(false.B)}
      def failure(id:Int):Unit={until("failure")(d.io.failed.valid.peek().litToBoolean)
        d.io.failed.bits.commandId.expect(id.U);d.io.failed.bits.status.expect(7.U)
        d.io.failed.ready.poke(true.B);d.clock.step();d.io.failed.ready.poke(false.B)}
      d.io.injectError.poke(true.B);submit(1,1);failure(1);d.io.poisoned.expect(true.B)
      d.io.injectError.poke(false.B);d.io.render(0).ready.poke(true.B);d.io.render(1).ready.poke(true.B)
      submit(0,2);failure(2);submit(1,3);failure(3)
      d.io.feedback.cancel.poke(true.B);d.clock.step();d.io.feedback.cancel.poke(false.B)
      until("reset clears poison")(!d.io.poisoned.peek().litToBoolean && !d.io.feedback.busy.peek().litToBoolean)
      submit(0,4);until("recovered admission")(d.io.render(0).valid.peek().litToBoolean)
      d.io.render(0).bits.commandId.expect(4.U);d.clock.step()
    }
  }

}
