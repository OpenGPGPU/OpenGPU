package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IsolineImageScratchHazardSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "protect shader scratch from active source and resolve allocations" in {
    simulate(new IsolineImageScratchHazard){d=>
      zero(d.io);val s=d.io.scratch;val image=d.io.image
      s.allocationBase.poke(0x1000.U);s.allocationLimit.poke(0x2000.U)
      val color=image.pixels.colors.colors(0);val view=image.views(0);val resolve=image.resolves(0)
      color.enabled.poke(true.B);color.loadOp.poke(AttachmentLoadOp.clear.U);view.store.poke(true.B)
      view.allocationBase.poke(0x2000.U);view.allocationLimit.poke(0x3000.U);d.io.hazard.expect(false.B)
      view.allocationBase.poke(0x1fc0.U);d.io.hazard.expect(true.B)
      view.store.poke(false.B);d.io.hazard.expect(false.B)
      color.loadOp.poke(AttachmentLoadOp.load.U);d.io.hazard.expect(true.B)
      color.enabled.poke(false.B);d.io.hazard.expect(false.B)
      color.enabled.poke(true.B);color.loadOp.poke(AttachmentLoadOp.clear.U)
      resolve.enable.poke(true.B);resolve.destination.allocationBase.poke(0x0fc0.U)
      resolve.destination.allocationLimit.poke(0x1040.U);d.io.hazard.expect(true.B)
      resolve.destination.allocationLimit.poke(0x1000.U);d.io.hazard.expect(false.B)
      s.allocationBase.poke("hfffff000".U);s.allocationLimit.poke((BigInt(1)<<32).U)
      resolve.destination.allocationBase.poke("hffffffc0".U);resolve.destination.allocationLimit.poke((BigInt(1)<<32).U)
      d.io.hazard.expect(true.B)
    }
  }
}
