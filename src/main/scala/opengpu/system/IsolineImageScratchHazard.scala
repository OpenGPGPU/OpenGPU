package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

/** An image transfer and fragment shader may share a memory port, but their
  * owned allocations cannot overlap while the tile is resident. Image and
  * scratch descriptor validity are checked by their respective admissions.
  */
class IsolineImageScratchHazard extends Module {
  val io=IO(new Bundle {
    val image=Input(new TypedTileImageDescriptor)
    val scratch=Input(new VertexInputView)
    val hazard=Output(Bool())
  })
  private val enabled=TypedTileImageRules.enabled(io.image.pixels)
  private val loads=TypedTileImageRules.loads(io.image.pixels)
  private def overlap(base:UInt,limit:UInt):Bool =
    base.pad(33)<io.scratch.allocationLimit && io.scratch.allocationBase.pad(33)<limit
  private val source=io.image.views.zipWithIndex.map{case(v,i)=>
    enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U) && overlap(v.allocationBase,v.allocationLimit)
  }
  private val resolve=io.image.resolves.zipWithIndex.map{case(r,i)=>
    enabled(i) && r.enable && overlap(r.destination.allocationBase,r.destination.allocationLimit)
  }
  io.hazard:=(source++resolve).reduce(_ || _)
}
