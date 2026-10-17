package opengpu.command

import chisel3._
import opengpu.config.GpuConfig

/** One unified render command in the submitting context's VM. The default
  * profile fetches a 16-word `gpu_job_record`; the persistent profile binds
  * an explicit bounded 256-byte tile descriptor allocation.
  * The graphics engine fetches the descriptor and its command buffer through
  * the translated command client, so a draw needs no dedicated physical word
  * port.
  */
class RenderDescriptor(config: GpuConfig, val commandIdWidth: Int, persistentAttachments: Boolean = false)
    extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val descriptorAddress = UInt(config.xLen.W)
  /** Legacy caller byte count, or the required descriptor size (256) in the
    * persistent profile. Persistent completion reports actual command reads. */
  val bytes = UInt(32.W)
  /** Explicit allocation for the 256-byte persistent descriptor profile. */
  val limit = if (persistentAttachments) Some(UInt(33.W)) else None
  val envelopeValid = if (persistentAttachments) Some(Bool()) else None
}

object RenderStatus {
  val width = 4
  val success = 0.U(width.W)
  /** A texture or memory fault failed the draw (drained before completion). */
  val memoryFault = 1.U(width.W)
  val invalidSampleMode = 2.U(width.W)
}

class RenderCompletion(val commandIdWidth: Int) extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val status = UInt(RenderStatus.width.W)
  val success = Bool()
  /** Bytes of command records the graphics engine consumed. */
  val bytesProcessed = UInt(64.W)
}
