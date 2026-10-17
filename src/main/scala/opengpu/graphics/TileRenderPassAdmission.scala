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
  private val Seq(idle, coordinates, products, productSum, rowSums, ranges, validate, complete) = Enum(8)
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
  private val startBase = Reg(Vec(3, UInt(50.W)))
  private val endBase = Reg(Vec(3, UInt(51.W)))
  private val start = Reg(Vec(3, UInt(51.W)))
  private val end = Reg(Vec(3, UInt(52.W)))
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
      startLow(i) := descriptor.originY(7, 0) * pitches(i)
      startHigh(i) := descriptor.originY(15, 8) * pitches(i)
      endLow(i) := lastY(7, 0) * pitches(i)
      endHigh(i) := lastY(17, 8) * pitches(i)
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
      startBase(i) := bases(i) +& rowStart(i)
      endBase(i) := bases(i) +& rowEnd(i)
    }
    state := ranges
  }
  when(state === ranges) {
    for (i <- 0 until 3) {
      start(i) := startBase(i) +& columnStart(i)
      end(i) := endBase(i) +& columnEnd(i)
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
