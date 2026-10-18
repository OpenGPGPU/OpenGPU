package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TileBinSharedResponseRoutingHarness extends Module {
  val io=IO(new Bundle {
    val transactionId=Input(UInt(7.W))
    val clientReady=Input(Vec(6,Bool()))
    val ready=Output(Bool())
  })
  // Unequal payload ranges followed by three one-ID page walkers.
  io.ready:=TileBinSharedResponseRouting.ready(io.transactionId,
    Seq(0,21,53,85,86,87),Seq(21,32,32,1,1,1),io.clientReady.toSeq)
}

class TileBinSharedResponseRoutingSpec extends AnyFlatSpec {
  it should "retain a selected-client stall despite unrelated ready clients and reject unowned IDs" in {
    simulate(new TileBinSharedResponseRoutingHarness) { h=>
      h.reset.poke(false.B)
      val owners=Vector.fill(21)(0) ++ Vector.fill(32)(1) ++ Vector.fill(32)(2) ++ Vector(3,4,5)
      for (id <- 0 until 128; mask <- 0 until 64) {
        h.io.transactionId.poke(id.U)
        for (i <- 0 until 6) h.io.clientReady(i).poke(((mask & (1<<i))!=0).B)
        val expected=id<owners.size && (mask & (1<<owners(id)))!=0
        h.io.ready.expect(expected.B)
      }
      info("SHARED_RESPONSE_READY ids=128 readyMasks=64 combinations=8192 selectedStallsPreserved=true")
    }
  }
}
