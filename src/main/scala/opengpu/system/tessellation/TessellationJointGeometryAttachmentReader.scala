package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

class TessellationJointGeometryAttachmentRequest(idWidth: Int) extends Bundle {
  val job = new TessellationJointGeometryJobCapture(idWidth)
  val directory = new TessellationJointDirectoryCapture(idWidth)

  /** From the actual captured GNT and fragment state, never a guessed layout. */
  val stateHandle = UInt(32.W); val tesOutputComponents = UInt(7.W)
  val inputTopology = UInt(4.W); val fragmentVaryingSlots = UInt(5.W)
}
class TessellationJointGeometryAttachment(p: VertexShaderStageConfig, idWidth: Int) extends Bundle {
  val sequenceIndex = UInt(32.W); val stateHandle = UInt(32.W); val domain = UInt(2.W)
  val identity = new GraphicsWorkIdentity; val commandId = UInt(idWidth.W)
  val gsTaskIdBase = UInt(32.W); val firstInputPrimitiveId = UInt(32.W)
  val inputSequence = UInt(64.W); val firstOutputSequence = UInt(64.W)
  val program = new NativeGeometryProgram; val evaluationComponents = Vec(64, new TessellationGeometryRawMap)
  val rasterDiscard = Bool()
}
class TessellationJointGeometryAttachmentCompletion(p: VertexShaderStageConfig, idWidth: Int) extends Bundle {

  /** 0 captured, 1 encoding/cross-link/bounds, 2 actual memory fault, 4 cancellation, 6 unavailable. No source or
    * shader was launched.
    */
  val commandId = UInt(idWidth.W); val status = UInt(3.W); val faultAddress = UInt(32.W)
  val readBytes = UInt(64.W); val captured = new TessellationJointGeometryAttachment(p, idWidth)
}

/** Actual immutable GGA1 header/entry and GNG1 reads, retaining one tag-zero offer/reply through cancellation. The
  * independent GNQ7..9 owner must hold real BUILD/MMU/resource leases through execution and physical retirement. This
  * component checks metadata; bounds alone confer no mapping authority.
  */
