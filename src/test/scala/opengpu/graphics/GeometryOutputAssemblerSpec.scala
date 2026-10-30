package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryOutputAssemblerSpec extends AnyFlatSpec {
  private def init(d:GeometryOutputAssembler):Unit={
    d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
    d.io.command.valid.poke(false.B);d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
    d.io.primitive.ready.poke(false.B);d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def start(d:GeometryOutputAssembler,topology:Int,max:Int,components:Int,invocation:Int=31):Unit={
    val t=d.io.task.bits;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.invocationId.poke(invocation.U)
    t.topology.poke(topology.U);t.maxVertices.poke(max.U);t.outputComponents.poke(components.U)
    d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
  }
  private def command(d:GeometryOutputAssembler,op:Int,id:Int=0):Unit={
    d.io.command.bits.operation.poke(op.U);d.io.command.bits.vertex.layer.poke((id+1000).U)
    for(i<-0 until 64)d.io.command.bits.vertex.components(i).poke((id*100+i).U)
    d.io.command.valid.poke(true.B);d.io.command.ready.expect(true.B);d.clock.step();d.io.command.valid.poke(false.B)
  }
  private def output(d:GeometryOutputAssembler,ids:Seq[Int],invocation:Int=31):Unit={
    d.io.primitive.valid.expect(true.B);val o=d.io.primitive.bits
    o.taskId.expect(19.U);o.primitiveId.expect(27.U);o.invocationId.expect(invocation.U);o.vertexCount.expect(ids.size.U)
    for((id,v)<-ids.zipWithIndex){o.vertices(v).layer.expect((id+1000).U)
      for(i<-0 until 64)o.vertices(v).components(i).expect((id*100+i).U)}
    val held=o.peek().litValue
    for(_<-0 until 3){d.io.command.ready.expect(false.B);d.clock.step();d.io.primitive.valid.expect(true.B);assert(o.peek().litValue==held)}
    d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B);d.io.primitive.valid.expect(false.B)
  }
  private def finish(d:GeometryOutputAssembler,status:Int,emitted:Int):Unit={
    d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(status.U)
    d.io.completion.bits.emittedVertices.expect(emitted.U);d.io.completion.bits.taskId.expect(19.U)
    val held=d.io.completion.bits.peek().litValue
    for(_<-0 until 2){d.io.task.ready.expect(false.B);d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
    d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
  }
  it should "assemble points, line strips and alternating triangle winding across primitive boundaries" in {
    simulate(new GeometryOutputAssembler){d=>init(d)
      start(d,0,2,64);command(d,0,1);output(d,Seq(1));command(d,0,2);output(d,Seq(2));command(d,2);finish(d,0,2)
      start(d,1,5,64);command(d,0,3);d.io.primitive.valid.expect(false.B)
      command(d,0,4);output(d,Seq(3,4));command(d,0,5);output(d,Seq(4,5));command(d,1)
      command(d,0,6);d.io.primitive.valid.expect(false.B);command(d,0,7);output(d,Seq(6,7));command(d,2);finish(d,0,5)
      start(d,2,8,64);command(d,0,1);command(d,0,2);d.io.primitive.valid.expect(false.B)
      command(d,0,3);output(d,Seq(1,2,3));command(d,0,4);output(d,Seq(3,2,4))
      command(d,0,5);output(d,Seq(3,4,5));command(d,1)
      command(d,0,6);command(d,0,7);d.io.primitive.valid.expect(false.B);command(d,0,8);output(d,Seq(6,7,8))
      command(d,2);finish(d,0,8)
      start(d,2,2,64);command(d,0,1);command(d,0,2);command(d,2);d.io.primitive.valid.expect(false.B);finish(d,0,2)
    }
  }
  it should "enforce coupled limits and retain a held primitive through cancellation" in {
    simulate(new GeometryOutputAssembler){d=>init(d)
      for((vertices,components,invocation,topology)<-Seq((257,4,0,0),(16,65,0,0),(17,64,0,0),(1,4,32,0),(1,4,0,3))){
        start(d,topology,vertices,components,invocation);finish(d,1,0)
      }
      start(d,0,256,4);for(i<-0 until 256){command(d,0,i);output(d,Seq(i))};command(d,0,256);finish(d,1,256)
      start(d,0,16,64);for(i<-0 until 16){command(d,0,i);output(d,Seq(i))};command(d,2);finish(d,0,16)
      for(invocation<-0 until 32){start(d,0,1,4,invocation);command(d,0,invocation)
        output(d,Seq(invocation),invocation);command(d,2);finish(d,0,1)}
      start(d,0,16,64);command(d,0,1);d.io.primitive.valid.expect(true.B)
      val held=d.io.primitive.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 4){d.io.completion.valid.expect(false.B);d.io.primitive.valid.expect(true.B)
        assert(d.io.primitive.bits.peek().litValue==held);d.clock.step()}
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B);finish(d,2,1)
      start(d,0,1,4);command(d,0,9);output(d,Seq(9));command(d,2);finish(d,0,1)
    }
  }
}
