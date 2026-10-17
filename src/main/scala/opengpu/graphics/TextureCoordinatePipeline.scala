package opengpu.graphics

import chisel3._
import chisel3.util._

/** Base-level bilinear metadata. Filtering and memory ownership stay in TextureUnit. */
class TexturePreparedSample extends Bundle {
  val addresses = Vec(4, UInt(32.W))
  val weights = Vec(4, UInt(17.W))
}

class TextureCoordinateRequest(laneWidth: Int) extends Bundle {
  val lane = UInt(laneWidth.W)
  val u = UInt(32.W)
  val v = UInt(32.W)
  val base = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val clamp = Bool()
}

class TextureCoordinateResult(laneWidth: Int) extends Bundle {
  val lane = UInt(laneWidth.W)
  val sample = new TexturePreparedSample
}

/** Six elastic stages preserve the sampler's arithmetic register cuts while
  * decoding later vector lanes during the current lane's fetch/filter work.
  * This unit issues no memory requests and keeps no cross-instruction state.
  */
class TextureCoordinatePipeline(laneWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureCoordinateRequest(laneWidth)))
    val out = Decoupled(new TextureCoordinateResult(laneWidth))
    val busy = Output(Bool())
  })
  private class Binding extends Bundle {
    val lane = UInt(laneWidth.W)
    val base = UInt(32.W)
    val texWidth = UInt(14.W)
    val texHeight = UInt(14.W)
    val clamp = Bool()
  }
  private class Biased extends Bundle {
    val texture = new Binding
    val x = UInt(38.W)
    val y = UInt(38.W)
  }
  private class Ranged extends Bundle {
    val texture = new Binding
    val x = UInt(22.W)
    val y = UInt(22.W)
  }
  private class Indexed extends Bundle {
    val lane = UInt(laneWidth.W)
    val base = UInt(32.W)
    val texWidth = UInt(14.W)
    val x = Vec(2, UInt(14.W))
    val y = Vec(2, UInt(14.W))
    val weights = Vec(4, UInt(17.W))
  }
  private val captured = Queue(io.in, 1, pipe = true, flow = false)
  // Keep the 32x14 product and the half-texel subtraction on different
  // register stages. Metadata remains elastic and preserves lane ownership.
  private val scale = Wire(Decoupled(new Biased))
  scale.valid := captured.valid
  captured.ready := scale.ready
  scale.bits.texture.lane := captured.bits.lane
  scale.bits.texture.base := captured.bits.base
  scale.bits.texture.texWidth := captured.bits.texWidth
  scale.bits.texture.texHeight := captured.bits.texHeight
  scale.bits.texture.clamp := captured.bits.clamp
  scale.bits.x := (captured.bits.u * captured.bits.texWidth) >> 8
  scale.bits.y := (captured.bits.v * captured.bits.texHeight) >> 8
  private val scaled = Queue(scale, 1, pipe = true, flow = false)
  private val bias = Wire(Decoupled(new Biased))
  bias.valid := scaled.valid
  scaled.ready := bias.ready
  bias.bits.texture := scaled.bits.texture
  bias.bits.x := scaled.bits.x - 128.U
  bias.bits.y := scaled.bits.y - 128.U
  private val biased = Queue(bias, 1, pipe = true, flow = false)

  private def axisRange(value: UInt, extent: UInt, clamp: Bool): UInt = {
    val signed = value.asSInt
    val last = ((extent << 8) - 1.U).pad(64)
    val clamped = Mux(signed < 0.S, 0.S(64.W),
      Mux(signed > last.asSInt, last.asSInt, signed.pad(64)))
    Mux(clamp, clamped.asUInt, value.pad(64) & last)(21, 0)
  }
  private val range = Wire(Decoupled(new Ranged))
  range.valid := biased.valid
  biased.ready := range.ready
  range.bits.texture := biased.bits.texture
  range.bits.x := axisRange(biased.bits.x, biased.bits.texture.texWidth, biased.bits.texture.clamp)
  range.bits.y := axisRange(biased.bits.y, biased.bits.texture.texHeight, biased.bits.texture.clamp)
  private val ranged = Queue(range, 1, pipe = true, flow = false)

  private def indices(value: UInt, extent: UInt, clamp: Bool): Seq[UInt] = {
    val last = extent - 1.U
    val raw = value(21, 8)
    val first = Mux(clamp, Mux(raw > last, last, raw), raw)
    val next = first + 1.U
    Seq(first, Mux(clamp, Mux(next > last, last, next), next & last))
  }
  private val index = Wire(Decoupled(new Indexed))
  index.valid := ranged.valid
  ranged.ready := index.ready
  index.bits.lane := ranged.bits.texture.lane
  index.bits.base := ranged.bits.texture.base
  index.bits.texWidth := ranged.bits.texture.texWidth
  index.bits.x := VecInit(indices(ranged.bits.x, ranged.bits.texture.texWidth, ranged.bits.texture.clamp))
  index.bits.y := VecInit(indices(ranged.bits.y, ranged.bits.texture.texHeight, ranged.bits.texture.clamp))
  private val fx = ranged.bits.x(7, 0)
  private val fy = ranged.bits.y(7, 0)
  index.bits.weights(0) := (256.U - fx) * (256.U - fy)
  index.bits.weights(1) := fx * (256.U - fy)
  index.bits.weights(2) := (256.U - fx) * fy
  index.bits.weights(3) := fx * fy
  private val indexed = Queue(index, 1, pipe = true, flow = false)

  private val addressed = Wire(Decoupled(new TextureCoordinateResult(laneWidth)))
  addressed.valid := indexed.valid
  indexed.ready := addressed.ready
  addressed.bits.lane := indexed.bits.lane
  addressed.bits.sample.weights := indexed.bits.weights
  for (tap <- 0 until 4) {
    addressed.bits.sample.addresses(tap) := indexed.bits.base +
      ((indexed.bits.y(tap / 2) * indexed.bits.texWidth + indexed.bits.x(tap % 2)) << 2)
  }
  io.out <> Queue(addressed, 1, pipe = true, flow = false)

  private val count = RegInit(0.U(3.W))
  when(io.in.fire =/= io.out.fire) {
    count := Mux(io.in.fire, count + 1.U, count - 1.U)
  }
  io.busy := count =/= 0.U
}