class TessellationJointGeometryAttachmentReader(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    idWidth: Int,
    enabled: Boolean = false
) extends Module {
  require(config.xLen == 32 && config.lanes == 16 && config.warps == 8 && idWidth > 0 && idWidth <= 12)
  require(p.input.attributes == 16 && p.input.bindings == 16 && p.varyings == 16)
  import TessellationJointJobLayout.{wide, maxVA, exact, overlaps, entryLegal, requestLegal}
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointGeometryAttachmentRequest(idWidth)))
    val completion = Decoupled(new TessellationJointGeometryAttachmentCompletion(p, idWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, offer, waitReply, checkHeader, checkEntry, checkProgram, checkMap, complete = Value
  }
  private object Record extends ChiselEnum { val header, entry, program = Value }
  private val state = RegInit(State.idle); private val record = RegInit(Record.header)
  private val q = Reg(new TessellationJointGeometryAttachmentRequest(idWidth))
  private val line = Reg(UInt(4.W)); private val component = Reg(UInt(6.W))
  private val h = Reg(Vec(16, UInt(32.W))); private val w = Reg(Vec(256, UInt(32.W)))
  private val program = Reg(new NativeGeometryProgram); private val malformed = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val claimed = RegInit(VecInit(Seq.fill(68)(false.B))); private val origin = Reg(Vec(68, UInt(6.W)))
  private val result = Reg(new TessellationJointGeometryAttachmentCompletion(p, idWidth))
  private val stop = cancelled || io.cancel
  private val job = q.job.job; private val request = q.job.request; private val entry = q.directory.entry
  private val entryBase = wide(job.geometry.base) + 64.U + wide(entry.sequenceIndex) * 1024.U
  private val gng = Wire(new TessellationJointView); gng.base := w(12); gng.limit := Cat(w(14)(0), w(13))
  private val address = Mux(
    record === Record.header,
    wide(job.geometry.base),
    Mux(record === Record.entry, entryBase, wide(gng.base))
  ) + (wide(line) << 6)
  private val attachment = Wire(new TessellationJointGeometryAttachment(p, idWidth))
  attachment := 0.U.asTypeOf(attachment)
  attachment.sequenceIndex := w(0); attachment.stateHandle := w(1); attachment.domain := w(2)
  attachment.identity.context := w(4); attachment.identity.pass := w(5); attachment.identity.generation := w(6)
  attachment.commandId := w(7); attachment.gsTaskIdBase := w(8); attachment.firstInputPrimitiveId := w(9)
  attachment.firstOutputSequence := Cat(w(11), w(10)); attachment.inputSequence := Cat(w(225), w(224))
  attachment.program := program; attachment.rasterDiscard := w(180)(0)
  for (i <- 0 until 64) {
    attachment.evaluationComponents(i).valid := w(16 + i)(7)
    attachment.evaluationComponents(i).component := w(16 + i)(6, 0)
  }
  private val headerOK = h(0) === TessellationJointGeometryJobLayout.attachmentMagic.U &&
    h(1) === job.geometryBytes && h(2) === 1.U && h(3) === 1024.U && h(4) === job.geometryCount &&
    h(5) === request.identity.context && h(6) === request.identity.pass && h(7) === request.identity.generation &&
    (8 until 16).map(h(_) === 0.U).reduce(_ && _)
  private val entryEncoding = !w(2)(31, 2).orR && w(3) === 0.U && !w(7)(31, idWidth).orR &&
    !w(14)(31, 1).orR && w(15) === 1024.U && !w(180)(31, 1).orR &&
    (16 until 80).map(i => !w(i)(31, 8).orR && (w(i)(7) || w(i) === 0.U)).reduce(_ && _) &&
    (80 until 180).map(w(_) === 0.U).reduce(_ && _) &&
    (181 until 224).map(w(_) === 0.U).reduce(_ && _) && (226 until 256).map(w(_) === 0.U).reduce(_ && _)
  private val gngOK = exact(gng, 1024.U(65.W), request.owner) &&
    !overlaps(gng, request.view) && !overlaps(gng, job.frame) && !overlaps(gng, job.sequence) &&
    !overlaps(gng, job.directory) && !overlaps(gng, job.geometry) && !overlaps(gng, entry.source)
  private val entryOK = entryEncoding && gngOK &&
    w(0) === entry.sequenceIndex && w(1) === q.stateHandle && w(2) === request.domain &&
    attachment.identity.asUInt === request.identity.asUInt
  private val programOK = !malformed && NativeGeometryProgramLayout.legal(program, p) &&
    program.identity.asUInt === request.identity.asUInt &&
    NativeGeometryProgramLayout.topologyMatches(program, q.inputTopology) &&
    program.fragmentVaryingSlots === q.fragmentVaryingSlots &&
    program.code.allocationBase >= request.owner.base && program.code.allocationLimit <= request.owner.limit
  private val raw = program.inputSources(component); private val mapping = attachment.evaluationComponents(component)
  private val active = component < program.inputComponents
  private val mapOK = Mux(
    active,
    mapping.valid && mapping.component < q.tesOutputComponents && mapping.component < 64.U,
    !mapping.valid && mapping.component === 0.U
  ) &&
    (!active || raw >= 68.U || !claimed(raw) || origin(raw) === mapping.component(5, 0))
  private val facts = io.submit.bits; private val j = facts.job; private val e = facts.directory.entry
  private val sequenceBase = wide(j.job.sequence.base) + wide(e.sequenceIndex) * wide(j.job.sequenceStride)
  private val independent = requestLegal(j.request) && TessellationJointGeometryJobLayout.legal(j.job, j.request) &&
    j.job.sequenceCount =/= 0.U && facts.directory.domain === j.request.domain &&
    facts.directory.commandId === j.request.commandId && facts.directory.identity.asUInt === j.request.identity.asUInt &&
    e.sequenceIndex < j.job.sequenceCount && entryLegal(e, j.job, j.request) &&
    wide(facts.directory.sequence.base) === sequenceBase && wide(
      facts.directory.sequence.limit
    ) === sequenceBase + 2048.U &&
    sequenceBase + 2048.U <= wide(
      j.job.sequence.limit
    ) && facts.tesOutputComponents >= 1.U && facts.tesOutputComponents <= 64.U &&
    facts.fragmentVaryingSlots <= 16.U
  io.submit.ready := state === State.idle && !io.cancel
  io.memoryRequest.valid := state === State.offer; io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := address(31, 0); io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.byteMask := Fill(64, 1.U(1.W)); io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryResponse.ready := state === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  io.completion.valid := state === State.complete; io.completion.bits := result
  io.busy := state =/= State.idle; io.drained := state =/= State.offer && state =/= State.waitReply
  private val fields = NativeGeometryProgramLayout.fields(program)
  NativeTypedGraphicsLayout.check(program, fields, 256)
  private val bad = NativeTypedGraphicsLayout.receive(
    fields,
    256,
    NativeGeometryProgramLayout.magic,
    line,
    io.memoryResponse.bits.readData,
    io.memoryResponse.fire && record === Record.program && !io.memoryResponse.bits.fault && !stop
  )
  private def reject(address: UInt): Unit = {
    result.status := 1.U; result.faultAddress := address; state := State.complete
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(io.submit.fire) {
    q := facts; result := 0.U.asTypeOf(result); result.commandId := facts.job.request.commandId
    h := 0.U.asTypeOf(h); w := 0.U.asTypeOf(w); program := 0.U.asTypeOf(program)
    line := 0.U; component := 0.U; record := Record.header; cancelled := false.B; malformed := false.B
    claimed.foreach(_ := false.B); origin.foreach(_ := 0.U)
    if (!enabled) { result.status := 6.U; state := State.complete }
    else { when(!independent) { reject(facts.job.request.view.base) }.otherwise { state := State.offer } }
  }
  when(io.memoryRequest.fire) { state := State.waitReply }
  when(io.memoryResponse.fire) {
    result.readBytes := result.readBytes + 64.U
    when(io.memoryResponse.bits.fault) {
      result.status := 2.U; result.faultAddress := address(31, 0); state := State.complete
    }
      .elsewhen(stop) { result.status := 4.U; state := State.complete }
      .otherwise {
        when(record === Record.header) {
          for (i <- 0 until 16) h(i) := io.memoryResponse.bits.readData(32 * i + 31, 32 * i)
          state := State.checkHeader
        }
          .otherwise {
            when(record === Record.entry) {
              for (i <- 0 until 16) w(Cat(line, i.U(4.W))) := io.memoryResponse.bits.readData(32 * i + 31, 32 * i)
            }
            when(record === Record.program && bad) { malformed := true.B }
            when(line === 15.U) { state := Mux(record === Record.entry, State.checkEntry, State.checkProgram) }
              .otherwise { line := line + 1.U; state := State.offer }
          }
      }
  }
  when(state === State.checkHeader) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(!headerOK) { reject(job.geometry.base) }
      .otherwise { record := Record.entry; line := 0.U; state := State.offer }
  }
  when(state === State.checkEntry) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(!entryOK) { reject(entryBase(31, 0)) }
      .otherwise { record := Record.program; line := 0.U; state := State.offer }
  }
  when(state === State.checkProgram) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(!programOK) { reject(gng.base) }
      .otherwise { component := 0.U; state := State.checkMap }
  }
  when(state === State.checkMap) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(!mapOK) { reject((entryBase + 64.U)(31, 0)) }
      .otherwise {
        when(active) { claimed(raw) := true.B; origin(raw) := mapping.component(5, 0) }
        when(component === 63.U) { result.captured := attachment; state := State.complete }
          .otherwise { component := component + 1.U }
      }
  }
  when(io.completion.fire) { state := State.idle }
}
