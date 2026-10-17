package opengpu.graphics

import chisel3._
import chisel3.util._

object AttachmentLoadOp {
  val load = 0
  val clear = 1
  val discard = 2
}

object TilePassStatus {
  val width = 3
  val success = 0
  val invalidDescriptor = 1
  val cancelled = 2
  val memoryFault = 3
  val executionFault = 4
  val commandFault = 5
  val textureFault = 6
}

/** Stable identity retained through completion and fault drain. Stage tags
  * reserve geometry/tessellation without claiming those stages exist yet.
  */
class GraphicsWorkIdentity extends Bundle {
  val context = UInt(32.W)
  val pass = UInt(32.W)
  val generation = UInt(32.W)
}

object GraphicsStage {
  val width = 3
  val vertex = 0
  val fragment = 1
  val compute = 2
  val geometry = 3
  val tessControl = 4
  val tessEvaluation = 5
}

/** One bounded 32-bit attachment view. Addresses are byte addresses; limit is
  * exclusive and 33 bits so an allocation may end exactly at 4 GiB.
  * Clear values are already packed in the attachment's memory format.
  */
class TileAttachmentView extends Bundle {
  val base = UInt(32.W)
  val limit = UInt(33.W)
  val rowPitch = UInt(32.W)
  val loadOp = UInt(2.W)
  val store = Bool()
  val clearValue = UInt(32.W)
}

/** H1 descriptor: one resident 8x8 or 16x16 tile, one color and one D24S8 plane.
  * Later render-pass descriptors can reference many such tile jobs. Reject
  * unsupported versions/limits instead of interpreting future layouts.
  */
class TileRenderPassDescriptor extends Bundle {
  val version = UInt(8.W)
  val identity = new GraphicsWorkIdentity
  val originX = UInt(16.W)
  val originY = UInt(16.W)
  val tileLog2 = UInt(3.W)
  val tileWidth = UInt(5.W)
  val tileHeight = UInt(5.W)
  val sampleMode = UInt(2.W)
  val colorFormat = UInt(PixelFormat.width.W)
  val color = new TileAttachmentView
  val depth = new TileAttachmentView
  /** Byte-wise half-up RGBA8 resolve into a disjoint single-sample view.
    * Independent of color.store: an MSAA source can be discarded after resolve.
    */
  val resolve = Bool()
  val resolveBase = UInt(32.W)
  val resolveLimit = UInt(33.W)
  val resolveRowPitch = UInt(32.W)
}

class TileRenderPassCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(TilePassStatus.width.W)
}

/** Half-open rectangle in tile-local pixel coordinates. A bit mask preserves
  * unselected color channels or the other half of a D24S8 word. All active
  * samples of selected pixels are cleared; values use memory byte order.
  */
class TileAttachmentClear extends Bundle {
  val minX = UInt(5.W)
  val minY = UInt(5.W)
  val maxX = UInt(5.W)
  val maxY = UInt(5.W)
  val colorMask = UInt(32.W)
  val depthMask = UInt(32.W)
  val colorValue = UInt(32.W)
  val depthValue = UInt(32.W)
}

/** begin/end/clear use ready/valid; cancel is a synchronous cancellation request.
  * The owner stops producing draws before end and
  * waits for completion before a dependent texture/DMA/CPU operation. This
  * completion acknowledges the memory port, not CPU cache coherence.
  */
class TileRenderPassPort extends Bundle {
  val begin = Flipped(Decoupled(new TileRenderPassDescriptor))
  val end = Flipped(Decoupled(Bool()))
  val clear = Flipped(Decoupled(new TileAttachmentClear))
  /** Accepted commands complete in order; false also fails/closes the pass. */
  val clearDone = Decoupled(Bool())
  val cancel = Input(Bool())
  /** Applies only to a memory response accepted on this cycle. */
  val memoryFault = Input(Bool())
  val completion = Decoupled(new TileRenderPassCompletion)
  val active = Output(Bool())
  val descriptor = Output(new TileRenderPassDescriptor)
}

/** Cycle events, not accumulated totals; byte counts count port handshakes. */
class TileAttachmentEvents extends Bundle {
  val resident = Bool()
  val admitted = Bool()
  val completed = Bool()
  val loadBytes = UInt(3.W)
  val storeBytes = UInt(3.W)
  /** Subset of storeBytes belonging to resolved single-sample output. */
  val resolveBytes = UInt(3.W)
  /** Clear-tag read accepted on the preceding cycle; count once per pulse. */
  val clearRead = Bool()
  val attachmentStall = Bool()
  /** Allocated transfer credits, including buffered requests and pending acks. */
  val transferOccupancy = UInt(6.W)
}
