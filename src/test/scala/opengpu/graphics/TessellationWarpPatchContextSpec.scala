package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class WarpPatchContextHarness extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationPatchContextTask));val opened=Decoupled(UInt(2.W))
    val seal=Flipped(Decoupled(Bool()));val request=Flipped(Decoupled(new TessellationWarpPatchRequest(2,16)))
    val result=Decoupled(new TessellationWarpPatchResult(2,16))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val release=Decoupled(new TessellationBarrierRelease)
    val finish=Flipped(Decoupled(UInt(5.W)));val control=Decoupled(new TessellationBarrierResult)
    val close=Flipped(Decoupled(Bool()));val cancel=Input(Bool())
  })
  val context=Module(new TessellationPatchContext);val warp=Module(new TessellationWarpPatchAccess(2,16))
  context.io.begin<>io.begin;io.opened<>context.io.opened;context.io.sealInput<>io.seal
  context.io.load.valid:=false.B;context.io.load.bits:=0.U.asTypeOf(context.io.load.bits)
  context.io.inspect.valid:=false.B;context.io.inspect.bits:=0.U.asTypeOf(context.io.inspect.bits)
  context.io.access<>warp.io.access;warp.io.reply<>context.io.reply;warp.io.request<>io.request;io.result<>warp.io.result
  context.io.arrive<>io.arrive;io.release<>context.io.release;context.io.finish<>io.finish
  io.control<>context.io.controlResult;context.io.close<>io.close
  context.io.cancel:=io.cancel;warp.io.cancel:=io.cancel;warp.io.terminal:=context.io.controlResult.fire && context.io.controlResult.bits.status=/=0.U
}

class TessellationWarpPatchContextSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "share a 32-invocation patch across two warps and a barrier" in {
    simulate(new WarpPatchContextHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<2000){d.clock.step();n+=1};assert(p)}
      val t=d.io.begin.bits;t.taskId.poke(42.U);t.layout.inputVertices.poke(1.U);t.layout.outputVertices.poke(32.U)
      t.layout.outputComponents.poke(1.U);d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean)
      d.clock.step();d.io.begin.valid.poke(false.B);d.io.opened.ready.poke(true.B)
      until(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U);d.clock.step();d.io.opened.ready.poke(false.B)
      d.io.seal.valid.poke(true.B);until(d.io.seal.ready.peek().litToBoolean);d.clock.step();d.io.seal.valid.poke(false.B)
      def batch(w:Int,write:Boolean,bad:Boolean=false,changedFirst:Boolean=false):Unit={val q=d.io.request.bits
        q.warp.poke(w.U);q.base.poke((16*w).U);q.mask.poke("hffff".U)
        for(i<-0 until 16){val a=q.access(i);a.output.poke(true.B);a.write.poke(write.B)
          a.vertex.poke((if(bad && i==1) 0 else 16*w+i).U);a.component.poke(0.U)
          a.data.poke((if(bad && i==0) 999 else 100+16*w+i).U)}
        d.io.request.valid.poke(true.B);until(d.io.request.ready.peek().litToBoolean);d.clock.step();d.io.request.valid.poke(false.B)
        until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.status.expect((if(bad)1 else 0).U)
        d.io.result.bits.retired.expect((if(bad)3 else 0xffff).U)
        if(!write && !bad)for(i<-0 until 16)d.io.result.bits.data(i).expect((if(changedFirst && i==0) 999 else 100+16*w+i).U)
        val held=d.io.result.bits.peek().litValue;d.clock.step(3);assert(d.io.result.bits.peek().litValue==held)
        d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B)
      }
      batch(0,true);batch(1,true)
      for(i<-0 until 32){d.io.arrive.bits.invocation.poke(i.U);d.io.arrive.bits.barrierId.poke(9.U)
        d.io.arrive.valid.poke(true.B);until(d.io.arrive.ready.peek().litToBoolean);d.clock.step();d.io.arrive.valid.poke(false.B)}
      d.io.release.ready.poke(true.B)
      for(i<-0 until 32){until(d.io.release.valid.peek().litToBoolean);d.io.release.bits.invocation.expect(i.U);d.clock.step()}
      d.io.release.ready.poke(false.B);batch(0,false);batch(1,false);batch(1,true,bad=true);batch(1,false,changedFirst=true)
      for(i<-0 until 32){d.io.finish.bits.poke(i.U);d.io.finish.valid.poke(true.B)
        until(d.io.finish.ready.peek().litToBoolean);d.clock.step();d.io.finish.valid.poke(false.B)}
      d.io.control.ready.poke(true.B);until(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(0.U);d.io.control.bits.barriers.expect(1.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.close.valid.poke(true.B);until(d.io.close.ready.peek().litToBoolean);d.clock.step()
    }
  }
}
