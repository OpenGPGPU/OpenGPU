package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationControlWarpSchedulerSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "order each warp's patch access before its barriers and retain ownership through drain" in {
    simulate(new TessellationControlWarpScheduler(2,16)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<2000){d.clock.step();n+=1};assert(p,s"$label timed out")}
      def begin():Unit={val t=d.io.begin.bits;t.taskId.poke(7.U);t.layout.inputVertices.poke(1.U)
        t.layout.outputVertices.poke(32.U);t.layout.outputComponents.poke(1.U)
        d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);d.clock.step();d.io.begin.valid.poke(false.B)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
        d.clock.step();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B)
        until("seal")(d.io.seal.ready.peek().litToBoolean);d.clock.step();d.io.seal.valid.poke(false.B)}
      def access(w:Int,write:Boolean,simultaneousFinish:Boolean=false):Unit={val q=d.io.access.bits;q.warp.poke(w.U);q.base.poke((16*w).U);q.mask.poke("hffff".U)
        for(i<-0 until 16){q.access(i).output.poke(true.B);q.access(i).write.poke(write.B)
          q.access(i).vertex.poke((16*w+i).U);q.access(i).component.poke(0.U);q.access(i).data.poke((100+16*w+i).U)}
        d.io.access.valid.poke(true.B);if(simultaneousFinish){d.io.finish.bits.poke(31.U);d.io.finish.valid.poke(true.B)}
        until("access")(d.io.access.ready.peek().litToBoolean)
        if(simultaneousFinish)d.io.finish.ready.expect(false.B)
        d.clock.step();d.io.access.valid.poke(false.B);d.io.finish.valid.poke(false.B)}
      def parkOffer(w:Int,id:Int):Unit={d.io.park.bits.warp.poke(w.U);d.io.park.bits.base.poke((16*w).U)
        d.io.park.bits.lanes.poke("hffff".U);d.io.park.bits.barrierId.poke(id.U);d.io.park.valid.poke(true.B)}
      def result(w:Int,read:Boolean):Unit={until("access result")(d.io.accessResult.valid.peek().litToBoolean)
        d.io.accessResult.bits.warp.expect(w.U);d.io.accessResult.bits.status.expect(0.U)
        d.io.accessResult.bits.retired.expect("hffff".U)
        if(read)for(i<-0 until 16)d.io.accessResult.bits.data(i).expect((100+16*w+i).U)
        d.io.accessResult.ready.poke(true.B);d.clock.step();d.io.accessResult.ready.poke(false.B)}
      def takeResumes(id:Int):Unit={var seen=Set.empty[Int];var n=0
        while(seen.size<2 && n<400){if(d.io.resume.valid.peek().litToBoolean){val w=d.io.resume.bits.warp.peek().litValue.toInt
          assert(!seen(w));d.io.resume.bits.status.expect(0.U);d.io.resume.bits.barrierId.expect(id.U)
          d.io.resume.ready.poke(true.B);d.clock.step();d.io.resume.ready.poke(false.B);seen+=w}else d.clock.step();n+=1}
        assert(seen==Set(0,1))}
      begin();access(1,true);result(1,false);access(0,true,simultaneousFinish=true)
      parkOffer(1,11);until("independent park1")(d.io.park.ready.peek().litToBoolean)
      d.clock.step();d.io.park.valid.poke(false.B);parkOffer(0,11)
      for(_<-0 until 5){d.io.park.ready.expect(false.B);d.clock.step()}
      until("held result")(d.io.accessResult.valid.peek().litToBoolean)
      for(_<-0 until 3){d.io.park.ready.expect(false.B);d.io.finish.bits.poke(0.U);d.io.finish.valid.poke(true.B)
        d.io.finish.ready.expect(false.B);d.clock.step()}
      d.io.finish.valid.poke(false.B);result(0,false);until("park0")(d.io.park.ready.peek().litToBoolean)
      d.io.finish.bits.poke(0.U);d.io.finish.valid.poke(true.B);d.io.finish.ready.expect(false.B)
      d.clock.step();d.io.park.valid.poke(false.B);d.io.finish.valid.poke(false.B)
      takeResumes(11)
      access(0,false);result(0,true);access(1,false);result(1,true)
      d.io.access.bits.warp.poke(0.U);d.io.access.bits.base.poke(0.U);d.io.access.bits.mask.poke(1.U)
      d.io.access.bits.access(0).vertex.poke(0.U);d.io.access.valid.poke(true.B)
      until("predicated access")(d.io.access.ready.peek().litToBoolean);d.clock.step();d.io.access.valid.poke(false.B)
      d.io.finish.bits.poke(15.U);d.io.finish.valid.poke(true.B);d.io.finish.ready.expect(false.B)
      until("predicated result")(d.io.accessResult.valid.peek().litToBoolean);d.io.finish.ready.expect(false.B)
      d.io.accessResult.bits.retired.expect(1.U);d.io.accessResult.bits.data(0).expect(100.U)
      d.io.accessResult.ready.poke(true.B);d.clock.step();d.io.accessResult.ready.poke(false.B);d.io.finish.valid.poke(false.B)
      for(i<-0 until 32){d.io.finish.bits.poke(i.U);d.io.finish.valid.poke(true.B)
        until("finish")(d.io.finish.ready.peek().litToBoolean);d.clock.step()}
      d.io.finish.valid.poke(false.B);d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(0.U);d.io.control.bits.barriers.expect(1.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.close.valid.poke(true.B);until("close")(d.io.close.ready.peek().litToBoolean);d.clock.step();d.io.close.valid.poke(false.B)
      d.io.busy.expect(false.B)
      begin();parkOffer(0,20);until("fault park0")(d.io.park.ready.peek().litToBoolean);d.clock.step();d.io.park.valid.poke(false.B)
      d.clock.step(8);parkOffer(1,21);until("fault park1")(d.io.park.ready.peek().litToBoolean);d.clock.step();d.io.park.valid.poke(false.B)
      d.io.control.ready.poke(true.B);until("divergent control")(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(1.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.close.valid.poke(true.B);d.io.close.ready.expect(false.B)
      var canceled=Set.empty[Int];var n=0
      while(canceled.size<2 && n<100){if(d.io.resume.valid.peek().litToBoolean){val w=d.io.resume.bits.warp.peek().litValue.toInt
        assert(!canceled(w));d.io.resume.bits.status.expect(2.U);d.io.resume.ready.poke(true.B)
        d.clock.step();d.io.resume.ready.poke(false.B);canceled+=w}else d.clock.step();n+=1}
      assert(canceled==Set(0,1));until("fault close")(d.io.close.ready.peek().litToBoolean)
      d.clock.step();d.io.close.valid.poke(false.B);d.io.busy.expect(false.B)
      begin();access(0,true);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      until("cancelled access")(d.io.accessResult.valid.peek().litToBoolean);d.io.accessResult.bits.status.expect(2.U)
      d.io.control.ready.poke(true.B);until("cancelled control")(d.io.control.valid.peek().litToBoolean)
      d.io.control.bits.status.expect(2.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.close.valid.poke(true.B);d.io.close.ready.expect(false.B)
      d.io.accessResult.ready.poke(true.B);d.clock.step();d.io.accessResult.ready.poke(false.B)
      until("cancel close")(d.io.close.ready.peek().litToBoolean);d.clock.step();d.io.close.valid.poke(false.B)
      d.io.busy.expect(false.B);begin()
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.control.ready.poke(true.B)
      until("reuse control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(2.U)
      d.clock.step();d.io.control.ready.poke(false.B);d.io.close.valid.poke(true.B)
      until("reuse close")(d.io.close.ready.peek().litToBoolean);d.clock.step();d.io.close.valid.poke(false.B)
    }
  }
}
