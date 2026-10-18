package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, IterativeSignedDivider, SignedProductParts, SplitSignedProduct}

/** Exact viewport preparation with bounded arithmetic between registers.
  * Accept start while idle, snapshot the complete triangle and viewport, and
  * retain done/results until the next admission. All w inputs must be nonzero.
  * Nine dividers run in parallel; positive power-of-two w with nonnegative
  * coordinate numerators uses an exact shift shortcut. Neither path rounds
  * or approximates the GeometryStage formula.
  */
class PreparedGeometryStage(config: GraphicsConfig) extends Module {
  require(config.subPixelBits >= 0)
  private val numeratorWidth = 50 + config.subPixelBits
  val io = IO(new Bundle {
    val clip = Input(Vec(3, new ClipVertex))
    val color = Input(Vec(3, new Varyings))
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val out = Output(Vec(3, new ScreenVertex(config)))
  })

  private val idle :: products :: assemble :: dispatch :: collect :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val complete = RegInit(false.B)
  private val sums = Reg(Vec(6, SInt(33.W)))
  private val dimensions = Reg(Vec(2, UInt(16.W)))
  private val w = Reg(Vec(3, SInt(32.W)))
  private val denominators = Reg(Vec(3, SInt(34.W)))
  private val colors = Reg(Vec(3, new Varyings))
  private val parts = Reg(Vec(6, new SignedProductParts(34)))
  private val numerators = Reg(Vec(6, SInt(numeratorWidth.W)))
  private val logarithms = Reg(Vec(3, UInt(5.W)))
  private val shortcut = Reg(Bool())
  private val result = Reg(Vec(3, new ScreenVertex(config)))

  private val coordinateDividers = Seq.fill(6)(Module(
    new IterativeSignedDivider(numeratorWidth, 34)))
  private val reciprocalDividers = Seq.fill(3)(Module(new IterativeSignedDivider(34, 34)))
  private val dividers = coordinateDividers ++ reciprocalDividers
  private val allReady = dividers.map(_.io.in.ready).reduce(_ && _)
  private val allResults = dividers.map(_.io.out.valid).reduce(_ && _)
  private val issue = state === dispatch && !shortcut && allReady
  for (d <- dividers) {
    d.io.in.valid := issue
    d.io.out.ready := state === collect && allResults
  }
  for (k <- 0 until 6) {
    coordinateDividers(k).io.in.bits.numerator := numerators(k)
    coordinateDividers(k).io.in.bits.denominator := denominators(k / 2)
  }
  for (i <- 0 until 3) {
    reciprocalDividers(i).io.in.bits.numerator := (BigInt(1) << 32).S(34.W)
    // GeometryStage defines the reciprocal using the raw unsigned w bits.
    reciprocalDividers(i).io.in.bits.denominator := w(i).asUInt.pad(34).asSInt
  }

  private def coordinateSum(a: SInt, b: SInt): SInt = {
    val adder = Module(new BlockPrefixAdder(33))
    adder.io.a := a.pad(33).asUInt
    adder.io.b := b.pad(33).asUInt
    adder.io.carryIn := false.B
    adder.io.sum(32, 0).asSInt
  }
  private val inputSums = (0 until 3).flatMap { i => Seq(
    coordinateSum(io.clip(i).x, io.clip(i).w),
    coordinateSum(io.clip(i).y, io.clip(i).w)) }
  private val splitProducts = (0 until 6).map { k =>
    SplitSignedProduct.split(sums(k).pad(34), dimensions(k % 2).pad(34).asSInt)
  }
  private val joinedProducts = parts.map(p => SplitSignedProduct.join(p)(49, 0).asSInt)
  when(state === idle && io.start) {
    for (i <- 0 until 3) {
      assert(io.clip(i).w =/= 0.S, "viewport division requires nonzero w")
      sums(i * 2) := inputSums(i * 2)
      sums(i * 2 + 1) := inputSums(i * 2 + 1)
      w(i) := io.clip(i).w
      denominators(i) := io.clip(i).w.pad(33) << 1
      colors(i) := io.color(i)
    }
    dimensions(0) := io.screenW
    dimensions(1) := io.screenH
    complete := false.B
    state := products
  }.elsewhen(state === products) {
    for (k <- 0 until 6) {
      parts(k) := splitProducts(k)
    }
    for (i <- 0 until 3) logarithms(i) := PriorityEncoder(w(i).asUInt)
    shortcut := (0 until 3).map { i =>
      w(i) > 0.S && PopCount(w(i).asUInt) === 1.U &&
        sums(i * 2) >= 0.S && sums(i * 2 + 1) >= 0.S
    }.reduce(_ && _)
    state := assemble
  }.elsewhen(state === assemble) {
    for (k <- 0 until 6) {
      // Signed33 times unsigned16 fits signed50 for every input, including
      // coordinates outside the frustum. Discard only redundant sign bits.
      numerators(k) := joinedProducts(k) << config.subPixelBits
    }
    state := dispatch
  }.elsewhen(state === dispatch) {
    when(shortcut) {
      for (i <- 0 until 3) {
        val shift = logarithms(i) +& 1.U
        result(i).sx := (numerators(i * 2).asUInt >> shift)
          .pad(config.edgeWidth)(config.edgeWidth - 1, 0).asSInt
        result(i).sy := (numerators(i * 2 + 1).asUInt >> shift)
          .pad(config.edgeWidth)(config.edgeWidth - 1, 0).asSInt
        result(i).invW := ((BigInt(1) << 32).U(33.W) >> logarithms(i))(31, 0).asSInt
        result(i).color := colors(i)
      }
      complete := true.B
      state := idle
    }.elsewhen(allReady) {
      state := collect
    }
  }.elsewhen(state === collect && allResults) {
    for (d <- dividers) {
      assert(!d.io.out.bits.divideByZero && !d.io.out.bits.magnitudeOverflow)
    }
    for (i <- 0 until 3) {
      result(i).sx := coordinateDividers(i * 2).io.out.bits.quotient
        .pad(config.edgeWidth)(config.edgeWidth - 1, 0).asSInt
      result(i).sy := coordinateDividers(i * 2 + 1).io.out.bits.quotient
        .pad(config.edgeWidth)(config.edgeWidth - 1, 0).asSInt
      result(i).invW := reciprocalDividers(i).io.out.bits.quotient(31, 0).asSInt
      result(i).color := colors(i)
    }
    complete := true.B
    state := idle
  }
  io.busy := state =/= idle
  io.done := complete
  io.out := result
}
