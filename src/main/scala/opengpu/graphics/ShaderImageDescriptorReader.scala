package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** GIU1 is an immutable image-unit record, independent of Bundle packing.
  * All fields occupy little-endian 32-bit words; narrow padding and reserved
  * words are zero. A level view belongs to the same pinned publication as the
  * permissions and storage format. The table owner supplies its VM lease.
  */
object ShaderImageDescriptorLayout {
  val magic = BigInt("47495501", 16); val words = 64; val bytes = 256
  def fields(d: ShaderImageBindingSnapshot): Seq[NativeTypedGraphicsLayout.Field] = {
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context", 3, d.identity.context), Field("identity.pass", 4, d.identity.pass),
      Field("identity.generation", 5, d.identity.generation), Field("unitIndex", 6, d.unitIndex),
      Field("bound", 7, d.bound), Field("complete", 8, d.complete), Field("stageMask", 9, d.stageMask),
      Field("readable", 10, d.readable), Field("writable", 11, d.writable),
      Field("storageFormat", 12, d.storageFormat), Field("imageFormat", 13, d.imageFormat),
      Field("compatibleBySize", 14, d.compatibleBySize), Field("level", 15, d.level),
      Field("baseLevel", 16, d.baseLevel), Field("maxLevel", 17, d.maxLevel),
      Field("layered", 18, d.layered), Field("layer", 19, d.layer),
      Field("view.target", 20, d.view.target), Field("view.level", 21, d.view.level),
      Field("view.allocationBase", 22, d.view.allocationBase), Field("view.allocationLimit", 23, d.view.allocationLimit),
      Field("view.base", 25, d.view.base), Field("view.texWidth", 26, d.view.texWidth),
      Field("view.texHeight", 27, d.view.texHeight), Field("view.texDepth", 28, d.view.texDepth),
      Field("view.layers", 29, d.view.layers), Field("view.rowPitch", 30, d.view.rowPitch),
      Field("view.slicePitch", 32, d.view.slicePitch), Field("view.layerPitch", 34, d.view.layerPitch),
      Field("view.block4x4", 36, d.view.block4x4), Field("view.blockBytes", 37, d.view.blockBytes))
  }
}

class ShaderImageDescriptorRequest extends Bundle {
  // The shared table envelope carries no stride: GIU1 fixes it at 256 bytes.
  val table = new TextureImageLevelTable
  val unitIndex = UInt(32.W)
  val tag = UInt(32.W)
}

class ShaderImageDescriptorResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val unitIndex = UInt(32.W)
  val tag = UInt(32.W)
  val status = UInt(3.W)
  val validSnapshot = Bool()
  val imageBinding = new ShaderImageBindingSnapshot
  val readLines = UInt(3.W)
  val hit = Bool()
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

/** Read one complete image-unit descriptor before exposing any binding fields.
  * A single outstanding read keeps reply ownership explicit. The immutable
  * one-entry cache is keyed by the complete table envelope and unit; callers
  * invalidate on rebinding, even if addresses and identity are reused. A task
  * pins the publication/VM until its eventual image memory operation retires.
  * Binding semantics and texel bounds are checked by ShaderImageAccess.
  */
