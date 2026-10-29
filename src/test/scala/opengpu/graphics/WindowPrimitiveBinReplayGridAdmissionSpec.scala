package opengpu.graphics

import chisel3._
import chisel3.util.Cat
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class WindowGridAdmissionHarness(capacity: Int) extends WindowPrimitiveBinReplayPass(
  g=GraphicsConfig(screenWidth=2048,screenHeight=2048),engines=4,pagedCapacity=capacity) {
  val childBeginOffers=IO(Output(UInt(2.W)))
  childBeginOffers:=Cat(producer.io.begin.valid,bins.io.begin.valid)
}

/** Descriptor admission only. No primitive is offered and at most six clocks
  * execute after reset; neither pixels nor the complete directory are walked.
  */
class WindowPrimitiveBinReplayGridAdmissionSpec extends AnyFlatSpec {
  private def check(capacity: Int,tiles: Int,accepted: Boolean): Unit = {
    simulate(new WindowGridAdmissionHarness(capacity)){d=>
      d.io.begin.valid.poke(false.B);d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.end.valid.poke(false.B);d.io.end.bits.poke(true.B)
      d.io.out.foreach(_.ready.poke(false.B));d.io.tileDone.ready.poke(false.B)
      d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val a=d.io.begin.bits.producer;val b=d.io.begin.bits.bins
      def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke("h03000000".U);a.arena.limit.poke("h04000000".U)
      a.screenWidth.poke(2048.U);a.screenHeight.poke(2048.U);a.tileLog2.poke(3.U)
      b.version.poke(3.U);b.tileCount.poke(tiles.U)
      b.directoryBase.poke("h08000000".U);b.directoryLimit.poke("h08400000".U)
      b.pool.version.poke(1.U);identity(b.pool.identity)
      b.pool.base.poke("h40000000".U);b.pool.limit.poke("h60000000".U);b.pool.pageSizeLog2.poke(12.U)
      d.io.begin.ready.expect(true.B);d.io.begin.valid.poke(true.B);d.clock.step();d.io.begin.valid.poke(false.B)
      var cycles=0
      while(d.childBeginOffers.peek().litValue==0 && !d.io.completion.valid.peek().litToBoolean && cycles<6){
        d.clock.step();cycles+=1
      }
      assert(cycles<6,"descriptor neither reached its children nor returned a rejection")
      d.io.memoryRequest.valid.expect(false.B)
      if(accepted){
        d.childBeginOffers.expect(3.U);d.io.completion.valid.expect(false.B)
      }else{
        d.childBeginOffers.expect(0.U);d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(1.U)
      }
    }
  }
  it should "admit the complete 65536-entry grid to both real child owners" in {check(65536,65536,accepted=true)}
  it should "retain the legacy profile rejection of a 65536-entry grid" in {check(16384,65536,accepted=false)}
  it should "reject a count that does not match the encoded grid" in {check(65536,65535,accepted=false)}
  it should "reject a count beyond the complete wide directory" in {check(65536,65537,accepted=false)}
}
