package opengpu.dma

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FillEngineSpec extends AnyFlatSpec {
  behavior of "FillEngine"

  private def initialize(dut: FillEngine): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.descriptor.valid.poke(false.B)
    dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
    dut.io.completion.ready.poke(false.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
  }

  private def submit(dut: FillEngine, id: Int, address: BigInt,
                     bytes: Int, pattern: BigInt): Unit = {
    dut.io.descriptor.bits.descriptorId.poke(id.U)
    dut.io.descriptor.bits.destinationAddress.poke(address.U)
    dut.io.descriptor.bits.bytes.poke(bytes.U)
    dut.io.descriptor.bits.pattern.poke(pattern.U)
    dut.io.descriptor.valid.poke(true.B)
    dut.io.descriptor.ready.expect(true.B)
    dut.clock.step(); dut.io.descriptor.valid.poke(false.B)
  }

  private def respond(dut: FillEngine, id: BigInt,
                      fault: Boolean = false): Unit = {
    dut.io.memoryResponse.bits.transactionId.poke(id.U)
    dut.io.memoryResponse.bits.fault.poke(fault.B)
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
  }

  it should "issue two patterned lines and accept out-of-order acknowledgements" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      val word = BigInt("deadbeef", 16)
      val line = BigInt(List.fill(16)("deadbeef").mkString, 16)
      submit(dut, 3, 0x4000, 128, word)

      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4000.U)
      dut.io.memoryRequest.bits.writeData.expect(line.U)
      val first = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryRequest.bits.address.expect(0x4040.U)
      val second = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(first != second)
      dut.clock.step()

      respond(dut, second)
      respond(dut, first)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.completion.bits.bytesFilled.expect(128.U)
    }
  }

  it should "drain outstanding writes after a fault" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, 4, 0x5000, 128, 0)
      val first = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      val second = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      respond(dut, first, fault = true)
      dut.io.completion.valid.expect(false.B)
      respond(dut, second)
      dut.clock.step()
      dut.io.completion.bits.status.expect(CopyStatus.writeFault)
      dut.io.completion.bits.bytesFilled.expect(64.U)
    }
  }

  it should "map a narrow slot index into a wider shared transaction range" in {
    simulate(new FillEngine(
      GpuConfig(lanes = 4),
      descriptorIdWidth = 4,
      maxOutstanding = 8,
      lineSlots = 2,
      transactionIdBase = 4)) { dut =>
      initialize(dut)
      submit(dut, 5, 0x5800, 64, 0x12345678L)

      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.transactionId.expect(4.U)
      dut.clock.step()
      respond(dut, 4)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.completion.bits.bytesFilled.expect(64.U)
    }
  }

  it should "reject unaligned and non-line-sized descriptors" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, 1, 0x5004, 64, 0)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.bits.status.expect(CopyStatus.invalidAlignment)
      dut.io.completion.ready.poke(true.B); dut.clock.step()
      submit(dut, 2, 0x6000, 63, 0)
      dut.io.completion.bits.status.expect(CopyStatus.invalidLength)
    }
  }

  for (slots <- Seq(8, 16)) {
    it should s"drain $slots outstanding slots after an out-of-order fault and reuse them" in {
      simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4,
        maxOutstanding = 2 * slots, lineSlots = slots, transactionIdBase = slots)) { dut =>
        initialize(dut)
        submit(dut, 6, 0x8000, (slots + 2) * 64, 0x12345678L)
        val ids = (0 until slots).map { line =>
          dut.io.memoryRequest.valid.expect(true.B)
          dut.io.memoryRequest.bits.address.expect((0x8000 + line * 64).U)
          val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          dut.clock.step()
          id
        }
        assert(ids.toSet == (slots until 2 * slots).map(BigInt(_)).toSet)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.memoryRequest.ready.poke(false.B)
        respond(dut, ids.last)
        dut.io.memoryRequest.valid.expect(true.B)
        dut.io.memoryRequest.bits.address.expect((0x8000 + slots * 64).U)
        for (_ <- 0 until 3) {
          dut.io.memoryRequest.bits.address.expect((0x8000 + slots * 64).U)
          dut.clock.step()
        }
        respond(dut, ids(2), fault = true)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
        for (slot <- (0 until slots).reverse.filterNot(i => i == 2 || i == slots - 1)) {
          respond(dut, ids(slot))
          dut.io.memoryRequest.valid.expect(false.B)
        }
        dut.clock.step()
        for (_ <- 0 until 4) {
          dut.io.completion.valid.expect(true.B)
          dut.io.completion.bits.status.expect(CopyStatus.writeFault)
          dut.io.completion.bits.bytesFilled.expect(((slots - 1) * 64).U)
          dut.clock.step()
        }
        dut.io.completion.ready.poke(true.B)
        dut.clock.step()
        dut.io.memoryRequest.ready.poke(true.B)
        submit(dut, 7, 0x9000, 64, 0xabcdef01L)
        dut.io.memoryRequest.valid.expect(true.B)
        val next = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        respond(dut, next)
        dut.clock.step()
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.success.expect(true.B)
        dut.io.completion.bits.bytesFilled.expect(64.U)
      }
    }
  }
}
