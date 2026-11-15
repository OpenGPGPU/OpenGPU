package opengpu.system

import chisel3._
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Control-protocol peer only: publication counts are accepted retirement
  * inputs, not CPU-generated shader/patch outputs or whole-CU evidence. */
class TessellationJointRangeCursorSpec extends AnyFlatSpec {
  private def zero(x:Data):Unit=x match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if chisel3.reflect.DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if chisel3.reflect.DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private val topSequence=BigInt(1)<<64
  private val topPrimitive=BigInt(1)<<32
  it should "commit actual publication cursors across partial CONT ranges and preserve exclusive endpoints" in {
    simulate(new TessellationVertexTypedRangeControl(VertexShaderStageConfig(),jointCursors=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.taskDrained.poke(true.B)
      def until(label:String)(condition: =>Boolean):Unit={
        var n=0;while(!condition && n<60){d.clock.step();n+=1};assert(condition,s"$label stalled")
      }
      def begin(firstPatch:Int,per:Int,instances:Int,vertices:Int,originalInstances:Int,seq:BigInt,prim:BigInt):Unit={
        val q=d.io.begin.bits;zero(q)
        q.firstSequence.poke(777.U);q.firstPrimitiveId.poke(888.U) // Deliberately stale raw-GNT seeds.
        val r=q.range;val t=r.template;t.version.poke(1.U);t.taskId.poke(100.U)
        t.layout.inputVertices.poke(2.U);t.layout.inputComponents.poke(64.U)
        t.layout.outputVertices.poke(2.U);t.layout.outputComponents.poke(12.U);t.layout.patchComponents.poke(8.U)
        t.draw.input.count.poke(vertices.U);t.draw.input.instances.poke(originalInstances.U)
        t.slice.version.poke(1.U);t.slice.firstOrdinal.poke((firstPatch*2).U)
        t.slice.count.poke(2.U);t.slice.instances.poke(1.U)
        r.patchesPerInstance.poke(per.U);r.instanceCount.poke(instances.U);r.patchCount.poke((per*instances).U)
        d.io.cursor.get.sequence.poke(seq.U);d.io.cursor.get.primitiveId.poke(prim.U)
        d.io.begin.valid.poke(true.B);until("cursor begin")(d.io.begin.ready.peek().litToBoolean)
        d.clock.step();d.io.begin.valid.poke(false.B);zero(q)
      }
      def task(id:Int,ordinal:Int,instance:Int,seq:BigInt,prim:BigInt):Unit={
        until("actual task offer")(d.io.task.valid.peek().litToBoolean)
        d.io.task.bits.taskId.expect(id.U);d.io.task.bits.slice.firstOrdinal.expect(ordinal.U)
        d.io.task.bits.slice.firstInstance.expect(instance.U)
        d.io.firstSequence.expect(seq.U);d.io.firstPrimitiveId.expect(prim.U)
        val held=d.io.task.bits.peek().litValue
        d.clock.step(3);assert(d.io.task.bits.peek().litValue==held)
        d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)
        d.io.taskDrained.poke(false.B)
      }
      def retire(id:Int,published:Int,status:Int=0):Unit={
        val r=d.io.retired.bits;r.taskId.poke(id.U);r.status.poke(status.U)
        r.publishedValid.poke(true.B);r.published.poke(published.U)
        d.io.retired.valid.poke(true.B);until("actual retirement")(d.io.retired.ready.peek().litToBoolean)
        d.clock.step();d.io.retired.valid.poke(false.B);zero(r)
        for(_<-0 until 3){d.io.task.valid.expect(false.B);d.io.completion.valid.expect(false.B);d.io.drained.expect(false.B);d.clock.step()}
        d.io.taskDrained.poke(true.B)
      }
      def report(status:Int,completed:Int,seq:BigInt,prim:BigInt,noTask:Boolean=false):Unit={
        var n=0
        while(!d.io.completion.valid.peek().litToBoolean && n<60){
          if(noTask)assert(!d.io.task.valid.peek().litToBoolean,"one-past cursor must not produce an actual task")
          d.clock.step();n+=1
        }
        d.io.completion.valid.expect(true.B);d.io.completion.bits.range.status.expect(status.U)
        d.io.completion.bits.range.completed.expect(completed.U)
        val c=d.io.nextCursor.get
        assert(c.sequence.peek().litValue==seq,s"actual CONT sequence cursor expected $seq got ${c.sequence.peek().litValue}")
        assert(c.primitiveId.peek().litValue==prim,s"actual CONT primitive cursor expected $prim got ${c.primitiveId.peek().litValue}")
        val held=d.io.completion.bits.peek().litValue;val cursor=c.peek().litValue
        d.io.cancel.poke(true.B);d.io.taskDrained.poke(false.B)
        for(_<-0 until 4){d.clock.step();d.io.completion.valid.expect(true.B)
          assert(d.io.completion.bits.peek().litValue==held);assert(c.peek().litValue==cursor)
          d.io.drained.expect(false.B);d.io.begin.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
        d.io.cancel.poke(false.B);d.io.begin.ready.expect(false.B)
        d.io.taskDrained.poke(true.B)
      }
      // Original instance has ten patches. Four are one directory segment,
      // whose end must not reset a generated primitive cursor for its CONT.
      begin(0,4,1,20,1,13,17)
      var seq=BigInt(13);var prim=BigInt(17)
      for((published,i)<-Seq(3,2,0,1).zipWithIndex){task(100+i,2*i,0,seq,prim);retire(100+i,published)
        seq+=published;prim+=published}
      report(0,4,19,23)
      begin(4,6,1,20,1,19,23)
      seq=19;prim=23
      for((published,i)<-Seq(1,4,0,2,1,3).zipWithIndex){task(100+i,8+2*i,0,seq,prim);retire(100+i,published)
        seq+=published;prim+=published}
      report(0,6,30,0) // Only the actual original-instance boundary resets it.
      // The last representable starts are valid; one-past exclusive endpoints
      // return faithfully. A subsequent nonempty launch rejects before offer.
      begin(0,1,1,2,1,topSequence-1,0);task(100,0,0,topSequence-1,0);retire(100,1)
      report(0,1,topSequence,0)
      begin(0,1,1,2,1,topSequence,0);report(1,0,topSequence,0,noTask=true)
      begin(0,1,1,4,1,13,topPrimitive-1);task(100,0,0,13,topPrimitive-1);retire(100,1)
      report(0,1,14,topPrimitive)
      begin(1,1,1,4,1,14,topPrimitive);report(1,0,14,topPrimitive,noTask=true)
      // Empty work consumes no task and preserves even a one-past cursor.
      begin(0,0,0,0,5,topSequence,topPrimitive);report(0,0,topSequence,topPrimitive,noTask=true)
      // Empty work cannot legitimize an out-of-domain cursor.
      begin(0,0,0,0,5,topSequence+1,0);report(1,0,topSequence+1,0,noTask=true)
      begin(0,0,0,0,5,0,topPrimitive+1);report(1,0,0,topPrimitive+1,noTask=true)
      // A real failed/cancelled task never fabricates a successful next cursor.
      begin(0,1,1,2,1,7,9);task(100,0,0,7,9);retire(100,0,status=4);report(4,0,7,9)
      begin(0,1,1,2,1,7,9);task(100,0,0,7,9);retire(100,0,status=2);report(2,0,7,9)
      // More than the exclusive end is an actual publication protocol fault.
      begin(0,1,1,2,1,topSequence-1,0);task(100,0,0,topSequence-1,0);retire(100,2)
      report(1,1,topSequence+1,0)
      begin(0,1,1,2,1,0,0);task(100,0,0,0,0);retire(100,3);report(0,1,3,0)
    }
  }
}
