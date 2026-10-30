package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationPatchContextSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  it should "drain writes before barriers and preserve patch ownership through inspection" in {
    simulate(new TessellationPatchContext){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def begin():Unit={val t=d.io.begin.bits;t.taskId.poke(31.U);t.layout.inputVertices.poke(2.U);t.layout.inputComponents.poke(2.U)
        t.layout.outputVertices.poke(2.U);t.layout.outputComponents.poke(2.U);t.layout.patchComponents.poke(1.U)
        d.io.begin.valid.poke(true.B);d.io.begin.ready.expect(true.B);d.clock.step();d.io.begin.valid.poke(false.B)
        d.io.opened.valid.expect(true.B);d.io.opened.bits.expect(0.U);d.io.opened.ready.poke(true.B);d.clock.step();d.io.opened.ready.poke(false.B)
        var n=0;while(!d.io.load.ready.peek().litToBoolean && n<5){d.io.load.valid.poke(true.B);d.clock.step();n+=1};d.io.load.valid.poke(false.B)}
      def set(q:TessellationPatchAccess,out:Boolean,patch:Boolean,level:Boolean,write:Boolean,v:Int,c:Int,value:Int):Unit={
        q.output.poke(out.B);q.perPatch.poke(patch.B);q.tessLevel.poke(level.B);q.write.poke(write.B)
        q.vertex.poke(v.U);q.component.poke(c.U);q.data.poke(value.U)}
      def consume(source:Int,inv:Int,status:Int,data:Int=0):Unit={var n=0;while(!d.io.reply.valid.peek().litToBoolean && n<8){d.clock.step();n+=1}
        d.io.reply.valid.expect(true.B);d.io.reply.bits.source.expect(source.U);d.io.reply.bits.invocation.expect(inv.U)
        d.io.reply.bits.result.status.expect(status.U);d.io.reply.bits.result.data.expect(data.U)
        d.io.reply.ready.poke(true.B);d.clock.step();d.io.reply.ready.poke(false.B)}
      def load(v:Int,value:Int):Unit={set(d.io.load.bits,false,false,false,true,v,0,value);d.io.load.valid.poke(true.B)
        d.io.load.ready.expect(true.B);d.clock.step();d.io.load.valid.poke(false.B);consume(0,0,0)}
      def access(inv:Int,out:Boolean,patch:Boolean,level:Boolean,write:Boolean,v:Int,c:Int,value:Int,status:Int,data:Int=0):Unit={
        d.io.access.bits.invocation.poke(inv.U);set(d.io.access.bits.access,out,patch,level,write,v,c,value)
        d.io.access.valid.poke(true.B);d.io.access.ready.expect(true.B);d.clock.step();d.io.access.valid.poke(false.B);consume(1,inv,status,data)}
      def arrive(inv:Int,id:Int):Unit={d.io.arrive.bits.invocation.poke(inv.U);d.io.arrive.bits.barrierId.poke(id.U)
        d.io.arrive.valid.poke(true.B);d.io.arrive.ready.expect(true.B);d.clock.step();d.io.arrive.valid.poke(false.B)}
      def finish(inv:Int):Unit={d.io.finish.bits.poke(inv.U);d.io.finish.valid.poke(true.B);d.io.finish.ready.expect(true.B)
        d.clock.step();d.io.finish.valid.poke(false.B)}
      def inspect(v:Int,c:Int,value:Int,level:Boolean=false):Unit={set(d.io.inspect.bits,true,level,level,false,v,c,0)
        d.io.inspect.valid.poke(true.B);d.io.inspect.ready.expect(true.B);d.clock.step();d.io.inspect.valid.poke(false.B);consume(2,0,0,value)}
      def close():Unit={d.io.close.valid.poke(true.B);d.io.close.ready.expect(true.B);d.clock.step();d.io.close.valid.poke(false.B);d.io.busy.expect(false.B)}
      begin()
      set(d.io.load.bits,false,false,false,true,0,0,10);d.io.load.valid.poke(true.B);d.io.load.ready.expect(true.B)
      d.clock.step();d.io.load.valid.poke(false.B);d.io.sealInput.valid.poke(true.B)
      for(_<-0 until 2){d.io.sealInput.ready.expect(false.B);d.clock.step()}
      consume(0,0,0);d.io.sealInput.valid.poke(false.B);load(1,20)
      d.io.sealInput.valid.poke(true.B);d.io.sealInput.ready.expect(true.B);d.clock.step();d.io.sealInput.valid.poke(false.B)
      access(0,false,false,false,false,1,0,0,0,20)
      d.io.access.bits.invocation.poke(0.U);set(d.io.access.bits.access,true,false,false,true,0,0,123)
      d.io.access.valid.poke(true.B);d.io.access.ready.expect(true.B);d.clock.step();d.io.access.valid.poke(false.B)
      d.io.arrive.bits.invocation.poke(0.U);d.io.arrive.bits.barrierId.poke(7.U);d.io.arrive.valid.poke(true.B)
      for(_<-0 until 3){d.io.arrive.ready.expect(false.B);d.io.release.valid.expect(false.B);d.clock.step()}
      consume(1,0,0);d.io.arrive.ready.expect(true.B);d.clock.step();d.io.arrive.valid.poke(false.B)
      access(0,true,false,false,false,0,0,0,1)
      access(1,true,false,false,true,1,0,456,0);arrive(1,7)
      d.io.release.valid.expect(true.B);d.io.release.bits.invocation.expect(0.U);d.io.release.ready.poke(true.B);d.clock.step()
      d.io.release.bits.invocation.expect(1.U);d.clock.step();d.io.release.ready.poke(false.B)
      access(0,true,false,false,false,1,0,0,0,456);access(1,true,false,false,true,0,0,999,1)
      access(0,false,false,false,true,0,0,999,1);access(0,false,false,false,false,0,0,0,0,10)
      access(0,true,true,true,true,0,0,7,0);finish(0);finish(1)
      d.io.controlResult.valid.expect(true.B);d.io.controlResult.bits.status.expect(0.U);d.io.controlResult.bits.barriers.expect(1.U)
      d.io.access.valid.poke(true.B);d.io.access.ready.expect(false.B)
      d.io.controlResult.ready.poke(true.B);d.clock.step();d.io.controlResult.ready.poke(false.B);d.io.access.valid.poke(false.B)
      inspect(0,0,123);inspect(1,0,456);inspect(0,0,7,level=true);close()
      begin();d.io.sealInput.valid.poke(true.B);d.clock.step();d.io.sealInput.valid.poke(false.B)
      arrive(0,1);arrive(1,2);d.io.controlResult.valid.expect(true.B);d.io.controlResult.bits.status.expect(1.U)
      d.io.controlResult.ready.poke(true.B);d.clock.step();d.io.controlResult.ready.poke(false.B);close()
      begin();set(d.io.load.bits,false,false,false,true,0,0,5);d.io.load.valid.poke(true.B);d.io.load.ready.expect(true.B)
      d.clock.step();d.io.load.valid.poke(false.B);d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.io.controlResult.valid.expect(false.B);d.clock.step()}
      consume(0,0,0);d.io.cancel.poke(false.B)
      var n=0;while(!d.io.controlResult.valid.peek().litToBoolean && n<8){d.clock.step();n+=1}
      d.io.controlResult.valid.expect(true.B);d.io.controlResult.bits.status.expect(2.U)
      d.io.controlResult.ready.poke(true.B);d.clock.step();d.io.controlResult.ready.poke(false.B);close()
    }
  }
}
