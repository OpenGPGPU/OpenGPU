package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,VectorTlbFlush}

object TessellationInputMemoryFabric {
  def transactions(renderIds:Int):Int=TileBinHostMemoryFabric.transactions(renderIds,1,4,programmable=true)
}

/** Route the image renderer and its patch-input loader through independent
  * Sv32 line clients. The caller holds satp and allocation ownership until
  * drained; input requests are read-only and use local transaction ID zero.
  */
class TessellationInputMemoryFabric(config:GpuConfig,renderIds:Int) extends Module {
  require(renderIds>0)
  private val slots=TessellationInputMemoryFabric.transactions(renderIds)
  val io=IO(new Bundle {
    val renderRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,renderIds)))
    val renderResponse=Decoupled(new ComputeMemoryResponse(64,renderIds))
    val inputRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,1)))
    val inputResponse=Decoupled(new ComputeMemoryResponse(64,1))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)))
    val walkActive=Output(Vec(4,UInt(3.W)));val drained=Output(Bool())
  })
  private val fabric=Module(new TileBinHostMemoryFabric(config,renderIds,1,4,0,programmable=true))
  fabric.io.renderRequest<>io.renderRequest;io.renderResponse<>fabric.io.renderResponse
  fabric.io.stagingRequest(0).valid:=io.inputRequest.valid
  fabric.io.stagingRequest(0).bits:=io.inputRequest.bits
  io.inputRequest.ready:=fabric.io.stagingRequest(0).ready
  io.inputResponse.valid:=fabric.io.stagingResponse(0).valid
  io.inputResponse.bits:=fabric.io.stagingResponse(0).bits
  fabric.io.stagingResponse(0).ready:=io.inputResponse.ready
  when(io.inputRequest.valid){assert(io.inputRequest.bits.transactionId===0.U && !io.inputRequest.bits.isWrite,
    "patch input must use read-only transaction zero")}
  when(fabric.io.stagingResponse(0).valid){assert(fabric.io.stagingResponse(0).bits.transactionId===0.U)}
  fabric.io.dmaRequest.valid:=false.B;fabric.io.dmaRequest.bits:=0.U.asTypeOf(fabric.io.dmaRequest.bits)
  fabric.io.dmaResponse.ready:=true.B
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse
  fabric.io.satp:=io.satp;fabric.io.flush:=io.flush
  io.translationStall:=fabric.io.translationStall;io.translationMiss:=fabric.io.translationMiss;io.walkActive:=fabric.io.walkActive
  io.drained:=fabric.io.drained
}
