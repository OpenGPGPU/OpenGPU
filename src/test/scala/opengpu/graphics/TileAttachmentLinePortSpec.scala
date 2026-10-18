package opengpu.graphics

import chisel3._
import chisel3.util.Queue
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class AttachmentLinePortHarness(optimized: Boolean,credits: Int=8,bufferedWriteAcks: Boolean=false) extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2)
  val io=IO(new TileAttachmentLinePortIO(cfg,credits+1))
  if (optimized) io <> Module(new TileAttachmentLinePort(cfg,credits+1,bufferedWriteAcks=bufferedWriteAcks)).io
  else {
    val p=Module(new OmWordToLinePort(cfg,64,credits+1,uncached=true))
    p.io.in <> io.in; io.out <> p.io.out
    val queue=Module(new Queue(chiselTypeOf(io.memoryRequest.bits),2,pipe=true))
    queue.io.enq <> p.io.memoryRequest; io.memoryRequest <> queue.io.deq
    p.io.memoryResponse <> io.memoryResponse
    val live=RegInit(0.U(6.W))
    when(io.in.fire && !io.out.fire) { live:=live+1.U }
    when(io.out.fire && !io.in.fire) { live:=live-1.U }
    io.drained:=live===0.U && !io.memoryRequest.valid
    io.responseFault:=io.memoryResponse.bits.fault
    io.writeFault:=false.B
    io.events:=0.U.asTypeOf(io.events)
    io.events.loadBytes:=Mux(io.out.fire && !io.out.bits.write,4.U,0.U)
    io.events.storeBytes:=Mux(io.memoryRequest.fire && io.memoryRequest.bits.isWrite,4.U,0.U)
    io.events.readLine:=io.memoryRequest.fire && !io.memoryRequest.bits.isWrite
    io.events.writeLine:=io.memoryRequest.fire && io.memoryRequest.bits.isWrite
  }
}

