package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeJobDomainProbeSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool=>b.poke(false.B);case u:UInt=>u.poke(0.U);case s:SInt=>s.poke(0.S);case _=>()
  }
  it should "route both job formats and preserve fault and cancellation ownership" in {
    simulate(new TessellationNativeJobDomainProbe(GpuConfig(),4)){d=>
      zero(d.io.submit.bits);zero(d.io.memoryResponse.bits)
      d.io.submit.valid.poke(false.B);d.io.result.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(valid:Boolean=true):Unit={
        d.io.submit.bits.commandId.poke(3.U);d.io.submit.bits.base.poke("h1000".U)
        d.io.submit.bits.limit.poke("h1200".U);d.io.submit.bits.envelopeValid.poke(valid.B)
        d.io.submit.valid.poke(true.B);d.io.submit.ready.expect(true.B);d.clock.step();d.io.submit.valid.poke(false.B)
      }
      def offer():Unit={d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.address.expect("h1000".U)
        d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)}
      def response(magic:BigInt,fault:Boolean=false):Unit={d.io.memoryResponse.bits.readData.poke(magic.U)
        d.io.memoryResponse.bits.fault.poke(fault.B);d.io.memoryResponse.valid.poke(true.B)
        d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)}
      def finish(status:Int,triangle:Boolean=false,address:BigInt=0):Unit={
        d.io.result.valid.expect(true.B);d.io.result.bits.status.expect(status.U);d.io.result.bits.triangle.expect(triangle.B)
        d.io.result.bits.faultAddress.expect(address.U);d.io.result.bits.job.commandId.expect(3.U)
        val held=d.io.result.bits.peek().litValue;d.clock.step(3);d.io.result.valid.expect(true.B);assert(d.io.result.bits.peek().litValue==held)
        d.io.submit.ready.expect(false.B);d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B)
      }
      submit(false);d.io.memoryRequest.valid.expect(false.B);finish(1,address=0x1000)
      submit();offer();response(TessellationNativeJobLayout.magic);finish(0)
      submit();offer();response(TessellationNativeTriangleJobLayout.magic);finish(0,triangle=true)
      submit();offer();response(BigInt(0));finish(1,address=0x1000)
      submit();offer();response(BigInt(0),fault=true);finish(3,address=0x1000)
      submit();val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.result.valid.expect(false.B);d.clock.step()}
      offer();response(TessellationNativeTriangleJobLayout.magic);finish(4)
      submit();offer();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.result.valid.expect(false.B);response(TessellationNativeJobLayout.magic);finish(4)
      submit();d.io.memoryRequest.ready.poke(true.B);d.io.memoryResponse.bits.readData.poke(TessellationNativeTriangleJobLayout.magic.U)
      d.io.memoryResponse.bits.fault.poke(false.B);d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      finish(0,triangle=true)
      submit();offer();response(TessellationNativeJobLayout.magic);finish(0);d.io.busy.expect(false.B)
    }
  }
}
