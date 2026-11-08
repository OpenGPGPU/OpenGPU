package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class DebugLabelStoreSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "DebugLabelStore"
  private case class Label(kind: Int,id: Int,length: Int,words: Vector[BigInt])
  private class Driver(val d: DebugLabelStore) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def pokeLabel(port: DebugLabel,l: Label): Unit={
      zero(port);port.key.objectType.poke(l.kind.U);port.key.objectId.poke(l.id.U)
      port.length.poke(l.length.U);l.words.zipWithIndex.foreach{case(w,i)=>port.words(i).poke(w.U)}
    }
    def write(l: Label): Unit={
      pokeLabel(d.io.write.bits,l);d.io.write.valid.poke(true.B);d.io.write.ready.expect(true.B)
      d.clock.step();d.io.write.valid.poke(false.B);zero(d.io.write.bits)
      System.err.println(s"WRITE kind=${l.kind} id=${l.id} count=${d.io.count.peek().litValue} overflow=${d.io.overflow.peek().litValue}")}
    def remove(l: Label): Unit={
      d.io.remove.bits.objectType.poke(l.kind.U);d.io.remove.bits.objectId.poke(l.id.U)
      d.io.remove.valid.poke(true.B);d.clock.step();d.io.remove.valid.poke(false.B)}
    def query(l: Label,tag: Int): Option[Label]={
      d.io.query.bits.key.objectType.poke(l.kind.U);d.io.query.bits.key.objectId.poke(l.id.U)
      d.io.query.bits.tag.poke(tag.U);d.io.query.valid.poke(true.B)
      var n=0;while(!d.io.query.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.query.ready.peek().litToBoolean);d.clock.step();d.io.query.valid.poke(false.B);zero(d.io.query.bits)
      n=0;while(!d.io.result.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(n<20,"label query timeout");d.io.result.bits.tag.expect(tag.U)
      val found=d.io.result.bits.found.peek().litToBoolean
      val value=if(found)Some(Label(0,0,d.io.result.bits.length.peek().litValue.toInt,
        (0 until 8).map(i=>d.io.result.bits.words(i).peek().litValue).toVector))else None
      d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B)
      value
    }
    def push(l: Label): Unit={pokeLabel(d.io.push.bits,l);d.io.push.valid.poke(true.B);d.clock.step();d.io.push.valid.poke(false.B);zero(d.io.push.bits)}
    def pop(): Unit={d.io.pop.poke(true.B);d.clock.step();d.io.pop.poke(false.B)}
    def clear(): Unit={d.io.clear.poke(true.B);d.clock.step();d.io.clear.poke(false.B)}
  }
  it should "store replace remove and query bounded object labels" in {
    simulate(new DebugLabelStore) { d=>val t=new Driver(d)
      val labels=(0 until 8).map{i=>Label(i%16,0x100+i,(i*5)%32,Vector.tabulate(8)(w=>BigInt((i<<16)|w)))}
      labels.foreach(t.write)
      d.io.count.expect(8.U);d.io.full.expect(true.B);d.io.overflow.expect(false.B)
      labels.zipWithIndex.foreach{case(l,i)=>assert(t.query(l,i)==Some(l.copy(kind=0,id=0)),s"label $i mismatch")}
      val replacement=Label(1,0x101,7,Vector.fill(8)(BigInt(0xabcdef)))
      t.write(replacement)
      d.io.count.expect(8.U)
      assert(t.query(replacement,20)==Some(replacement.copy(kind=0,id=0)))
      t.remove(labels(2));d.io.count.expect(7.U);d.io.full.expect(false.B)
      assert(t.query(labels(2),21).isEmpty)
      t.write(labels(2));d.io.count.expect(8.U)
      assert(t.query(labels(2),22).isDefined)
      info("DEBUG_LABELS capacity8=true replaceKeepsCount=true removeClears=true exactPayload=true")
    }
  }
  it should "drop writes when full and clear the table overflow" in {
    simulate(new DebugLabelStore) { d=>val t=new Driver(d)
      for(i<-0 until 8)t.write(Label(1,i,1,Vector.fill(8)(BigInt(i))))
      val extra=Label(2,999,3,Vector.fill(8)(BigInt(0x55)))
      t.write(extra)
      d.io.count.expect(8.U);d.io.overflow.expect(true.B)
      assert(t.query(extra,1).isEmpty)
      t.clear()
      d.io.count.expect(0.U);d.io.overflow.expect(false.B);d.io.full.expect(false.B)
      assert(t.query(Label(1,0,0,Vector.fill(8)(BigInt(0))),2).isEmpty)
      t.write(extra);assert(t.query(extra,3).isDefined)
      info("DEBUG_LABELS_OVERFLOW dropNewKey=true sticky=true clearRecovers=true")
    }
  }
  it should "maintain the bounded debug-group stack and its current label" in {
    simulate(new DebugLabelStore) { d=>val t=new Driver(d)
      val groups=(0 until 8).map{i=>Label(9,i,i,Vector.fill(8)(BigInt(0x1000+i)))}
      groups.foreach(t.push)
      d.io.groupDepth.expect(8.U);d.io.groupValid.expect(true.B)
      d.io.group.words(0).expect(0x1007L.U);d.io.group.key.objectId.expect(7.U)
      t.push(Label(9,99,0,Vector.fill(8)(BigInt(0x999))))
      d.io.groupDepth.expect(8.U);d.io.overflow.expect(true.B)
      for(i<-0 until 8){
        d.io.group.words(0).expect(BigInt(0x1007-i).U)
        t.pop()}
      d.io.groupDepth.expect(0.U);d.io.groupValid.expect(false.B)
      t.pop()
      d.io.groupDepth.expect(0.U)
      t.push(groups(0));d.io.group.words(0).expect(0x1000L.U)
      t.clear();d.io.groupDepth.expect(0.U);d.io.overflow.expect(false.B)
      info("DEBUG_GROUPS depth8=true pushOrder=true dropWhenFull=true popEmptyIgnored=true clearResets=true")
    }
  }
  it should "hold query results under backpressure" in {
    simulate(new DebugLabelStore) { d=>val t=new Driver(d)
      val l=Label(4,7,5,Vector.tabulate(8)(w=>BigInt(w+1)))
      t.write(l)
      d.io.query.bits.key.objectType.poke(4.U);d.io.query.bits.key.objectId.poke(7.U);d.io.query.bits.tag.poke(9.U)
      d.io.query.valid.poke(true.B);d.clock.step();d.io.query.valid.poke(false.B)
      var n=0;while(!d.io.result.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(n<20);val held=d.io.result.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();assert(d.io.result.bits.peek().litValue==held);d.io.query.ready.expect(false.B)}
      d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B)
      info("DEBUG_LABELS_HOLD immutable=true exactResult=true")
    }
  }
}
