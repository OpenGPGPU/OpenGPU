package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeJobDomainRouterSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool=>b.poke(false.B);case u:UInt=>u.poke(0.U);case s:SInt=>s.poke(0.S);case _=>()
  }
  it should "retain selected jobs and cancellation until child retirement" in {
    simulate(new TessellationNativeJobDomainRouter(GpuConfig(),4)){d=>
      zero(d.io.submit.bits);zero(d.io.memoryResponse.bits)
      d.io.submit.valid.poke(false.B);d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.isoline.ready.poke(false.B);d.io.triangle.ready.poke(false.B);d.io.failed.ready.poke(false.B)
      d.io.retired.poke(false.B);d.io.cancel.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit():Unit={d.io.submit.bits.commandId.poke(7.U);d.io.submit.bits.base.poke("h1000".U)
        d.io.submit.bits.limit.poke("h1200".U);d.io.submit.bits.envelopeValid.poke(true.B)
        d.io.submit.valid.poke(true.B);d.io.submit.ready.expect(true.B);d.clock.step();d.io.submit.valid.poke(false.B)}
      def probe(magic:BigInt):Unit={d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.ready.poke(true.B);d.clock.step()
        d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.bits.readData.poke(magic.U)
        d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
        d.io.memoryResponse.valid.poke(false.B);d.clock.step()}
      def retire():Unit={d.io.retired.poke(true.B);d.clock.step();d.io.retired.poke(false.B);d.io.busy.expect(false.B)}
      submit();probe(TessellationNativeJobLayout.magic)
      d.io.isoline.valid.expect(true.B);d.io.triangle.valid.expect(false.B);d.io.isoline.bits.commandId.expect(7.U)
      d.io.isoline.ready.poke(true.B);d.clock.step();d.io.isoline.ready.poke(false.B)
      d.io.submit.ready.expect(false.B);d.io.cancelIsoline.expect(false.B);retire()
      submit();probe(TessellationNativeTriangleJobLayout.magic)
      d.io.triangle.valid.expect(true.B);val held=d.io.triangle.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.triangle.valid.expect(true.B);assert(d.io.triangle.bits.peek().litValue==held)
        d.io.cancelTriangle.expect(false.B);d.clock.step()}
      d.io.triangle.ready.poke(true.B);d.clock.step();d.io.triangle.ready.poke(false.B)
      d.io.cancelTriangle.expect(true.B);d.io.cancelIsoline.expect(false.B);d.clock.step(2);d.io.cancelTriangle.expect(true.B);retire()
      submit();probe(BigInt(0));d.io.failed.valid.expect(true.B);d.io.failed.bits.status.expect(1.U)
      d.io.failed.bits.commandId.expect(7.U);d.io.failed.bits.faultAddress.expect("h1000".U);d.io.failed.bits.descriptorReadBytes.expect(64.U)
      d.io.submit.ready.expect(false.B);d.clock.step(2);d.io.failed.valid.expect(true.B)
      d.io.failed.ready.poke(true.B);d.clock.step();d.io.failed.ready.poke(false.B);d.io.busy.expect(false.B)
      submit();d.io.memoryRequest.valid.expect(true.B);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.failed.valid.expect(false.B);d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.readData.poke(TessellationNativeTriangleJobLayout.magic.U)
      d.io.memoryResponse.valid.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B);d.clock.step()
      d.io.failed.valid.expect(true.B);d.io.failed.bits.status.expect(4.U);d.io.failed.bits.faultAddress.expect(0.U);d.io.failed.bits.descriptorReadBytes.expect(64.U)
      d.io.isoline.valid.expect(false.B);d.io.triangle.valid.expect(false.B)
      d.io.failed.ready.poke(true.B);d.clock.step();d.io.failed.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
}
