package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.graphics.{GraphicsWorkIdentity, NativeShaderTextureUnit, ShaderTextureResources}

/** Immutable compute texture publication held through task completion. */
class ComputeTextureTask extends Bundle {
  val enabled = Bool()
  val resources = new ShaderTextureResources
  val identity = new GraphicsWorkIdentity
}

/** Task-owned native texture execution with captured Sv32 translation.
  * The enclosing CU must reject legacy instructions, retain publication and VM
  * ownership until completion, route faults, and drain accepted traffic.
  */
class ComputeTextureCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val task = Input(new ComputeTextureTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val vectorIn = Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val vectorCommit = Decoupled(new VectorCommitRequest(config))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val enabled = Output(Bool())
    val busy = Output(Bool())
    val responseFault = Output(Bool())
    val faultAddress = Output(UInt(32.W))
  })
  private val task = RegInit(0.U.asTypeOf(new ComputeTextureTask))
  private val satp = RegInit(0.U(32.W))
  private val execute = Module(new NativeShaderTextureUnit(config, stage=2))
  private val translation = Module(new TranslatedLineClient(config, 2, preserveCachePolicy=true))
  when(io.kernelStart) {
    assert(!execute.io.busy && !translation.io.walkActive, "compute texture owner changed before drain")
    task := io.task; satp := io.satp
  }
  when(io.kernelCompletion) {
    assert(!execute.io.busy && !translation.io.walkActive, "compute completion escaped texture ownership")
  }
  io.enabled := task.enabled
  io.busy := execute.io.busy || translation.io.walkActive
  execute.io.in.valid := io.in.valid && !io.kernelStart && task.enabled
  execute.io.in.bits := io.in.bits
  io.in.ready := execute.io.in.ready && !io.kernelStart && task.enabled
  execute.io.vectorIn.valid := io.vectorIn.valid && !io.kernelStart && task.enabled
  execute.io.vectorIn.bits := io.vectorIn.bits
  io.vectorIn.ready := execute.io.vectorIn.ready && !io.kernelStart && task.enabled
  io.commit <> execute.io.commit; io.vectorCommit <> execute.io.vectorCommit
  execute.io.resources := task.resources; execute.io.identity := task.identity
  execute.io.texFormat := 0.U; execute.io.texBase := 0.U
  execute.io.texWidth := 0.U; execute.io.texHeight := 0.U; execute.io.wrapClamp := false.B
  execute.io.coordinatePolicy := 0.U.asTypeOf(execute.io.coordinatePolicy)
  execute.io.texMaxLevel := 0.U; execute.io.lodBias := 0.S; execute.io.minLevel := 0.U
  execute.io.clearParameters := io.kernelStart; execute.io.cancel := io.cancel
  execute.io.invalidate := io.kernelStart || io.flush.valid
  io.responseFault := execute.io.responseFault; io.faultAddress := execute.io.faultAddress
  translation.io.in <> execute.io.memoryRequest
  execute.io.memoryResponse <> translation.io.out
  translation.io.satp := satp
  translation.io.flush.valid := io.kernelStart || io.flush.valid
  translation.io.flush.bits := Mux(io.kernelStart, 0.U.asTypeOf(io.flush.bits), io.flush.bits)
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 4), 2))
  private val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 4), 2, pipe=true))
  arbiter.io.in(0).valid := translation.io.memReq.valid
  arbiter.io.in(0).bits := translation.io.memReq.bits
  translation.io.memReq.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := translation.io.pageWalk.valid
  arbiter.io.in(1).bits := translation.io.pageWalk.bits
  arbiter.io.in(1).bits.transactionId := 2.U
  translation.io.pageWalk.ready := arbiter.io.in(1).ready
  offers.io.enq <> arbiter.io.out; io.memoryRequest <> offers.io.deq
  private val id = io.memoryResponse.bits.transactionId
  private val dataReply = id < 2.U
  private val walkReply = id === 2.U
  translation.io.memResp.valid := io.memoryResponse.valid && dataReply
  translation.io.memResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.valid := io.memoryResponse.valid && walkReply
  translation.io.pageWalkResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.bits.transactionId := 0.U
  io.memoryResponse.ready := (dataReply && translation.io.memResp.ready) ||
    (walkReply && translation.io.pageWalkResp.ready)
}
