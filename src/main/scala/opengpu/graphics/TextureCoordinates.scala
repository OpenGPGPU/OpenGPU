package opengpu.graphics

import chisel3._
import chisel3.util.Cat

/** Exact unsigned Q16.16 bilinear coordinates for any nonzero 14-bit extent. */
private[graphics] object TextureCoordinates {
  def normalize(coord: UInt, clamp: Bool): UInt = {
    // Whole normalized periods disappear under REPEAT, for every extent.
    // Under CLAMP, u >= 1.5 is beyond the last texel even at extent one.
    // Bounding before multiplication avoids interpreting a large unsigned
    // product as a negative biased coordinate.
    Mux(clamp, Mux(coord > 98304.U, 98304.U(17.W), coord(16, 0)),
      Cat(0.U(1.W), coord(15, 0)))
  }

  def scale(coord: UInt, extent: UInt, clamp: Bool): UInt =
    (normalize(coord, clamp) * extent) >> 8

  def bias(scaled: UInt): UInt = {
    val result = Wire(UInt(24.W))
    result := (Cat(0.U(1.W), scaled).asSInt - 128.S).asUInt
    result
  }

  def range(biased: UInt, extent: UInt, clamp: Bool): UInt = {
    val period = (extent << 8).pad(24)
    val last = period - 1.U
    val negative = biased.asSInt < 0.S
    val clamped = Mux(negative, 0.U, Mux(biased > last, last, biased))
    // Normalizing the input bounds the repeat coordinate to [-128,period).
    // One addition handles the half-texel phase; no divider is needed.
    val repeated = Mux(negative, biased + period, biased)
    Mux(clamp, clamped, repeated)(21, 0)
  }

  def indices(value: UInt, extent: UInt, clamp: Bool): Seq[UInt] = {
    val first = value(21, 8)
    val next = first + 1.U
    Seq(first, Mux(next === extent, Mux(clamp, first, 0.U), next))
  }
}
