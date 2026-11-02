package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationDomainSampleMuxSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "forward each domain's query samples without losing held completions" in {
    simulate(new TessellationDomainSampleMux){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for((triangle,count,status)<-Seq((false,3,0),(true,7,2))){
        d.io.selected.poke(triangle.B);val source=if(triangle)d.io.triangle else d.io.isoline
        val other=if(triangle)d.io.isoline else d.io.triangle
        source.bits.passingSamples.poke(count.U);source.bits.status.poke(status.U);source.valid.poke(true.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.passingSamples.expect(count.U);d.io.out.bits.status.expect(status.U)
        source.ready.expect(false.B);other.ready.expect(false.B);val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);source.ready.expect(true.B);other.ready.expect(false.B);d.clock.step()
        source.valid.poke(false.B);d.io.out.ready.poke(false.B);d.clock.step()
      }
    }
  }
}
