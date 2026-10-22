package opengpu.core.execute.control

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorSimtBranchExecuteStageSpec extends AnyFlatSpec {
  private def encoding(op: Int, offset: Int): BigInt = {
    val n = offset & 8191
    (BigInt((n >> 12) & 1) << 31) | (BigInt((n >> 5) & 63) << 25) |
      (BigInt(2) << 20) | (BigInt(1) << 15) | (BigInt(op) << 12) |
      (BigInt((n >> 1) & 15) << 8) | (BigInt((n >> 11) & 1) << 7) | 0x5b
  }
  for (lanes <- Seq(4, 16)) {
    it should s"compare all six full-width predicates with owned stalls and clear lanes=$lanes" in {
      val config = GpuConfig(lanes = lanes, warps = 8)
      simulate(new VectorSimtBranchExecuteStage(config)) { d =>
        d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.clear.valid.poke(false.B); d.io.clear.bits.poke(0.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val random = new scala.util.Random(9137 + lanes)
        var packets = 0
        val unsignedMask = (BigInt(1) << 32) - 1
        for (op <- Seq(0, 1, 4, 5, 6, 7); round <- 0 until 20) {
          val values = Vector.tabulate(lanes) { lane =>
            val directed = Vector(0L, 1L, 0x7fffffffL, 0x80000000L, 0xffffffffL)
            val a = if (round < 5) directed((lane + round) % directed.size)
              else random.nextInt().toLong & 0xffffffffL
            val b = if (round < 5) directed((lane * 2 + round) % directed.size)
              else random.nextInt().toLong & 0xffffffffL
            (a, b)
          }
          val active = if (round == 0) (BigInt(1) << lanes) - 1
            else BigInt(lanes, random)
          val taken = values.zipWithIndex.foldLeft(BigInt(0)) { case (mask, ((a, b), lane)) =>
            val selected = op match {
              case 0 => a == b
              case 1 => a != b
              case 4 => a.toInt < b.toInt
              case 5 => a.toInt >= b.toInt
              case 6 => a < b
              case 7 => a >= b
            }
            if (selected && active.testBit(lane)) mask.setBit(lane) else mask
          }
          val offset = Vector(-4096, -4, 2, 4, 2048, 4094)(round % 6)
          val pc = if (round % 3 == 0) BigInt("fffffffc", 16) else BigInt(0x4000 + 4 * round)
          d.io.in.bits.decode.instruction.poke(encoding(op, offset).U)
          d.io.in.bits.decode.pc.poke(pc.U)
          d.io.in.bits.decode.warpId.poke((round % 8).U)
          d.io.in.bits.decode.activeMask.poke(active.U)
          // A zero RVV predicate mask must not suppress a SIMT branch lane.
          d.io.in.bits.predicateMask.poke(0.U)
          for (lane <- 0 until lanes) {
            d.io.in.bits.vs1Data(lane).poke(BigInt(values(lane)._1).U)
            d.io.in.bits.vs2Data(lane).poke(BigInt(values(lane)._2).U)
          }
          d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
          d.clock.step(); d.io.in.valid.poke(false.B)
          def check(): Unit = {
            d.io.out.valid.expect(true.B)
            d.io.out.bits.warpId.expect((round % 8).U)
            d.io.out.bits.pc.expect(pc.U)
            d.io.out.bits.targetPc.expect(((pc + offset) & unsignedMask).U)
            d.io.out.bits.fallthroughPc.expect(((pc + 4) & unsignedMask).U)
            d.io.out.bits.activeMask.expect(active.U)
            d.io.out.bits.takenMask.expect(taken.U)
          }
          for (_ <- 0 until 3) { check(); d.clock.step() }
          // Clearing a peer cannot change a held branch result.
          d.io.clear.valid.poke(true.B); d.io.clear.bits.poke(((round + 1) % 8).U)
          check(); d.clock.step(); d.io.clear.valid.poke(false.B)
          if (round % 5 == 0) {
            d.io.clear.valid.poke(true.B); d.io.clear.bits.poke((round % 8).U)
            d.io.out.valid.expect(false.B); d.clock.step(); d.io.clear.valid.poke(false.B)
          } else {
            d.io.out.ready.poke(true.B); check(); d.clock.step(); d.io.out.ready.poke(false.B)
          }
          d.io.out.valid.expect(false.B); packets += 1
        }
        info(s"VECTOR_SIMT_PREDICATES lanes=$lanes packets=$packets ops=6 signedUnsigned32=true " +
          "BImmediate13=true pcWrap=true ignoresRVVPredicate=true heldResult=true scopedClear=true")
      }
    }
  }
}
