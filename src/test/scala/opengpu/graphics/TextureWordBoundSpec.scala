package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureWordBoundProof extends Module {
  private val config = GpuConfig(lanes = 16, warps = 8)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OmMemoryRequest))
    val out = Decoupled(new OmMemoryResponse)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 64))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 64)))
    val invalidate = Input(Bool())
    val boundedReady = Output(Bool())
  })
  val reference = Module(new BufferedTextureWordPort(config, 64, 16))
  val bounded = Module(new BufferedTextureWordPort(config, 16, 16))
  for (d <- Seq(reference, bounded)) {
    d.io.port.in.valid := io.in.valid
    d.io.port.in.bits := io.in.bits
    d.io.port.out.ready := io.out.ready
    d.io.port.memoryRequest.ready := io.memoryRequest.ready
    d.io.port.memoryResponse.valid := io.memoryResponse.valid
    d.io.port.memoryResponse.bits := io.memoryResponse.bits
    d.io.invalidate := io.invalidate
  }
  io.in.ready := reference.io.port.in.ready
  io.boundedReady := bounded.io.port.in.ready
  io.out <> reference.io.port.out
  io.memoryRequest.valid := reference.io.port.memoryRequest.valid
  io.memoryRequest.bits := reference.io.port.memoryRequest.bits
  io.memoryResponse.ready := reference.io.port.memoryResponse.ready
  assert(reference.io.port.out.valid === bounded.io.port.out.valid)
  when(reference.io.port.out.valid) {
    assert(reference.io.port.out.bits.asUInt === bounded.io.port.out.bits.asUInt)
  }
  assert(reference.io.port.memoryRequest.valid === bounded.io.port.memoryRequest.valid)
  val widenedBoundedRequest = Wire(chiselTypeOf(io.memoryRequest.bits))
  widenedBoundedRequest := bounded.io.port.memoryRequest.bits
  when(reference.io.port.memoryRequest.valid) {
    assert(reference.io.port.memoryRequest.bits.asUInt === widenedBoundedRequest.asUInt)
  }
  assert(reference.io.port.memoryResponse.ready === bounded.io.port.memoryResponse.ready)
}

class TextureWordBoundSpec extends AnyFlatSpec {
  it should "match 64 word slots at full sixteen-line capacity, reuse, invalidation and fault retry" in {
    simulate(new TextureWordBoundProof) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.out.ready.poke(false.B); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.invalidate.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

      def invalidate(): Unit = {
        d.io.in.valid.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
        d.io.invalidate.poke(true.B); d.clock.step(); d.io.invalidate.poke(false.B)
      }
      def phase(addresses: Seq[Int], key: Long, fault: Boolean = false): Int = {
        val fills = mutable.Map.empty[BigInt, BigInt]
        val expected = mutable.Map.empty[BigInt, BigInt]
        var response = Option.empty[(BigInt, BigInt)]
        var held = Option.empty[(BigInt, BigInt)]
        var sent = 0
        var received = 0
        var requests = 0
        var cycles = 0
        var peak = 0
        def word(a: BigInt): BigInt = if (fault) BigInt(0) else a ^ key
        while (received < addresses.size && cycles < 1000) {
          assert(sent - received <= 16)
          d.io.in.valid.poke((sent < addresses.size && sent - received < 16).B)
          d.io.in.bits.addr.poke(addresses(math.min(sent, addresses.size - 1)).U)
          d.io.memoryRequest.ready.poke((cycles % 5 != 0).B)
          // Fill the complete word/line capacity before permitting replies.
          d.io.out.ready.poke((sent == addresses.size && cycles % 4 != 0).B)
          if (response.isEmpty && sent == addresses.size && fills.nonEmpty && cycles % 3 != 0) {
            val id = fills.keys.max
            response = Some((id, fills(id)))
          }
          d.io.memoryResponse.valid.poke(response.nonEmpty.B)
          response.foreach { case (id, line) =>
            d.io.memoryResponse.bits.transactionId.poke(id.U)
            val data = (0 until 16).foldLeft(BigInt(0))((v, i) => v | ((line + 4 * i ^ key) << (32 * i)))
            d.io.memoryResponse.bits.readData.poke(data.U)
            d.io.memoryResponse.bits.fault.poke(fault.B)
          }
          if (d.io.in.valid.peek().litToBoolean) {
            assert(d.io.in.ready.peek().litToBoolean == d.io.boundedReady.peek().litToBoolean,
              s"word readiness differs at cycle=$cycles pending=${sent - received}")
            if (d.io.in.ready.peek().litToBoolean) {
              val a = BigInt(addresses(sent))
              assert(!expected.contains(a)); expected(a) = word(a); sent += 1
            }
          }
          if (d.io.memoryRequest.valid.peek().litToBoolean) {
            val p = d.io.memoryRequest.bits
            val value = (p.transactionId.peek().litValue, p.address.peek().litValue)
            held.foreach(old => assert(old == value, "line request changed under stall"))
            p.isWrite.expect(false.B); p.sizeLog2.expect(6.U)
            assert(value._1 < 16)
            if (d.io.memoryRequest.ready.peek().litToBoolean) {
              assert(!fills.contains(value._1)); fills(value._1) = value._2
              requests += 1; held = None
            } else held = Some(value)
          }
          if (d.io.out.valid.peek().litToBoolean) {
            val a = d.io.out.bits.addr.peek().litValue
            assert(expected.contains(a)); d.io.out.bits.data.expect(expected(a).U)
            d.io.out.bits.write.expect(false.B)
            if (d.io.out.ready.peek().litToBoolean) { expected -= a; received += 1 }
          }
          if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) {
            fills -= response.get._1; response = None
          }
          peak = math.max(peak, sent - received)
          d.clock.step(); cycles += 1
        }
        assert(received == addresses.size && expected.isEmpty && fills.isEmpty)
        assert(peak == addresses.size)
        d.io.in.valid.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
        requests
      }

      // A sole first reply aligns the two uninitialized RR priority registers.
      assert(phase(Seq(0x8000), 0x11110000L) == 1)
      assert(phase((0 until 16).map(i => 0x10000 + i * 64), 0x22220000L) == 16)
      val words = (0 until 16).map(i => 0x10000 + i * 4)
      assert(phase(words, 0x22220000L) == 0, "all sixteen cache lines must remain reusable")
      invalidate()
      assert(phase(words, 0x33330000L) == 1, "invalidation must fetch fresh data")
      invalidate()
      assert(phase(words, 0x44440000L, fault = true) == 1)
      assert(phase(words, 0x55550000L) == 1, "faulted fills must not become cache hits")
    }
  }
}
