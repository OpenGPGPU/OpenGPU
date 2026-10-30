package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand, GpuCommandResult}
import opengpu.config.GpuConfig
import opengpu.core.memory.VectorTlbFlush

object GpuCommandMmioRegs {
  val COMMAND_ID = 0x0c4
  val OPCODE = 0x0c8
  val KERNEL_PC = 0x0cc
  val KERNARG = 0x0d0
  val GRID_X = 0x0d4
  val GRID_Y = 0x0d8
  val GRID_Z = 0x0dc
  val LOCAL_X = 0x0e0
  val LOCAL_Y = 0x0e4
  val LOCAL_Z = 0x0e8
  val FLAGS = 0x0ec
  val DMA_DEPENDENCY = 0x0f0
  val SOURCE = 0x0f4
  val DESTINATION = 0x0f8
  val BYTES = 0x0fc
  val PATTERN = 0x100
  val WIDTH = 0x104
  val HEIGHT = 0x108
  val SOURCE_STRIDE = 0x10c
  val DESTINATION_STRIDE = 0x110
  val WAIT_EVENT = 0x114
  val SIGNAL_EVENT = 0x118
  val SUBMIT = 0x11c
  val STATUS = 0x120
  val COMPLETION = 0x124
  val COMPLETION_BYTES_LO = 0x128
  val COMPLETION_BYTES_HI = 0x12c
  val COMPLETION_POP = 0x130
  /** W1P bit0: request a safe unified-command reset.  The hardware stops
    * accepting and dispatching new commands, drains in-flight work and memory
    * transactions, resets the command-path state, then raises the completion
    * event.  Lives outside the contiguous staging bank (MSAA_CONFIG owns
    * 0x134); the AXI top routes this one address here explicitly. */
  val RESET = 0x138
  /** MSAA sample mode staged for a typed resolve submission (bits 1:0; 0/1/2).
    * Placed past `RenderHostRegs.END` (0x148), which was previously invalid, so
    * the AXI top routes this one address here as well. */
  val SAMPLE_MODE = 0x148
  /** Sv32 `satp` for the vector data and instruction MMUs.  Bit 31 enables
    * translation; bits 30:22 are the ASID and bits 19:0 the root page-table
    * PPN.  Changing either requires a TLB flush. */
  val VECTOR_SATP = 0x14c
  val INSTRUCTION_SATP = 0x150
  /** `satp` fields (bits 30:22 ASID, bit 31 enable), mirrored in gpu_abi.h. */
  val SATP_ENABLE = 0x80000000L
  val SATP_ASID_SHIFT = 22
  val SATP_ASID_MASK = 0x1ff
  /** Write-1 TLB control.  Bit 0 invalidates every entry (a full flush).
    * Bit 1 requests an ASID-scoped flush: entries whose ASID equals bits
    * [11:3] are dropped while global mappings and other address spaces stay
    * warm.  Bit 2 requests a VPN-scoped flush: entries whose virtual page
    * number equals bits [31:12] are dropped.  Bits 1 and 2 may be combined to
    * drop only the one mapping.  A full flush dominates the scoped bits. */
  val TLB_FLUSH = 0x154
  /** Write-1 `TLB_FLUSH` fields, mirrored in `driver/gpu_abi.h`. */
  val TLB_FLUSH_FULL = 1
  val TLB_FLUSH_ASID = 1 << 1
  val TLB_FLUSH_ASID_SHIFT = 3
  val TLB_FLUSH_ASID_MASK = 0x1ff
  val TLB_FLUSH_VPN = 1 << 2
  val TLB_FLUSH_VPN_SHIFT = 12
  val TLB_FLUSH_VPN_MASK = 0xfffff
  /** Read-only shader lanes/warps/kernarg banks/execution CUs, one byte each. */
  val SHADER_CONFIG = 0x158
  val END = 0x15c
}

/** Opt-in image command staging, between the uniform and blend discovery blocks.
  * The magic identifies this transport only; it does not advertise API support.
  */
object ShaderImageCommandMmioRegs {
  val base = 0x3e0
  val CONTROL = base
  val TABLE_BASE = base + 4
  val TABLE_LIMIT_LO = base + 8
  val TABLE_LIMIT_HI = base + 12
  val CONTEXT = base + 16
  val PASS = base + 20
  val GENERATION = base + 24
  val MAGIC = base + 28
  val end = base + 32
  val magic = BigInt("47494d01", 16)
  val controlMask = BigInt("0000ff01", 16)
}

