package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationSystemHarness(config: GpuConfig) extends Module {
  val gpu = Module(new GpuSystem(config, numComputeUnits = 1, commandIdWidth = 4, transactionsPerCu = 4,
    enableGraphicsKernels = true, geometryAvailable = true, tessellationAvailable = true,
    instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1))
  val client = Module(new TessellationCuIsolineSystemClient(config, 4, 1))
  val io = IO(new Bundle {
    val system = chiselTypeOf(gpu.io); val task = Flipped(Decoupled(new TessellationCuIsolineSystemTask(config, 4)))
    val opened = Decoupled(UInt(2.W)); val seal = Flipped(Decoupled(Bool()))
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationCuIsolineCompletion)
    val cancel = Input(Bool()); val trap = Decoupled(new TaggedGraphicsTrap(config, 4, 1))
    val evaluationOwned = Output(Bool())
  })
  private def tie(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tie); case v: Vec[_] => v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case _ => ()
  }
  tie(client.io); io.system <> gpu.io; client.io.shared <> gpu.io.graphicsKernels.get
  client.io.task <> io.task; io.opened <> client.io.opened; client.io.seal <> io.seal
  io.out <> client.io.out; io.completion <> client.io.completion; client.io.cancel := io.cancel
  io.trap <> client.io.trap
  val evaluation = RegInit(false.B)
  when(client.io.shared.launch.fire) { evaluation := client.io.shared.geometryTask.get.valid }
  when(client.io.shared.completion.fire) { evaluation := false.B }
  io.evaluationOwned := evaluation && client.io.shared.owned.orR
}

class TessellationCuIsolineSystemClientSpec extends AnyFlatSpec {
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
  it should "run control and evaluation through the actual shared system" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new TessellationSystemHarness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val sys = d.io.system; sys.graphicsDrained.poke(true.B); sys.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.ready.poke(true.B)
      val control = Vector(BigInt("0104f057",16), BigInt("3f800537",16), vector(23,4,3,10), vector(23,3,2,0),
        vector(23,3,4,7), patch(20,vs1=3), patch(22,vs1=2,vs2=4), vector(23,3,4,15), patch(22,vs1=2,vs2=4),
        vector(23,3,4,3), patch(22,vs1=2,vs2=4), BigInt("0000000b",16), BigInt("30500073",16))
      val eval = Vector(BigInt("00000093",16), BigInt("00000113",16), geo(32,3,1,2), BigInt("00000213",16),
        geo(33,0,4,3), BigInt("00300113",16), geo(32,3,1,2), BigInt("00100213",16), geo(33,0,4,3),
        geo(35), geo(36), BigInt("30500073",16))
      def line(p: Vector[BigInt]): BigInt = p.zipWithIndex.map { case (w,i) => w << (32*i) }.sum
      val pending = mutable.Queue.empty[(BigInt, BigInt, Int)]; var cycle = 0; var outputs = 0; var cancelling = false
      def tick(): Unit = {
        sys.memoryRequest.ready.poke((cycle%3 != 0).B)
        val response = pending.headOption.filter(_._3 <= cycle); sys.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach { case (id,data,_) => sys.memoryResponse.bits.transactionId.poke(id.U); sys.memoryResponse.bits.readData.poke(data.U) }
        if (response.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean) {
          val addr = sys.memoryRequest.bits.address.peek().litValue; assert(addr == 0x1000 || addr == 0x2000, s"fetch $addr")
          pending.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue, line(if (addr == 0x1000) control else eval), cycle+3))
        }
        if (d.io.trap.valid.peek().litToBoolean) assert(cancelling, s"unexpected trap at $cycle")
        d.io.out.ready.poke((cycle%4 != 0).B)
        if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
          d.io.out.bits.vertices(0)(0).expect(0.U); d.io.out.bits.vertices(1)(0).expect("h3f800000".U)
          d.io.out.bits.vertices(0)(1).expect("h3f800000".U); d.io.out.bits.vertices(1)(1).expect("h3f800000".U); outputs += 1
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = { var n = 0; while (!p && n < 50000) { tick(); n += 1 }; assert(p, s"$label timeout at $cycle") }
      val t = d.io.task.bits.patch; val ctl = t.control; ctl.patch.context.taskId.poke(31.U)
      ctl.patch.context.layout.inputVertices.poke(1.U); ctl.patch.context.layout.outputVertices.poke(4.U)
      ctl.patch.context.layout.patchComponents.poke(1.U); ctl.kernel.kernelPc.poke(0x1000.U)
      for (i <- 0 until 3) { ctl.kernel.gridSize(i).poke(1.U); ctl.kernel.localSize(i).poke((if(i==0) 4 else 1).U) }
      ctl.bases(0).poke(0.U); ctl.bases(1).poke(4.U)
      t.commandId.poke(3.U); t.evaluationKernel.kernelPc.poke(0x2000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      def start(): Unit = {
        d.io.task.valid.poke(true.B); until("task")(d.io.task.ready.peek().litToBoolean); tick(); d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B); until("open")(d.io.opened.valid.peek().litToBoolean); d.io.opened.bits.expect(0.U)
        tick(); d.io.opened.ready.poke(false.B); d.io.seal.valid.poke(true.B); until("seal")(d.io.seal.ready.peek().litToBoolean)
        tick(); d.io.seal.valid.poke(false.B)
      }
      def done(status: Int, expectedOutputs: Int): Unit = {
        until("completion")(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.control.barriers.expect(1.U); d.io.completion.bits.published.expect((if (status == 0) 1 else 0).U)
        assert(outputs == expectedOutputs && pending.isEmpty); d.io.evaluationOwned.expect(false.B)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      start(); done(0, 1)
      start(); until("evaluation ownership")(d.io.evaluationOwned.peek().litToBoolean)
      cancelling = true; d.io.cancel.poke(true.B); tick(); d.io.cancel.poke(false.B)
      done(4, 1); cancelling = false
      start(); done(0, 2)
    }
  }
}
