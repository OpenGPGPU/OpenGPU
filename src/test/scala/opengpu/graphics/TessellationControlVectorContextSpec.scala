package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class ControlVectorContextHarness extends Module {
  val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationPatchContextTask));val opened=Decoupled(UInt(2.W))
    val seal=Flipped(Decoupled(Bool()));val in=Flipped(Decoupled(new TessellationControlVectorRequest(config)))
    val commit=Decoupled(new VectorCommitRequest(config));val status=Output(UInt(2.W))
    val park=Flipped(Decoupled(new TessellationWarpPark(2,4)));val resume=Decoupled(new TessellationWarpResume(2))
    val finish=Flipped(Decoupled(UInt(5.W)));val control=Decoupled(new TessellationBarrierResult)
    val close=Flipped(Decoupled(Bool()));val cancel=Input(Bool());val offered=Output(Bool())
  })
  val scheduler=Module(new TessellationControlWarpScheduler(2,4));val vector=Module(new TessellationControlVectorAccess(config))
  scheduler.io.begin<>io.begin;io.opened<>scheduler.io.opened;scheduler.io.seal<>io.seal
  scheduler.io.load.valid:=false.B;scheduler.io.load.bits:=0.U.asTypeOf(scheduler.io.load.bits)
  scheduler.io.inspect.valid:=false.B;scheduler.io.inspect.bits:=0.U.asTypeOf(scheduler.io.inspect.bits);scheduler.io.reply.ready:=true.B
  scheduler.io.access<>vector.io.request;vector.io.result<>scheduler.io.accessResult
  io.offered:=vector.io.request.valid && !vector.io.request.ready
  scheduler.io.park<>io.park;io.resume<>scheduler.io.resume;scheduler.io.finish<>io.finish
  io.control<>scheduler.io.control;scheduler.io.close<>io.close;scheduler.io.cancel:=io.cancel
  vector.io.in<>io.in;io.commit<>vector.io.commit;io.status:=vector.io.status
  vector.io.kernelStart:=false.B;vector.io.warpStart.valid:=false.B;vector.io.warpStart.bits:=0.U;vector.io.cancel:=io.cancel
  vector.io.terminal:=scheduler.io.control.fire && scheduler.io.control.bits.status=/=0.U
}
class TessellationControlVectorContextSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "write and read control outputs across a patch barrier using vector issue" in {
    simulate(new ControlVectorContextHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,s"$label timeout")}
      val t=d.io.begin.bits;t.taskId.poke(10.U);t.layout.inputVertices.poke(1.U);t.layout.outputVertices.poke(4.U);t.layout.outputComponents.poke(1.U)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);d.clock.step();d.io.begin.valid.poke(false.B)
      d.io.opened.ready.poke(true.B);until("opened")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
      d.clock.step();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B);until("seal")(d.io.seal.ready.peek().litToBoolean)
      d.clock.step();d.io.seal.valid.poke(false.B)
      def issue(op:Int,reverse:Boolean=false):Unit={val q=d.io.in.bits;q.operation.poke(op.U);q.sideEffectMask.poke(15.U)
        q.vector.executionMask.poke(15.U);q.vector.issued.decode.activeMask.poke(15.U)
        for(i<-0 until 4){q.vector.issued.vs1Data(i).poke((if(op==0)100+i else if(reverse)3-i else i).U)
          q.vector.issued.vs2Data(i).poke(1.U)}
        d.io.in.valid.poke(true.B);until("issue")(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B)}
      def finish(read:Boolean=false):Unit={until("commit")(d.io.commit.valid.peek().litToBoolean);d.io.status.expect(0.U)
        if(read)for(i<-0 until 4)d.io.commit.bits.writeback.data(i).expect((103-i).U)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)}
      issue(0);finish();issue(2);finish()
      d.io.park.bits.warp.poke(0.U);d.io.park.bits.lanes.poke(15.U);d.io.park.bits.barrierId.poke(3.U)
      d.io.park.valid.poke(true.B);until("park")(d.io.park.ready.peek().litToBoolean);d.clock.step();d.io.park.valid.poke(false.B)
      d.io.resume.ready.poke(true.B);until("resume")(d.io.resume.valid.peek().litToBoolean);d.io.resume.bits.status.expect(0.U)
      d.clock.step();d.io.resume.ready.poke(false.B);issue(1,reverse=true);finish(read=true)
      for(i<-0 until 4){d.io.finish.bits.poke(i.U);d.io.finish.valid.poke(true.B)
        until("finish")(d.io.finish.ready.peek().litToBoolean);d.clock.step()};d.io.finish.valid.poke(false.B)
      d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(0.U);d.io.control.bits.barriers.expect(1.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.close.valid.poke(true.B);until("close")(d.io.close.ready.peek().litToBoolean);d.clock.step();d.io.close.valid.poke(false.B)
      d.io.begin.valid.poke(true.B);until("second begin")(d.io.begin.ready.peek().litToBoolean);d.clock.step();d.io.begin.valid.poke(false.B)
      d.io.opened.ready.poke(true.B);until("second open")(d.io.opened.valid.peek().litToBoolean);d.clock.step();d.io.opened.ready.poke(false.B)
      d.io.seal.valid.poke(true.B);until("second seal")(d.io.seal.ready.peek().litToBoolean);d.clock.step();d.io.seal.valid.poke(false.B)
      d.io.park.valid.poke(true.B);until("second park")(d.io.park.ready.peek().litToBoolean);d.clock.step();d.io.park.valid.poke(false.B)
      issue(1);until("blocked vector offer")(d.io.offered.peek().litToBoolean)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.control.ready.poke(true.B)
      until("cancel control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(2.U)
      d.clock.step();d.io.control.ready.poke(false.B);until("cancel commit")(d.io.commit.valid.peek().litToBoolean)
      d.io.status.expect(2.U);d.io.commit.bits.writesVd.expect(false.B);d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      d.io.resume.ready.poke(true.B);until("cancel resume")(d.io.resume.valid.peek().litToBoolean);d.clock.step();d.io.resume.ready.poke(false.B)
      d.io.close.valid.poke(true.B);until("cancel close")(d.io.close.ready.peek().litToBoolean);d.clock.step()
    }
  }
}
