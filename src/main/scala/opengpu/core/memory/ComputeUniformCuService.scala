package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.graphics.{GraphicsWorkIdentity, ShaderUniformExecuteUnit, ShaderUniformResources}

/** Immutable compute uniform publication, retained through task completion. */
class ComputeUniformTask extends Bundle {
  val enabled = Bool()
  val resources = new ShaderUniformResources
  val identity = new GraphicsWorkIdentity
  val privateBase = UInt(32.W)
  val privateLimit = UInt(33.W)
}

/** Captures the compute task and translation root before executing checked
  * uniform instructions. The parent retains publication and VM ownership until
  * all offered/accepted memory and the public completion have drained.
  */
class ComputeUniformCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val task = Input(new ComputeUniformTask)
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
    val status = Output(UInt(3.W))
    val faultSource = Output(UInt(2.W))
    val faultAddressValid = Output(Bool())
    val faultAddress = Output(UInt(32.W))
  })
  private val task = RegInit(0.U.asTypeOf(new ComputeUniformTask))
  private val satp = RegInit(0.U(32.W))
  private val execute = Module(new ShaderUniformExecuteUnit(config, 2))
  private val translation = Module(new TranslatedLineClient(config, 1, preserveCachePolicy = true))
  when(io.kernelStart) {
    assert(!execute.io.busy && !translation.io.walkActive,
      "compute uniform owner changed before prior task drained")
    task := io.task; satp := io.satp
  }
  when(io.kernelCompletion) {
    assert(!execute.io.busy && !translation.io.walkActive,
      "compute completion escaped uniform ownership")
  }
  io.enabled := task.enabled
  io.busy := execute.io.busy || translation.io.walkActive
  execute.io.in.valid := io.in.valid && !io.kernelStart
  execute.io.in.bits := io.in.bits
  io.in.ready := execute.io.in.ready && !io.kernelStart
  execute.io.vectorIn.valid := io.vectorIn.valid && !io.kernelStart
  execute.io.vectorIn.bits := io.vectorIn.bits
  io.vectorIn.ready := execute.io.vectorIn.ready && !io.kernelStart
  io.commit <> execute.io.commit; io.vectorCommit <> execute.io.vectorCommit
  execute.io.resources := task.resources
  when(!task.enabled) { execute.io.resources.version := 0.U; execute.io.resources.address := 0.U }
  execute.io.identity := task.identity
  execute.io.privateBase := task.privateBase; execute.io.privateLimit := task.privateLimit
  execute.io.cancel := io.cancel
  io.status := execute.io.status; io.faultSource := execute.io.faultSource
  io.faultAddressValid := execute.io.faultAddressValid; io.faultAddress := execute.io.faultAddress
  translation.io.in <> execute.io.memoryRequest
  execute.io.memoryResponse <> translation.io.out
  translation.io.satp := satp
  translation.io.flush.valid := io.kernelStart || io.flush.valid
  translation.io.flush.bits := Mux(io.kernelStart, 0.U.asTypeOf(io.flush.bits), io.flush.bits)
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 4), 2))
  private val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 4), 2, pipe = true))
  arbiter.io.in(0).valid := translation.io.memReq.valid
  arbiter.io.in(0).bits := translation.io.memReq.bits
  translation.io.memReq.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := translation.io.pageWalk.valid
  arbiter.io.in(1).bits := translation.io.pageWalk.bits
  arbiter.io.in(1).bits.transactionId := 1.U
  translation.io.pageWalk.ready := arbiter.io.in(1).ready
  offers.io.enq <> arbiter.io.out
  io.memoryRequest <> offers.io.deq
  private val dataReply = io.memoryResponse.bits.transactionId === 0.U
  private val walkReply = io.memoryResponse.bits.transactionId === 1.U
  translation.io.memResp.valid := io.memoryResponse.valid && dataReply
  translation.io.memResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.valid := io.memoryResponse.valid && walkReply
  translation.io.pageWalkResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.bits.transactionId := 0.U
  io.memoryResponse.ready := (dataReply && translation.io.memResp.ready) ||
    (walkReply && translation.io.pageWalkResp.ready)
}