/** Register-programmed bridge to the ordered unified GPU command stream. */
class GpuCommandMmio(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8,
  queueDepth: Int = 4,
  shaderComputeUnits: Int = 1,
  shaderKernargBanks: Int = 2,
  protectVmState: Boolean = false,
  nativeCompletionInfo: Boolean = false,
  imageAvailable: Boolean = false
) extends Module {
  require(commandIdWidth > 0 && commandIdWidth <= 8)
  require(queueDepth > 0)
  require(!imageAvailable || protectVmState, "image commands retain protected VM ownership")
  private val shaderConfigWord = ShaderResourceConfig.encode(
    config.lanes, config.warps, shaderKernargBanks, shaderComputeUnits)

  val io = IO(new Bundle {
    val reg = new Bundle {
      val req = Flipped(Decoupled(new RenderHostRegRequest))
      val resp = Decoupled(new RenderHostRegResponse)
    }
    val command = Decoupled(new GpuCommand(config, commandIdWidth, imageAvailable))
    val completion = Flipped(Decoupled(
      new GpuCommandResult(commandIdWidth)))
    val completionDetails=if(nativeCompletionInfo)Some(Input(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))else None
    val completionEvent = Output(Bool())
    /** Held from a RESET request until the system's command path reports a
      * completed drain and state reset via `resetDone`.  While active this
      * bridge drops staged commands, rejects submissions and discards late
      * completions. */
    val resetActive = Output(Bool())
    /** Single-cycle pulse from the system: every in-flight command and memory
      * transaction has drained and the command-path state has been reset. */
    val resetDone = Input(Bool())
    /** Every external owner/translation/memory client has retired. Local
      * staged commands and the software completion slot are checked here. */
    val vmContextIdle = if (protectVmState) Some(Input(Bool())) else None
    /** Includes local staged commands, held/incoming completions and reset,
      * in addition to all external context owners. */
    val vmStateWriteAllowed = if (protectVmState) Some(Output(Bool())) else None
    /** Current Sv32 page-table base/ASID for the vector and instruction MMUs. */
    val vectorSatp = Output(UInt(32.W))
    val instructionSatp = Output(UInt(32.W))
    /** One-cycle TLB flush programmed through `TLB_FLUSH`.  Both validity
      * bits clear is a full flush; the scoped fields select the entries. */
    val tlbFlush = Output(Valid(new VectorTlbFlush(config)))
  })

  private def merge(old: UInt, data: UInt, strb: UInt): UInt =
    Cat((0 until 4).reverse.map { byte =>
      Mux(strb(byte), data(8 * byte + 7, 8 * byte),
        old(8 * byte + 7, 8 * byte))
    })

  private val commandId = RegInit(0.U(32.W))
  private val opcode = RegInit(0.U(32.W))
  private val kernelPc = RegInit(0.U(32.W))
  private val kernarg = RegInit(0.U(32.W))
  private val grid = RegInit(VecInit(Seq.fill(3)(1.U(32.W))))
  private val local = RegInit(VecInit(Seq.fill(3)(1.U(32.W))))
  private val flags = RegInit(0.U(32.W))
  private val dmaDependency = RegInit(0.U(32.W))
  private val source = RegInit(0.U(32.W))
  private val destination = RegInit(0.U(32.W))
  private val bytes = RegInit(0.U(32.W))
  private val pattern = RegInit(0.U(32.W))
  private val width = RegInit(0.U(32.W))
  private val height = RegInit(0.U(32.W))
  private val sourceStride = RegInit(0.U(32.W))
  private val destinationStride = RegInit(0.U(32.W))
  private val waitEvent = RegInit(0.U(32.W))
  private val signalEvent = RegInit(0.U(32.W))
  private val sampleMode = RegInit(0.U(32.W))
  private val vectorSatp = RegInit(0.U(32.W))
  private val instructionSatp = RegInit(0.U(32.W))
  private val tlbFlushValid = RegInit(false.B)
  private val tlbFlushRequest =
    RegInit(0.U.asTypeOf(new VectorTlbFlush(config)))
  private val submitOverflow = RegInit(false.B)
  private val resetActive = RegInit(false.B)
  private val resetRejected = RegInit(false.B)
  private val imageWords = if (imageAvailable)
    Some(RegInit(VecInit(Seq.fill(7)(0.U(32.W))))) else None
  private val imageAddress = if (imageAvailable)
    io.reg.req.bits.addr >= ShaderImageCommandMmioRegs.base.U &&
      io.reg.req.bits.addr < ShaderImageCommandMmioRegs.end.U &&
      io.reg.req.bits.addr(1, 0) === 0.U else false.B
  private val imageWriteOk = WireDefault(false.B)
  imageWords.foreach { words =>
    val index = ((io.reg.req.bits.addr - ShaderImageCommandMmioRegs.base.U) >> 2)(2, 0)
    val selected = Mux(index < 7.U, words(index), 0.U)
    val merged = merge(selected, io.reg.req.bits.data, io.reg.req.bits.strb)
    imageWriteOk := index < 7.U &&
      (index =/= 0.U || (merged & (~ShaderImageCommandMmioRegs.controlMask & ((BigInt(1) << 32) - 1)).U) === 0.U) &&
      (index =/= 3.U || !merged(31, 1).orR)
    when(io.reg.req.fire && io.reg.req.bits.isWrite && imageAddress && imageWriteOk) {
      words(index) := merged
    }
  }

  private val queue = Module(new Queue(
    new GpuCommand(config, commandIdWidth, imageAvailable), queueDepth))

  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite
  private val resetRequest = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.RESET.U &&
    io.reg.req.bits.data(0)
  when(resetRequest) { resetActive := true.B }
  imageWords.foreach { words => when(resetRequest) { words(0) := 0.U } }
  when(io.resetDone) { resetActive := false.B }
  io.resetActive := resetActive

  // Staged commands belong to the pre-reset command stream: while a reset
  // drains, pop them into the bit bucket instead of dispatching them.
  io.command.valid := queue.io.deq.valid && !resetActive
  io.command.bits := queue.io.deq.bits
  queue.io.deq.ready := resetActive || io.command.ready

  private val submit = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.SUBMIT.U &&
    io.reg.req.bits.data(0)
  queue.io.enq.valid := submit && !submitOverflow && !resetActive
  when(submit && resetActive) { resetRejected := true.B }
  queue.io.enq.bits.commandId := commandId(commandIdWidth - 1, 0)
  queue.io.enq.bits.opcode := opcode
  imageWords.foreach { words =>
    val images = queue.io.enq.bits.images.get
    images.enabled := words(0)(0) && (opcode === opengpu.command.GpuCommandOpcode.kernel ||
      opcode === opengpu.command.GpuCommandOpcode.kernelIndirect)
    images.table.version := words(0)(15, 8)
    images.table.base := words(1)
    images.table.limit := Cat(words(3)(0), words(2))
    images.table.identity.context := words(4)
    images.table.identity.pass := words(5)
    images.table.identity.generation := words(6)
  }
  queue.io.enq.bits.launch.invocations := 0.U.asTypeOf(queue.io.enq.bits.launch.invocations)
  queue.io.enq.bits.launch.kernelPc := kernelPc
  queue.io.enq.bits.launch.kernargAddress := kernarg
  for (axis <- 0 until 3) {
    queue.io.enq.bits.launch.gridSize(axis) := grid(axis)
    queue.io.enq.bits.launch.localSize(axis) := local(axis)(15, 0)
  }
  queue.io.enq.bits.rasterDiscard := flags(3)
  queue.io.enq.bits.feedbackCapture := flags(4)
  queue.io.enq.bits.waitForDma := flags(0)
  queue.io.enq.bits.dmaSource := dmaDependency(1, 0)
  queue.io.enq.bits.dmaDescriptorId :=
    dmaDependency(8 + commandIdWidth - 1, 8)
  queue.io.enq.bits.sourceAddress := source
  queue.io.enq.bits.destinationAddress := destination
  queue.io.enq.bits.bytes := bytes
  queue.io.enq.bits.pattern := pattern
  queue.io.enq.bits.widthBytes := width
  queue.io.enq.bits.height := height
  queue.io.enq.bits.sourceStride := sourceStride
  queue.io.enq.bits.destinationStride := destinationStride
  // Typed resolve sample mode (1x/2x/4x); ignored by other opcodes.
  queue.io.enq.bits.sampleMode := sampleMode(1, 0)
  queue.io.enq.bits.waitForEvent := flags(1)
  queue.io.enq.bits.waitEventId := waitEvent(commandIdWidth - 1, 0)
  queue.io.enq.bits.waitEventGeneration := waitEvent(15, 8)
  queue.io.enq.bits.signalEvent := flags(2)
  queue.io.enq.bits.signalEventId := signalEvent(commandIdWidth - 1, 0)
  queue.io.enq.bits.signalEventGeneration := signalEvent(15, 8)
  when(submit && !queue.io.enq.ready) { submitOverflow := true.B }

  private val completionValid = RegInit(false.B)
  private val completion = Reg(new GpuCommandResult(commandIdWidth))
  private val completionDetails=if(nativeCompletionInfo)Some(Reg(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))else None
  private val popCompletion = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.COMPLETION_POP.U &&
    io.reg.req.bits.data(0)
  private val vmWriteAllowed = io.vmContextIdle.getOrElse(true.B) &&
    (if (protectVmState) queue.io.count === 0.U && !completionValid &&
      !io.completion.valid && !resetActive else true.B)
  io.vmStateWriteAllowed.foreach(_ := vmWriteAllowed)
  private val vmMutation = io.reg.req.bits.isWrite && (
    io.reg.req.bits.addr === GpuCommandMmioRegs.VECTOR_SATP.U ||
    io.reg.req.bits.addr === GpuCommandMmioRegs.INSTRUCTION_SATP.U ||
    io.reg.req.bits.addr === GpuCommandMmioRegs.TLB_FLUSH.U)
  // Completions arriving mid-reset belong to the aborted stream; consume and
  // discard them so the post-reset slot starts empty.
  io.completion.ready := resetActive || !completionValid || popCompletion
  io.completionEvent := (io.completion.fire && !resetActive && !resetRequest) || io.resetDone
  when(io.completion.fire && !resetActive && !resetRequest) {
    completion := io.completion.bits
    completionDetails.foreach(_:=io.completionDetails.get)
    completionValid := true.B
  }.elsewhen(popCompletion || resetRequest ||
    (io.completion.fire && resetActive)) {
    completionValid := false.B
  }

  io.vectorSatp := vectorSatp
  io.instructionSatp := instructionSatp
  io.tlbFlush.valid := tlbFlushValid
  io.tlbFlush.bits := tlbFlushRequest
  when(tlbFlushValid) { tlbFlushValid := false.B }

  when(wFire) {
    switch(io.reg.req.bits.addr) {
      is(GpuCommandMmioRegs.COMMAND_ID.U) {
        commandId := merge(commandId, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.OPCODE.U) {
        opcode := merge(opcode, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.KERNEL_PC.U) {
        kernelPc := merge(kernelPc, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.KERNARG.U) {
        kernarg := merge(kernarg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.GRID_X.U) { grid(0) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.GRID_Y.U) { grid(1) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.GRID_Z.U) { grid(2) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_X.U) { local(0) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_Y.U) { local(1) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_Z.U) { local(2) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.FLAGS.U) { flags := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.DMA_DEPENDENCY.U) {
        dmaDependency := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.SOURCE.U) { source := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.DESTINATION.U) {
        destination := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.BYTES.U) { bytes := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.PATTERN.U) { pattern := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.WIDTH.U) { width := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.HEIGHT.U) { height := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.SOURCE_STRIDE.U) {
        sourceStride := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.DESTINATION_STRIDE.U) {
        destinationStride := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.WAIT_EVENT.U) { waitEvent := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.SIGNAL_EVENT.U) {
        signalEvent := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.SAMPLE_MODE.U) {
        sampleMode := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.VECTOR_SATP.U) {
        when(vmWriteAllowed) {
          vectorSatp := merge(vectorSatp, io.reg.req.bits.data,
                              io.reg.req.bits.strb)
        }
      }
      is(GpuCommandMmioRegs.INSTRUCTION_SATP.U) {
        when(vmWriteAllowed) {
          instructionSatp := merge(instructionSatp, io.reg.req.bits.data,
                                   io.reg.req.bits.strb)
        }
      }
      is(GpuCommandMmioRegs.TLB_FLUSH.U) {
        val data = io.reg.req.bits.data
        val full = data(0)
        val asidValid = data(1) && !full
        val vpnValid = data(2) && !full
        when(vmWriteAllowed && (full || asidValid || vpnValid)) {
          tlbFlushValid := true.B
          tlbFlushRequest.virtualPageNumberValid := vpnValid
          tlbFlushRequest.virtualPageNumber :=
            data(31, GpuCommandMmioRegs.TLB_FLUSH_VPN_SHIFT)
          tlbFlushRequest.asidValid := asidValid
          tlbFlushRequest.asid :=
            data(11, GpuCommandMmioRegs.TLB_FLUSH_ASID_SHIFT)
        }
      }
      is(GpuCommandMmioRegs.STATUS.U) {
        when(io.reg.req.bits.data(2)) { submitOverflow := false.B }
        when(io.reg.req.bits.data(4)) { resetRejected := false.B }
      }
    }
  }

  // Preserve every legacy field position. The fourth opcode bit uses reserved
  // bit16, rather than shifting status/success into a different ABI layout.
  private val legacyCompletionMeta = Cat(completion.success, completion.status,
    completion.opcode(2, 0), completion.commandId)
  private val completionMeta = Cat(0.U(15.W), completion.opcode(3), legacyCompletionMeta.pad(16))
  private val status = Cat(
    0.U(27.W), resetRejected, resetActive, submitOverflow, completionValid,
    queue.io.enq.ready)
  private val readData = MuxLookup(io.reg.req.bits.addr, 0.U(32.W))(Seq(
    GpuCommandMmioRegs.COMMAND_ID.U -> commandId,
    GpuCommandMmioRegs.OPCODE.U -> opcode,
    GpuCommandMmioRegs.KERNEL_PC.U -> kernelPc,
    GpuCommandMmioRegs.KERNARG.U -> kernarg,
    GpuCommandMmioRegs.GRID_X.U -> grid(0),
    GpuCommandMmioRegs.GRID_Y.U -> grid(1),
    GpuCommandMmioRegs.GRID_Z.U -> grid(2),
    GpuCommandMmioRegs.LOCAL_X.U -> local(0),
    GpuCommandMmioRegs.LOCAL_Y.U -> local(1),
    GpuCommandMmioRegs.LOCAL_Z.U -> local(2),
    GpuCommandMmioRegs.FLAGS.U -> flags,
    GpuCommandMmioRegs.DMA_DEPENDENCY.U -> dmaDependency,
    GpuCommandMmioRegs.SOURCE.U -> source,
    GpuCommandMmioRegs.DESTINATION.U -> destination,
    GpuCommandMmioRegs.BYTES.U -> bytes,
    GpuCommandMmioRegs.PATTERN.U -> pattern,
    GpuCommandMmioRegs.WIDTH.U -> width,
    GpuCommandMmioRegs.HEIGHT.U -> height,
    GpuCommandMmioRegs.SOURCE_STRIDE.U -> sourceStride,
    GpuCommandMmioRegs.DESTINATION_STRIDE.U -> destinationStride,
    GpuCommandMmioRegs.WAIT_EVENT.U -> waitEvent,
    GpuCommandMmioRegs.SIGNAL_EVENT.U -> signalEvent,
    GpuCommandMmioRegs.SAMPLE_MODE.U -> sampleMode,
    GpuCommandMmioRegs.VECTOR_SATP.U -> vectorSatp,
    GpuCommandMmioRegs.INSTRUCTION_SATP.U -> instructionSatp,
    GpuCommandMmioRegs.SHADER_CONFIG.U -> shaderConfigWord.U(32.W),
    GpuCommandMmioRegs.STATUS.U -> status,
    GpuCommandMmioRegs.COMPLETION.U -> completionMeta,
    GpuCommandMmioRegs.COMPLETION_BYTES_LO.U -> completion.bytesProcessed(31, 0),
    GpuCommandMmioRegs.COMPLETION_BYTES_HI.U -> completion.bytesProcessed(63, 32))++
    completionDetails.toSeq.flatMap(ws=>ws.zipWithIndex.map{case(word,i)=>(NativeBinnedCompletionInfo.base+i*4).U -> Mux(completionValid && !resetActive,word,0.U)}) ++
    imageWords.toSeq.flatMap(ws => ws.zipWithIndex.map { case (word, i) =>
      (ShaderImageCommandMmioRegs.base + i * 4).U -> word
    } :+ (ShaderImageCommandMmioRegs.MAGIC.U -> ShaderImageCommandMmioRegs.magic.U(32.W))))

  io.reg.req.ready := true.B
  io.reg.resp.valid := io.reg.req.valid && !io.reg.req.bits.isWrite
  io.reg.resp.bits.data := readData
  val detailAddress=if(nativeCompletionInfo)io.reg.req.bits.addr>=NativeBinnedCompletionInfo.base.U && io.reg.req.bits.addr<NativeBinnedCompletionInfo.end.U else false.B
  io.reg.resp.bits.ok := ((io.reg.req.bits.addr >= GpuCommandMmioRegs.COMMAND_ID.U &&
    io.reg.req.bits.addr < GpuCommandMmioRegs.END.U &&
    (!vmMutation || vmWriteAllowed)) || (detailAddress && !io.reg.req.bits.isWrite) ||
    (imageAddress && (!io.reg.req.bits.isWrite || imageWriteOk))) && io.reg.req.bits.addr(1,0)===0.U
}
