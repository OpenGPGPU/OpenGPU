package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinHostMemoryFabricSpec extends AnyFlatSpec {
  it should "preserve client ownership, DMA offsets and a selected response stall across all line clients" in {
    simulate(new TileBinHostMemoryFabric(GpuConfig(lanes=4,warps=2),30,2,32,16,programmable=true)) { h=>
      val io=h.io
      io.satp.poke(0.U);io.flush.valid.poke(false.B);io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
      io.memoryRequest.ready.poke(true.B);io.memoryResponse.valid.poke(false.B)
      io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      val sources=Seq(io.dmaRequest,io.renderRequest)++io.stagingRequest.toSeq
      val sinks=Seq(io.dmaResponse,io.renderResponse)++io.stagingResponse.toSeq
      sources.foreach { p=>p.valid.poke(false.B);p.bits.poke(0.U.asTypeOf(p.bits)) }
      sinks.foreach(_.ready.poke(true.B));io.renderResponse.ready.poke(false.B)
      h.reset.poke(true.B);h.clock.step();h.reset.poke(false.B)
      for ((p,i) <- sources.zipWithIndex) {
        p.bits.address.poke((0x10000+i*64).U);p.bits.sizeLog2.poke(6.U)
        p.bits.transactionId.poke((if(i==0)16 else 0).U);p.valid.poke(true.B)
      }
      val active=Array.fill(4)(true);val requests=mutable.ArrayBuffer.empty[Int]
      var cycles=0
      while(requests.size<4 && cycles<1000) {
        val fired=sources.indices.map(i=>active(i) && sources(i).ready.peek().litToBoolean)
        if(io.memoryRequest.valid.peek().litToBoolean) requests+=io.memoryRequest.bits.transactionId.peek().litValue.toInt
        h.clock.step();for(i <- sources.indices if fired(i)){active(i)=false;sources(i).valid.poke(false.B)}
        cycles+=1
      }
      assert(requests.toSet==Set(0,4,34,66));io.drained.expect(false.B)
      def respond(id: Int,owner: Int,fault: Boolean=false): Unit = {
        io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(id.U)
        io.memoryResponse.bits.readData.poke((0x12340000L+id).U);io.memoryResponse.bits.fault.poke(fault.B)
        sinks(owner).valid.expect(true.B);sinks(owner).bits.transactionId.expect((if(owner==0)16 else 0).U)
        sinks(owner).bits.fault.expect(fault.B);io.memoryResponse.ready.expect(true.B)
        h.clock.step();io.memoryResponse.valid.poke(false.B)
      }
      respond(66,3);respond(0,0)
      io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(4.U)
      io.memoryResponse.bits.readData.poke(0xabcdef.U);io.memoryResponse.bits.fault.poke(false.B)
      for(_ <- 0 until 8) {io.memoryResponse.ready.expect(false.B);io.renderResponse.valid.expect(true.B);io.drained.expect(false.B);h.clock.step()}
      io.renderResponse.ready.poke(true.B);respond(4,1);respond(34,2,fault=true)
      h.clock.step(3);io.drained.expect(true.B)
    }
  }
}
