package opengpu.graphics

import chisel3._
import chisel3.util._

/** One texture-sample request: texture-space coordinates as unsigned Q16.16
  * over the unit square ([0,1) maps the texture extent; larger values reach
  * the wrap/clamp machinery).
  *
  * `mipLevel` selects the desired mip level (0 = base). `lodFrac` is the
  * Q0.8 weight of the following level for trilinear filtering. The unit walks the
  * chain at most min(mipLevel, texMaxLevel) levels deep, so a level-0 request
  * behaves exactly as before mip support existed.
  */
class TexSampleRequest extends Bundle {
  val u = UInt(32.W)
  val v = UInt(32.W)
  val mipLevel = UInt(4.W)
  val lodFrac = UInt(8.W)
}

/** Wrap behaviour applied independently to both axes.
  *
  *   0 = REPEAT: the texture tiles at any nonzero supported dimensions.
  *   1 = CLAMP : samples past the edge stick to the edge texels.
  */
object TexWrap {
  val repeat = 0.U(1.W)
  val clamp = 1.U(1.W)
}

/** Fixed-function texture sampling unit (M5b).
  *
  * Samples an RGBA8888 texture in shared memory with a bilinear filter.  This
  * is the fixed-function stage the roadmap places beside the SIMT lanes: the
  * shader program computes coordinates, the sampler decodes format/addressing/
  * filtering so the lanes never issue four raw loads plus blend maths per
  * sample.
  *
  * Configuration comes in as registers (driven by the host/driver, mirroring
  * the OutputMerger register model -- hardware only computes addresses and
  * issues reads, it never owns or sizes a buffer):
  *   - texBase    : byte address of texel (0,0);
  *   - texWidth/H : texture extent in texels;
  *   - wrapMode   : TexWrap.repeat or TexWrap.clamp, applied to both axes.
  *
  * Texel word layout matches the pipeline's packed-colour convention:
  * bits[31:24] red, [23:16] green, [15:8] blue, [7:0] alpha (little-endian
  * bytes A,B,G,R). `texFormat` selects the memory byte order; each response
  * is decoded before filtering. Alpha is filtered with RGB; RGBX/BGRX taps
  * have alpha 255. Blending stays with the OM.
  *
  * Sampling maths (all integer, no hidden rounding):
  *   biased = (u * width) - 0.5      -- texel-space coordinate with 8
  *                                      fractional bits, half-texel aligned so
  *                                      texel centres sit at u=(i+0.5)/N
  *   REPEAT: biased = floorMod(biased, width<<8)
  *   CLAMP : saturate biased into [0, (width<<8)-1]
  *   x0 = xs >> 8 ; fx = xs & 0xff
  *   REPEAT: x1 = (x0 + 1) % width   (x0 is already in range)
  *   CLAMP : x0 = min(x0, width - 1); x1 = min(x0 + 1, width - 1)
  * and identically for v/y/height.  With zero fractions (sample centre) the
  * blend degenerates to the exact texel value, so NEAREST behaves correctly
  * through the same datapath.
  *
  * Mip chain: levels are stored as one contiguous chain starting at texBase;
  * level i has dims max(1,w>>i) x max(1,h>>i), RGBA8888 tightly packed, so
  * level i's byte offset is the sum of the preceding levels' w*h*4.  On
  * accept the unit walks the chain one level per cycle -- accumulate the
  * current level's byte size into the base, halve the dims (floored at 1) --
  * up to min(mipLevel, texMaxLevel) steps, then decodes and fetches from the
  * resolved level.  Zero steps leaves base/dims untouched, so level 0 is
  * bit-identical to the pre-mip unit when `lodFrac` is zero. The byte offsets stay natural
  * width: a 2^14 x 2^14 RGBA8888 level is 2^30 bytes and the whole chain
  * fits under 2^32, so no fixed-size slicing is needed. When `lodFrac` is
  * non-zero and a following level exists, the unit filters both selected
  * levels and mixes their RGB values with weights `256-lodFrac` and
  * `lodFrac`; a request at the final level remains a single-level fetch.
  *
  * Blend: each channel accumulates the four taps weighted by
  *   w00=(256-fx)(256-fy) w10=fx(256-fy) w01=(256-fx)fy w11=fx*fy
  * whose sum is exactly 65536, then shifts right by 16 (truncate).
  *
  * The four taps of one mip level are issued in consecutive cycles through
  * one `OmMemoryRequest` word port, with up to four reads outstanding.  The
  * response's echoed byte address routes out-of-order completions back to the
  * matching tap. Duplicate clamp addresses are coalesced into one physical
  * read and its response fills every matching logical tap. Trilinear sampling
  * keeps the two four-tap level groups sequential, bounding the required
  * downstream transaction capacity at four.
  * An optional word cache reuses raw taps within an owner-defined operation.
  * Its owner must invalidate it before each new instruction or binding.
  */
