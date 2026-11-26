package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadPin,SharedShaderTaskRouter}

/** Reserve independent original-VS and control/evaluation task slots on the
  * same external CU pool. Clients zero/one are patch/VS; optional client two
  * is scalar GS with no patch/evaluation/info authority or payload lease.
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
    evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false,nativeGeometryAvailable:Boolean=false) extends Module {
  // Preserve the complete previous JVM constructor with native GS disabled.
  def this(config:GpuConfig,numComputeUnits:Int,taskIdWidth:Int,shaderPayload:Option[ShaderPayloadConfig],tessellationAvailable:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean) =
    this(config,numComputeUnits,taskIdWidth,shaderPayload,tessellationAvailable,evaluationPatchAvailable,tessellationInfoAvailable,false)

  // Preserve the previous full JVM constructor with the language service disabled.
  def this(config:GpuConfig,numComputeUnits:Int,taskIdWidth:Int,shaderPayload:Option[ShaderPayloadConfig],tessellationAvailable:Boolean,evaluationPatchAvailable:Boolean) =
    this(config,numComputeUnits,taskIdWidth,shaderPayload,tessellationAvailable,evaluationPatchAvailable,false)

  private val clients=if(nativeGeometryAvailable)3 else 2
  val io=IO(new Bundle {
    val patch=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,
      tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable)
    val vertex=new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits)
    /** Scalar GS owns an independent slot without tessellation permissions. */
    val geometry=if(nativeGeometryAvailable)Some(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true))else None
    val pool=Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,
      tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    /** Patch then VS, sampled with each accepted launch. */
    val clientPayload=shaderPayload.map(p=>Input(Vec(2,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload=shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    /** Active slots then prospective offers; scalar GS pins are always invalid. */
    val payloadPins=shaderPayload.map(p=>Output(Vec(2*clients,Valid(new ShaderPayloadPin(p)))))
    val drained=Output(Bool())
  })
  private val router=Module(new SharedShaderTaskRouter(config,numComputeUnits,clients,taskIdWidth,
    tasksPerClient=1,shaderPayload=shaderPayload,geometryAvailable=true,
    tessellationAvailable=tessellationAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
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
    for(i<-0 until 2)router.io.clientPayload.get(i):=io.clientPayload.get(i)
    if(nativeGeometryAvailable)router.io.clientPayload.get(2):=0.U.asTypeOf(router.io.clientPayload.get(2))
    io.poolPayload.get:=router.io.poolPayload.get
    io.payloadPins.get:=router.io.payloadPins.get
    if(nativeGeometryAvailable)for(i<-Seq(2,clients+2)){
      assert(!router.io.payloadPins.get(i).valid,"scalar GS cannot acquire a payload lease")
      io.payloadPins.get(i):=0.U.asTypeOf(io.payloadPins.get(i))
    }
  }
  io.drained:=router.io.drained

  if(tessellationInfoAvailable){
    vertex.tessellationTask.get:=0.U.asTypeOf(vertex.tessellationTask.get)
    vertex.tessellationInfoCancel.get:=VecInit(Seq.fill(numComputeUnits)(false.B))
  }
  if(nativeGeometryAvailable){
    val internal=router.io.clients(2);val gs=io.geometry.get
    internal.nativeResources:=gs.nativeResources;internal.uniformResources:=gs.uniformResources
    internal.launch<>gs.launch;gs.completion<>internal.completion;gs.trap<>internal.trap
    gs.texture<>internal.texture;gs.owned:=internal.owned;gs.taskIds:=internal.taskIds
    internal.invalidate<>gs.invalidate;gs.invalidateDone<>internal.invalidateDone
    internal.geometryTask.get:=gs.geometryTask.get
    internal.geometryCancel.get:=gs.geometryCancel.get;gs.geometryEvent.get<>internal.geometryEvent.get
    if(tessellationAvailable)internal.tessellationBases.get:=0.U.asTypeOf(internal.tessellationBases.get)
    if(evaluationPatchAvailable)internal.evaluationPatchBinding.get:=0.U.asTypeOf(internal.evaluationPatchBinding.get)
    if(tessellationInfoAvailable){
      internal.tessellationTask.get:=0.U.asTypeOf(internal.tessellationTask.get)
      internal.tessellationInfoCancel.get:=VecInit(Seq.fill(numComputeUnits)(false.B))
    }
    for(cu<-0 until numComputeUnits){
      if(tessellationAvailable){
        val p=internal.tessellation.get(cu)
        p.request.ready:=false.B;p.park.ready:=false.B;p.finish.ready:=false.B
        p.result.valid:=false.B;p.result.bits:=0.U.asTypeOf(p.result.bits)
        p.resume.valid:=false.B;p.resume.bits:=0.U.asTypeOf(p.resume.bits)
        p.cancel:=false.B;p.terminal:=false.B
        assert(!p.request.valid && !p.park.valid && !p.finish.valid,
          "scalar GS cannot own tessellation control traffic")
      }
      if(evaluationPatchAvailable){
        val p=internal.evaluationPatch.get(cu)
        p.request.ready:=false.B;p.result.valid:=false.B;p.result.bits:=0.U.asTypeOf(p.result.bits);p.cancel:=false.B
        assert(!p.request.valid,"scalar GS cannot issue TES patch reads")
      }
    }
  }
}
