package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

class TessellationPatchLayout extends Bundle {
  val inputVertices = UInt(6.W); val inputComponents = UInt(7.W)
  val outputVertices = UInt(6.W); val outputComponents = UInt(7.W); val patchComponents = UInt(7.W)
}
class TessellationPatchAccess extends Bundle {

  /** output=false selects input vertices; output=true selects control output. */
  /** tessLevel selects outer[0..3] and inner[0..1], outside the patch-component budget. */
  val output = Bool(); val perPatch = Bool(); val tessLevel = Bool(); val write = Bool()
  val vertex = UInt(6.W); val component = UInt(7.W); val data = UInt(32.W)
}
class TessellationPatchReply extends Bundle {
  val status = UInt(2.W); val data = UInt(32.W)
}

/** One owned patch with separately packed input and control-output memories. Legacy unwritten components have undefined
  * contents. With zeroOnBegin, every address reachable under the new layout and all six levels initialize before
  * beginResult; shader barriers still govern concurrent access. Requests and replies are serialized, and a held reply
  * prevents reuse or patch release.
  */
class TessellationPatchStore(zeroOnBegin: Boolean) extends Module {
  def this() = this(false)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationPatchLayout))
    val beginResult = Decoupled(UInt(2.W))
    val access = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationPatchReply)
    val end = Flipped(Decoupled(Bool()))
    val busy = Output(Bool())
  })
  private val depth = TessellationLimits.maxControlOutputComponents
  private val input = SyncReadMem(depth, UInt(32.W)); private val output = SyncReadMem(depth, UInt(32.W))
  private val levels = Reg(Vec(6, UInt(32.W)))
  private val layout = Reg(new TessellationPatchLayout)
  private val active = RegInit(false.B); private val opening = RegInit(false.B); private val openStatus = Reg(UInt(2.W))
  private val responseValid = RegInit(false.B); private val response = Reg(new TessellationPatchReply)
  private val readPending = RegInit(false.B); private val readOutput = Reg(Bool())
  private val clearing = if (zeroOnBegin) Some(RegInit(false.B)) else None
  private val clearAddress = if (zeroOnBegin) Some(Reg(UInt(11.W))) else None
  private val inputWords = if (zeroOnBegin) Some(Reg(UInt(12.W))) else None
  private val outputWords = if (zeroOnBegin) Some(Reg(UInt(12.W))) else None
  private val isClearing = clearing.getOrElse(false.B)
  private val inCount = io.begin.bits.inputVertices * io.begin.bits.inputComponents
  private val outCount = io.begin.bits.outputVertices * io.begin.bits.outputComponents +& io.begin.bits.patchComponents
  private val legal =
    io.begin.bits.inputVertices >= 1.U && io.begin.bits.inputVertices <= TessellationLimits.maxPatchVertices.U &&
      io.begin.bits.outputVertices >= 1.U && io.begin.bits.outputVertices <= TessellationLimits.maxPatchVertices.U &&
      io.begin.bits.inputComponents <= 64.U && io.begin.bits.outputComponents <= 64.U && io.begin.bits.patchComponents <= 120.U &&
      inCount <= depth.U && outCount <= depth.U
  io.begin.ready := !active && !opening && !responseValid && !readPending && !isClearing
  io.beginResult.valid := opening && !isClearing; io.beginResult.bits := openStatus
  io.busy := active || opening || responseValid || readPending || isClearing
  when(io.begin.fire) {
    layout := io.begin.bits; active := legal; opening := true.B; openStatus := Mux(legal, 0.U, 1.U)
  }
  if (zeroOnBegin) {
    when(io.begin.fire) {
      clearing.get := legal && (inCount =/= 0.U || outCount =/= 0.U)
      clearAddress.get := 0.U; inputWords.get := inCount; outputWords.get := outCount
      when(legal) { levels := VecInit(Seq.fill(6)(0.U(32.W))) }
    }
    when(isClearing) {
      val words = Mux(inputWords.get > outputWords.get, inputWords.get, outputWords.get)
      when(clearAddress.get +& 1.U >= words) { clearing.get := false.B }
        .otherwise { clearAddress.get := clearAddress.get + 1.U }
    }
  }
  when(io.beginResult.fire) { opening := false.B }

  private val req = io.access.bits
  private val vertexBound = Mux(req.output, layout.outputVertices, layout.inputVertices)
  private val componentBound = Mux(req.output, layout.outputComponents, layout.inputComponents)
  private val vertexValid = !req.perPatch && req.vertex < vertexBound && req.component < componentBound
  private val patchValid = req.output && req.perPatch && req.vertex === 0.U && req.component < layout.patchComponents
  private val levelValid = req.output && req.perPatch && req.tessLevel && req.vertex === 0.U && req.component < 6.U
  private val accessValid = Mux(req.tessLevel, levelValid, vertexValid || patchValid)
  private val vertexAddress = req.vertex * componentBound +& req.component
  private val patchAddress = layout.outputVertices * layout.outputComponents +& req.component
  private val address = Mux(req.perPatch, patchAddress, vertexAddress)(10, 0)
  io.access.ready := active && !opening && !responseValid && !readPending && !io.end.valid && !isClearing
  io.end.ready := active && !opening && !responseValid && !readPending && !isClearing
  when(io.end.fire) { active := false.B }
  private val read = io.access.fire && accessValid && !req.write && !req.tessLevel
  private val inputData = input.read(address, read && !req.output)
  private val outputData = output.read(address, read && req.output)
  private val write = io.access.fire && accessValid && req.write && !req.tessLevel
  // Share each bank's original write port with initialization; do not infer a
  // second SRAM write port or increase its capacity for owner isolation.
  if (zeroOnBegin) {
    val a = Mux(isClearing, clearAddress.get, address); val data = Mux(isClearing, 0.U, req.data)
    when((isClearing && clearAddress.get < inputWords.get) || (write && !req.output)) { input.write(a, data) }
    when((isClearing && clearAddress.get < outputWords.get) || (write && req.output)) { output.write(a, data) }
  } else {
    when(write && !req.output) { input.write(address, req.data) }
    when(write && req.output) { output.write(address, req.data) }
  }
  when(io.access.fire) {
    when(!accessValid) { response.status := 1.U; response.data := 0.U; responseValid := true.B }
      .elsewhen(req.tessLevel) {
        when(req.write) { levels(req.component(2, 0)) := req.data }
        response.status := 0.U; response.data := Mux(req.write, 0.U, levels(req.component(2, 0)));
        responseValid := true.B
      }
      .elsewhen(req.write) {
        response.status := 0.U; response.data := 0.U; responseValid := true.B
      }
      .otherwise { readPending := true.B; readOutput := req.output }
  }
  when(readPending) {
    response.status := 0.U; response.data := Mux(readOutput, outputData, inputData); responseValid := true.B;
    readPending := false.B
  }
  io.reply.valid := responseValid; io.reply.bits := response
  when(io.reply.fire) { responseValid := false.B }
}
