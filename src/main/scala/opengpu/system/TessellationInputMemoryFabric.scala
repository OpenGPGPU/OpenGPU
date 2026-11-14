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
class TessellationInputMemoryFabric(config:GpuConfig,renderIds:Int,ownedVertexSource:Boolean=false) extends Module {
  def this(config:GpuConfig,renderIds:Int)=this(config,renderIds,false)
  require(renderIds>0)
  private val slots=TessellationInputMemoryFabric.transactions(renderIds)
  val io=IO(new Bundle {
    val renderRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,renderIds)))
    val renderResponse=Decoupled(new ComputeMemoryResponse(64,renderIds))
    val inputRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,1)))
    val inputResponse=Decoupled(new ComputeMemoryResponse(64,1))
    val sourceRequest=if(ownedVertexSource)Some(Flipped(Decoupled(new ComputeMemoryRequest(config,64,4))))else None
    val sourceResponse=if(ownedVertexSource)Some(Decoupled(new ComputeMemoryResponse(64,4)))else None
    /** Requested selection only. selected changes after all old credits drain. */
    val sourceLive=if(ownedVertexSource)Some(Input(Bool()))else None
    val sourceSelected=if(ownedVertexSource)Some(Output(Bool()))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)))
    val walkActive=Output(Vec(4,UInt(3.W)));val drained=Output(Bool())
  })
  private val fabric=Module(new TileBinHostMemoryFabric(config,renderIds,1,4,0,programmable=true))
  fabric.io.renderRequest<>io.renderRequest;io.renderResponse<>fabric.io.renderResponse
  if(ownedVertexSource){
    val adapter=Module(new TessellationVertexMemoryAdapter(config))
    val selected=RegInit(false.B)
    // Recognize an offered request while its owner is asking for this port.
    // Withdrawing selection intent cannot revoke that Decoupled offer: grant
    // it once after the current owner drains, even if intent has since moved.
    val sourceOwed=RegInit(false.B)
    val legacyOwed=RegInit(false.B)
    when(!selected && io.sourceLive.get && io.sourceRequest.get.valid){sourceOwed:=true.B}
    when(selected && !io.sourceLive.get && io.inputRequest.valid){legacyOwed:=true.B}
    when(adapter.io.sourceRequest.fire){sourceOwed:=false.B}
    when(io.inputRequest.fire){legacyOwed:=false.B}
    adapter.io.sourceRequest.valid:=selected && io.sourceRequest.get.valid
    adapter.io.sourceRequest.bits:=io.sourceRequest.get.bits
    io.sourceRequest.get.ready:=selected && adapter.io.sourceRequest.ready
    io.sourceResponse.get<>adapter.io.sourceResponse
    io.sourceSelected.get:=selected
    fabric.io.stagingRequest(0).valid:=Mux(selected,adapter.io.memoryRequest.valid,io.inputRequest.valid)
    fabric.io.stagingRequest(0).bits:=Mux(selected,adapter.io.memoryRequest.bits,io.inputRequest.bits)
    adapter.io.memoryRequest.ready:=selected && fabric.io.stagingRequest(0).ready
    io.inputRequest.ready:= !selected && fabric.io.stagingRequest(0).ready
    // Check the full four-slot staging tag before narrowing to the one-slot
    // adapter; e.g. tag 2 must never truncate into an apparently owned zero.
    val responseOwned=fabric.io.stagingResponse(0).bits.transactionId===0.U
    adapter.io.memoryResponse.valid:=selected && responseOwned && fabric.io.stagingResponse(0).valid
    adapter.io.memoryResponse.bits:=fabric.io.stagingResponse(0).bits
    io.inputResponse.valid:= !selected && responseOwned && fabric.io.stagingResponse(0).valid
    io.inputResponse.bits:=fabric.io.stagingResponse(0).bits
    fabric.io.stagingResponse(0).ready:=responseOwned && Mux(selected,adapter.io.memoryResponse.ready,io.inputResponse.ready)
    // Valid-only flushes have no acknowledgement channel. Retain them across
    // late writes/replies; overlapping different scopes can over-invalidate
    // safely with a full flush. Neither a flush nor sourceLive retags traffic.
    val pendingFlush=RegInit(false.B)
    val flushBits=RegInit(0.U.asTypeOf(new VectorTlbFlush(config)))
    val merged=WireDefault(Mux(pendingFlush,flushBits,io.flush.bits))
    when(pendingFlush && io.flush.valid && flushBits.asUInt=/=io.flush.bits.asUInt){
      merged:=0.U.asTypeOf(merged)
    }
    val physicallyDrained=fabric.io.drained && adapter.io.drained
    val flushRequested=pendingFlush || io.flush.valid
    val applyFlush=physicallyDrained && flushRequested
    fabric.io.flush.valid:=applyFlush;fabric.io.flush.bits:=merged
    when(io.flush.valid){pendingFlush:=true.B;flushBits:=merged}
    when(applyFlush){pendingFlush:=false.B}
    when(physicallyDrained && !flushRequested){
      // Unselected offers do not enter physicallyDrained: they must be able
      // to acquire the grant. Keep a granted debt until actual acceptance.
      when(selected && sourceOwed){selected:=true.B}
        .elsewhen(!selected && legacyOwed){selected:=false.B}
        .elsewhen(selected && legacyOwed){selected:=false.B}
        .elsewhen(!selected && sourceOwed){selected:=true.B}
        .otherwise{selected:=io.sourceLive.get}
    }
    io.drained:=physicallyDrained && !flushRequested && (selected===io.sourceLive.get) &&
      !sourceOwed && !legacyOwed && !io.sourceRequest.get.valid && !io.inputRequest.valid
  }else{
    fabric.io.stagingRequest(0).valid:=io.inputRequest.valid
    fabric.io.stagingRequest(0).bits:=io.inputRequest.bits
    io.inputRequest.ready:=fabric.io.stagingRequest(0).ready
    io.inputResponse.valid:=fabric.io.stagingResponse(0).valid
    io.inputResponse.bits:=fabric.io.stagingResponse(0).bits
    fabric.io.stagingResponse(0).ready:=io.inputResponse.ready
    fabric.io.flush:=io.flush
    io.drained:=fabric.io.drained
  }
  when(io.inputRequest.valid){assert(io.inputRequest.bits.transactionId===0.U && !io.inputRequest.bits.isWrite,
    "patch input must use read-only transaction zero")}
  when(fabric.io.stagingResponse(0).valid){assert(fabric.io.stagingResponse(0).bits.transactionId===0.U)}
  fabric.io.dmaRequest.valid:=false.B;fabric.io.dmaRequest.bits:=0.U.asTypeOf(fabric.io.dmaRequest.bits)
  fabric.io.dmaResponse.ready:=true.B
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse
  fabric.io.satp:=io.satp
  io.translationStall:=fabric.io.translationStall;io.translationMiss:=fabric.io.translationMiss;io.walkActive:=fabric.io.walkActive
}
