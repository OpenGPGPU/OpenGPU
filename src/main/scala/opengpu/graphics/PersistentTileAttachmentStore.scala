package opengpu.graphics

import chisel3._
import chisel3.util._

class PersistentTileAttachmentStoreIO(externalTransfers: Boolean=false,bankedSamples: Boolean=false) extends Bundle {
  /** Optional enclosing line-buffer ownership; not a producer fault input. */
  val externalDrained=if(externalTransfers) Some(Input(Bool())) else None
  val externalWriteFault=if(externalTransfers) Some(Input(Bool())) else None
  val quad=if(bankedSamples) Some(Flipped(Decoupled(new TileSampleQuad))) else None
  val quadCompletion=if(bankedSamples) Some(Decoupled(new TileSampleQuadCompletion)) else None
  val acceptedSamples=if(bankedSamples) Some(Output(UInt(3.W))) else None
  val committedSamples=if(bankedSamples) Some(Output(UInt(3.W))) else None
  val pass = new TileRenderPassPort
  val fragmentX = Input(UInt(16.W))
  val fragmentY = Input(UInt(16.W))
  val permit = Output(Bool())
  val producerDrained = Input(Bool())
  val omDrained = Input(Bool())
  /** Accepted shader/staging fault pulse for this owner, not a prior owner's sticky status. */
  val executionFault = Input(Bool())
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
}

private class TileScalarWordRequest extends Bundle {
  val memory=new OmMemoryRequest
  val index=UInt(10.W)
  val depth=Bool()
}

/** H1 single-owner tile SRAM. Draw completion never evicts the attachments.
  * Clear tags cover unwritten samples as well as samples touched by shading;
  * a store of a clear-start pass therefore exports the complete clear region.
  * A configurable transfer window retains external ownership until pass drain.
  */
