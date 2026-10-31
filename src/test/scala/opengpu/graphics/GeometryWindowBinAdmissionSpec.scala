package opengpu.graphics

import opengpu.system._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryWindowBinAdmissionSpec extends AnyFlatSpec {
  it should "reject mismatched identities and stop before the bin sequence wraps" in {
    simulate(new GeometryWindowBinAdmission){d=>
      d.io.pass.context.poke(7.U);d.io.pass.pass.poke(11.U);d.io.pass.generation.poke(13.U)
      d.io.primitive.context.poke(7.U);d.io.primitive.pass.poke(11.U);d.io.primitive.generation.poke(13.U)
      d.io.sequence.poke("hffffffff".U);d.io.sameIdentity.expect(true.B);d.io.exhausted.expect(false.B)
      d.io.sequence.poke("h100000000".U);d.io.exhausted.expect(true.B)
      d.io.primitive.context.poke(8.U);d.io.sameIdentity.expect(false.B);d.io.primitive.context.poke(7.U)
      d.io.primitive.pass.poke(12.U);d.io.sameIdentity.expect(false.B);d.io.primitive.pass.poke(11.U)
      d.io.primitive.generation.poke(14.U);d.io.sameIdentity.expect(false.B)
    }
  }
  it should "validate layered geometry bin grids and image identities before admission" in {
    simulate(new LayeredGeometryBinImageAdmission){d=>
      def zero(x:Data):Unit=x match {
        case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
        case b:Bool=>b.poke(false.B);case u:UInt=>u.poke(0.U);case s:SInt=>s.poke(0.S);case _=>()
      }
      zero(d.io.descriptor);val q=d.io.descriptor;val b=q.bins.producer;val i=q.image
      b.tileLog2.poke(3.U);b.screenWidth.poke(20.U);b.screenHeight.poke(17.U)
      b.arena.identity.context.poke(7.U);i.screenWidth.poke(20.U);i.screenHeight.poke(17.U);i.layerCount.poke(2.U)
      i.template.image.pixels.colors.tileLog2.poke(3.U);i.template.image.pixels.colors.identity.context.poke(7.U)
      i.template.states.identity.context.poke(7.U);i.template.states.version.poke(1.U)
      i.template.states.base.poke(0x200000.U);i.template.states.limit.poke(0x200200.U)
      i.template.scratchStride.poke(4096.U);i.template.scratch(0).base.poke(0x100000.U);i.template.scratch(0).limit.poke(0x101000.U)
      b.arena.base.poke(0x10000.U);b.arena.limit.poke(0x20000.U);q.bins.bins.pool.base.poke(0x40000.U)
      q.bins.bins.pool.limit.poke(0x44000.U);q.bins.bins.pool.identity.context.poke(7.U)
      q.bins.bins.directoryBase.poke(0x80000.U);q.bins.bins.directoryLimit.poke(0x80800.U)
      q.bins.bins.version.poke(3.U);q.bins.bins.tileCount.poke(18.U);d.io.legal.expect(true.B)
      q.bins.bins.version.poke(2.U);d.io.legal.expect(false.B);q.bins.bins.version.poke(3.U)
      q.bins.bins.resume.poke(true.B);d.io.legal.expect(false.B);q.bins.bins.resume.poke(false.B)
      i.template.scratch(0).base.poke(0x40000.U);i.template.scratch(0).limit.poke(0x41000.U);d.io.legal.expect(false.B)
      i.template.scratch(0).base.poke(0x100000.U);i.template.scratch(0).limit.poke(0x101000.U)
      val image=i.template.image;image.pixels.colors.colors(0).enabled.poke(true.B);image.views(0).store.poke(true.B)
      image.views(0).allocationBase.poke(0x200000.U);image.views(0).allocationLimit.poke(0x201000.U);d.io.legal.expect(false.B)
      image.pixels.colors.colors(0).enabled.poke(false.B)
      q.bins.bins.tileCount.poke(17.U);d.io.legal.expect(false.B);q.bins.bins.tileCount.poke(18.U)
      i.template.states.identity.context.poke(8.U);d.io.legal.expect(false.B);i.template.states.identity.context.poke(7.U)
      i.template.image.pixels.colors.identity.context.poke(8.U);d.io.legal.expect(false.B)
      i.template.image.pixels.colors.identity.context.poke(7.U);i.screenWidth.poke(19.U);d.io.legal.expect(false.B)
      i.screenWidth.poke(20.U);i.layerCount.poke(0.U);d.io.legal.expect(false.B)
      i.layerCount.poke(65535.U);d.io.legal.expect(false.B)
    }
  }

}
