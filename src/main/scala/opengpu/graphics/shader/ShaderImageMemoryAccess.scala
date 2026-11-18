package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** A texel resolved by the task's image descriptor/address owner. Invalid image
  * accesses return zero or perform no store. The parent retains descriptor,
  * translation and coherence ownership until the public result retires.
  */
class ShaderImageMemoryRequest extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val validTexel = Bool()
  val address = UInt(32.W)
  val allocationBase = UInt(32.W)
  val allocationLimit = UInt(33.W)
  val format = UInt(TextureImageFormat.width.W)
  val colorType = UInt(2.W)
  val isWrite = Bool()
  val rgba = Vec(4, UInt(32.W))
}

class ShaderImageMemoryResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val isWrite = Bool()
  val rgba = Vec(4, UInt(32.W))
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

object ShaderImageMemoryStatus {
  val ok = 0; val invalid = 1; val bounds = 2
  val memory = 3; val cancelled = 4; val componentType = 5
}

private[graphics] object ShaderImageLoadDecode {
  // The complete signed-byte domain. Each division rounds directly to FP32;
  // both -128 and -127 represent the required clamped value -1.
  private val snorm = Vector.tabulate(256) { raw =>
    val signed = if (raw < 128) raw else raw - 256
    BigInt(java.lang.Float.floatToRawIntBits(math.max(-1.0f, signed.toFloat / 127.0f)).toLong & 0xffffffffL)
  }
  def component(value: UInt, format: UInt, c: UInt): UInt = {
    val bits = TextureImageFormat.componentWidth(format, c)
    val offset = TextureImageFormat.componentOffset(format, c)
    val raw = (value >> offset)(31, 0) & ((1.U(33.W) << bits) - 1.U)(31, 0)
    val signed = ((raw << (32.U - bits))(31, 0).asSInt >> (32.U - bits)).asUInt
    val decoded = MuxLookup(TextureImageFormat.encoding(format), raw)(Seq(
      0.U -> TypedColorDecode.unorm(raw, bits),
      2.U -> signed,
      3.U -> TypedColorDecode.half(raw(15, 0)),
      6.U -> VecInit(snorm.map(_.U(32.W)))(raw(7, 0))))
    val missing = Mux(c === 3.U, Mux(TextureImageFormat.kind(format) === 0.U, "h3f800000".U, 1.U), 0.U)
    Mux(bits === 0.U, missing, decoded)
  }
}

/** Serial image load/store ownership for all thirteen GLES 3.1 image formats.
  * A texel may span two cache lines. Validate both complete backing lines before
  * offering either, and retain every offered request through its acknowledgement.
  * Stores are masked; reads use the complete checked backing line. Cancellation
  * cannot retract an offered store or undo an acknowledged store. It drains the
  * owned transaction and suppresses any subsequent line. A memory fault remains
  * observable even if cancellation arrived while that transaction was pending.
  * This component does not implement image atomics or descriptor/VM admission.
  */
