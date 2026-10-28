package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativePixelMetadataQueueSpec extends AnyFlatSpec {
  import WindowRecordTest._

  it should "keep speculative tail captures invisible and preserve held full rows through wrap and backpressure" in {
    simulate(new NativePixelMetadataQueue(64)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val expected=mutable.Queue.empty[BigInt]
      val random=new scala.util.Random(0x4d455441L)
      var offered=0;var returned=0;var speculative=0;var full=0;var concurrent=0
      var held=Option.empty[BigInt]
      def tick(capture: Boolean,commit: Boolean,ready: Boolean): Unit={
        d.io.capture.poke(capture.B);d.io.enq.valid.poke(commit.B);d.io.deq.ready.poke(ready.B)
        for((_,x)<-leaves(d.io.enq.bits))x match {
          case u: UInt=>u.poke(BigInt(u.getWidth,random).U)
          case _=>()
        }
        val input=d.io.enq.bits.peek().litValue
        val valid=d.io.deq.valid.peek().litToBoolean
        val output=if(valid)Some(d.io.deq.bits.peek().litValue)else None
        assert(valid==expected.nonEmpty,"uncommitted capture changed visibility")
        output.foreach(value=>assert(value==expected.front,"metadata changed or reordered"))
        held.foreach(value=>assert(output.contains(value),"held metadata changed during speculative capture"))
        val push=commit && d.io.enq.ready.peek().litToBoolean
        val pop=valid && ready
        if(!d.io.enq.ready.peek().litToBoolean)full+=1
        if(capture && !commit)speculative+=1
        if(push && pop)concurrent+=1
        if(pop){expected.dequeue();returned+=1}
        if(push){assert(capture);expected.enqueue(input);offered+=1}
        held=if(valid && !ready)output else None
        d.clock.step()
      }
      // Writes into an unowned row must not consume capacity or publish data.
      for(_<-0 until 96)tick(capture=true,commit=false,ready=false)
      assert(offered==0 && returned==0)
      for(_<-0 until 64)tick(capture=true,commit=true,ready=false)
      assert(expected.size==64)
      // At full capacity tail equals head; speculative data must not overwrite it.
      for(_<-0 until 32)tick(capture=true,commit=false,ready=false)
      for(_<-0 until 512){
        val capture=random.nextInt(5)!=0
        tick(capture,commit=capture && random.nextBoolean(),ready=random.nextBoolean())
      }
      // Long simultaneous transfers cross both pointer wraps repeatedly.
      for(_<-0 until 256)tick(capture=true,commit=true,ready=true)
      while(expected.nonEmpty)tick(capture=false,commit=false,ready=true)
      tick(capture=false,commit=false,ready=true)
      assert(offered==returned && offered>=320 && full>=32 && speculative>=128 && concurrent>=192)
      d.io.deq.valid.expect(false.B)
      info(s"NATIVE_METADATA_CAPTURE allocations=$offered retirements=$returned speculative=$speculative fullCycles=$full concurrent=$concurrent exactBits=true uncommittedInvisible=true heldFullRowsImmutable=true repeatedWrap=true")
    }
  }
}
