package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class IsolineGeometryCuQualificationHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  val executor=Module(new IsolineGeometryCuExecutor(config,4,1))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io)
    val task=Flipped(Decoupled(new IsolineGeometryCuTask(config,4)))
    val response=Decoupled(new IsolineEvaluationResponse);val cancel=Input(Bool())
  })
  io.system<>gpu.io;executor.io.task<>io.task;io.response<>executor.io.response;executor.io.cancel:=io.cancel
  val g=gpu.io.graphicsKernels.get;g.launch<>executor.io.launch;g.geometryTask.get:=executor.io.geometryTask
  executor.io.kernelCompletion<>g.completion;executor.io.event<>g.geometryEvent.get
  executor.io.owner:=g.owned;executor.io.taskIds:=g.taskIds;g.geometryCancel.get:=executor.io.cancelCu
}

class IsolineGeometryCuQualificationSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "execute tessellation coordinate input and output on a real shared CU" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new IsolineGeometryCuQualificationHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      val program=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
        BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
        BigInt("00100213",16),geometry(33,0,4,3),BigInt("00200113",16),geometry(32,3,1,2),
        BigInt("00200213",16),geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
      val code=program.zipWithIndex.map{case(word,i)=>word<<(32*i)}.sum
      val t=d.io.task.bits;t.commandId.poke(3.U);t.kernel.kernelPc.poke(0x1000.U);t.outputComponents.poke(3.U)
      t.evaluation.taskId.poke(19.U);t.evaluation.sequence.poke(27.U);t.evaluation.vertex.poke(1.U)
      t.evaluation.tessCoord(0).poke("h3f000000".U);t.evaluation.tessCoord(1).poke("h3e800000".U)
      t.evaluation.tessCoord(2).poke(0.U);d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B)
      d.clock.step();d.io.task.valid.poke(false.B)
      val pending=mutable.Queue.empty[(BigInt,Int)];var cycle=0
      while(!d.io.response.valid.peek().litToBoolean && cycle<30000){
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val reply=pending.headOption.filter(_._2<=cycle)
        sys.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(code.U)})
        if(reply.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          sys.memoryRequest.bits.address.expect(0x1000.U);pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,cycle+3))}
        sys.graphicsKernels.get.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      assert(cycle<30000 && pending.isEmpty);d.io.response.valid.expect(true.B)
      val r=d.io.response.bits;r.taskId.expect(19.U);r.sequence.expect(27.U);r.vertex.expect(1.U);r.failed.expect(false.B)
      r.components(0).expect("h3f000000".U);r.components(1).expect("h3e800000".U);r.components(2).expect(0.U)
      val held=r.peek().litValue;d.clock.step(3);assert(r.peek().litValue==held)
      d.io.response.ready.poke(true.B);d.clock.step()
    }
  }
}
