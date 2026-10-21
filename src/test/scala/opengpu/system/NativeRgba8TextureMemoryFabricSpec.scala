package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import chisel3.reflect.DataMirror
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureMemoryFabricSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for (clusters <- Seq(1, 2, 3)) it should s"retain every physical namespace through reordered stalled fault replies and reuse clusters=$clusters" in {
    simulate(new NativeRgba8TextureMemoryFabric(GpuConfig(lanes=4,warps=2),64,clusters)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val sources=Seq(d.io.rendererRequest) ++ (0 until clusters).flatMap(n=>Seq(d.io.dataRequest(n),d.io.pageWalk(n)))
      val sinks=Seq(d.io.rendererResponse) ++ (0 until clusters).flatMap(n=>Seq(d.io.dataResponse(n),d.io.pageWalkResponse(n)))
      val bases=Seq(0) ++ (0 until clusters).flatMap(n=>Seq(64+32*n,80+32*n))
      val sizes=Seq(64) ++ (0 until clusters).flatMap(_=>Seq(16,4))
      val total=sizes.sum
      for (round <- 0 until 2) {
        val next=Array.fill(sources.size)(0)
        val seen=mutable.ArrayBuffer.empty[Int]
        var cycle=0; var held: Option[BigInt]=None
        sinks.foreach(_.ready.poke(true.B))
        while (seen.size<total && cycle<2000) {
          for((source,n)<-sources.zipWithIndex) {
            source.valid.poke((next(n)<sizes(n)).B)
            if(next(n)<sizes(n)) {
              val id=bases(n)+next(n)
              zero(source.bits);source.bits.transactionId.poke(next(n).U)
              source.bits.address.poke((0x100000+round*0x10000+id*64).U)
              source.bits.sizeLog2.poke((if(n>0 && n%2==0)2 else 6).U)
              source.bits.cachePolicy.poke((id%4).U)
              source.bits.isWrite.poke((n==0 && id%2==1).B)
              source.bits.byteMask.poke((BigInt(1)<<(id%64)).U)
              source.bits.writeData.poke((BigInt(id)<<64|round).U)
            }
          }
          val ready=cycle%7>=3; d.io.memoryRequest.ready.poke(ready.B)
          val valid=d.io.memoryRequest.valid.peek().litToBoolean
          held.foreach { bits=>assert(valid);assert(d.io.memoryRequest.bits.peek().litValue==bits,"held physical offer changed") }
          held=if(valid && !ready)Some(d.io.memoryRequest.bits.peek().litValue)else None
          val fires=sources.map(s=>s.valid.peek().litToBoolean && s.ready.peek().litToBoolean)
          if(valid && ready) {
            val b=d.io.memoryRequest.bits;val id=b.transactionId.peek().litValue.toInt
            assert(!seen.contains(id));seen+=id
            val n=bases.indices.find(n=>id>=bases(n) && id<bases(n)+sizes(n)).get
            b.address.expect((0x100000+round*0x10000+id*64).U)
            b.sizeLog2.expect((if(n>0 && n%2==0)2 else 6).U)
            b.cachePolicy.expect((id%4).U);b.isWrite.expect((n==0 && id%2==1).B)
            b.byteMask.expect((BigInt(1)<<(id%64)).U);b.writeData.expect((BigInt(id)<<64|round).U)
          }
          d.clock.step();for(n<-sources.indices)if(fires(n))next(n)+=1
          cycle+=1
        }
        assert(seen.size==total && next.toSeq==sizes)
        sources.foreach(_.valid.poke(false.B));d.io.memoryRequest.ready.poke(true.B)
        d.io.drained.expect(false.B)
        // Every source uses local ID zero, including independent page walkers.
        assert(bases.forall(seen.contains))
        for(id<-seen.reverse) {
          val n=bases.indices.find(n=>id>=bases(n) && id<bases(n)+sizes(n)).get
          val sink=sinks(n);val fault=id%5==0
          d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.transactionId.poke(id.U)
          d.io.memoryResponse.bits.readData.poke((BigInt(round+1)<<256|id).U)
          d.io.memoryResponse.bits.fault.poke(fault.B);sink.ready.poke(false.B)
          for(_<-0 until (if(id%7==0)9 else 1)) {
            d.io.memoryResponse.ready.expect(false.B);sink.valid.expect(true.B)
            sink.bits.transactionId.expect((id-bases(n)).U)
            sink.bits.readData.expect((BigInt(round+1)<<256|id).U);sink.bits.fault.expect(fault.B)
            for(m<-sinks.indices if m!=n)sinks(m).valid.expect(false.B)
            d.io.drained.expect(false.B);d.clock.step()
          }
          sink.ready.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
        }
        d.io.memoryResponse.valid.poke(false.B);d.clock.step();d.io.drained.expect(true.B)
      }
      info(s"NATIVE_RGBA8_MEMORY clusters=$clusters physicalIDs=$total rounds=2 allPortsSaturated=true noIDAlias=true reorderedReplies=true heldOffers=true heldFaults=true exactMetadata=true")
    }
  }
}
