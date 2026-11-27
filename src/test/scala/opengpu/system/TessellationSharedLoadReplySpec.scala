package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual domain evaluator, scheduler and patch store. No CU, shader results, inspection response peer or completion
  * injection is used by this fixture.
  */
private class SharedLoadReplyHarness(domain: Int, dynamic: Boolean) extends Module {
  private val config = GpuConfig(lanes = 16, warps = 8)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationPatchContextTask))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val done = Decoupled(new Bundle { val taskId = UInt(32.W); val status = UInt(3.W) })
    val cancel = Input(Bool()); val busy = Output(Bool()); val launched = Output(Bool())
  })
  private def tie(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tie); case v: Vec[_] => v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case _                                                             => ()
  }
  // Domain tasks deliberately remain their real independent bundle types.
  // The test never seals the patch or launches a control/evaluation kernel.
  domain match {
    case 0 =>
      val d = Module(new TessellationCuIsolineSharedEvaluator(config, 8, 2, evaluationPatchAvailable = dynamic))
      tie(d.io)
      d.io.task.valid := io.task.valid; io.task.ready := d.io.task.ready
      d.io.task.bits.control.patch.context := io.task.bits
      for (i <- 0 until 3) {
        d.io.task.bits.control.kernel.gridSize(i) := 1.U;
        d.io.task.bits.control.kernel.localSize(i) := Mux(i.U === 0.U, io.task.bits.layout.outputVertices, 1.U)
      }
      io.opened <> d.io.opened; d.io.load <> io.load; io.reply <> d.io.reply; d.io.cancel := io.cancel
      io.done.valid := d.io.completion.valid; io.done.bits.taskId := d.io.completion.bits.taskId;
      io.done.bits.status := d.io.completion.bits.status
      d.io.completion.ready := io.done.ready; io.busy := d.io.busy;
      io.launched := d.io.controlKernel.valid || d.io.launch.valid
    case 1 =>
      val d = Module(new TessellationCuTriangleSharedEvaluator(config, 8, 2, evaluationPatchAvailable = dynamic))
      tie(d.io)
      d.io.task.valid := io.task.valid; io.task.ready := d.io.task.ready
      d.io.task.bits.control.patch.context := io.task.bits
      for (i <- 0 until 3) {
        d.io.task.bits.control.kernel.gridSize(i) := 1.U;
        d.io.task.bits.control.kernel.localSize(i) := Mux(i.U === 0.U, io.task.bits.layout.outputVertices, 1.U)
      }
      io.opened <> d.io.opened; d.io.load <> io.load; io.reply <> d.io.reply; d.io.cancel := io.cancel
      io.done.valid := d.io.completion.valid; io.done.bits.taskId := d.io.completion.bits.taskId;
      io.done.bits.status := d.io.completion.bits.status
      d.io.completion.ready := io.done.ready; io.busy := d.io.busy;
      io.launched := d.io.controlKernel.valid || d.io.launch.valid
    case 2 =>
      val d = Module(new TessellationCuQuadSharedEvaluator(config, 8, 2, evaluationPatchAvailable = dynamic))
      tie(d.io)
      d.io.task.valid := io.task.valid; io.task.ready := d.io.task.ready
      d.io.task.bits.control.patch.context := io.task.bits
      for (i <- 0 until 3) {
        d.io.task.bits.control.kernel.gridSize(i) := 1.U;
        d.io.task.bits.control.kernel.localSize(i) := Mux(i.U === 0.U, io.task.bits.layout.outputVertices, 1.U)
      }
      io.opened <> d.io.opened; d.io.load <> io.load; io.reply <> d.io.reply; d.io.cancel := io.cancel
      io.done.valid := d.io.completion.valid; io.done.bits.taskId := d.io.completion.bits.taskId;
      io.done.bits.status := d.io.completion.bits.status
      d.io.completion.ready := io.done.ready; io.busy := d.io.busy;
      io.launched := d.io.controlKernel.valid || d.io.launch.valid
  }
}

class TessellationSharedLoadReplySpec extends AnyFlatSpec {
  behavior of "original VS input acknowledgements with dynamic TES inspection"
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _                                                             => ()
  }
  for (
    (name, domain, dynamic) <- Seq(("isoline", 0, true), ("triangle", 1, true), ("quad", 2, true), ("legacy", 0, false))
  ) {
    it should s"retire actual $name input writes and cancel without inspection credit" in {
      simulate(new SharedLoadReplyHarness(domain, dynamic)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        def tick(): Unit = { d.io.launched.expect(false.B); d.clock.step() }
        def until(label: String, limit: Int = 128)(p: => Boolean): Unit = {
          var n = 0; while (!p && n < limit) { tick(); n += 1 }; assert(p, label)
        }
        val t = d.io.task.bits
        t.layout.inputVertices.poke(32.U); t.layout.inputComponents.poke(64.U)
        t.layout.outputVertices.poke(32.U); t.layout.outputComponents.poke(60.U); t.layout.patchComponents.poke(120.U)
        def begin(id: Int): Unit = {
          t.taskId.poke(id.U); d.io.task.valid.poke(true.B);
          until("task admission")(d.io.task.ready.peek().litToBoolean)
          tick(); d.io.task.valid.poke(false.B); d.io.opened.ready.poke(true.B)
          until("full-capacity patch initialization", 8192)(d.io.opened.valid.peek().litToBoolean)
          d.io.opened.bits.expect(0.U); tick(); d.io.opened.ready.poke(false.B)
        }
        def load(vertex: Int, component: Int): Unit = {
          val a = d.io.load.bits; a.write.poke(true.B); a.vertex.poke(vertex.U); a.component.poke(component.U);
          a.data.poke("h5a17c0de".U)
          d.io.load.valid.poke(true.B); until("actual input write accepted")(d.io.load.ready.peek().litToBoolean)
          tick(); d.io.load.valid.poke(false.B)
        }
        def heldReply(status: Int, cancel: Boolean): Unit = {
          until("load response stalled after its real input write")(d.io.reply.valid.peek().litToBoolean)
          d.io.reply.bits.source.expect(0.U); d.io.reply.bits.invocation.expect(0.U);
          d.io.reply.bits.result.status.expect(status.U)
          def snapshot = Seq(
            d.io.reply.bits.source,
            d.io.reply.bits.invocation,
            d.io.reply.bits.result.status,
            d.io.reply.bits.result.data
          ).map(_.peek().litValue)
          val before = snapshot
          d.io.cancel.poke(cancel.B)
          for (_ <- 0 until 5) {
            tick(); d.io.reply.valid.expect(true.B); assert(snapshot == before, "held load acknowledgement changed")
          }
          d.io.reply.ready.poke(true.B); tick(); d.io.reply.ready.poke(false.B)
        }
        def retire(id: Int): Unit = {
          until("canceled patch did not drain after its real load reply", 512)(d.io.done.valid.peek().litToBoolean)
          d.io.done.bits.taskId.expect(id.U); d.io.done.bits.status.expect(4.U)
          d.io.cancel.poke(false.B)
          for (_ <- 0 until 3) { tick(); d.io.done.valid.expect(true.B); d.io.done.bits.status.expect(4.U) }
          d.io.done.ready.poke(true.B); tick(); d.io.done.ready.poke(false.B)
          until("patch reuse remained busy")(!d.io.busy.peek().litToBoolean)
        }
        begin(71); load(0, 0); heldReply(0, false); load(31, 63); heldReply(0, true); retire(71)
        // Reuse the actual store without reset, including a checked OOB write.
        begin(72); load(32, 0); heldReply(1, false); load(31, 63); heldReply(0, true); retire(72)
      }
    }
  }
}
