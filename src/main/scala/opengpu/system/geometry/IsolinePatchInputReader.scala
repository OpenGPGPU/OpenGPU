package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.KernelLaunch
import opengpu.graphics._

/** A checked, ordered snapshot of selected control outputs for one evaluation. */
class IsolinePatchInputSelection extends Bundle {
  val perPatch = Bool(); val tessLevel = Bool()
  val vertex = UInt(6.W); val component = UInt(7.W)
}

class IsolinePatchInputReader(config: GpuConfig, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new IsolineEvaluationRequest))
    val count = Input(UInt(6.W)); val selections = Input(Vec(61, new IsolinePatchInputSelection))
    val commandId = Input(UInt(idWidth.W)); val kernel = Input(new KernelLaunch(config)); val outputComponents = Input(UInt(7.W))
    val inspect = Decoupled(new TessellationPatchAccess); val reply = Flipped(Decoupled(new TessellationContextReply))
    val task = Decoupled(new IsolineGeometryCuTask(config, idWidth)); val result = Flipped(Decoupled(new IsolineEvaluationResponse))
    val response = Decoupled(new IsolineEvaluationResponse); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val idle :: inspect :: reply :: offer :: result :: failure :: Nil = Enum(6)
  private val state = RegInit(idle)
  private val task = Reg(new IsolineGeometryCuTask(config, idWidth))
  private val selections = Reg(Vec(61, new IsolinePatchInputSelection))
  private val index = Reg(UInt(6.W)); private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  io.busy := state =/= idle
  // An offered evaluator request must be consumed even after cancellation.
  io.request.ready := state === idle
  io.inspect.valid := state === inspect
  io.inspect.bits := 0.U.asTypeOf(io.inspect.bits)
  io.inspect.bits.output := true.B; io.inspect.bits.perPatch := selections(index).perPatch
  io.inspect.bits.tessLevel := selections(index).tessLevel
  io.inspect.bits.vertex := selections(index).vertex; io.inspect.bits.component := selections(index).component
  io.reply.ready := state === reply
  io.task.valid := state === offer; io.task.bits := task
  io.result.ready := state === result && io.response.ready
  io.response.valid := state === failure || (state === result && io.result.valid)
  io.response.bits := io.result.bits
  when(state === failure) {
    io.response.bits := 0.U.asTypeOf(io.response.bits)
    io.response.bits.taskId := task.evaluation.taskId; io.response.bits.sequence := task.evaluation.sequence
    io.response.bits.vertex := task.evaluation.vertex; io.response.bits.failed := true.B
  }
  when(io.request.fire) {
    task := 0.U.asTypeOf(task); task.evaluation := io.request.bits; task.commandId := io.commandId
    task.kernel := io.kernel; task.outputComponents := io.outputComponents; task.patchCount := io.count
    selections := io.selections; index := 0.U; cancelled := io.cancel
    state := Mux(io.cancel || io.count > 61.U, failure, Mux(io.count === 0.U, offer, inspect))
  }
  when(state =/= idle && io.cancel) { cancelled := true.B }
  // Keep an offered inspection stable until accepted, then drain its reply.
  when(io.inspect.fire) { state := reply }
  when(io.reply.fire) {
    when(stop || io.reply.bits.source =/= 2.U || io.reply.bits.result.status =/= 0.U) { state := failure }
      .otherwise {
        task.patchWords(index) := io.reply.bits.result.data
        when(index +& 1.U === task.patchCount) { state := offer }
          .otherwise { index := index + 1.U; state := inspect }
      }
  }
  when(io.task.fire) { state := result }
  when(io.response.fire) { state := idle }
}
