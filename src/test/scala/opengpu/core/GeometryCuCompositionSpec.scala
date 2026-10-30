package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.core.trap.CoreTrapEvent
import opengpu.graphics.{GeometryInvocationController, GeometryInvocationLaunch, GeometryInvocationResult, GeometryInvocationTask, GeometryOutputPrimitive}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GeometryCuHarness(config:GpuConfig) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryInvocationTask))
    val primitive=Decoupled(new GeometryOutputPrimitive)
    val completion=Decoupled(new GeometryInvocationResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val trap=Decoupled(new CoreTrapEvent(config))
    val cancel=Input(Bool())
  })
  val controller=Module(new GeometryInvocationController)
  val cu=Module(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,geometryAvailable=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  controller.io.task<>io.task;controller.io.cancel:=io.cancel;io.primitive<>controller.io.primitive;io.completion<>controller.io.completion
  cu.io.kernel.valid:=controller.io.launch.valid;controller.io.launch.ready:=cu.io.kernel.ready
  cu.io.kernel.bits:=0.U.asTypeOf(cu.io.kernel.bits);cu.io.kernel.bits.kernelPc:=0x1000.U
  for(i<-0 until 3){cu.io.kernel.bits.gridSize(i):=1.U;cu.io.kernel.bits.localSize(i):=1.U}
  cu.io.geometryEnabled.get:=true.B;cu.io.geometryTask.get:=controller.io.launch.bits
  cu.io.geometryCancel.get:=io.cancel || controller.io.abort
  controller.io.command<>cu.io.geometryEvent.get
  val owner=Reg(new GeometryInvocationLaunch)
  when(controller.io.launch.fire){owner:=controller.io.launch.bits}
  controller.io.workerCompletion.valid:=cu.io.completion.valid
  controller.io.workerCompletion.bits.taskId:=owner.taskId;controller.io.workerCompletion.bits.primitiveId:=owner.primitiveId
  controller.io.workerCompletion.bits.invocationId:=owner.invocationId;controller.io.workerCompletion.bits.success:=cu.io.completion.bits.success
  cu.io.completion.ready:=controller.io.workerCompletion.ready
  io.memoryRequest<>cu.io.memoryRequest;cu.io.memoryResponse<>io.memoryResponse;io.trap<>cu.io.trap
  cu.io.invalidateInstructionCache:=false.B;cu.io.instructionSatp:=0.U;cu.io.vectorSatp:=0.U
  cu.io.instructionTlbFlush.valid:=false.B;cu.io.instructionTlbFlush.bits:=0.U.asTypeOf(cu.io.instructionTlbFlush.bits)
  cu.io.vectorTlbFlush.valid:=false.B;cu.io.vectorTlbFlush.bits:=0.U.asTypeOf(cu.io.vectorTlbFlush.bits)
  cu.io.simtBranch.valid:=false.B;cu.io.simtBranch.bits:=0.U.asTypeOf(cu.io.simtBranch.bits)
  cu.io.l1Invalidate.valid:=false.B;cu.io.l1Invalidate.bits:=0.U.asTypeOf(cu.io.l1Invalidate.bits);cu.io.l1InvalidateDone.ready:=true.B
  cu.io.globalAtomicRequest.ready:=true.B;cu.io.globalAtomicResponse.valid:=false.B;cu.io.globalAtomicResponse.bits:=0.U.asTypeOf(cu.io.globalAtomicResponse.bits)
  cu.io.fpu.ready:=true.B;cu.io.vector.ready:=true.B;cu.io.memory.ready:=true.B
  cu.io.texSample.ready:=true.B;cu.io.texWriteback.valid:=false.B;cu.io.texWriteback.bits:=0.U.asTypeOf(cu.io.texWriteback.bits)
  cu.io.vectorTexSample.ready:=true.B;cu.io.vectorTexWriteback.valid:=false.B;cu.io.vectorTexWriteback.bits:=0.U.asTypeOf(cu.io.vectorTexWriteback.bits)
}
class GeometryCuCompositionSpec extends AnyFlatSpec {
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "execute 32 ordered geometry invocations on a real compute unit" in {
    val config=GpuConfig(lanes=4,warps=2)
    simulate(new GeometryCuHarness(config)){d=>
      d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.primitive.ready.poke(false.B);d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.trap.ready.poke(true.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val program=Vector(BigInt("00500093",16),BigInt("03f00113",16),geometry(32,3,1,2),geometry(37,6),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100213",16),geometry(33,0,4,6),
        geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.task.bits;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.inputVertices.poke(6.U);t.inputComponents.poke(64.U)
      t.invocations.poke(32.U);t.topology.poke(0.U);t.maxVertices.poke(1.U);t.outputComponents.poke(2.U)
      t.vertices(5).components(63).poke("h12345678".U);d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B)
      d.clock.step();d.io.task.valid.poke(false.B);t.vertices(5).components(63).poke(0.U)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0;var emitted=0;var held:Option[BigInt]=None
      while(!d.io.completion.valid.peek().litToBoolean && cycle<30000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B);reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r._1.U);d.io.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          d.io.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((d.io.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        d.io.primitive.ready.poke((cycle%7<4).B)
        if(d.io.primitive.valid.peek().litToBoolean){val p=d.io.primitive.bits;p.taskId.expect(19.U);p.primitiveId.expect(27.U)
          p.invocationId.expect(emitted.U);p.vertexCount.expect(1.U);p.vertices(0).components(0).expect("h12345678".U)
          p.vertices(0).components(1).expect(emitted.U);val value=p.peek().litValue;held.foreach(v=>assert(v==value))
          if(d.io.primitive.ready.peek().litToBoolean){emitted+=1;held=None}else held=Some(value)
        }else assert(held.isEmpty)
        d.io.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(0.U)
      d.io.completion.bits.completedInvocations.expect(32.U);assert(emitted==32 && pending.isEmpty)
    }
  }
}
