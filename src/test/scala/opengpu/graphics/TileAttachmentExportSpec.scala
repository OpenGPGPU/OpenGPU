package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Model a one-cycle SRAM whose output becomes invalid after a disabled read.
  * The physical macro may hold data, but the export pipeline cannot require it.
  */
private class ExportSramHarness extends Module {
  val io=IO(new Bundle {
    val prepare=Flipped(Decoupled(new TileAttachmentExportWord))
    val request=Decoupled(new OmMemoryRequest)
    val response=Flipped(Decoupled(new OmMemoryResponse))
    val begin=Input(Bool())
    val drained=Output(Bool())
  })
  val e=Module(new TileAttachmentExport)
  e.io.begin:=io.begin;e.io.prepare<>io.prepare;io.request<>e.io.local.req
  e.io.local.resp<>io.response;io.drained:=e.io.drained
  val read=io.prepare.fire && io.prepare.bits.fromSram
  e.io.colorData:=RegNext(Mux(read,io.prepare.bits.address ^ 0xc0100000L.U,0.U))
  e.io.depthData:=RegNext(Mux(read,io.prepare.bits.address ^ 0xda7a0000L.U,0.U))
}

class TileAttachmentExportSpec extends AnyFlatSpec {
  for(stalled <- Seq(false,true))it should s"retain synchronous read results and exact private acknowledgements stalled=$stalled" in {
    simulate(new ExportSramHarness) { d=>
      d.io.prepare.valid.poke(false.B);d.io.prepare.bits.poke(0.U.asTypeOf(d.io.prepare.bits))
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits));d.io.begin.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
      val expected=mutable.Queue.empty[(Long,Long)]
      val replies=mutable.Queue.empty[(Long,Int)]
      val total=160;var prepared=0;var requested=0;var retired=0;var cycle=0
      var held=Option.empty[BigInt];var consecutive=0;var peak=0
      while((retired<total || !d.io.drained.peek().litToBoolean) && cycle<5000) {
        val address=0x10000L+prepared*4L;val depth=prepared%3==0;val fromSram=prepared%5!=4
        d.io.prepare.valid.poke((prepared<total).B);d.io.prepare.bits.address.poke(address.U)
        d.io.prepare.bits.fromSram.poke(fromSram.B);d.io.prepare.bits.depth.poke(depth.B)
        d.io.prepare.bits.clearValue.poke((0xabcdef00L+prepared).U)
        val ready= !stalled || (cycle%17>=9)
        d.io.request.ready.poke(ready.B)
        val response=replies.headOption.filter(_._2<=cycle)
        d.io.response.valid.poke(response.nonEmpty.B)
        response.foreach { case(a,_)=>
          d.io.response.bits.addr.poke(a.U);d.io.response.bits.write.poke(true.B);d.io.response.bits.data.poke(0.U)
        }
        held.foreach(b=>{d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==b)})
        held=if(d.io.request.valid.peek().litToBoolean && !ready)Some(d.io.request.bits.peek().litValue) else None
        if(response.nonEmpty && d.io.response.ready.peek().litToBoolean){replies.dequeue();retired+=1}
        if(d.io.prepare.valid.peek().litToBoolean && d.io.prepare.ready.peek().litToBoolean) {
          expected.enqueue((address,if(fromSram)address ^ (if(depth)0xda7a0000L else 0xc0100000L) else 0xabcdef00L+prepared))
          prepared+=1
        }
        if(d.io.request.valid.peek().litToBoolean && ready) {
          val(a,value)=expected.dequeue();d.io.request.bits.addr.expect(a.U)
          d.io.request.bits.data.expect(value.U);d.io.request.bits.write.expect(true.B)
          replies.enqueue((a,cycle+(if(stalled)7 else 1)));requested+=1;consecutive+=1
        } else consecutive=0
        peak=math.max(peak,consecutive)
        d.clock.step();cycle+=1
      }
      assert(prepared==total && requested==total && retired==total && expected.isEmpty && replies.isEmpty)
      d.io.drained.expect(true.B)
      if(!stalled)assert(peak>=64,s"continuous preparation failed to sustain one word/cycle, peak=$peak")
      info(s"TILE_EXPORT_PIPE stalled=$stalled words=$total cycles=$cycle consecutive=$peak")
    }
  }
}
