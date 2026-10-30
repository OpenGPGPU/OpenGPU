package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedStorageCounterPortSpec extends AnyFlatSpec {
  it should "serialize shared IDs while routing independent out-of-order replies" in {
    simulate(new SharedStorageCounterPort(GpuConfig(lanes=4,warps=2))){d=>
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.storageRequest.valid.poke(false.B);d.io.counterRequest.valid.poke(false.B)
      d.io.storageRequest.bits.poke(0.U.asTypeOf(d.io.storageRequest.bits));d.io.counterRequest.bits.poke(0.U.asTypeOf(d.io.counterRequest.bits))
      d.io.storageResponse.ready.poke(false.B);d.io.counterResponse.ready.poke(false.B)
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B);d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      def storage(id:Int,address:Int):Unit={d.io.storageRequest.bits.transactionId.poke(id.U);d.io.storageRequest.bits.address.poke(address.U);d.io.storageRequest.valid.poke(true.B)}
      def counter(id:Int,address:Int):Unit={d.io.counterRequest.bits.transactionId.poke(id.U);d.io.counterRequest.bits.address.poke(address.U);d.io.counterRequest.valid.poke(true.B)}
      def drain(id:Int,address:Int):Unit={d.io.request.valid.expect(true.B);d.io.request.bits.transactionId.expect(id.U);d.io.request.bits.address.expect(address.U)
        val held=d.io.request.bits.peek().litValue;d.clock.step(2);d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==held)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)}
      def reply(id:Int,isCounter:Boolean,returnedId:Int,value:Int):Unit={
        d.io.response.bits.transactionId.poke(id.U);d.io.response.bits.readData.poke(value.U);d.io.response.valid.poke(true.B)
        val port=if(isCounter)d.io.counterResponse else d.io.storageResponse
        port.valid.expect(true.B);port.bits.transactionId.expect(returnedId.U);port.bits.readData.expect(value.U)
        d.io.response.ready.expect(false.B);d.clock.step(2);port.valid.expect(true.B);port.bits.readData.expect(value.U)
        port.ready.poke(true.B);d.clock.step();port.ready.poke(false.B);d.io.response.valid.poke(false.B)
      }
      storage(0,0x1000);d.io.storageRequest.ready.expect(true.B);d.clock.step();d.io.storageRequest.valid.poke(false.B)
      counter(0,0x2000);d.io.counterRequest.ready.expect(false.B);drain(0,0x1000)
      storage(1,0x3000);d.io.storageRequest.ready.expect(true.B);d.clock.step();d.io.storageRequest.valid.poke(false.B);drain(1,0x3000)
      reply(1,false,1,31);d.io.counterRequest.ready.expect(false.B);reply(0,false,0,11)
      d.io.counterRequest.ready.expect(true.B);d.clock.step();d.io.counterRequest.valid.poke(false.B);drain(0,0x2000)
      storage(2,0x4000);d.io.storageRequest.ready.expect(true.B);d.clock.step();d.io.storageRequest.valid.poke(false.B);drain(2,0x4000)
      counter(1,0x5000);d.io.counterRequest.ready.expect(false.B);reply(0,true,0,21);reply(2,false,2,41)
      d.io.counterRequest.ready.expect(true.B);d.clock.step();d.io.counterRequest.valid.poke(false.B);drain(2,0x5000)
      reply(2,true,1,51);d.io.busy.expect(false.B)
    }
  }
}
