package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{GeometryInvocationLaunch,ShaderPayloadConfig,ShaderPayloadPin,SharedShaderTaskRouter}

/** Connect a patch/geometry producer which has no image publication to an
  * image-capable router. Explicitly clear the publication on every task;
  * fragment state must never bleed into control/evaluation shader launches.
  */
object GeometryFragmentShaderRouter {
  def connectWithoutImages(destination:SharedCuGraphicsPort,source:SharedCuGraphicsPort):Unit={
    require(destination.imageTask.nonEmpty && source.imageTask.isEmpty)
    require(destination.elements.keySet - "imageTask" == source.elements.keySet)
    source.elements.foreach{case(name,value)=>destination.elements(name)<>value}
    destination.imageTask.get:=0.U.asTypeOf(destination.imageTask.get)
  }
}

/** Share the CU pool between geometry and a fragment stage that has no
  * geometry sidebands. Task tags, texture traffic, traps and payload pins are
  * managed by the existing shared shader router.
  */
class GeometryFragmentShaderRouter(config: GpuConfig, numComputeUnits: Int, taskIdWidth: Int,
    shaderPayload: Option[ShaderPayloadConfig]=None, tessellationAvailable: Boolean=false,
    imageAvailable: Boolean=false) extends Module {
  val io = IO(new Bundle {
    val geometry = new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,tessellationAvailable=tessellationAvailable,imageAvailable=imageAvailable)
    val fragment = new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,imageAvailable=imageAvailable)
    val pool = Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true,tessellationAvailable=tessellationAvailable,imageAvailable=imageAvailable))
    val clientPayload = shaderPayload.map(p=>Input(Vec(2,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload = shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    val payloadPins = shaderPayload.map(p=>Output(Vec(4,Valid(new ShaderPayloadPin(p)))))
    val drained = Output(Bool())
  })
  private val router = Module(new SharedShaderTaskRouter(config,numComputeUnits,2,taskIdWidth,
    shaderPayload=shaderPayload,geometryAvailable=true,tessellationAvailable=tessellationAvailable,imageAvailable=imageAvailable))
  router.io.clients(0) <> io.geometry
  io.pool <> router.io.pool
  val fragment = router.io.clients(1)
  fragment.nativeResources := io.fragment.nativeResources
  fragment.uniformResources := io.fragment.uniformResources
  if(imageAvailable) fragment.imageTask.get := io.fragment.imageTask.get
  fragment.launch <> io.fragment.launch
  io.fragment.completion <> fragment.completion
  io.fragment.trap <> fragment.trap
  io.fragment.texture <> fragment.texture
  io.fragment.owned := fragment.owned
  io.fragment.taskIds := fragment.taskIds
  fragment.invalidate <> io.fragment.invalidate
  io.fragment.invalidateDone <> fragment.invalidateDone
  fragment.geometryTask.get := 0.U.asTypeOf(Valid(new GeometryInvocationLaunch))
  if(tessellationAvailable) fragment.tessellationBases.get := 0.U.asTypeOf(fragment.tessellationBases.get)
  for(cu<-0 until numComputeUnits) {
    fragment.geometryCancel.get(cu) := false.B
    fragment.geometryEvent.get(cu).ready := true.B
    assert(!fragment.geometryEvent.get(cu).valid,"fragment task cannot own geometry output")
    if(tessellationAvailable) {
      val p=fragment.tessellation.get(cu)
      p.request.ready:=true.B; p.park.ready:=true.B; p.finish.ready:=true.B
      p.result.valid:=false.B; p.result.bits:=0.U.asTypeOf(p.result.bits)
      p.resume.valid:=false.B; p.resume.bits:=0.U.asTypeOf(p.resume.bits)
      p.cancel:=false.B; p.terminal:=false.B
      assert(!p.request.valid && !p.park.valid && !p.finish.valid,"fragment task cannot own tessellation control traffic")
    }
  }
  shaderPayload.foreach { _ =>
    router.io.clientPayload.get := io.clientPayload.get
    io.poolPayload.get := router.io.poolPayload.get
    io.payloadPins.get := router.io.payloadPins.get
  }
  io.drained := router.io.drained
}
