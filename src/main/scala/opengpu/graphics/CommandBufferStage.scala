package opengpu.graphics

import chisel3._
import chisel3.util._

object TileCommandMemoryLayout {
  val payloadWords = 40
  val legacyBytes = payloadWords * 4
  val typedBytes = (payloadWords + 1) * 4
  val draw = 0
  val clear = 1
}

/** Versioned command-buffer reader (legacy draw interface by default).
  *
  * Reads `count` sequential draw-call records from software-allocated host
  * memory starting at `base` and emits each as a SceneTriangle on `draw`.
  * A legacy draw record has this 40-word layout (little-endian word index):
  *
  *   [0..11]  v0/v1/v2 clip-space (x,y,z,w) as Q16.16   (12 words)
  *   [12..20] v0/v1/v2 colour (r,g,b) as 8-bit          (9 words)
  *   [21..23] v0/v1/v2 depth as signed 32-bit           (3 words)
  *   [24]     shader entry PC (the draw's shader descriptor)
  *   [25]     kernarg buffer address
  *   [26..31] v0/v1/v2 texture u,v as unsigned Q16.16
  *   [32]     optional depth/cull/texture/blend/stencil state override
  *   [33]     signed integer LOD bias and minimum mip clamp
  *   [34]     optional two-bank kernarg byte stride
  *   [35]     optional GL-style blend config (bit0 present, factors, equation)
  *   [36]     stencil config: func [2:0], fail [5:3], zfail [8:6], zpass [11:9]
  *   [37]     stencil ref [7:0], read mask [15:8], write mask [23:16]
  *   [38..39] packed scissor min/max XY (enabled by word 32 bit 18)
  *
  * This is the hardware side of "the driver writes a command list, the GPU
  * executes it", the prerequisite for a host-driven (M6) Linux device.  The
  * phase-D shader descriptor lets a draw select a compiled RV32 shader program
  * and point at it kernarg buffer.
  */
