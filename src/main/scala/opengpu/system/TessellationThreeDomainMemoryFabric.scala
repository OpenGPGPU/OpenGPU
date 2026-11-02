package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

object TessellationThreeDomainMemoryFabric {
  def transactions(isolineIds:Int,triangleIds:Int,quadIds:Int):Int =
    TessellationHostMemoryFabric.transactions(TessellationHostMemoryFabric.transactions(isolineIds,triangleIds),quadIds)
}

/** Shares a physical memory port among the three serialized tessellation owners.
  * Each fabric stage keeps its accepted transaction IDs until the originating
  * client consumes its response, including when later requests are stalled.
  */
class TessellationThreeDomainMemoryFabric(config:GpuConfig,isolineIds:Int,triangleIds:Int,quadIds:Int) extends Module {
  private val pairIds=TessellationHostMemoryFabric.transactions(isolineIds,triangleIds)
  private val slots=TessellationThreeDomainMemoryFabric.transactions(isolineIds,triangleIds,quadIds)
  val io=IO(new Bundle {
    val isolineRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,isolineIds)))
    val isolineResponse=Decoupled(new ComputeMemoryResponse(64,isolineIds))
    val triangleRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,triangleIds)))
    val triangleResponse=Decoupled(new ComputeMemoryResponse(64,triangleIds))
    val quadRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,quadIds)))
    val quadResponse=Decoupled(new ComputeMemoryResponse(64,quadIds))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val drained=Output(Bool())
  })
  private val pair=Module(new TessellationHostMemoryFabric(config,isolineIds,triangleIds))
  private val outer=Module(new TessellationHostMemoryFabric(config,pairIds,quadIds))
  pair.io.hostRequest<>io.isolineRequest;io.isolineResponse<>pair.io.hostResponse
  pair.io.tessellationRequest<>io.triangleRequest;io.triangleResponse<>pair.io.tessellationResponse
  outer.io.hostRequest<>pair.io.memoryRequest;pair.io.memoryResponse<>outer.io.hostResponse
  outer.io.tessellationRequest<>io.quadRequest;io.quadResponse<>outer.io.tessellationResponse
  io.memoryRequest<>outer.io.memoryRequest;outer.io.memoryResponse<>io.memoryResponse
  io.drained:=pair.io.drained && outer.io.drained
}
