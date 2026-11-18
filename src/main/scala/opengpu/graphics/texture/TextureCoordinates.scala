package opengpu.graphics

import chisel3._
import opengpu.util.{BlockPrefixAdder,CarrySaveMultiplier}

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
  def scale(coord: UInt, extent: UInt, clamp: Bool): UInt =
    scale(coord,extent,Mux(clamp,TextureCoordinatePolicy.clamp.U,TextureCoordinatePolicy.repeat.U),false.B)

  /** Reduce Q16.16 periods before multiplication without a divider. Mirrored
    * coordinates round the reflected product upward: reflection reverses the
    * original floor direction, preserving the unfurled integer-filter weights.
    */
  def scale(coord: UInt,extent: UInt,mode: UInt,signed: Bool): UInt={
    val reflected=mode===TextureCoordinatePolicy.mirroredRepeat.U && coord(16)
    val clamping=mode=/=TextureCoordinatePolicy.repeat.U && mode=/=TextureCoordinatePolicy.mirroredRepeat.U
    val low=CarrySaveMultiplier(coord(15,0),extent,false,false)>>8
    val period=extent<<8
    // ceil(((65536-r)*n)/256) = 256*n-floor(r*n/256).
    // Reflect after multiplication, avoiding a subtract/compare/mux before it.
    val adjusted=Module(new BlockPrefixAdder(23))
    adjusted.io.a:=Mux(reflected,~low.pad(23),low)
    adjusted.io.b:=Mux(reflected || (clamping && coord(16)),period,0.U)
    adjusted.io.carryIn:=reflected
    val saturated=Module(new BlockPrefixAdder(23))
    saturated.io.a:=period;saturated.io.b:=extent<<7;saturated.io.carryIn:=false.B
    // Every legal result fits 23 bits: the largest clamp value is 1.5*16383*256.
    // The reflected subtraction's carry-out is deliberately outside that value.
    val value=adjusted.io.sum(22,0)
    val clampValue=Mux(signed && coord(31),0.U,Mux(coord>=98304.U,saturated.io.sum(22,0),value))
    Mux(clamping,clampValue,value)
  }

  def bias(scaled: UInt): UInt = {
    val result = Module(new BlockPrefixAdder(24))
    result.io.a:=scaled;result.io.b:=((BigInt(1)<<24)-128).U;result.io.carryIn:=false.B
    result.io.sum(23,0)
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
