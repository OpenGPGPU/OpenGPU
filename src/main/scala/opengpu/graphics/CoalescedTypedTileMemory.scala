package opengpu.graphics

import chisel3._
import chisel3.util._

/** Tagged coalescing frontend and the actual atomic banked tile SRAM service. */
class CoalescedTypedTileMemory(p: TypedTileColorConfig=TypedTileColorConfig(),
    ports: Int=4,banks: Int=8,slots: Int=16,useBlackBoxes: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val request=Vec(ports,Flipped(Decoupled(new TypedTileWordRequest)))
    val response=Vec(ports,Decoupled(new TypedTileWordResponse))
    val drained=Output(Bool())
  })
  protected val coalescer=Module(new TypedTileWordCoalescer(ports,slots))
  protected val memory=Module(new BankedTypedTileMemory(p,ports,banks,useBlackBoxes))
  coalescer.io.request<>io.request;io.response<>coalescer.io.response
  memory.io.request<>coalescer.io.memoryRequest;coalescer.io.memoryResponse<>memory.io.response
  io.drained:=coalescer.io.drained && memory.io.drained
}
