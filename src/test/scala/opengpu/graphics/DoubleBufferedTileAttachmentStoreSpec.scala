package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

/** Include the vendor's unmodified functional SRAM model only in simulation.
  * Production emission retains the characterized external macro references.
  */
private[graphics] class TileSramSimulationResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle { val present=Output(Bool()) })
  private val vendor=Files.readString(Paths.get(
    "depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v"))
  setInline("TileSramSimulationResources.sv",
    "module TileSramSimulationResources(output present); assign present=1'b1; endmodule\n"+vendor)

}

private class SramBackedDoubleTile(coalesced: Boolean=true,buffered: Boolean=true) extends DoubleBufferedTileAttachmentStore(GpuConfig(lanes=4,warps=2),
  screenWidth=64,screenHeight=64,storeCredits=8,useSramBlackBoxes=true,coalesceTransfers=coalesced,bufferedWriteAcks=buffered) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new TileSramSimulationResources)
  macroModelPresent:=resources.io.present
}

class DoubleBufferedTileAttachmentStoreSpec extends AnyFlatSpec {
  import TilePassTestData._
  private val credits=2
  private val perSlot=credits+1
  private def store(blackBoxes: Boolean=false,window: Int=credits,coalesced: Boolean=true,buffered: Boolean=true): DoubleBufferedTileAttachmentStore =
    if (blackBoxes) { require(window==8); new SramBackedDoubleTile(coalesced,buffered) }
    else new DoubleBufferedTileAttachmentStore(GpuConfig(lanes=4,warps=2),
      screenWidth=64,screenHeight=64,storeCredits=window,coalesceTransfers=coalesced,bufferedWriteAcks=buffered)
  case class Request(tid: Int,address: Long,write: Boolean,data: BigInt,mask: BigInt,due: Int)
  case class Result(id: Int,status: Int,loads: Long,stores: Long,resolves: Long)

