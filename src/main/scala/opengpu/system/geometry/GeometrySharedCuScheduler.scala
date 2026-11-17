package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

/** A serial geometry client of a shared CU pool. The caller reserves commandId
  * exclusively until completion and routes other clients' traffic separately.
  */
class GeometrySharedTask(config: GpuConfig, commandIdWidth: Int) extends Bundle {
  val geometry = new GeometryInvocationTask
  val commandId = UInt(commandIdWidth.W)
  val kernel = new KernelLaunch(config)
}

class GeometrySharedCuScheduler(config: GpuConfig, commandIdWidth: Int, numComputeUnits: Int) extends Module {
  require(numComputeUnits > 0)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new GeometrySharedTask(config, commandIdWidth)))
    val primitive = Decoupled(new GeometryOutputPrimitive)
    val completion = Decoupled(new GeometryInvocationResult)
    val cancel = Input(Bool())
    val launch = Decoupled(new TaggedKernelLaunch(config, commandIdWidth))
    val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event = Flipped(Vec(numComputeUnits, Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W))
    val taskIds = Input(Vec(numComputeUnits, UInt(commandIdWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits, Bool()))
  })
  val controller = Module(new GeometryInvocationController)
  val saved = Reg(new GeometrySharedTask(config, commandIdWidth))
  val worker = Reg(new GeometryInvocationLaunch)
  val live = RegInit(false.B)
  controller.io.task.valid := io.task.valid
  controller.io.task.bits := io.task.bits.geometry
  io.task.ready := controller.io.task.ready
  when(io.task.fire) { saved := io.task.bits }
  controller.io.cancel := io.cancel
  io.primitive <> controller.io.primitive
  io.completion <> controller.io.completion
  io.launch.valid := controller.io.launch.valid
  io.launch.bits.commandId := saved.commandId
  io.launch.bits.launch := saved.kernel
  // Each geometry invocation owns one scalar work-item.
  for (i <- 0 until 3) { io.launch.bits.launch.gridSize(i) := 1.U; io.launch.bits.launch.localSize(i) := 1.U }
  controller.io.launch.ready := io.launch.ready
  io.geometryTask.valid := controller.io.launch.valid
  io.geometryTask.bits := controller.io.launch.bits
  when(controller.io.launch.fire) { worker := controller.io.launch.bits; live := true.B }

  val selected = VecInit((0 until numComputeUnits).map(i => live && io.owner(i) && io.taskIds(i) === saved.commandId))
  assert(PopCount(selected) <= 1.U, "geometry command tag must identify at most one CU")
  controller.io.command.valid := (0 until numComputeUnits).map(i => selected(i) && io.event(i).valid).reduce(_ || _)
  controller.io.command.bits := Mux1H(selected, io.event.map(_.bits))
  for(i <- 0 until numComputeUnits) {
    io.event(i).ready := selected(i) && controller.io.command.ready
    io.cancelCu(i) := selected(i) && (io.cancel || controller.io.abort)
  }
  val completionMatches = live && io.kernelCompletion.bits.commandId === saved.commandId
  controller.io.workerCompletion.valid := io.kernelCompletion.valid && completionMatches
  controller.io.workerCompletion.bits.taskId := worker.taskId
  controller.io.workerCompletion.bits.primitiveId := worker.primitiveId
  controller.io.workerCompletion.bits.invocationId := worker.invocationId
  controller.io.workerCompletion.bits.success := io.kernelCompletion.bits.success
  io.kernelCompletion.ready := completionMatches && controller.io.workerCompletion.ready
  when(controller.io.workerCompletion.fire) { live := false.B }
}
