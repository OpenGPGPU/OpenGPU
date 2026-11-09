package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationCuFinishSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "hold warp retirement through per-invocation finish and terminal drain" in {
    simulate(new TessellationCuFinish(GpuConfig(lanes=4,warps=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<50){d.clock.step();n+=1};assert(p)}
      def launch(w:Int,base:Int,mask:Int):Unit={d.io.launch.bits.warp.poke(w.U);d.io.launch.bits.base.poke(base.U)
        d.io.launch.bits.mask.poke(mask.U);d.io.launch.valid.poke(true.B);d.clock.step();d.io.launch.valid.poke(false.B)}
      def complete(w:Int,normal:Boolean):Unit={d.io.in.bits.warp.poke(w.U);d.io.in.bits.normal.poke(normal.B)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)}
      def send(inv:Int):Unit={until(d.io.finish.valid.peek().litToBoolean);d.io.finish.bits.expect(inv.U)
        val held=d.io.finish.bits.peek().litValue;d.clock.step(3);d.io.finish.valid.expect(true.B)
        assert(d.io.finish.bits.peek().litValue==held);d.io.retired.valid.expect(false.B)
        d.io.finish.ready.poke(true.B);d.clock.step();d.io.finish.ready.poke(false.B)}
      def retired(w:Int):Unit={until(d.io.retired.valid.peek().litToBoolean);d.io.retired.bits.expect(w.U);d.clock.step()}
      launch(0,0,11);launch(1,4,3);complete(0,true);complete(1,true)
      send(0);send(1);send(3);retired(0);send(4);send(5);retired(1);d.io.busy.expect(false.B)
      launch(0,0,15);complete(0,true);until(d.io.finish.valid.peek().litToBoolean)
      d.io.terminal.poke(true.B);d.clock.step();d.io.terminal.poke(false.B);retired(0)
      until(!d.io.busy.peek().litToBoolean);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
      launch(0,0,3);complete(0,true);until(d.io.finish.valid.peek().litToBoolean)
      d.io.finish.ready.poke(true.B);d.io.terminal.poke(true.B);d.clock.step()
      d.io.finish.ready.poke(false.B);d.io.terminal.poke(false.B);retired(0)
      until(!d.io.busy.peek().litToBoolean);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
      launch(1,4,15);complete(1,false);d.io.finish.valid.expect(false.B);retired(1)
      launch(0,0,3);launch(1,4,3);complete(0,true);until(d.io.finish.valid.peek().litToBoolean)
      complete(1,false);retired(1);send(0);send(1);retired(0)
    }
  }
}
