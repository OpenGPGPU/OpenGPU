package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometryPrimitivePipelineGpuHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val pipeline=Module(new GeometryPrimitivePipeline(config,VertexShaderStageConfig(),4,1))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new GeometryPrimitivePipelineTask(config,VertexShaderStageConfig(),4)))
    val primitive=Decoupled(new GeometryMappedPrimitive(VertexShaderStageConfig()))
    val completion=Decoupled(new GeometryPrimitivePipelineResult)
    val cancel=Input(Bool())
  })
  io.system<>gpu.io;pipeline.io.task<>io.task;io.primitive<>pipeline.io.primitive
  io.completion<>pipeline.io.completion;pipeline.io.cancel:=io.cancel
  val g=gpu.io.graphicsKernels.get;g.launch<>pipeline.io.launch;g.geometryTask.get:=pipeline.io.geometryTask
  pipeline.io.kernelCompletion<>g.completion;pipeline.io.event<>g.geometryEvent.get
  pipeline.io.owner:=g.owned;pipeline.io.taskIds:=g.taskIds;g.geometryCancel.get:=pipeline.io.cancelCu
}

class GeometryPrimitivePipelineSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case x:SInt if DataMirror.directionOf(x)==ActualDirection.Input=>x.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "retain primitive ownership through ordered invocations, faults and cancellation" in {
    simulate(new GeometryPrimitivePipeline(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(),4,2)){d=>
      d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.primitive.ready.poke(false.B);d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.launch.ready.poke(false.B);d.io.kernelCompletion.valid.poke(false.B)
      d.io.kernelCompletion.bits.poke(0.U.asTypeOf(d.io.kernelCompletion.bits));d.io.owner.poke(0.U)
      for(c<-0 until 2){d.io.taskIds(c).poke(0.U);d.io.event(c).valid.poke(false.B)
        d.io.event(c).bits.poke(0.U.asTypeOf(d.io.event(c).bits))}
      d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p)}
      def submit(invocations:Int,bad:Boolean=false,components:Int=1,first:BigInt=10):Unit={val t=d.io.task.bits
        t.commandId.poke(4.U);t.kernel.kernelPc.poke(0x1000.U);t.firstSequence.poke(first.U)
        t.input.taskId.poke(19.U);t.input.primitive.primitiveId.poke(27.U);t.input.primitive.kind.poke(2.U)
        t.input.primitive.vertexCount.poke(3.U);t.input.primitive.varyingSlots.poke(1.U)
        t.input.primitive.vertices(2).position(0).poke(77.U);t.input.inputComponents.poke(components.U)
        t.input.invocations.poke(invocations.U);t.input.outputTopology.poke(0.U);t.input.maxVertices.poke(1.U)
        t.input.outputComponents.poke(1.U);t.input.builtinOutputMask.poke(2.U)
        t.outputSources(0).valid.poke(true.B);t.outputSources(0).bits.poke((if(bad)1 else 0).U)
        d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
        t.input.primitive.vertices(2).position(0).poke(0.U);t.firstSequence.poke(999.U)
      }
      def runInvocation(index:Int):Unit={
        until(d.io.launch.valid.peek().litToBoolean);d.io.launch.bits.commandId.expect(4.U)
        d.io.geometryTask.bits.taskId.expect(19.U);d.io.geometryTask.bits.invocationId.expect(index.U)
        d.io.geometryTask.bits.vertices(2).components(0).expect(77.U)
        d.io.launch.ready.poke(true.B);d.clock.step();d.io.launch.ready.poke(false.B)
        d.io.owner.poke(2.U);d.io.taskIds(1).poke(4.U)
        val e=d.io.event(1);e.bits.operation.poke(0.U);e.bits.vertex.components(0).poke((100+index).U)
        e.bits.vertex.shaderPrimitiveId.poke((200+index).U);e.valid.poke(true.B)
        until(e.ready.peek().litToBoolean);d.clock.step();e.valid.poke(false.B)
        d.io.kernelCompletion.bits.commandId.poke(4.U);d.io.kernelCompletion.bits.success.poke(true.B)
        d.io.kernelCompletion.valid.poke(true.B);until(d.io.kernelCompletion.ready.peek().litToBoolean)
        d.clock.step();d.io.kernelCompletion.valid.poke(false.B);d.io.owner.poke(0.U)
      }
      def result(status:Int,count:Int,produced:Int,next:Int):Unit={
        until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.taskId.expect(19.U);r.inputPrimitiveId.expect(27.U);r.status.expect(status.U)
        r.completedInvocations.expect(count.U);r.producedPrimitives.expect(produced.U);r.nextSequence.expect(next.U)
        val held=r.peek().litValue;for(_<-0 until 2){d.io.task.ready.expect(false.B);d.clock.step();assert(r.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
      submit(2)
      for(i<-0 until 2){runInvocation(i);until(d.io.primitive.valid.peek().litToBoolean)
        val o=d.io.primitive.bits;o.inputPrimitiveId.expect(27.U);o.invocationId.expect(i.U)
        o.primitive.sequence.expect((10+i).U);o.primitive.primitiveId.expect((200+i).U)
        o.primitive.vertices(0).position(0).expect((100+i).U)
        val held=o.peek().litValue;for(_<-0 until 3){d.io.completion.valid.expect(false.B);d.clock.step();assert(o.peek().litValue==held)}
        d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)}
      result(0,2,2,12)
      submit(1);runInvocation(0);until(d.io.primitive.valid.peek().litToBoolean)
      val retained=d.io.primitive.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.completion.valid.expect(false.B);assert(d.io.primitive.bits.peek().litValue==retained);d.clock.step()}
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B);result(2,1,1,11)
      submit(1,bad=true);runInvocation(0);result(1,1,0,11)
      submit(1);until(d.io.launch.valid.peek().litToBoolean);d.io.launch.ready.poke(true.B);d.clock.step();d.io.launch.ready.poke(false.B)
      d.io.owner.poke(2.U);d.io.taskIds(1).poke(4.U);d.io.cancel.poke(true.B);d.io.cancelCu(1).expect(true.B)
      d.clock.step();d.io.cancel.poke(false.B);d.io.cancelCu(1).expect(true.B)
      d.io.kernelCompletion.bits.commandId.poke(4.U);d.io.kernelCompletion.bits.success.poke(false.B)
      d.io.kernelCompletion.valid.poke(true.B);until(d.io.kernelCompletion.ready.peek().litToBoolean)
      d.clock.step();d.io.kernelCompletion.valid.poke(false.B);d.io.owner.poke(0.U);result(2,0,0,10)
      val last=(BigInt(1)<<64)-1;submit(1,first=last);runInvocation(0)
      until(d.io.primitive.valid.peek().litToBoolean);d.io.primitive.bits.primitive.sequence.expect(last.U)
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(1.U)
      d.io.completion.bits.producedPrimitives.expect(1.U);d.io.completion.bits.nextSequence.expect(last.U)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      submit(1,components=64);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.launch.valid.expect(false.B);result(2,0,0,10)
    }
  }
  it should "execute mapped primitives on a real shared CU and retain downstream output" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometryPrimitivePipelineGpuHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("05d00293",16),geometry(38,0,5),
        geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.task.bits;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U);t.firstSequence.poke(20.U)
      val q=t.input;q.taskId.poke(19.U);q.primitive.primitiveId.poke(27.U);q.primitive.kind.poke(2.U)
      q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(1.U)
      q.primitive.vertices(2).position(0).poke("h12345678".U);q.inputComponents.poke(1.U)
      q.invocations.poke(2.U);q.outputTopology.poke(0.U);q.maxVertices.poke(1.U)
      q.outputComponents.poke(1.U);q.builtinOutputMask.poke(2.U);t.outputSources(0).valid.poke(true.B)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      q.primitive.vertices(2).position(0).poke(0.U)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var emitted=0;var held:Option[BigInt]=None
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val o=d.io.primitive.bits;o.inputPrimitiveId.expect(27.U)
          o.invocationId.expect(emitted.U);o.primitive.sequence.expect((20+emitted).U);o.primitive.primitiveId.expect(93.U)
          o.primitive.vertices(0).position(0).expect("h12345678".U);val value=o.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){emitted+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        sys.graphicsKernels.get.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.completedInvocations.expect(2.U);d.io.completion.bits.producedPrimitives.expect(2.U)
      d.io.completion.bits.nextSequence.expect(22.U);assert(emitted==2 && pending.isEmpty)
    }
  }

}
