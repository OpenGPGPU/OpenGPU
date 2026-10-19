package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeTypedGraphicsMemoryFabricSpec extends AnyFlatSpec {
  private def init(h: NativeTypedGraphicsMemoryFabric,satp: BigInt=0): Unit={
    h.io.satp.poke(satp.U);h.io.flush.valid.poke(false.B);h.io.flush.bits.poke(0.U.asTypeOf(h.io.flush.bits))
    h.io.renderRequest.valid.poke(false.B);h.io.renderRequest.bits.poke(0.U.asTypeOf(h.io.renderRequest.bits))
    h.io.dmaRequest.valid.poke(false.B);h.io.dmaRequest.bits.poke(0.U.asTypeOf(h.io.dmaRequest.bits))
    h.io.renderResponse.ready.poke(true.B);h.io.dmaResponse.ready.poke(true.B)
    h.io.memoryRequest.ready.poke(true.B);h.io.memoryResponse.valid.poke(false.B);h.io.memoryResponse.bits.poke(0.U.asTypeOf(h.io.memoryResponse.bits))
    h.reset.poke(true.B);h.clock.step();h.reset.poke(false.B)
  }
  it should "share disjoint virtual physical and legacy DMA namespaces while retaining a stalled physical reply" in {
    simulate(new NativeTypedGraphicsMemoryFabric(GpuConfig(lanes=4,warps=2),32,16)){h=>init(h)
      val requests=mutable.ArrayBuffer.empty[(Int,BigInt)];var index=0;var dmaSent=false;var cycles=0
      while(requests.size<4 && cycles<1000){
        h.io.renderRequest.valid.poke((index<3).B)
        if(index<3){h.io.renderRequest.bits.transactionId.poke(Seq(0,16,24)(index).U);h.io.renderRequest.bits.address.poke((0x10000+index*64).U);h.io.renderRequest.bits.sizeLog2.poke(6.U)}
        h.io.dmaRequest.valid.poke((!dmaSent).B);h.io.dmaRequest.bits.transactionId.poke(16.U);h.io.dmaRequest.bits.address.poke(0x20000.U);h.io.dmaRequest.bits.sizeLog2.poke(6.U)
        val r=h.io.renderRequest.valid.peek().litToBoolean && h.io.renderRequest.ready.peek().litToBoolean
        val d=h.io.dmaRequest.valid.peek().litToBoolean && h.io.dmaRequest.ready.peek().litToBoolean
        if(h.io.memoryRequest.valid.peek().litToBoolean)requests+=((h.io.memoryRequest.bits.transactionId.peek().litValue.toInt,h.io.memoryRequest.bits.address.peek().litValue))
        h.clock.step();if(r)index+=1;if(d)dmaSent=true;cycles+=1
      }
      h.io.renderRequest.valid.poke(false.B);h.io.dmaRequest.valid.poke(false.B)
      assert(requests.map(_._1).toSet==Set(0,16,24,32));h.io.drained.expect(false.B)
      def reply(id: Int,fault: Boolean=false): Unit={
        h.io.memoryResponse.valid.poke(true.B);h.io.memoryResponse.bits.transactionId.poke(id.U)
        h.io.memoryResponse.bits.readData.poke((0x12340000L+id).U);h.io.memoryResponse.bits.fault.poke(fault.B)
        val sink=if(id==32)h.io.dmaResponse else h.io.renderResponse
        sink.valid.expect(true.B);sink.bits.transactionId.expect((if(id==32)16 else id).U);sink.bits.fault.expect(fault.B)
        h.io.memoryResponse.ready.expect(true.B);h.clock.step();h.io.memoryResponse.valid.poke(false.B)
      }
      reply(16);reply(32,fault=true);reply(0)
      h.io.renderResponse.ready.poke(false.B);h.io.memoryResponse.valid.poke(true.B);h.io.memoryResponse.bits.transactionId.poke(24.U)
      h.io.memoryResponse.bits.readData.poke(0xdeadbeefL.U);h.io.memoryResponse.bits.fault.poke(false.B)
      for(_<-0 until 9){h.io.memoryResponse.ready.expect(false.B);h.io.renderResponse.valid.expect(true.B);h.io.drained.expect(false.B);h.clock.step()}
      h.io.renderResponse.ready.poke(true.B);reply(24);h.clock.step(3);h.io.drained.expect(true.B)
      info("NATIVE_TYPED_FABRIC ranges=3 originalRenderIDs=true restoredDMAID=true reorderedReplies=true physicalReplyHeld=true noEarlyDrain=true")
    }
  }
  it should "translate virtual reads reject read-only DMA writes and bypass translation for trusted physical images" in {
    simulate(new NativeTypedGraphicsMemoryFabric(GpuConfig(lanes=4,warps=2),32,16)){h=>init(h,BigInt("80000030",16))
      val pending=mutable.ArrayBuffer.empty[(Int,BigInt)];var offered: Option[(Int,BigInt)]=None
      val requests=mutable.ArrayBuffer.empty[(Int,BigInt)];val renderReplies=mutable.Set.empty[Int]
      var nativeIndex=0;var dmaSent=false;var dmaDone=false;var held=0;var cycles=0
      while((renderReplies.size<2 || !dmaDone || pending.nonEmpty || offered.nonEmpty) && cycles<2000){
        h.io.renderRequest.valid.poke((nativeIndex<2).B)
        if(nativeIndex<2){h.io.renderRequest.bits.transactionId.poke((if(nativeIndex==0)0 else 24).U)
          h.io.renderRequest.bits.address.poke((if(nativeIndex==0)0x400000 else 0x1000000).U);h.io.renderRequest.bits.sizeLog2.poke(6.U)}
        h.io.dmaRequest.valid.poke((!dmaSent).B);h.io.dmaRequest.bits.transactionId.poke(16.U)
        h.io.dmaRequest.bits.address.poke(0x500000.U);h.io.dmaRequest.bits.sizeLog2.poke(6.U);h.io.dmaRequest.bits.isWrite.poke(true.B);h.io.dmaRequest.bits.byteMask.poke(((BigInt(1)<<64)-1).U)
        h.io.dmaResponse.ready.poke((held>=9).B)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        h.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>h.io.memoryResponse.bits.transactionId.poke(id.U);h.io.memoryResponse.bits.readData.poke(data.U);h.io.memoryResponse.bits.fault.poke(false.B)}
        val r=h.io.renderRequest.valid.peek().litToBoolean && h.io.renderRequest.ready.peek().litToBoolean
        val d=h.io.dmaRequest.valid.peek().litToBoolean && h.io.dmaRequest.ready.peek().litToBoolean
        if(h.io.memoryRequest.valid.peek().litToBoolean){val id=h.io.memoryRequest.bits.transactionId.peek().litValue.toInt;val address=h.io.memoryRequest.bits.address.peek().litValue
          requests+=((id,address));assert(id!=32,"a read-only PTE must reject the DMA store before physical issue")
          if(id>=36){assert(address==0x30004);pending+=((id,(BigInt(0x800)<<10)|0x43))}
          else {assert((id==0 && address==0x800000) || (id==24 && address==0x1000000));pending+=((id,BigInt(0x1234+id)))}}
        if(offered.nonEmpty && h.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(h.io.renderResponse.valid.peek().litToBoolean){val id=h.io.renderResponse.bits.transactionId.peek().litValue.toInt
          h.io.renderResponse.bits.fault.expect(false.B);h.io.renderResponse.bits.readData.expect((0x1234+id).U);assert(renderReplies.add(id))}
        if(h.io.dmaResponse.valid.peek().litToBoolean){h.io.dmaResponse.bits.transactionId.expect(16.U);h.io.dmaResponse.bits.fault.expect(true.B)
          if(held<9){h.io.drained.expect(false.B);held+=1}else dmaDone=true}
        h.clock.step();if(r)nativeIndex+=1;if(d)dmaSent=true;cycles+=1
      }
      assert(cycles<2000 && renderReplies==Set(0,24) && dmaDone && held==9)
      h.io.renderRequest.valid.poke(false.B);h.io.dmaRequest.valid.poke(false.B);h.io.memoryResponse.valid.poke(false.B)
      h.clock.step(3);h.io.drained.expect(true.B)
      assert(requests.filter(_._1>=36).map(_._1).toSet==Set(36,37))
      info("NATIVE_TYPED_FABRIC Sv32=true readTranslation=true readOnlyWriteFault=true independentWalkIDs=true physicalImageBypass=true heldLocalFaultPinsVM=true")
    }
  }
}