class CommandBufferStage(config: GraphicsConfig, vertCore: Boolean = false,
  cancellable: Boolean = false, tileCommands: Boolean = false) extends Module {
  require(!tileCommands || (cancellable && !vertCore))
  private val wordsPerRecord = TileCommandMemoryLayout.payloadWords

  val io = IO(new Bundle {
    val base = Input(UInt(32.W))
    val count = Input(UInt(16.W))
    val start = Input(Bool())
    /** Optional pass-owner cancellation. Unaccepted draws are discarded;
      * an offered or accepted memory read retains ownership until drain.
      * The next job may start only after done, including the discarded FIFO.
      */
    val cancel = if (cancellable) Some(Input(Bool())) else None
    /** Version 2 adds a type word before the unchanged forty-word payload. */
    val typedCommands = if (tileCommands) Some(Input(Bool())) else None
    val clear = if (tileCommands) Some(Decoupled(new TileAttachmentClear)) else None
    val clearComplete = if (tileCommands) Some(Input(Bool())) else None
    val encodingFault = if (tileCommands) Some(Output(Bool())) else None
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val draw = if (vertCore) Decoupled(new VertexDrawCommand(config)) else Decoupled(new SceneTriangle(config))
    val done = Output(Bool())
  })

  private val record = RegInit(0.U(16.W))
  private val word = RegInit(0.U(log2Ceil(wordsPerRecord).W)) // 0..39
  private val waiting = RegInit(false.B) // a read is in flight
  private val words = Reg(Vec(wordsPerRecord, UInt(32.W)))
  private val typed = if (tileCommands) RegInit(false.B) else false.B
  private val commandType = if (tileCommands) Reg(UInt(32.W)) else 0.U
  private val clearPending = if (tileCommands) RegInit(false.B) else false.B
  private val running = RegInit(false.B)
  private val presenting = RegInit(false.B)
  private val readAddress = if (cancellable) Some(Reg(UInt(32.W))) else None
  private val jobCount = if (cancellable) Reg(UInt(16.W)) else io.count
  private val aborting = if (cancellable) RegInit(false.B) else false.B
  private val offered = if (cancellable) RegInit(false.B) else false.B
  private val cancelRequested = io.cancel.getOrElse(false.B)
  private val discard = aborting || cancelRequested
  /**
    * Decouple command-buffer memory latency from rasterization.  The parser
    * may fill these entries while the pipeline is still rendering the
    * previous draw, but the FIFO remains the ordering point for draw records.
    */
  private val drawFifo = if (!tileCommands) Some(Module(new Queue(
    if (vertCore) new VertexDrawCommand(config) else new SceneTriangle(config),
    config.drawFifoDepth))) else None
  private val fifoValid = Wire(Bool())
  private val enqueueFire = Wire(Bool())

  // Pass-owned fetching is sequential. Increment the address only on accepted
  // reads instead of repeating a record multiply and two additions per word.
  private val wordAddr = readAddress.getOrElse(
    io.base + ((record * wordsPerRecord.U) + word) * 4.U)

  private val decodedDraw = if (vertCore) {
    val d = Wire(new VertexDrawCommand(config))
    d := 0.U.asTypeOf(new VertexDrawCommand(config))
    // New draw record format for vertCore:
    // [0] vertex buffer base, [1] vertex count, [2] vertex stride
    // [3] vertex shader PC, [4] vertex kernarg addr, [5] vertex kernarg bank stride
    // [6] vertex attribute format, [7-23] reserved
    // [24] fragment shader PC, [25] fragment kernarg addr, [26-31] reserved
    // [32] state override flags, [33] LOD bias + min mip clamp, [34] frag kernarg bank stride
    d.vertBufferBase := words(0)
    d.vertCount := words(1)(15, 0)
    d.vertStride := words(2)(15, 0)
    d.vertShaderPc := words(3)
    d.vertKernarg := words(4)
    d.vertKernargBankStride := words(5)
    d.fragShaderPc := words(24)
    d.fragKernarg := words(25)
    d.fragKernargBankStride := words(34)
    d.fragInputSkip := words(32)(24, 19)
    d.fragOutputSkip := words(32)(28, 25)
    d.stateOverride := words(32)(0)
    d.depthTestEnable := words(32)(1)
    d.depthFunc := words(32)(6, 4)
    d.depthWriteEnable := words(32)(7)
    d.blendEnable := words(32)(16)
    d.stencilTestEnable := words(32)(17)
    d.cullMode := words(32)(9, 8)
    d.texEnable := words(32)(10)
    d.texWrapClamp := words(32)(11)
    d.texMaxLevel := words(32)(15, 12)
    d.texLodBias := words(33)(4, 0).asSInt
    d.texMinLevel := words(33)(11, 8)
    d.blendCfgEnable := words(35)(0)
    d.blendSrcFactor := words(35)(7, 4)
    d.blendDstFactor := words(35)(11, 8)
    d.blendEquation := words(35)(14, 12)
    d.stencilFunc := words(36)(2, 0)
    d.stencilFailOp := words(36)(5, 3)
    d.stencilZFailOp := words(36)(8, 6)
    d.stencilZPassOp := words(36)(11, 9)
    d.stencilRef := words(37)(7, 0)
    d.stencilReadMask := words(37)(15, 8)
    d.stencilWriteMask := words(37)(23, 16)
    d.scissorEnable := words(32)(18)
    d.scissorMinX := words(38)(15, 0)
    d.scissorMinY := words(38)(31, 16)
    d.scissorMaxX := words(39)(15, 0)
    d.scissorMaxY := words(39)(31, 16)
    d
  } else {
    val d = Wire(new SceneTriangle(config))
    d := 0.U.asTypeOf(new SceneTriangle(config))
    d.clip(0).x := words(0).asSInt
    d.clip(0).y := words(1).asSInt
    d.clip(0).z := words(2).asSInt
    d.clip(0).w := words(3).asSInt
    d.clip(1).x := words(4).asSInt
    d.clip(1).y := words(5).asSInt
    d.clip(1).z := words(6).asSInt
    d.clip(1).w := words(7).asSInt
    d.clip(2).x := words(8).asSInt
    d.clip(2).y := words(9).asSInt
    d.clip(2).z := words(10).asSInt
    d.clip(2).w := words(11).asSInt
    d.color(0).r := words(12)(7, 0)
    d.color(0).g := words(13)(7, 0)
    d.color(0).b := words(14)(7, 0)
    d.color(1).r := words(15)(7, 0)
    d.color(1).g := words(16)(7, 0)
    d.color(1).b := words(17)(7, 0)
    d.color(2).r := words(18)(7, 0)
    d.color(2).g := words(19)(7, 0)
    d.color(2).b := words(20)(7, 0)
    // Fixed-function records store RGB only. Leave them opaque so existing
    // draws keep the alpha the rasterizer used to force.
    d.alpha(0) := 0xff.U
    d.alpha(1) := 0xff.U
    d.alpha(2) := 0xff.U
    d.depth(0) := words(21).asSInt
    d.depth(1) := words(22).asSInt
    d.depth(2) := words(23).asSInt
    d.shaderPc := words(24)
    d.shaderKernarg := words(25)
    d.uv(0).u := words(26)
    d.uv(0).v := words(27)
    d.uv(1).u := words(28)
    d.uv(1).v := words(29)
    d.uv(2).u := words(30)
    d.uv(2).v := words(31)
    d.fragInputSkip := words(32)(24, 19)
    d.fragOutputSkip := words(32)(28, 25)
    d.stateOverride := words(32)(0)
    d.depthTestEnable := words(32)(1)
    d.depthFunc := words(32)(6, 4)
    d.depthWriteEnable := words(32)(7)
    d.cullMode := words(32)(9, 8)
    d.texEnable := words(32)(10)
    d.texWrapClamp := words(32)(11)
    d.texMaxLevel := words(32)(15, 12)
    d.blendEnable := words(32)(16)
    d.stencilTestEnable := words(32)(17)
    d.texLodBias := words(33)(4, 0).asSInt
    d.texMinLevel := words(33)(11, 8)
    d.kernargBankStride := words(34)
    d.blendCfgEnable := words(35)(0)
    d.blendSrcFactor := words(35)(7, 4)
    d.blendDstFactor := words(35)(11, 8)
    d.blendEquation := words(35)(14, 12)
    d.stencilFunc := words(36)(2, 0)
    d.stencilFailOp := words(36)(5, 3)
    d.stencilZFailOp := words(36)(8, 6)
    d.stencilZPassOp := words(36)(11, 9)
    d.stencilRef := words(37)(7, 0)
    d.stencilReadMask := words(37)(15, 8)
    d.stencilWriteMask := words(37)(23, 16)
    d.scissorEnable := words(32)(18)
    d.scissorMinX := words(38)(15, 0)
    d.scissorMinY := words(38)(31, 16)
    d.scissorMaxX := words(39)(15, 0)
    d.scissorMaxY := words(39)(31, 16)
    d
  }

  if (tileCommands) {
    val decodedClear = Wire(new TileAttachmentClear)
    decodedClear.minX := words(0)(4, 0); decodedClear.minY := words(0)(12, 8)
    decodedClear.maxX := words(0)(20, 16); decodedClear.maxY := words(0)(28, 24)
    decodedClear.colorMask := words(1); decodedClear.depthMask := words(2)
    decodedClear.colorValue := words(3); decodedClear.depthValue := words(4)
    val isClear = typed && commandType === TileCommandMemoryLayout.clear.U
    val legalClear = (words(0) & "he0e0e0e0".U) === 0.U && words.drop(5).map(_ === 0.U).reduce(_ && _)
    val invalid = typed && (commandType > TileCommandMemoryLayout.clear.U || (isClear && !legalClear))
    // This signal must not depend on cancel: the controller uses it to
    // request cancellation on this same cycle.
    io.encodingFault.get := presenting && invalid && !aborting
    val fifo = Module(new Queue(new Bundle {
      val clear = Bool()
      val payload = UInt(decodedDraw.getWidth.W)
    }, config.drawFifoDepth))
    require(decodedDraw.getWidth >= decodedClear.getWidth)
    fifo.io.enq.valid := presenting && !discard && !invalid
    fifo.io.enq.bits.clear := isClear
    fifo.io.enq.bits.payload := Mux(isClear, decodedClear.asUInt, decodedDraw.asUInt)
    fifoValid := fifo.io.deq.valid
    enqueueFire := fifo.io.enq.fire
    io.draw.valid := fifo.io.deq.valid && !fifo.io.deq.bits.clear && !clearPending && !discard
    io.draw.bits := fifo.io.deq.bits.payload.asTypeOf(io.draw.bits)
    io.clear.get.valid := fifo.io.deq.valid && fifo.io.deq.bits.clear && !clearPending && !discard
    io.clear.get.bits := fifo.io.deq.bits.payload.asTypeOf(new TileAttachmentClear)
    fifo.io.deq.ready := discard || (!clearPending && Mux(fifo.io.deq.bits.clear,
      io.clear.get.ready, io.draw.ready))
    when(io.clear.get.fire) { clearPending := true.B }
    when(io.clearComplete.get || discard) { clearPending := false.B }
    when(presenting && invalid && !aborting) {
      aborting := true.B; running := false.B; presenting := false.B
    }
  } else {
    val fifo = drawFifo.get
    fifo.io.enq.valid := presenting && !discard
    fifo.io.enq.bits := decodedDraw
    fifoValid := fifo.io.deq.valid
    enqueueFire := fifo.io.enq.fire
    if (cancellable) {
    // Cancellation explicitly withdraws unaccepted draw records. It never
    // withdraws a bus request that was already offered to memory.
      io.draw.valid := fifo.io.deq.valid && !discard
      io.draw.bits := fifo.io.deq.bits
      fifo.io.deq.ready := discard || io.draw.ready
    } else io.draw <> fifo.io.deq
  }

  // A start pulse is not itself a completed job.  This also makes the
  // contract robust for users that sample done in the launch cycle rather
  // than using the RenderHost sawBusy guard.
  io.done := !io.start && !running && !presenting && !waiting &&
    !fifoValid && !offered && !clearPending
  io.mem.req.valid := (running && !presenting && !waiting && !discard) || offered
  io.mem.req.bits.addr := wordAddr
  io.mem.req.bits.write := false.B
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := (if (cancellable) waiting else running && waiting)

  private val lastRecord = record === jobCount - 1.U

  when(!running && io.start && io.count.orR && !fifoValid && !clearPending &&
      !waiting && !offered && !cancelRequested) {
    if (cancellable) {
      readAddress.get := io.base
      jobCount := io.count
      aborting := false.B
    }
    if (tileCommands) { typed := io.typedCommands.get }
    running := true.B
    record := 0.U
    word := 0.U
    waiting := false.B
    presenting := false.B
  }.elsewhen(discard) {
    running := false.B
    presenting := false.B
    when(io.mem.resp.fire) { waiting := false.B }
    when(io.mem.req.fire) { waiting := true.B }
  }.elsewhen(waiting) {
    when(io.mem.resp.fire) {
      if (tileCommands) {
        when(typed && word === 0.U) { commandType := io.mem.resp.bits.data }
          .otherwise { words(Mux(typed, word - 1.U, word)) := io.mem.resp.bits.data }
      } else words(word) := io.mem.resp.bits.data
      waiting := false.B
      when(word === Mux(typed, wordsPerRecord.U, (wordsPerRecord - 1).U)) {
        presenting := true.B
      }.otherwise {
        word := word + 1.U
      }
    }
  }.elsewhen(presenting) {
    when(enqueueFire) {
      presenting := false.B
      when(lastRecord) {
        running := false.B
      }.otherwise {
        record := record + 1.U
        word := 0.U
      }
    }
  }.otherwise {
    // issue the next word read
    when(io.mem.req.fire) { waiting := true.B }
  }

  if (cancellable) {
    when(cancelRequested) { aborting := true.B }
    when(io.mem.req.valid) { offered := !io.mem.req.ready }
    when(io.mem.req.fire) { readAddress.get := readAddress.get + 4.U }
    assert(!(offered && waiting), "a command read cannot be both offered and issued")
  }

}
