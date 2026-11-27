package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

/** Capture one exact new-domain record, without launching a patch or reading GVS/GND. The outer owner retains all
  * inputs through complete retirement.
  */
class TessellationVertexSequenceCaptureReader[D <: Data](config: GpuConfig, idWidth: Int, domain: Int, sequenceType: D)
    extends Module {
  require(config.xLen == 32 && config.lanes == 16 && config.warps == 8 && idWidth > 0 && idWidth <= 12)
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationVertexSequenceRequest(idWidth)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val completion = Decoupled(new TessellationVertexSequenceCompletion(sequenceType, idWidth))
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offer, waitReply, check, complete = Value }
  private val state = RegInit(State.idle); private val request = Reg(new TessellationVertexSequenceRequest(idWidth))
  private val record = Reg(new TessellationVertexSequenceRecord(sequenceType))
  private val result = Reg(new TessellationVertexSequenceCompletion(sequenceType, idWidth))
  private val line = Reg(UInt(5.W)); private val cancelled = RegInit(false.B); private val malformed = RegInit(false.B)
  private val mapped = TessellationVertexSequenceLayout.fields(record, domain)
  NativeTypedGraphicsLayout.check(record, mapped, TessellationVertexSequenceLayout.words)
  private val write = state === State.waitReply && io.memoryResponse.fire && !io.memoryResponse.bits.fault
  private val malformedLine = NativeTypedGraphicsLayout.receive(
    mapped,
    TessellationVertexSequenceLayout.words,
    TessellationVertexSequenceLayout.magic(domain),
    line,
    io.memoryResponse.bits.readData,
    write,
    recordVersion = TessellationVertexSequenceLayout.version(domain)
  )
  private def field(name: String): Data = mapped
    .find(_.name == name)
    .getOrElse(throw new IllegalArgumentException(s"missing vertex sequence field $name"))
    .value
  private def u(name: String): UInt = field(name).asUInt
  private val prefix = "template.source.viewport."
  private val system = prefix + "system."
  private val patch = system + "patch."
  private val context = patch + "control.patch.context."
  private val uniforms = record.sequence
    .asInstanceOf[Record]
    .elements("template")
    .asInstanceOf[Record]
    .elements("source")
    .asInstanceOf[Record]
    .elements("viewport")
    .asInstanceOf[Record]
    .elements("system")
    .asInstanceOf[Record]
    .elements("uniforms")
    .asInstanceOf[TessellationUniformTaskResources]

  private def inside(base: UInt, end: UInt, scopeBase: UInt, scopeEnd: UInt): Bool =
    end > base && end <= (BigInt(1) << 32).U && base >= scopeBase && end <= scopeEnd
  private val q = io.submit.bits; private val v = q.view
  private val end = v.base.pad(33) +& TessellationVertexSequenceLayout.bytes.U
  private val requestLegal =
    v.base(5, 0) === 0.U && v.allocationBase(5, 0) === 0.U && v.allocationLimit(5, 0) === 0.U &&
      inside(v.base, end, v.allocationBase, v.allocationLimit) && v.limit === end &&
      v.allocationLimit <= (BigInt(1) << 32).U &&
      q.privateBase(5, 0) === 0.U && q.privateLimit(5, 0) === 0.U &&
      inside(v.base, end, q.privateBase, q.privateLimit) && q.privateLimit <= (BigInt(1) << 32).U
  private val sourceEnd = record.sourceBase.pad(33) +& 512.U
  private val sourceBounds = record.sourceBase(5, 0) === 0.U && record.sourceLimit === sourceEnd &&
    inside(record.sourceBase, sourceEnd, request.privateBase, request.privateLimit)
  private val layout = Wire(new opengpu.graphics.tessellation.TessellationPatchLayout)
  layout.inputVertices := u(context + "layout.inputVertices");
  layout.inputComponents := u(context + "layout.inputComponents")
  layout.outputVertices := u(context + "layout.outputVertices");
  layout.outputComponents := u(context + "layout.outputComponents")
  layout.patchComponents := u(context + "layout.patchComponents")
  private val l = layout
  private val layoutLegal =
    l.inputVertices >= 1.U && l.inputVertices <= 32.U && l.outputVertices >= 1.U && l.outputVertices <= 32.U &&
      l.inputComponents <= 64.U && l.outputComponents <= 64.U && l.patchComponents <= 120.U &&
      l.inputVertices * l.inputComponents <= 2048.U && l.outputVertices * l.outputComponents +& l.patchComponents <= 2048.U
  private val excluded = mapped
    .filter(f =>
      f.name == "patchStride" || f.name.startsWith("template.input.") ||
        f.name == "template.stride" || f.name == patch + "patchInputCount" || f.name.startsWith(
          patch + "patchInputs["
        ) ||
        f.name == system + "uniformResources"
    )
    .map(f => !f.value.asUInt.orR)
    .reduce(_ && _)
  private val sameIdentity = Seq("context", "pass", "generation")
    .map(n => u(prefix + "identity." + n) === request.frameIdentity.elements(n).asUInt)
    .reduce(_ && _)
  private val present = uniforms.control.resources.version =/= 0.U || uniforms.evaluation.resources.version =/= 0.U
  private val uniformRoots = Seq(uniforms.control, uniforms.evaluation)
    .map(s =>
      s.resources.version === 0.U ||
        inside(s.resources.address, s.resources.address.pad(33) +& 64.U, request.privateBase, request.privateLimit)
    )
    .reduce(_ && _)
  private val uniformLegal = TessellationUniformTaskResources.legal(uniforms) && uniformRoots &&
    (!present || uniforms.identity.asUInt === request.frameIdentity.asUInt)
  private val count = u("count"); private val taskId = u(context + "taskId")
  private val taskRange = taskId.pad(65) + count.pad(65) <= (BigInt(1) << 32).U
  private val packetLegal = sourceBounds && layoutLegal && excluded && sameIdentity && uniformLegal && taskRange &&
    u(patch + "commandId") === 0.U

  io.submit.ready := state === State.idle && !io.cancel
  io.memoryRequest.valid := state === State.offer; io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := (request.view.base.pad(33) + (line << 6))(31, 0)
  io.memoryRequest.bits.sizeLog2 := 6.U; io.memoryRequest.bits.byteMask := Fill(64, 1.U(1.W))
  io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryResponse.ready := state === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  io.completion.valid := state === State.complete; io.completion.bits := result; io.busy := state =/= State.idle
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(io.submit.fire) {
    request := io.submit.bits; record := 0.U.asTypeOf(record); result := 0.U.asTypeOf(result);
    result.commandId := io.submit.bits.commandId
    line := 0.U; cancelled := false.B; malformed := false.B
    when(!requestLegal) {
      result.status := 1.U; result.faultAddress := io.submit.bits.view.base; state := State.complete
    }
      .otherwise { state := State.offer }
  }
  when(io.memoryRequest.fire) { state := State.waitReply }
  when(io.memoryResponse.fire) {
    result.readBytes := result.readBytes + 64.U
    when(io.memoryResponse.bits.fault) {
      result.status := 2.U; result.faultAddress := request.view.base + (line << 6); state := State.complete
    }
      .elsewhen(cancelled || io.cancel) { result.status := 4.U; state := State.complete }
      .otherwise {
        when(malformedLine) { malformed := true.B }
        when(line === 31.U) { state := State.check }.otherwise { line := line + 1.U; state := State.offer }
      }
  }
  when(state === State.check) {
    when(cancelled || io.cancel) { result.status := 4.U }
      .elsewhen(malformed || !packetLegal) { result.status := 1.U; result.faultAddress := request.view.base }
      .otherwise {
        result.sequence := record.sequence
        // Only the accepted owner's tag is injected into captured metadata;
        // raw commandId was required to be canonical zero above.
        result.sequence
          .asInstanceOf[Record]
          .elements("template")
          .asInstanceOf[Record]
          .elements("source")
          .asInstanceOf[Record]
          .elements("viewport")
          .asInstanceOf[Record]
          .elements("system")
          .asInstanceOf[Record]
          .elements("patch")
          .asInstanceOf[Record]
          .elements("commandId") := request.commandId
        val s = result.sourceRequest
        s.view.base := record.sourceBase; s.view.limit := record.sourceLimit
        s.view.allocationBase := record.sourceBase; s.view.allocationLimit := record.sourceLimit
        s.privateBase := request.privateBase; s.privateLimit := request.privateLimit;
        s.identity := request.frameIdentity
        s.sequenceIndex := request.expectedSequenceIndex; s.drawIndex := request.expectedDrawIndex
        s.taskIdBase := taskId; s.patchCount := count; s.layout := layout
      }
    state := State.complete
  }
  when(io.completion.fire) { state := State.idle }
}
