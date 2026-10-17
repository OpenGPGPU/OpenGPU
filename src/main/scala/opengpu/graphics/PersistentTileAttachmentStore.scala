package opengpu.graphics

import chisel3._
import chisel3.util._

/** H1 single-owner tile SRAM. Draw completion never evicts the attachments.
  * Clear tags cover unwritten samples as well as samples touched by shading;
  * a store of a clear-start pass therefore exports the complete clear region.
  * A configurable transfer window retains external ownership until pass drain.
  */
class PersistentTileAttachmentStore(maxSampleCount: Int = 4,
  screenWidth: Int = 65535, screenHeight: Int = 65535,
  useSramBlackBoxes: Boolean = false, storeCredits: Int = 1) extends Module {
  require(storeCredits > 0 && storeCredits <= 32)
  require(Set(1, 2, 4)(maxSampleCount))
  require(screenWidth > 0 && screenWidth <= 65535 && screenHeight > 0 && screenHeight <= 65535)
  private val planeWords = 256 * maxSampleCount
  private val indexBits = log2Ceil(planeWords)
  val io = IO(new Bundle {
    val pass = new TileRenderPassPort
    val fragmentX = Input(UInt(16.W))
    val fragmentY = Input(UInt(16.W))
    val permit = Output(Bool())
    val producerDrained = Input(Bool())
    val omDrained = Input(Bool())
    val wordIndex = Input(UInt(10.W))
    val depthPlane = Input(Bool())
    val om = new Bundle {
      val req = Flipped(Decoupled(new OmMemoryRequest))
      val resp = Decoupled(new OmMemoryResponse)
    }
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val events = Output(new TileAttachmentEvents)
  })

  private val localMemory = Wire(new Bundle {
    val req = Decoupled(new OmMemoryRequest)
    val resp = Flipped(Decoupled(new OmMemoryResponse))
  })
  private val localResponseFault = Wire(Bool())
  private val transfersDrained = WireDefault(true.B)
  private val transferFault = WireDefault(false.B)
  private val resolveStoreEvent = Wire(Bool())
  private val transferOccupancy = Wire(UInt(6.W))

  private val Seq(idle, admitting, running, hitRead, missRequest, missResponse, flushing, flushRead,
    transferRequest, storeResponse, resolveSample, resolveRead, resolveWrite,
    resolveResponse, completed) = Enum(15)
  private val state = RegInit(idle)
  private val descriptor = Reg(new TileRenderPassDescriptor)
  private val closing = RegInit(false.B)
  private val status = RegInit(TilePassStatus.success.U(2.W))
  private val failed = status =/= TilePassStatus.success.U
  private val color = Module(new TileAttachmentSram(planeWords, useSramBlackBoxes))
  private val depth = Module(new TileAttachmentSram(planeWords, useSramBlackBoxes))
  for (memory <- Seq(color, depth)) {
    memory.io.readEnable := false.B
    memory.io.readAddress := 0.U
    memory.io.writeEnable := false.B
    memory.io.writeAddress := 0.U
    memory.io.writeData := 0.U
  }
  private val colorValid = RegInit(0.U(planeWords.W))
  private val depthValid = RegInit(0.U(planeWords.W))
  private val colorDirty = RegInit(0.U(planeWords.W))
  private val depthDirty = RegInit(0.U(planeWords.W))
  private val responseValid = RegInit(false.B)
  private val response = Reg(new OmMemoryResponse)
  private val missIndex = Reg(UInt(indexBits.W))
  private val missDepth = Reg(Bool())
  private val missAddress = Reg(UInt(32.W))
  private val missForResolve = RegInit(false.B)
  private val cursor = RegInit(0.U((indexBits + 2).W))
  private val storeAddress = Reg(UInt(32.W))
  private val storeData = Reg(UInt(32.W))
  private val tileColorBase = Reg(UInt(32.W))
  private val tileDepthBase = Reg(UInt(32.W))
  private val flushRowBase = Reg(UInt(32.W))
  private val resolveSourceRowBase = Reg(UInt(32.W))
  private val resolveDestinationRowBase = Reg(UInt(32.W))
  private val hitDepth = Reg(Bool())
  private val storeOffered = RegInit(false.B)
  private val resolveX = RegInit(0.U(4.W))
  private val resolveY = RegInit(0.U(4.W))
  private val resolveS = RegInit(0.U(2.W))
  private val resolveAcc = Reg(Vec(4, UInt(10.W)))
  private val clearEngine = Module(new TileAttachmentClearEngine)
  clearEngine.io.descriptor := descriptor
  clearEngine.io.colorTileBase := tileColorBase
  clearEngine.io.depthTileBase := tileDepthBase
  clearEngine.io.cancel := failed || io.pass.cancel
  private val clearAdmission = state === running && !closing && io.producerDrained &&
    io.omDrained && !responseValid && !io.om.req.valid
  clearEngine.io.command.valid := io.pass.clear.valid && clearAdmission
  clearEngine.io.command.bits := io.pass.clear.bits
  io.pass.clear.ready := clearEngine.io.command.ready && clearAdmission
  io.pass.clearDone <> clearEngine.io.done
  private val responseForClear = RegInit(false.B)

  io.pass.end.ready := state === running && !closing && !clearEngine.io.busy && !io.pass.clear.valid
  io.pass.active := state =/= idle && (state =/= completed || !transfersDrained)
  io.pass.descriptor := descriptor
  io.pass.completion.valid := state === completed && transfersDrained
  io.pass.completion.bits.identity := descriptor.identity
  io.pass.completion.bits.status := status
  io.om.resp.valid := responseValid && !responseForClear
  io.om.resp.bits := response
  clearEngine.io.mem.resp.valid := responseValid && responseForClear
  clearEngine.io.mem.resp.bits := response
  when(io.om.resp.fire || clearEngine.io.mem.resp.fire) { responseValid := false.B }
  when(io.pass.clearDone.fire && !io.pass.clearDone.bits && !failed && !io.pass.cancel) {
    status := TilePassStatus.invalidDescriptor.U
    closing := true.B
  }

  private val admission = Module(new TileRenderPassAdmission(maxSampleCount, screenWidth, screenHeight))
  admission.io.in.valid := io.pass.begin.valid && state === idle
  admission.io.in.bits := io.pass.begin.bits
  admission.io.out.ready := state === admitting
  io.pass.begin.ready := state === idle && admission.io.in.ready
  private val incoming = io.pass.begin.bits
  when(admission.io.out.fire) {
    tileColorBase := admission.io.out.bits.tileBases(0)
    tileDepthBase := admission.io.out.bits.tileBases(1)
    flushRowBase := admission.io.out.bits.tileBases(0)
    resolveSourceRowBase := admission.io.out.bits.tileBases(0)
    resolveDestinationRowBase := admission.io.out.bits.tileBases(2)
    when(failed || io.pass.cancel) { state := completed }
      .otherwise {
        status := Mux(admission.io.out.bits.valid, TilePassStatus.success.U, TilePassStatus.invalidDescriptor.U)
        state := Mux(admission.io.out.bits.valid, running, completed)
      }
  }

  when(io.pass.begin.fire) {
    descriptor := incoming
    closing := false.B
    colorValid := 0.U
    depthValid := 0.U
    colorDirty := 0.U
    depthDirty := 0.U
    cursor := 0.U
    storeOffered := false.B
    resolveX := 0.U
    resolveY := 0.U
    resolveS := 0.U
    for (c <- 0 until 4) resolveAcc(c) := 0.U
    status := TilePassStatus.success.U
    state := admitting
  }
  when(io.pass.end.fire) { closing := true.B }
  when(io.pass.cancel && io.pass.active) {
    closing := true.B
    when(!failed) { status := TilePassStatus.cancelled.U }
  }

  private val xEnd = descriptor.originX +& descriptor.tileWidth
  private val yEnd = descriptor.originY +& descriptor.tileHeight
  io.permit := state === running && !clearEngine.io.busy && io.fragmentX >= descriptor.originX &&
    io.fragmentX < xEnd && io.fragmentY >= descriptor.originY && io.fragmentY < yEnd

  private val operation = Wire(Decoupled(new OmMemoryRequest))
  operation.valid := Mux(clearEngine.io.busy, clearEngine.io.mem.req.valid,
    io.om.req.valid)
  operation.bits := Mux(clearEngine.io.busy, clearEngine.io.mem.req.bits, io.om.req.bits)
  // The legacy OM exports global coordinates modulo 16. Rebase them into
  // the admitted tile class, including 8x8 tiles in the upper/right half of
  // a 16x16 coordinate block. SRAM rows retain the common 16-pixel stride.
  private val omPixel = io.wordIndex >> descriptor.sampleMode
  private val omLocalX = (omPixel(3, 0) - descriptor.originX(3, 0))(3, 0)
  private val omLocalY = (omPixel(7, 4) - descriptor.originY(3, 0))(3, 0)
  private val omSample = io.wordIndex & ((1.U(3.W) << descriptor.sampleMode) - 1.U)
  private val localOmIndex = (Cat(omLocalY, omLocalX) << descriptor.sampleMode) + omSample
  private val operationIndex = Mux(clearEngine.io.busy, clearEngine.io.wordIndex, localOmIndex)
  private val operationDepth = Mux(clearEngine.io.busy, clearEngine.io.depthPlane, io.depthPlane)
  private val wordIndex = operationIndex(indexBits - 1, 0)
  private val selectedValid = Mux(operationDepth, depthValid(wordIndex), colorValid(wordIndex))
  private val selectedView = Mux(operationDepth, descriptor.depth, descriptor.color)
  private val taggedRead = !selectedValid && selectedView.loadOp =/= AttachmentLoadOp.load.U
  operation.ready := state === running && (!responseValid ||
    Mux(responseForClear, clearEngine.io.mem.resp.ready, io.om.resp.ready))
  io.om.req.ready := operation.ready && !clearEngine.io.busy
  clearEngine.io.mem.req.ready := operation.ready && clearEngine.io.busy
  when(operation.fire) {
    responseForClear := clearEngine.io.busy
    val pixel = operationIndex >> descriptor.sampleMode
    val x = pixel(3, 0)
    val y = pixel(7, 4)
    val expectedAddress = selectedView.base +
      (descriptor.originY +& y) * selectedView.rowPitch +
      (((descriptor.originX << descriptor.sampleMode) + operationIndex -
        ((y << 4) << descriptor.sampleMode)) << 2)
    assert(operationIndex < (256.U << descriptor.sampleMode), "tile sample index exceeds active capacity")
    assert(x < descriptor.tileWidth && y < descriptor.tileHeight, "OM request leaves the admitted tile extent")
    assert(operation.bits.addr === expectedAddress(31, 0), "OM request does not belong to its tile view")
    response.addr := operation.bits.addr
    response.write := operation.bits.write
    response.data := 0.U
    when(operation.bits.write) {
      when(!failed && !io.pass.cancel) {
        when(operationDepth) {
          depth.io.writeEnable := true.B
          depth.io.writeAddress := wordIndex
          depth.io.writeData := operation.bits.data
          depthValid := depthValid.bitSet(wordIndex, true.B)
          depthDirty := depthDirty.bitSet(wordIndex, true.B)
        }.otherwise {
          color.io.writeEnable := true.B
          color.io.writeAddress := wordIndex
          color.io.writeData := operation.bits.data
          colorValid := colorValid.bitSet(wordIndex, true.B)
          colorDirty := colorDirty.bitSet(wordIndex, true.B)
        }
      }
      responseValid := true.B
    }.elsewhen(failed || io.pass.cancel || taggedRead) {
      // A failed pass still drains old OM entries, but its completion reports
      // failure and it cannot publish these drain values as attachment stores.
      response.data := Mux(failed || io.pass.cancel, 0.U,
        Mux(selectedView.loadOp === AttachmentLoadOp.clear.U, selectedView.clearValue, 0.U))
      responseValid := true.B
    }.elsewhen(selectedValid) {
      color.io.readEnable := !operationDepth
      color.io.readAddress := wordIndex
      depth.io.readEnable := operationDepth
      depth.io.readAddress := wordIndex
      hitDepth := operationDepth
      state := hitRead
    }.otherwise {
      missAddress := operation.bits.addr
      missIndex := wordIndex
      missDepth := operationDepth
      missForResolve := false.B
      state := missRequest
    }
  }
  when(state === hitRead) {
    response.data := Mux(failed || io.pass.cancel, 0.U,
      Mux(hitDepth, depth.io.readData, color.io.readData))
    responseValid := true.B
    state := running
  }

  private val flushIndex = cursor(indexBits - 1, 0)
  private val flushDepth = cursor(indexBits)
  private val flushPixel = flushIndex >> descriptor.sampleMode
  private val flushX = flushPixel(3, 0)
  private val flushY = flushPixel(7, 4)
  private val flushView = Mux(flushDepth, descriptor.depth, descriptor.color)
  private val flushValid = Mux(flushDepth, depthValid(flushIndex), colorValid(flushIndex))
  private val flushDirty = Mux(flushDepth, depthDirty(flushIndex), colorDirty(flushIndex))
  private val flushInRange = flushIndex < (256.U << descriptor.sampleMode) &&
    flushX < descriptor.tileWidth && flushY < descriptor.tileHeight
  private val flushNeeded = flushView.store && flushInRange &&
    (flushDirty || flushView.loadOp === AttachmentLoadOp.clear.U)
  private val flushColumnSample = flushIndex & ((16.U(7.W) << descriptor.sampleMode) - 1.U)
  private val flushAddress = flushRowBase + (flushColumnSample << 2)
  private val samplesPerPixel = 1.U(3.W) << descriptor.sampleMode
  private val flushSample = flushIndex & (samplesPerPixel - 1.U)
  private val lastInRow = flushX +& 1.U === descriptor.tileWidth &&
    flushSample === samplesPerPixel - 1.U
  private val lastInPlane = lastInRow && flushY +& 1.U === descriptor.tileHeight
  private val nextCursor = Mux(lastInPlane,
    Mux(flushDepth, (2 * planeWords).U, planeWords.U),
    Mux(lastInRow,
      Mux(flushDepth, planeWords.U, 0.U) + (((flushY +& 1.U) << 4) << descriptor.sampleMode),
      cursor + 1.U))
  private def advanceFlush(): Unit = {
    cursor := nextCursor
    when(lastInPlane) { flushRowBase := tileDepthBase }
      .elsewhen(lastInRow) { flushRowBase := flushRowBase + descriptor.color.rowPitch }
  }
  private val resolveIndex = ((Cat(resolveY, resolveX) << descriptor.sampleMode) + resolveS)(indexBits - 1, 0)
  private val resolveAddress = resolveDestinationRowBase + (resolveX << 2)
  private val resolveSourceAddress = resolveSourceRowBase +
    (((resolveX << descriptor.sampleMode) + resolveS) << 2)
  private def accumulateResolve(word: UInt): Unit = {
    val sums = (0 until 4).map(c => resolveAcc(c) +& word(8 * c + 7, 8 * c))
    when(resolveS +& 1.U === samplesPerPixel) {
      val round = Mux(descriptor.sampleMode === 0.U, 0.U, samplesPerPixel >> 1)
      val channels = sums.map(sum => ((sum +& round) >> descriptor.sampleMode)(7, 0))
      storeData := Cat(channels.reverse)
      storeAddress := resolveAddress(31, 0)
      state := resolveWrite
    }.otherwise {
      for (c <- 0 until 4) resolveAcc(c) := sums(c)
      resolveS := resolveS + 1.U
      state := resolveSample
    }
  }

  localMemory.req.valid := state === missRequest ||
    (state === transferRequest && (!failed || storeOffered)) ||
    (state === resolveWrite && (!failed || storeOffered))
  localMemory.req.bits.write := state =/= missRequest
  localMemory.req.bits.addr := Mux(state === missRequest, missAddress, storeAddress)
  localMemory.req.bits.data := storeData
  localMemory.resp.ready := state === missResponse || state === storeResponse || state === resolveResponse
  when(state === missRequest && localMemory.req.fire) { state := missResponse }
  when(state === missResponse && localMemory.resp.fire) {
    assert(!localMemory.resp.bits.write && localMemory.resp.bits.addr === missAddress,
      "tile load response must retain transaction ownership")
    when(!localResponseFault && !failed && !io.pass.cancel) {
      when(missDepth) {
        depth.io.writeEnable := true.B
        depth.io.writeAddress := missIndex
        depth.io.writeData := localMemory.resp.bits.data
        depthValid := depthValid.bitSet(missIndex, true.B)
      }.otherwise {
        color.io.writeEnable := true.B
        color.io.writeAddress := missIndex
        color.io.writeData := localMemory.resp.bits.data
        colorValid := colorValid.bitSet(missIndex, true.B)
      }
    }
    when(missForResolve) {
      when(localResponseFault || failed || io.pass.cancel) { state := completed }
        .otherwise { accumulateResolve(localMemory.resp.bits.data) }
    }.otherwise {
      response.data := Mux(localResponseFault || failed || io.pass.cancel, 0.U, localMemory.resp.bits.data)
      responseValid := true.B
      state := running
    }
  }
  when(localMemory.resp.fire && localResponseFault) {
    status := TilePassStatus.memoryFault.U
    closing := true.B
  }
  when(state === running && closing && io.producerDrained && io.omDrained &&
    !responseValid && !operation.valid && !clearEngine.io.busy) {
    state := Mux(failed, completed, flushing)
  }
  when(state === flushing) {
    when(failed || io.pass.cancel) {
      state := completed
    }.elsewhen(cursor === (2 * planeWords).U) {
      state := Mux(descriptor.resolve, resolveSample, completed)
    }.elsewhen(!flushView.store) {
      cursor := Mux(flushDepth, (2 * planeWords).U, planeWords.U)
      flushRowBase := tileDepthBase
    }.elsewhen(!flushNeeded) {
      advanceFlush()
    }.otherwise {
      storeAddress := flushAddress(31, 0)
      when(flushValid) {
        color.io.readEnable := !flushDepth
        color.io.readAddress := flushIndex
        depth.io.readEnable := flushDepth
        depth.io.readAddress := flushIndex
        state := flushRead
      }.otherwise {
        storeData := flushView.clearValue
        state := transferRequest
      }
    }
  }
  when(state === flushRead) {
    storeData := Mux(flushDepth, depth.io.readData, color.io.readData)
    state := transferRequest
  }
  when(state === transferRequest) {
    // Keep an offered request stable through cancellation and backpressure.
    storeOffered := localMemory.req.valid && !localMemory.req.ready
    when(failed && !storeOffered) { state := completed }
      .elsewhen(localMemory.req.fire) {
        storeOffered := false.B
        state := storeResponse
      }
  }
  when(state === storeResponse && localMemory.resp.fire) {
    assert(localMemory.resp.bits.write && localMemory.resp.bits.addr === storeAddress,
      "tile store completion must acknowledge its outstanding address")
    advanceFlush()
    state := flushing
  }
  when(state === resolveSample) {
    when(failed || io.pass.cancel) { state := completed }
      .elsewhen(colorValid(resolveIndex)) {
        color.io.readEnable := true.B
        color.io.readAddress := resolveIndex
        state := resolveRead
      }.elsewhen(descriptor.color.loadOp =/= AttachmentLoadOp.load.U) {
        accumulateResolve(Mux(descriptor.color.loadOp === AttachmentLoadOp.clear.U, descriptor.color.clearValue, 0.U))
      }.otherwise {
        missAddress := resolveSourceAddress(31, 0)
        missIndex := resolveIndex
        missDepth := false.B
        missForResolve := true.B
        state := missRequest
      }
  }
  when(state === resolveRead) {
    when(failed || io.pass.cancel) { state := completed }
      .otherwise { accumulateResolve(color.io.readData) }
  }
  when(state === resolveWrite) {
    storeOffered := localMemory.req.valid && !localMemory.req.ready
    when(failed && !storeOffered) { state := completed }
      .elsewhen(localMemory.req.fire) {
        storeOffered := false.B
        storeAddress := localMemory.req.bits.addr
        state := resolveResponse
      }
  }
  when(state === resolveResponse && localMemory.resp.fire) {
    assert(localMemory.resp.bits.write && localMemory.resp.bits.addr === storeAddress,
      "resolve completion must acknowledge its outstanding destination")
    for (c <- 0 until 4) resolveAcc(c) := 0.U
    resolveS := 0.U
    when(resolveX +& 1.U === descriptor.tileWidth) {
      resolveX := 0.U
      resolveY := resolveY + 1.U
      resolveSourceRowBase := resolveSourceRowBase + descriptor.color.rowPitch
      resolveDestinationRowBase := resolveDestinationRowBase + descriptor.resolveRowPitch
      state := Mux(resolveY +& 1.U === descriptor.tileHeight, completed, resolveSample)
    }.otherwise {
      resolveX := resolveX + 1.U
      state := resolveSample
    }
  }
  if (storeCredits > 1) {
    val transfer = Module(new TileAttachmentTransfer(storeCredits))
    transfer.io.begin := io.pass.begin.fire
    transfer.io.abort := failed || io.pass.cancel
    transfer.io.resolve := state === resolveWrite
    transfer.io.local.req <> localMemory.req
    localMemory.resp <> transfer.io.local.resp
    localResponseFault := transfer.io.local.responseFault
    io.mem.req <> transfer.io.mem.req
    transfer.io.mem.resp <> io.mem.resp
    transfer.io.mem.responseFault := io.pass.memoryFault
    transfersDrained := transfer.io.drained
    transferFault := transfer.io.fault
    resolveStoreEvent := transfer.io.resolveStore
    transferOccupancy := transfer.io.occupancy
  } else {
    io.mem.req <> localMemory.req
    localMemory.resp <> io.mem.resp
    localResponseFault := io.pass.memoryFault
    resolveStoreEvent := io.mem.req.fire && state === resolveWrite
    transferOccupancy := Mux(state === missResponse || state === storeResponse || state === resolveResponse, 1.U, 0.U)
  }
  // A real write may fail after its private preparation acknowledgement.
  // Keep completion hidden until every external transfer has drained.
  when(transferFault) {
    status := TilePassStatus.memoryFault.U
    closing := true.B
  }
  when(io.pass.completion.fire) { state := idle }

  io.events.resident := io.pass.active
  io.events.admitted := admission.io.out.fire && admission.io.out.bits.valid && !failed && !io.pass.cancel
  io.events.completed := io.pass.completion.fire
  io.events.loadBytes := Mux(io.mem.req.fire && !io.mem.req.bits.write, 4.U, 0.U)
  io.events.storeBytes := Mux(io.mem.req.fire && io.mem.req.bits.write, 4.U, 0.U)
  io.events.resolveBytes := Mux(resolveStoreEvent, 4.U, 0.U)
  io.events.transferOccupancy := transferOccupancy
  io.events.clearRead := operation.fire && !operation.bits.write && taggedRead &&
    selectedView.loadOp === AttachmentLoadOp.clear.U && !failed
  io.events.attachmentStall := operation.valid && !operation.ready
}
