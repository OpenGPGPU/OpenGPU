package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationThreeDomainSampleMuxSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "forward all domain sample counts and faults through backpressure" in {
    simulate(new TessellationThreeDomainSampleMux){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for((domain,count,status)<-Seq((0,3,0),(2,7,2),(1,5,1),(0,9,0))){
        d.io.selected.poke(domain.U);val source=d.io.domains(domain)
        source.bits.passingSamples.poke(count.U);source.bits.status.poke(status.U);source.valid.poke(true.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.passingSamples.expect(count.U);d.io.out.bits.status.expect(status.U)
        d.io.domains.foreach(_.ready.expect(false.B));val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);source.ready.expect(true.B)
        for(other<-0 until 3 if other!=domain)d.io.domains(other).ready.expect(false.B)
        d.clock.step();source.valid.poke(false.B);d.io.out.ready.poke(false.B);d.clock.step()
      }
    }
  }
}
