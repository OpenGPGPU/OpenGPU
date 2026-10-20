package opengpu.graphics

import chisel3._
import chisel3.util.Cat

/** Per-axis normalized-coordinate policy for a descriptor-owning sampler.
  * Mode3 is reserved. Legacy users retain their Boolean wrap input.
  */
class TextureCoordinatePolicy extends Bundle {
  val wrapU=UInt(2.W);val wrapV=UInt(2.W);val signed=Bool()
}
object TextureCoordinatePolicy {
  val repeat=0;val clamp=1;val mirroredRepeat=2
  def valid(p: TextureCoordinatePolicy): Bool=p.wrapU<=mirroredRepeat.U && p.wrapV<=mirroredRepeat.U
}

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

  /** Reduce Q16.16 periods before multiplication without a divider. Mirrored
    * coordinates round the reflected product upward: reflection reverses the
    * original floor direction, preserving the unfurled integer-filter weights.
    */
  def scale(coord: UInt,extent: UInt,mode: UInt,signed: Bool): UInt={
    val phase=coord(16,0)
    val reflected=mode===TextureCoordinatePolicy.mirroredRepeat.U && phase(16)
    val mirror=Mux(phase(16),(131072.U(18.W)-phase)(16,0),phase)
    val clamped=Mux(signed && coord(31),0.U(17.W),normalize(coord,true.B))
    val reduced=Mux(mode===TextureCoordinatePolicy.repeat.U,Cat(0.U(1.W),coord(15,0)),
      Mux(mode===TextureCoordinatePolicy.mirroredRepeat.U,mirror,clamped))
    val product=reduced*extent
    (product>>8)+&(reflected && product(7,0).orR)
  }

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
