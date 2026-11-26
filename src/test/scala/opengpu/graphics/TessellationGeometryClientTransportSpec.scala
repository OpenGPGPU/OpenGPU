package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.TessellationGeometryClientTransport
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Production local-credit helper; no CU, shader or frame execution is implied. */
class TessellationGeometryClientTransportSpec extends AnyFlatSpec {
  private def dut=new TessellationGeometryClientTransport(GpuConfig(lanes=16,warps=8))
  private def initialize(d:TessellationGeometryClientTransport):Unit={
    d.io.launchAccepted.poke(false.B);d.io.completionAccepted.poke(false.B)
    d.io.invalidateAccepted.poke(false.B);d.io.invalidateDoneAccepted.poke(false.B);d.io.executionOffersLive.poke(false.B);d.io.invalidateOffersLive.poke(false.B)
    d.io.memoryRequest.valid.poke(false.B);d.io.memoryRequest.bits.address.poke(0x1000.U)
    d.io.memoryRequest.bits.writeData.poke(0.U);d.io.memoryRequest.bits.byteMask.poke(0.U)
    d.io.memoryRequest.bits.isWrite.poke(false.B);d.io.memoryRequest.bits.sizeLog2.poke(6.U)
    d.io.memoryRequest.bits.cacheClient.poke(false.B);d.io.memoryRequest.bits.cacheResident.poke(false.B)
    d.io.memoryRequest.bits.cachePolicy.poke(0.U);d.io.memoryRequest.bits.transactionId.poke(0.U)
    d.io.memoryResponse.ready.poke(false.B);d.io.request.ready.poke(false.B)
    d.io.response.valid.poke(false.B);d.io.response.bits.readData.poke(0.U)
    d.io.response.bits.fault.poke(false.B);d.io.response.bits.transactionId.poke(0.U)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
  }
  it should "retain accepted GS credits through held offers, delayed replies and reset-free replacement" in {
    simulateWithInlineVerification(dut){d=>
      initialize(d)
      // A logical launch is reserved before the physical pool accepts it.
      d.io.launchAccepted.poke(true.B);d.clock.step();d.io.launchAccepted.poke(false.B)
      for(_<-0 until 4){d.io.drained.expect(false.B);d.clock.step()}
      // A visible held completion still retains the slot until its real handshake.
      d.io.executionOffersLive.poke(true.B)
      for(_<-0 until 3){d.io.drained.expect(false.B);d.clock.step()}
      d.io.completionAccepted.poke(true.B);d.clock.step();d.io.completionAccepted.poke(false.B)
      d.io.drained.expect(false.B);d.io.executionOffersLive.poke(false.B);d.io.drained.expect(true.B)

      d.io.invalidateAccepted.poke(true.B);d.clock.step();d.io.invalidateAccepted.poke(false.B)
      for(_<-0 until 5){d.io.drained.expect(false.B);d.io.executionDrained.expect(true.B);d.clock.step()}
      d.io.invalidateDoneAccepted.poke(true.B);d.clock.step();d.io.invalidateDoneAccepted.poke(false.B)
      d.io.drained.expect(true.B)
      // New pre-admission flush offers and accepted late acknowledgements change
      // live admission/drain, while a previously captured execution report stays valid.
      d.io.invalidateOffersLive.poke(true.B);d.io.drained.expect(false.B);d.io.executionDrained.expect(true.B)
      d.io.invalidateAccepted.poke(true.B);d.clock.step();d.io.invalidateAccepted.poke(false.B)
      d.io.invalidateOffersLive.poke(false.B)
      for(_<-0 until 3){d.io.drained.expect(false.B);d.io.executionDrained.expect(true.B);d.clock.step()}
      d.io.invalidateDoneAccepted.poke(true.B);d.clock.step();d.io.invalidateDoneAccepted.poke(false.B)
      d.io.drained.expect(true.B)
      // Other clients have no drain input here: their live slots cannot gate GS reuse.
      d.io.launchAccepted.poke(true.B);d.clock.step();d.io.launchAccepted.poke(false.B)
      d.io.completionAccepted.poke(true.B);d.clock.step();d.io.completionAccepted.poke(false.B)
      d.io.drained.expect(true.B)

      d.io.memoryRequest.valid.poke(true.B);d.io.memoryRequest.ready.expect(true.B)
      d.clock.step();d.io.memoryRequest.valid.poke(false.B)
      d.io.request.valid.expect(true.B);val held=d.io.request.bits.peek().litValue
      for(_<-0 until 4){d.io.drained.expect(false.B);d.io.request.valid.expect(true.B)
        assert(d.io.request.bits.peek().litValue==held);d.clock.step()}
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(true.B);d.io.response.bits.readData.poke(0x12345678.U)
      d.io.response.bits.fault.poke(true.B)
      for(_<-0 until 4){d.io.memoryResponse.valid.expect(true.B);d.io.memoryResponse.bits.readData.expect(0x12345678.U)
        d.io.memoryResponse.bits.fault.expect(true.B);d.io.response.ready.expect(false.B);d.io.drained.expect(false.B);d.clock.step()}
      // Consume the old reply and accept a new read in the same cycle.
      d.io.memoryRequest.bits.address.poke(0x2000.U);d.io.memoryRequest.valid.poke(true.B)
      d.io.memoryResponse.ready.poke(true.B);d.io.memoryRequest.ready.expect(true.B);d.io.response.ready.expect(true.B)
      d.clock.step();d.io.memoryRequest.valid.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.memoryResponse.ready.poke(false.B);d.io.request.valid.expect(true.B);d.io.request.bits.address.expect(0x2000.U)
      for(_<-0 until 3){d.io.drained.expect(false.B);d.clock.step()}
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      d.io.response.bits.readData.poke(0xbeef.U);d.io.response.bits.fault.poke(false.B)
      d.io.response.valid.poke(true.B);d.io.memoryResponse.ready.poke(true.B)
      d.io.memoryResponse.valid.expect(true.B);d.clock.step();d.io.response.valid.poke(false.B)
      d.io.drained.expect(true.B)
      // A new offered request itself prevents drain before acceptance.
      d.io.memoryRequest.valid.poke(true.B);d.io.drained.expect(false.B)
      d.clock.step();d.io.memoryRequest.valid.poke(false.B);d.io.request.ready.poke(true.B)
      d.clock.step();d.io.request.ready.poke(false.B);d.io.response.valid.poke(true.B)
      d.clock.step();d.io.response.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }
  for((kind,needle)<-Seq("completion"->"GS completion without accepted launch",
      "invalidate"->"GS invalidate acknowledgement without request",
      "response"->"GS uniform reply without issued request"))
  it should s"reject an unowned $kind with its exact production assertion" in {
    val error=intercept[chisel3.simulator.Exceptions.AssertionFailed]{simulateWithInlineVerification(dut){d=>
      initialize(d)
      kind match {
        case "completion"=>d.io.completionAccepted.poke(true.B)
        case "invalidate"=>d.io.invalidateDoneAccepted.poke(true.B)
        case "response"=>d.io.response.valid.poke(true.B)
      }
      d.clock.step()
    }}
    assert(error.simulatorOutput.contains(needle),error.simulatorOutput)
  }
}
