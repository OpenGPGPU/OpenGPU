package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object WindowRecordTest {
  def zero(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  def identity(d: GraphicsWorkIdentity): Unit={d.context.poke(7.U);d.pass.poke(11.U);d.generation.poke(13.U)}
  def leaves(d: Data,prefix: String=""): Map[String,Data]=d match {
    case r: Record=>r.elements.toSeq.flatMap{case(n,x)=>leaves(x,if(prefix.isEmpty)n else s"$prefix.$n")}.toMap
    case v: Vec[_]=>v.zipWithIndex.flatMap{case(x,i)=>leaves(x,s"$prefix[$i]")}.toMap
    case x=>Map(prefix->x)
  }
  def values(d: Data): Map[String,BigInt]=leaves(d).map{case(n,x)=>n->x.peek().litValue}
  def fill(d: WindowPrimitiveRecord,seed: Int): Map[String,BigInt]={
    val random=new scala.util.Random(seed)
    for((_,x)<-leaves(d).toSeq.sortBy(_._1)){
      val bits=BigInt(x.getWidth,random)
      x match {case s: SInt=>s.poke((if(bits.testBit(s.getWidth-1))bits-(BigInt(1)<<s.getWidth) else bits).S)
        case u: UInt=>u.poke(bits.U)}
    }
    identity(d.primitive.clipped.primitive.identity)
    if(!d.geometry.valid.peek().litToBoolean)zero(d.geometry.bits)
    values(d)
  }
  class Memory(val request: opengpu.core.memory.ComputeMemoryRequest,val response: opengpu.core.memory.ComputeMemoryResponse,
      val requestValid: Bool,val requestReady: Bool,val responseValid: Bool,val responseReady: Bool) {
    case class Pending(address: BigInt,write: Boolean,data: BigInt,mask: BigInt,tag: BigInt,due: Int)
    val bytes=mutable.Map.empty[BigInt,Int];val pending=mutable.ArrayBuffer.empty[Pending]
    val seen=mutable.ArrayBuffer.empty[Pending]
    var cycle=0;var holdRequests=false;var holdResponses=false;var faultAt=Set.empty[BigInt]
    var delay=4;var held=Option.empty[BigInt];var reply=Option.empty[Pending]
    var beforeRequest: Pending=>Unit=_=>()
    def line(address: BigInt): BigInt=(0 until 64).foldLeft(BigInt(0))((a,i)=>a|(BigInt(bytes.getOrElse(address+i,0))<<(8*i)))
    def word(address: BigInt): BigInt=(line(address)&0xffffffffL)
    def putWord(address: BigInt,bits: BigInt): Unit=for(i<-0 until 4)bytes(address+i)=((bits>>(i*8))&255).toInt
    def drive(): Unit={
      requestReady.poke((!holdRequests && cycle%4!=0).B)
      if(reply.isEmpty && !holdResponses){val candidates=pending.indices.filter(i=>pending(i).due<=cycle)
        if(candidates.nonEmpty)reply=Some(pending.remove(candidates.last))}
      responseValid.poke(reply.nonEmpty.B)
      reply.foreach{r=>response.transactionId.poke(r.tag.U);response.fault.poke(faultAt(r.address).B)
        response.readData.poke(line(r.address).U)}
    }
    def capture(): Unit={
      val valid=requestValid.peek().litToBoolean;val bits=if(valid || held.nonEmpty)request.peek().litValue else BigInt(0)
      held.foreach(v=>assert(valid && bits==v,"memory offer changed under backpressure"))
      held=if(valid && !requestReady.peek().litToBoolean)Some(bits)else None
      if(valid && requestReady.peek().litToBoolean){
        val r=Pending(request.address.peek().litValue,request.isWrite.peek().litToBoolean,request.writeData.peek().litValue,
          request.byteMask.peek().litValue,request.transactionId.peek().litValue,cycle+delay)
        assert(!pending.exists(_.tag==r.tag) && !reply.exists(_.tag==r.tag));beforeRequest(r);seen+=r;pending+=r
      }
      if(reply.nonEmpty && responseReady.peek().litToBoolean){val r=reply.get
        // Stores become visible at acknowledgement, exercising publication.
        if(r.write)for(i<-0 until 64 if r.mask.testBit(i))bytes(r.address+i)=((r.data>>(8*i))&255).toInt
        reply=None
      }
      cycle+=1
    }
  }
}

class WindowPrimitiveRecordsSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val p=VertexShaderStageConfig()
  private class Driver(val d: WindowPrimitiveRecordTransfer) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(condition: =>Boolean,limit: Int=5000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"timeout at $n")}
    def submit(write: Boolean,handle: BigInt=0,base: BigInt=0x10000,limit: BigInt=0x40000): Unit={
      val r=d.io.request.bits;r.arena.version.poke(1.U);identity(r.arena.identity);r.arena.base.poke(base.U);r.arena.limit.poke(limit.U)
      r.handle.poke(handle.U);r.write.poke(write.B);d.io.request.valid.poke(true.B)
      until(d.io.request.ready.peek().litToBoolean);tick();d.io.request.valid.poke(false.B)
    }
    def result(status: Int): Unit={until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.status.expect(status.U)
      d.io.result.ready.poke(true.B);tick();d.io.result.ready.poke(false.B)}
  }
  behavior of "WindowPrimitiveRecordTransfer"
  it should "retain every full-capacity scalar across acknowledged stores and independent reads" in {
    simulate(new WindowPrimitiveRecordTransfer(p=p)){d=>val t=new Driver(d)
      for(seed<-Seq(1,19,81)){
        val expected=fill(d.io.request.bits.record,seed)
        t.submit(true,seed);t.until(d.io.result.valid.peek().litToBoolean)
        d.io.result.bits.lines.expect(d.recordLines.U);d.io.result.bits.recordValid.expect(false.B)
        assert(t.memory.pending.isEmpty && t.memory.reply.isEmpty)
        t.result(0)
        zero(d.io.request.bits.record);t.submit(false,seed);t.until(d.io.result.valid.peek().litToBoolean)
        d.io.result.bits.recordValid.expect(true.B);assert(values(d.io.result.bits.record)==expected)
        val held=d.io.result.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 5){t.tick();assert(d.io.result.bits.peek().litValue==held)}
        t.result(0);d.io.cancel.poke(false.B)
      }
      assert(p.varyings==16);assert(t.memory.seen.count(_.write)==d.recordLines*3)
      info(s"WINDOW_RECORD varyingCapacity=16 fields=${leaves(d.io.request.bits.record).size} bytes=${d.recordBytes} lines=${d.recordLines} fullScalarRoundTrips=3 acknowledgedPublication=true")
    }
  }
  it should "check every arena bound including records ending at 4 GiB without wrapping" in {
    simulate(new WindowPrimitiveRecordTransfer(p=p)){d=>val t=new Driver(d);val expected=fill(d.io.request.bits.record,31)
      val high=(BigInt(1)<<32)-d.recordBytes
      t.submit(true,base=high,limit=BigInt(1)<<32);t.result(0)
      t.submit(false,base=high,limit=BigInt(1)<<32);t.until(d.io.result.valid.peek().litToBoolean)
      assert(values(d.io.result.bits.record)==expected);t.result(0)
      val count=t.memory.seen.size
      for((base,limit,handle)<-Seq((high,BigInt(1)<<32,BigInt(1)),(BigInt(0x10001),BigInt(0x40000),BigInt(0)),
        (BigInt(0x10000),BigInt(0x10040),BigInt(0)),(BigInt(0x10000),BigInt(0x40000),BigInt("ffffffff",16)),
        (BigInt(0x10000),BigInt(0x40001),BigInt(0)))){t.submit(false,handle,base,limit);t.result(1)}
      assert(t.memory.seen.size==count)
    }
  }
  it should "reject corrupt headers padding and signed extensions without publishing partial data" in {
    simulate(new WindowPrimitiveRecordTransfer(p=p)){d=>val t=new Driver(d);fill(d.io.request.bits.record,9)
      d.io.request.bits.record.geometry.valid.poke(false.B);zero(d.io.request.bits.record.geometry.bits)
      t.submit(true);t.result(0)
      val original=t.memory.bytes.toMap
      val fs=WindowPrimitiveRecordLayout.fields(d.io.request.bits.record)
      val narrow=fs.find(f=>f.value.isInstanceOf[SInt] && f.width<32).get
      val cases=Seq((0,BigInt(0)),(1,BigInt(64)),(3,BigInt(1)),(4,BigInt(8)),(7,BigInt(1)),(8,BigInt(1)),
        (narrow.word,BigInt(1)<<narrow.width))
      for((word,bits)<-cases){t.memory.bytes.clear();t.memory.bytes++=original;t.memory.putWord(0x10000+word*4,bits)
        t.submit(false);t.until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.recordValid.expect(false.B)
        assert(values(d.io.result.bits.record).values.forall(_==0));t.result(7)}
      t.memory.bytes.clear();t.memory.bytes++=original;t.submit(false);t.result(0)
    }
  }
  it should "preserve legacy headers and validate geometry metadata before replay" in {
    simulate(new WindowPrimitiveRecordTransfer(p=p)){d=>val t=new Driver(d)
      fill(d.io.request.bits.record,17)
      d.io.request.bits.record.geometry.valid.poke(false.B);zero(d.io.request.bits.record.geometry.bits)
      val legacy=values(d.io.request.bits.record)
      t.submit(true);t.result(0)
      assert(t.memory.word(0x10008)==1 && (8 until 16).forall(w=>t.memory.word(0x10000+w*4)==0))
      zero(d.io.request.bits.record);t.submit(false);t.until(d.io.result.valid.peek().litToBoolean)
      assert(values(d.io.result.bits.record)==legacy);t.result(0)

      fill(d.io.request.bits.record,18)
      val geometry=d.io.request.bits.record.geometry
      geometry.valid.poke(true.B);geometry.bits.inputPrimitiveId.poke("hfedcba98".U)
      geometry.bits.invocationId.poke(63.U);geometry.bits.layer.poke("h81234567".U)
      val expected=values(d.io.request.bits.record)
      t.submit(true);t.result(0)
      assert(t.memory.word(0x10008)==2 && t.memory.word(0x10020)==BigInt("fedcba98",16))
      assert(t.memory.word(0x10024)==63 && t.memory.word(0x10028)==BigInt("81234567",16))
      zero(d.io.request.bits.record);t.submit(false);t.until(d.io.result.valid.peek().litToBoolean)
      assert(values(d.io.result.bits.record)==expected);t.result(0)

      val original=t.memory.bytes.toMap
      for((word,bits)<-Seq((2,BigInt(3)),(9,BigInt(64)),(11,BigInt(1)))){
        t.memory.bytes.clear();t.memory.bytes++=original;t.memory.putWord(0x10000+word*4,bits)
        t.submit(false);t.until(d.io.result.valid.peek().litToBoolean)
        d.io.result.bits.recordValid.expect(false.B);t.result(7)
      }
    }
  }
  it should "drain held offers and delayed acknowledgements on cancellation and memory faults then rebind" in {
    simulate(new WindowPrimitiveRecordTransfer(p=p)){d=>val t=new Driver(d);fill(d.io.request.bits.record,77)
      t.memory.holdRequests=true;t.submit(true);t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val request=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==request);d.io.result.valid.expect(false.B)}
      t.memory.holdResponses=true;t.memory.holdRequests=false;t.until(t.memory.seen.nonEmpty)
      for(_<-0 until 8){t.tick();d.io.result.valid.expect(false.B)}
      t.memory.holdResponses=false;t.result(4);assert(t.memory.seen.size==1);d.io.cancel.poke(false.B)
      t.memory.faultAt=Set(BigInt(0x10000+64));t.submit(true);t.result(3)
      t.memory.faultAt=Set.empty;t.submit(true);t.result(0)
      t.memory.faultAt=Set(BigInt(0x10000+128));t.submit(false);t.until(d.io.result.valid.peek().litToBoolean)
      d.io.result.bits.recordValid.expect(false.B);t.result(3)
      t.memory.faultAt=Set.empty;t.submit(false);t.result(0)
    }
  }
}