class ShaderImageDescriptorReader(config: GpuConfig = GpuConfig(), imageUnits: Int = 4) extends Module {
  require(config.xLen == 32 && imageUnits >= 4)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageDescriptorRequest))
    val out = Decoupled(new ShaderImageDescriptorResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val invalidate = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, waitReply, advance, verify, finish, complete = Value }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageDescriptorRequest)
  private val snapshot = Reg(new ShaderImageBindingSnapshot)
  private val result = Reg(new ShaderImageDescriptorResult)
  private val memory = Reg(new ComputeMemoryRequest(config, 64, 1))
  private val start = Reg(UInt(32.W))
  private val line = RegInit(0.U(2.W))
  private val lines = RegInit(0.U(3.W))
  private val pending = RegInit(false.B)
  private val status = RegInit(0.U(3.W))
  private val malformed = RegInit(false.B)
  private val fault = RegInit(false.B)
  private val faultAddress = RegInit(0.U(32.W))
  private val cached = RegInit(false.B)
  private val cachedKey = Reg(new ShaderImageDescriptorRequest)
  private val cachedSnapshot = Reg(new ShaderImageBindingSnapshot)
  private val noFill = RegInit(false.B)
  private val hit = RegInit(false.B)
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel

  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete; io.out.bits := result
  io.busy := phase =/= State.idle
  io.memoryRequest.valid := phase === State.offer; io.memoryRequest.bits := memory
  io.memoryResponse.ready := pending && io.memoryResponse.bits.transactionId === 0.U
  when(io.invalidate) { cached := false.B; when(active) { noFill := true.B } }
  when(active && io.cancel && status === 0.U) { status := ShaderImageAccessStatus.cancelled.U }
  when(io.cancel) { cached := false.B }
  when(io.memoryRequest.fire) { assert(!pending); pending := true.B; phase := State.waitReply }

  private val fields = ShaderImageDescriptorLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot, fields, ShaderImageDescriptorLayout.words)
  private val bad = NativeTypedGraphicsLayout.receive(fields, ShaderImageDescriptorLayout.words,
    ShaderImageDescriptorLayout.magic, line, io.memoryResponse.bits.readData,
    io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop)
  when(io.memoryResponse.fire) {
    pending := false.B; lines := lines + 1.U; malformed := malformed || bad; phase := State.advance
    when(io.memoryResponse.bits.fault) {
      status := ShaderImageAccessStatus.memory.U; fault := true.B; faultAddress := memory.address
    }
  }
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!pending, "image descriptor admission abandoned a reply")
        request := io.in.bits; snapshot := 0.U.asTypeOf(snapshot)
        status := 0.U; malformed := false.B; fault := false.B; faultAddress := 0.U
        line := 0.U; lines := 0.U; hit := false.B; noFill := io.invalidate; phase := State.check
      }
    }
    is(State.check) {
      val a = request.table
      val begin = a.base.pad(64) + (request.unitIndex.pad(64) << 8)
      val end = begin + ShaderImageDescriptorLayout.bytes.U
      val legal = a.base(5, 0) === 0.U && a.limit(5, 0) === 0.U &&
        a.limit > a.base && a.limit <= (BigInt(1) << 32).U &&
        (a.limit - a.base)(7, 0) === 0.U && end <= a.limit
      val same = cachedKey.table.asUInt === a.asUInt && cachedKey.unitIndex === request.unitIndex
      start := begin(31, 0)
      when(stop) { phase := State.finish }
        .elsewhen(a.version =/= 1.U || request.unitIndex >= imageUnits.U) {
          status := ShaderImageAccessStatus.descriptor.U; phase := State.finish
        }.elsewhen(!legal) { status := ShaderImageAccessStatus.bounds.U; phase := State.finish }
        .elsewhen(cached && same && !noFill && !io.invalidate) {
          snapshot := cachedSnapshot; hit := true.B; phase := State.finish
        }.otherwise {
          cached := false.B; memory := 0.U.asTypeOf(memory)
          memory.address := begin(31, 0); memory.sizeLog2 := 6.U
          memory.byteMask := Fill(64, 1.U(1.W)); memory.cachePolicy := CachePolicy.uncached
          phase := State.offer
        }
    }
    is(State.advance) {
      when(stop) { phase := State.finish }
        .elsewhen(line === 3.U) { phase := State.verify }
        .otherwise { line := line + 1.U; memory.address := start + ((line +& 1.U) << 6); phase := State.offer }
    }
    is(State.verify) {
      when(!stop && malformed) { status := ShaderImageAccessStatus.descriptor.U }
        .elsewhen(!stop && (snapshot.identity.asUInt =/= request.table.identity.asUInt || snapshot.unitIndex =/= request.unitIndex)) {
          status := ShaderImageAccessStatus.identity.U
        }
      phase := State.finish
    }
    is(State.finish) {
      assert(!pending && !io.memoryRequest.valid, "image descriptor result escaped memory drain")
      result := 0.U.asTypeOf(result)
      result.identity := request.table.identity; result.unitIndex := request.unitIndex; result.tag := request.tag
      result.status := Mux(io.cancel && status === 0.U, ShaderImageAccessStatus.cancelled.U, status)
      result.readLines := lines; result.hit := hit
      result.faultAddressValid := fault; result.faultAddress := faultAddress
      when(!stop) {
        result.validSnapshot := true.B; result.imageBinding := snapshot
        when(!noFill && !io.invalidate) { cached := true.B; cachedKey := request; cachedSnapshot := snapshot }
      }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
