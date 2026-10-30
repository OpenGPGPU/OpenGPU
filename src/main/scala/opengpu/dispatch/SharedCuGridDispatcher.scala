package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{ShaderPayloadConfig, ShaderPayloadPin}
import opengpu.core.memory.ShaderPayloadBinding

/** Distribute compute workgroups while preserving the existing physical
  * graphics/compute arbiter, completion reservations and graphics payload pins.
  */
class SharedCuGridDispatcher(
  config: GpuConfig = GpuConfig(), numComputeUnits: Int = 4,
  commandIdWidth: Int = 8, shaderPayload: Option[ShaderPayloadConfig] = None, imageAvailable: Boolean = false, storageAvailable: Boolean = false, computeUniformAvailable: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth, imageAvailable, storageAvailable, computeUniformAvailable)))
    val completion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val graphicsLaunch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth, imageAvailable, storageAvailable, computeUniformAvailable)))
    val graphicsCompletion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val cuLaunch = Vec(numComputeUnits, Decoupled(new KernelLaunch(config)))
    val cuImages = if(imageAvailable) Some(Output(Vec(numComputeUnits, new opengpu.graphics.ShaderImageTask))) else None
    val cuStorage = if(storageAvailable) Some(Output(Vec(numComputeUnits, new opengpu.core.memory.StorageTask))) else None
    val cuComputeUniform = if(computeUniformAvailable) Some(Output(Vec(numComputeUnits, new opengpu.core.memory.ComputeUniformTask))) else None
    val cuGridSlice = Output(Vec(numComputeUnits, new KernelGridSlice))
    val cuCompletion = Vec(numComputeUnits, Flipped(Decoupled(new KernelCompletion)))
    val busy = Output(UInt(numComputeUnits.W))
    val graphicsOwner = Output(UInt(numComputeUnits.W))
    val taskIds = Output(Vec(numComputeUnits, UInt(commandIdWidth.W)))
    val graphicsPayload = shaderPayload.map(p => Input(Valid(new ShaderPayloadBinding(config, p))))
    val cuPayload = shaderPayload.map(p => Output(Vec(numComputeUnits, Valid(new ShaderPayloadBinding(config, p)))))
    val cuPayloadDrained = shaderPayload.map(_ => Input(Vec(numComputeUnits, Bool())))
    val payloadPins = shaderPayload.map(p => Output(Vec(numComputeUnits + 1, Valid(new ShaderPayloadPin(p)))))
  })
  private val grids = Module(new ComputeGridDistributor(config, numComputeUnits, commandIdWidth, imageAvailable, storageAvailable, computeUniformAvailable))
  private val shared = Module(new SharedCuKernelDispatcher(config, numComputeUnits, commandIdWidth, shaderPayload, imageAvailable, storageAvailable, computeUniformAvailable))
  grids.io.launch <> io.launch
  io.completion <> grids.io.completion
  shared.io.launch <> grids.io.childLaunch
  grids.io.childCompletion <> shared.io.completion
  shared.io.graphicsLaunch <> io.graphicsLaunch
  io.graphicsCompletion <> shared.io.graphicsCompletion
  io.cuLaunch <> shared.io.cuLaunch
  if(imageAvailable) { io.cuImages.get := shared.io.cuImages.get }
  if(storageAvailable) { io.cuStorage.get := shared.io.cuStorage.get }
  if(computeUniformAvailable) { io.cuComputeUniform.get := shared.io.cuComputeUniform.get }
  shared.io.cuCompletion <> io.cuCompletion
  io.busy := shared.io.busy
  io.graphicsOwner := shared.io.graphicsOwner
  for (cu <- 0 until numComputeUnits) {
    // The existing arbiter accepts the selected producer and physical CU on
    // the same edge. Sideband is captured only on that CU's kernel.fire.
    io.cuGridSlice(cu) := grids.io.childGroup
    io.cuGridSlice(cu).singleGroup := shared.io.launch.fire && shared.io.cuLaunch(cu).fire
    val tag = shared.io.taskIds(cu)
    io.taskIds(cu) := Mux(shared.io.graphicsOwner(cu), tag, grids.io.childParentIds(tag))
    when(shared.io.busy(cu) && !shared.io.graphicsOwner(cu)) {
      assert(tag < grids.slots.U, "physical compute tag must identify a child slot")
    }
  }
  shaderPayload.foreach { _ =>
    shared.io.graphicsPayload.get := io.graphicsPayload.get
    io.cuPayload.get := shared.io.cuPayload.get
    shared.io.cuPayloadDrained.get := io.cuPayloadDrained.get
    io.payloadPins.get := shared.io.payloadPins.get
  }
}
