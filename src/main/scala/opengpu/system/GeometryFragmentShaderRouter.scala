package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{GeometryInvocationLaunch,ShaderPayloadConfig,ShaderPayloadPin,SharedShaderTaskRouter}

/** Share the CU pool between geometry and a fragment stage that has no
  * geometry sidebands. Task tags, texture traffic, traps and payload pins are
  * managed by the existing shared shader router.
  */
class GeometryFragmentShaderRouter(config: GpuConfig, numComputeUnits: Int, taskIdWidth: Int,
    shaderPayload: Option[ShaderPayloadConfig]=None) extends Module {
  val io = IO(new Bundle {
    val geometry = new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true)
    val fragment = new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits)
    val pool = Flipped(new SharedCuGraphicsPort(config,taskIdWidth,numComputeUnits,geometryAvailable=true))
    val clientPayload = shaderPayload.map(p=>Input(Vec(2,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload = shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    val payloadPins = shaderPayload.map(p=>Output(Vec(4,Valid(new ShaderPayloadPin(p)))))
    val drained = Output(Bool())
  })
  private val router = Module(new SharedShaderTaskRouter(config,numComputeUnits,2,taskIdWidth,
    shaderPayload=shaderPayload,geometryAvailable=true))
  router.io.clients(0) <> io.geometry
  io.pool <> router.io.pool
  val fragment = router.io.clients(1)
  fragment.nativeResources := io.fragment.nativeResources
  fragment.uniformResources := io.fragment.uniformResources
  fragment.launch <> io.fragment.launch
  io.fragment.completion <> fragment.completion
  io.fragment.trap <> fragment.trap
  io.fragment.texture <> fragment.texture
  io.fragment.owned := fragment.owned
  io.fragment.taskIds := fragment.taskIds
  fragment.invalidate <> io.fragment.invalidate
  io.fragment.invalidateDone <> fragment.invalidateDone
  fragment.geometryTask.get := 0.U.asTypeOf(Valid(new GeometryInvocationLaunch))
  for(cu<-0 until numComputeUnits) {
    fragment.geometryCancel.get(cu) := false.B
    fragment.geometryEvent.get(cu).ready := true.B
    assert(!fragment.geometryEvent.get(cu).valid,"fragment task cannot own geometry output")
  }
  shaderPayload.foreach { _ =>
    router.io.clientPayload.get := io.clientPayload.get
    io.poolPayload.get := router.io.poolPayload.get
    io.payloadPins.get := router.io.payloadPins.get
  }
  io.drained := router.io.drained
}
