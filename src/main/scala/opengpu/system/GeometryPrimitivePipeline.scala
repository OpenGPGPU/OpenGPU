package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class GeometryPrimitivePipelineTask(config: GpuConfig, p: VertexShaderStageConfig, commandIdWidth: Int) extends Bundle {
  val input = new GeometryPrimitiveInputRequest(p)
  val commandId = UInt(commandIdWidth.W)
  val kernel = new KernelLaunch(config)
  val firstSequence = UInt(64.W)
  val varyingSlots = UInt(5.W)
  val flatMask = UInt((4*p.varyings).W)
  val outputSources = Vec(4 + 4*p.varyings, Valid(UInt(7.W)))
}
class GeometryPrimitivePipelineResult extends Bundle {
  val taskId = UInt(32.W)
  val inputPrimitiveId = UInt(32.W)
  val status = UInt(2.W)
  val completedInvocations = UInt(6.W)
  val producedPrimitives = UInt(64.W)
  val nextSequence = UInt(64.W)
}

/** One input primitive owns the adapters and CU task until all results drain.
  * The command tag is reserved by the enclosing graphics router until completion.
  */
class GeometryPrimitivePipeline(config: GpuConfig, p: VertexShaderStageConfig, commandIdWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new GeometryPrimitivePipelineTask(config,p,commandIdWidth)))
    val primitive = Decoupled(new GeometryMappedPrimitive(p))
    val completion = Decoupled(new GeometryPrimitivePipelineResult)
    val cancel = Input(Bool())
    val launch = Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event = Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W))
    val taskIds = Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits,Bool()))
  })
  private val idle :: inputWait :: running :: report :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val saved = Reg(new GeometryPrimitivePipelineTask(config,p,commandIdWidth))
  private val result = Reg(new GeometryPrimitivePipelineResult)
  private val cancelled = RegInit(false.B)
  private val outputFault = RegInit(false.B)
  private val mapperBusy = RegInit(false.B)
  private val sequence = Reg(UInt(64.W))
  private val input = Module(new GeometryPrimitiveInput(p))
  private val scheduler = Module(new GeometrySharedCuScheduler(config,commandIdWidth,numComputeUnits))
  private val output = Module(new GeometryPrimitiveOutput(p))

  input.io.in.valid := state === idle && io.task.valid && !io.cancel
  input.io.in.bits := io.task.bits.input
  io.task.ready := state === idle && !io.cancel && input.io.in.ready
  when(io.task.fire) {
    saved := io.task.bits; cancelled := false.B; outputFault := false.B; mapperBusy := false.B
    sequence := io.task.bits.firstSequence; result := 0.U.asTypeOf(result)
    result.taskId := io.task.bits.input.taskId; result.inputPrimitiveId := io.task.bits.input.primitive.primitiveId
    result.nextSequence := io.task.bits.firstSequence; state := inputWait
  }
  when(state =/= idle && state =/= report && io.cancel) { cancelled := true.B }
  scheduler.io.task.valid := state === inputWait && input.io.task.valid && !cancelled && !io.cancel
  scheduler.io.task.bits.geometry := input.io.task.bits
  scheduler.io.task.bits.commandId := saved.commandId; scheduler.io.task.bits.kernel := saved.kernel
  input.io.task.ready := state === inputWait && (cancelled || io.cancel || scheduler.io.task.ready)
  input.io.fault.ready := state === inputWait
  when(state === inputWait && input.io.fault.fire) {
    result.status := Mux(cancelled || io.cancel, GeometryInvocationStatus.cancelled, GeometryInvocationStatus.invalid)
    state := report
  }
  when(state === inputWait && input.io.task.fire) {
    when(cancelled || io.cancel) { result.status := GeometryInvocationStatus.cancelled; state := report }
      .otherwise { state := running }
  }
  scheduler.io.cancel := cancelled || io.cancel || outputFault
  io.launch <> scheduler.io.launch; io.geometryTask := scheduler.io.geometryTask
  scheduler.io.kernelCompletion <> io.kernelCompletion; scheduler.io.event <> io.event
  scheduler.io.owner := io.owner; scheduler.io.taskIds := io.taskIds; io.cancelCu := scheduler.io.cancelCu

  output.io.in.valid := state === running && scheduler.io.primitive.valid
  scheduler.io.primitive.ready := state === running && output.io.in.ready
  output.io.in.bits := 0.U.asTypeOf(output.io.in.bits)
  output.io.in.bits.geometry := scheduler.io.primitive.bits
  output.io.in.bits.source := saved.input.primitive; output.io.in.bits.taskId := saved.input.taskId
  output.io.in.bits.sequence := sequence; output.io.in.bits.outputComponents := saved.input.outputComponents
  output.io.in.bits.builtinOutputMask := saved.input.builtinOutputMask
  output.io.in.bits.varyingSlots := saved.varyingSlots; output.io.in.bits.flatMask := saved.flatMask
  output.io.in.bits.componentSources := saved.outputSources
  io.primitive <> output.io.out
  output.io.fault.ready := state === running
  when(output.io.in.fire) {
    mapperBusy := true.B
    when(sequence.andR) { outputFault := true.B }.otherwise { sequence := sequence + 1.U }
  }
  when(io.primitive.fire) { mapperBusy := false.B; result.producedPrimitives := result.producedPrimitives + 1.U }
  when(output.io.fault.fire) { mapperBusy := false.B; outputFault := true.B }
  scheduler.io.completion.ready := state === running && !mapperBusy && !output.io.in.valid
  when(scheduler.io.completion.fire) {
    result.completedInvocations := scheduler.io.completion.bits.completedInvocations
    result.nextSequence := sequence
    result.status := Mux(outputFault, GeometryInvocationStatus.invalid,
      Mux(cancelled || io.cancel, GeometryInvocationStatus.cancelled, scheduler.io.completion.bits.status))
    state := report
  }
  io.completion.valid := state === report; io.completion.bits := result
  when(io.completion.fire) { state := idle }
}
