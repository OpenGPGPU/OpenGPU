package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.system.TessellationDomainSampleMux
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercise the same sample handoff used by the host query path. */
class TessellationDomainVisibilityQueryHarness extends Module {
  private val query = Module(new NativeVisibilityQueryEngine(contexts = 1, producers = 1))
  val io = IO(chiselTypeOf(query.io))
  val domain = IO(new Bundle {
    val selected = Input(Bool())
    val isoline = Flipped(Decoupled(new TypedTilePixelQuadCompletion))
    val triangle = Flipped(Decoupled(new TypedTilePixelQuadCompletion))
    val ready = Input(Bool())
  })
  io <> query.io
  private val mux = Module(new TessellationDomainSampleMux)
  mux.io.selected := domain.selected
  mux.io.isoline <> domain.isoline
  mux.io.triangle <> domain.triangle
  mux.io.out.ready := domain.ready
  query.io.samples(0).valid := mux.io.out.fire
  query.io.samples(0).bits.context := io.samples(0).bits.context
  query.io.samples(0).bits.root := io.samples(0).bits.root
  query.io.samples(0).bits.status := mux.io.out.bits.status
  query.io.samples(0).bits.passingSamples := mux.io.out.bits.passingSamples
}

class TessellationDomainVisibilityQuerySpec extends AnyFlatSpec {
  import WindowRecordTest._
  it should "accumulate accepted samples from both domains and propagate faults" in {
    simulate(new TessellationDomainVisibilityQueryHarness) { d =>
      zero(d.io); zero(d.domain)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.renderIdle.poke(true.B); d.io.memoryRequest.ready.poke(true.B)
      d.io.samples(0).bits.context.poke(7.U); d.io.samples(0).bits.root.poke(9.U)
      d.io.satp.poke(9.U)
      def waitFor(p: => Boolean): Unit = {
        var cycles = 0
        while (!p && cycles < 100) { d.clock.step(); cycles += 1 }
        assert(p, "visibility query stalled")
      }
      def command(action: Int, cookie: Int, expectedStatus: Int, expectedValue: Boolean): BigInt = {
        val q = d.io.command.bits
        q.action.poke(action.U); q.address.poke(0x2000.U); q.limit.poke(0x2040.U)
        q.context.poke(7.U); q.cookie.poke(cookie.U); q.tag.poke(cookie.U)
        d.io.command.valid.poke(true.B)
        waitFor(d.io.command.ready.peek().litToBoolean); d.clock.step()
        d.io.command.valid.poke(false.B)
        waitFor(d.io.memoryRequest.valid.peek().litToBoolean)
        val written = d.io.memoryRequest.bits.writeData.peek().litValue
        d.io.memoryRequest.bits.isWrite.expect(true.B); d.clock.step()
        d.io.memoryResponse.valid.poke(true.B)
        waitFor(d.io.memoryResponse.ready.peek().litToBoolean); d.clock.step()
        d.io.memoryResponse.valid.poke(false.B)
        waitFor(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.status.expect(expectedStatus.U); d.io.reply.bits.value.expect(expectedValue.B)
        d.io.reply.bits.available.expect((action == 1).B)
        d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
        written
      }
      def sample(triangle: Boolean, count: Int, status: Int, hold: Boolean = false): Unit = {
        d.domain.selected.poke(triangle.B)
        val source = if (triangle) d.domain.triangle else d.domain.isoline
        source.bits.passingSamples.poke(count.U); source.bits.status.poke(status.U)
        source.valid.poke(true.B)
        if (hold) { d.domain.ready.poke(false.B); d.clock.step(3) }
        d.domain.ready.poke(true.B); source.ready.expect(true.B); d.clock.step()
        source.valid.poke(false.B); d.domain.ready.poke(false.B); d.clock.step()
      }
      command(0, 1, 0, false)
      sample(triangle = false, count = 0, status = 0)
      sample(triangle = true, count = 3, status = 0, hold = true)
      val visible = command(1, 1, 0, true)
      assert(((visible >> (5 * 32)) & 0xffffffffL) == 1)
      command(0, 2, 0, false)
      sample(triangle = true, count = 2, status = 0)
      sample(triangle = false, count = 0, status = 2)
      val faulted = command(1, 2, 3, false)
      assert(((faulted >> (4 * 32)) & 0x1f) == 13)
      command(0, 3, 0, false)
      d.io.samples(0).bits.root.poke(10.U)
      sample(triangle = false, count = 5, status = 0)
      d.io.samples(0).bits.root.poke(9.U)
      sample(triangle = true, count = 0, status = 0)
      val invisible = command(1, 3, 0, false)
      assert(((invisible >> (5 * 32)) & 0xffffffffL) == 0)
    }
  }
}
