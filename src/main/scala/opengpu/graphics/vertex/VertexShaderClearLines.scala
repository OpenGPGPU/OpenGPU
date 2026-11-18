package opengpu.graphics

import chisel3._
import chisel3.util.{Cat, Fill}
import opengpu.util.{BlockPrefixAdder, CarrySavePrefixSum}

/** Clear-line endpoints for an admitted, 64-byte-aligned scratch allocation.
  * endBytes is nonzero and base+endBytes must fit the exclusive 33-bit bound.
  * Admission retains those checks in VertexShaderStage before any access.
  * Reduce the base, relative end and last-line correction together before a
  * single carry-propagate finish. Neither base nor correction traverses two
  * serial carry chains.
  */
class VertexShaderClearLines extends Module {
  val io = IO(new Bundle {
    val base = Input(UInt(32.W))
    val startBytes = Input(UInt(32.W))
    val endBytes = Input(UInt(32.W))
    val first = Output(UInt(32.W))
    val last = Output(UInt(32.W))
  })
  private val first = Module(new BlockPrefixAdder(26))
  first.io.a := io.base(31, 6)
  first.io.b := io.startBytes(31, 6)
  first.io.carryIn := false.B
  private val last = Module(new CarrySavePrefixSum(26))
  last.io.terms(0) := io.base(31, 6)
  last.io.terms(1) := io.endBytes(31, 6)
  last.io.terms(2) := Fill(26, !io.endBytes(5, 0).orR)
  io.first := Cat(first.io.sum(25, 0), 0.U(6.W))
  io.last := Cat(last.io.sum(25, 0), 0.U(6.W))
}