class ShaderImageMemoryAccess(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageMemoryRequest))
    val out = Decoupled(new ShaderImageMemoryResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, check, offerEncode, waitEncode, prepareLine, waitLine, decode, finish, complete = Value
  }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageMemoryRequest)
  private val result = Reg(new ShaderImageMemoryResult)
  private val status = RegInit(0.U(3.W))
  private val payload = Reg(UInt(128.W))
  private val bytes = Reg(UInt(5.W))
  private val firstCount = Reg(UInt(5.W))
  private val crossesLine = Reg(Bool())
  private val second = RegInit(false.B)
  private val component = RegInit(0.U(2.W))
  private val offered = RegInit(false.B)
  private val pending = RegInit(false.B)
  private val memory = Reg(new ComputeMemoryRequest(config, 64, 1))
  private val encoder = Module(new ShaderImageStoreEncoder)
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  private val firstLine = Cat(request.address(31, 6), 0.U(6.W))
  private val line = Mux(second, (firstLine.pad(33) + 64.U)(31, 0), firstLine)
  private val offset = Mux(second, 0.U(6.W), request.address(5, 0))
  private val count = Mux(second, bytes - firstCount, firstCount)

  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete
  io.out.bits := result
  io.busy := phase =/= State.idle
  io.memoryRequest.valid := offered
  io.memoryRequest.bits := memory
  io.memoryResponse.ready := pending && io.memoryResponse.bits.transactionId === 0.U
  encoder.io.in.valid := phase === State.offerEncode
  encoder.io.in.bits.format := request.format
  encoder.io.in.bits.colorType := request.colorType
  encoder.io.in.bits.rgba := request.rgba
  encoder.io.in.bits.tag := request.tag
  encoder.io.out.ready := phase === State.waitEncode

  when(active && io.cancel && status === 0.U) { status := ShaderImageMemoryStatus.cancelled.U }
  when(io.memoryRequest.fire) { offered := false.B; pending := true.B }
  when(io.memoryResponse.fire) {
    pending := false.B
    when(io.memoryResponse.bits.fault && (status === 0.U || status === ShaderImageMemoryStatus.cancelled.U)) {
      status := ShaderImageMemoryStatus.memory.U
      result.faultAddressValid := true.B
      result.faultAddress := Mux(second, line, request.address)
    }
    when(!request.isWrite && !io.memoryResponse.bits.fault && !stop) {
      val mask = ((1.U(129.W) << (count << 3)) - 1.U)(127, 0)
      val chunk = (io.memoryResponse.bits.readData >> (offset << 3))(127, 0) & mask
      payload := Mux(second, payload | ((chunk << (firstCount << 3))(127, 0)), chunk)
    }
  }
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!offered && !pending && !encoder.io.busy)
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.identity := io.in.bits.identity; result.tag := io.in.bits.tag
        result.isWrite := io.in.bits.isWrite
        status := 0.U; payload := 0.U; second := false.B; component := 0.U
        phase := State.check
      }
    }
    is(State.check) {
      val size = TextureImageFormat.bytes(request.format)
      val crossing = request.address(5, 0) +& size > 64.U
      val end = firstLine.pad(33) + Mux(crossing, 128.U, 64.U)
      val backing = request.allocationBase(5, 0) === 0.U && request.allocationLimit(5, 0) === 0.U &&
        request.allocationLimit > request.allocationBase && request.allocationLimit <= (BigInt(1) << 32).U &&
        firstLine >= request.allocationBase && end <= request.allocationLimit
      bytes := size; crossesLine := crossing
      firstCount := Mux(crossing, 64.U - request.address(5, 0), size)
      when(stop || !request.validTexel) { phase := State.finish }
        .elsewhen(!ShaderImageFormat.valid(request.format)) { status := ShaderImageMemoryStatus.invalid.U; phase := State.finish }
        .elsewhen(request.colorType =/= TextureImageFormat.kind(request.format)) {
          status := ShaderImageMemoryStatus.componentType.U; phase := State.finish
        }.elsewhen(!backing) { status := ShaderImageMemoryStatus.bounds.U; phase := State.finish }
        .otherwise { phase := Mux(request.isWrite, State.offerEncode, State.prepareLine) }
    }
    is(State.offerEncode) { when(encoder.io.in.fire) { phase := State.waitEncode } }
    is(State.waitEncode) {
      when(encoder.io.out.fire) {
        assert(encoder.io.out.bits.tag === request.tag && encoder.io.out.bits.status === 0.U)
        assert(encoder.io.out.bits.bytes === bytes)
        payload := encoder.io.out.bits.value
        phase := Mux(stop, State.finish, State.prepareLine)
      }
    }
    is(State.prepareLine) {
      assert(!offered && !pending)
      when(stop) { phase := State.finish }
        .otherwise {
          val data = Mux(second, payload >> (firstCount << 3), payload)
          val mask = ((1.U(17.W) << count) - 1.U)(15, 0)
          memory := 0.U.asTypeOf(memory)
          memory.address := line; memory.isWrite := request.isWrite; memory.sizeLog2 := 6.U
          memory.cachePolicy := CachePolicy.uncached
          memory.writeData := (data.pad(512) << (offset << 3))(511, 0)
          memory.byteMask := Mux(request.isWrite, (mask.pad(64) << offset)(63, 0), Fill(64, 1.U(1.W)))
          offered := true.B; phase := State.waitLine
        }
    }
    is(State.waitLine) {
      when(!offered && !pending) {
        when(stop) { phase := State.finish }
          .elsewhen(crossesLine && !second) { second := true.B; phase := State.prepareLine }
          .otherwise { phase := Mux(request.isWrite, State.finish, State.decode) }
      }
    }
    is(State.decode) {
      result.rgba(component) := ShaderImageLoadDecode.component(payload, request.format, component)
      when(stop || component === 3.U) { phase := State.finish }
        .otherwise { component := component + 1.U }
    }
    is(State.finish) {
      assert(!offered && !pending && !encoder.io.busy, "image completion escaped memory/converter drain")
      result.status := Mux(io.cancel && status === 0.U, ShaderImageMemoryStatus.cancelled.U, status)
      when(stop) { result.rgba := 0.U.asTypeOf(result.rgba) }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
