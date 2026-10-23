package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest

object TexSampleUnit {
  /** First active lane plus one, retaining the terminal lane for an empty mask.
    * Encoding a shifted mask avoids a carry chain after priority selection.
    */
  private[graphics] def laneAfterFirst(mask: UInt): UInt =
    PriorityEncoder(Cat(mask, 0.U(1.W)))
}

/** Execute-side backend for the `tex.sample rd, rs1, rs2` custom instruction
  * (opengpu graphics ISA extension, custom-0 space).
  *
  * The core decodes the instruction and dispatches it through the system
  * path with the operand values already read (`rs1Data` = u, `rs2Data` = v,
  * both unsigned Q16.16).  This unit drives the fixed-function sampler
  * ([[TextureUnit]]) and returns the packed RGBA8888 texel word through the
  * scalar commit path (writeRd, rd from the instruction, nextPc = pc + 4),
  * so the scoreboard's rd reservation is released by the ordinary commit.
  *
  * The scalar instruction is warp-uniform. `vtex.sample` supplies one Q16.16
  * coordinate pair per vector lane; this unit serializes the active lanes
  * through the one physical sampler and preserves inactive destination lanes.
  * A fixed single-texel mip is sampled once for all active vector lanes.
  */
class TexSampleUnit(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig(),
  coordinatePolicyInput: Boolean = false
) extends Module {
  def this(config: GpuConfig, gfxConfig: GraphicsConfig) = this(config, gfxConfig, false)
  require(config.lanes < 4 || config.lanes % 4 == 0,
    "texture vectors must contain complete derivative quads")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val vectorIn = Flipped(Decoupled(new VectorTextureRequest(config)))
    val vectorCommit = Decoupled(new VectorCommitRequest(config))
    val texFormat = Input(UInt(PixelFormat.width.W))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val wrapClamp = Input(Bool())
    val coordinatePolicy = if (coordinatePolicyInput) Some(Input(new TextureCoordinatePolicy)) else None
    /** Meaningful with scalar/vector commit.valid. Owners must handle faults
      * before normal completion; faulted instructions suppress destination writes.
      */
    val coordinateFault = if (coordinatePolicyInput) Some(Output(Bool())) else None
    val texMaxLevel = Input(UInt(4.W))
    /** Signed integer bias applied after gradient LOD selection. */
    val lodBias = Input(SInt(5.W))
    /** Inclusive lower clamp; texMaxLevel is the inclusive upper clamp. */
    val minLevel = Input(UInt(4.W))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val sampler = Module(new TextureUnit(gfxConfig, wordCacheEntries = 8, allowPrepared = true, overlapPrepared = true,
    coordinatePolicyInput = coordinatePolicyInput))

  // Reuse is limited to one instruction. A later operation may observe
  // changed texture memory, format, binding, or address translation.
  sampler.io.invalidateCache.get := io.vectorIn.fire || io.in.fire
  sampler.io.texFormat := io.texFormat
  sampler.io.texBase := io.texBase
  sampler.io.texWidth := io.texWidth
  sampler.io.texHeight := io.texHeight
  sampler.io.wrapMode := io.wrapClamp
  sampler.io.texMaxLevel := io.texMaxLevel
  io.mem.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.mem.resp

  // Reduce differences independently per texture axis before multiplying:
  // max(d0*w, ..., d3*w) = max(d0, ..., d3)*w for unsigned full products.
  // Two 32-bit max stages replace two 46-bit post-multiply max stages;
  // keeping the split multiply and final axis max preserves request latency.
  private val sIdle :: sGrad :: sMaxPair :: sMax :: sMul :: sAdd :: sMax2 :: sLod :: sFrac :: sSample :: sCommit :: Nil =
    Enum(11)
  private val state = RegInit(sIdle)

  private val warpIdReg = RegInit(0.U(config.warpIdWidth.W))
  private val rdReg = RegInit(0.U(5.W))
  private val pcReg = RegInit(0.U(config.xLen.W))
  private val activeMaskReg = RegInit(0.U(config.lanes.W))
  private val dataReg = RegInit(0.U(32.W))
  private val vectorModeReg = RegInit(false.B)
  private val broadcastTexelReg = RegInit(false.B)
  private val executionMaskReg = RegInit(0.U(config.lanes.W))
  private val vectorDataReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val uVectorReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val vVectorReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val laneWidth = math.max(1, log2Ceil(config.lanes))
  private val laneReg = RegInit(0.U(laneWidth.W))
  // Track prepared issue independently from retirement: one result may be
  // held while its successor fetches. Derive the pointer and terminal bound
  // from shader lane count, including VLEN1.
  private val requestLane = RegInit(0.U(log2Ceil(config.lanes + 1).W))
  private val requestIndex = requestLane(laneWidth - 1, 0)
  private val preparedRequestActive = requestLane < config.lanes.U && executionMaskReg(requestIndex)
  private val coordinatePipeline = Module(new TextureCoordinatePipeline(laneWidth, coordinatePolicyInput))
  private val preparedModeReg = RegInit(false.B)
  private val firstSampleReg = RegInit(true.B)
  private val prepareLane = RegInit(0.U(log2Ceil(config.lanes + 1).W))
  private val texBaseReg = RegInit(0.U(32.W))
  private val texFormatReg = RegInit(0.U(PixelFormat.width.W))
  private val wrapClampReg = RegInit(false.B)
  private val policyReg = if (coordinatePolicyInput) Some(Reg(new TextureCoordinatePolicy)) else None
  private val coordinateFaultReg = if (coordinatePolicyInput) Some(RegInit(false.B)) else None
  io.coordinateFault.foreach(_ := coordinateFaultReg.get)

  // Latch the coordinates at accept: the upstream issued-instruction bits are
  // only stable through the handshake cycle (same race class as M4b/M5b).
  private val uReg = RegInit(0.U(32.W))
  private val vReg = RegInit(0.U(32.W))
  private val mipLevelReg = RegInit(0.U(4.W))
  private val mipFracReg = RegInit(0.U(8.W))
  private val rhoReg = RegInit(0.U(46.W))
  private val rhoNormalReg = RegInit(0.U(46.W))
  private val lodIsClampedReg = RegInit(false.B)
  private val lodBiasReg = RegInit(0.S(5.W))
  private val minLevelReg = RegInit(0.U(4.W))
  private val maxLevelReg = RegInit(0.U(4.W))
  private val texWidthReg = RegInit(0.U(14.W))
  private val texHeightReg = RegInit(0.U(14.W))

  /** One nearest mip per quad. UV differences remain Q16.16; multiplying by
    * the base extent converts them to texels-per-pixel in Q16.16. */
  private def absDiff(a: UInt, b: UInt): UInt = {
    if (coordinatePolicyInput) {
      // Flipping the sign bit orders S16.16 monotonically without narrowing
      // its full 32-bit difference (including opposite-sign endpoints).
      val sign = Mux(policyReg.get.signed, "h80000000".U(32.W), 0.U(32.W))
      val orderedA = a ^ sign
      val orderedB = b ^ sign
      Mux(orderedA >= orderedB, orderedA - orderedB, orderedB - orderedA)
    } else Mux(a >= b, a - b, b - a)
  }
  private val diffReg = Reg(Vec(8, UInt(32.W)))
  private val diffPairReg = Reg(Vec(4, UInt(32.W)))
  private val diffAxisReg = Reg(Vec(2, UInt(32.W)))
  // Only the maximum U and V differences need extent multiplication. Keep
  // 16x14 registered partial products so no full 32x14 multiply is on a
  // single-cycle path. Recombination is exact at 46 bits, without saturation.
  private val gradLoReg = Reg(Vec(2, UInt(30.W)))
  private val gradHiReg = Reg(Vec(2, UInt(30.W)))
  private val gradReg = Reg(Vec(2, UInt(46.W)))
  if (config.lanes >= 4) {
    // Wide vectors contain independent TL/TR/BL/BR quads. Select the
    // current quad, including masked helper coordinates, for its own LOD.
    def quad(values: Vec[UInt]): Seq[UInt] = (0 until 4).map { lane =>
      if (config.lanes == 4) values(lane)
      else VecInit((0 until config.lanes / 4).map(q => values(q * 4 + lane)))(laneReg >> 2)
    }
    val u = quad(uVectorReg)
    val v = quad(vVectorReg)
    diffReg(0) := absDiff(u(1), u(0))
    diffReg(1) := absDiff(u(3), u(2))
    diffReg(2) := absDiff(u(2), u(0))
    diffReg(3) := absDiff(u(3), u(1))
    diffReg(4) := absDiff(v(1), v(0))
    diffReg(5) := absDiff(v(3), v(2))
    diffReg(6) := absDiff(v(2), v(0))
    diffReg(7) := absDiff(v(3), v(1))
    val extents = Seq(texWidthReg, texHeightReg)
    for (i <- 0 until 2) {
      gradLoReg(i) := diffAxisReg(i)(15, 0) * extents(i)
      gradHiReg(i) := diffAxisReg(i)(31, 16) * extents(i)
      gradReg(i) := gradLoReg(i) + (gradHiReg(i) << 16)
    }
  }
  // Full coordinate differences times a 14-bit extent can reach exponent29.
  // Apply bias before narrowing to the descriptor's 0..15 mip interval.
  private val automaticMip = WireDefault(0.U((if (coordinatePolicyInput) 5 else 4).W))
  if (config.lanes >= 4) {
    for (level <- 1 until (if (coordinatePolicyInput) 30 else 16)) {
      when(rhoReg >= (BigInt(1) << (16 + level)).U) {
        automaticMip := level.U
      }
    }
  }
  // Legacy automaticMip is 0..15; optional full-gradient policy extends it
  // to 0..29. With bias -16..15 the respective sums need six/seven signed
  // bits, preserving a true positive exponent before the upper mip clamp.
  // Scalar sampling adds no automatic level and retains its accepted bias.
  private val lodSumWidth = if (coordinatePolicyInput) 7 else 6
  private val biasedMip = automaticMip.pad(lodSumWidth).asSInt + lodBiasReg.pad(lodSumWidth)
  private val clampedMip = Mux(biasedMip < minLevelReg.pad(lodSumWidth).asSInt,
    minLevelReg, Mux(biasedMip > maxLevelReg.pad(lodSumWidth).asSInt,
      maxLevelReg, biasedMip.asUInt(3, 0)))
  // The sampler consumes an 8-bit trilinear weight.  Normalise rho into
  // [1,2), then look up frac(log2(rho)) from the top eight fractional bits.
  // This is a 256-entry constant mux (not a divider or iterative logarithm),
  // and keeps the LOD error below one Q0.8 step plus input quantisation.
  // Clamp endpoints intentionally have no fractional blend, matching a
  // saturated LOD.
  private val rhoNormal = rhoNormalReg
  private val log2FracLut = VecInit(Seq.tabulate(256) { i =>
    math.floor(math.log(1.0 + i.toDouble / 256.0) / math.log(2.0) * 256.0)
      .toInt.U(8.W)
  })
  private val automaticFrac = Mux(rhoNormal > (1 << 16).U,
    log2FracLut(rhoNormal(15, 8)), 0.U)
  private val lodIsClamped = lodIsClampedReg
  private val clampedFrac = Mux(lodIsClamped, 0.U, automaticFrac)

  // Alternate priority after every accepted instruction so independent scalar
  // and vector warps cannot starve one another at the shared physical sampler.
  private val preferVector = RegInit(true.B)
  private val selectVector =
    io.vectorIn.valid && (!io.in.valid || preferVector)
  io.vectorIn.ready := state === sIdle && selectVector
  io.in.ready := state === sIdle && !selectVector

  if (coordinatePolicyInput) {
    sampler.io.texBase := texBaseReg
    sampler.io.texFormat := texFormatReg
    sampler.io.texWidth := texWidthReg
    sampler.io.texHeight := texHeightReg
    sampler.io.wrapMode := wrapClampReg
    sampler.io.texMaxLevel := maxLevelReg
    sampler.io.coordinatePolicy.get := policyReg.get
  } else {
    sampler.io.texBase := Mux(preparedModeReg, texBaseReg, io.texBase)
    sampler.io.texFormat := Mux(preparedModeReg, texFormatReg, io.texFormat)
    sampler.io.texWidth := Mux(preparedModeReg, texWidthReg, io.texWidth)
    sampler.io.texHeight := Mux(preparedModeReg, texHeightReg, io.texHeight)
    sampler.io.wrapMode := Mux(preparedModeReg, wrapClampReg, io.wrapClamp)
    sampler.io.texMaxLevel := Mux(preparedModeReg, maxLevelReg, io.texMaxLevel)
  }

  private val prepareIndex = prepareLane(laneWidth - 1, 0)
  private val prepareActive = executionMaskReg(prepareIndex)
  private val prepareMore = state === sSample && preparedModeReg && prepareLane < config.lanes.U
  coordinatePipeline.io.in.valid := prepareMore && prepareActive
  coordinatePipeline.io.in.bits.lane := prepareIndex
  coordinatePipeline.io.in.bits.u := uVectorReg(prepareIndex)
  coordinatePipeline.io.in.bits.v := vVectorReg(prepareIndex)
  coordinatePipeline.io.in.bits.base := texBaseReg
  coordinatePipeline.io.in.bits.texWidth := texWidthReg
  coordinatePipeline.io.in.bits.texHeight := texHeightReg
  coordinatePipeline.io.in.bits.clamp := wrapClampReg
  coordinatePipeline.io.in.bits.coordinatePolicy.foreach(_ := policyReg.get)
  when(prepareMore && (!prepareActive || coordinatePipeline.io.in.fire)) {
    prepareLane := prepareLane + 1.U
  }
  // Select the real next lane while the previous result retires. A new
  // automatic-LOD quad, an inactive successor, scalar and broadcast keep
  // the ordinary idle path. Lane width and final-lane tests follow config.
  private val vectorLaneActive = executionMaskReg(laneReg)
  private val nextLane = (laneReg + 1.U)(laneWidth - 1, 0)
  private val sameMip = if (config.lanes <= 4) true.B else
    laneReg(1, 0) =/= 3.U || minLevelReg === maxLevelReg
  private val canChain = vectorModeReg && !broadcastTexelReg &&
    laneReg =/= (config.lanes - 1).U && executionMaskReg(nextLane) && sameMip
  private val retiringSample = sampler.io.result.valid
  private val requestedLane = Mux(preparedModeReg, requestIndex,
    Mux(retiringSample, nextLane, laneReg))
  when(state === sSample && preparedModeReg && requestLane < config.lanes.U &&
    (!executionMaskReg(requestIndex) || sampler.io.sample.fire)) {
    requestLane := requestLane + 1.U
  }
  private val usePrepared = preparedModeReg && !firstSampleReg
  sampler.io.prepared.get.valid := usePrepared && coordinatePipeline.io.out.valid
  sampler.io.prepared.get.bits := coordinatePipeline.io.out.bits.sample
  sampler.io.preparedCoordinateFault.foreach(_ := coordinatePipeline.io.out.bits.coordinateFault.get)
  coordinatePipeline.io.out.ready := usePrepared && sampler.io.sample.fire
  when(preparedModeReg && sampler.io.sample.fire) { firstSampleReg := false.B }
  when(usePrepared && sampler.io.sample.fire) {
    assert(coordinatePipeline.io.out.bits.lane === requestedLane, "prepared coordinate lane must match sample owner")
  }
  when(io.vectorIn.fire || io.in.fire) {
    assert(!coordinatePipeline.io.busy, "coordinate metadata must drain before the next instruction")
  }

  sampler.io.sample.valid := state === sSample &&
    Mux(preparedModeReg, preparedRequestActive,
      Mux(retiringSample, canChain, !vectorModeReg || vectorLaneActive)) &&
      (!usePrepared || coordinatePipeline.io.out.valid)
  sampler.io.sample.bits.u :=
    Mux(vectorModeReg, uVectorReg(requestedLane), uReg)
  sampler.io.sample.bits.v :=
    Mux(vectorModeReg, vVectorReg(requestedLane), vReg)
  sampler.io.sample.bits.mipLevel := mipLevelReg
  sampler.io.sample.bits.lodFrac := mipFracReg
  sampler.io.result.ready :=
    state === sSample && (!vectorModeReg || vectorLaneActive)

  io.commit.valid := state === sCommit && !vectorModeReg
  io.commit.bits.warpId := warpIdReg
  io.commit.bits.nextPc := pcReg + 4.U
  io.commit.bits.activeMask := activeMaskReg
  io.commit.bits.writeRd := coordinateFaultReg.map(!_).getOrElse(true.B)
  io.commit.bits.rd := rdReg
  io.commit.bits.data := dataReg

  io.vectorCommit.valid := state === sCommit && vectorModeReg
  io.vectorCommit.bits.writeback.warpId := warpIdReg
  io.vectorCommit.bits.writeback.vd := rdReg
  io.vectorCommit.bits.writeback.data := vectorDataReg
  io.vectorCommit.bits.saturated := false.B
  io.vectorCommit.bits.writesVd := coordinateFaultReg.map(!_).getOrElse(true.B)
  io.vectorCommit.bits.writesScalar := false.B
  io.vectorCommit.bits.scalarData := 0.U
  io.vectorCommit.bits.writesFloat := false.B
  io.vectorCommit.bits.floatData := 0.U
  io.vectorCommit.bits.flags := 0.U
  io.vectorCommit.bits.writesFlags := false.B
  io.vectorCommit.bits.pc := pcReg
  io.vectorCommit.bits.warpActiveMask := activeMaskReg

  private def advanceVectorLane(): Unit = {
    when(laneReg === (config.lanes - 1).U) {
      state := sCommit
    }.otherwise {
      laneReg := laneReg + 1.U
      if (config.lanes > 4) {
        val nextQuadActive = MuxLookup(laneReg >> 2, false.B)(
          (0 until config.lanes / 4 - 1).map { q =>
            q.U -> executionMaskReg((q + 2) * 4 - 1, (q + 1) * 4).orR
          })
        // A fixed clamp already selects the mip for every quad. The
        // accepted controls remain latched even if the caller changes them.
        when(laneReg(1, 0) === 3.U && nextQuadActive &&
          minLevelReg =/= maxLevelReg) { state := sGrad }
      }
    }
  }

  if (coordinatePolicyInput) {
    when(io.in.fire || io.vectorIn.fire) {
      policyReg.get := io.coordinatePolicy.get
      // Reject an inverted mip interval through the same no-read fault path.
      when(io.minLevel > io.texMaxLevel) { policyReg.get.wrapU := 3.U }
      if (config.lanes < 4) {
        when(io.vectorIn.fire && io.minLevel =/= io.texMaxLevel) {
          policyReg.get.wrapU := 3.U
        }
      }
      coordinateFaultReg.get := false.B
      texBaseReg := io.texBase
      texFormatReg := io.texFormat
      texWidthReg := io.texWidth
      texHeightReg := io.texHeight
      wrapClampReg := io.wrapClamp
      maxLevelReg := io.texMaxLevel
      minLevelReg := io.minLevel
      lodBiasReg := io.lodBias
    }
    when(sampler.io.result.fire) {
      coordinateFaultReg.get := coordinateFaultReg.get || sampler.io.coordinateFault.get
    }
  }

  switch(state) {
    is(sIdle) {
      // Cursors are unobservable while idle. Loading them independently of
      // acceptance removes the late arbitration enable from their data path;
      // the acceptance edge still snapshots the actual offered mask.
      requestLane := PriorityEncoder(io.vectorIn.bits.executionMask)
      prepareLane := TexSampleUnit.laneAfterFirst(io.vectorIn.bits.executionMask)
      when(io.vectorIn.fire) {
        vectorModeReg := true.B
        warpIdReg := io.vectorIn.bits.issued.decode.warpId
        rdReg := io.vectorIn.bits.issued.decode.instruction(11, 7)
        pcReg := io.vectorIn.bits.issued.decode.pc
        activeMaskReg := io.vectorIn.bits.issued.decode.activeMask
        executionMaskReg := io.vectorIn.bits.executionMask
        vectorDataReg := io.vectorIn.bits.issued.oldVdData
        uVectorReg := io.vectorIn.bits.issued.vs1Data
        vVectorReg := io.vectorIn.bits.issued.vs2Data
        texWidthReg := io.texWidth
        texHeightReg := io.texHeight
        lodBiasReg := io.lodBias
        minLevelReg := io.minLevel
        maxLevelReg := io.texMaxLevel
        // A fixed mip whose dimensions have both reached one has exactly
        // one decoded colour for every coordinate. Reuse the scalar
        // sampler's format/address handling, then broadcast its result.
        val oneTexel = io.minLevel === io.texMaxLevel &&
          (io.texWidth >> io.minLevel) <= 1.U &&
          (io.texHeight >> io.minLevel) <= 1.U &&
          io.vectorIn.bits.executionMask.orR
        broadcastTexelReg := oneTexel
        preparedModeReg := io.minLevel === 0.U && io.texMaxLevel === 0.U &&
          !oneTexel && PopCount(io.vectorIn.bits.executionMask) > 1.U
        // Preserve the original first-sample latency; only later active
        // lanes enter the lookahead pipeline.
        firstSampleReg := true.B
        texBaseReg := io.texBase
        texFormatReg := io.texFormat
        wrapClampReg := io.wrapClamp
        laneReg := Mux(oneTexel,
          PriorityEncoder(io.vectorIn.bits.executionMask), 0.U)
        preferVector := false.B
        when(io.minLevel === io.texMaxLevel) {
          // Every derivative and bias clamps to this exact level. Fractional
          // blending cannot select a different mip at the upper endpoint.
          mipLevelReg := io.minLevel
          mipFracReg := 0.U
          state := sSample
        }.otherwise {
          state := sGrad
          if (coordinatePolicyInput && config.lanes < 4) {
            // Partial quads have no implicit derivative contract. Return a
            // held no-read fault instead of selecting an undefined gradient.
            mipLevelReg := 0.U
            mipFracReg := 0.U
            state := sSample
          }
        }
      }.elsewhen(io.in.fire) {
        vectorModeReg := false.B
        preparedModeReg := false.B
        broadcastTexelReg := false.B
        warpIdReg := io.in.bits.decode.warpId
        rdReg := io.in.bits.decode.instruction(11, 7)
        pcReg := io.in.bits.decode.pc
        activeMaskReg := io.in.bits.decode.activeMask
        uReg := io.in.bits.rs1Data
        vReg := io.in.bits.rs2Data
        val scalarBiased = io.lodBias
        mipLevelReg := Mux(scalarBiased < io.minLevel.zext,
          io.minLevel, Mux(scalarBiased > io.texMaxLevel.zext,
            io.texMaxLevel, scalarBiased.asUInt(3, 0)))
        // Scalar tex.sample has no derivatives, hence it is an exact selected
        // level sample even when a positive LOD bias chooses a higher mip.
        mipFracReg := 0.U
        preferVector := true.B
        state := sSample
      }
    }
    is(sGrad) {
      // UV differences are captured from the accepted coordinates.
      state := sMaxPair
    }
    is(sMaxPair) {
      for (i <- 0 until 4) {
        diffPairReg(i) := Mux(diffReg(2 * i) >= diffReg(2 * i + 1),
          diffReg(2 * i), diffReg(2 * i + 1))
      }
      state := sMax
    }
    is(sMax) {
      for (i <- 0 until 2) {
        diffAxisReg(i) := Mux(diffPairReg(2 * i) >= diffPairReg(2 * i + 1),
          diffPairReg(2 * i), diffPairReg(2 * i + 1))
      }
      state := sMul
    }
    is(sMul) {
      // Capture two partial products for each axis maximum.
      state := sAdd
    }
    is(sAdd) {
      // Recombine into two full-width texel gradients.
      state := sMax2
    }
    is(sMax2) {
      rhoReg := Mux(gradReg(0) >= gradReg(1), gradReg(0), gradReg(1))
      state := sLod
    }
    is(sLod) {
      // Stage 8: select the nearest mip and register the normalized rho used
      // by the fractional-LOD lookup.
      rhoNormalReg := rhoReg >> automaticMip
      lodIsClampedReg := biasedMip < minLevelReg.pad(lodSumWidth).asSInt ||
        biasedMip > maxLevelReg.pad(lodSumWidth).asSInt
      mipLevelReg := clampedMip
      state := sFrac
    }
    is(sFrac) {
      // Stage 9: keep the LUT and its output mux off the request boundary.
      mipFracReg := clampedFrac
      state := sSample
    }
    is(sSample) {
      when(vectorModeReg) {
        when(vectorLaneActive) {
          when(sampler.io.result.fire) {
            when(broadcastTexelReg) {
              for (lane <- 0 until config.lanes) {
                when(executionMaskReg(lane)) {
                  vectorDataReg(lane) := sampler.io.result.bits
                }
              }
              state := sCommit
            }.otherwise {
              vectorDataReg(laneReg) := sampler.io.result.bits
              advanceVectorLane()
            }
          }
        }.otherwise {
          advanceVectorLane()
        }
      }.otherwise {
        when(sampler.io.result.fire) {
          dataReg := sampler.io.result.bits
          state := sCommit
        }
      }
    }
    is(sCommit) {
      when((vectorModeReg && io.vectorCommit.fire) ||
        (!vectorModeReg && io.commit.fire)) {
        state := sIdle
      }
    }
  }
}
