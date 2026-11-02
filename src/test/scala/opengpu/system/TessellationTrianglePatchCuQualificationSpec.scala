package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationTrianglePatchCuQualificationHarness(config: GpuConfig) extends Module {
  val gpu = Module(new GpuSystem(config, numComputeUnits = 1, commandIdWidth = 4, transactionsPerCu = 4,
    enableGraphicsKernels = true, geometryAvailable = true, instructionCacheSets = 4, instructionCacheWays = 1,
    vectorCacheSets = 4, vectorCacheWays = 1))
  val client = Module(new TessellationTrianglePatchCuEvaluation(config, 4, 1))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io)
    val task = Flipped(Decoupled(new TessellationTrianglePatchCuTask(config, 4)))
    val opened = Decoupled(UInt(2.W)); val seal = Flipped(Decoupled(Bool()))
    val access = Flipped(Decoupled(new TessellationInvocationAccess)); val reply = Decoupled(new TessellationContextReply)
    val finish = Flipped(Decoupled(UInt(5.W))); val control = Decoupled(new TessellationBarrierResult)
    val out = Decoupled(new EvaluatedTrianglePrimitive); val completion = Decoupled(new TessellationTrianglePatchEvaluationCompletion)
    val cancel = Input(Bool()); val owned = Output(Bool())
  })
  io.system <> gpu.io; client.io.task <> io.task; io.opened <> client.io.opened; client.io.sealInput <> io.seal
  client.io.access <> io.access; io.reply <> client.io.reply; client.io.finish <> io.finish; io.control <> client.io.control
  client.io.load.valid := false.B; client.io.load.bits := 0.U.asTypeOf(client.io.load.bits)
  client.io.arrive.valid := false.B; client.io.arrive.bits := 0.U.asTypeOf(client.io.arrive.bits)
  client.io.releaseBarrier.ready := true.B; client.io.inspect.valid := false.B; client.io.inspect.bits := 0.U.asTypeOf(client.io.inspect.bits)
  client.io.cancel := io.cancel; io.out <> client.io.out; io.completion <> client.io.completion
  val g = gpu.io.graphicsKernels.get; g.launch <> client.io.launch; g.geometryTask.get := client.io.geometryTask
  client.io.kernelCompletion <> g.completion; client.io.event <> g.geometryEvent.get
  client.io.owner := g.owned; client.io.taskIds := g.taskIds; g.geometryCancel.get := client.io.cancelCu
  io.owned := g.owned.orR
}

class TessellationTrianglePatchCuQualificationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def geo(fn: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt =
    (BigInt(fn) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | 0x0b
  it should "read controlled patch data while evaluating triangle vertices on a real CU" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new TessellationTrianglePatchCuQualificationHarness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys = d.io.system; sys.graphicsDrained.poke(true.B); sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      sys.graphicsKernels.get.trap.ready.poke(true.B)
      // Read tessCoord.x and selected patch output; emit one point as the TES result.
      val program = Vector(BigInt("00000093",16), BigInt("00000113",16), geo(32,3,1,2), BigInt("00000213",16),
        geo(33,0,4,3), BigInt("00300113",16), geo(32,3,1,2), BigInt("00100213",16), geo(33,0,4,3),
        geo(35), geo(36), BigInt("30500073",16))
      val code = program.zipWithIndex.map { case (w, i) => w << (32 * i) }.sum
      val pending = mutable.Queue.empty[(BigInt, Int)]; var cycle = 0; var outputs = 0; var cancelling = false
      def tick(): Unit = {
        sys.memoryRequest.ready.poke((cycle % 3 != 0).B)
        val r = pending.headOption.filter(_._2 <= cycle); sys.memoryResponse.valid.poke(r.nonEmpty.B)
        r.foreach(x => { sys.memoryResponse.bits.transactionId.poke(x._1.U); sys.memoryResponse.bits.readData.poke(code.U) })
        if (r.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean) {
          sys.memoryRequest.bits.address.expect(0x1000.U); pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue, cycle + 3))
        }
        if (sys.graphicsKernels.get.trap.valid.peek().litToBoolean) assert(cancelling, s"unexpected trap at $cycle")
        val ready = cycle % 4 != 0; d.io.out.ready.poke(ready.B)
        if (ready && d.io.out.valid.peek().litToBoolean) {
          val q = d.io.out.bits; q.taskId.expect(31.U); q.sequence.expect(0.U)
          val xs = Vector(BigInt("3f800000",16), BigInt(0), BigInt(0))
          for (i <- 0 until 3) { q.vertices(i)(0).expect(xs(i).U); q.vertices(i)(1).expect(0x4321.U) }
          outputs += 1
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 200000) { tick(); n += 1 }; assert(done, s"$label stalled")
      }
      val t = d.io.task.bits; t.patch.context.taskId.poke(31.U)
      t.patch.context.layout.inputVertices.poke(1.U); t.patch.context.layout.outputVertices.poke(1.U)
      t.patch.context.layout.patchComponents.poke(1.U); t.commandId.poke(3.U)
      t.evaluationKernel.kernelPc.poke(0x1000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      def write(component: Int, value: BigInt, level: Boolean): Unit = {
        val a = d.io.access.bits.access; d.io.access.bits.invocation.poke(0.U)
        a.output.poke(true.B); a.perPatch.poke(true.B); a.tessLevel.poke(level.B); a.write.poke(true.B)
        a.vertex.poke(0.U); a.component.poke(component.U); a.data.poke(value.U)
        d.io.access.valid.poke(true.B); until("write")(d.io.access.ready.peek().litToBoolean); tick(); d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B); until("write reply")(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.result.status.expect(0.U); tick(); d.io.reply.ready.poke(false.B)
      }
      def start(component: Int): Unit = {
        t.patchInputs(0).component.poke(component.U); d.io.task.valid.poke(true.B)
        until("task")(d.io.task.ready.peek().litToBoolean); tick(); d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B); until("open")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U); tick(); d.io.opened.ready.poke(false.B)
        d.io.seal.valid.poke(true.B); until("seal")(d.io.seal.ready.peek().litToBoolean); tick(); d.io.seal.valid.poke(false.B)
        for (i <- Seq(0, 1, 2, 4)) write(i, BigInt("3f800000",16), level = true)
        write(0, 0x4321, level = false)
        d.io.finish.valid.poke(true.B); until("finish")(d.io.finish.ready.peek().litToBoolean); tick(); d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B); until("control")(d.io.control.valid.peek().litToBoolean)
        d.io.control.bits.status.expect(0.U); tick(); d.io.control.ready.poke(false.B)
      }
      def finish(status: Int, published: Int): Unit = {
        until("completion")(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.published.expect(published.U); assert(pending.isEmpty)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      start(0); finish(0, 1); assert(outputs == 1)
      start(1); finish(2, 0); assert(outputs == 1)
      start(0); until("CU ownership")(d.io.owned.peek().litToBoolean)
      cancelling = true; d.io.cancel.poke(true.B); tick(); d.io.cancel.poke(false.B); finish(4, 0); cancelling = false
      start(0); finish(0, 1); assert(outputs == 2)
    }
  }
}
