package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Compare every external handshake against the larger existing owner table. */
class TextureRouteEquivalenceProof(workers: Int) extends Module {
  val io = IO(new Bundle {
    val clients = Vec(workers, new FragmentTextureClient)
    val memoryRequest = Decoupled(new OmMemoryRequest)
    val memoryResponse = Flipped(Decoupled(new OmMemoryResponse))
    val drained = Output(Bool())
    val boundedRequestReady = Output(Vec(workers, Bool()))
  })
  val reference = Module(new LegacyFragmentTextureArbiter(workers, 64))
  val bounded = Module(new FragmentTextureArbiter(workers, 4 * workers))
  for (w <- 0 until workers) {
    reference.io.clients(w).request.valid := io.clients(w).request.valid
    reference.io.clients(w).request.bits := io.clients(w).request.bits
    reference.io.clients(w).response.ready := io.clients(w).response.ready
    bounded.io.clients(w).request.valid := io.clients(w).request.valid
    bounded.io.clients(w).request.bits := io.clients(w).request.bits
    bounded.io.clients(w).response.ready := io.clients(w).response.ready
    io.clients(w).request.ready := reference.io.clients(w).request.ready
    io.clients(w).response.valid := reference.io.clients(w).response.valid
    io.clients(w).response.bits := reference.io.clients(w).response.bits
    // Sample this in the driver after all client inputs have been updated.
    // During sequential pokes, a just-accepted client still presents its old
    // valid until that client's next poke, temporarily exceeding its bound.
    io.boundedRequestReady(w) := bounded.io.clients(w).request.ready
    assert(reference.io.clients(w).response.valid === bounded.io.clients(w).response.valid)
    when(reference.io.clients(w).response.valid) {
      assert(reference.io.clients(w).response.bits.asUInt === bounded.io.clients(w).response.bits.asUInt)
    }
  }
  reference.io.memoryRequest.ready := io.memoryRequest.ready
  reference.io.memoryResponse.valid := io.memoryResponse.valid
  reference.io.memoryResponse.bits := io.memoryResponse.bits
  bounded.io.memoryRequest.ready := io.memoryRequest.ready
  bounded.io.memoryResponse.valid := io.memoryResponse.valid
  bounded.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryRequest.valid := reference.io.memoryRequest.valid
  io.memoryRequest.bits := reference.io.memoryRequest.bits
  io.drained := reference.io.drained
  // Both response-ready outputs must agree; drive the external sink once.
  io.memoryResponse.ready := reference.io.memoryResponse.ready
  assert(reference.io.memoryRequest.valid === bounded.io.memoryRequest.valid)
  when(reference.io.memoryRequest.valid) {
    assert(reference.io.memoryRequest.bits.asUInt === bounded.io.memoryRequest.bits.asUInt)
  }
  assert(reference.io.memoryResponse.ready === bounded.io.memoryResponse.ready)
  assert(reference.io.drained === bounded.io.drained)
}

class TextureRouteEquivalenceSpec extends AnyFlatSpec {
  behavior of "Direct texture owner decode and sampler-derived capacity versus original RTL"

  for (workers <- Seq(1, 2, 3, 4, 8)) {
    it should s"match 64 owner slots with $workers workers, full four-tap groups and stalls" in {
      simulate(new TextureRouteEquivalenceProof(workers)) { d =>
        d.io.clients.foreach { c =>
          c.request.valid.poke(false.B)
          c.request.bits.poke(0.U.asTypeOf(c.request.bits))
          c.response.ready.poke(false.B)
        }
        d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.valid.poke(false.B)
        d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val sent = Array.fill(workers)(0)
        val received = Array.fill(workers)(0)
        val owners = mutable.Map.empty[BigInt, Int]
        val pending = mutable.ArrayBuffer.empty[BigInt]
        var response = Option.empty[BigInt]
        var heldRequest = Option.empty[BigInt]
        var peak = 0
        var cycles = 0
        var full = false
        val readsPerWorker = 48
        def address(w: Int, n: Int): BigInt = {
          // The second half introduces cross-worker duplicate addresses.
          if (n < 24) BigInt(0x10000 + w * 0x1000 + n * 4)
          else BigInt(0x30000 + n * 4)
        }
        while (!received.forall(_ == readsPerWorker) && cycles < 10000) {
          for (w <- 0 until workers) {
            val c = d.io.clients(w)
            assert(sent(w) - received(w) <= 4)
            // RRArbiter's default lastGrant is uninitialized. A sole initial
            // requester aligns both arbiters before comparing simultaneous
            // traffic; this read also counts toward the full-capacity test.
            c.request.valid.poke((sent(w) < readsPerWorker && sent(w) - received(w) < 4 &&
              (cycles > 0 || w == 0)).B)
            c.request.bits.addr.poke(address(w, sent(w)).U)
            c.response.ready.poke((full && cycles % (w + 3) != 0).B)
          }
          d.io.memoryRequest.ready.poke((cycles % 5 != 0).B)
          if (response.isEmpty && full && pending.nonEmpty && cycles % 4 != 0)
            response = Some(pending.max)
          d.io.memoryResponse.valid.poke(response.nonEmpty.B)
          response.foreach { a =>
            d.io.memoryResponse.bits.addr.poke(a.U)
            d.io.memoryResponse.bits.data.poke((a ^ 0x5a5a5a5aL).U)
          }
          for (w <- 0 until workers) {
            val c = d.io.clients(w)
            if (c.request.valid.peek().litToBoolean) {
              assert(c.request.ready.peek().litToBoolean == d.io.boundedRequestReady(w).peek().litToBoolean,
                s"ready mismatch cycle=$cycles worker=$w sent=${sent.toSeq} received=${received.toSeq} pending=${owners.size}")
            }
            if (c.request.valid.peek().litToBoolean && c.request.ready.peek().litToBoolean) {
              val a = address(w, sent(w))
              assert(!owners.contains(a), "duplicate physical address admitted before retirement")
              owners(a) = w
              sent(w) += 1
            }
            if (c.response.valid.peek().litToBoolean) {
              val a = c.response.bits.addr.peek().litValue
              assert(response.contains(a) && owners(a) == w)
              c.response.bits.data.expect((a ^ 0x5a5a5a5aL).U)
              if (c.response.ready.peek().litToBoolean) {
                received(w) += 1
              }
            }
          }
          if (d.io.memoryRequest.valid.peek().litToBoolean) {
            val a = d.io.memoryRequest.bits.addr.peek().litValue
            heldRequest.foreach(old => assert(old == a, "request changed under stall"))
            assert(owners.contains(a))
            if (d.io.memoryRequest.ready.peek().litToBoolean) {
              assert(!pending.contains(a))
              pending += a
              heldRequest = None
            } else heldRequest = Some(a)
          }
          if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) {
            val a = response.get
            assert(pending.contains(a))
            pending -= a; owners -= a; response = None
          }
          peak = math.max(peak, owners.size)
          if (owners.size == 4 * workers) full = true
          d.clock.step(); cycles += 1
        }
        assert(full && peak == 4 * workers, "must exercise the full physical concurrency bound")
        assert(received.forall(_ == readsPerWorker), "all replies must retire")
        d.io.memoryResponse.valid.poke(false.B)
        d.io.clients.foreach(_.request.valid.poke(false.B))
        d.io.drained.expect(true.B)
      }
    }
  }
}
