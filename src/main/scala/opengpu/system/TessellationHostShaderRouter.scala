package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.graphics.{ShaderImageTask, ShaderPayloadConfig, ShaderPayloadPin, SharedShaderTaskRouter}

/** Adapts the existing native renderer and the tessellation job owner to the
  * same tagged CU pool. The legacy renderer cannot issue patch or geometry
  * operations; the tessellation owner's sidebands remain task-owned.
  */
class TessellationHostShaderRouter(config: GpuConfig, numComputeUnits: Int, taskIdWidth: Int,
    tasksPerClient: Int, payload: Option[ShaderPayloadConfig] = None,
    imageAvailable: Boolean = false) extends Module {
  require(numComputeUnits > 0 && taskIdWidth > 0 && tasksPerClient > 0)
  private val pins = 2 * tasksPerClient + 2
  val io = IO(new Bundle {
    val legacy = new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits, imageAvailable=imageAvailable)
    val tessellation = new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits, geometryAvailable=true, tessellationAvailable=true)
    val pool = Flipped(new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits,
      geometryAvailable=true, tessellationAvailable=true, imageAvailable=imageAvailable))
    val clientPayload = payload.map(p => Input(Vec(2, Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload = payload.map(p => Output(Valid(new ShaderPayloadBinding(config,p))))
    val payloadPins = payload.map(p => Output(Vec(pins, Valid(new ShaderPayloadPin(p)))))
    val drained = Output(Bool())
  })
  private val router = Module(new SharedShaderTaskRouter(config, numComputeUnits, 2, taskIdWidth,
    tasksPerClient, payload, geometryAvailable=true, tessellationAvailable=true, imageAvailable=imageAvailable))
  private val legacy = router.io.clients(0)
  legacy.launch <> io.legacy.launch
  io.legacy.completion <> legacy.completion
  io.legacy.trap <> legacy.trap
  io.legacy.texture <> legacy.texture
  io.legacy.owned := legacy.owned; io.legacy.taskIds := legacy.taskIds
  legacy.invalidate <> io.legacy.invalidate; io.legacy.invalidateDone <> legacy.invalidateDone
  legacy.nativeResources := io.legacy.nativeResources; legacy.uniformResources := io.legacy.uniformResources
  if (imageAvailable) legacy.imageTask.get := io.legacy.imageTask.get
  legacy.geometryTask.get.valid := false.B; legacy.geometryTask.get.bits := 0.U.asTypeOf(legacy.geometryTask.get.bits)
  legacy.tessellationBases.get.valid := false.B; legacy.tessellationBases.get.bits := 0.U.asTypeOf(legacy.tessellationBases.get.bits)
  for (cu <- 0 until numComputeUnits) {
    legacy.geometryEvent.get(cu).ready := false.B; legacy.geometryCancel.get(cu) := false.B
    val patch = legacy.tessellation.get(cu)
    patch.request.ready := false.B; patch.park.ready := false.B; patch.finish.ready := false.B
    patch.result.valid := false.B; patch.result.bits := 0.U.asTypeOf(patch.result.bits)
    patch.resume.valid := false.B; patch.resume.bits := 0.U.asTypeOf(patch.resume.bits)
    patch.cancel := false.B; patch.terminal := false.B
    when(legacy.geometryEvent.get(cu).valid || patch.request.valid || patch.park.valid || patch.finish.valid) {
      assert(false.B, "legacy graphics task emitted tessellation or geometry traffic")
    }
  }
  private val tessellation = router.io.clients(1)
  tessellation.launch <> io.tessellation.launch
  io.tessellation.completion <> tessellation.completion
  io.tessellation.trap <> tessellation.trap
  io.tessellation.texture <> tessellation.texture
  io.tessellation.owned := tessellation.owned; io.tessellation.taskIds := tessellation.taskIds
  tessellation.invalidate <> io.tessellation.invalidate; io.tessellation.invalidateDone <> tessellation.invalidateDone
  tessellation.nativeResources := io.tessellation.nativeResources
  tessellation.uniformResources := io.tessellation.uniformResources
  tessellation.geometryTask.get := io.tessellation.geometryTask.get
  io.tessellation.geometryEvent.get <> tessellation.geometryEvent.get
  tessellation.geometryCancel.get := io.tessellation.geometryCancel.get
  tessellation.tessellationBases.get := io.tessellation.tessellationBases.get
  io.tessellation.tessellation.get <> tessellation.tessellation.get
  // Tessellation jobs do not yet admit image bindings. Never inherit the
  // previous legacy fragment task's image publication.
  if (imageAvailable) tessellation.imageTask.get := 0.U.asTypeOf(new ShaderImageTask)
  io.pool <> router.io.pool
  if (payload.nonEmpty) {
    router.io.clientPayload.get := io.clientPayload.get
    io.poolPayload.get := router.io.poolPayload.get
    io.payloadPins.get := router.io.payloadPins.get
  }
  io.drained := router.io.drained
}
