package opengpu.util

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class RegisteredQueue2EquivalenceHarness(width: Int) extends Module {
  val io = IO(new Bundle {
    val data = Input(UInt(width.W))
    val offer = Input(Bool())
    val consume = Input(Bool())
    val equivalent = Output(Bool())
    val accepted = Output(Bool())
    val retired = Output(Bool())
    val value = Output(UInt(width.W))
  })
  val reference = Module(new Queue(UInt(width.W), 2, pipe = false, flow = false))
  val candidate = Module(new RegisteredQueue2(UInt(width.W)))
  reference.io.enq.valid := io.offer; candidate.io.enq.valid := io.offer
  reference.io.enq.bits := io.data; candidate.io.enq.bits := io.data
  reference.io.deq.ready := io.consume; candidate.io.deq.ready := io.consume
  io.equivalent := reference.io.enq.ready === candidate.io.enq.ready &&
    reference.io.deq.valid === candidate.io.deq.valid &&
    reference.io.count === candidate.io.count &&
    (!reference.io.deq.valid || reference.io.deq.bits === candidate.io.deq.bits)
  io.accepted := candidate.io.enq.fire
  io.retired := candidate.io.deq.fire
  io.value := candidate.io.deq.bits
}

object EmitRegisteredQueue2Proof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RegisteredQueue2EquivalenceHarness(8),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class RegisteredQueue2Spec extends AnyFlatSpec {
  behavior of "Two-entry registered-head queue"

  for (width <- Seq(1, 32, 1300)) {
    it should s"match Queue every cycle through full, empty and stalled transitions at width$width" in {
      simulate(new RegisteredQueue2EquivalenceHarness(width)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val random = new scala.util.Random(0x2f00 + width)
        val expected = collection.mutable.Queue.empty[BigInt]
        var accepted = 0; var retired = 0
        for (cycle <- 0 until 5000) {
          val (offer, consume) = if (cycle < 8) (true, false)
            else if (cycle < 1040) (true, true)
            else if (cycle < 1050) (false, true)
            else (random.nextBoolean(), random.nextInt(7) < 3)
          val data = BigInt(width, random)
          dut.io.data.poke(data.U); dut.io.offer.poke(offer.B); dut.io.consume.poke(consume.B)
          dut.io.equivalent.expect(true.B)
          if (dut.io.retired.peek().litToBoolean) {
            assert(expected.nonEmpty && dut.io.value.peek().litValue == expected.dequeue())
            retired += 1
          }
          if (dut.io.accepted.peek().litToBoolean) { expected.enqueue(data); accepted += 1 }
          dut.clock.step()
        }
        dut.io.offer.poke(false.B); dut.io.consume.poke(true.B)
        for (_ <- 0 until 4) {
          dut.io.equivalent.expect(true.B)
          if (dut.io.retired.peek().litToBoolean) {
            assert(dut.io.value.peek().litValue == expected.dequeue()); retired += 1
          }
          dut.clock.step()
        }
        assert(expected.isEmpty && accepted == retired && retired > 1000)
        info(s"width=$width cycles=5004 orderedTransfers=$retired equalEveryCycle=true")
      }
    }
  }
}
