package opengpu.graphics.tessellation

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.graphics.GeometryScalarOpcode

/** Internal original-input-patch identity; independent of generated TES output order. The producer captures this
  * binding with the accepted original-VS tuple.
  */
class TessellationInputPatchBinding extends Bundle {
  val version = UInt(8.W)
  val taskId = UInt(32.W)
  val inputPatchPrimitiveId = UInt(32.W)
}
object TessellationInputPatchBinding {
  def legal(binding: ValidIO[TessellationInputPatchBinding], taskId: UInt): Bool =
    binding.valid && binding.bits.version === 1.U && binding.bits.taskId === taskId

  /** Safe full-width division: even rejected inputs never divide by zero. Ordinals are within an original draw
    * instance; indexed values/baseInstance and generated primitive sequence have no role in this language identity.
    */
  def aligned(firstOrdinal: UInt, inputVertices: UInt): Bool = {
    val valid = inputVertices >= 1.U && inputVertices <= 32.U
    val divisor = Mux(valid, inputVertices, 1.U)
    valid && firstOrdinal % divisor === 0.U && firstOrdinal.pad(33) +& inputVertices.pad(33) <= (BigInt(1) << 32).U
  }
  def primitiveId(firstOrdinal: UInt, inputVertices: UInt): UInt =
    firstOrdinal / Mux(inputVertices >= 1.U && inputVertices <= 32.U, inputVertices, 1.U)
}

/** Internal language-input binding, independent of geometry output metadata. taskId is an ownership crosslink, never
  * the shader-visible primitive ID. Version 1 binds one original input patch, shared by every launched warp.
  */
class TessellationShaderTaskBinding extends Bundle {
  val version = UInt(8.W)
  val stage = UInt(3.W)
  val taskId = UInt(32.W)
  val inputPatchPrimitiveId = UInt(32.W)
}

/** Read-only CUSTOM0 fn37 selector6. This grants no geometry operations. Starts are actual kernel/warp acceptance
  * events. A held commit is immutable through cancellation; its owner cannot release or replace the binding until that
  * commit has transferred. A rejected instruction uses the scalar backend's failed-resource-commit trap path
  * (writeRd=false).
  */
class TessellationScalarInfoWorker(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32 && config.warps > 0 && isPow2(config.warps))
  val io = IO(new Bundle {
    val start = Flipped(Valid(new TessellationShaderTaskBinding))
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val end = Input(Bool())
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val enabled = Output(Bool())
    val active = Output(Bool())
    val stage = Output(UInt(3.W))
    val busy = Output(Bool())
  })
  private val binding = Reg(new TessellationShaderTaskBinding)
  private val active = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val started = RegInit(0.U(config.warps.W))
  private val pending = RegInit(false.B)
  private val result = Reg(new ScalarCommitRequest(config))
  private val legalBinding = binding.version === 1.U && (binding.stage === 4.U || binding.stage === 5.U)
  io.active := active
  io.enabled := active && legalBinding
  io.stage := binding.stage
  io.busy := pending
  io.in.ready := active && !pending && !io.end
  io.commit.valid := pending
  io.commit.bits := result
  when(io.start.valid) {
    assert(!io.end, "kernel start and retirement must not overlap")
    assert(!active && !pending, "tessellation language binding replaced before retirement")
    binding := io.start.bits
    active := true.B
    cancelled := io.cancel
    started := 0.U
  }
  when(active && io.warpStart.valid) { started := started | UIntToOH(io.warpStart.bits, config.warps) }
  when(active && io.cancel) { cancelled := true.B }
  when(io.end) {
    assert(!pending, "tessellation language commit escaped kernel retirement")
    active := false.B
    cancelled := false.B
    started := 0.U
  }
  when(io.in.fire) {
    val d = io.in.bits.decode
    val i = d.instruction
    val valid = legalBinding && !cancelled && !io.cancel && started(d.warpId) &&
      GeometryScalarOpcode.info(i) && i(24, 20) === 0.U && io.in.bits.rs1Data === 6.U
    result := 0.U.asTypeOf(result)
    result.warpId := d.warpId
    result.nextPc := d.pc + 4.U
    result.activeMask := d.activeMask
    result.rd := i(11, 7)
    result.writeRd := valid
    result.data := Mux(valid, binding.inputPatchPrimitiveId, 0.U)
    pending := true.B
  }
  when(io.commit.fire) { pending := false.B }
}

/** The actual CU scalar-service boundary. Old geometry selectors retain their meaning only with a real geometry owner.
  * A TES language binding alone must never send those selectors to an unserviced external texture port.
  */
class TessellationScalarInfoMux(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val bindingActive = Input(Bool())
    val stage = Input(UInt(3.W))
    val geometryOwned = Input(Bool())
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val info = Decoupled(new ScalarIssuedInstruction(config))
    val legacy = Decoupled(new ScalarIssuedInstruction(config))
    val infoCommit = Flipped(Decoupled(new ScalarCommitRequest(config)))
    val legacyCommit = Flipped(Decoupled(new ScalarCommitRequest(config)))
    val out = Decoupled(new ScalarCommitRequest(config))
    val legacyCommitSelected = Output(Bool())
  })
  private val selected = io.bindingActive && GeometryScalarOpcode.info(io.in.bits.decode.instruction) &&
    (io.stage === 4.U || io.in.bits.rs1Data >= 6.U || !io.geometryOwned)
  io.info.valid := io.in.valid && selected
  io.info.bits := io.in.bits
  io.legacy.valid := io.in.valid && !selected
  io.legacy.bits := io.in.bits
  io.in.ready := Mux(selected, io.info.ready, io.legacy.ready)
  private val commits = Module(new RRArbiter(new ScalarCommitRequest(config), 2))
  commits.io.in(0) <> io.legacyCommit
  commits.io.in(1) <> io.infoCommit
  io.out <> commits.io.out
  io.legacyCommitSelected := commits.io.chosen === 0.U
}
