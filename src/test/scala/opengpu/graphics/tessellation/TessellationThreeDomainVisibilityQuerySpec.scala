package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.system.TessellationThreeDomainSampleMux
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercise the same sample handoff used by the host query path. */
class TessellationThreeDomainVisibilityQueryHarness extends Module {
  private val query = Module(new NativeVisibilityQueryEngine(contexts = 1, producers = 1))
  val io = IO(chiselTypeOf(query.io))
  val domain = IO(new Bundle {
    val selected = Input(UInt(2.W))
    val domains = Flipped(Vec(3,Decoupled(new TypedTilePixelQuadCompletion)))
    val ready = Input(Bool())
  })
  io <> query.io
  private val mux = Module(new TessellationThreeDomainSampleMux)
  mux.io.selected := domain.selected
  mux.io.domains <> domain.domains
  mux.io.out.ready := domain.ready
  query.io.samples(0).valid := mux.io.out.fire
  query.io.samples(0).bits.context := io.samples(0).bits.context
  query.io.samples(0).bits.root := io.samples(0).bits.root
  query.io.samples(0).bits.status := mux.io.out.bits.status
  query.io.samples(0).bits.passingSamples := mux.io.out.bits.passingSamples
}

class TessellationThreeDomainVisibilityQuerySpec extends AnyFlatSpec {
  import WindowRecordTest._
  it should "accumulate accepted samples from all three domains and propagate faults" in {
    simulate(new TessellationThreeDomainVisibilityQueryHarness) { d =>
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
      def sample(domain: Int, count: Int, status: Int, hold: Boolean = false): Unit = {
        d.domain.selected.poke(domain.U)
        val source = d.domain.domains(domain)
        source.bits.passingSamples.poke(count.U); source.bits.status.poke(status.U)
        source.valid.poke(true.B)
        if (hold) { d.domain.ready.poke(false.B); d.clock.step(3) }
        d.domain.ready.poke(true.B); source.ready.expect(true.B); d.clock.step()
        source.valid.poke(false.B); d.domain.ready.poke(false.B); d.clock.step()
      }
      command(0, 1, 0, false)
      sample(domain = 0, count = 0, status = 0)
      sample(domain = 2, count = 3, status = 0, hold = true)
      sample(domain = 1, count = 2, status = 0)
      val visible = command(1, 1, 0, true)
      assert(((visible >> (5 * 32)) & 0xffffffffL) == 1)
      command(0, 2, 0, false)
      sample(domain = 1, count = 2, status = 0)
      sample(domain = 2, count = 0, status = 2)
      val faulted = command(1, 2, 3, false)
      assert(((faulted >> (4 * 32)) & 0x1f) == 13)
      command(0, 3, 0, false)
      d.io.samples(0).bits.root.poke(10.U)
      sample(domain = 2, count = 5, status = 0)
      d.io.samples(0).bits.root.poke(9.U)
      sample(domain = 1, count = 0, status = 0)
      val invisible = command(1, 3, 0, false)
      assert(((invisible >> (5 * 32)) & 0xffffffffL) == 0)

      // Hold an end boundary while the selected renderer is still active.
      // Accepted samples must remain in this query, even under output stalls.
      command(0, 4, 0, false)
      d.io.renderIdle.poke(false.B)
      val end = d.io.command.bits
      end.action.poke(1.U); end.address.poke(0x2000.U); end.limit.poke(0x2040.U)
      end.context.poke(7.U); end.cookie.poke(4.U); end.tag.poke(4.U)
      d.io.command.valid.poke(true.B)
      for (domain <- 0 until 3) {
        d.io.command.ready.expect(false.B)
        d.io.memoryRequest.valid.expect(false.B)
        sample(domain, if (domain == 2) 1 else 0, 0, hold = true)
        d.io.command.ready.expect(false.B)
      }
      d.io.renderIdle.poke(true.B)
      waitFor(d.io.command.ready.peek().litToBoolean); d.clock.step()
      d.io.command.valid.poke(false.B)
      waitFor(d.io.memoryRequest.valid.peek().litToBoolean)
      val deferred = d.io.memoryRequest.bits.writeData.peek().litValue
      d.clock.step(); d.io.memoryResponse.valid.poke(true.B)
      waitFor(d.io.memoryResponse.ready.peek().litToBoolean); d.clock.step()
      d.io.memoryResponse.valid.poke(false.B)
      waitFor(d.io.reply.valid.peek().litToBoolean)
      d.io.reply.bits.status.expect(0.U); d.io.reply.bits.available.expect(true.B)
      d.io.reply.bits.value.expect(true.B)
      assert(((deferred >> (5 * 32)) & 0xffffffffL) == 1)
      d.io.reply.ready.poke(true.B); d.clock.step()
    }
  }
}
