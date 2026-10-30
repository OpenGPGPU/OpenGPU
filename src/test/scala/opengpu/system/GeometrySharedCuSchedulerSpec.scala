package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometrySharedSystemHarness(config: GpuConfig, mappedInput: Boolean = false) extends Module {
  val gpu = Module(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val scheduler = Module(new GeometrySharedCuScheduler(config,4,2))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io)
    val task = Flipped(Decoupled(new GeometrySharedTask(config,4)))
    val primitive = Decoupled(new GeometryOutputPrimitive)
    val completion = Decoupled(new GeometryInvocationResult)
    val cancel = Input(Bool())
    val mapped = if (mappedInput) Some(Flipped(Decoupled(new GeometryPrimitiveInputRequest(VertexShaderStageConfig())))) else None
    val mappingFault = if (mappedInput) Some(Decoupled(new GeometryPrimitiveInputFault)) else None
  })
  io.system <> gpu.io
  if (mappedInput) {
    val adapter = Module(new GeometryPrimitiveInput())
    adapter.io.in <> io.mapped.get; io.mappingFault.get <> adapter.io.fault
    scheduler.io.task.valid := adapter.io.task.valid; adapter.io.task.ready := scheduler.io.task.ready
    scheduler.io.task.bits.geometry := adapter.io.task.bits
    scheduler.io.task.bits.commandId := 3.U
    scheduler.io.task.bits.kernel := 0.U.asTypeOf(scheduler.io.task.bits.kernel)
    scheduler.io.task.bits.kernel.kernelPc := 0x1000.U
    io.task.ready := false.B
  } else scheduler.io.task <> io.task
  io.primitive <> scheduler.io.primitive
  io.completion <> scheduler.io.completion; scheduler.io.cancel := io.cancel
  val g = gpu.io.graphicsKernels.get
  g.launch <> scheduler.io.launch
  g.geometryTask.get := scheduler.io.geometryTask
  scheduler.io.kernelCompletion <> g.completion
  scheduler.io.event <> g.geometryEvent.get
  scheduler.io.owner := g.owned; scheduler.io.taskIds := g.taskIds
  g.geometryCancel.get := scheduler.io.cancelCu
}

class GeometrySharedCuSchedulerSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0): BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "run ordered geometry invocations through the shared system" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometrySharedSystemHarness(config)){ d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys=d.io.system; sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00500093",16),BigInt("03f00113",16),geometry(32,3,1,2),geometry(37,6),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100213",16),geometry(33,0,4,6),
        geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.task.bits;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U)
      val g=t.geometry;g.taskId.poke(19.U);g.primitiveId.poke(27.U);g.inputVertices.poke(6.U);g.inputComponents.poke(64.U)
      g.invocations.poke(4.U);g.topology.poke(0.U);g.maxVertices.poke(1.U);g.outputComponents.poke(2.U)
      g.vertices(5).components(63).poke("h12345678".U);d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B)
      d.clock.step();d.io.task.valid.poke(false.B);g.vertices(5).components(63).poke(0.U)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var emitted=0;var held:Option[BigInt]=None
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val p=d.io.primitive.bits;p.taskId.expect(19.U);p.primitiveId.expect(27.U)
          p.invocationId.expect(emitted.U);p.vertexCount.expect(1.U);p.vertices(0).components(0).expect("h12345678".U)
          p.vertices(0).components(1).expect(emitted.U);val value=p.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){emitted+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        sys.graphicsKernels.get.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.completedInvocations.expect(4.U);assert(emitted==4 && pending.isEmpty)
    }
  }
  it should "isolate foreign traffic and drain a cancelled owner" in {
    simulate(new GeometrySharedCuScheduler(GpuConfig(lanes=4,warps=2),4,2)){ d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val t=d.io.task.bits;t.commandId.poke(7.U);t.geometry.taskId.poke(19.U);t.geometry.primitiveId.poke(27.U)
      t.geometry.inputVertices.poke(1.U);t.geometry.inputComponents.poke(1.U);t.geometry.invocations.poke(1.U)
      t.geometry.maxVertices.poke(1.U);t.geometry.outputComponents.poke(1.U)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      d.io.launch.ready.poke(true.B)
      var cycles=0;while(!d.io.launch.valid.peek().litToBoolean && cycles<100){d.clock.step();cycles+=1}
      d.io.launch.valid.expect(true.B);d.io.launch.bits.commandId.expect(7.U)
      for(i<-0 until 3){d.io.launch.bits.launch.gridSize(i).expect(1.U);d.io.launch.bits.launch.localSize(i).expect(1.U)}
      d.clock.step();d.io.owner.poke(3.U);d.io.taskIds(0).poke(7.U);d.io.taskIds(1).poke(8.U)
      d.io.event(1).valid.poke(true.B);d.io.kernelCompletion.valid.poke(true.B);d.io.kernelCompletion.bits.commandId.poke(8.U)
      d.io.event(1).ready.expect(false.B);d.io.kernelCompletion.ready.expect(false.B)
      d.io.cancel.poke(true.B);d.io.cancelCu(0).expect(true.B);d.io.cancelCu(1).expect(false.B)
      d.clock.step();d.io.cancel.poke(false.B)
      d.io.cancelCu(0).expect(true.B);d.io.event(0).valid.poke(true.B);d.io.event(0).ready.expect(true.B)
      d.io.kernelCompletion.bits.commandId.poke(7.U);d.io.kernelCompletion.bits.success.poke(false.B)
      d.io.kernelCompletion.ready.expect(true.B);d.clock.step();d.io.event(0).valid.poke(false.B)
      d.io.kernelCompletion.valid.poke(false.B)
      cycles=0;while(!d.io.completion.valid.peek().litToBoolean && cycles<100){d.clock.step();cycles+=1}
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(GeometryInvocationStatus.cancelled)
      d.io.completion.bits.completedInvocations.expect(0.U)
    }
  }

  it should "execute a mapped assembled triangle through a shared compute unit" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new GeometrySharedSystemHarness(config,mappedInput=true)){ d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B);d.io.mappingFault.get.ready.poke(true.B)
      val program=Vector(BigInt("00200093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("05d00293",16),geometry(38,0,5),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val input=d.io.mapped.get;val q=input.bits;q.taskId.poke(19.U);q.primitive.primitiveId.poke(27.U)
      q.primitive.kind.poke(2.U);q.primitive.vertexCount.poke(3.U);q.primitive.varyingSlots.poke(16.U)
      q.primitive.vertices(2).varyings(15)(3).poke("h12345678".U);q.componentSources(0).poke(67.U)
      q.inputComponents.poke(1.U);q.invocations.poke(2.U);q.outputTopology.poke(0.U)
      q.maxVertices.poke(1.U);q.outputComponents.poke(1.U);q.builtinOutputMask.poke(2.U)
      input.valid.poke(true.B);input.ready.expect(true.B);d.clock.step();input.valid.poke(false.B)
      q.primitive.vertices(2).varyings(15)(3).poke(0.U)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var emitted=0;var held:Option[BigInt]=None
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val p=d.io.primitive.bits;p.taskId.expect(19.U);p.primitiveId.expect(27.U)
          p.invocationId.expect(emitted.U);p.vertices(0).components(0).expect("h12345678".U)
          p.vertices(0).shaderPrimitiveId.expect(93.U)
          val value=p.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){emitted+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        d.io.mappingFault.get.valid.expect(false.B);sys.graphicsKernels.get.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.completedInvocations.expect(2.U);assert(emitted==2 && pending.isEmpty)
    }
  }

}