class TextureUnit(
  gfxConfig: GraphicsConfig = GraphicsConfig(),
  wordCacheEntries: Int = 0,
  allowPrepared: Boolean = false,
  overlapPrepared: Boolean = false,
  coordinatePolicyInput: Boolean = false
) extends Module {
  // Preserve the original constructor ABIs for precompiled callers.
  def this(gfxConfig: GraphicsConfig,wordCacheEntries: Int,allowPrepared: Boolean,overlapPrepared: Boolean) =
    this(gfxConfig,wordCacheEntries,allowPrepared,overlapPrepared,false)
  def this(gfxConfig: GraphicsConfig, wordCacheEntries: Int, allowPrepared: Boolean) =
    this(gfxConfig, wordCacheEntries, allowPrepared, false)
  require(!overlapPrepared || allowPrepared)
  require(!coordinatePolicyInput || !allowPrepared,
    "extended coordinate policy currently uses the owned direct/mip path")
  // Preserve the uncached single-argument interface for existing callers.
  def this(gfxConfig: GraphicsConfig) = this(gfxConfig, 0, false)
  def this(gfxConfig: GraphicsConfig, wordCacheEntries: Int) = this(gfxConfig, wordCacheEntries, false)
  require(wordCacheEntries == 0 || (wordCacheEntries >= 4 && isPow2(wordCacheEntries)))
  val io = IO(new Bundle {
    // The owner must invalidate between instructions/bindings. Uncached
    // legacy samplers have no extra input or persistent cached words.
    val invalidateCache = if (wordCacheEntries > 0) Some(Input(Bool())) else None
    /** Optional policy is captured with every accepted direct sample. Fault
      * accompanies the held result for reserved modes; no reads are issued.
      */
    val coordinatePolicy=if(coordinatePolicyInput)Some(Input(new TextureCoordinatePolicy))else None
    val coordinateFault=if(coordinatePolicyInput)Some(Output(Bool()))else None
    val texFormat = Input(UInt(PixelFormat.width.W))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    /** TexWrap.repeat or TexWrap.clamp (see above). */
    val wrapMode = Input(Bool())
    /** Deepest mip level the level-walk may reach (TEX_CONFIG[5:2]). */
    val texMaxLevel = Input(UInt(4.W))
    val sample = Flipped(Decoupled(new TexSampleRequest))
    val prepared = if (allowPrepared) Some(Input(Valid(new TexturePreparedSample))) else None
    val result = Decoupled(UInt(32.W))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  // sBlend exists so the final response's tapWords write commits before the
  // blend reads it (same-cycle write + blend-read would otherwise use stale
  // data).
  // sLevel walks the mip chain between accept and tap decode (see the class
  // doc); level 0 takes one decode cycle without changing base or dimensions.
  // The tap decode itself is two pipelined cycles: sWrap resolves the
  // wrapped/clamped texel-space coordinate (it contains the coordinate
  // multiply), sTap forms the bilinear tap addresses and fractions (it
  // contains the index-by-width multiply).
  private val sIdle :: sLevel :: sWrap :: sIdx :: sTap :: sFetch :: sBlend :: sMix :: sDone :: Nil =
    Enum(9)
  private val state = RegInit(sIdle)

  // Level-walk state latched at accept: the request pins are only stable
  // through the handshake cycle, so the coordinates start the walk in
  // registers.  levelBase accumulates the byte offsets of the skipped
  // levels; levelW/H shrink per level, floored at 1.
  private val formatReg = RegInit(0.U(PixelFormat.width.W))
  private val policy=if(coordinatePolicyInput)Some(Reg(new TextureCoordinatePolicy))else None
  private val policyFault=if(coordinatePolicyInput)Some(RegInit(false.B))else None
  private val bindingBase=if(coordinatePolicyInput)Some(Reg(UInt(32.W)))else None
  private val bindingWidth=if(coordinatePolicyInput)Some(Reg(UInt(14.W)))else None
  private val bindingHeight=if(coordinatePolicyInput)Some(Reg(UInt(14.W)))else None
  private val clampU=policy.map(_.wrapU=/=TextureCoordinatePolicy.repeat.U).getOrElse(io.wrapMode)
  private val clampV=policy.map(_.wrapV=/=TextureCoordinatePolicy.repeat.U).getOrElse(io.wrapMode)
  io.coordinateFault.foreach(_:=policyFault.get)
  private val uReg = RegInit(0.U(32.W))
  private val vReg = RegInit(0.U(32.W))
  private val levelBase = RegInit(0.U(32.W))
  private val levelW = RegInit(1.U(14.W))
  private val levelH = RegInit(1.U(14.W))
  private val levelSteps = RegInit(0.U(4.W))
  private val baseMipLevel = RegInit(0.U(4.W))
  private val lodFracReg = RegInit(0.U(8.W))
  private val secondLevel = RegInit(false.B)
  private val singleTexel = RegInit(false.B)

  // Four logical per-level reads. `tapIssued` marks physical requests, which
  // are coalesced when clamp addressing makes logical taps share an address.
  // `tapReceived` tracks the four blend inputs independently.
  private val tapAddrs = Reg(Vec(4, UInt(32.W)))
  private val tapIssued = RegInit(0.U(4.W))
  private val tapReceived = RegInit(0.U(4.W))
  private val tapWords = Reg(Vec(4, UInt(32.W)))

  // Two prepared contexts share the existing word cache, physical port and
  // blend multipliers. This window is independent of shader vector length.
  private class PreparedWindow {
    val valid = RegInit(VecInit(Seq.fill(2)(false.B))).suggestName("preparedValid")
    val head = RegInit(0.U(1.W)).suggestName("preparedHead")
    val tail = RegInit(0.U(1.W)).suggestName("preparedTail")
    val addresses = Reg(Vec(2, Vec(4, UInt(32.W)))).suggestName("preparedAddresses")
    val weights = Reg(Vec(2, Vec(4, UInt(17.W)))).suggestName("preparedWeights")
    val format = Reg(Vec(2, UInt(PixelFormat.width.W))).suggestName("preparedFormat")
    val words = Reg(Vec(2, Vec(4, UInt(32.W)))).suggestName("preparedWords")
    val issued = RegInit(VecInit(Seq.fill(2)(0.U(4.W)))).suggestName("preparedIssued")
    val received = RegInit(VecInit(Seq.fill(2)(0.U(4.W)))).suggestName("preparedReceived")
    val lookup = RegInit(VecInit(Seq.fill(2)(false.B))).suggestName("preparedLookup")
  }
  private val preparedWindow = if (overlapPrepared) Some(new PreparedWindow) else None
  private val preparedActive = preparedWindow.map(p => state === sIdle && p.valid.asUInt.orR).getOrElse(false.B)
  private val preparedEmpty = preparedWindow.map(p => !p.valid.asUInt.orR).getOrElse(true.B)
  private val preparedLookupIndex = preparedWindow.map(p =>
    Mux(p.valid(p.head) && p.lookup(p.head), p.head, ~p.head)).getOrElse(0.U(1.W))
  private val preparedLookupActive = preparedWindow.map(p =>
    state === sIdle && p.valid(preparedLookupIndex) && p.lookup(preparedLookupIndex)).getOrElse(false.B)
  private val preparedRequestValid = WireDefault(false.B)
  private val preparedRequestAddress = WireDefault(0.U(32.W))
  private val preparedResponseMatch = WireDefault(false.B)


  private val cacheValid = if (wordCacheEntries > 0)
    Some(RegInit(0.U(wordCacheEntries.W))) else None
  private val cacheTags = if (wordCacheEntries > 0)
    Some(Reg(Vec(wordCacheEntries, UInt(32.W)))) else None
  private val cacheWords = if (wordCacheEntries > 0)
    Some(Reg(Vec(wordCacheEntries, UInt(32.W)))) else None
  private val cacheLookupPending = if (wordCacheEntries > 0)
    Some(RegInit(false.B)) else None
  private val cacheReplace = if (wordCacheEntries > 0)
    Some(RegInit(0.U(log2Ceil(wordCacheEntries).W))) else None

  // A binding change must not invalidate taps that an older accepted
  // prepared context still needs. Retain even a one-cycle invalidate pulse.
  private val preparedFetched = preparedWindow.map(p => VecInit((0 until 2).map(g =>
    !p.valid(g) || (!p.lookup(g) && p.received(g).andR))).asUInt.andR).getOrElse(true.B)
  private val invalidationPending = if (overlapPrepared && wordCacheEntries > 0)
    Some(RegInit(false.B)) else None
  private val cacheInvalidateRequested = io.invalidateCache.map(pin =>
    pin || invalidationPending.getOrElse(false.B)).getOrElse(false.B)
  private val cacheInvalidateNow = cacheInvalidateRequested && preparedFetched
  invalidationPending.foreach { pending =>
    when(cacheInvalidateNow) { pending := false.B }
      .elsewhen(io.invalidateCache.get) { pending := true.B }
  }

  // Blended result held until result.fire.
  private val resultReg = RegInit(0.U(32.W))
  // One output slot overlaps the next prepared fetch with the prior blend.
  // The prepared fetch window shares its blend multipliers with this generic path.
  private val resultValid = if (overlapPrepared) Some(RegInit(false.B)) else None
  private val resultSpace = resultValid.map(v => !v || io.result.ready).getOrElse(true.B)
  private val preparedRetire = preparedWindow.map(p =>
    state === sIdle && p.valid(p.head) && !p.lookup(p.head) &&
      p.received(p.head).andR && resultSpace).getOrElse(false.B)

  if (overlapPrepared) { when(io.result.fire) { resultValid.get := false.B } }
  private def completeResult(value: UInt): Unit = {
    resultReg := value
    if (overlapPrepared) {
      assert(resultSpace, "a pending texture result must not be overwritten")
      resultValid.get := true.B
      state := sIdle
    } else { state := sDone }
  }
  private val firstLevelResult = RegInit(0.U(32.W))
  // Bilinear result registered between sBlend and sMix so the trilinear
  // weight multiplies never sit behind the four-tap blend adder tree.
  private val blendedReg = RegInit(0.U(32.W))
  // Corner indices registered between sIdx and sTap so the tap-address
  // multiply never sits behind the clamp/wrap comparators.
  private val idxXReg = Reg(Vec(2, UInt(14.W)))
  private val idxYReg = Reg(Vec(2, UInt(14.W)))

  if (overlapPrepared) {
    val finishingBase = state === sBlend && !secondLevel && lodFracReg === 0.U
    val p = preparedWindow.get
    val room = !p.valid.asUInt.andR || preparedRetire
    val canPrepare = room && (state === sIdle || (finishingBase && resultSpace)) &&
      (!cacheInvalidateRequested || preparedFetched)
    io.sample.ready := Mux(io.prepared.get.valid, canPrepare,
      preparedEmpty && resultSpace && state === sIdle)
  } else {
    io.sample.ready := state === sIdle || (state === sDone && io.result.ready)
  }

  // ---------------------------------------------------------------------
  // Coordinate decode, split across the sWrap and sTap stages so neither
  // stage carries more than one multiply.
  // ---------------------------------------------------------------------
  /** Stage 1 (captured on the sLevel -> sWrap transition): map the coordinate
    * into texel space x256 with the industry-standard half-texel alignment,
    * so u = (i+0.5)/N hits texel i's centre and u = k/N falls exactly between
    * texel k-1 and k.
    *
    * Normalize whole repeat periods or bound clamped coordinates before
    * scaling. The signed coordinate has eight fractional bits and fits in24 bits.
    */
  private def axisBias(coord: UInt, extent: UInt): UInt =
    TextureCoordinates.bias(TextureCoordinates.scale(coord, extent, io.wrapMode))

  private val biasedXReg = RegInit(0.U(24.W))
  private val biasedYReg = RegInit(0.U(24.W))
  // Capture parallel partial products at admission or the prior mip-walk
  // edge, then combine at sLevel. Preserve the original fallback latency.
  private val axisProducts = if (overlapPrepared)
    Some(RegInit(VecInit(Seq.fill(4)(0.U(30.W))))) else None
  private val axisClampedHigh = if (overlapPrepared)
    Some(RegInit(VecInit(Seq.fill(2)(false.B)))) else None
  private val productCapture = WireDefault(false.B)
  // Capture can occur either at a new handshake or during sLevel's mip
  // walk. Select operands from registered state, so live input-valid and
  // backpressure do not precede a multiplier. The capture enable is separate.
  private val productU = Mux(state === sLevel, uReg, io.sample.bits.u)
  private val productV = Mux(state === sLevel, vReg, io.sample.bits.v)
  private val productW = Mux(state === sLevel,
    Mux(levelW === 1.U, 1.U, levelW >> 1), io.texWidth)
  private val productH = Mux(state === sLevel,
    Mux(levelH === 1.U, 1.U, levelH >> 1), io.texHeight)
  if (overlapPrepared) {
    when(productCapture) {
      assert(state === sLevel || io.sample.fire,
        "partial-product capture must belong to a mip walk or new sample")
      // Keep live-coordinate comparisons parallel to the low-half multiply.
      // REPEAT ignores the whole periods; unsaturated CLAMP needs one high
      // coordinate bit. Saturated CLAMP bypasses the joined product below.
      axisProducts.get(0) := productU(15, 0) * productW
      axisProducts.get(1) := Mux(io.wrapMode && productU(16), productW, 0.U)
      axisProducts.get(2) := productV(15, 0) * productH
      axisProducts.get(3) := Mux(io.wrapMode && productV(16), productH, 0.U)
      axisClampedHigh.get(0) := io.wrapMode && productU >= 98304.U
      axisClampedHigh.get(1) := io.wrapMode && productV >= 98304.U
    }
  }
  private val inRangeXReg = RegInit(0.U(22.W))
  private val inRangeYReg = RegInit(0.U(22.W))

  // ---------------------------------------------------------------------
  // Tap addressing: linear RGBA8888, 4 bytes per texel, into the selected
  // mip level's base/extent.
  // ---------------------------------------------------------------------
  private def tapAddr(xIdx: UInt, yIdx: UInt): UInt =
    levelBase + ((yIdx * levelW + xIdx) << 2)

  // The first logical tap at an address owns its physical request. This keeps
  // request selection deterministic even when a clamped corner maps all four
  // bilinear taps onto one texel.
  private val tapLeaders = VecInit((0 until 4).map { i =>
    if (i == 0) true.B
    else !VecInit((0 until i).map(j => tapAddrs(j) === tapAddrs(i))).asUInt.orR
  })
  private val lookupAddresses = preparedWindow.map(p =>
    Mux(preparedLookupActive, p.addresses(preparedLookupIndex), tapAddrs)).getOrElse(tapAddrs)
  private val cachedTapHits = if (wordCacheEntries > 0) Some(VecInit(
    (0 until 4).map(tap => VecInit((0 until wordCacheEntries).map(entry =>
      cacheValid.get(entry) && cacheTags.get(entry) === lookupAddresses(tap))).asUInt))) else None
  private val cachedTapReady = cachedTapHits.map(hits =>
    VecInit(hits.map(_.orR)).asUInt).getOrElse(0.U(4.W))
  private val issuedForSelection = Mux(cacheLookupPending.getOrElse(false.B),
    cachedTapReady, tapIssued)
  private val unissued = ~issuedForSelection & tapLeaders.asUInt
  private val hasUnissued = unissued.orR
  private val issueIdx = PriorityEncoder(unissued)
  io.mem.req.valid := (state === sFetch && hasUnissued) || preparedRequestValid
  io.mem.req.bits.write := false.B
  io.mem.req.bits.addr := Mux(preparedActive, preparedRequestAddress, tapAddrs(issueIdx))
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := (state === sFetch && !cacheLookupPending.getOrElse(false.B)) || preparedActive

  // A response belongs to its issued leader, then fills every as-yet-empty
  // logical tap at that address. This preserves all four blend inputs without
  // making redundant memory transactions at clamped edges.
  private val responseMatches = VecInit((0 until 4).map { i =>
    tapIssued(i) && !tapReceived(i) &&
      tapAddrs(i) === io.mem.resp.bits.addr
  })
  private val responseMatchBits = responseMatches.asUInt
  private val responseAccepted = io.mem.resp.fire && !io.mem.resp.bits.write
  private val responseFillBits = VecInit((0 until 4).map { i =>
    !tapReceived(i) && tapAddrs(i) === io.mem.resp.bits.addr
  }).asUInt
  private val receivedAfterResponse =
    tapReceived | responseFillBits

  if (overlapPrepared) {
    val p = preparedWindow.get
    when(p.valid.asUInt.orR) {
      assert(state === sIdle, "prepared contexts must own the idle generic datapath")
    }
    val afterLookupIssued = VecInit((0 until 2).map(g =>
      p.issued(g) | Mux(preparedLookupActive && preparedLookupIndex === g.U,
        cachedTapReady, 0.U(4.W))))
    val afterLookupReceived = VecInit((0 until 2).map(g =>
      p.received(g) | Mux(preparedLookupActive && preparedLookupIndex === g.U,
        cachedTapReady, 0.U(4.W))))
    val outstanding = PopCount(Cat((0 until 2).map(g =>
      Mux(p.valid(g), p.issued(g) & ~p.received(g), 0.U(4.W)))))
    val matches = VecInit((0 until 2).map(g => VecInit((0 until 4).map(t =>
      p.valid(g) && p.issued(g)(t) && !p.received(g)(t) &&
        p.addresses(g)(t) === io.mem.resp.bits.addr)).asUInt))
    preparedResponseMatch := matches.asUInt.orR
    val response = preparedActive && responseAccepted && preparedResponseMatch
    val candidates = VecInit((0 until 2).map(g => VecInit((0 until 4).map { t =>
      val alreadyPending = VecInit((0 until 2).flatMap(other => (0 until 4).map { j =>
        p.valid(other) && p.issued(other)(j) && !p.received(other)(j) &&
          p.addresses(other)(j) === p.addresses(g)(t)
      })).asUInt.orR
      // Keep the cache tag CAM off the physical address-selection path.
      p.valid(g) && !p.lookup(g) &&
        !p.issued(g)(t) && !p.received(g)(t) && !alreadyPending
    }).asUInt))
    val group = Mux(candidates(p.head).orR, p.head, ~p.head)
    val tap = PriorityEncoder(candidates(group))
    preparedRequestValid := preparedActive && candidates.asUInt.orR &&
      (outstanding < TextureUnit.maxOutstandingReads.U || response)
    preparedRequestAddress := p.addresses(group)(tap)
    val request = preparedActive && io.mem.req.fire

    when(preparedActive) {
      assert(outstanding <= TextureUnit.maxOutstandingReads.U,
        "prepared fetch window exceeded the physical read budget")
      when(io.mem.resp.fire) {
        assert(!io.mem.resp.bits.write && preparedResponseMatch,
          "prepared texture response must match an issued pending address")
      }
      for (g <- 0 until 2) {
        when(p.valid(g)) {
          p.issued(g) := afterLookupIssued(g) |
            Mux(request && group === g.U, UIntToOH(tap, 4), 0.U(4.W))
          val fills = VecInit((0 until 4).map(t =>
            !afterLookupReceived(g)(t) && p.addresses(g)(t) === io.mem.resp.bits.addr)).asUInt
          p.received(g) := afterLookupReceived(g) | Mux(response, fills, 0.U(4.W))
          for (t <- 0 until 4) {
            if (wordCacheEntries > 0) {
              when(preparedLookupActive && preparedLookupIndex === g.U && cachedTapHits.get(t).orR) {
                p.words(g)(t) := PixelFormat.decode(
                  cacheWords.get(PriorityEncoder(cachedTapHits.get(t))), p.format(g))
              }
            }
            when(response && fills(t)) {
              p.words(g)(t) := PixelFormat.decode(io.mem.resp.bits.data, p.format(g))
            }
          }
        }
      }
      when(preparedLookupActive) { p.lookup(preparedLookupIndex) := false.B }
    }
    when(preparedRetire) {
      p.valid(p.head) := false.B
      p.head := ~p.head
    }
    // Admission is last so a full window can retire and reuse its old head
    // on one edge. The blend still reads the predecessor's registered data.
    when(io.sample.fire && io.prepared.get.valid) {
      p.valid(p.tail) := true.B
      p.addresses(p.tail) := io.prepared.get.bits.addresses
      p.weights(p.tail) := io.prepared.get.bits.weights
      p.format(p.tail) := io.texFormat
      p.issued(p.tail) := 0.U
      p.received(p.tail) := 0.U
      p.lookup(p.tail) := cacheValid.map(_.orR).getOrElse(false.B) && !cacheInvalidateNow
      p.tail := ~p.tail
      assert(io.sample.bits.mipLevel === 0.U && io.sample.bits.lodFrac === 0.U &&
        io.texMaxLevel === 0.U, "prepared samples require fixed base-level filtering")
    }
  }


  if (wordCacheEntries > 0) {
    when(cacheInvalidateNow) {
      cacheValid.get := 0.U
      cacheReplace.get := 0.U
      cacheLookupPending.get := false.B
    }.elsewhen(responseAccepted && ((state === sFetch && responseMatchBits.orR) || preparedResponseMatch)) {
      cacheValid.get := cacheValid.get | UIntToOH(cacheReplace.get, wordCacheEntries)
      cacheTags.get(cacheReplace.get) := io.mem.resp.bits.addr
      cacheWords.get(cacheReplace.get) := io.mem.resp.bits.data
      cacheReplace.get := cacheReplace.get + 1.U
    }
  }

  // ---------------------------------------------------------------------
  // Blend: fixed-point weights summing to exactly 65536, truncated shift.
  // The weights are captured in sIdx so the sBlend adder tree starts from
  // registered weights instead of live fx/fy multiplies.
  // ---------------------------------------------------------------------
  // An endpoint weight can equal 256*256 = 65536, so all 17 bits are needed.
  // Keeping only 16 bits would turn exact-texel samples into transparent RGB.
  private val wReg = Reg(Vec(4, UInt(17.W)))

  // Valid prepared contexts only coexist with the idle generic FSM. Decode
  // their datapath from registered validity, keeping FSM state off the shared
  // blend multiplier inputs.
  private val blendPrepared = preparedWindow.map(p => p.valid(p.head)).getOrElse(false.B)
  private val blendWords = preparedWindow.map(p =>
    Mux(blendPrepared, p.words(p.head), tapWords)).getOrElse(tapWords)
  private val blendWeights = preparedWindow.map(p =>
    Mux(blendPrepared, p.weights(p.head), wReg)).getOrElse(wReg)
  private def blend(sel: UInt => UInt): UInt = {
    val acc = sel(blendWords(0)) * blendWeights(0) + sel(blendWords(1)) * blendWeights(1) +
      sel(blendWords(2)) * blendWeights(2) + sel(blendWords(3)) * blendWeights(3)
    acc(23, 16)
  }

  // Output uses the pipeline's packed-colour convention (r<<24 | g<<16 |
  // b<<8 | a), independent of the memory byte order.
  private val blended = Cat(
    blend(w => w(31, 24)), // red
    blend(w => w(23, 16)), // green
    blend(w => w(15, 8)), // blue
    blend(w => w(7, 0))
  )

  private def trilinearChannel(shift: Int): UInt = {
    val first = firstLevelResult(shift + 7, shift)
    val second = blendedReg(shift + 7, shift)
    (((first * (256.U - lodFracReg)) + second * lodFracReg) >> 8)(7, 0)
  }
  private val trilinear = Cat(
    trilinearChannel(24), trilinearChannel(16), trilinearChannel(8), trilinearChannel(0))

  io.result.valid := resultValid.getOrElse(state === sDone)
  io.result.bits := Mux(policyFault.getOrElse(false.B),0.U,resultReg)

  // Keep the trilinear multiply behind a register for both the filtered path
  // and the single-texel path. Each packed mip still has its own address,
  // even after its dimensions have reached 1x1.
  private def finishBilinear(value: UInt): Unit = {
    blendedReg := value
    when(!secondLevel && lodFracReg =/= 0.U) {
      firstLevelResult := value
      levelBase := bindingBase.getOrElse(io.texBase)
      levelW := bindingWidth.getOrElse(io.texWidth)
      levelH := bindingHeight.getOrElse(io.texHeight)
      levelSteps := baseMipLevel + 1.U
      secondLevel := true.B
      state := sLevel
    }.elsewhen(lodFracReg === 0.U) {
      // A non-fractional sample already has its final packed result. Avoid
      // the extra register-forwarding cycle; fractional trilinear mixing
      // still consumes blendedReg on the following cycle.
      completeResult(value)
    }.otherwise {
      state := sMix
    }
  }

  /** Cache lookup follows the registered tap addresses, keeping its CAM
    * and data mux off the address-multiply stage's combinational path.
    * The first uncached tap can issue during lookup; no extra miss bubble. */
  private def beginTapFetch(addresses: Seq[UInt]): Unit = {
    for (i <- 0 until 4) { tapAddrs(i) := addresses(i) }
    tapIssued := 0.U
    tapReceived := 0.U
    state := sFetch
    if (wordCacheEntries > 0) { cacheLookupPending.get := cacheValid.get.orR }
  }

  private def loadCachedTaps(): Unit = {
    if (wordCacheEntries > 0) {
      cacheLookupPending.get := false.B
      val hits = cachedTapHits.get
      val ready = cachedTapReady
      for (tap <- 0 until 4) {
        when(hits(tap).orR) {
          tapWords(tap) := PixelFormat.decode(
            cacheWords.get(PriorityEncoder(hits(tap))), formatReg)
        }
      }
      tapIssued := Mux(io.mem.req.fire,
        ready | UIntToOH(issueIdx, 4), ready)
      tapReceived := ready
      when(ready.andR) {
        when(singleTexel) {
          val decoded = PixelFormat.decode(cacheWords.get(PriorityEncoder(hits(0))), formatReg)
          when(!secondLevel && lodFracReg === 0.U) {
            completeResult(decoded)
          }.otherwise { finishBilinear(decoded) }
        }.otherwise { state := sBlend }
      }
    }
  }

  private def fetchMemory(): Unit = {
    when(io.mem.req.fire) {
      tapIssued := tapIssued | UIntToOH(issueIdx, 4)
    }
    when(io.mem.resp.fire) {
      assert(!io.mem.resp.bits.write,
        "texture reads must not receive a write acknowledgement")
      assert(responseMatchBits.orR,
        "texture response must match an issued, pending tap address")
    }
    when(responseAccepted && responseMatchBits.orR) {
      for (i <- 0 until 4) {
        when(responseFillBits(i)) { tapWords(i) := PixelFormat.decode(io.mem.resp.bits.data, formatReg) }
      }
      tapReceived := receivedAfterResponse
      when(receivedAfterResponse.andR) {
        when(singleTexel) {
          val decoded = PixelFormat.decode(io.mem.resp.bits.data, formatReg)
          when(!secondLevel && lodFracReg === 0.U) {
            completeResult(decoded)
          }.otherwise {
            finishBilinear(decoded)
          }
        }.otherwise {
          state := sBlend
        }
      }
    }
  }

  private def acceptSample(): Unit = {
    formatReg := io.texFormat
    assert(io.texWidth =/= 0.U && io.texHeight =/= 0.U,
      "texture dimensions must be non-zero")
    uReg := io.sample.bits.u
    vReg := io.sample.bits.v
    if (overlapPrepared) {
      productCapture := true.B
    }
    levelBase := io.texBase
    levelW := io.texWidth
    levelH := io.texHeight
    val selectedLevel = Mux(io.sample.bits.mipLevel > io.texMaxLevel,
      io.texMaxLevel, io.sample.bits.mipLevel)
    levelSteps := selectedLevel
    baseMipLevel := selectedLevel
    // There is no next level at the upper clamp, so suppress the second
    // four-tap fetch rather than blending a level with itself.
    // min(requested, max) < max iff requested < max. Keep the clamp
    // mux out of the fractional-weight register's timing cone.
    lodFracReg := Mux(io.sample.bits.mipLevel < io.texMaxLevel,
      io.sample.bits.lodFrac, 0.U)
    secondLevel := false.B
    state := sLevel
    if(coordinatePolicyInput){
      policy.get:=io.coordinatePolicy.get;policyFault.get:= !TextureCoordinatePolicy.valid(io.coordinatePolicy.get)
      bindingBase.get:=io.texBase;bindingWidth.get:=io.texWidth;bindingHeight.get:=io.texHeight
      when(!TextureCoordinatePolicy.valid(io.coordinatePolicy.get)){resultReg:=0.U;state:=sDone}
    }
    if (allowPrepared && !overlapPrepared) {
      when(io.prepared.get.valid) {
        assert(io.sample.bits.mipLevel === 0.U && io.sample.bits.lodFrac === 0.U &&
          io.texMaxLevel === 0.U, "prepared samples require fixed base-level filtering")
        singleTexel := false.B
        wReg := io.prepared.get.bits.weights
        beginTapFetch(io.prepared.get.bits.addresses.toSeq)
      }
    }
  }

  switch(state) {
    is(sIdle) {
      when(io.sample.fire && (!io.prepared.map(_.valid).getOrElse(false.B) || (!overlapPrepared).B)) { acceptSample() }
    }
    is(sLevel) {
      when(levelSteps === 0.U) {
        val isSingleTexel = levelW === 1.U && levelH === 1.U
        singleTexel := isSingleTexel
        when(isSingleTexel) {
          // All four taps are the same for every coordinate and wrap mode.
          beginTapFetch(Seq.fill(4)(levelBase))
        }.otherwise {
          if (overlapPrepared) {
            val products = axisProducts.get
            val scaledX = Wire(UInt(23.W))
            val scaledY = Wire(UInt(23.W))
            scaledX := (products(1) << 8) + products(0)(29, 8)
            scaledY := (products(3) << 8) + products(2)(29, 8)
            // A positive period sentinel saturates to period-1 at sWrap.
            biasedXReg := Mux(axisClampedHigh.get(0), (levelW << 8).pad(24),
              TextureCoordinates.bias(scaledX))
            biasedYReg := Mux(axisClampedHigh.get(1), (levelH << 8).pad(24),
              TextureCoordinates.bias(scaledY))
          } else {
            if(coordinatePolicyInput){
              biasedXReg:=TextureCoordinates.bias(TextureCoordinates.scale(uReg,levelW,policy.get.wrapU,policy.get.signed))
              biasedYReg:=TextureCoordinates.bias(TextureCoordinates.scale(vReg,levelH,policy.get.wrapV,policy.get.signed))
            }else{
              biasedXReg := axisBias(uReg, levelW)
              biasedYReg := axisBias(vReg, levelH)
            }
          }
          state := sWrap
        }
      }.otherwise {
        if (overlapPrepared) { productCapture := true.B }
        levelBase := levelBase + ((levelW * levelH) << 2)
        levelW := Mux(levelW === 1.U, 1.U, levelW >> 1)
        levelH := Mux(levelH === 1.U, 1.U, levelH >> 1)
        levelSteps := levelSteps - 1.U
      }
    }
    is(sWrap) {
      inRangeXReg := TextureCoordinates.range(biasedXReg, levelW, clampU)
      inRangeYReg := TextureCoordinates.range(biasedYReg, levelH, clampV)
      state := sIdx
    }
    is(sIdx) {
      // Clamp/wrap decode: corner indices and fractions off the wrapped
      // coordinate.
      val Seq(idx0X, idx1X) = TextureCoordinates.indices(inRangeXReg, levelW, clampU)
      val Seq(idx0Y, idx1Y) = TextureCoordinates.indices(inRangeYReg, levelH, clampV)
      wReg(0) := (256.U - inRangeXReg(7, 0)) * (256.U - inRangeYReg(7, 0))
      wReg(1) := inRangeXReg(7, 0) * (256.U - inRangeYReg(7, 0))
      wReg(2) := (256.U - inRangeXReg(7, 0)) * inRangeYReg(7, 0)
      wReg(3) := inRangeXReg(7, 0) * inRangeYReg(7, 0)
      idxXReg(0) := idx0X
      idxXReg(1) := idx1X
      idxYReg(0) := idx0Y
      idxYReg(1) := idx1Y
      state := sTap
    }
    is(sTap) {
      // Tap addresses: one index-by-width multiply plus the level base.
      beginTapFetch(Seq(
        tapAddr(idxXReg(0), idxYReg(0)), tapAddr(idxXReg(1), idxYReg(0)),
        tapAddr(idxXReg(0), idxYReg(1)), tapAddr(idxXReg(1), idxYReg(1))))
    }
    is(sFetch) {
      if (wordCacheEntries > 0) {
        when(cacheLookupPending.get) { loadCachedTaps() }
          .otherwise { fetchMemory() }
      } else { fetchMemory() }
    }
    is(sBlend) {
      when(resultSpace) {
        finishBilinear(blended)
        if (overlapPrepared) { when(io.sample.fire && !io.prepared.get.valid) { acceptSample() } }
      }
    }
    is(sMix) {
      when(resultSpace) { completeResult(Mux(secondLevel, trilinear, blendedReg)) }
    }
    is(sDone) {
      when(io.result.fire) {
        when(io.sample.fire) { acceptSample() }
          .otherwise { state := sIdle }
      }
    }
  }
  if (overlapPrepared) {
    when(preparedRetire) { completeResult(blended) }
  }
}

/** Physical memory-read concurrency, independent of the shader vector length.
  * Bilinear samples have four taps; the two-context prepared window shares this read budget.
  * A prepared successor can fetch while its predecessor holds a result.
  * Update this contract together with the owner/word credits if that changes.
  */
object TextureUnit {
  val maxOutstandingReads: Int = 4
}