class PersistentTileAttachmentStore(maxSampleCount: Int = 4,
  screenWidth: Int = 65535, screenHeight: Int = 65535,
  useSramBlackBoxes: Boolean = false, storeCredits: Int = 1,
  externalTransfers: Boolean = false,bankedSamples: Boolean = false) extends Module {
  require(storeCredits > 0 && storeCredits <= 32)
  require(Set(1, 2, 4)(maxSampleCount))
  require(screenWidth > 0 && screenWidth <= 65535 && screenHeight > 0 && screenHeight <= 65535)
  private val planeWords = 256 * maxSampleCount
  private val indexBits = log2Ceil(planeWords)
  val io = IO(new PersistentTileAttachmentStoreIO(externalTransfers,bankedSamples))

  private val localMemory = Wire(new Bundle {
    val req = Decoupled(new OmMemoryRequest)
    val resp = Flipped(Decoupled(new OmMemoryResponse))
  })
  private val localResponseFault = Wire(Bool())
  private val transfersDrained = WireDefault(true.B)
  private val transferFault = WireDefault(false.B)
  private val resolveStoreEvent = Wire(Bool())
  private val transferOccupancy = Wire(UInt(6.W))
  private val allTransfersDrained=transfersDrained && io.externalDrained.getOrElse(true.B)

  private val Seq(idle, admitting, running, hitRead, missRequest, missResponse, flushing,
    resolveSample, resolveRead, resolveWrite, resolveResponse, completed,
    quadMissProducts,quadMissRow,quadMissBase,quadMissAddress) = Enum(16)
  private val state = RegInit(idle)
  private val descriptor = Reg(new TileRenderPassDescriptor)
  private val closing = RegInit(false.B)
  private val status = RegInit(TilePassStatus.success.U(TilePassStatus.width.W))
  private val failed = status =/= TilePassStatus.success.U
  private val abortNow = io.pass.cancel || io.executionFault
  private val colorIo=if(!bankedSamples) Module(new TileAttachmentSram(planeWords,useSramBlackBoxes)).io
    else Wire(new TileAttachmentSramIO(planeWords))
  private val depthIo=if(!bankedSamples) Module(new TileAttachmentSram(planeWords,useSramBlackBoxes)).io
    else Wire(new TileAttachmentSramIO(planeWords))
  private val quadDrained=WireDefault(true.B)
  private val scalarDrained=WireDefault(true.B)
  private val quadColorMask=WireDefault(0.U(planeWords.W))
  private val quadDepthMask=WireDefault(0.U(planeWords.W))
  private val quadFillColor=WireDefault(0.U(planeWords.W))
  private val quadFillDepth=WireDefault(0.U(planeWords.W))
  private val export = Module(new TileAttachmentExport)
  export.io.begin := io.pass.begin.fire
  export.io.colorData := colorIo.readData
  export.io.depthData := depthIo.readData
  for (memory <- Seq(colorIo, depthIo)) {
    memory.readEnable := false.B
    memory.readAddress := 0.U
    memory.writeEnable := false.B
    memory.writeAddress := 0.U
    memory.writeData := 0.U
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
  private val missForQuad = RegInit(false.B)
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
  clearEngine.io.cancel := failed || abortNow
  private val clearAdmission = state === running && !closing && io.producerDrained &&
    io.omDrained && quadDrained && scalarDrained && !responseValid && !io.om.req.valid
  clearEngine.io.command.valid := io.pass.clear.valid && clearAdmission
  clearEngine.io.command.bits := io.pass.clear.bits
  io.pass.clear.ready := clearEngine.io.command.ready && clearAdmission
  io.pass.clearDone <> clearEngine.io.done
  private val responseForClear = RegInit(false.B)

  io.pass.end.ready := state === running && !closing && !clearEngine.io.busy && !io.pass.clear.valid
  io.pass.active := state =/= idle && (state =/= completed || !allTransfersDrained)
  io.pass.descriptor := descriptor
  io.pass.completion.valid := state === completed && allTransfersDrained
  io.pass.completion.bits.identity := descriptor.identity
  io.pass.completion.bits.status := status
  io.om.resp.valid := responseValid && !responseForClear
  io.om.resp.bits := response
  clearEngine.io.mem.resp.valid := responseValid && responseForClear
  clearEngine.io.mem.resp.bits := response
  when(io.om.resp.fire || clearEngine.io.mem.resp.fire) { responseValid := false.B }
  when(io.pass.clearDone.fire && !io.pass.clearDone.bits && !failed && !abortNow) {
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
    when(failed || abortNow) { state := completed }
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
  when(io.executionFault && io.pass.active) {
    closing := true.B
    when(!failed || status === TilePassStatus.cancelled.U) {
      status := TilePassStatus.executionFault.U
    }
  }

  private val xEnd = descriptor.originX +& descriptor.tileWidth
  private val yEnd = descriptor.originY +& descriptor.tileHeight
  io.permit := state === running && !clearEngine.io.busy && io.fragmentX >= descriptor.originX &&
    io.fragmentX < xEnd && io.fragmentY >= descriptor.originY && io.fragmentY < yEnd

  // The legacy OM exports global coordinates modulo 16. Rebase them into
  // the admitted tile class, including 8x8 tiles in the upper/right half of
  // a 16x16 coordinate block. SRAM rows retain the common 16-pixel stride.
  private val omPixel = io.wordIndex >> descriptor.sampleMode
  private val omLocalX = (omPixel(3, 0) - descriptor.originX(3, 0))(3, 0)
  private val omLocalY = (omPixel(7, 4) - descriptor.originY(3, 0))(3, 0)
  private val omSample = io.wordIndex & ((1.U(3.W) << descriptor.sampleMode) - 1.U)
  private val localOmIndex = (Cat(omLocalY, omLocalX) << descriptor.sampleMode) + omSample
  private val scalar=Wire(Decoupled(new TileScalarWordRequest))
  if(bankedSamples) {
    val request=Module(new Queue(new TileScalarWordRequest,1))
    val admit=state===running && quadDrained && !clearEngine.io.busy
    request.io.enq.valid:=io.om.req.valid && admit
    request.io.enq.bits.memory:=io.om.req.bits
    request.io.enq.bits.index:=localOmIndex
    request.io.enq.bits.depth:=io.depthPlane
    io.om.req.ready:=request.io.enq.ready && admit
    scalar <> request.io.deq
    scalarDrained:= !request.io.deq.valid
  }else {
    scalar.valid:=io.om.req.valid;scalar.bits.memory:=io.om.req.bits
    scalar.bits.index:=localOmIndex;scalar.bits.depth:=io.depthPlane
    io.om.req.ready:=scalar.ready
  }
  private val operation=Wire(Decoupled(new OmMemoryRequest))
  operation.valid:=Mux(clearEngine.io.busy,clearEngine.io.mem.req.valid,scalar.valid)
  operation.bits:=Mux(clearEngine.io.busy,clearEngine.io.mem.req.bits,scalar.bits.memory)
  private val operationIndex = Mux(clearEngine.io.busy, clearEngine.io.wordIndex, scalar.bits.index)
  private val operationDepth = Mux(clearEngine.io.busy, clearEngine.io.depthPlane, scalar.bits.depth)
  private val wordIndex = operationIndex(indexBits - 1, 0)
  private val selectedValid = Mux(operationDepth, depthValid(wordIndex), colorValid(wordIndex))
  private val selectedView = Mux(operationDepth, descriptor.depth, descriptor.color)
  private val taggedRead = !selectedValid && selectedView.loadOp =/= AttachmentLoadOp.load.U
  operation.ready := state === running && quadDrained && (!responseValid ||
    Mux(responseForClear, clearEngine.io.mem.resp.ready, io.om.resp.ready))
  scalar.ready := operation.ready && !clearEngine.io.busy
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
      when(!failed && !abortNow) {
        when(operationDepth) {
          depthIo.writeEnable := true.B
          depthIo.writeAddress := wordIndex
          depthIo.writeData := operation.bits.data
          depthValid := depthValid.bitSet(wordIndex, true.B)
          depthDirty := depthDirty.bitSet(wordIndex, true.B)
        }.otherwise {
          colorIo.writeEnable := true.B
          colorIo.writeAddress := wordIndex
          colorIo.writeData := operation.bits.data
          colorValid := colorValid.bitSet(wordIndex, true.B)
          colorDirty := colorDirty.bitSet(wordIndex, true.B)
        }
      }
      responseValid := true.B
    }.elsewhen(failed || abortNow || taggedRead) {
      // A failed pass still drains old OM entries, but its completion reports
      // failure and it cannot publish these drain values as attachment stores.
      response.data := Mux(failed || abortNow, 0.U,
        Mux(selectedView.loadOp === AttachmentLoadOp.clear.U, selectedView.clearValue, 0.U))
      responseValid := true.B
    }.elsewhen(selectedValid) {
      colorIo.readEnable := !operationDepth
      colorIo.readAddress := wordIndex
      depthIo.readEnable := operationDepth
      depthIo.readAddress := wordIndex
      hitDepth := operationDepth
      state := hitRead
    }.otherwise {
      missAddress := operation.bits.addr
      missIndex := wordIndex
      missDepth := operationDepth
      missForResolve := false.B
      missForQuad := false.B
      state := missRequest
    }
  }
  when(state === hitRead) {
    response.data := Mux(failed || abortNow, 0.U,
      Mux(hitDepth, depthIo.readData, colorIo.readData))
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

  private val exporting = state === flushing
  export.io.prepare.valid := exporting && !failed && !abortNow &&
    cursor =/= (2 * planeWords).U && flushNeeded
  export.io.prepare.bits.address := flushAddress(31,0)
  export.io.prepare.bits.clearValue := flushView.clearValue
  export.io.prepare.bits.fromSram := flushValid
  export.io.prepare.bits.depth := flushDepth
  export.io.local.req.ready := exporting && localMemory.req.ready
  export.io.local.resp.valid := exporting && localMemory.resp.valid
  export.io.local.resp.bits := localMemory.resp.bits
  localMemory.req.valid := Mux(exporting,export.io.local.req.valid,
    state === missRequest || (state === resolveWrite && (!failed || storeOffered)))
  localMemory.req.bits := export.io.local.req.bits
  when(!exporting) {
    localMemory.req.bits.write := state =/= missRequest
    localMemory.req.bits.addr := Mux(state === missRequest,missAddress,storeAddress)
    localMemory.req.bits.data := storeData
  }
  localMemory.resp.ready := Mux(exporting,export.io.local.resp.ready,
    state === missResponse || state === resolveResponse)
  when(state === missRequest && localMemory.req.fire) { state := missResponse }
  // Scalar/resolve fills have an unconditional sink in missResponse. Keep
  // their SRAM write controls independent of the quad refill ready path.
  when(state===missResponse && !missForQuad && localMemory.resp.valid &&
    !localResponseFault && !failed && !abortNow) {
    when(missDepth) {
      depthIo.writeEnable:=true.B;depthIo.writeAddress:=missIndex
      depthIo.writeData:=localMemory.resp.bits.data
      depthValid:=depthValid.bitSet(missIndex,true.B)
    }.otherwise {
      colorIo.writeEnable:=true.B;colorIo.writeAddress:=missIndex
      colorIo.writeData:=localMemory.resp.bits.data
      colorValid:=colorValid.bitSet(missIndex,true.B)
    }
  }
  when(state === missResponse && localMemory.resp.fire) {
    assert(!localMemory.resp.bits.write && localMemory.resp.bits.addr === missAddress,
      "tile load response must retain transaction ownership")
    when(missForQuad) { state:=running }
      .elsewhen(missForResolve) {
      when(localResponseFault || failed || abortNow) { state := completed }
        .otherwise { accumulateResolve(localMemory.resp.bits.data) }
    }.otherwise {
      response.data := Mux(localResponseFault || failed || abortNow, 0.U, localMemory.resp.bits.data)
      responseValid := true.B
      state := running
    }
  }
  when(localMemory.resp.fire && localResponseFault) {
    status := TilePassStatus.memoryFault.U
    closing := true.B
  }
  when(state === running && closing && io.producerDrained && io.omDrained && quadDrained && scalarDrained &&
    !responseValid && !operation.valid && !io.om.req.valid && !clearEngine.io.busy) {
    state := Mux(failed, completed, flushing)
  }
  when(state === flushing) {
    when(failed || abortNow) {
      when(export.io.drained) { state := completed }
    }.elsewhen(cursor === (2 * planeWords).U) {
      when(export.io.drained) { state := Mux(descriptor.resolve, resolveSample, completed) }
    }.elsewhen(!flushView.store) {
      cursor := Mux(flushDepth, (2 * planeWords).U, planeWords.U)
      flushRowBase := tileDepthBase
    }.elsewhen(!flushNeeded) {
      advanceFlush()
    }.elsewhen(export.io.prepare.fire) {
      when(flushValid) {
        colorIo.readEnable := !flushDepth
        colorIo.readAddress := flushIndex
        depthIo.readEnable := flushDepth
        depthIo.readAddress := flushIndex
      }
      advanceFlush()
    }
  }
  when(state === resolveSample) {
    when(failed || abortNow) { state := completed }
      .elsewhen(colorValid(resolveIndex)) {
        colorIo.readEnable := true.B
        colorIo.readAddress := resolveIndex
        state := resolveRead
      }.elsewhen(descriptor.color.loadOp =/= AttachmentLoadOp.load.U) {
        accumulateResolve(Mux(descriptor.color.loadOp === AttachmentLoadOp.clear.U, descriptor.color.clearValue, 0.U))
      }.otherwise {
        missAddress := resolveSourceAddress(31, 0)
        missIndex := resolveIndex
        missDepth := false.B
        missForResolve := true.B
        missForQuad := false.B
        state := missRequest
      }
  }
  when(state === resolveRead) {
    when(failed || abortNow) { state := completed }
      .otherwise { accumulateResolve(colorIo.readData) }
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
  private val transfer = Module(new TileAttachmentTransfer(storeCredits))
  transfer.io.begin := io.pass.begin.fire
  transfer.io.abort := failed || abortNow
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

  // A real write may fail after its private preparation acknowledgement.
  // Keep completion hidden until every external transfer has drained.
  when(transferFault) {
    status := TilePassStatus.memoryFault.U
    closing := true.B
  }
  when(io.externalWriteFault.getOrElse(false.B)) {
    assert(!io.externalDrained.getOrElse(true.B),"a late write fault must retain its enclosing owner")
    when(io.pass.active) { status:=TilePassStatus.memoryFault.U; closing:=true.B }
  }
  when(io.pass.completion.fire) { state := idle }

  if(bankedSamples) {
    val banks=Module(new BankedTileSampleSram(useSramBlackBoxes,writePorts=5,legacyPort=true))
    val merge=Module(new TileSampleQuadMerge)
    val admitted=admission.io.out.fire && admission.io.out.bits.valid && !failed && !abortNow
    banks.io.begin:=admitted;banks.io.sampleMode:=descriptor.sampleMode
    merge.io.begin:=admitted;merge.io.sampleMode:=descriptor.sampleMode
    merge.io.cancel:=failed || abortNow
    quadDrained:=merge.io.drained && banks.io.drained
    val permitQuad=state===running && !closing && !failed && !abortNow &&
      !clearEngine.io.busy && !io.pass.clear.valid && !io.pass.end.valid &&
      !io.om.req.valid && scalarDrained && !responseValid
    merge.io.in.valid:=io.quad.get.valid && permitQuad
    merge.io.in.bits:=io.quad.get.bits
    io.quad.get.ready:=merge.io.in.ready && permitQuad
    io.quadCompletion.get <> merge.io.out
    io.acceptedSamples.get:=merge.io.acceptedSamples
    io.committedSamples.get:=merge.io.committedSamples
    when(merge.io.in.fire) {
      for(p <- 0 until 4)when(merge.io.in.bits.mask(p)) {
        val pixel=merge.io.in.bits.index(p)>>descriptor.sampleMode
        assert(pixel(3,0)<descriptor.tileWidth && pixel(7,4)<descriptor.tileHeight,
          "quad sample leaves its admitted tile extent")
      }
    }
    val legacy=banks.io.legacy.get
    legacy.readEnable:=colorIo.readEnable || depthIo.readEnable
    legacy.readIndex:=Mux(depthIo.readEnable,depthIo.readAddress,colorIo.readAddress)
    colorIo.readData:=legacy.colorData;depthIo.readData:=legacy.depthStencilData
    legacy.writeEnable:=colorIo.writeEnable || depthIo.writeEnable
    legacy.writeIndex:=Mux(depthIo.writeEnable,depthIo.writeAddress,colorIo.writeAddress)
    legacy.colorEnable:=colorIo.writeEnable;legacy.depthStencilEnable:=depthIo.writeEnable
    legacy.color:=colorIo.writeData;legacy.depthStencil:=depthIo.writeData
    when(legacy.readEnable || legacy.writeEnable) {
      assert(merge.io.drained,"scalar/clear/export SRAM access must drain the quad owner")
    }
    class QuadMiss extends BankedTileSampleRead {val depth=Bool()}
    val misses=Module(new RRArbiter(new QuadMiss,4))
    for(p <- 0 until 4) {
      val read=merge.io.read(p)
      val index=read.bits.index(indexBits-1,0)
      val colorMiss= !colorValid(index) && descriptor.color.loadOp===AttachmentLoadOp.load.U
      val depthMiss= !depthValid(index) && descriptor.depth.loadOp===AttachmentLoadOp.load.U
      val needsLoad=(colorMiss || depthMiss) && !failed && !abortNow
      banks.io.read(p).valid:=read.valid && !needsLoad
      banks.io.read(p).bits:=read.bits
      read.ready:=banks.io.read(p).ready && !needsLoad
      misses.io.in(p).valid:=read.valid && needsLoad
      misses.io.in(p).bits.index:=read.bits.index
      misses.io.in(p).bits.ticket:=read.bits.ticket
      misses.io.in(p).bits.depth:= !colorMiss
      merge.io.response(p).valid:=banks.io.response(p).valid
      merge.io.response(p).bits:=banks.io.response(p).bits
      val responseIndex=banks.io.response(p).bits.index(indexBits-1,0)
      val packedColor=Mux(colorValid(responseIndex),banks.io.response(p).bits.color,
        Mux(descriptor.color.loadOp===AttachmentLoadOp.clear.U,descriptor.color.clearValue,0.U))
      merge.io.response(p).bits.color:=PixelFormat.decode(packedColor,descriptor.colorFormat)
      merge.io.response(p).bits.depthStencil:=Mux(depthValid(responseIndex),banks.io.response(p).bits.depthStencil,
        Mux(descriptor.depth.loadOp===AttachmentLoadOp.clear.U,descriptor.depth.clearValue,0.U))
      banks.io.response(p).ready:=merge.io.response(p).ready
      banks.io.write(p) <> merge.io.write(p)
      banks.io.write(p).bits.color:=PixelFormat.encode(merge.io.write(p).bits.color,descriptor.colorFormat)
    }
    // A newer scalar request waits for quad drain. It must not prevent the
    // older quad's refill, otherwise both owners would wait on each other.
    misses.io.out.ready:=state===running && !responseValid && !clearEngine.io.busy && !failed && !abortNow
    when(misses.io.out.fire) {
      missIndex:=misses.io.out.bits.index;missDepth:=misses.io.out.bits.depth
      missForQuad:=true.B;missForResolve:=false.B
      state:=quadMissProducts
    }
    val rowLow=Reg(UInt(20.W));val rowHigh=Reg(UInt(20.W))
    val rowOffset=Reg(UInt(32.W));val baseRow=Reg(UInt(32.W));val columnByte=Reg(UInt(8.W))
    when(state===quadMissProducts) {
      val pixel=missIndex>>descriptor.sampleMode
      val view=Mux(missDepth,descriptor.depth,descriptor.color)
      val sample=missIndex&((1.U(3.W)<<descriptor.sampleMode)-1.U)
      rowLow:=pixel(7,4)*view.rowPitch(15,0)
      rowHigh:=pixel(7,4)*view.rowPitch(31,16)
      columnByte:=((pixel(3,0)<<descriptor.sampleMode)+sample)<<2
      state:=Mux(failed || abortNow,running,quadMissRow)
    }
    when(state===quadMissRow) {
      rowOffset:=rowLow+(rowHigh<<16)
      state:=Mux(failed || abortNow,running,quadMissBase)
    }
    when(state===quadMissBase) {
      baseRow:=Mux(missDepth,tileDepthBase,tileColorBase)+rowOffset
      state:=Mux(failed || abortNow,running,quadMissAddress)
    }
    when(state===quadMissAddress) {
      missAddress:=baseRow+columnByte
      state:=Mux(failed || abortNow,running,missRequest)
    }
    val fill=banks.io.write(4)
    val fillOffered=RegInit(false.B)
    val fillAllowed= !localResponseFault && !failed && !abortNow
    fill.valid:=state===missResponse && missForQuad && localMemory.resp.valid &&
      (fillOffered || fillAllowed)
    fill.bits.index:=missIndex;fill.bits.color:=localMemory.resp.bits.data
    fill.bits.depthStencil:=localMemory.resp.bits.data
    fill.bits.colorEnable:= !missDepth;fill.bits.depthStencilEnable:=missDepth
    when(fill.valid && !fill.ready){fillOffered:=true.B}
    when(fill.fire){fillOffered:=false.B}
    val fillWasHeld=RegNext(fill.valid && !fill.ready,false.B)
    val heldFill=RegEnable(fill.bits.asUInt,fill.valid && !fill.ready)
    when(fillWasHeld) {
      assert(fill.valid && fill.bits.asUInt===heldFill,"offered quad refill must survive cancellation until SRAM commit")
    }
    when(state===missResponse && missForQuad) {
      // An already offered refill retains its external response and data until
      // actual SRAM commit, even if cancel/fault arrives while the bank stalls.
      localMemory.resp.ready:=Mux(fillOffered,fill.ready,!fillAllowed || fill.ready)
    }
    quadColorMask:=(0 until 4).map(p=>Mux(merge.io.write(p).fire && merge.io.write(p).bits.colorEnable,
      UIntToOH(merge.io.write(p).bits.index(indexBits-1,0),planeWords),0.U(planeWords.W))).reduce(_|_)
    quadDepthMask:=(0 until 4).map(p=>Mux(merge.io.write(p).fire && merge.io.write(p).bits.depthStencilEnable,
      UIntToOH(merge.io.write(p).bits.index(indexBits-1,0),planeWords),0.U(planeWords.W))).reduce(_|_)
    quadFillColor:=Mux(fill.fire && !missDepth,UIntToOH(missIndex,planeWords),0.U(planeWords.W))
    quadFillDepth:=Mux(fill.fire && missDepth,UIntToOH(missIndex,planeWords),0.U(planeWords.W))
    when((quadColorMask|quadFillColor).orR) {colorValid:=colorValid|quadColorMask|quadFillColor}
    when((quadDepthMask|quadFillDepth).orR) {depthValid:=depthValid|quadDepthMask|quadFillDepth}
    when(quadColorMask.orR){colorDirty:=colorDirty|quadColorMask}
    when(quadDepthMask.orR){depthDirty:=depthDirty|quadDepthMask}
  }

  io.events.resident := io.pass.active
  io.events.admitted := admission.io.out.fire && admission.io.out.bits.valid && !failed && !abortNow
  io.events.completed := io.pass.completion.fire
  io.events.loadBytes := Mux(io.mem.req.fire && !io.mem.req.bits.write, 4.U, 0.U)
  io.events.storeBytes := Mux(io.mem.req.fire && io.mem.req.bits.write, 4.U, 0.U)
  io.events.resolveBytes := Mux(resolveStoreEvent, 4.U, 0.U)
  io.events.transferOccupancy := transferOccupancy
  // The wide tag lookup already serves the local response path. Register its
  // diagnostic pulse so it does not also consume the external I/O budget.
  io.events.clearRead := RegNext(operation.fire && !operation.bits.write && taggedRead &&
    selectedView.loadOp === AttachmentLoadOp.clear.U && !failed && !abortNow, false.B)
  io.events.attachmentStall := operation.valid && !operation.ready
}
