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