  private class Driver(val d: DoubleBufferedTileAttachmentStore,window: Int=credits,latency: Int=2,writeLimit: Int=100,maxWait: Int=20000) {
    private val perSlot=window+1
    val memory=mutable.LongMap.empty[Long]
    val pending=mutable.ArrayBuffer.empty[Request]
    val acceptedWrites=mutable.ArrayBuffer.empty[Request]
    val live=mutable.Set.empty[Int]
    var returning=Option.empty[Request]
    var responseData=BigInt(0)
    var responseFault=false
    var holdRequests=false
    val holdWrites=Array(false,false)
    var faultSlot=Option.empty[Int]
    val reads=Array(0,0); val writes=Array(0,0); val writeWords=Array(0,0)
    var cycle=0
    private var held=Option.empty[BigInt]
    d.io.pass.begin.valid.poke(false.B); d.io.pass.begin.bits.poke(0.U.asTypeOf(d.io.pass.begin.bits))
    d.io.pass.end.valid.poke(false.B); d.io.pass.end.bits.poke(true.B)
    d.io.pass.clear.valid.poke(false.B); d.io.pass.clear.bits.poke(0.U.asTypeOf(d.io.pass.clear.bits))
    d.io.pass.clearDone.ready.poke(true.B); d.io.pass.completion.ready.poke(false.B)
    d.io.pass.cancel.poke(false.B); d.io.pass.memoryFault.poke(false.B)
    d.io.fragmentX.poke(0.U); d.io.fragmentY.poke(0.U)
    d.io.producerDrained.poke(true.B); d.io.omDrained.poke(true.B); d.io.executionFault.poke(false.B)
    d.io.wordIndex.poke(0.U); d.io.depthPlane.poke(false.B)
    d.io.om.req.valid.poke(false.B); d.io.om.req.bits.poke(0.U.asTypeOf(d.io.om.req.bits))
    d.io.om.resp.ready.poke(false.B)
    d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def step(): Unit = {
      val writeLive=pending.count(_.write)+returning.count(_.write)
      val ready= !holdRequests && cycle%5!=2 &&
        !(d.io.memoryRequest.bits.isWrite.peek().litToBoolean && writeLive>=writeLimit)
      d.io.memoryRequest.ready.poke(ready.B)
      held.foreach { bits=>d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue==bits) }
      held=if (d.io.memoryRequest.valid.peek().litToBoolean && !ready) Some(d.io.memoryRequest.bits.peek().litValue) else None
      if (returning.isEmpty) {
        val i=pending.lastIndexWhere(r=>r.due<=cycle && !(r.write && holdWrites(r.tid/perSlot)))
        if (i>=0) {
          returning=Some(pending.remove(i))
          val r=returning.get
          responseData=(0 until 16).foldLeft(BigInt(0))((v,n)=>v|
            (BigInt(memory.getOrElse(r.address+n*4L,0xdeadbeefL))<<(32*n)))
          responseFault=faultSlot.contains(r.tid/perSlot)
          if (responseFault) faultSlot=None
        }
      }
      d.io.memoryResponse.valid.poke(returning.nonEmpty.B)
      returning.foreach { r=>
        d.io.memoryResponse.bits.transactionId.poke(r.tid.U)
        d.io.memoryResponse.bits.readData.poke(responseData.U(512.W))
        d.io.memoryResponse.bits.fault.poke(responseFault.B)
      }
      if (returning.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
        val r=returning.get
        if (r.write && !responseFault) for (n <- 0 until 16 if ((r.mask>>(4*n))&15)==15)
          memory(r.address+n*4L)=((r.data>>(32*n))&0xffffffffL).toLong
        assert(live.remove(r.tid)); returning=None
      }
      if (d.io.memoryRequest.valid.peek().litToBoolean && ready) {
        val b=d.io.memoryRequest.bits; val tid=b.transactionId.peek().litValue.toInt
        assert(live.add(tid)); assert(tid<2*perSlot)
        val write=b.isWrite.peek().litToBoolean
        if (write) { writes(tid/perSlot)+=1; writeWords(tid/perSlot)+=b.byteMask.peek().litValue.bitCount/4 } else reads(tid/perSlot)+=1
        val request=Request(tid,b.address.peek().litValue.toLong,write,b.writeData.peek().litValue,
          b.byteMask.peek().litValue,cycle+latency+tid%4)
        pending+=request
        if(write)acceptedWrites+=request
      }
      d.io.mem.req.valid.expect(false.B)
      d.clock.step(); cycle+=1
    }
    def until(p: => Boolean,max: Int=maxWait): Unit = {
      val start=cycle
      while (!p && cycle-start<max) step()
      assert(p,s"stalled at $cycle live=$live pending=$pending")
    }
    def begin(p: Pass): Unit = {
      pokeDescriptor(d.io.pass.begin.bits,p); d.io.pass.begin.valid.poke(true.B)
      until(d.io.pass.begin.ready.peek().litToBoolean); step(); d.io.pass.begin.valid.poke(false.B)
      d.io.pass.begin.bits.poke(0.U.asTypeOf(d.io.pass.begin.bits))
      until(d.io.pass.end.ready.peek().litToBoolean || d.io.pass.completion.valid.peek().litToBoolean)
    }
    def om(p: Pass,x: Int,y: Int,depth: Boolean,data: Option[Long]=None,sample: Int=0): Long = {
      d.io.producerDrained.poke(false.B); d.io.omDrained.poke(false.B)
      d.io.fragmentX.poke((p.originX+x).U); d.io.fragmentY.poke((p.originY+y).U)
      d.io.wordIndex.poke(p.index(x,y,sample).U); d.io.depthPlane.poke(depth.B)
      d.io.om.req.bits.addr.poke(p.address(x,y,sample,depth).U)
      d.io.om.req.bits.write.poke(data.nonEmpty.B); d.io.om.req.bits.data.poke(data.getOrElse(0L).U)
      d.io.om.req.valid.poke(true.B); until(d.io.om.req.ready.peek().litToBoolean)
      step(); d.io.om.req.valid.poke(false.B)
      until(d.io.om.resp.valid.peek().litToBoolean)
      val result=d.io.om.resp.bits.data.peek().litValue.toLong
      d.io.om.resp.bits.addr.expect(p.address(x,y,sample,depth).U)
      d.io.om.resp.ready.poke(true.B); step(); d.io.om.resp.ready.poke(false.B)
      d.io.producerDrained.poke(true.B); d.io.omDrained.poke(true.B)
      result
    }
    def end(): Unit = {
      d.io.pass.end.valid.poke(true.B); until(d.io.pass.end.ready.peek().litToBoolean)
      step(); d.io.pass.end.valid.poke(false.B)
    }
    def retire(): Result = {
      until(d.io.pass.completion.valid.peek().litToBoolean)
      val bits=d.io.pass.completion.bits.peek().litValue
      val counts=d.io.completionBytes.peek().litValue
      val result=Result(d.io.pass.completion.bits.identity.pass.peek().litValue.toInt,
        d.io.pass.completion.bits.status.peek().litValue.toInt,
        d.io.completionBytes.loadBytes.peek().litValue.toLong,
        d.io.completionBytes.storeBytes.peek().litValue.toLong,
        d.io.completionBytes.resolveBytes.peek().litValue.toLong)
      for (_ <- 0 until 4) {
        step(); d.io.pass.completion.valid.expect(true.B)
        assert(d.io.pass.completion.bits.peek().litValue==bits && d.io.completionBytes.peek().litValue==counts)
      }
      d.io.pass.completion.ready.poke(true.B); step(); d.io.pass.completion.ready.poke(false.B)
      result
    }
  }

  it should "load and render a second tile while the first retains every store acknowledgement" in {
    simulate(store()) { dut=>
      val d=new Driver(dut)
      val first=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3)
      val second=first.copy(originX=8,id=8,colorLoad=AttachmentLoadOp.load,depthLoad=AttachmentLoadOp.load)
      for (z <- Seq(false,true); y <- 0 until 8; x <- 0 until 8)
        d.memory(second.address(x,y,0,z))=if (z) 0xabcdef01L else 0x98765432L
      d.holdWrites(0)=true
      d.begin(first); d.end(); d.until(d.writes(0)>0)
      d.begin(second)
      assert(d.om(second,0,0,false)==0x98765432L)
      assert(d.om(second,0,0,true)==0xabcdef01L)
      d.om(second,0,0,false,Some(0x11223344L)); d.om(second,0,0,true,Some(0x55667788L))
      d.end(); d.until(d.writes(1)==2 && !d.live.exists(_/perSlot==1))
      assert(d.live.exists(_/perSlot==0) && d.reads(1)==2)
      assert(d.memory(second.address(0,0,0,false))==0x11223344L)
      dut.io.pass.completion.valid.expect(false.B); dut.io.occupied.expect(3.U)
      d.holdWrites(0)=false
      assert(d.retire()==Result(7,0,0,512,0)); assert(d.retire()==Result(8,0,8,8,0))
      assert(d.live.isEmpty && d.pending.isEmpty)
      for (z <- Seq(false,true); y <- 0 until 8; x <- 0 until 8)
        assert(d.memory(first.address(x,y,0,z))==(if (z) first.depthClear else first.colorClear))
      dut.io.occupied.expect(0.U)
      info(s"DOUBLE_TILE overlapReadBytes=8 storeBytes=520 cycles=${d.cycle}")
    }
  }

  it should "reject overlapping attachment owners without touching the second allocation" in {
    simulate(store()) { dut=>
      val d=new Driver(dut)
      val p=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3)
      d.holdWrites(0)=true; d.begin(p); d.end(); d.until(d.writes(0)>0)
      // Invalid admission has no end-ready signal and is ordered behind the
      // stalled first completion, so drive begin directly rather than waiting.
      pokeDescriptor(dut.io.pass.begin.bits,p.copy(id=8)); dut.io.pass.begin.valid.poke(true.B)
      d.until(dut.io.pass.begin.ready.peek().litToBoolean); d.step(); dut.io.pass.begin.valid.poke(false.B)
      for (_ <- 0 until 30) d.step()
      assert(d.reads(1)==0 && d.writes(1)==0)
      d.holdWrites(0)=false
      assert(d.retire().status==0); assert(d.retire()==Result(8,TilePassStatus.invalidDescriptor,0,0,0))
      d.begin(p.copy(id=9)); d.end(); assert(d.retire().status==0)
    }
  }

  it should "preserve the last sample in separate SRAM-macro banks while slots overlap" in {
    simulate(store(blackBoxes=true,window=8)) { dut=>
      val d=new Driver(dut,window=8)
      val a=Pass(mode=2,width=16,height=16,originX=0,originY=0,pitch=512)
      val b=a.copy(originY=16,id=8,colorLoad=AttachmentLoadOp.load,depthLoad=AttachmentLoadOp.load)
      d.holdWrites(0)=true
      d.begin(a); d.om(a,15,15,false,Some(0xaabbccddL),sample=3)
      assert(d.om(a,15,15,false,sample=3)==0xaabbccddL)
      d.end(); d.until(d.writes(0)>0)
      d.begin(b)
      assert(d.om(b,15,15,false,sample=3)==0xdeadbeefL)
      assert(d.om(b,15,15,true,sample=3)==0xdeadbeefL)
      d.om(b,15,15,false,Some(0x55667788L),sample=3)
      d.om(b,15,15,true,Some(0x12345678L),sample=3)
      assert(d.om(b,15,15,false,sample=3)==0x55667788L)
      d.end(); d.until(d.writes(1)==2 && !d.live.exists(_/9==1))
      d.holdWrites(0)=false
      assert(d.retire()==Result(7,0,0,8192,0)); assert(d.retire()==Result(8,0,8,8,0))
      assert(d.memory(a.address(15,15,3,false))==0xaabbccddL)
      assert(d.memory(b.address(15,15,3,false))==0x55667788L)
      assert(d.memory(b.address(15,15,3,true))==0x12345678L)
    }
  }

  it should "attribute a late store fault to its original slot while the next tile continues" in {
    simulate(store()) { dut=>
      val d=new Driver(dut)
      val p=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3)
      d.holdWrites(0)=true; d.begin(p); d.end(); d.until(d.writes(0)>0)
      d.begin(p.copy(originX=8,id=8)); d.om(p.copy(originX=8,id=8),0,0,false,Some(0x12345678L))
      d.faultSlot=Some(0); d.holdWrites(0)=false
      for (_ <- 0 until 15) d.step()
      d.end()
      assert(d.retire().status==TilePassStatus.memoryFault)
      assert(d.retire().status==0 && d.live.isEmpty)
      assert(d.memory(p.copy(originX=8).address(0,0,0,false))==0x12345678L)
    }
  }

  it should "keep per-tile MSAA resolve bytes and images separate under reordered transfers" in {
    simulate(store()) { dut=>
      val d=new Driver(dut)
      val a=Pass(mode=2,width=5,height=3,originX=0,originY=0,pitch=1024,resolve=true,resolvePitch=256)
      val b=a.copy(originX=16,id=8,colorClear=0x55667788L,depthClear=0x12345678L)
      d.holdWrites(0)=true; d.begin(a); d.end(); d.until(d.writes(0)>0)
      d.begin(b); d.end()
      for (_ <- 0 until 40) d.step()
      d.holdWrites(0)=false
      assert(d.retire()==Result(7,0,0,540,60)); assert(d.retire()==Result(8,0,0,540,60))
      for (p <- Seq(a,b); y <- 0 until 3; x <- 0 until 5) {
        assert(d.memory(p.resolveAddress(x,y))==p.colorClear)
        for (s <- 0 until 4; z <- Seq(false,true))
          assert(d.memory(p.address(x,y,s,z))==(if (z) p.depthClear else p.colorClear))
      }
    }
  }

  it should "drain an offered second-slot read on cancel and retain late fault priority" in {
    simulate(store()) { dut=>
      val d=new Driver(dut)
      val a=Pass(width=8,height=8,originX=0,originY=0,pitch=128,tileLog2=3)
      val b=a.copy(originX=8,id=8,colorLoad=AttachmentLoadOp.load,depthLoad=AttachmentLoadOp.load)
      d.holdWrites(0)=true; d.begin(a); d.end(); d.until(d.writes(0)>0)
      d.begin(b); d.holdRequests=true
      dut.io.producerDrained.poke(false.B); dut.io.omDrained.poke(false.B)
      dut.io.fragmentX.poke(b.originX.U); dut.io.fragmentY.poke(b.originY.U)
      dut.io.wordIndex.poke(b.index(0,0).U); dut.io.depthPlane.poke(false.B)
      dut.io.om.req.bits.addr.poke(b.address(0,0,0,false).U)
      dut.io.om.req.bits.data.poke(0.U); dut.io.om.req.bits.write.poke(false.B)
      dut.io.om.req.valid.poke(true.B); d.until(dut.io.om.req.ready.peek().litToBoolean)
      d.step(); dut.io.om.req.valid.poke(false.B)
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      val offered=dut.io.memoryRequest.bits.peek().litValue
      dut.io.pass.cancel.poke(true.B); d.step(); dut.io.pass.cancel.poke(false.B)
      for (_ <- 0 until 5) {
        d.step(); dut.io.memoryRequest.valid.expect(true.B)
        assert(dut.io.memoryRequest.bits.peek().litValue==offered)
        dut.io.pass.completion.valid.expect(false.B)
      }
      d.faultSlot=Some(1); d.holdRequests=false; d.holdWrites(0)=false
      d.until(dut.io.om.resp.valid.peek().litToBoolean)
      dut.io.om.resp.bits.data.expect(0.U); dut.io.om.resp.ready.poke(true.B)
      d.step(); dut.io.om.resp.ready.poke(false.B)
      dut.io.producerDrained.poke(true.B); dut.io.omDrained.poke(true.B)
      assert(d.retire().status==TilePassStatus.cancelled)
      assert(d.retire().status==TilePassStatus.memoryFault)
      assert(d.live.isEmpty && d.pending.isEmpty)
      d.begin(a.copy(id=9)); d.end(); assert(d.retire().status==0)
    }
  }
  it should "reduce clear-heavy SRAM export traffic and cycles at a fixed external write window" in {
    val measured=mutable.ArrayBuffer.empty[(String,Int,Int)]
    for ((profile,coalesced,buffered) <- Seq(("generic",false,false),("strict",true,false),("buffered",true,true)))
      simulate(store(blackBoxes=true,window=8,coalesced=coalesced,buffered=buffered)) { dut=>
      val d=new Driver(dut,window=8,latency=80,writeLimit=1,maxWait=100000)
      val p=Pass(width=16,height=16,originX=0,originY=0,pitch=64)
      d.begin(p)
      val selected=(0 until 16).map(i=>(if (i==15) 15 else (i*7)%16,i))
      for (((x,y),i) <- selected.zipWithIndex) {
        d.om(p,x,y,false,Some(0x80000000L+i)); d.om(p,x,y,true,Some(0x10000000L+i))
      }
      d.end(); val r=d.retire()
      assert(r==Result(7,0,0,2048,0) && d.reads.sum==0 && d.writeWords.sum==512)
      for (z <- Seq(false,true); y <- 0 until 16; x <- 0 until 16) {
        val i=selected.indexOf((x,y))
        val expected=if (i>=0) (if (z) 0x10000000L+i else 0x80000000L+i) else if (z) p.depthClear else p.colorClear
        assert(d.memory(p.address(x,y,0,z))==expected)
      }
      measured+=((profile,d.writes.sum,d.cycle))
      info(s"ATTACHMENT_SRAM_EXPORT profile=$profile words=512 lines=${d.writes.sum} cycles=${d.cycle} storeBytes=${r.stores}")
      if(profile=="buffered")for(r <- d.acceptedWrites if r.mask.bitCount!=64)
        info(s"BUFFERED_PARTIAL_LINE address=${r.address.toHexString} mask=${r.mask.toString(16)} due=${r.due}")
    }
    assert(measured.map(_._2).toSeq==Seq(512,64,32))
    assert(measured(1)._3*3<measured(0)._3)
    assert(measured(2)._3<measured(1)._3)
  }

  it should "measure complete SRAM exports separately from preparation for clear and dense sample planes" in {
    for(mode <- Seq(0,2);dirty <- Seq(false,true))simulate(store(blackBoxes=true,window=8)) { dut=>
      val d=new Driver(dut,window=8,latency=2,writeLimit=8,maxWait=100000)
      val p=Pass(mode=mode,width=16,height=16,originX=0,originY=0,pitch=64L*(1<<mode))
      def value(z: Boolean,x: Int,y: Int,s: Int): Long=
        (if(z)0xda7a0000L else 0xc0100000L)|(y*16*(1<<mode)+x*(1<<mode)+s)
      d.begin(p)
      if(dirty)for(z <- Seq(false,true);y <- 0 until 16;x <- 0 until 16;s <- 0 until (1<<mode))
        d.om(p,x,y,z,Some(value(z,x,y,s)),s)
      val prepared=d.cycle;d.end();val r=d.retire();val elapsed=d.cycle-prepared
      val words=512*(1<<mode)
      assert(r==Result(7,0,0,words*4L,0) && d.writeWords.sum==words && d.reads.sum==0)
      for(z <- Seq(false,true);y <- 0 until 16;x <- 0 until 16;s <- 0 until (1<<mode)) {
        val expected=if(dirty)value(z,x,y,s) else if(z)p.depthClear else p.colorClear
        assert(d.memory(p.address(x,y,s,z))==expected)
      }
      info(s"TILE_EXPORT_STREAM mode=$mode dirty=$dirty words=$words lines=${d.writes.sum} preparationCycles=$prepared exportCycles=$elapsed totalCycles=${d.cycle}")
    }
  }
  for(fault <- Seq(false,true))it should s"retain locally completed buffered owners through cancellation and late B fault=$fault" in {
    simulate(store()) { h=>
      val d=new Driver(h);val p=Pass(width=1,height=1,originX=0,originY=0,pitch=64,tileLog2=3)
      d.holdWrites(0)=true;d.begin(p);d.end();d.until(d.writes(0)==2)
      // Only two clear words exist. Both private replies retire before the
      // idle timer seals their separate lines; the SRAM child is locally done.
      for(_ <- 0 until 20){d.step();h.io.pass.completion.valid.expect(false.B)}
      h.io.pass.cancel.poke(true.B);d.step();h.io.pass.cancel.poke(false.B)
      if(fault)d.faultSlot=Some(0)
      d.holdWrites(0)=false
      assert(d.retire().status==(if(fault)TilePassStatus.memoryFault else TilePassStatus.cancelled))
      d.begin(p.copy(id=8));d.end();assert(d.retire()==Result(8,0,0,8,0))
    }
  }

  it should "freeze a fully drained buffered completion while cancellation is asserted" in {
    simulate(store()) { h=>
      val d=new Driver(h);val p=Pass(width=1,height=1,originX=0,originY=0,pitch=64,tileLog2=3)
      d.begin(p);d.end();d.until(h.io.pass.completion.valid.peek().litToBoolean)
      val bits=h.io.pass.completion.bits.peek().litValue
      h.io.pass.cancel.poke(true.B)
      for(_ <- 0 until 6){d.step();h.io.pass.completion.valid.expect(true.B);assert(h.io.pass.completion.bits.peek().litValue==bits)}
      h.io.pass.cancel.poke(false.B);assert(d.retire()==Result(7,0,0,8,0))
    }
  }

}
