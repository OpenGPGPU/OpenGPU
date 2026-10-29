package opengpu.graphics

import chisel3._
import chisel3.util._

/** A resolved image-unit snapshot. Its parent must obtain the level view from
  * checked, immutable GPU descriptors and retain that snapshot and its VM lease
  * through the eventual memory result. This is not a descriptor-memory reader.
  */
class ShaderImageBindingSnapshot extends Bundle {
  val identity = new GraphicsWorkIdentity
  val unitIndex = UInt(32.W)
  val bound = Bool()
  val complete = Bool()
  val stageMask = UInt(6.W)
  val readable = Bool()
  val writable = Bool()
  val storageFormat = UInt(TextureImageFormat.width.W)
  val imageFormat = UInt(TextureImageFormat.width.W)
  val compatibleBySize = Bool()
  val level = UInt(32.W)
  val baseLevel = UInt(32.W)
  val maxLevel = UInt(32.W)
  val layered = Bool()
  val layer = UInt(32.W)
  val view = new TextureImageLevelView
}

class ShaderImageBindingAddressRequest extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val unitIndex = UInt(32.W)
  val stage = UInt(GraphicsStage.width.W)
  val isWrite = Bool()
  val shaderTarget = UInt(3.W)
  val colorType = UInt(2.W)
  val hasFormatQualifier = Bool()
  val shaderFormat = UInt(TextureImageFormat.width.W)
  /** Signed shader coordinates retain their complete two's-complement bits.
    * Negative coordinates compare out of bounds before any narrowing.
    */
  val x = UInt(32.W)
  val y = UInt(32.W)
  val z = UInt(32.W)
  val imageBinding = new ShaderImageBindingSnapshot
}

object ShaderImageBindingAddressStatus {
  val ok = 0; val descriptor = 1; val identity = 2
  val permission = 3; val bounds = 4; val cancelled = 5; val shaderType = 6
}

class ShaderImageBindingAddressResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val unitIndex = UInt(32.W)
  val isWrite = Bool()
  val format = UInt(TextureImageFormat.width.W)
  val colorType = UInt(2.W)
  val validTexel = Bool()
  val address = UInt(32.W)
  val allocationBase = UInt(32.W)
  val allocationLimit = UInt(33.W)
  val status = UInt(3.W)
}

/** Resolve image-unit semantics before an image memory owner may publish a
  * request. GLES 3.1 section 8.22 distinguishes invalid texels (zero/no-op) from
  * undefined permission/type mismatches; this implementation rejects the latter.
  * Layered array/cube/3D coordinates and single-layer 2D views share the existing
  * full-width level address checks. No filtering, sampler state or swizzle applies.
  */
