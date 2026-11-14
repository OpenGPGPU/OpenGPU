package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadPin,SharedShaderTaskRouter}

/** Reserve independent original-VS and control/evaluation task slots on the
  * same external CU pool. Client zero is the patch owner; client one is VS.
  * Local command tags, providers, texture traffic, traps, invalidations and
  * payload leases retain the existing SharedShaderTaskRouter lifetime.
  *
  * The pool has the patch client's exact optional interface. VS has none of
  * these capabilities: unused launch sidebands are explicitly cleared and
  * unexpected execute-side requests fail closed. This component neither
  * creates a CU nor admits a native program/resource view.
  */
class TessellationVertexShaderRouter(config:GpuConfig,numComputeUnits:Int,taskIdWidth:Int,
    shaderPayload:Option[ShaderPayloadConfig]=None,tessellationAvailable:Boolean=false,
    evaluationPatchAvailable:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val patch=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,
      tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable)
    val vertex=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits)
    val pool=Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,
      tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable))
    /** Patch then VS, sampled with each accepted launch. */
    val clientPayload=shaderPayload.map(p=>Input(Vec(2,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload=shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    /** Two active slots followed by the two prospective client offers. */
    val payloadPins=shaderPayload.map(p=>Output(Vec(4,Valid(new ShaderPayloadPin(p)))))
    val drained=Output(Bool())
  })
  private val router=Module(new SharedShaderTaskRouter(config,numComputeUnits,2,taskIdWidth,
    tasksPerClient=1,shaderPayload=shaderPayload,geometryAvailable=true,
    tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable))
  router.io.clients(0)<>io.patch
  io.pool<>router.io.pool

  private val vertex=router.io.clients(1)
  vertex.nativeResources:=io.vertex.nativeResources
  vertex.uniformResources:=io.vertex.uniformResources
  vertex.launch<>io.vertex.launch
  io.vertex.completion<>vertex.completion
  io.vertex.trap<>vertex.trap
  io.vertex.texture<>vertex.texture
  io.vertex.owned:=vertex.owned;io.vertex.taskIds:=vertex.taskIds
  vertex.invalidate<>io.vertex.invalidate
  io.vertex.invalidateDone<>vertex.invalidateDone

  vertex.geometryTask.get:=0.U.asTypeOf(vertex.geometryTask.get)
  if(tessellationAvailable)vertex.tessellationBases.get:=0.U.asTypeOf(vertex.tessellationBases.get)
  if(evaluationPatchAvailable)vertex.evaluationPatchBinding.get:=0.U.asTypeOf(vertex.evaluationPatchBinding.get)
  for(cu<-0 until numComputeUnits){
    vertex.geometryCancel.get(cu):=false.B
    vertex.geometryEvent.get(cu).ready:=false.B
    assert(!vertex.geometryEvent.get(cu).valid,"original VS cannot own geometry output")
    if(tessellationAvailable){
      val p=vertex.tessellation.get(cu)
      p.request.ready:=false.B;p.park.ready:=false.B;p.finish.ready:=false.B
      p.result.valid:=false.B;p.result.bits:=0.U.asTypeOf(p.result.bits)
      p.resume.valid:=false.B;p.resume.bits:=0.U.asTypeOf(p.resume.bits)
      p.cancel:=false.B;p.terminal:=false.B
      assert(!p.request.valid && !p.park.valid && !p.finish.valid,
        "original VS cannot own tessellation control traffic")
    }
    if(evaluationPatchAvailable){
      val p=vertex.evaluationPatch.get(cu)
      p.request.ready:=false.B;p.result.valid:=false.B;p.result.bits:=0.U.asTypeOf(p.result.bits)
      p.cancel:=false.B
      assert(!p.request.valid,"original VS cannot issue TES patch reads")
    }
  }
  shaderPayload.foreach{_=>
    router.io.clientPayload.get:=io.clientPayload.get
    io.poolPayload.get:=router.io.poolPayload.get
    io.payloadPins.get:=router.io.payloadPins.get
  }
  io.drained:=router.io.drained
}
