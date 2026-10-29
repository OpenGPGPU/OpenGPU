package opengpu.graphics

import chisel3._
import chisel3.util._

/** GLES 3.1 tables 8.27/8.28, using the existing texture storage IDs.
  * Image-unit eligibility is independent of attachment renderability and of
  * sampler filtering. A descriptor owner must also check binding lifetime,
  * target, level, access permissions and the shader variable's declared type.
  */
object ShaderImageFormat {
  val names: Vector[String] = Vector("RGBA32F", "RGBA16F", "R32F",
    "RGBA32UI", "RGBA16UI", "RGBA8UI", "R32UI",
    "RGBA32I", "RGBA16I", "RGBA8I", "R32I", "RGBA8", "RGBA8_SNORM")
  val ids: Vector[Int] = names.map(TextureImageFormat.id)
  def valid(format: UInt): Bool = ids.map(format === _.U).reduce(_ || _)

  /** GL-owned storage uses size compatibility. External storage may instead
    * require the same component-count/width class; numeric kind may differ.
    */
  def compatible(storage: UInt, image: UInt, bySize: Bool): Bool = {
    val sameClass = (0 until 4).map(c =>
      TextureImageFormat.componentWidth(storage, c.U) === TextureImageFormat.componentWidth(image, c.U)).reduce(_ && _)
    valid(storage) && valid(image) && TextureImageFormat.bytes(storage) === TextureImageFormat.bytes(image) &&
      (bySize || sameClass)
  }

  /** Signed normalized byte conversion, with nearest/even rounding. NaNs map
    * to zero; infinities saturate. Magnitude uses the exact existing rational
    * UNORM conversion with seven magnitude bits, then restores the sign.
    */
  def snorm8(raw: UInt): UInt = {
    val magnitude = TypedColorFormat.unorm(Cat(0.U(1.W), raw(30, 0)), 7.U)(7, 0)
    Mux(raw(31), (0.U(8.W) - magnitude)(7, 0), magnitude)
  }
}

class ShaderImageStoreEncodeRequest extends Bundle {
  val format = UInt(TextureImageFormat.width.W)
  val colorType = UInt(2.W)
  val rgba = Vec(4, UInt(32.W))
  val tag = UInt(32.W)
}

class ShaderImageStoreEncodeResult extends Bundle {
  val value = UInt(128.W)
  val byteMask = UInt(16.W)
  val bytes = UInt(5.W)
  val tag = UInt(32.W)
  /** 0 success, 1 unsupported image format, 2 shader component-type mismatch. */
  val status = UInt(2.W)
}

/** Converts one complete shader image texel. It performs no memory operation:
  * the image owner must retain the result until all masked writes acknowledge.
  * R-only formats consume R and never write the unused G/B/A inputs. Existing
  * attachment IDs and advertised capabilities are unchanged.
  */
class ShaderImageStoreEncoder extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageStoreEncodeRequest))
    val out = Decoupled(new ShaderImageStoreEncodeResult)
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, component, complete = Value }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageStoreEncodeRequest)
  private val result = Reg(new ShaderImageStoreEncodeResult)
  private val component = RegInit(0.U(2.W))
  private val bits = TextureImageFormat.componentWidth(request.format, component)
  private val offset = TextureImageFormat.componentOffset(request.format, component)
  private val encoding = TextureImageFormat.encoding(request.format)
  private val raw = request.rgba(component)
  private val converted = MuxLookup(encoding, raw)(Seq(
    0.U -> TypedColorFormat.unorm(raw, bits),
    1.U -> TypedColorFormat.integer(raw, bits, false.B),
    2.U -> TypedColorFormat.integer(raw, bits, true.B),
    3.U -> TypedColorFormat.half(raw),
    6.U -> ShaderImageFormat.snorm8(raw)))
  io.in.ready := phase === State.idle
  io.out.valid := phase === State.complete
  io.out.bits := result
  io.busy := phase =/= State.idle
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.tag := io.in.bits.tag
        component := 0.U
        when(!ShaderImageFormat.valid(io.in.bits.format)) {
          result.status := 1.U
          phase := State.complete
        }.elsewhen(io.in.bits.colorType =/= TextureImageFormat.kind(io.in.bits.format)) {
          result.status := 2.U
          phase := State.complete
        }.otherwise {
          val bytes = TextureImageFormat.bytes(io.in.bits.format)
          result.bytes := bytes
          result.byteMask := ((1.U(17.W) << bytes) - 1.U)(15, 0)
          phase := State.component
        }
      }
    }
    is(State.component) {
      val mask = (((1.U(129.W) << bits) - 1.U) << offset)(127, 0)
      when(bits =/= 0.U) {
        result.value := result.value | ((converted.pad(128) << offset) & mask)
      }
      when(component === 3.U) { phase := State.complete }
        .otherwise { component := component + 1.U }
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
