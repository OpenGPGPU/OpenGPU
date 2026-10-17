package opengpu.util

import chisel3._
import chisel3.util._

/** Exact unsigned addition with a prefix tree between short carry groups.
  * Native wide additions can map to long ripple chains in the physical flow.
  * Each group uses a bounded local addition; parallel generate/propagate
  * prefixes determine the incoming carry without a group-to-group ripple.
  * This is combinational and returns the full width+1 result.
  */
class BlockPrefixAdder(width: Int = 64, groupBits: Int = 4) extends Module {
  require(width > 0 && groupBits > 0)
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val carryIn = Input(Bool())
    val sum = Output(UInt((width + 1).W))
  })

  private val groups = (width + groupBits - 1) / groupBits
  private val sizes = (0 until groups).map(i => math.min(groupBits, width - i * groupBits))
  private val local = sizes.zipWithIndex.map { case (bits, i) =>
    val lo = i * groupBits
    io.a(lo + bits - 1, lo) +& io.b(lo + bits - 1, lo)
  }
  private var generate: Seq[Bool] = local.zip(sizes).map { case (sum, bits) => sum(bits) }
  private var propagate: Seq[Bool] = sizes.zipWithIndex.map { case (bits, i) =>
    val lo = i * groupBits
    (io.a(lo + bits - 1, lo) ^ io.b(lo + bits - 1, lo)).andR
  }
  for (stage <- 0 until log2Ceil(groups)) {
    val distance = 1 << stage
    val g = generate
    val p = propagate
    generate = (0 until groups).map(i =>
      if (i < distance) g(i) else g(i) || (p(i) && g(i - distance)))
    propagate = (0 until groups).map(i =>
      if (i < distance) p(i) else p(i) && p(i - distance))
  }
  private val carries = (0 until groups).map { i =>
    if (i == 0) io.carryIn else generate(i - 1) || (propagate(i - 1) && io.carryIn)
  }
  private val sums = local.zip(sizes).zip(carries).map { case ((sum, bits), carry) =>
    (sum(bits - 1, 0) + carry.asUInt)(bits - 1, 0)
  }
  private val carryOut = generate.last || (propagate.last && io.carryIn)
  io.sum := Cat(carryOut, Cat(sums.reverse))
}