class ShaderImageBindingAddress(p: TextureImageAddressConfig = TextureImageAddressConfig(),
    imageUnits: Int = 4) extends Module {
  require(imageUnits >= 4)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ShaderImageBindingAddressRequest))
    val out = Decoupled(new ShaderImageBindingAddressResult)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, waitAddress, finish, complete = Value }
  private val phase = RegInit(State.idle)
  private val request = Reg(new ShaderImageBindingAddressRequest)
  private val result = Reg(new ShaderImageBindingAddressResult)
  private val status = RegInit(0.U(3.W))
  private val address = Module(new TextureImageAddressUnit(p, pipelineBounds = true, pipelineProducts = true))
  private val binding = request.imageBinding
  private val view = binding.view
  private val active = phase =/= State.idle && phase =/= State.complete
  private val stop = status =/= 0.U || io.cancel
  private val multiLayer = view.target === TextureImageTarget.array2D.U ||
    view.target === TextureImageTarget.cube.U || view.target === TextureImageTarget.image3D.U
  private val effectiveTarget = Mux(multiLayer && !binding.layered, TextureImageTarget.image2D.U, view.target)
  private val selectedLayer = Mux(binding.layered, request.z, binding.layer)

  io.in.ready := phase === State.idle && !io.cancel
  io.out.valid := phase === State.complete
  io.out.bits := result
  io.busy := phase =/= State.idle
  address.io.in.valid := phase === State.offer
  address.io.in.bits := 0.U.asTypeOf(address.io.in.bits)
  address.io.in.bits.tag := request.tag
  address.io.in.bits.view := view
  address.io.in.bits.x := request.x
  address.io.in.bits.y := request.y
  address.io.in.bits.z := Mux(view.target === TextureImageTarget.image3D.U, selectedLayer, 0.U)
  address.io.in.bits.layer := Mux(view.target === TextureImageTarget.array2D.U, selectedLayer, 0.U)
  address.io.in.bits.face := Mux(view.target === TextureImageTarget.cube.U, selectedLayer(2, 0), 0.U)
  address.io.out.ready := phase === State.waitAddress

  when(active && io.cancel && status === 0.U) { status := ShaderImageBindingAddressStatus.cancelled.U }
  switch(phase) {
    is(State.idle) {
      when(io.in.fire) {
        assert(!address.io.busy, "new image binding admitted before address drain")
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.identity := io.in.bits.identity; result.tag := io.in.bits.tag
        result.unitIndex := io.in.bits.unitIndex; result.isWrite := io.in.bits.isWrite
        result.format := io.in.bits.imageBinding.imageFormat; result.colorType := io.in.bits.colorType
        status := 0.U; phase := State.check
      }
    }
    is(State.check) {
      val validLevel = binding.level < 16.U && binding.level >= binding.baseLevel && binding.level <= binding.maxLevel
      val validImage = binding.complete && validLevel &&
        ShaderImageFormat.compatible(binding.storageFormat, binding.imageFormat, binding.compatibleBySize)
      val permittedStage = request.stage < 6.U && binding.stageMask(Mux(request.stage < 6.U, request.stage, 0.U))
      val permittedAccess = Mux(request.isWrite, binding.writable, binding.readable)
      val shaderType = request.shaderTarget === effectiveTarget &&
        request.colorType === TextureImageFormat.kind(binding.imageFormat) &&
        (request.isWrite || request.hasFormatQualifier) &&
        (!request.hasFormatQualifier || request.shaderFormat === binding.imageFormat)
      // Cube faces must be checked before narrowing to the three-bit address
      // port. Array layers and volume slices remain full-width at that port.
      val invalidFace = view.target === TextureImageTarget.cube.U && selectedLayer >= 6.U
      when(stop) { phase := State.finish }
        .elsewhen(request.unitIndex >= imageUnits.U) {
          status := ShaderImageBindingAddressStatus.descriptor.U; phase := State.finish
        }.elsewhen(!binding.bound) { phase := State.finish }
        .elsewhen(binding.identity.asUInt =/= request.identity.asUInt || binding.unitIndex =/= request.unitIndex) {
          status := ShaderImageBindingAddressStatus.identity.U; phase := State.finish
        }.elsewhen(!permittedStage || !permittedAccess) {
          status := ShaderImageBindingAddressStatus.permission.U; phase := State.finish
        }.elsewhen(!validImage) { phase := State.finish }
        .elsewhen(view.level =/= binding.level || view.block4x4 ||
          view.blockBytes =/= TextureImageFormat.bytes(binding.storageFormat)) {
          status := ShaderImageBindingAddressStatus.descriptor.U; phase := State.finish
        }.elsewhen(!shaderType) {
          status := ShaderImageBindingAddressStatus.shaderType.U; phase := State.finish
        }.elsewhen(invalidFace) { phase := State.finish }
        .otherwise { phase := State.offer }
    }
    is(State.offer) { when(address.io.in.fire) { phase := State.waitAddress } }
    is(State.waitAddress) {
      when(address.io.out.fire) {
        assert(address.io.out.bits.tag === request.tag)
        when(address.io.out.bits.status === TextureImageAddressStatus.bounds.U) {
          status := ShaderImageBindingAddressStatus.bounds.U
        }.elsewhen(address.io.out.bits.status === TextureImageAddressStatus.invalidView.U) {
          status := ShaderImageBindingAddressStatus.descriptor.U
        }.elsewhen(address.io.out.bits.status === TextureImageAddressStatus.ok.U && !stop) {
          assert(address.io.out.bits.bytes === TextureImageFormat.bytes(binding.imageFormat))
          result.validTexel := true.B; result.address := address.io.out.bits.address
          result.allocationBase := view.allocationBase; result.allocationLimit := view.allocationLimit
        }
        phase := State.finish
      }
    }
    is(State.finish) {
      assert(!address.io.busy, "image binding completion escaped address drain")
      result.status := Mux(io.cancel && status === 0.U, ShaderImageBindingAddressStatus.cancelled.U, status)
      when(stop) {
        result.validTexel := false.B; result.address := 0.U
        result.allocationBase := 0.U; result.allocationLimit := 0.U
      }
      phase := State.complete
    }
    is(State.complete) { when(io.out.fire) { phase := State.idle } }
  }
}
