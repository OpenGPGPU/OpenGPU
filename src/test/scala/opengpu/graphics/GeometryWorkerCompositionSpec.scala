package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class GeometryWorkerHarness(config:GpuConfig) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryInvocationTask))
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit=Decoupled(new ScalarCommitRequest(config))
    val finish=Flipped(Decoupled(Bool()))
    val primitive=Decoupled(new GeometryOutputPrimitive)
    val completion=Decoupled(new GeometryInvocationResult)
    val cancel=Input(Bool())
    val abort=Output(Bool())
  })
  val controller=Module(new GeometryInvocationController)
  val worker=Module(new GeometryScalarWorker(config))
  controller.io.task<>io.task;controller.io.cancel:=io.cancel
  worker.io.bind.valid:=controller.io.launch.valid;controller.io.launch.ready:=worker.io.bind.ready
  worker.io.bind.bits.invocation:=controller.io.launch.bits;worker.io.bind.bits.warpId:=0.U
  val owner=Reg(new GeometryInvocationLaunch)
  when(worker.io.bind.fire){owner:=worker.io.bind.bits.invocation}
  controller.io.command<>worker.io.event;worker.io.cancel:=io.cancel || controller.io.abort
  worker.io.in<>io.in;io.commit<>worker.io.commit;io.primitive<>controller.io.primitive
  io.completion<>controller.io.completion;io.abort:=controller.io.abort
  worker.io.release.valid:=io.finish.valid && controller.io.workerCompletion.ready
  worker.io.release.bits:=false.B
  controller.io.workerCompletion.valid:=io.finish.valid && worker.io.release.ready
  controller.io.workerCompletion.bits.taskId:=owner.taskId;controller.io.workerCompletion.bits.primitiveId:=owner.primitiveId
  controller.io.workerCompletion.bits.invocationId:=owner.invocationId;controller.io.workerCompletion.bits.success:=io.finish.bits
  io.finish.ready:=worker.io.release.ready && controller.io.workerCompletion.ready
}
class GeometryWorkerCompositionSpec extends AnyFlatSpec {
  it should "drain a held worker event and commit before completing cancellation" in {
    simulate(new GeometryWorkerHarness(GpuConfig(lanes=4,warps=2))){d=>
      d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.commit.ready.poke(false.B);d.io.finish.valid.poke(false.B);d.io.finish.bits.poke(false.B)
      d.io.primitive.ready.poke(false.B);d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      val t=d.io.task.bits;t.taskId.poke(19.U);t.primitiveId.poke(27.U);t.inputVertices.poke(1.U);t.inputComponents.poke(4.U)
      t.invocations.poke(1.U);t.topology.poke(0.U);t.maxVertices.poke(2.U);t.outputComponents.poke(4.U)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      def issue(fn:Int,a:Int=0,b:Int=0):Unit={
        val q=d.io.in.bits;q.decode.instruction.poke(((BigInt(fn)<<25)|(BigInt(5)<<7)|0x0b).U)
        q.decode.warpId.poke(0.U);q.decode.pc.poke(0x1000.U);q.decode.activeMask.poke(1.U)
        q.rs1Data.poke(a.U);q.rs2Data.poke(b.U);d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
        d.clock.step();d.io.in.valid.poke(false.B)
      }
      def retire(success:Boolean):Unit={until(d.io.commit.valid.peek().litToBoolean);d.io.commit.bits.writeRd.expect(success.B)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)}
      issue(33,0,42);retire(true);issue(35);retire(true)
      d.io.primitive.valid.expect(true.B);d.io.primitive.bits.vertices(0).components(0).expect(42.U)
      val held=d.io.primitive.bits.peek().litValue
      issue(33,0,84);retire(true);issue(35)
      for(_<-0 until 3){d.io.commit.valid.expect(false.B);assert(d.io.primitive.bits.peek().litValue==held);d.clock.step()}
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.abort.expect(true.B)
      until(d.io.commit.valid.peek().litToBoolean);d.io.commit.bits.writeRd.expect(false.B)
      d.io.finish.valid.poke(true.B);d.io.finish.ready.expect(false.B)
      d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      until(d.io.finish.ready.peek().litToBoolean);d.clock.step();d.io.finish.valid.poke(false.B)
      for(_<-0 until 3){d.io.completion.valid.expect(false.B);assert(d.io.primitive.bits.peek().litValue==held);d.clock.step()}
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.primitive.ready.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(GeometryInvocationStatus.cancelled)
      d.io.completion.bits.completedInvocations.expect(0.U)
    }
  }
}
