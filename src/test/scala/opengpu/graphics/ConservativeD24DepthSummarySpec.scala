package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class ConservativeD24DepthSummarySpec extends AnyFlatSpec {
  private val maxDepth=0xffffff
  private class Driver(val d: ConservativeD24DepthSummary) {
    var memory=Array.empty[Option[(Int,Int)]]
    d.io.bind.valid.poke(false.B);d.io.bind.bits.poke(0.U.asTypeOf(d.io.bind.bits))
    d.io.invalidate.poke(false.B)
    d.io.writes.foreach{w=>w.valid.poke(false.B);w.bits.poke(0.U.asTypeOf(w.bits))}
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def bind(words: Int,clear: Boolean,depth: Int=0): Unit={
      d.io.bind.bits.words.poke(words.U);d.io.bind.bits.clear.poke(clear.B);d.io.bind.bits.depth.poke(depth.U)
      d.io.bind.valid.poke(true.B);d.io.bounds.valid.expect(false.B);d.clock.step();d.io.bind.valid.poke(false.B)
      memory=Array.fill(words)(if(clear)Some((depth,depth))else None)
      d.io.bounds.valid.expect(clear.B);check()
    }
    def check(): Unit={
      if(d.io.bounds.valid.peek().litToBoolean){
        assert(memory.nonEmpty && memory.forall(_.nonEmpty),"summary trusted an unobserved loaded word")
        val actual=memory.toVector.flatten.flatMap{case(a,b)=>Vector(a,b)}
        val lo=d.io.bounds.minimum.peek().litValue.toInt;val hi=d.io.bounds.maximum.peek().litValue.toInt
        assert(lo<=actual.min && hi>=actual.max && lo<=hi,
          s"[$lo,$hi] excludes resident depths [${actual.min},${actual.max}]")
      }
    }
    def write(events: (Int,Int,Int)*): Unit={
      require(events.size<=2 && events.map(_._1).distinct.size==events.size)
      for((w,i)<-d.io.writes.zipWithIndex){
        w.valid.poke((i<events.size).B)
        if(i<events.size){val(address,a,b)=events(i)
          w.bits.word.poke(address.U);w.bits.depths(0).poke(a.U);w.bits.depths(1).poke(b.U)
        }
      }
      if(events.nonEmpty)d.io.bounds.valid.expect(false.B)
      d.clock.step();d.io.writes.foreach(_.valid.poke(false.B))
      if(events.nonEmpty)d.io.bounds.valid.expect(false.B)
      events.foreach{case(address,a,b)=>if(address<memory.length)memory(address)=Some((a,b))}
      check()
    }
    def settle(): Unit={d.clock.step(2);check()}
    def expectBounds(lo: Int,hi: Int): Unit={
      settle()
      d.io.bounds.valid.expect(true.B);d.io.bounds.minimum.expect(lo.U);d.io.bounds.maximum.expect(hi.U)
    }
    def invalidate(): Unit={
      d.io.invalidate.poke(true.B);d.io.bounds.valid.expect(false.B);d.clock.step();d.io.invalidate.poke(false.B)
      d.io.bounds.valid.expect(false.B);memory=Array.empty
    }
  }
  behavior of "ConservativeD24DepthSummary"

  it should "withhold stale bounds until observation drains and flush either pending stage on generation changes" in {
    simulate(new ConservativeD24DepthSummary()){d=>
      val h=new Driver(d)
      h.bind(2,clear=true,100)
      h.write((0,0,maxDepth));d.clock.step();d.io.bounds.valid.expect(false.B)
      d.clock.step();h.expectBounds(0,maxDepth)
      for(age<-0 to 1){
        h.bind(2,clear=true,100)
        h.write((0,1,2));if(age>0)d.clock.step(age)
        h.bind(2,clear=false);h.settle();d.io.bounds.valid.expect(false.B)
        h.write((0,50,60));h.settle();d.io.bounds.valid.expect(false.B)
        h.write((1,70,80));h.expectBounds(50,80)
        h.write((0,0,maxDepth));if(age>0)d.clock.step(age)
        h.invalidate();h.bind(2,clear=true,200);h.expectBounds(200,200)
      }
      // Every input cycle is accepted, including consecutive single-word epochs.
      h.bind(1,clear=false)
      for(z<-10 to 19)h.write((0,z,z+1))
      h.expectBounds(19,20)
    }
  }

  it should "retain safe bounds across duplicate writes, partial epochs, invalidation and full native capacity" in {
    simulate(new ConservativeD24DepthSummary()){d=>
      val h=new Driver(d)
      h.bind(4,clear=true,100)
      h.write((0,20,30),(1,40,50));h.expectBounds(20,100)
      h.write((0,60,70),(2,80,90));h.expectBounds(20,100)
      h.write((3,55,65));h.expectBounds(20,90) // overwritten20 remains conservative until the next complete epoch
      h.write((0,61,62),(1,63,64));h.expectBounds(20,90)
      h.write((2,65,66),(3,67,68));h.expectBounds(61,68)
      h.write((0,0,maxDepth),(511,0,0));h.expectBounds(0,maxDepth) // unrelated word cannot complete coverage
      h.invalidate()
      h.write((0,1,1),(1,1,1));d.io.bounds.valid.expect(false.B)
      h.bind(512,clear=false)
      for(i<-0 until 510 by 2){h.write((i,100+i,101+i),(i+1,101+i,102+i));d.io.bounds.valid.expect(false.B)}
      h.write((510,610,611));d.io.bounds.valid.expect(false.B)
      h.write((511,611,612));h.expectBounds(100,612)
      // A new generation at the same plane must discard old complete coverage.
      h.bind(2,clear=false);h.write((0,7,8));d.io.bounds.valid.expect(false.B)
      h.write((1,9,10));h.expectBounds(7,10)
      // Invalidation wins over simultaneous rebind/write and closes combinational trust immediately.
      d.io.bind.valid.poke(true.B);d.io.bind.bits.words.poke(2.U);d.io.bind.bits.clear.poke(true.B)
      d.io.invalidate.poke(true.B);d.io.writes(0).valid.poke(true.B)
      d.io.bounds.valid.expect(false.B);d.clock.step()
      d.io.bind.valid.poke(false.B);d.io.invalidate.poke(false.B);d.io.writes(0).valid.poke(false.B)
      d.io.bounds.valid.expect(false.B)
    }
  }

  it should "contain every actual resident sample under adversarial reordered two-port writes" in {
    simulate(new ConservativeD24DepthSummary()){d=>
      val h=new Driver(d);val random=new Random(0xead24)
      for(generation<-0 until 12){
        val words=Vector(2,8,32,128)(generation%4)
        h.bind(words,clear=generation%2==0,random.nextInt(maxDepth+1))
        for(round<-0 until 3){
          val order=random.shuffle((0 until words).toVector)
          for(pair<-order.grouped(2)){
            h.write(pair.map(i=>(i,random.nextInt(maxDepth+1),random.nextInt(maxDepth+1))):_*)
            // Repeat one already observed word with extreme/reversed values.
            if(random.nextBoolean())h.write((pair.head,if(round%2==0)0 else maxDepth,random.nextInt(maxDepth+1)))
          }
          h.settle();d.io.bounds.valid.expect(true.B)
        }
        h.invalidate()
      }
    }
  }
}
