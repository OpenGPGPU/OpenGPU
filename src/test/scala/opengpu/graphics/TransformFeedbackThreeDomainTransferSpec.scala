package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TransformFeedbackThreeDomainTransferHarness(contexts:Int=4) extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new TransformFeedbackCommand));val commandReply=Decoupled(new TransformFeedbackCommandReply)
    val switch=Flipped(Decoupled(UInt(2.W)));val switchReply=Decoupled(UInt(3.W))
    val in=Flipped(Vec(3,Decoupled(new TransformFeedbackPrimitive(p))))
    val result=Vec(3,Valid(new TransformFeedbackPrimitiveResult))
    val memRequest=Vec(3,Decoupled(new ComputeMemoryRequest(cfg,64,2)))
    val memResponse=Flipped(Vec(3,Decoupled(new ComputeMemoryResponse(64,2))))
    val block=Input(Bool());val injectError=Input(Bool());val injectScope=Input(Bool());val cancel=Input(Bool())
    val selected=Output(UInt(2.W));val quiesce=Output(Bool());val poisoned=Output(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  val owner=Module(new TransformFeedbackThreeDomainTransfer(contexts));val exporters=Seq.fill(3)(Module(new TransformFeedbackExport(cfg,p,contexts)))
  owner.io.command<>io.command;io.commandReply<>owner.io.commandReply;owner.io.switch<>io.switch;io.switchReply<>owner.io.switchReply
  owner.io.cancel:=io.cancel;io.selected:=owner.io.selected;io.quiesce:=owner.io.quiesce;io.poisoned:=owner.io.poisoned;io.busy:=owner.io.busy
  io.drained:=owner.io.drained
  for((f,i)<-exporters.zipWithIndex){
    f.io.command.valid:=owner.io.ownerCommand(i).valid && !io.block;f.io.command.bits:=owner.io.ownerCommand(i).bits
    owner.io.ownerCommand(i).ready:=f.io.command.ready && !io.block
    owner.io.ownerReply(i).valid:=f.io.commandReply.valid;owner.io.ownerReply(i).bits:=f.io.commandReply.bits
    when(io.injectError){owner.io.ownerReply(i).bits.status:=2.U}
    when(io.injectScope){owner.io.ownerReply(i).bits.state.root:=99.U}
    f.io.commandReply.ready:=owner.io.ownerReply(i).ready
    owner.io.ownerIdle(i):= !f.io.busy;f.io.cancel:=owner.io.ownerCancel(i);f.io.abort:=false.B
    f.io.in<>io.in(i);f.io.out.ready:=true.B;f.io.result.ready:=true.B
    io.result(i).valid:=f.io.result.valid;io.result(i).bits:=f.io.result.bits
    io.memRequest(i)<>f.io.memoryRequest;f.io.memoryResponse<>io.memResponse(i)
  }
}

class TransformFeedbackThreeDomainTransferSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  it should "reject an invalid owner and recover from a failed quad transfer" in {
    simulate(new TransformFeedbackThreeDomainTransferHarness(1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 3)d.io.memRequest(i).ready.poke(true.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<3000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      d.io.switch.bits.poke(3.U);d.io.switch.valid.poke(true.B);d.io.switch.ready.expect(false.B)
      d.clock.step();d.io.switch.valid.poke(false.B);d.io.selected.expect(0.U)
      val s=d.io.command.bits.state;val q=s.descriptor;s.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
      q.kind.poke(0.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)
      d.io.command.bits.action.poke(TransformFeedbackAction.begin.U);d.io.command.valid.poke(true.B)
      until("begin")(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
      until("begin reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.ready.poke(true.B);d.clock.step();d.io.commandReply.ready.poke(false.B)
      d.io.switch.bits.poke(2.U);d.io.switch.valid.poke(true.B);until("quad switch")(d.io.switch.ready.peek().litToBoolean)
      d.clock.step();d.io.switch.valid.poke(false.B);d.io.injectError.poke(true.B)
      until("failed switch")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(2.U);d.io.poisoned.expect(true.B)
      for(_<-0 until 3){d.io.drained.expect(false.B);d.clock.step()}
      d.io.switchReply.ready.poke(true.B);d.clock.step();d.io.switchReply.ready.poke(false.B);d.io.injectError.poke(false.B)
      d.io.busy.expect(true.B);d.io.drained.expect(true.B)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);until("reset drain")(!d.io.busy.peek().litToBoolean)
      d.io.poisoned.expect(false.B);d.io.selected.expect(0.U)
      d.io.switch.bits.poke(2.U);d.io.switch.valid.poke(true.B);until("quad reuse")(d.io.switch.ready.peek().litToBoolean)
      d.clock.step();d.io.switch.valid.poke(false.B);until("reuse reply")(d.io.switchReply.valid.peek().litToBoolean)
      d.io.switchReply.bits.expect(0.U);d.io.selected.expect(2.U);d.io.switchReply.ready.poke(true.B);d.clock.step()
    }
  }
  it should "move a singleton object table without losing its identity" in {
    simulate(new TransformFeedbackThreeDomainTransferHarness(1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 3)d.io.memRequest(i).ready.poke(true.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<3000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val s=d.io.command.bits.state;val q=s.descriptor;s.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
      q.kind.poke(0.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)
      def command(action:Int):Unit={d.io.command.bits.action.poke(action.U);d.io.command.valid.poke(true.B)
        until("command")(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
        until("reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.bits.status.expect(0.U)
        d.io.commandReply.bits.state.descriptor.cookie.expect(1.U);d.io.commandReply.ready.poke(true.B);d.clock.step();d.io.commandReply.ready.poke(false.B)}
      command(TransformFeedbackAction.begin);d.io.switch.bits.poke(1.U);d.io.switch.valid.poke(true.B)
      until("switch")(d.io.switch.ready.peek().litToBoolean);d.clock.step();d.io.switch.valid.poke(false.B)
      until("switch reply")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(0.U)
      d.io.switchReply.ready.poke(true.B);d.clock.step();d.io.switchReply.ready.poke(false.B);command(TransformFeedbackAction.end)
    }
  }
  it should "move all object checkpoints and continue exact capture on either owner" in {
    simulate(new TransformFeedbackThreeDomainTransferHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val mem=(0 until 3).map(i=>new Memory(d.io.memRequest(i).bits,d.io.memResponse(i).bits,d.io.memRequest(i).valid,
        d.io.memRequest(i).ready,d.io.memResponse(i).valid,d.io.memResponse(i).ready))
      def tick():Unit={mem.foreach(_.drive());mem.foreach(_.capture());d.clock.step()}
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){tick();n+=1};assert(p,s"$label stalled")}
      def cmd(action:Int,cookie:Int,root:BigInt=0x30,base:Int=0x1000,status:Int=0):(BigInt,BigInt,BigInt)={
        zero(d.io.command.bits);val s=d.io.command.bits.state;val q=s.descriptor;s.root.poke(root.U)
        q.context.poke(7.U);q.cookie.poke(cookie.U);q.program.poke(9.U);d.io.command.bits.action.poke(action.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.selectors(0).poke(0.U);q.views(0).base.poke(base.U);q.views(0).limit.poke((base+256).U)}
        d.io.command.valid.poke(true.B);d.io.quiesce.expect(true.B);until("command ready")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
        until("command reply")(d.io.commandReply.valid.peek().litToBoolean);val r=d.io.commandReply.bits;r.status.expect(status.U)
        val held=r.peek().litValue;for(_<-0 until 3){tick();assert(r.peek().litValue==held)}
        val values=(r.state.offsets(0).peek().litValue,r.state.primitives.peek().litValue,r.state.vertices.peek().litValue)
        d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B);values
      }
      def primitive(owner:Int,cookie:Int,seed:Int,root:BigInt=0x30,status:Int=0):Unit={val q=d.io.in(owner).bits;zero(q);q.context.poke(7.U);q.root.poke(root.U);q.program.poke(9.U)
        q.mode.poke(1.U);q.cookie.poke(cookie.U);val a=q.primitive;a.kind.poke(1.U);a.vertexCount.poke(2.U);a.provokingVertex.poke(1.U)
        a.vertices(0).position(0).poke(seed.U);a.vertices(1).position(0).poke((seed+1).U)
        d.io.in(owner).valid.poke(true.B);until("primitive ready")(d.io.in(owner).ready.peek().litToBoolean);tick();d.io.in(owner).valid.poke(false.B)
        until("primitive result")(d.io.result(owner).valid.peek().litToBoolean);d.io.result(owner).bits.status.expect(status.U)
        d.io.result(owner).bits.captured.expect((status==0).B);tick();until("owner drained")(!d.io.busy.peek().litToBoolean && d.io.command.ready.peek().litToBoolean)
      }
      def swap(to:Int):Unit={d.io.switch.bits.poke(to.U);d.io.switch.valid.poke(true.B);d.io.quiesce.expect(true.B)
        until("switch ready")(d.io.switch.ready.peek().litToBoolean);tick();d.io.switch.valid.poke(false.B)
        until("switch reply")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(0.U)
        d.io.selected.expect(to.U);for(_<-0 until 3){tick();d.io.switchReply.valid.expect(true.B)}
        d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B)}
      cmd(TransformFeedbackAction.begin,1);cmd(TransformFeedbackAction.pause,1)
      cmd(TransformFeedbackAction.pause,1,status=5)
      cmd(TransformFeedbackAction.begin,2,base=0x2000);primitive(0,2,11)
      cmd(TransformFeedbackAction.begin,3,root=0x31,base=0x3000);primitive(0,3,51,root=0x31)
      swap(1);primitive(1,2,21);primitive(1,3,61,root=0x31)
      assert(cmd(TransformFeedbackAction.end,2)==((16,2,4)))
      assert(cmd(TransformFeedbackAction.end,3,root=0x31)==((16,2,4)))
      cmd(TransformFeedbackAction.resume,1);primitive(1,1,31);swap(0);primitive(0,1,41)
      assert(cmd(TransformFeedbackAction.end,1)==((16,2,4)))
      assert(mem(0).word(0x2000)==11 && mem(0).word(0x2004)==12)
      assert(mem(1).word(0x2008)==21 && mem(1).word(0x200c)==22)
      assert(mem(1).word(0x1000)==31 && mem(0).word(0x1008)==41)
      assert(mem(0).word(0x3000)==51 && mem(1).word(0x3008)==61)
      cmd(TransformFeedbackAction.begin,4,base=0x4000);mem(0).faultAt=Set(BigInt(0x4000))
      primitive(0,4,71,status=3);mem(0).faultAt=Set.empty;swap(1)
      assert(cmd(TransformFeedbackAction.end,4,status=3)==((0,0,0)))
      swap(0);cmd(TransformFeedbackAction.begin,5,base=0x5000);primitive(0,5,81)
      swap(2);primitive(2,5,91);swap(1);primitive(1,5,101);swap(0);primitive(0,5,111)
      assert(cmd(TransformFeedbackAction.end,5)==((32,4,8)))
      assert(mem(0).word(0x5000)==81 && mem(2).word(0x5008)==91 &&
        mem(1).word(0x5010)==101 && mem(0).word(0x5018)==111)
    }
  }
  it should "fail closed on transfer errors and drain a held offer through reset" in {
    simulate(new TransformFeedbackThreeDomainTransferHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 3){d.io.memRequest(i).ready.poke(true.B)}
      def tick():Unit=d.clock.step()
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<3000){tick();n+=1};assert(p,s"$label stalled")}
      val s=d.io.command.bits.state;val q=s.descriptor;s.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
      q.kind.poke(0.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)
      d.io.command.bits.action.poke(TransformFeedbackAction.begin.U);d.io.command.valid.poke(true.B);until("begin")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until("begin reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B)
      d.io.switch.bits.poke(1.U);d.io.switch.valid.poke(true.B);until("switch")(d.io.switch.ready.peek().litToBoolean);tick();d.io.switch.valid.poke(false.B)
      d.io.injectError.poke(true.B);until("error reply")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(2.U);d.io.poisoned.expect(true.B)
      d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B);d.io.injectError.poke(false.B)
      d.io.command.ready.expect(false.B);d.io.switch.ready.expect(false.B);d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      until("reset clears poison")(!d.io.busy.peek().litToBoolean);d.io.poisoned.expect(false.B)
      d.io.command.valid.poke(true.B);until("begin again")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until("second begin reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B)
      d.io.switch.valid.poke(true.B);until("scope switch")(d.io.switch.ready.peek().litToBoolean);tick();d.io.switch.valid.poke(false.B)
      d.io.injectScope.poke(true.B);until("scope error")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(2.U);d.io.poisoned.expect(true.B)
      d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B);d.io.injectScope.poke(false.B)
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);until("scope reset")(!d.io.busy.peek().litToBoolean)
      d.io.command.valid.poke(true.B);until("third begin")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until("third begin reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B)
      d.io.block.poke(true.B);d.io.switch.valid.poke(true.B);until("second switch")(d.io.switch.ready.peek().litToBoolean);tick();d.io.switch.valid.poke(false.B)
      tick();d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);for(_<-0 until 4){tick();d.io.switchReply.valid.expect(false.B)}
      d.io.block.poke(false.B);until("cancelled switch reply")(d.io.switchReply.valid.peek().litToBoolean);d.io.switchReply.bits.expect(4.U)
      d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B);until("reset drain")(!d.io.busy.peek().litToBoolean)
      d.io.poisoned.expect(false.B)
      d.io.command.valid.poke(true.B);until("fourth begin")(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until("fourth begin reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B)
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);d.io.busy.expect(true.B);d.io.command.ready.expect(false.B)
      until("idle owner reset")(!d.io.busy.peek().litToBoolean)
      d.io.switch.bits.poke(0.U);d.io.switch.valid.poke(true.B);until("held result switch")(d.io.switch.ready.peek().litToBoolean)
      tick();d.io.switch.valid.poke(false.B);until("held result")(d.io.switchReply.valid.peek().litToBoolean)
      val held=d.io.switchReply.bits.peek().litValue;d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      for(_<-0 until 4){d.io.switchReply.valid.expect(true.B);assert(d.io.switchReply.bits.peek().litValue==held);tick()}
      d.io.switchReply.ready.poke(true.B);tick();d.io.switchReply.ready.poke(false.B);until("held result reset drain")(!d.io.busy.peek().litToBoolean)
    }
  }
  it should "retain an accepted control command through reset and allow reuse" in {
    simulate(new TransformFeedbackThreeDomainTransferHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 3)d.io.memRequest(i).ready.poke(true.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<3000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val s=d.io.command.bits.state;val q=s.descriptor;s.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
      q.kind.poke(0.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)
      d.io.command.bits.action.poke(TransformFeedbackAction.begin.U);d.io.block.poke(true.B);d.io.command.valid.poke(true.B)
      until("command admission")(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 4){d.io.commandReply.valid.expect(false.B);d.io.busy.expect(true.B);d.clock.step()}
      d.io.block.poke(false.B);until("cancelled command reply")(d.io.commandReply.valid.peek().litToBoolean)
      d.io.commandReply.bits.status.expect(4.U);d.io.commandReply.bits.applied.expect(false.B)
      val held=d.io.commandReply.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();d.io.commandReply.valid.expect(true.B);assert(d.io.commandReply.bits.peek().litValue==held)}
      d.io.commandReply.ready.poke(true.B);d.clock.step();d.io.commandReply.ready.poke(false.B)
      until("reset drain")(!d.io.busy.peek().litToBoolean)
      d.io.command.valid.poke(true.B);until("reuse command")(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
      until("reuse reply")(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.bits.status.expect(0.U);d.io.commandReply.bits.applied.expect(true.B)
      d.io.commandReply.ready.poke(true.B);d.clock.step();d.io.commandReply.ready.poke(false.B)
    }
  }
}
