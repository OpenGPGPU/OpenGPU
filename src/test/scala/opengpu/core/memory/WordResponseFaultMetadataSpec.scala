package opengpu.core.memory

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class WordResponseFaultMetadataSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((kind,write) <- Seq(("plain",false),("plain",true),("combined",false),("combined",true),("command",false),("texture",false)))
  it should s"retain word fault metadata independently of ready and recycle failed lines kind=$kind write=$write" in {
    simulate(new TranslatedWordClient(GpuConfig(lanes=4,warps=2),wordTransactions=8,outstanding=8,
      uncached=true,preserveCachePolicy=true,coalesceWrites=kind=="combined",
      bufferCommandReads=kind=="command",bufferTextureReads=kind=="texture")) { d=>
      zero(d.io);d.io.memReq.ready.poke(true.B);d.io.pageWalk.ready.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val ids=mutable.ArrayBuffer.empty[BigInt]
      val replies=mutable.ArrayBuffer.empty[(BigInt,Boolean)]
      var cycles=0;var lowerFired=false;var faultPulses=0
      def tick(): Unit = {
        if(d.io.memReq.valid.peek().litToBoolean && d.io.memReq.ready.peek().litToBoolean)
          ids+=d.io.memReq.bits.transactionId.peek().litValue
        val fire=d.io.memResp.valid.peek().litToBoolean && d.io.memResp.ready.peek().litToBoolean
        if(d.io.fault.peek().litToBoolean)faultPulses+=1
        if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean)
          replies+=((d.io.out.bits.addr.peek().litValue,d.io.responseFault.peek().litToBoolean))
        d.clock.step();cycles+=1
        if(fire){lowerFired=true;d.io.memResp.valid.poke(false.B)}
      }
      def until(condition: => Boolean): Unit = {
        val start=cycles
        while(!condition && cycles-start<300)tick()
        assert(condition,s"stalled $kind write=$write cycle=$cycles replies=$replies")
      }
      def send(address: Int): Unit = {
        d.io.in.bits.addr.poke(address.U);d.io.in.bits.write.poke(write.B);d.io.in.bits.data.poke(0x10203040.U)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      }
      val members=if(kind=="combined" || kind=="texture")2 else 1
      send(0x1000);if(members==2)send(0x1004)
      until(ids.nonEmpty);assert(ids.size==1)
      d.io.memResp.bits.transactionId.poke(ids.head.U);d.io.memResp.bits.fault.poke(true.B)
      d.io.memResp.bits.readData.poke(BigInt("10203040"*16,16).U);d.io.memResp.valid.poke(true.B)
      until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      for(_ <- 0 until 8) {
        d.io.out.valid.expect(true.B);d.io.responseFault.expect(true.B)
        assert(d.io.out.bits.peek().litValue==held);tick()
      }
      // Combinationally changing ready cannot change offered word metadata.
      d.io.out.ready.poke(true.B);d.io.responseFault.expect(true.B)
      until(replies.size==members && lowerFired)
      assert(replies.forall(_._2) && replies.map(_._1).toSet==(0 until members).map(n=>BigInt(0x1000+n*4)).toSet)
      assert(faultPulses==1,"the line-consumed fault pulse changed semantics")
      d.io.out.ready.poke(false.B);d.io.responseFault.expect(false.B)
      val before=ids.size;send(0x1000);until(ids.size>before)
      assert(ids.size==before+1,"faulted data was reused as a successful cache hit")
      lowerFired=false;d.io.memResp.bits.transactionId.poke(ids.last.U)
      d.io.memResp.bits.fault.poke(false.B);d.io.memResp.valid.poke(true.B)
      until(d.io.out.valid.peek().litToBoolean)
      for(_ <- 0 until 6){d.io.responseFault.expect(false.B);d.io.out.valid.expect(true.B);tick()}
      d.io.out.ready.poke(true.B);until(replies.size==members+1 && lowerFired)
      assert(!replies.last._2 && faultPulses==1)
      d.io.responseFault.expect(false.B)
    }
  }
}
