package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryPrimitiveInputSpec extends AnyFlatSpec {
  it should "retain ordered primitive inputs with linked component maps and reject invalid sources" in {
    simulate(new GeometryPrimitiveInput()) { d =>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.task.ready.poke(false.B);d.io.fault.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<250){d.clock.step();n+=1};assert(p)}
      def offer(kind:Int,count:Int,components:Int,bad:Boolean=false):Unit={
        val x=d.io.in.bits;x.taskId.poke(19.U);x.primitive.primitiveId.poke((30+kind).U)
        x.primitive.kind.poke(kind.U);x.primitive.vertexCount.poke(count.U);x.primitive.varyingSlots.poke(16.U)
        x.invocations.poke(32.U);x.outputTopology.poke(2.U);x.maxVertices.poke(8.U);x.outputComponents.poke(64.U)
        x.inputComponents.poke(components.U)
        for(c<-0 until 64)x.componentSources(c).poke((if(bad && c==0)68 else if(c==0)67 else c-1).U)
        for(v<-0 until 3){for(c<-0 until 4)x.primitive.vertices(v).position(c).poke((1000*v+c).U)
          for(c<-0 until 64)x.primitive.vertices(v).varyings(c/4)(c%4).poke((1000*v+4+c).U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        x.taskId.poke(99.U);x.primitive.vertices(0).varyings(15)(3).poke(0.U)
      }
      for((kind,count)<-Seq((0,1),(1,2),(2,3))){
        offer(kind,count,64);until(d.io.task.valid.peek().litToBoolean)
        val t=d.io.task.bits;t.taskId.expect(19.U);t.primitiveId.expect((30+kind).U)
        t.inputVertices.expect(count.U);t.inputComponents.expect(64.U);t.invocations.expect(32.U)
        t.maxVertices.expect(8.U);t.outputComponents.expect(64.U)
        for(v<-0 until count;c<-0 until 64)t.vertices(v).components(c).expect((1000*v+(if(c==0)67 else c-1)).U)
        val held=t.peek().litValue;for(_<-0 until 3){d.io.in.ready.expect(false.B);assert(t.peek().litValue==held);d.clock.step()}
        d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)
      }
      offer(2,3,1,bad=true);until(d.io.fault.valid.peek().litToBoolean)
      d.io.fault.bits.taskId.expect(19.U);d.io.fault.bits.primitiveId.expect(32.U)
      d.io.task.valid.expect(false.B);for(_<-0 until 3){d.io.fault.valid.expect(true.B);d.clock.step()}
      d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B)
      offer(0,1,0);d.io.task.valid.expect(true.B);d.io.task.bits.inputComponents.expect(0.U)
      d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)
      for((kind,count,components)<-Seq((0,2,1),(0,1,65))){
        offer(kind,count,components);d.io.fault.valid.expect(true.B);d.io.task.valid.expect(false.B)
        d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B)
      }
    }
  }
}
