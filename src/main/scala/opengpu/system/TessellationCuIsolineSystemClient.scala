package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class TessellationCuIsolineSystemTask(config: GpuConfig, idWidth: Int) extends Bundle {
  val patch = new TessellationCuIsolineSharedTask(config, idWidth)
  val nativeResources = Bool(); val uniformResources = Bool()
}

/** Own one tagged control patch and its evaluation invocations on the shared
  * CU pool. The surrounding router reserves the command tag and forwards the
  * texture, trap and invalidation services through completion. */
class TessellationCuIsolineSystemClient(config: GpuConfig, idWidth: Int, numComputeUnits: Int) extends Module {
  require(numComputeUnits > 0)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationCuIsolineSystemTask(config, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationCuIsolineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val shared = Flipped(new SharedCuGraphicsPort(config, idWidth, numComputeUnits, geometryAvailable = true, tessellationAvailable = true))
    val texture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val trap = Decoupled(new TaggedGraphicsTrap(config, idWidth, numComputeUnits))
    val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config))); val invalidateDone = Decoupled(new CacheLineInvalidate(config))
  })
  private val client = Module(new TessellationCuIsolineSharedEvaluator(config, idWidth, numComputeUnits))
  private val tag = Reg(UInt(idWidth.W)); private val native = Reg(Bool()); private val uniform = Reg(Bool())
  client.io.task.valid := io.task.valid; client.io.task.bits := io.task.bits.patch; io.task.ready := client.io.task.ready
  when(io.task.fire) { tag := io.task.bits.patch.commandId; native := io.task.bits.nativeResources; uniform := io.task.bits.uniformResources }
  io.opened <> client.io.opened; client.io.load <> io.load; client.io.seal <> io.seal
  client.io.inspect <> io.inspect; io.reply <> client.io.reply; io.out <> client.io.out
  io.completion <> client.io.completion; client.io.cancel := io.cancel; io.busy := client.io.busy
  private val controlLive = RegInit(false.B)
  private val controlOffer = client.io.controlKernel.valid
  io.shared.launch.valid := Mux(controlOffer, client.io.controlKernel.valid, client.io.launch.valid)
  io.shared.launch.bits := client.io.launch.bits
  when(controlOffer) { io.shared.launch.bits := 0.U.asTypeOf(io.shared.launch.bits)
    io.shared.launch.bits.commandId := tag; io.shared.launch.bits.launch := client.io.controlKernel.bits }
  client.io.controlKernel.ready := io.shared.launch.ready && controlOffer
  client.io.launch.ready := io.shared.launch.ready && !controlOffer
  io.shared.tessellationBases.get.valid := controlOffer
  io.shared.tessellationBases.get.bits := client.io.bases
  io.shared.geometryTask.get := client.io.geometryTask
  when(controlOffer) { io.shared.geometryTask.get.valid := false.B }
  when(client.io.controlKernel.fire) { controlLive := true.B }
  private val controlCompletion = controlLive && io.shared.completion.bits.commandId === tag
  client.io.controlCompletion.valid := io.shared.completion.valid && controlCompletion
  client.io.controlCompletion.bits.success := io.shared.completion.bits.success
  client.io.kernelCompletion.valid := io.shared.completion.valid && !controlCompletion
  client.io.kernelCompletion.bits := io.shared.completion.bits
  io.shared.completion.ready := Mux(controlCompletion, client.io.controlCompletion.ready, client.io.kernelCompletion.ready)
  when(client.io.controlCompletion.fire) { controlLive := false.B }
  client.io.event <> io.shared.geometryEvent.get
  client.io.owner := io.shared.owned; client.io.taskIds := io.shared.taskIds
  io.shared.geometryCancel.get := client.io.cancelCu
  private val selected = VecInit((0 until numComputeUnits).map(i => controlLive && io.shared.owned(i) && io.shared.taskIds(i) === tag))
  assert(PopCount(selected) <= 1.U, "patch control tag must identify at most one CU")
  private val ports = io.shared.tessellation.get
  client.io.access.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).request.valid).reduce(_ || _)
  client.io.access.bits := Mux1H(selected, ports.map(_.request.bits))
  client.io.park.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).park.valid).reduce(_ || _)
  client.io.park.bits := Mux1H(selected, ports.map(_.park.bits))
  client.io.finish.valid := (0 until numComputeUnits).map(i => selected(i) && ports(i).finish.valid).reduce(_ || _)
  client.io.finish.bits := Mux1H(selected, ports.map(_.finish.bits))
  client.io.accessResult.ready := (0 until numComputeUnits).map(i => selected(i) && ports(i).result.ready).reduce(_ || _)
  client.io.resume.ready := (0 until numComputeUnits).map(i => selected(i) && ports(i).resume.ready).reduce(_ || _)
  for (i <- 0 until numComputeUnits) {
    ports(i).request.ready := selected(i) && client.io.access.ready
    ports(i).park.ready := selected(i) && client.io.park.ready
    ports(i).finish.ready := selected(i) && client.io.finish.ready
    ports(i).result.valid := selected(i) && client.io.accessResult.valid; ports(i).result.bits := client.io.accessResult.bits
    ports(i).resume.valid := selected(i) && client.io.resume.valid; ports(i).resume.bits := client.io.resume.bits
    ports(i).cancel := selected(i) && client.io.controlCancel; ports(i).terminal := selected(i) && client.io.controlTerminal
  }
  io.shared.nativeResources := native; io.shared.uniformResources := uniform
  io.texture <> io.shared.texture; io.trap <> io.shared.trap
  io.shared.invalidate <> io.invalidate; io.invalidateDone <> io.shared.invalidateDone
}
