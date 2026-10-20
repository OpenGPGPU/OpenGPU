package opengpu.graphics

import chisel3._
import chisel3.util._

/** Base-level bilinear metadata. Filtering and memory ownership stay in TextureUnit. */
class TexturePreparedSample extends Bundle {
  val addresses = Vec(4, UInt(32.W))
  val weights = Vec(4, UInt(17.W))
}

class TextureCoordinateRequest(laneWidth: Int, coordinatePolicyInput: Boolean) extends Bundle {
  def this(laneWidth: Int) = this(laneWidth, false)
  val lane = UInt(laneWidth.W)
  val u = UInt(32.W)
  val v = UInt(32.W)
  val base = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val clamp = Bool()
  val coordinatePolicy = if (coordinatePolicyInput) Some(new TextureCoordinatePolicy) else None
}

class TextureCoordinateResult(laneWidth: Int, coordinatePolicyInput: Boolean) extends Bundle {
  def this(laneWidth: Int) = this(laneWidth, false)
  val lane = UInt(laneWidth.W)
  val sample = new TexturePreparedSample
  val coordinateFault = if (coordinatePolicyInput) Some(Bool()) else None
}

/** Six elastic stages preserve the sampler's arithmetic register cuts while
  * decoding later vector lanes during the current lane's fetch/filter work.
  * This unit issues no memory requests and keeps no cross-instruction state.
  */
class TextureCoordinatePipeline(laneWidth: Int, coordinatePolicyInput: Boolean) extends Module {
  def this(laneWidth: Int) = this(laneWidth, false)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureCoordinateRequest(laneWidth, coordinatePolicyInput)))
    val out = Decoupled(new TextureCoordinateResult(laneWidth, coordinatePolicyInput))
    val busy = Output(Bool())
  })
  private class Binding extends Bundle {
    val lane = UInt(laneWidth.W)
    val base = UInt(32.W)
    val texWidth = UInt(14.W)
    val texHeight = UInt(14.W)
    val clamp = Bool()
    val clampV = if (coordinatePolicyInput) Some(Bool()) else None
    val fault = if (coordinatePolicyInput) Some(Bool()) else None
  }
  private class Biased extends Bundle {
    val texture = new Binding
    val x = UInt(24.W)
    val y = UInt(24.W)
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
    val fault = if (coordinatePolicyInput) Some(Bool()) else None
  }
  private val captured = Queue(io.in, 1, pipe = true, flow = false)
  // Keep the bounded 17x14 product and half-texel subtraction on different
  // register stages. Metadata remains elastic and preserves lane ownership.
  private val scale = Wire(Decoupled(new Biased))
  scale.valid := captured.valid
  captured.ready := scale.ready
  scale.bits.texture.lane := captured.bits.lane
  scale.bits.texture.base := captured.bits.base
  scale.bits.texture.texWidth := captured.bits.texWidth
  scale.bits.texture.texHeight := captured.bits.texHeight
  if (coordinatePolicyInput) {
    val policy = captured.bits.coordinatePolicy.get
    scale.bits.texture.clamp := policy.wrapU =/= TextureCoordinatePolicy.repeat.U
    scale.bits.texture.clampV.get := policy.wrapV =/= TextureCoordinatePolicy.repeat.U
    scale.bits.texture.fault.get := !TextureCoordinatePolicy.valid(policy) ||
      captured.bits.texWidth === 0.U || captured.bits.texHeight === 0.U
    scale.bits.x := TextureCoordinates.scale(captured.bits.u, captured.bits.texWidth, policy.wrapU, policy.signed)
    scale.bits.y := TextureCoordinates.scale(captured.bits.v, captured.bits.texHeight, policy.wrapV, policy.signed)
  } else {
    scale.bits.texture.clamp := captured.bits.clamp
    scale.bits.x := TextureCoordinates.scale(captured.bits.u, captured.bits.texWidth, captured.bits.clamp)
    scale.bits.y := TextureCoordinates.scale(captured.bits.v, captured.bits.texHeight, captured.bits.clamp)
  }
  private val scaled = Queue(scale, 1, pipe = true, flow = false)
  private val bias = Wire(Decoupled(new Biased))
  bias.valid := scaled.valid
  scaled.ready := bias.ready
  bias.bits.texture := scaled.bits.texture
  bias.bits.x := TextureCoordinates.bias(scaled.bits.x)
  bias.bits.y := TextureCoordinates.bias(scaled.bits.y)
  private val biased = Queue(bias, 1, pipe = true, flow = false)

  private val range = Wire(Decoupled(new Ranged))
  range.valid := biased.valid
  biased.ready := range.ready
  range.bits.texture := biased.bits.texture
  range.bits.x := TextureCoordinates.range(biased.bits.x, biased.bits.texture.texWidth, biased.bits.texture.clamp)
  range.bits.y := TextureCoordinates.range(biased.bits.y, biased.bits.texture.texHeight, biased.bits.texture.clampV.getOrElse(biased.bits.texture.clamp))
  private val ranged = Queue(range, 1, pipe = true, flow = false)

  private val index = Wire(Decoupled(new Indexed))
  index.valid := ranged.valid
  ranged.ready := index.ready
  index.bits.fault.foreach(_ := ranged.bits.texture.fault.get)
  index.bits.lane := ranged.bits.texture.lane
  index.bits.base := ranged.bits.texture.base
  index.bits.texWidth := ranged.bits.texture.texWidth
  index.bits.x := VecInit(TextureCoordinates.indices(ranged.bits.x, ranged.bits.texture.texWidth, ranged.bits.texture.clamp))
  index.bits.y := VecInit(TextureCoordinates.indices(ranged.bits.y, ranged.bits.texture.texHeight, ranged.bits.texture.clampV.getOrElse(ranged.bits.texture.clamp)))
  private val fx = ranged.bits.x(7, 0)
  private val fy = ranged.bits.y(7, 0)
  index.bits.weights(0) := (256.U - fx) * (256.U - fy)
  index.bits.weights(1) := fx * (256.U - fy)
  index.bits.weights(2) := (256.U - fx) * fy
  index.bits.weights(3) := fx * fy
  private val indexed = Queue(index, 1, pipe = true, flow = false)

  private val addressed = Wire(Decoupled(new TextureCoordinateResult(laneWidth, coordinatePolicyInput)))
  addressed.valid := indexed.valid
  indexed.ready := addressed.ready
  addressed.bits.lane := indexed.bits.lane
  addressed.bits.coordinateFault.foreach(_ := indexed.bits.fault.get)
  addressed.bits.sample.weights := indexed.bits.weights
  if (coordinatePolicyInput) {
    when(indexed.bits.fault.get) { addressed.bits.sample.weights := VecInit(Seq.fill(4)(0.U(17.W))) }
  }
  for (tap <- 0 until 4) {
    addressed.bits.sample.addresses(tap) := indexed.bits.base +
      ((indexed.bits.y(tap / 2) * indexed.bits.texWidth + indexed.bits.x(tap % 2)) << 2)
    if (coordinatePolicyInput) {
      when(indexed.bits.fault.get) { addressed.bits.sample.addresses(tap) := 0.U }
    }
  }
  io.out <> Queue(addressed, 1, pipe = true, flow = false)

  private val count = RegInit(0.U(3.W))
  when(io.in.fire =/= io.out.fire) {
    count := Mux(io.in.fire, count + 1.U, count - 1.U)
  }
  io.busy := count =/= 0.U
}
