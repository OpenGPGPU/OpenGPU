package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class DebugMessageLogSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "DebugMessageLog"
  private case class Message(id: Int,source: Int,kind: Int,severity: Int,length: Int,words: Vector[BigInt])
  private class Driver(val d: DebugMessageLog) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    /** Insert with waiting so the log (which is always ready) accepts one record. */
    def insert(m: Message): Unit={
      val q=d.io.in.bits;zero(q);q.id.poke(m.id.U);q.source.poke(m.source.U);q.kind.poke(m.kind.U)
      q.severity.poke(m.severity.U);q.length.poke(m.length.U)
      m.words.zipWithIndex.foreach{case(w,i)=>q.words(i).poke(w.U)}
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
    }
    def read(): Message={
      d.io.out.valid.expect(true.B);val b=d.io.out.bits
      val m=Message(b.id.peek().litValue.toInt,b.source.peek().litValue.toInt,b.kind.peek().litValue.toInt,
        b.severity.peek().litValue.toInt,b.length.peek().litValue.toInt,(0 until 8).map(i=>b.words(i).peek().litValue).toVector)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      m
    }
  }
  it should "retain ordered records with exact metadata and payload" in {
    simulate(new DebugMessageLog){d=>val t=new Driver(d)
      d.io.count.expect(0.U);d.io.full.expect(false.B);d.io.overflow.expect(false.B);d.io.out.valid.expect(false.B)
      val messages=(0 until 5).map{i=>Message(0x100+i,i%16,(i*3)%16,i%8,(i*7)%32,
        Vector.tabulate(8)(w=>BigInt(((i<<24)|(w<<16)|0x5a5a)&0xffffffffL)))}
      messages.foreach(t.insert)
      d.io.count.expect(5.U);d.io.full.expect(false.B)
      messages.foreach{m=>assert(t.read()==m,"debug message order or payload mismatch")}
      d.io.count.expect(0.U);d.io.out.valid.expect(false.B)
      info("DEBUG_LOG_ORDER records5=true exactMetadata=true exactPayload=true fifoOrder=true")
    }
  }
  it should "drop on full set overflow and clear it only when the log empties" in {
    simulate(new DebugMessageLog){d=>val t=new Driver(d)
      for(i<-0 until 8)t.insert(Message(i,1,2,3,4,Vector.fill(8)(BigInt(i))))
      d.io.count.expect(8.U);d.io.full.expect(true.B);d.io.overflow.expect(false.B)
      t.insert(Message(99,9,9,7,31,Vector.fill(8)(BigInt(0xdeadbeefL))))
      d.io.count.expect(8.U);d.io.full.expect(true.B);d.io.overflow.expect(true.B)
      d.io.out.valid.expect(true.B);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.overflow.expect(true.B)}
      for(i<-0 until 8){val m=t.read();assert(m.id==i,s"dropped message replaced record $i: ${m.id}")}
      d.io.count.expect(0.U);d.io.overflow.expect(false.B);d.io.out.valid.expect(false.B)
      info("DEBUG_LOG_OVERFLOW depth8=true dropNewest=true stickyUntilEmpty=true orderPreserved=true")
    }
  }
  it should "clear on reset and recover for reused contexts" in {
    simulate(new DebugMessageLog){d=>val t=new Driver(d)
      for(i<-0 until 9)t.insert(Message(i,0,0,0,0,Vector.fill(8)(BigInt(i))))
      d.io.overflow.expect(true.B)
      d.io.reset.poke(true.B);d.clock.step();d.io.reset.poke(false.B)
      d.io.count.expect(0.U);d.io.overflow.expect(false.B);d.io.full.expect(false.B);d.io.out.valid.expect(false.B)
      t.insert(Message(0x77,3,4,5,6,Vector.fill(8)(BigInt(0x11))))
      d.io.count.expect(1.U);assert(t.read().id==0x77)
      info("DEBUG_LOG_RESET clearsCount=true clearsOverflow=true reusable=true")
    }
  }
}
