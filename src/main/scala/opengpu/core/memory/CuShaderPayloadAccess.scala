package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig

/** Complete CU resident-access boundary. Contiguous vectors reach the same
  * banked lease directly; general vectors still coalesce with scalar/FPU line
  * accesses before checked local/VM splitting. Both clients drain before the
  * task can release or replace its payload binding.
  */
class CuShaderPayloadAccess(config: GpuConfig,p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val vector=Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val vectorResult=Decoupled(new VectorMemoryResponse(config))
    val vectorFallbackRequest=Decoupled(new VectorMemoryTransaction(config))
    val vectorFallbackResponse=Flipped(Decoupled(new VectorMemoryResponse(config)))
    val line=Flipped(Decoupled(new VectorCacheLineRequest(config)))
    val lineResult=Decoupled(new VectorCacheLineResponse)
    val memoryRequest=Decoupled(new VectorCacheLineRequest(config))
    val memoryResponse=Flipped(Decoupled(new VectorCacheLineResponse))
    val payload=new ShaderPayloadAccessPort(config,p)
  })
  private val vectors=Module(new VectorShaderPayloadRouter(config,p))
  private val lines=Module(new ShaderPayloadMemoryRouter(config,p))
  private val access=Module(new ShaderPayloadAccessArbiter(p))
  vectors.io.in<>io.vector;io.vectorResult<>vectors.io.out
  io.vectorFallbackRequest<>vectors.io.fallbackRequest;vectors.io.fallbackResponse<>io.vectorFallbackResponse
  lines.io.in<>io.line;io.lineResult<>lines.io.out
  io.memoryRequest<>lines.io.memoryRequest;lines.io.memoryResponse<>io.memoryResponse
  vectors.io.payload.task:=io.payload.task;lines.io.payload.task:=io.payload.task
  vectors.io.payload.cancel:=io.payload.cancel
  lines.io.payload.cancel:=io.payload.cancel || vectors.io.fallbackCanceled
  access.io.requests(0)<>lines.io.payload.request;lines.io.payload.response<>access.io.responses(0)
  access.io.requests(1)<>vectors.io.payload.request;vectors.io.payload.response<>access.io.responses(1)
  io.payload.request<>access.io.request;access.io.response<>io.payload.response
  io.payload.drained:=lines.io.payload.drained && vectors.io.payload.drained && access.io.drained
}
