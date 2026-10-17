package opengpu.graphics

import chisel3._
import chisel3.util._

/** Snapshot then validate full-precision address ranges in bounded stages.
  * Keeping multiply, base addition, column addition and comparisons in
  * separate cycles avoids an input-descriptor-to-ready combinational cone.
  */
class TileRenderPassAdmissionResult extends Bundle {
  val valid = Bool()
  val tileBases = Vec(3, UInt(32.W))
}

class TileRenderPassAdmission(maxSampleCount: Int, screenWidth: Int, screenHeight: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TileRenderPassDescriptor))
    val out = Decoupled(new TileRenderPassAdmissionResult)
  })
  private val Seq(idle, coordinates, products, joinProducts, productSum, rowSums, rowCarry,
    ranges, rangeCarry, validate, complete) = Enum(11)
  private val state = RegInit(idle)
  private val descriptor = Reg(new TileRenderPassDescriptor)
  private val basicValid = Reg(Bool())
  private val lastY = Reg(UInt(18.W))
  private val columnStart = Reg(Vec(3, UInt(32.W)))
  private val columnEnd = Reg(Vec(3, UInt(32.W)))
  private val rowStart = Reg(Vec(3, UInt(49.W)))
  private val rowEnd = Reg(Vec(3, UInt(50.W)))
  private val startLow = Reg(Vec(3, UInt(40.W)))
  private val startHigh = Reg(Vec(3, UInt(40.W)))
  private val endLow = Reg(Vec(3, UInt(40.W)))
  private val endHigh = Reg(Vec(3, UInt(42.W)))
  private val startLowParts = Reg(Vec(3, Vec(2, UInt(24.W))))
  private val startHighParts = Reg(Vec(3, Vec(2, UInt(24.W))))
  private val endLowParts = Reg(Vec(3, Vec(2, UInt(24.W))))
  private val endHighParts = Reg(Vec(3, Vec(2, UInt(26.W))))
  private val startBase = Reg(Vec(3, UInt(50.W)))
  private val endBase = Reg(Vec(3, UInt(51.W)))
  private val start = Reg(Vec(3, UInt(51.W)))
  private val end = Reg(Vec(3, UInt(52.W)))
  private class SumParts(highBits: Int) extends Bundle {
    val low = UInt(17.W)
    val high = UInt(highBits.W)
  }
  private val rowStartParts = Reg(Vec(3, new SumParts(34)))
  private val rowEndParts = Reg(Vec(3, new SumParts(35)))
  private val rangeStartParts = Reg(Vec(3, new SumParts(35)))
  private val rangeEndParts = Reg(Vec(3, new SumParts(36)))
  private def splitSum(parts: SumParts, a: UInt, b: UInt): Unit = {
    parts.low := a(15, 0) +& b(15, 0)
    parts.high := (a >> 16) +& (b >> 16)
  }
  private def joinSum(parts: SumParts): UInt =
    Cat(parts.high + parts.low(16), parts.low(15, 0))
  private val result = Reg(Bool())
  private val bases = Seq(descriptor.color.base, descriptor.depth.base, descriptor.resolveBase)
  private val pitches = Seq(descriptor.color.rowPitch, descriptor.depth.rowPitch, descriptor.resolveRowPitch)
  private val limits = Seq(descriptor.color.limit, descriptor.depth.limit, descriptor.resolveLimit)

  io.in.ready := state === idle
  io.out.valid := state === complete
  io.out.bits.valid := result
  for (i <- 0 until 3) io.out.bits.tileBases(i) := start(i)(31, 0)
  when(io.in.fire) { descriptor := io.in.bits; state := coordinates }
  when(state === coordinates) {
    val xEnd = descriptor.originX +& descriptor.tileWidth
    val yEnd = descriptor.originY +& descriptor.tileHeight
    lastY := yEnd - 1.U
    val sourceColumnEnd = xEnd << (descriptor.sampleMode +& 2.U)
    columnStart(0) := descriptor.originX << (descriptor.sampleMode +& 2.U)
    columnStart(1) := descriptor.originX << (descriptor.sampleMode +& 2.U)
    columnStart(2) := descriptor.originX << 2
    columnEnd(0) := sourceColumnEnd
    columnEnd(1) := sourceColumnEnd
    columnEnd(2) := xEnd << 2
    val classSize = Mux(descriptor.tileLog2 === 3.U, 8.U, 16.U)
    val sourceViewsValid = Seq(descriptor.color, descriptor.depth).map(view =>
      view.base(1, 0) === 0.U && view.rowPitch(1, 0) === 0.U &&
        view.rowPitch >= sourceColumnEnd && view.loadOp <= AttachmentLoadOp.discard.U).reduce(_ && _)
    basicValid := descriptor.version === 1.U &&
      (descriptor.tileLog2 === 3.U || descriptor.tileLog2 === 4.U) &&
      descriptor.originX(2, 0) === 0.U && descriptor.originY(2, 0) === 0.U &&
      (descriptor.tileLog2 === 3.U || (!descriptor.originX(3) && !descriptor.originY(3))) &&
      descriptor.tileWidth > 0.U && descriptor.tileWidth <= classSize &&
      descriptor.tileHeight > 0.U && descriptor.tileHeight <= classSize &&
      xEnd <= screenWidth.U && yEnd <= screenHeight.U &&
      descriptor.sampleMode <= log2Ceil(maxSampleCount).U &&
      descriptor.color.rowPitch === descriptor.depth.rowPitch &&
      descriptor.colorFormat <= PixelFormat.bgrx.U && sourceViewsValid &&
      (!descriptor.resolve || (descriptor.resolveBase(1, 0) === 0.U &&
        descriptor.resolveRowPitch(1, 0) === 0.U && descriptor.resolveRowPitch >= (xEnd << 2)))
    state := products
  }
  when(state === products) {
    for (i <- 0 until 3) {
      for (half <- 0 until 2) {
        val pitchHalf = pitches(i)(16 * half + 15, 16 * half)
        startLowParts(i)(half) := descriptor.originY(7, 0) * pitchHalf
        startHighParts(i)(half) := descriptor.originY(15, 8) * pitchHalf
        endLowParts(i)(half) := lastY(7, 0) * pitchHalf
        endHighParts(i)(half) := lastY(17, 8) * pitchHalf
      }
    }
    state := joinProducts
  }
  when(state === joinProducts) {
    // Exact 8x32 (or 10x32) products in two shorter multiply/add stages.
    // Preserve all pitch bits, including invalid descriptors whose high
    // address carry must be rejected rather than wrapped into an allocation.
    for (i <- 0 until 3) {
      startLow(i) := startLowParts(i)(0) +& (startLowParts(i)(1) << 16)
      startHigh(i) := startHighParts(i)(0) +& (startHighParts(i)(1) << 16)
      endLow(i) := endLowParts(i)(0) +& (endLowParts(i)(1) << 16)
      endHigh(i) := endHighParts(i)(0) +& (endHighParts(i)(1) << 16)
    }
    state := productSum
  }
  when(state === productSum) {
    for (i <- 0 until 3) {
      rowStart(i) := startLow(i) +& (startHigh(i) << 8)
      rowEnd(i) := endLow(i) +& (endHigh(i) << 8)
    }
    state := rowSums
  }
  when(state === rowSums) {
    for (i <- 0 until 3) {
      splitSum(rowStartParts(i), rowStart(i), bases(i))
      splitSum(rowEndParts(i), rowEnd(i), bases(i))
    }
    state := rowCarry
  }
  when(state === rowCarry) {
    for (i <- 0 until 3) {
      startBase(i) := joinSum(rowStartParts(i))
      endBase(i) := joinSum(rowEndParts(i))
    }
    state := ranges
  }
  when(state === ranges) {
    for (i <- 0 until 3) {
      splitSum(rangeStartParts(i), startBase(i), columnStart(i))
      splitSum(rangeEndParts(i), endBase(i), columnEnd(i))
    }
    state := rangeCarry
  }
  when(state === rangeCarry) {
    for (i <- 0 until 3) {
      start(i) := joinSum(rangeStartParts(i))
      end(i) := joinSum(rangeEndParts(i))
    }
    state := validate
  }
  when(state === validate) {
    val bounds = (0 until 3).map(i => end(i) <= limits(i) && limits(i) <= (BigInt(1) << 32).U)
    def disjoint(a: Int, b: Int): Bool = end(a) <= start(b) || end(b) <= start(a)
    result := basicValid && bounds(0) && bounds(1) && disjoint(0, 1) &&
      (!descriptor.resolve || (bounds(2) && disjoint(2, 0) && disjoint(2, 1)))
    state := complete
  }
  when(io.out.fire) { state := idle }
}
