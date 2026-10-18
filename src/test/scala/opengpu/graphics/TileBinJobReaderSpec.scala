package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinJobReaderSpec extends AnyFlatSpec {
  import TileBinJobTestData._
  private val cfg=GpuConfig(lanes=4,warps=2)
  private class Driver(val h: TileBinJobReader,header: Vector[Long]=descriptor(),refs: Vector[Ref]=references(3),
    val base: Long=descriptorBase) {
    case class Read(id: Int,address: Long,due: Int,fault: Boolean)
    val memory=mutable.LongMap.empty[Long]
    for ((w,i) <- header.zipWithIndex) memory(base+4L*i)=w
    for ((r,i) <- refs.zipWithIndex; (w,k) <- r.words.zipWithIndex) memory(header(5)+32L*i+4L*k)=w
    val pending=mutable.ArrayBuffer.empty[Read]; val requests=mutable.ArrayBuffer.empty[Read]
    val appends=mutable.ArrayBuffer.empty[Ref]; val live=mutable.Set.empty[Int]
    var returning=Option.empty[Read]; var cycle=0; var peakLive=0; var begins=0; var ends=0
    var holdRequests=false; var holdResponses=false; var holdBegin=false; var holdAppend=false; var holdReplies=false
    var fault: Long=>Boolean = _=>false
    var rootStatus=0; var appendStatus=0; var forceRootEnd=false
    var rootRunning=false; var rootEnded=false; var rootCancelled=false
    var reply=Option.empty[(Ref,Int,Int)]
    var heldRequest=Option.empty[BigInt]; var heldBegin=Option.empty[BigInt]
    private val io=h.io
    io.submit.valid.poke(false.B); io.submit.bits.poke(0.U.asTypeOf(io.submit.bits)); io.cancel.poke(false.B)
    io.begin.ready.poke(false.B); io.append.ready.poke(false.B); io.end.ready.poke(false.B)
    io.appended.valid.poke(false.B); io.appended.bits.poke(0.U.asTypeOf(io.appended.bits))
    io.renderCompletion.valid.poke(false.B); io.renderCompletion.bits.poke(0.U.asTypeOf(io.renderCompletion.bits))
    io.memoryRequest.ready.poke(false.B); io.memoryResponse.valid.poke(false.B)
    io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits)); io.completion.ready.poke(false.B)
    h.reset.poke(true.B); h.clock.step(); h.reset.poke(false.B)
    def step(): Unit = {
      io.begin.ready.poke((!holdBegin && cycle%5!=1).B)
      io.append.ready.poke((!holdAppend && cycle%7!=2).B)
      io.end.ready.poke((cycle%5!=2).B)
      io.memoryRequest.ready.poke((!holdRequests && cycle%5!=2).B)
      heldRequest.foreach(x=>{io.memoryRequest.valid.expect(true.B); assert(io.memoryRequest.bits.peek().litValue==x)})
      heldRequest=if (io.memoryRequest.valid.peek().litToBoolean && !io.memoryRequest.ready.peek().litToBoolean) Some(io.memoryRequest.bits.peek().litValue) else None
      heldBegin.foreach(x=>{io.begin.valid.expect(true.B); assert(io.begin.bits.peek().litValue==x)})
      heldBegin=if (io.begin.valid.peek().litToBoolean && !io.begin.ready.peek().litToBoolean) Some(io.begin.bits.peek().litValue) else None
      if (returning.isEmpty && !holdResponses) {
        val i=pending.lastIndexWhere(_.due<=cycle)
        if (i>=0) returning=Some(pending.remove(i))
      }
      io.memoryResponse.valid.poke(returning.nonEmpty.B)
      returning.foreach { r=>
        io.memoryResponse.bits.transactionId.poke(r.id.U); io.memoryResponse.bits.fault.poke(r.fault.B)
        val data=(0 until 16).foldLeft(BigInt(0))((n,i)=>n|(BigInt(memory.getOrElse(r.address+4L*i,0L)&0xffffffffL)<<(32*i)))
        io.memoryResponse.bits.readData.poke(data.U)
      }
      io.appended.valid.poke((reply.exists(_._2<=cycle) && !holdReplies).B)
      reply.foreach { case (r,_,status)=>
        val b=io.appended.bits.result
        b.poke(0.U.asTypeOf(b)); b.identity.context.poke(3.U); b.identity.pass.poke(7.U); b.identity.generation.poke(11.U)
        b.tile.poke(r.tile.U); b.reference.primitive.poke(r.primitive.U); b.reference.state.poke(r.state.U)
        b.reference.sequence.poke(r.sequence.U); b.reference.token.poke(r.token.U); b.status.poke(status.U)
      }
      if (io.cancelCore.peek().litToBoolean) { rootEnded=true; rootCancelled=true }
      if (forceRootEnd && rootRunning) rootEnded=true
      io.renderCompletion.valid.poke((rootRunning && rootEnded && reply.isEmpty).B)
      io.renderCompletion.bits.poke(0.U.asTypeOf(io.renderCompletion.bits))
      io.renderCompletion.bits.identity.context.poke(3.U); io.renderCompletion.bits.identity.pass.poke(7.U)
      io.renderCompletion.bits.identity.generation.poke(11.U)
      io.renderCompletion.bits.status.poke((if (rootCancelled && rootStatus==0) PrimitiveBinStatus.cancelled else rootStatus).U)
      if (io.begin.valid.peek().litToBoolean && io.begin.ready.peek().litToBoolean) { begins+=1; rootRunning=true; rootEnded=false; rootCancelled=false }
      if (io.appended.valid.peek().litToBoolean && io.appended.ready.peek().litToBoolean) {
        if (reply.get._3!=0) rootEnded=true
        reply=None
      }
      if (io.append.valid.peek().litToBoolean && io.append.ready.peek().litToBoolean) {
        assert(reply.isEmpty)
        val b=io.append.bits; val r=Ref(b.tile.peek().litValue.toLong,b.reference.primitive.peek().litValue.toLong,
          b.reference.state.peek().litValue.toLong,b.reference.sequence.peek().litValue.toLong,b.reference.token.peek().litValue.toLong)
        appends+=r; reply=Some((r,cycle+5,appendStatus))
      }
      if (io.end.valid.peek().litToBoolean && io.end.ready.peek().litToBoolean) { ends+=1; rootEnded=true }
      if (io.renderCompletion.valid.peek().litToBoolean && io.renderCompletion.ready.peek().litToBoolean) rootRunning=false
      if (returning.nonEmpty && io.memoryResponse.ready.peek().litToBoolean) { assert(live.remove(returning.get.id)); returning=None }
      if (io.memoryRequest.valid.peek().litToBoolean && io.memoryRequest.ready.peek().litToBoolean) {
        val b=io.memoryRequest.bits; val id=b.transactionId.peek().litValue.toInt; val addr=b.address.peek().litValue.toLong
        b.isWrite.expect(false.B); b.sizeLog2.expect(6.U)
        assert(addr%64==0 && live.add(id))
        val r=Read(id,addr,cycle+25,fault(addr)); requests+=r; pending+=r; peakLive=peakLive.max(live.size)
      }
      h.clock.step(); cycle+=1
    }
    def until(p: => Boolean): Unit = { val start=cycle; while (!p && cycle-start<10000) step(); assert(p,s"stalled at $cycle live=$live reply=$reply") }
    def submit(limit: Long=base+512,envelope: Boolean=true): Unit = {
      io.submit.bits.commandId.poke(37.U); io.submit.bits.base.poke(base.U); io.submit.bits.limit.poke(limit.U)
      io.submit.bits.envelopeValid.poke(envelope.B); io.submit.valid.poke(true.B)
      until(io.submit.ready.peek().litToBoolean); step(); io.submit.valid.poke(false.B)
      io.submit.bits.poke(0.U.asTypeOf(io.submit.bits))
    }
    def finish(status: Int,valid: Boolean): Unit = {
      until(io.completion.valid.peek().litToBoolean)
      assert(live.isEmpty && pending.isEmpty && returning.isEmpty && reply.isEmpty)
      io.completion.bits.commandId.expect(37.U); io.completion.bits.status.expect(status.U)
      io.completion.bits.renderValid.expect(valid.B); val held=io.completion.bits.peek().litValue
      for (i <- 0 until 4) {
        io.cancel.poke((i==1).B); step(); io.completion.valid.expect(true.B)
        assert(io.completion.bits.peek().litValue==held); io.submit.ready.expect(false.B)
      }
      io.cancel.poke(false.B); io.completion.ready.poke(true.B); step(); io.completion.ready.poke(false.B)
      io.busy.expect(false.B)
    }
  }

  it should "snapshot all descriptor fields, reorder eight reads and consume each append once" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdBegin=true; d.submit(); d.until(h.io.begin.valid.peek().litToBoolean)
      assert(d.requests.size==8 && d.peakLive==8)
      h.io.begin.bits.render.shaderBase.expect(0xb0000.U); h.io.begin.bits.render.replay.bins.pool.limit.expect(0x10100.U)
      h.io.begin.bits.render.attachments.color.clearValue.expect(TileBinRenderTestData.colorClear.U)
      h.io.begin.bits.render.attachments.color.loadOp.expect(AttachmentLoadOp.clear.U)
      val held=h.io.begin.bits.peek().litValue
      for (i <- 0 until 128) d.memory(descriptorBase+4L*i)=0L
      for (_ <- 0 until 5) { d.step(); assert(h.io.begin.bits.peek().litValue==held) }
      d.holdBegin=false; d.finish(0,true)
      assert(d.appends.toVector==references(3) && d.begins==1 && d.ends==1)
      assert(d.requests.count(_.id==8)==2)
      h.io.completion.bits.descriptorReadBytes.expect(512.U); h.io.completion.bits.appendReadBytes.expect(128.U)
      h.io.completion.bits.acceptedAppends.expect(3.U)
    }
  }

  it should "reject invalid submit allocations before issuing memory requests" in {
    for ((base,limit,envelope) <- Seq((descriptorBase+4,descriptorBase+516,true),
      (descriptorBase,descriptorBase+511,true),(0xffffff00L,0x100000000L,true),
      (descriptorBase,0x100000001L,true),(descriptorBase,descriptorBase+512,false))) {
      simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
        val d=new Driver(h,base=base); d.submit(limit,envelope); d.finish(PrimitiveBinStatus.invalidDescriptor,false)
        assert(d.requests.isEmpty && d.begins==0)
      }
    }
  }

  it should "accept the last complete descriptor allocation below four GiB" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h,header=descriptor(0),refs=Vector.empty,base=0xfffffe00L)
      d.submit(0x100000000L); d.finish(0,true)
      assert(d.requests.size==8 && d.requests.last.address==0xffffffc0L && d.ends==1)
    }
  }

  it should "reject reserved encodings and unsafe append allocations before beginning rendering" in {
    val cases=Seq((0,0L,TileBinRenderStatus.invalidEncoding),(1,64L,TileBinRenderStatus.invalidEncoding),
      (19,0x10000L,TileBinRenderStatus.invalidEncoding),(49,0x18000L,TileBinRenderStatus.invalidEncoding),(74,1L,TileBinRenderStatus.invalidEncoding),
      (7,2L,TileBinRenderStatus.invalidEncoding),(13,1L<<31,TileBinRenderStatus.invalidEncoding),
      (8,0xffffffffL,PrimitiveBinStatus.invalidDescriptor),(6,appendBase+64,PrimitiveBinStatus.invalidDescriptor),
      (5,TileBinRenderTestData.binBase,PrimitiveBinStatus.invalidDescriptor),
      (5,0x80000L,PrimitiveBinStatus.invalidDescriptor),(5,0xb0000L,PrimitiveBinStatus.invalidDescriptor))
    for ((word,value,status) <- cases) simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h,header=descriptor().updated(word,value)); d.submit(); d.finish(status,false)
      assert(d.begins==0 && d.requests.size==8)
    }
  }

  it should "retain a stalled memory offer through cancellation and preserve a late read fault" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdRequests=true; d.fault=_=>true; d.submit()
      d.until(h.io.memoryRequest.valid.peek().litToBoolean); h.io.cancel.poke(true.B); d.step(); h.io.cancel.poke(false.B)
      for (_ <- 0 until 5) d.step()
      d.holdRequests=false; d.finish(PrimitiveBinStatus.memoryFault,false)
      assert(d.requests.size==1 && d.begins==0)
    }
  }

  it should "retain an offered begin through cancellation and drain its renderer before completion" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdBegin=true; d.submit(); d.until(h.io.begin.valid.peek().litToBoolean)
      h.io.cancel.poke(true.B); d.step(); h.io.cancel.poke(false.B)
      for (_ <- 0 until 5) { d.step(); h.io.cancelCore.expect(false.B) }
      d.holdBegin=false; d.finish(PrimitiveBinStatus.cancelled,true)
      assert(d.begins==1 && d.appends.isEmpty)
    }
  }

  it should "cancel accepted appends only after their matching reply drains" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdReplies=true; d.submit(); d.until(d.appends.nonEmpty)
      h.io.cancel.poke(true.B); d.step(); h.io.cancel.poke(false.B)
      for (_ <- 0 until 8) { d.step(); h.io.completion.valid.expect(false.B) }
      d.holdReplies=false; d.finish(PrimitiveBinStatus.cancelled,true)
      assert(d.appends.size==1)
    }
  }

  it should "fail stream faults and malformed records without substituting successful references" in {
    for (readFault <- Seq(false,true)) simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); if (readFault) d.fault=_==appendBase else d.memory(appendBase+20)=1
      d.submit(); d.finish(if (readFault) PrimitiveBinStatus.memoryFault else TileBinRenderStatus.invalidEncoding,true)
      assert(d.begins==1 && d.appends.isEmpty && d.requests.count(_.id==8)==1)
    }
  }

  it should "preserve terminal renderer append statuses and avoid further input fetches" in {
    for (status <- Seq(PrimitiveBinStatus.exhausted,PrimitiveBinStatus.invalidOrder)) simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.appendStatus=status; d.rootStatus=status
      d.submit(); d.finish(status,true); assert(d.appends.size==1 && d.ends==0 && d.requests.count(_.id==8)==1)
    }
  }

  it should "reject isolated overlaps with every enabled writable arena" in {
    for (base <- Seq(0x10000L,0x80000L,0x40000L,0x50000L,0x60000L,0xb0000L)) {
      val header=descriptor(resolve=true).updated(5,base).updated(6,base+128)
      simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
        val d=new Driver(h,header=header); d.submit(); d.finish(PrimitiveBinStatus.invalidDescriptor,false)
        assert(d.requests.size==8 && d.begins==0)
      }
    }
  }

  it should "drain all eight accepted descriptor reads after cancellation and retain late fault priority" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdResponses=true; d.fault=_==descriptorBase+448
      d.submit(); d.until(d.requests.size==8); h.io.cancel.poke(true.B); d.step(); h.io.cancel.poke(false.B)
      for (_ <- 0 until 5) { d.step(); h.io.completion.valid.expect(false.B) }
      d.holdResponses=false; d.finish(PrimitiveBinStatus.memoryFault,false)
      h.io.completion.bits.descriptorReadBytes.expect(512.U); assert(d.begins==0)
    }
  }

  it should "accept a final padded append line below four GiB without wrapping" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val header=descriptor(1).updated(5,0xffffffc0L).updated(6,0L).updated(7,1L)
      val d=new Driver(h,header=header,refs=references(1)); d.submit(); d.finish(0,true)
      assert(d.requests.last.address==0xffffffc0L && d.appends.toVector==references(1))
      h.io.completion.bits.appendReadBytes.expect(64.U)
    }
  }

  it should "reject stray response IDs without poisoning a live descriptor" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.holdResponses=true; d.submit(); d.until(d.requests.size==8)
      h.io.memoryResponse.valid.poke(true.B); h.io.memoryResponse.bits.transactionId.poke(9.U)
      h.io.memoryResponse.bits.fault.poke(true.B); h.io.memoryResponse.ready.expect(false.B)
      h.clock.step(); h.io.memoryResponse.valid.poke(false.B); d.holdResponses=false; d.finish(0,true)
    }
  }

  it should "drain an early renderer rejection without reading an unnecessary append line" in {
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h); d.rootStatus=PrimitiveBinStatus.invalidDescriptor; d.forceRootEnd=true
      d.submit(); d.finish(PrimitiveBinStatus.invalidDescriptor,true)
      assert(d.appends.isEmpty && d.requests.size==8)
    }
  }

  it should "reject descriptor allocations that alias enabled writable arenas" in {
    for (base <- Seq(0x10000L,0x80000L,0x40000L,0x50000L,0x60000L,0xb0000L)) {
      simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
        val d=new Driver(h,header=descriptor(0,resolve=true),refs=Vector.empty,base=base)
        d.submit(); d.finish(PrimitiveBinStatus.invalidDescriptor,false)
        assert(d.requests.size==8 && d.begins==0)
      }
    }
  }

  it should "construct the selected resident and programmable profiles without accepting unused fields" in {
    for ((paged,programmable) <- Seq((false,false),(true,false),(false,true))) {
      simulate(new TileBinJobReader(cfg,paged=paged,programmable=programmable)) { h=>
        val header=if (paged) descriptor(0,programmable=programmable) else descriptor(0,programmable=programmable).updated(23,0L).updated(24,0L)
        val d=new Driver(h,header=header,refs=Vector.empty); d.holdBegin=true; d.submit()
        d.until(h.io.begin.valid.peek().litToBoolean)
        h.io.begin.bits.render.replay.bins.version.expect((if (paged) 2 else 1).U)
        h.io.begin.bits.render.shaderBase.expect((if (programmable) 0xb0000 else 0).U)
        d.holdBegin=false; d.finish(0,true)
      }
    }
  }

  it should "reject shader staging fields in a fixed-function memory job" in {
    simulate(new TileBinJobReader(cfg,programmable=false)) { h=>
      val d=new Driver(h); d.submit(); d.finish(TileBinRenderStatus.invalidEncoding,false)
      assert(d.begins==0 && d.requests.size==8)
    }
  }

  it should "decode both checked spill segments without changing their reserved gaps" in {
    val header=descriptor().updated(17,0x12080L).updated(19,0x10000L).updated(21,0x202L)
      .updated(49,0x11000L).updated(50,0x11080L).updated(52,0x12000L).updated(53,0x12080L)
    simulate(new TileBinJobReader(cfg,programmable=true)) { h=>
      val d=new Driver(h,header=header); d.holdBegin=true; d.submit(); d.until(h.io.begin.valid.peek().litToBoolean)
      val pool=h.io.begin.bits.render.replay.bins.pool
      pool.version.expect(2.U); pool.spillCount.expect(2.U); pool.initialLimit.expect(0x10000.U)
      pool.spills(0).base.expect(0x11000.U); pool.spills(1).limit.expect(0x12080.U)
      d.holdBegin=false; d.finish(0,true); assert(d.appends.toVector==references(3))
    }
  }
}
