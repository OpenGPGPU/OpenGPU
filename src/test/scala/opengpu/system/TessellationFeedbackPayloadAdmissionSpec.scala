package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import opengpu.graphics.{ShaderPayloadConfig, TileJobDescriptorRequest}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercises the ready/valid composition used by the three-domain host. */
private class TessellationFeedbackPayloadAdmissionHarness extends Module {
  private val p = ShaderPayloadConfig(capacityBytes=256,maxPayloadBytes=256,owners=2,ports=4)
  val io = IO(new Bundle {
    val submit = Flipped(Vec(3, Decoupled(new TileJobDescriptorRequest(4))))
    val selected = Input(UInt(2.W)); val childReady = Input(Vec(3, Bool()))
    val childFire = Output(Vec(3, Bool())); val release = Input(Bool())
    val releaseReady = Output(Bool()); val active = Output(Bool()); val busy = Output(Bool())
  })
  private val admission = Module(new TessellationThreeDomainFeedbackAdmission(4))
  private val owner = Module(new TessellationThreeDomainPayloadOwner(p))
  for (i <- 0 until 3) {
    admission.io.submit(i) <> io.submit(i)
    admission.io.render(i).ready := io.childReady(i) && owner.io.acquire.ready
    io.childFire(i) := admission.io.render(i).fire
  }
  admission.io.selected := io.selected; admission.io.poisoned := false.B; admission.io.cancel := false.B
  admission.io.failed.ready := true.B; admission.io.switch.ready := false.B
  admission.io.switchReply.valid := false.B; admission.io.switchReply.bits := 0.U
  owner.io.acquire.bits := admission.io.offerDomain
  owner.io.acquire.valid := (0 until 3).map(i => admission.io.render(i).valid && io.childReady(i)).reduce(_ || _)
  owner.io.end.valid := io.release; owner.io.end.bits := true.B
  io.releaseReady := owner.io.end.ready; io.active := owner.io.active; io.busy := admission.io.busy
  for (domain <- 0 until 3; i <- 0 until p.owners) {
    val client = owner.io.clients(domain)(i)
    client.control.valid := false.B; client.control.bits := 0.U.asTypeOf(client.control.bits)
    client.request.valid := false.B; client.request.bits := 0.U.asTypeOf(client.request.bits)
    client.controlResponse.ready := false.B; client.response.ready := false.B
  }
  for (i <- 0 until p.owners) {
    val pool = owner.io.pool(i)
    pool.control.ready := true.B; pool.request.ready := true.B
    pool.controlResponse.valid := false.B; pool.controlResponse.bits := 0.U.asTypeOf(pool.controlResponse.bits)
    pool.response.valid := false.B; pool.response.bits := 0.U.asTypeOf(pool.response.bits)
  }
}

class TessellationFeedbackPayloadAdmissionSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "acquire and retire each domain without a ready-valid cycle" in {
    simulate(new TessellationFeedbackPayloadAdmissionHarness) { d =>
      zero(d.io)
      d.reset.poke(true.B); d.io.selected.poke(0.U); d.io.release.poke(false.B)
      for (i <- 0 until 3) {
        d.io.submit(i).valid.poke(false.B)
        d.io.childReady(i).poke(false.B)
      }
      d.clock.step(); d.reset.poke(false.B)
      for (domain <- 0 until 3) {
        d.io.selected.poke(domain.U); d.io.submit(domain).valid.poke(true.B)
        d.io.submit(domain).ready.expect(true.B); d.clock.step(); d.io.submit(domain).valid.poke(false.B)
        d.clock.step(); d.io.busy.expect(true.B)
        for (_ <- 0 until 3) { d.io.childFire(domain).expect(false.B); d.io.active.expect(false.B); d.clock.step() }
        d.io.childReady(domain).poke(true.B); d.io.childFire(domain).expect(true.B)
        for (other <- 0 until 3 if other != domain) d.io.childFire(other).expect(false.B)
        d.clock.step(); d.io.childReady(domain).poke(false.B); d.io.active.expect(true.B)
        d.io.release.poke(true.B); d.io.releaseReady.expect(true.B); d.clock.step(); d.io.release.poke(false.B)
        d.io.active.expect(false.B); d.io.busy.expect(false.B)
      }
      // A later job cannot acquire the same payload pool until the current
      // job retires, even when its child is already ready.
      d.io.selected.poke(0.U); d.io.childReady(0).poke(true.B)
      d.io.submit(0).valid.poke(true.B); d.io.submit(0).ready.expect(true.B)
      d.clock.step(); d.io.submit(0).valid.poke(false.B); d.clock.step()
      d.io.childFire(0).expect(true.B); d.clock.step(); d.io.active.expect(true.B)
      d.io.submit(0).valid.poke(true.B); d.io.submit(0).ready.expect(true.B)
      d.clock.step(); d.io.submit(0).valid.poke(false.B); d.clock.step()
      for (_ <- 0 until 3) { d.io.childFire(0).expect(false.B); d.io.busy.expect(true.B); d.clock.step() }
      d.io.release.poke(true.B); d.io.releaseReady.expect(true.B); d.clock.step(); d.io.release.poke(false.B)
      d.io.childFire(0).expect(true.B); d.clock.step(); d.io.active.expect(true.B)
      d.io.release.poke(true.B); d.io.releaseReady.expect(true.B); d.clock.step()
      d.io.active.expect(false.B); d.io.busy.expect(false.B)
    }
  }
}
