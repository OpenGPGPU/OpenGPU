package opengpu.graphics

import chisel3._
import chisel3.util._

/** One native owner leases one shared request through response retirement.
  * Request and response offers remain immutable. Invalidation is an event;
  * an owner invalidated before its held request is accepted emits another
  * event when that request drains, preventing late cache publication.
  */
class NativeRgba8TextureClientIO extends Bundle {
  val request = Decoupled(new TextureRgba8GroupRequest)
  val response = Flipped(Decoupled(new NativeRgba8TextureResult))
  val cancelGroup = Valid(new BankedTextureGroupCancel)
  val invalidate = Output(Bool())
}

/** Renderer clients retain individual CU placement across nested task routers. */
class NativeRgba8TextureRendererPorts(numComputeUnits: Int, clients: Int) extends Bundle {
  val client = Vec(clients, new NativeRgba8TextureClientIO)
  val cuMask = Output(Vec(clients, UInt(numComputeUnits.W)))
}

/** A checked level/sampler snapshot for same-binding nonmip RGBA8 lanes.
  * Task-owned snapshot caching retains it only until invalidation/cancellation
  * or the next trusted launch. Implicit reuse needs the additional proof below.
  */
class NativeRgba8TextureLaneTemplate extends Bundle {
  val group = new TextureRgba8GroupRequest
  val swizzle = new TextureImageSwizzleState
  /** A validated single level with identical nonmip min/mag filtering makes
    * the selected level/filter independent of finite implicit derivatives.
    * Meaningful only with a valid template, which checks those filter rules.
    */
  val lodIndependent = Bool()
}