class TileAttachmentLinePortSpec extends AnyFlatSpec {
  case class Word(address: Long,write: Boolean,value: Long,resolve: Boolean=false)
  case class Reply(word: Word,data: Long,fault: Boolean)
  case class Line(id: Int,address: Long,write: Boolean,data: BigInt,mask: BigInt,due: Int,fault: Boolean)
  private class Driver(val h: AttachmentLinePortHarness,val latency: Int=5,val writeWindow: Int=1) {
    val io=h.io
    val memory=mutable.LongMap.empty[Long]
    val words=mutable.LongMap.empty[Word]
    val lines=mutable.ArrayBuffer.empty[Line]
    val requests=mutable.ArrayBuffer.empty[Line]
    val replies=mutable.ArrayBuffer.empty[Reply]
    var cycle=0
    var holdRequests=false; var holdReplies=false; var holdWriteResponses=false
    var fault: (Boolean,Long)=>Boolean=(_,_)=>false
    var extraDelay: Long=>Int = _=>0
    var returning=Option.empty[Line]
    var heldMemory=Option.empty[BigInt]; var heldWord=Option.empty[(BigInt,Boolean)]
    var usefulLoads=0L; var usefulStores=0L; var usefulResolves=0L; var hits=0
    var writeFaults=0
    private val live=mutable.Set.empty[Int]
    io.in.valid.poke(false.B); io.in.bits.poke(0.U.asTypeOf(io.in.bits))
    io.out.ready.poke(false.B); io.begin.poke(false.B); io.abort.poke(false.B); io.resolve.poke(false.B)
    io.memoryRequest.ready.poke(false.B); io.memoryResponse.valid.poke(false.B)
    io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
    h.reset.poke(true.B); h.clock.step(); h.reset.poke(false.B)
    def step(): Boolean = {
      val req=io.memoryRequest
      io.out.ready.poke((!holdReplies && cycle%7!=3).B)
      val writeLive=lines.count(_.write) + returning.count(_.write)
      req.ready.poke((!holdRequests && cycle%5!=2 && !(req.bits.isWrite.peek().litToBoolean && writeLive>=writeWindow)).B)
      heldMemory.foreach(x=>{req.valid.expect(true.B); assert(req.bits.peek().litValue==x,"offered masked line changed")})
      heldMemory=if (req.valid.peek().litToBoolean && !req.ready.peek().litToBoolean) Some(req.bits.peek().litValue) else None
      if (returning.isEmpty) {
        val i=lines.lastIndexWhere(l=>l.due<=cycle && !(l.write && holdWriteResponses))
        if (i>=0) returning=Some(lines.remove(i))
      }
      io.memoryResponse.valid.poke(returning.nonEmpty.B)
      returning.foreach { l=>
        io.memoryResponse.bits.transactionId.poke(l.id.U)
        io.memoryResponse.bits.fault.poke(l.fault.B)
        val data=(0 until 16).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory.getOrElse(l.address+i*4L,0xdeadbeefL))<<(32*i)))
        io.memoryResponse.bits.readData.poke(data.U)
      }
      if (returning.nonEmpty && io.memoryResponse.ready.peek().litToBoolean) {
        val l=returning.get
        if (l.write && !l.fault) for (i <- 0 until 16 if l.mask.testBit(4*i)) {
          assert(((l.mask>>(4*i))&15)==15)
          memory(l.address+i*4L)=((l.data>>(32*i))&0xffffffffL).toLong
        }
        assert(live.remove(l.id)); returning=None
      }
      if (io.out.valid.peek().litToBoolean) {
        val raw=io.out.bits.peek().litValue; val isFault=io.responseFault.peek().litToBoolean
        heldWord.foreach(old=>assert(old==(raw,isFault),"offered word acknowledgement changed"))
        if (io.out.ready.peek().litToBoolean) {
          val a=io.out.bits.addr.peek().litValue.toLong
          val w=words.remove(a).getOrElse(fail(s"unowned/duplicate word response at $a"))
          io.out.bits.write.expect(w.write.B)
          replies+=Reply(w,io.out.bits.data.peek().litValue.toLong,isFault)
          heldWord=None
        } else heldWord=Some((raw,isFault))
      } else assert(heldWord.isEmpty)
      if (req.valid.peek().litToBoolean && req.ready.peek().litToBoolean) {
        val id=req.bits.transactionId.peek().litValue.toInt; assert(live.add(id))
        val l=Line(id,req.bits.address.peek().litValue.toLong,req.bits.isWrite.peek().litToBoolean,
          req.bits.writeData.peek().litValue,req.bits.byteMask.peek().litValue,cycle+latency+extraDelay(req.bits.address.peek().litValue.toLong),false)
        val owned=l.copy(fault=fault(l.write,l.address)); lines+=owned; requests+=owned
        assert(l.address%64==0); req.bits.sizeLog2.expect(6.U)
        if (l.write) {
          assert(l.mask!=0 && (0 until 16).forall(i=>((l.mask>>(4*i))&15)==0 || ((l.mask>>(4*i))&15)==15))
          for (i <- 0 until 16 if !l.mask.testBit(4*i)) assert(((l.data>>(32*i))&0xffffffffL)==0)
        }
      }
      val inputAccepted=io.in.valid.peek().litToBoolean && io.in.ready.peek().litToBoolean
      if (inputAccepted) {
        val a=io.in.bits.addr.peek().litValue.toLong; assert(!words.contains(a))
        words(a)=Word(a,io.in.bits.write.peek().litToBoolean,io.in.bits.data.peek().litValue.toLong,io.resolve.peek().litToBoolean)
      }
      usefulLoads+=io.events.loadBytes.peek().litValue.toLong
      usefulStores+=io.events.storeBytes.peek().litValue.toLong
      usefulResolves+=io.events.resolveBytes.peek().litValue.toLong
      if (io.events.readHit.peek().litToBoolean) hits+=1
      if (io.writeFault.peek().litToBoolean) writeFaults+=1
      h.clock.step(); cycle+=1
      inputAccepted
    }
    def until(p: => Boolean): Unit = { val start=cycle; while (!p && cycle-start<30000) step(); assert(p,s"stalled at $cycle words=${words.size} lines=$lines") }
    def bind(): Unit = { until(io.drained.peek().litToBoolean); io.begin.poke(true.B); step(); io.begin.poke(false.B) }
    def offer(w: Word): Unit = {
      io.in.bits.addr.poke(w.address.U); io.in.bits.write.poke(w.write.B); io.in.bits.data.poke(w.value.U)
      io.resolve.poke(w.resolve.B); io.in.valid.poke(true.B)
    }
    def send(w: Word): Unit = { offer(w); var fired=false; val start=cycle; while (!fired && cycle-start<30000) fired=step(); assert(fired); io.in.valid.poke(false.B) }
    def drain(): Unit = { io.in.valid.poke(false.B); until(io.drained.peek().litToBoolean); assert(words.isEmpty && lines.isEmpty && returning.isEmpty && live.isEmpty) }
  }

  it should "reduce masked write traffic and elapsed cycles under one reserved external write credit" in {
    val results=mutable.ArrayBuffer.empty[(Boolean,Int,Int)]
    for (optimized <- Seq(false,true)) simulate(new AttachmentLinePortHarness(optimized)) { h=>
      val d=new Driver(h,latency=80); d.bind()
      var sent=0; var presenting=false
      while (sent<64) {
        if (!presenting && d.words.size<8 && d.cycle%6==0) {
          d.offer(Word(0x10000L+sent*4,write=true,0x90000000L+sent,resolve=sent>=48)); presenting=true
        }
        val fire=d.step()
        if (fire) { sent+=1; presenting=false; h.io.in.valid.poke(false.B) }
      }
      d.drain()
      assert(d.replies.size==64 && d.replies.forall(!_.fault))
      for (i <- 0 until 64) assert(d.memory(0x10000L+i*4)==0x90000000L+i)
      assert(d.usefulStores==256 && d.requests.map(_.mask.bitCount).sum==256)
      if (optimized) assert(d.usefulResolves==64)
      results+=((optimized,d.requests.size,d.cycle))
      info(s"ATTACHMENT_LINE_WRITES coalesced=$optimized words=64 lines=${d.requests.size} cycles=${d.cycle}")
    }
    assert(results(0)._2==64 && results(1)._2==8)
    assert(results(1)._3*3<results(0)._3)
  }

  it should "reuse complete read lines and invalidate them on writes and owner rebinding" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h)
      for (i <- 0 until 64) d.memory(0x10000L+i*4)=0x80000000L+i
      d.bind()
      for (i <- 0 until 64) { d.send(Word(0x10000L+i*4,false,0)); d.until(!d.words.contains(0x10000L+i*4)) }
      assert(d.requests.count(!_.write)==4 && d.hits==60 && d.usefulLoads==256)
      assert(d.replies.zipWithIndex.forall {case (r,i)=> !r.fault && r.data==0x80000000L+i})
      d.send(Word(0x100fcL,true,0x12345678L)); d.send(Word(0x100fcL,false,0))
      d.drain(); assert(d.replies.last.data==0x12345678L)
      val reads=d.requests.count(!_.write)
      d.memory(0x100fcL)=0xfedcba98L; d.bind(); d.send(Word(0x100fcL,false,0)); d.drain()
      assert(d.replies.last.data==0xfedcba98L && d.requests.count(!_.write)==reads+1)
    }
  }

  it should "reserve read progress while all write words wait for one masked line acknowledgement" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h); d.bind(); d.holdWriteResponses=true
      for (i <- 0 until 8) d.send(Word(0x10000L+i*4,true,i))
      d.until(d.requests.exists(_.write)); d.memory(0x20000L)=0xabcdef01L
      d.send(Word(0x20000L,false,0)); d.until(d.replies.exists(!_.word.write))
      assert(d.replies.find(!_.word.write).get.data==0xabcdef01L && d.words.size==8)
      d.holdWriteResponses=false; d.drain(); assert(d.replies.size==9)
    }
  }

  it should "drop unoffered work on abort while retaining a stalled offered line and every word reply" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h); d.bind(); d.holdRequests=true
      for (i <- 0 until 3) d.send(Word(0x10000L+i*4,true,i))
      h.io.abort.poke(true.B); d.step(); h.io.abort.poke(false.B)
      d.drain(); assert(d.requests.isEmpty && d.replies.size==3 && d.usefulStores==0)
      d.bind()
      for (i <- 0 until 5) d.send(Word(0x20000L+i*4,true,100+i))
      d.until(h.io.memoryRequest.valid.peek().litToBoolean)
      val held=h.io.memoryRequest.bits.peek().litValue
      h.io.abort.poke(true.B); d.step(); h.io.abort.poke(false.B)
      for (_ <- 0 until 5) { d.step(); h.io.memoryRequest.valid.expect(true.B); assert(h.io.memoryRequest.bits.peek().litValue==held) }
      d.holdRequests=false; d.holdReplies=true
      d.until(d.requests.nonEmpty && d.lines.isEmpty && d.returning.isEmpty)
      h.io.drained.expect(false.B); d.holdReplies=false; d.drain()
      assert(d.requests.size==1 && d.replies.size==8 && d.usefulStores==20)
    }
  }

  it should "return a read fault without caching substitute data and recover with a fresh owner" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h); d.bind(); d.fault=(_,_)=>true
      d.send(Word(0xfffffffcL,false,0)); d.drain()
      assert(d.replies.last.fault && d.hits==0)
      d.fault=(_,_)=>false; d.memory(0xfffffffcL)=0x13579bdfL
      d.bind(); d.send(Word(0xfffffffcL,false,0)); d.drain()
      assert(!d.replies.last.fault && d.replies.last.data==0x13579bdfL && d.requests.size==2)
    }
  }
  it should "retain both attachment planes across the two rows of raster quads" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h); d.bind()
      val bases=Vector(0x10000L,0x10100L,0x20000L,0x20100L)
      for ((base,n) <- bases.zipWithIndex; i <- 0 until 16) d.memory(base+i*4)=0x80000000L+n*100+i
      for (i <- 0 until 16; (base,n) <- bases.zipWithIndex) {
        d.send(Word(base+i*4,false,0)); d.until(!d.words.contains(base+i*4))
        assert(d.replies.last.data==0x80000000L+n*100+i)
      }
      d.drain(); assert(d.requests.size==4 && d.hits==60 && d.usefulLoads==256)
    }
  }

  it should "fan a masked store fault out to every owned word and preserve resolve-mask accounting" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h); d.bind(); d.fault=(write,_)=>write
      for (i <- 0 until 8) d.send(Word(0x10000L+i*4,true,100+i,resolve=i>=4))
      d.drain()
      assert(d.requests.size==1 && d.replies.size==8 && d.replies.forall(_.fault))
      assert(d.usefulStores==32 && d.usefulResolves==16 && d.memory.isEmpty)
      d.fault=(_,_)=>false; d.bind()
      for (i <- 0 until 8) d.send(Word(0x10000L+i*4,true,200+i))
      d.drain(); assert(d.replies.drop(8).forall(!_.fault))
      for (i <- 0 until 8) assert(d.memory(0x10000L+i*4)==200+i)
    }
  }

  it should "match reordered line replies to disjoint masked words without acknowledging an unowned tag" in {
    simulate(new AttachmentLinePortHarness(true)) { h=>
      val d=new Driver(h,writeWindow=2); d.bind(); d.extraDelay=a=>if (a==0x10000L) 80 else 0
      for (a <- Seq(0x10000L,0x1003cL,0x20004L,0x2001cL)) d.send(Word(a,true,a+1))
      d.until(d.requests.size==2)
      h.io.memoryResponse.valid.poke(true.B); h.io.memoryResponse.bits.transactionId.poke(7.U)
      h.io.memoryResponse.bits.fault.poke(true.B); h.io.memoryResponse.ready.expect(false.B)
      h.clock.step(); h.io.memoryResponse.valid.poke(false.B)
      d.drain()
      assert(d.replies.size==4 && d.replies.forall(!_.fault) && d.replies.head.word.address>=0x20000L)
      for (a <- Seq(0x10000L,0x1003cL,0x20004L,0x2001cL)) assert(d.memory(a)==a+1)
      assert(d.usefulStores==16 && d.requests.forall(_.mask.bitCount==8))
    }
  }

  for (credits <- Seq(1,2,16)) {
  it should s"flush bounded partial and full lines at write credits=$credits" in {
    simulate(new AttachmentLinePortHarness(true,credits)) { h=>
      val d=new Driver(h); d.bind()
      for (i <- 0 until 16) d.send(Word(0x10000L+i*4,true,0xf0000000L+i))
      d.drain()
      assert(d.requests.size==(16+credits-1)/credits && d.usefulStores==64 && d.replies.size==16)
      for (i <- 0 until 16) assert(d.memory(0x10000L+i*4)==0xf0000000L+i)
    }
  }
  }

  for(window <- Seq(1,2,8))it should s"fill a complete buffered line before any B through a private window of $window" in {
    simulate(new AttachmentLinePortHarness(true,window,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h,latency=5);d.bind();d.holdWriteResponses=true
      for(i <- 0 until 16)d.send(Word(0x1000+i*4L,true,0x12340000L+i))
      d.until(d.replies.size==16 && d.requests.nonEmpty)
      assert(d.requests.size==1 && d.requests.head.write && d.requests.head.mask==((BigInt(1)<<64)-1))
      assert(d.words.isEmpty && d.replies.forall(r=>r.word.write && !r.fault))
      h.io.drained.expect(false.B);assert(d.memory.isEmpty)
      d.holdWriteResponses=false;d.drain()
      assert(d.replies.size==16 && d.writeFaults==0 && d.usefulStores==64)
      for(i <- 0 until 16)assert(d.memory(0x1000+i*4L)==0x12340000L+i)
    }
  }

  it should "retain recycled capture replies through an older line fault without a second word acknowledgement" in {
    simulate(new AttachmentLinePortHarness(true,8,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h,latency=2,writeWindow=2);d.bind();d.holdWriteResponses=true
      d.fault=(write,address)=>write && address==0x1000
      d.extraDelay=address=>if(address==0x2000)1000 else 0
      for(i <- 0 until 16)d.send(Word(0x1000+i*4L,true,0x11110000L+i))
      d.until(d.replies.size==16 && d.requests.size==1)
      for(i <- 0 until 8)d.send(Word(0x2000+i*4L,true,0x22220000L+i))
      d.until(d.replies.size==24)
      d.holdReplies=true
      for(i <- 8 until 16)d.send(Word(0x2000+i*4L,true,0x22220000L+i))
      d.until(h.io.out.valid.peek().litToBoolean && d.requests.size==2)
      val held=h.io.out.bits.peek().litValue;h.io.responseFault.expect(false.B)
      d.holdWriteResponses=false;d.until(d.writeFaults==1)
      for(_ <- 0 until 8){d.step();h.io.out.valid.expect(true.B);assert(h.io.out.bits.peek().litValue==held);h.io.responseFault.expect(false.B)}
      d.holdReplies=false;d.drain()
      assert(d.replies.size==32 && d.replies.forall(r=> !r.fault) && d.writeFaults==1)
      for(i <- 0 until 16)assert(d.memory(0x2000+i*4L)==0x22220000L+i)
      d.bind();d.send(Word(0x2000,false,0));d.drain()
      assert(d.replies.last.data==0x22220000L && !d.replies.last.fault)
    }
  }

  it should "hold a buffered read-after-write hazard until actual line visibility" in {
    simulate(new AttachmentLinePortHarness(true,2,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h,latency=5);d.bind();d.holdWriteResponses=true
      d.memory(0x3004)=0x11111111L
      d.send(Word(0x3004,true,0xabcdef12L));d.until(d.replies.size==1)
      d.offer(Word(0x3004,false,0));d.until(d.requests.exists(_.write))
      for(_ <- 0 until 12){assert(!d.step());h.io.in.ready.expect(false.B)}
      assert(d.requests.forall(_.write) && d.memory(0x3004)==0x11111111L)
      d.holdWriteResponses=false
      var accepted=false;while(!accepted)accepted=d.step()
      h.io.in.valid.poke(false.B);d.drain()
      assert(d.replies.last.data==0xabcdef12L && d.usefulStores==4 && d.usefulLoads==4)
    }
  }

  it should "drop unoffered buffered stores on abort without duplicating their capture replies" in {
    simulate(new AttachmentLinePortHarness(true,2,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h);d.bind()
      d.send(Word(0x4000,true,1));d.send(Word(0x4004,true,2));d.until(d.replies.size==2)
      assert(d.requests.isEmpty);h.io.abort.poke(true.B);d.step();h.io.abort.poke(false.B);d.drain()
      assert(d.requests.isEmpty && d.replies.size==2 && d.writeFaults==0)
      d.bind();d.send(Word(0x5000,true,3));d.drain();assert(d.memory(0x5000)==3)
    }
  }

  it should "retain a stalled buffered line through abort after its capture replies retire" in {
    simulate(new AttachmentLinePortHarness(true,2,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h);d.bind();d.holdRequests=true
      d.send(Word(0x6000,true,0x60000001L));d.send(Word(0x6004,true,0x60000002L))
      d.until(d.replies.size==2 && h.io.memoryRequest.valid.peek().litToBoolean)
      val offered=h.io.memoryRequest.bits.peek().litValue
      h.io.abort.poke(true.B);d.step();h.io.abort.poke(false.B)
      for(_ <- 0 until 8){d.step();h.io.memoryRequest.valid.expect(true.B);assert(h.io.memoryRequest.bits.peek().litValue==offered)}
      h.io.drained.expect(false.B);assert(d.replies.size==2 && d.requests.isEmpty)
      d.holdRequests=false;d.drain()
      assert(d.requests.size==1 && d.replies.size==2 && d.usefulStores==8 && d.writeFaults==0)
      assert(d.memory(0x6000)==0x60000001L && d.memory(0x6004)==0x60000002L)
    }
  }

  for(finalResolve <- Seq(false,true))it should s"combine repeated unoffered attachment writes and attribute the final resolve flag=$finalResolve" in {
    simulate(new AttachmentLinePortHarness(true,2,bufferedWriteAcks=true)) { h=>
      val d=new Driver(h);d.bind()
      d.send(Word(0x7000,true,0x11111111L,!finalResolve));d.until(d.replies.size==1)
      d.send(Word(0x7004,true,0x33333333L,true));d.until(d.replies.size==2)
      d.send(Word(0x7000,true,0x22222222L,finalResolve));d.until(d.replies.size==3)
      assert(d.requests.isEmpty);d.drain()
      assert(d.requests.size==1 && d.requests.head.mask==255 && d.usefulStores==8)
      assert(d.memory(0x7000)==0x22222222L && d.memory(0x7004)==0x33333333L && d.replies.size==3)
      assert(d.usefulResolves==(if(finalResolve)8L else 4L))
    }
  }

}
