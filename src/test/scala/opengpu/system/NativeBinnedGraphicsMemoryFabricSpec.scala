package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.NativeBinnedGraphicsConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeBinnedGraphicsMemoryFabricSpec extends AnyFlatSpec {
  private val native=NativeBinnedGraphicsConfig(pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=None,translateImageViews=false)
  private def init(h: NativeBinnedGraphicsMemoryFabric,satp: BigInt=0): Unit={
    h.io.satp.poke(satp.U);h.io.flush.valid.poke(false.B);h.io.flush.bits.poke(0.U.asTypeOf(h.io.flush.bits))
    h.io.renderRequest.valid.poke(false.B);h.io.renderRequest.bits.poke(0.U.asTypeOf(h.io.renderRequest.bits))
    h.io.dmaRequest.valid.poke(false.B);h.io.dmaRequest.bits.poke(0.U.asTypeOf(h.io.dmaRequest.bits))
    h.io.renderResponse.ready.poke(true.B);h.io.dmaResponse.ready.poke(true.B)
    h.io.memoryRequest.ready.poke(true.B);h.io.memoryResponse.valid.poke(false.B);h.io.memoryResponse.bits.poke(0.U.asTypeOf(h.io.memoryResponse.bits))
    h.reset.poke(true.B);h.clock.step();h.reset.poke(false.B)
  }
  for(readOnly<-Seq(true,false))
  it should s"translate both image engines and enforce actual image store permissions readOnly=$readOnly" in {
    simulate(new NativeBinnedGraphicsMemoryFabric(GpuConfig(lanes=4,warps=2),native.copy(translateImageViews=true),32,16)){h=>
      init(h,BigInt("80000030",16))
      val inputs=Vector((41,0x404080,true),(40,0x404000,false),(73,0x4040c0,true),(72,0x404040,false),(0,0x401000,false),(48,0x406000,false))
      val pending=mutable.ArrayBuffer.empty[(Int,BigInt)];var offered: Option[(Int,BigInt)]=None
      val seen=mutable.Set.empty[Int];val lower=mutable.ArrayBuffer.empty[(Int,BigInt)];var index=0;var dmaSent=false;var dmaDone=false
      var held=0;var heldBits: Option[BigInt]=None;var cycles=0
      while((seen.size<inputs.size || !dmaDone) && cycles<3000){
        h.io.renderRequest.valid.poke((index<inputs.size).B)
        if(index<inputs.size){val(id,address,write)=inputs(index)
          h.io.renderRequest.bits.transactionId.poke(id.U);h.io.renderRequest.bits.address.poke(address.U)
          h.io.renderRequest.bits.isWrite.poke(write.B);h.io.renderRequest.bits.sizeLog2.poke(6.U)
          h.io.renderRequest.bits.byteMask.poke(((BigInt(1)<<64)-1).U)}
        h.io.dmaRequest.valid.poke((!dmaSent).B);h.io.dmaRequest.bits.transactionId.poke(16.U)
        h.io.dmaRequest.bits.address.poke(0x408000.U);h.io.dmaRequest.bits.sizeLog2.poke(6.U)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        h.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>h.io.memoryResponse.bits.transactionId.poke(id.U);h.io.memoryResponse.bits.readData.poke(data.U)}
        val response=h.io.renderResponse
        val hold=response.valid.peek().litToBoolean && Set(41,73)(response.bits.transactionId.peek().litValue.toInt) && held<7
        response.ready.poke((!hold).B)
        if(response.valid.peek().litToBoolean){
          val bits=response.bits.peek().litValue;heldBits.foreach(x=>assert(x==bits));heldBits=if(hold)Some(bits)else None
          if(hold){held+=1;h.io.drained.expect(false.B)}else{
            val id=response.bits.transactionId.peek().litValue.toInt;assert(seen.add(id))
            val fail=readOnly && Set(41,73)(id);response.bits.fault.expect(fail.B)
            if(!fail)response.bits.readData.expect((0x1234+id).U)
          }
        }else assert(heldBits.isEmpty)
        if(h.io.dmaResponse.valid.peek().litToBoolean){assert(!dmaDone);dmaDone=true
          h.io.dmaResponse.bits.transactionId.expect(16.U);h.io.dmaResponse.bits.fault.expect(false.B)
          h.io.dmaResponse.bits.readData.expect((0x1234+256).U)}
        val r=h.io.renderRequest.valid.peek().litToBoolean && h.io.renderRequest.ready.peek().litToBoolean
        val d=h.io.dmaRequest.valid.peek().litToBoolean && h.io.dmaRequest.ready.peek().litToBoolean
        if(h.io.memoryRequest.valid.peek().litToBoolean){
          val id=h.io.memoryRequest.bits.transactionId.peek().litValue.toInt;val address=h.io.memoryRequest.bits.address.peek().litValue
          lower+=((id,address))
          if(id>=260){assert(Set(260,261)(id) && address==0x30004)
            pending+=((id,(BigInt(0x800)<<10)|(if(readOnly)0x43 else 0xcf)))}
          else{
            val va=if(id==256)0x408000 else inputs.find(_._1==id).get._2
            assert(address==BigInt(va)+0x400000,s"GNC2 did not translate id=$id VA=${va.toHexString} PA=${address.toString(16)}")
            assert(!(readOnly && Set(41,73)(id)),"denied image stores must not reach physical memory")
            pending+=((id,BigInt(0x1234+id)))
          }
        }
        if(offered.nonEmpty && h.io.memoryResponse.ready.peek().litToBoolean)offered=None
        h.clock.step();if(r)index+=1;if(d)dmaSent=true;cycles+=1
      }
      assert(cycles<3000 && seen==inputs.map(_._1).toSet && dmaDone && held==7)
      val expected=inputs.filterNot(x=>readOnly && x._3).map(_._1).toSet+256
      assert(lower.filter(_._1<260).map(_._1).toSet==expected && lower.filter(_._1>=260).map(_._1).toSet==Set(260,261))
      h.io.renderRequest.valid.poke(false.B);h.io.dmaRequest.valid.poke(false.B);h.io.memoryResponse.valid.poke(false.B)
      h.clock.step(3);h.io.drained.expect(true.B)
      info(s"NATIVE_GNC2_FABRIC readOnly=$readOnly bothImageEnginesTranslated=true descriptorStateDMA=true originalIDs=true imageWritePermission=true heldImageReply=true independentWalkers=true exactDrain=true")
    }
  }
  it should "retain distinct descriptor bin state image and DMA IDs through reordered replies and simultaneous held outputs" in {
    simulate(new NativeBinnedGraphicsMemoryFabric(GpuConfig(lanes=4,warps=2),native,32,16)){h=>init(h)
      val tags=Vector(0,3,16,31,32,40,48,72,80)
      val requests=mutable.ArrayBuffer.empty[(Int,BigInt)]
      val replies=mutable.Set.empty[Int];var dmaReply=false;var index=0;var dmaSent=false;var cycles=0
      var heldRender: Option[BigInt]=None;var heldDma: Option[BigInt]=None
      def tick(): Unit={
        if(h.io.memoryRequest.valid.peek().litToBoolean)requests+=((h.io.memoryRequest.bits.transactionId.peek().litValue.toInt,h.io.memoryRequest.bits.address.peek().litValue))
        val r=h.io.renderResponse
        if(r.valid.peek().litToBoolean){val bits=r.bits.peek().litValue;heldRender.foreach(x=>assert(x==bits));heldRender=if(r.ready.peek().litToBoolean)None else Some(bits)
          if(r.ready.peek().litToBoolean){val id=r.bits.transactionId.peek().litValue.toInt;assert(tags.contains(id) && replies.add(id))
            r.bits.readData.expect((0x12340000L+id).U);r.bits.fault.expect((id==3).B)}}else assert(heldRender.isEmpty)
        val d=h.io.dmaResponse
        if(d.valid.peek().litToBoolean){val bits=d.bits.peek().litValue;heldDma.foreach(x=>assert(x==bits));heldDma=if(d.ready.peek().litToBoolean)None else Some(bits)
          if(d.ready.peek().litToBoolean){assert(!dmaReply);dmaReply=true;d.bits.transactionId.expect(16.U);d.bits.readData.expect((0x12340000L+256).U);d.bits.fault.expect(true.B)}}
        else assert(heldDma.isEmpty)
        h.clock.step();cycles+=1;assert(cycles<2000)
      }
      while(requests.size<tags.size+1){
        h.io.renderRequest.valid.poke((index<tags.size).B)
        if(index<tags.size){h.io.renderRequest.bits.transactionId.poke(tags(index).U);h.io.renderRequest.bits.address.poke((0x10000+index*64).U);h.io.renderRequest.bits.sizeLog2.poke(6.U)}
        h.io.dmaRequest.valid.poke((!dmaSent).B);h.io.dmaRequest.bits.transactionId.poke(16.U);h.io.dmaRequest.bits.address.poke(0x20000.U);h.io.dmaRequest.bits.sizeLog2.poke(6.U)
        val r=h.io.renderRequest.valid.peek().litToBoolean && h.io.renderRequest.ready.peek().litToBoolean
        val d=h.io.dmaRequest.valid.peek().litToBoolean && h.io.dmaRequest.ready.peek().litToBoolean
        tick();if(r)index+=1;if(d)dmaSent=true
      }
      h.io.renderRequest.valid.poke(false.B);h.io.dmaRequest.valid.poke(false.B)
      assert(requests.map(_._1).toSet==tags.toSet+256);h.io.drained.expect(false.B)
      def reply(id: Int): Unit={
        h.io.memoryResponse.valid.poke(true.B);h.io.memoryResponse.bits.transactionId.poke(id.U)
        h.io.memoryResponse.bits.readData.poke((0x12340000L+id).U);h.io.memoryResponse.bits.fault.poke((id==3 || id==256).B)
        var n=0;while(!h.io.memoryResponse.ready.peek().litToBoolean && n<1000){tick();n+=1};assert(n<1000)
        tick();h.io.memoryResponse.valid.poke(false.B)
      }
      h.io.renderResponse.ready.poke(false.B);h.io.dmaResponse.ready.poke(false.B)
      reply(72);reply(0);reply(256)
      for(_<-0 until 9){tick();h.io.renderResponse.valid.expect(true.B);h.io.dmaResponse.valid.expect(true.B);h.io.drained.expect(false.B)}
      h.io.renderResponse.ready.poke(true.B);h.io.dmaResponse.ready.poke(true.B);tick()
      for(id<-tags.reverse if id!=72 && id!=0)reply(id)
      while(replies.size<tags.size || !dmaReply)tick()
      h.clock.step(3);h.io.drained.expect(true.B)
      info("NATIVE_BINNED_FABRIC nativeIDs=256 lowerIDs=512 twoImageEngines=true stateIDsRemainVirtual=true originalRenderIDs=true restoredDMAID=true reorderedReplies=true immutableHeldOutputs=true noEarlyDrain=true")
    }
  }
  it should "translate descriptors bins and both state readers enforce write permissions and bypass both physical image ranges" in {
    simulate(new NativeBinnedGraphicsMemoryFabric(GpuConfig(lanes=4,warps=2),native,32,16)){h=>init(h,BigInt("80000030",16))
      val inputs=Vector(0->BigInt(0x400000),48->BigInt(0x400040),80->BigInt(0x400080),16->BigInt(0x4000c0),
        40->BigInt(0x1000000),72->BigInt(0x1000040))
      val expected=Map(0->BigInt(0x800000),48->BigInt(0x800040),80->BigInt(0x800080),16->BigInt(0x8000c0),
        40->BigInt(0x1000000),72->BigInt(0x1000040))
      val pending=mutable.ArrayBuffer.empty[(Int,BigInt)];var offered: Option[(Int,BigInt)]=None
      val requests=mutable.ArrayBuffer.empty[(Int,BigInt)];val renderReplies=mutable.Set.empty[Int]
      var index=0;var dmaSent=false;var dmaDone=false;var held=0;var cycles=0
      while((renderReplies.size<inputs.size || !dmaDone || pending.nonEmpty || offered.nonEmpty) && cycles<3000){
        h.io.renderRequest.valid.poke((index<inputs.size).B)
        if(index<inputs.size){h.io.renderRequest.bits.transactionId.poke(inputs(index)._1.U);h.io.renderRequest.bits.address.poke(inputs(index)._2.U);h.io.renderRequest.bits.sizeLog2.poke(6.U)}
        h.io.dmaRequest.valid.poke((!dmaSent).B);h.io.dmaRequest.bits.transactionId.poke(16.U);h.io.dmaRequest.bits.address.poke(0x500000.U)
        h.io.dmaRequest.bits.sizeLog2.poke(6.U);h.io.dmaRequest.bits.isWrite.poke(true.B);h.io.dmaRequest.bits.byteMask.poke(((BigInt(1)<<64)-1).U)
        h.io.dmaResponse.ready.poke((held>=9).B)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        h.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>h.io.memoryResponse.bits.transactionId.poke(id.U);h.io.memoryResponse.bits.readData.poke(data.U);h.io.memoryResponse.bits.fault.poke(false.B)}
        val r=h.io.renderRequest.valid.peek().litToBoolean && h.io.renderRequest.ready.peek().litToBoolean
        val d=h.io.dmaRequest.valid.peek().litToBoolean && h.io.dmaRequest.ready.peek().litToBoolean
        if(h.io.memoryRequest.valid.peek().litToBoolean){val id=h.io.memoryRequest.bits.transactionId.peek().litValue.toInt;val address=h.io.memoryRequest.bits.address.peek().litValue
          requests+=((id,address));assert(id!=256,"read-only Sv32 mapping must reject DMA store before physical issue")
          if(id>=260){assert(address==0x30004 && (id==260 || id==261));pending+=((id,(BigInt(0x800)<<10)|0x43))}
          else {assert(expected.get(id).contains(address),s"wrong physical routing id=$id address=${address.toString(16)}");pending+=((id,BigInt(0x1234+id)))}}
        if(offered.nonEmpty && h.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(h.io.renderResponse.valid.peek().litToBoolean){val id=h.io.renderResponse.bits.transactionId.peek().litValue.toInt
          h.io.renderResponse.bits.fault.expect(false.B);h.io.renderResponse.bits.readData.expect((0x1234+id).U);assert(renderReplies.add(id))}
        if(h.io.dmaResponse.valid.peek().litToBoolean){h.io.dmaResponse.bits.transactionId.expect(16.U);h.io.dmaResponse.bits.fault.expect(true.B)
          if(held<9){h.io.drained.expect(false.B);held+=1}else dmaDone=true}
        h.clock.step();if(r)index+=1;if(d)dmaSent=true;cycles+=1
      }
      assert(cycles<3000 && renderReplies==inputs.map(_._1).toSet && dmaDone && held==9)
      h.io.renderRequest.valid.poke(false.B);h.io.dmaRequest.valid.poke(false.B);h.io.memoryResponse.valid.poke(false.B)
      h.clock.step(3);h.io.drained.expect(true.B);assert(requests.filter(_._1>=260).map(_._1).toSet==Set(260,261))
      info("NATIVE_BINNED_FABRIC Sv32=true descriptorBinStateTranslation=true bothImageEnginesBypass=true readOnlyWriteFault=true independentWalkIDs260x261=true heldLocalFaultPinsVM=true noEarlyDrain=true")
    }
  }
}
