package opengpu.system

import chisel3._
import chisel3.util.DecoupledIO
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics.WindowRecordTest.zero
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exact outer-fabric geometry of the actual four-bank joint empty frame.
  * These isolate translated-credit accounting, not frame or shader execution. */
class TileBinHostMemoryFabricJointClearSpec extends AnyFlatSpec {
  private def exercise(clock:Clock,input:DecoupledIO[ComputeMemoryRequest],inputReply:DecoupledIO[ComputeMemoryResponse],
      render:DecoupledIO[ComputeMemoryRequest],renderReply:DecoupledIO[ComputeMemoryResponse],
      memory:DecoupledIO[ComputeMemoryRequest],memoryReply:DecoupledIO[ComputeMemoryResponse],drained:Bool):Unit={
    inputReply.ready.poke(true.B);renderReply.ready.poke(true.B)
    var externalAcks=0
    def until(why:String)(p: =>Boolean):Unit={
      var n=0;while(!p && n<100){clock.step();n+=1};assert(p,why)
    }
    def transfer(q:DecoupledIO[ComputeMemoryRequest],r:DecoupledIO[ComputeMemoryResponse],
        address:BigInt,rawTag:Int,physicalTag:Int,write:Boolean,held:Boolean):Unit={
      q.bits.address.poke(address.U);q.bits.transactionId.poke(rawTag.U)
      q.bits.sizeLog2.poke(6.U);q.bits.isWrite.poke(write.B)
      q.bits.byteMask.poke("hffffffffffffffff".U)
      q.bits.writeData.poke("h47444402".U);q.valid.poke(true.B)
      until("actual translated ingress")(q.ready.peek().litToBoolean)
      clock.step();q.valid.poke(false.B)
      until("actual physical offer")(memory.valid.peek().litToBoolean)
      memory.bits.address.expect(address.U);memory.bits.transactionId.expect(physicalTag.U)
      memory.bits.isWrite.expect(write.B)
      memory.ready.poke(true.B);clock.step();memory.ready.poke(false.B)
      clock.step(4)
      memoryReply.bits.transactionId.poke(physicalTag.U)
      memoryReply.bits.fault.poke(false.B);memoryReply.bits.readData.poke("ha7a7a7a7".U)
      r.ready.poke((!held).B);memoryReply.valid.poke(true.B)
      r.valid.expect(true.B);r.bits.transactionId.expect(rawTag.U)
      if(held){
        memoryReply.ready.expect(false.B);drained.expect(false.B)
        val bits=r.bits.peek().litValue
        clock.step()
        r.valid.expect(true.B);assert(r.bits.peek().litValue==bits,"held translated reply changed")
        r.ready.poke(true.B)
      }
      memoryReply.ready.expect(true.B);r.valid.expect(true.B)
      clock.step();externalAcks+=1;memoryReply.valid.poke(false.B)
      clock.step(2);r.valid.expect(false.B);drained.expect(true.B)
    }
    for(i<-0 until 40){
      val address=if(i<8)BigInt(0x1000)+64*i else BigInt(0x2000)+64*(i-8)
      transfer(input,inputReply,address,0,260,write=false,held=false)
    }
    transfer(render,renderReply,0x100000,12,16,write=true,held=true)
    assert(externalAcks==41,"each accepted physical transaction must have one actual ACK")
    drained.expect(true.B)
  }

  it should "retire forty metadata reads and one held directory write once" in {
    simulate(new TileBinHostMemoryFabric(
        GpuConfig(lanes=16,warps=8),renderIds=256,engines=1,kernelWordIds=4,dmaIdBase=0,programmable=true)){d=>
      val io=d.io;zero(io)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.satp.poke(0.U);io.dmaResponse.ready.poke(true.B)
      exercise(d.clock,io.stagingRequest(0),io.stagingResponse(0),io.renderRequest,io.renderResponse,
        io.memoryRequest,io.memoryResponse,io.drained)
    }
  }

  it should "preserve the same credits through the actual owned-source input wrapper" in {
    simulate(new TessellationInputMemoryFabric(
        GpuConfig(lanes=16,warps=8),renderIds=256,ownedVertexSource=true)){d=>
      val io=d.io;zero(io)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.satp.poke(0.U);io.sourceLive.get.poke(false.B)
      io.sourceRequest.get.valid.poke(false.B);io.sourceResponse.get.ready.poke(true.B)
      exercise(d.clock,io.inputRequest,io.inputResponse,io.renderRequest,io.renderResponse,
        io.memoryRequest,io.memoryResponse,io.drained)
      io.sourceSelected.get.expect(false.B);io.sourceDrained.get.expect(true.B)
    }
  }
}
