package opengpu.util

import chisel3._

/** Full-precision sum of three unsigned terms: bitwise carry-save reduction
  * followed by one block-prefix carry propagation. Truncating its low N bits
  * also gives the exact modular sum of N-bit two's-complement operands.
  */
class CarrySavePrefixSum(width: Int = 64) extends Module {
  require(width > 0)
  val io = IO(new Bundle {
    val terms = Input(Vec(3, UInt(width.W)))
    val sum = Output(UInt((width + 2).W))
  })
  private val a = io.terms(0)
  private val b = io.terms(1)
  private val c = io.terms(2)
  private val reducedSum = a ^ b ^ c
  private val reducedCarry = ((a & b) | (a & c) | (b & c)) << 1
  private val adder = Module(new BlockPrefixAdder(width + 1))
  adder.io.a := reducedSum
  adder.io.b := reducedCarry
  adder.io.carryIn := false.B
  io.sum := adder.io.sum
}
