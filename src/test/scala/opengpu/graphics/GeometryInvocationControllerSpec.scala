package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryInvocationControllerSpec extends AnyFlatSpec {
  private def init(d:GeometryInvocationController):Unit={
    d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
    d.io.command.valid.poke(false.B);d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
    d.io.workerCompletion.valid.poke(false.B);d.io.workerCompletion.bits.poke(0.U.asTypeOf(d.io.workerCompletion.bits))
    d.io.launch.ready.poke(false.B);d.io.primitive.ready.poke(false.B);d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def until(d:GeometryInvocationController)(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
  private def start(d:GeometryInvocationController,count:Int=32,topology:Int=1,max:Int=2,components:Int=64,inputCount:Int=6):Unit={
    val t=d.io.task.bits;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.inputVertices.poke(inputCount.U)
    t.inputComponents.poke(64.U);t.invocations.poke(count.U);t.topology.poke(topology.U)
    t.maxVertices.poke(max.U);t.outputComponents.poke(components.U)
    for(v<-0 until 6){t.vertices(v).layer.poke((900+v).U);for(c<-0 until 64)t.vertices(v).components(c).poke((v*100+c).U)}
    d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
    t.vertices(0).components(0).poke(9999.U)
  }
  private def launch(d:GeometryInvocationController,index:Int):Unit={
    until(d)(d.io.launch.valid.peek().litToBoolean);val q=d.io.launch.bits
    q.taskId.expect(19.U);q.primitiveId.expect(27.U);q.invocationId.expect(index.U)
    q.inputVertices.expect(6.U);q.inputComponents.expect(64.U)
    for(v<-0 until 6){q.vertices(v).layer.expect((900+v).U);for(c<-0 until 64)q.vertices(v).components(c).expect((v*100+c).U)}
    val held=q.peek().litValue;for(_<-0 until 2){d.clock.step();assert(q.peek().litValue==held)}
    d.io.launch.ready.poke(true.B);d.clock.step();d.io.launch.ready.poke(false.B)
  }
  private def command(d:GeometryInvocationController,op:Int,value:Int):Unit={
    d.io.command.bits.operation.poke(op.U);d.io.command.bits.vertex.layer.poke((1000+value).U)
    for(c<-0 until 64)d.io.command.bits.vertex.components(c).poke((value*100+c).U)
    d.io.command.valid.poke(true.B);until(d)(d.io.command.ready.peek().litToBoolean);d.clock.step();d.io.command.valid.poke(false.B)
  }
  private def workerDone(d:GeometryInvocationController,index:Int,success:Boolean):Unit={
    val r=d.io.workerCompletion.bits;r.taskId.poke(19.U);r.primitiveId.poke(27.U);r.invocationId.poke(index.U);r.success.poke(success.B)
    d.io.workerCompletion.valid.poke(true.B);until(d)(d.io.workerCompletion.ready.peek().litToBoolean)
    d.clock.step();d.io.workerCompletion.valid.poke(false.B)
  }
  private def finish(d:GeometryInvocationController,status:Int,count:Int):Unit={
    until(d)(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
    d.io.completion.bits.completedInvocations.expect(count.U);d.io.completion.bits.taskId.expect(19.U)
    val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.io.task.ready.expect(false.B);d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
    d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
  }
  it should "retain six input vertices and serialize all 32 invocations through held primitive output" in {
    simulate(new GeometryInvocationController){d=>init(d);start(d)
      for(index<-0 until 32){launch(d,index);command(d,0,index*2);command(d,0,index*2+1)
        d.io.primitive.valid.expect(true.B);val p=d.io.primitive.bits
        p.invocationId.expect(index.U);p.vertexCount.expect(2.U)
        p.vertices(0).components(63).expect((index*200+63).U);p.vertices(1).components(63).expect(((index*2+1)*100+63).U)
        val held=p.peek().litValue;workerDone(d,index,success=true)
        for(_<-0 until 3){d.io.launch.valid.expect(false.B);d.io.completion.valid.expect(false.B);assert(p.peek().litValue==held);d.clock.step()}
        d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)
      }
      finish(d,0,32)
    }
  }
  it should "reject invalid tasks and drain held output plus worker ownership on cancellation or faults" in {
    simulate(new GeometryInvocationController){d=>init(d)
      for((count,max,components,inputCount)<-Seq((0,2,64,6),(33,2,64,6),(1,17,64,6),(1,2,65,6),(1,2,64,5))){
        start(d,count=count,max=max,components=components,inputCount=inputCount);finish(d,1,0)}
      for(inputCount<-Seq(1,2,3,4,6)) {
        start(d,count=1,topology=0,max=1,components=4,inputCount=inputCount)
        until(d)(d.io.launch.valid.peek().litToBoolean);d.io.launch.bits.inputVertices.expect(inputCount.U)
        d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);finish(d,2,0)
      }
      start(d,count=1,topology=0,max=1,components=4);launch(d,0);command(d,0,3)
      d.io.primitive.valid.expect(true.B);val held=d.io.primitive.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.abort.expect(true.B)
      workerDone(d,0,success=false)
      for(_<-0 until 4){d.io.completion.valid.expect(false.B);assert(d.io.primitive.bits.peek().litValue==held);d.clock.step()}
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B);finish(d,2,0)
      start(d,count=1,topology=0,max=1,components=4);launch(d,0);command(d,2,0)
      until(d)(d.io.abort.peek().litToBoolean);workerDone(d,0,success=false);finish(d,1,0)
      start(d,count=1,topology=0,max=1,components=4);launch(d,0);command(d,0,2)
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)
      workerDone(d,0,success=false);finish(d,3,0)
      start(d,count=1,topology=0,max=1,components=4);launch(d,0);command(d,0,2)
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)
      command(d,0,3);until(d)(d.io.abort.peek().litToBoolean);workerDone(d,0,success=false);finish(d,1,0)
      start(d,count=1,topology=0,max=1,components=4);launch(d,0);command(d,0,1)
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B);workerDone(d,0,success=true);finish(d,0,1)
    }
  }
}
