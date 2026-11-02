package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class CuTriangleControlEvaluationHarness(config: GpuConfig) extends Module {
  val compute = Module(new GpuComputeUnit(config, finishOnTrap = true, textureAvailable = false,
    nativeResourcesAvailable = false, tessellationAvailable = true, geometryAvailable = true,
    instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1))
  val client = Module(new TessellationCuTriangleSharedEvaluator(config, 4, 1))
  val io = IO(new Bundle {
    val cu = chiselTypeOf(compute.io); val task = Flipped(Decoupled(new TessellationCuTriangleSharedTask(config, 4)))
    val opened = Decoupled(UInt(2.W)); val seal = Flipped(Decoupled(Bool()))
    val out = Decoupled(new EvaluatedTrianglePrimitive); val completion = Decoupled(new TessellationCuTriangleCompletion)
    val cancel = Input(Bool()); val evaluationOwned = Output(Bool())
  })
  io.cu <> compute.io; client.io.task <> io.task; io.opened <> client.io.opened; client.io.seal <> io.seal
  client.io.load.valid := false.B; client.io.load.bits := 0.U.asTypeOf(client.io.load.bits)
  client.io.inspect.valid := false.B; client.io.inspect.bits := 0.U.asTypeOf(client.io.inspect.bits); client.io.reply.ready := true.B
  client.io.cancel := io.cancel; io.out <> client.io.out; io.completion <> client.io.completion
  val owned = RegInit(false.B); val geometry = RegInit(false.B); val tag = Reg(UInt(4.W))
  io.evaluationOwned := owned && geometry
  val selectControl = client.io.controlKernel.valid
  compute.io.kernel.valid := !owned && (client.io.controlKernel.valid || client.io.launch.valid)
  compute.io.kernel.bits := Mux(selectControl, client.io.controlKernel.bits, client.io.launch.bits.launch)
  client.io.controlKernel.ready := !owned && compute.io.kernel.ready
  client.io.launch.ready := !owned && !selectControl && compute.io.kernel.ready
  when(compute.io.kernel.fire) { owned := true.B; geometry := !selectControl; tag := client.io.launch.bits.commandId }
  client.io.controlCompletion.valid := owned && !geometry && compute.io.completion.valid
  client.io.controlCompletion.bits := compute.io.completion.bits
  client.io.kernelCompletion.valid := owned && geometry && compute.io.completion.valid
  client.io.kernelCompletion.bits.commandId := tag; client.io.kernelCompletion.bits.success := compute.io.completion.bits.success
  compute.io.completion.ready := Mux(geometry, client.io.kernelCompletion.ready, client.io.controlCompletion.ready)
  when(compute.io.completion.fire) { owned := false.B }
  compute.io.tessellationEnabled.get := selectControl; compute.io.tessellationBases.get := client.io.bases
  compute.io.tessellationCancel.get := client.io.controlCancel; compute.io.tessellationTerminal.get := client.io.controlTerminal
  client.io.access <> compute.io.tessellationRequest.get; compute.io.tessellationResult.get <> client.io.accessResult
  client.io.park <> compute.io.tessellationPark.get; compute.io.tessellationResume.get <> client.io.resume
  client.io.finish <> compute.io.tessellationFinish.get
  compute.io.geometryEnabled.get := !selectControl; compute.io.geometryTask.get := client.io.geometryTask.bits
  compute.io.geometryCancel.get := client.io.cancelCu(0); client.io.event(0) <> compute.io.geometryEvent.get
  client.io.owner := (owned && geometry).asUInt; client.io.taskIds(0) := tag
}

class TessellationCuTriangleSharedEvaluatorSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn: Int, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|0x2b
  private def geo(fn: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt =
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  it should "evaluate real triangle control outputs on the same CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new CuTriangleControlEvaluationHarness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val c = d.io.cu; c.l1InvalidateDone.ready.poke(true.B); c.trap.ready.poke(true.B)
      // Write the three triangle outer levels and first inner level and one per-patch value to 1.0.
      val control = Vector(BigInt("0104f057",16), BigInt("3f800537",16), vector(23,4,3,10),
        vector(23,3,2,0), patch(20,vs1=3)) ++
        Vector(7,15,23,39,3).flatMap { address => Vector(
          BigInt((address << 20) | (5 << 7) | 0x13), vector(23,4,4,5), patch(22,vs1=2,vs2=4)) } ++
        Vector(BigInt("0000000b",16), BigInt("30500073",16))
      // Read tessCoord.x and the selected per-patch output into components 0 and 1.
      val eval = Vector(BigInt("00000093",16), BigInt("00000113",16), geo(32,3,1,2), BigInt("00000213",16),
        geo(33,0,4,3), BigInt("00300113",16), geo(32,3,1,2), BigInt("00100213",16), geo(33,0,4,3),
        geo(35), geo(36), BigInt("30500073",16))
      def line(p: Vector[BigInt]): BigInt = p.zipWithIndex.map { case (w,i) => w << (32*i) }.sum
      val pending = mutable.Queue.empty[(BigInt,BigInt)]; var cycle = 0; var outputs = 0; var cancelling = false
      def tick(): Unit = {
        c.memoryRequest.ready.poke((cycle%3 != 0).B); c.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach { case (id,data) => c.memoryResponse.bits.transactionId.poke(id.U); c.memoryResponse.bits.readData.poke(data.U) }
        if (pending.nonEmpty && c.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (c.memoryRequest.valid.peek().litToBoolean && c.memoryRequest.ready.peek().litToBoolean) {
          val addr = c.memoryRequest.bits.address.peek().litValue; assert(addr == 0x1000 || addr == 0x1040 || addr == 0x2000, s"fetch $addr")
          c.memoryRequest.bits.isWrite.expect(false.B); pending.enqueue((c.memoryRequest.bits.transactionId.peek().litValue, line(if (addr == 0x1000) control.take(16) else if (addr == 0x1040) control.drop(16) else eval)))
        }
        if (c.trap.valid.peek().litToBoolean) assert(cancelling, s"unexpected trap at $cycle")
        d.io.out.ready.poke((cycle%4 != 0).B)
        if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
          val xs = Seq(BigInt("3f800000",16), BigInt(0), BigInt(0))
          for (i <- 0 until 3) { d.io.out.bits.vertices(i)(0).expect(xs(i).U); d.io.out.bits.vertices(i)(1).expect("h3f800000".U) }; outputs += 1
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = { var n = 0; while (!p && n < 50000) { tick(); n += 1 }; assert(p, s"$label timeout") }
      val t = d.io.task.bits; val ctl = t.control; ctl.patch.context.taskId.poke(31.U)
      ctl.patch.context.layout.inputVertices.poke(1.U); ctl.patch.context.layout.outputVertices.poke(4.U)
      ctl.patch.context.layout.patchComponents.poke(1.U); ctl.kernel.kernelPc.poke(0x1000.U)
      for (i <- 0 until 3) { ctl.kernel.gridSize(i).poke(1.U); ctl.kernel.localSize(i).poke((if(i==0) 4 else 1).U) }
      ctl.bases(0).poke(0.U); ctl.bases(1).poke(4.U)
      t.commandId.poke(3.U); t.evaluationKernel.kernelPc.poke(0x2000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      def run(component: Int, status: Int, expectedOutputs: Int): Unit = {
        t.patchInputs(0).component.poke(component.U)
        d.io.task.valid.poke(true.B); until("task")(d.io.task.ready.peek().litToBoolean); tick(); d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B); until("open")(d.io.opened.valid.peek().litToBoolean); d.io.opened.bits.expect(0.U)
        tick(); d.io.opened.ready.poke(false.B); d.io.seal.valid.poke(true.B); until("seal")(d.io.seal.ready.peek().litToBoolean)
        tick(); d.io.seal.valid.poke(false.B); until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.control.barriers.expect(1.U)
        d.io.completion.bits.published.expect((if (status == 0) 1 else 0).U)
        assert(outputs == expectedOutputs && pending.isEmpty)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      run(0, 0, 1); run(1, 3, 1)
      t.patchInputs(0).component.poke(0.U)
      d.io.task.valid.poke(true.B); until("cancel task")(d.io.task.ready.peek().litToBoolean); tick(); d.io.task.valid.poke(false.B)
      d.io.opened.ready.poke(true.B); until("cancel open")(d.io.opened.valid.peek().litToBoolean); tick(); d.io.opened.ready.poke(false.B)
      d.io.seal.valid.poke(true.B); until("cancel seal")(d.io.seal.ready.peek().litToBoolean); tick(); d.io.seal.valid.poke(false.B)
      until("evaluation ownership")(d.io.evaluationOwned.peek().litToBoolean)
      cancelling = true; d.io.cancel.poke(true.B); tick(); d.io.cancel.poke(false.B)
      until("cancel completion")(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(4.U)
      d.io.evaluationOwned.expect(false.B); assert(outputs == 1 && pending.isEmpty)
      d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B); cancelling = false
      run(0, 0, 2)
    }
  }
}
