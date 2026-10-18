package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, IterativeSignedDivider, SignedProductParts, SplitSignedProduct}

/** A clip-space vertex with every varying needed after clipping. */
class ClipVertexColor(config: GraphicsConfig) extends Bundle {
  val x = SInt(32.W)
  val y = SInt(32.W)
  val z = SInt(32.W)
  val w = SInt(32.W)
  val color = new Varyings
  val alpha = UInt(8.W)
  val depth = SInt(32.W)
  val uv = new TexUV
}

object NearClipStage {
  // A triangle gains at most one vertex per clipping plane. The positive-w
  // guard precedes the six canonical clip-space planes.
  val PlaneCount = 7
  val MaxVertices = 3 + PlaneCount
  val MaxTriangles = MaxVertices - 2
  val InterpolatedFields = 11
}

/** Preserve every signed34 distance while sharing a bounded carry tree.
  * Select the coordinate before arithmetic; all planes use w +/- coordinate.
  */
private[graphics] object ClipDistanceMath {
  def apply(v: ClipVertexColor, which: UInt, near: UInt): SInt = {
    val coordinate = MuxLookup(which, near.pad(34).asSInt)(Seq(
      1.U -> v.x.pad(34), 2.U -> v.x.pad(34),
      3.U -> v.y.pad(34), 4.U -> v.y.pad(34),
      5.U -> v.z.pad(34), 6.U -> v.z.pad(34)))
    val add = which === 1.U || which === 3.U || which === 5.U
    val adder = Module(new BlockPrefixAdder(34))
    adder.io.a := v.w.pad(34).asUInt
    adder.io.b := Mux(add, coordinate.asUInt, ~coordinate.asUInt)
    adder.io.carryIn := !add
    adder.io.sum(33, 0).asSInt
  }
}

/** Sequential Sutherland-Hodgman clipping against the complete view volume.
  *
  * The positive-w guard (`w >= wNear`) keeps the perspective divide finite;
  * the remaining planes implement `-w <= x,y,z <= w`. A triangle can grow
  * into a convex polygon with at most ten vertices and is returned as a fan of
  * up to eight triangles. Position, colour, depth and UV are all interpolated
  * at newly created vertices.
  *
  * `start` is accepted while idle. `done` and the result remain asserted and
  * stable until the next accepted start.
  */
class NearClipStage(config: GraphicsConfig) extends Module {
  import NearClipStage._

  private val countWidth = log2Ceil(MaxVertices + 1)
  private val planeWidth = log2Ceil(PlaneCount)
  private val fieldWidth = 4

  val io = IO(new Bundle {
    val tri = Input(Vec(3, new ClipVertexColor(config)))
    val wNear = Input(UInt(16.W))
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val out = Output(Vec(MaxTriangles * 3, new ClipVertexColor(config)))
    val outValid = Output(UInt(log2Ceil(MaxTriangles + 1).W))
  })

  private val sIdle :: sCheckInside :: sEdges :: sClassify :: sInterpolate :: sParts :: sAssemble :: sDivide :: sRetire :: sAppend :: sAdvance :: Nil = Enum(11)
  private val state = RegInit(sIdle)
  private val ready = RegInit(false.B)
  private val current = Reg(Vec(MaxVertices, new ClipVertexColor(config)))
  private val next = Reg(Vec(MaxVertices, new ClipVertexColor(config)))
  private val inputCount = RegInit(0.U(countWidth.W))
  private val outputCount = RegInit(0.U(countWidth.W))
  private val finalCount = RegInit(0.U(countWidth.W))
  private val triangleValid = RegInit(VecInit(Seq.fill(MaxTriangles)(false.B)))
  private val edgeIndex = RegInit(0.U(countWidth.W))
  private val plane = RegInit(0.U(planeWidth.W))
  private val field = RegInit(0.U(fieldWidth.W))
  private val edgePrev = Reg(new ClipVertexColor(config))
  private val edgeCur = Reg(new ClipVertexColor(config))
  private val edgePrevDistance = Reg(SInt(34.W))
  private val edgeCurDistance = Reg(SInt(34.W))
  private val edgeEntering = RegInit(false.B)
  private val intersection = Reg(new ClipVertexColor(config))
  private val intersectionBase = Reg(UInt(32.W))
  private val numeratorReg = Reg(SInt(68.W))
  private val denominatorReg = Reg(SInt(34.W))
  private val deltaReg = Reg(SInt(34.W))
  private val productParts = Reg(new SignedProductParts(34))
  // A crossing ratio is in[0,1]. Each field's endpoint difference has
  // magnitude<=2^32-1, so its intersection quotient fits32 magnitude bits.
  // Return those exact low32 bits and assert the bound at retirement.
  private val divider = Module(new IterativeSignedDivider(68, 34, 32))
  divider.io.in.valid := state === sDivide
  divider.io.in.bits.numerator := numeratorReg
  divider.io.in.bits.denominator := denominatorReg
  divider.io.out.ready := state === sRetire
  /** Signed distance with inside represented by distance >= 0. */
  private def distance(v: ClipVertexColor, which: UInt): SInt =
    ClipDistanceMath(v, which, io.wNear)

