package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationControlVectorAccessSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "map checked vector operands and retain commits through faults and cancellation" in {
    simulate(new TessellationControlVectorAccess(GpuConfig(lanes=4,warps=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<40){d.clock.step();n+=1};assert(p)}
      def issue(op:Int,mask:Int,side:Int=15,warp:Int=0,bad:Boolean=false):Unit={val q=d.io.in.bits
        q.operation.poke(op.U);q.base.poke((warp*4).U);q.sideEffectMask.poke(side.U)
        val v=q.vector;v.executionMask.poke(mask.U);v.issued.decode.activeMask.poke(15.U)
        v.issued.decode.warpId.poke(warp.U);v.issued.decode.pc.poke(0x100.U);v.issued.decode.instruction.poke((5<<7).U)
        for(i<-0 until 4){v.issued.vs1Data(i).poke((if(op==0)100+i else if(bad && i==1)64 else warp*4+i).U)
          v.issued.vs2Data(i).poke((1|(i<<3)).U);v.issued.oldVdData(i).poke((200+i).U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def finish(status:Int,load:Boolean=false,scalar:Boolean=false):Unit={until(d.io.commit.valid.peek().litToBoolean)
        d.io.status.expect(status.U);d.io.commit.bits.writesVd.expect(load.B);d.io.commit.bits.writesScalar.expect(scalar.B)
        val held=d.io.commit.bits.peek().litValue;d.clock.step(3);assert(d.io.commit.bits.peek().litValue==held)
        d.io.in.ready.expect(false.B);d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)}
      def request(mask:Int,write:Boolean,warp:Int=0):Unit={until(d.io.request.valid.peek().litToBoolean)
        d.io.request.bits.warp.expect(warp.U);d.io.request.bits.base.expect((4*warp).U);d.io.request.bits.mask.expect(mask.U)
        for(i<-0 until 4 if ((mask>>i)&1)!=0){val a=d.io.request.bits.access(i);a.output.expect(true.B)
          a.vertex.expect((4*warp+i).U);a.component.expect(i.U);a.write.expect(write.B);if(write)a.data.expect((100+i).U)}
        val held=d.io.request.bits.peek().litValue;d.clock.step(2);assert(d.io.request.bits.peek().litValue==held)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)}
      def result(mask:Int,status:Int=0,warp:Int=0):Unit={d.io.result.bits.warp.poke(warp.U);d.io.result.bits.retired.poke(mask.U)
        d.io.result.bits.status.poke(status.U);for(i<-0 until 4)d.io.result.bits.data(i).poke((300+i).U)
        d.io.result.valid.poke(true.B);d.io.result.ready.expect(true.B);d.clock.step();d.io.result.valid.poke(false.B)}
      issue(0,3);finish(0,scalar=true)
      issue(2,15);until(d.io.commit.valid.peek().litToBoolean);d.io.request.valid.expect(false.B);finish(1)
      issue(0,15);finish(0,scalar=true);issue(2,15,side=5);request(5,true);result(5);finish(0,scalar=true)
      issue(1,10);request(10,false);result(10);until(d.io.commit.valid.peek().litToBoolean)
      d.io.commit.bits.writeback.data(0).expect(200.U);d.io.commit.bits.writeback.data(1).expect(301.U)
      d.io.commit.bits.writeback.data(2).expect(202.U);d.io.commit.bits.writeback.data(3).expect(303.U);finish(0,load=true)
      issue(1,2,bad=true);finish(1);issue(1,0);finish(0,load=true)
      issue(1,3);request(3,false);result(1,status=1);finish(1)
      issue(1,1);until(d.io.request.valid.peek().litToBoolean);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      request(1,false);result(1,status=2);finish(2)
      issue(1,1);until(d.io.request.valid.peek().litToBoolean);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.request.valid.expect(true.B);d.io.terminal.poke(true.B);d.clock.step();d.io.terminal.poke(false.B);finish(2)
      issue(1,1);until(d.io.request.valid.peek().litToBoolean);d.io.request.ready.poke(true.B);d.io.terminal.poke(true.B)
      d.clock.step();d.io.request.ready.poke(false.B);d.io.terminal.poke(false.B);d.io.commit.valid.expect(false.B)
      result(1);finish(2)
      d.io.warpStart.valid.poke(true.B);d.io.warpStart.bits.poke(0.U);d.clock.step();d.io.warpStart.valid.poke(false.B)
      issue(2,1);finish(1);issue(0,15,warp=1);finish(0,scalar=true)
      issue(2,15,warp=1);request(15,true,warp=1);result(15,warp=1);finish(0,scalar=true)
    }
  }
}