  when(state === sIdle && io.start) {
    for (i <- 0 until 3) { current(i) := io.tri(i) }
    inputCount := 3.U
    outputCount := 0.U
    finalCount := 0.U
    triangleValid.foreach(_ := false.B)
    edgeIndex := 0.U
    plane := 0.U
    ready := false.B
    state := sCheckInside
  }.elsewhen(state === sCheckInside) {
    // Most triangles need no intersections. Check the captured vertices in
    // parallel before entering the serial polygon walk. Constant plane
    // selectors remove the coordinate-selection muxes from this check.
    val inside = (for (i <- 0 until 3; p <- 0 until PlaneCount)
      yield distance(current(i), p.U) >= 0.S).reduce(_ && _)
    when(inside) {
      finalCount := 3.U
      triangleValid(0) := true.B
      ready := true.B
      state := sIdle
    }.otherwise {
      state := sEdges
    }
  }.elsewhen(state === sEdges) {
    val cur = current(edgeIndex)
    val prevIndex = Mux(edgeIndex === 0.U, inputCount - 1.U, edgeIndex - 1.U)
    val prev = current(prevIndex)
    val prevDistance = distance(prev, plane)
    val curDistance = distance(cur, plane)

    // Capture edge payload independently of classification. Only crossing
    // edges consume it. Likewise, an uncounted next-polygon slot may hold a
    // temporary current vertex: append overwrites it for crossing edges,
    // later accepted vertices overwrite dropped edges, and inputCount bounds
    // all subsequent reads. Keep distance/sign logic off wide write enables.
    edgePrev := prev
    edgeCur := cur
    edgePrevDistance := prevDistance
    edgeCurDistance := curDistance
    when(outputCount < MaxVertices.U) { next(outputCount) := cur }
    // Register distance/sign arithmetic before polygon-count feedback.
    state := sClassify
  }.elsewhen(state === sClassify) {
    val prevIn = edgePrevDistance >= 0.S
    val curIn = edgeCurDistance >= 0.S
    edgeEntering := curIn
    when(curIn =/= prevIn) {
      field := 0.U
      state := sInterpolate
    }.elsewhen(curIn) {
      assert(outputCount < MaxVertices.U,
        "frustum clip polygon exceeded its vertex bound")
      outputCount := outputCount + 1.U
    }

    when(curIn === prevIn) {
      when(edgeIndex === inputCount - 1.U) {
        state := sAdvance
      }.otherwise {
        edgeIndex := edgeIndex + 1.U
        state := sEdges
      }
    }
  }.elsewhen(state === sInterpolate) {
    // Signed32 positions/depth and unsigned32 UVs all fit in signed34.
    // Their difference fits signed34 too; multiplying by a signed34 edge
    // distance needs68 bits. Preserve the original34-bit denominator subtract.
    val a = MuxLookup(field, edgePrev.x.pad(34))(Seq(
      0.U -> edgePrev.x.pad(34),
      1.U -> edgePrev.y.pad(34),
      2.U -> edgePrev.z.pad(34),
      3.U -> edgePrev.w.pad(34),
      4.U -> edgePrev.color.r.pad(34).asSInt,
      5.U -> edgePrev.color.g.pad(34).asSInt,
      6.U -> edgePrev.color.b.pad(34).asSInt,
      10.U -> edgePrev.alpha.pad(34).asSInt,
      7.U -> edgePrev.depth.pad(34),
      8.U -> edgePrev.uv.u.pad(34).asSInt,
      9.U -> edgePrev.uv.v.pad(34).asSInt
    ))
    val b = MuxLookup(field, edgeCur.x.pad(34))(Seq(
      0.U -> edgeCur.x.pad(34),
      1.U -> edgeCur.y.pad(34),
      2.U -> edgeCur.z.pad(34),
      3.U -> edgeCur.w.pad(34),
      4.U -> edgeCur.color.r.pad(34).asSInt,
      5.U -> edgeCur.color.g.pad(34).asSInt,
      6.U -> edgeCur.color.b.pad(34).asSInt,
      10.U -> edgeCur.alpha.pad(34).asSInt,
      7.U -> edgeCur.depth.pad(34),
      8.U -> edgeCur.uv.u.pad(34).asSInt,
      9.U -> edgeCur.uv.v.pad(34).asSInt
    ))
    deltaReg := b - a
    denominatorReg := edgePrevDistance - edgeCurDistance
    intersectionBase := a(31, 0)
    state := sParts
  }.elsewhen(state === sParts) {
    productParts := SplitSignedProduct.split(deltaReg, edgePrevDistance)
    state := sAssemble
  }.elsewhen(state === sAssemble) {
    numeratorReg := SplitSignedProduct.join(productParts)
    state := sDivide
  }.elsewhen(state === sDivide) {
    assert(denominatorReg =/= 0.S, "crossing clip edge must have a nonzero distance difference")
    when(divider.io.in.fire) { state := sRetire }
  }.elsewhen(state === sRetire && divider.io.out.fire) {
    assert(!divider.io.out.bits.divideByZero)
    assert(!divider.io.out.bits.magnitudeOverflow,
      "crossing interpolation quotient must fit32 magnitude bits")
    // Only low32 result bits are observable; addition commutes with truncation.
    val value = intersectionBase + divider.io.out.bits.quotient(31, 0)
    switch(field) {
      is(0.U) { intersection.x := value(31, 0).asSInt }
      is(1.U) { intersection.y := value(31, 0).asSInt }
      is(2.U) { intersection.z := value(31, 0).asSInt }
      is(3.U) { intersection.w := value(31, 0).asSInt }
      is(4.U) { intersection.color.r := value(7, 0) }
      is(5.U) { intersection.color.g := value(7, 0) }
      is(6.U) { intersection.color.b := value(7, 0) }
      is(7.U) { intersection.depth := value(31, 0).asSInt }
      is(10.U) { intersection.alpha := value(7, 0) }
      is(8.U) { intersection.uv.u := value(31, 0) }
      is(9.U) { intersection.uv.v := value(31, 0) }
    }
    when(field === 10.U) {
      state := sAppend
    }.otherwise {
      field := field + 1.U
      state := sInterpolate
    }
  }.elsewhen(state === sAppend) {
    when(edgeEntering) {
      assert(outputCount + 1.U < MaxVertices.U,
        "frustum clip polygon exceeded its vertex bound")
      next(outputCount) := intersection
      next(outputCount + 1.U) := edgeCur
      outputCount := outputCount + 2.U
    }.otherwise {
      assert(outputCount < MaxVertices.U,
        "frustum clip polygon exceeded its vertex bound")
      next(outputCount) := intersection
      outputCount := outputCount + 1.U
    }
    when(edgeIndex === inputCount - 1.U) {
      state := sAdvance
    }.otherwise {
      edgeIndex := edgeIndex + 1.U
      state := sEdges
    }
  }.elsewhen(state === sAdvance) {
    for (i <- 0 until MaxVertices) { current(i) := next(i) }
    when(outputCount < 3.U) {
      finalCount := 0.U
      ready := true.B
      state := sIdle
    }.elsewhen(plane === (PlaneCount - 1).U) {
      finalCount := outputCount
      for (i <- 0 until MaxTriangles) {
        triangleValid(i) := outputCount >= (i + 3).U
      }
      ready := true.B
      state := sIdle
    }.otherwise {
      inputCount := outputCount
      outputCount := 0.U
      edgeIndex := 0.U
      plane := plane + 1.U
      state := sEdges
    }
  }

  io.busy := state =/= sIdle
  io.done := ready
  io.outValid := Mux(ready && finalCount >= 3.U, finalCount - 2.U, 0.U)

  // Fan triangulation: triangle i = polygon vertices (0, i+1, i+2).
  for (i <- 0 until MaxTriangles) {
    // Registered per-triangle enables avoid finalCount decode on every wide
    // output. They clear on admission/reset and publish only the final fan.
    val valid = triangleValid(i)
    io.out(i * 3) := Mux(valid, current(0),
      0.U.asTypeOf(new ClipVertexColor(config)))
    io.out(i * 3 + 1) := Mux(valid, current(i + 1),
      0.U.asTypeOf(new ClipVertexColor(config)))
    io.out(i * 3 + 2) := Mux(valid, current(i + 2),
      0.U.asTypeOf(new ClipVertexColor(config)))
  }
}
