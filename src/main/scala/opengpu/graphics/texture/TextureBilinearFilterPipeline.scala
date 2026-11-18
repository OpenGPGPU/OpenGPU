package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32FmaLane, Fp32Operation, Fp32Request}

private[graphics] class TexturePairInput(pairs: Int, tagWidth: Int) extends Bundle {
  val values = Vec(2 * pairs, Vec(4, UInt(32.W)))
  val weight = UInt(32.W)
  val tag = UInt(tagWidth.W)
}
private[graphics] class TexturePairOutput(pairs: Int, tagWidth: Int) extends Bundle {
  val values = Vec(pairs, Vec(4, UInt(32.W)))
  val tag = UInt(tagWidth.W)
}

/** A named elastic boundary keeps its emitted definition independent of the
  * arithmetic queues. It bypasses while empty and closes admission while full.
  */
private[graphics] class TextureBilinearAxisRows extends Queue[TexturePairOutput](
  new TexturePairOutput(2, 65), 2, pipe = false, flow = true) {
  override def desiredName: String = "TextureBilinearAxisRows"
}

/** One axis of the native FP32 filter tree. Every arithmetic and metadata
  * queue advances the same ordered rows. Credits cover the FMA's complete
  * round trip, so the three arithmetic phases can each accept one row/cycle.
  * Endpoint/equal-bit bypasses preserve the serial filter's exact semantics.
  * Cancellation belongs to the outer epoch owner; accepted arithmetic drains.
  */
private[graphics] class TexturePairPipeline(pairs: Int, tagWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TexturePairInput(pairs, tagWidth)))
    val out = Decoupled(new TexturePairOutput(pairs, tagWidth))
  })
  private val entries = 16
  private val inverse = Module(new Fp32FmaLane(1, resultEntries = entries))
  private val products = Seq.fill(4 * pairs)(Module(new Fp32FmaLane(1, resultEntries = entries)))
  private val sums = Seq.fill(4 * pairs)(Module(new Fp32FmaLane(1, resultEntries = entries)))
  (Seq(inverse) ++ products ++ sums).foreach(_.io.flush := false.B)
  private val inverseRows = Module(new Queue(new TexturePairInput(pairs, tagWidth), entries))
  private val productRows = Module(new Queue(new TexturePairInput(pairs, tagWidth), entries))
  private val sumRows = Module(new Queue(new TexturePairInput(pairs, tagWidth), entries))

  private def operation(lane: Fp32FmaLane, op: Fp32Operation.Type,
                        a: UInt, b: UInt, c: UInt, subtract: Boolean = false): Unit = {
    lane.io.in.bits := 0.U.asTypeOf(new Fp32Request(1))
    lane.io.in.bits.operation := op
    lane.io.in.bits.operandA := a
    lane.io.in.bits.operandB := b
    lane.io.in.bits.operandC := c
    lane.io.in.bits.operationModifier := subtract.B
  }

  operation(inverse, Fp32Operation.add, 0.U, "h3f800000".U, io.in.bits.weight, subtract = true)
  io.in.ready := inverse.io.in.ready && inverseRows.io.enq.ready
  inverse.io.in.valid := io.in.valid && inverseRows.io.enq.ready
  inverseRows.io.enq.valid := io.in.valid && inverse.io.in.ready
  inverseRows.io.enq.bits := io.in.bits

  private val productReady = products.map(_.io.in.ready).reduce(_ && _) && productRows.io.enq.ready
  private val inverseValid = inverse.io.out.valid && inverseRows.io.deq.valid
  inverse.io.out.ready := inverseRows.io.deq.valid && productReady
  inverseRows.io.deq.ready := inverse.io.out.valid && productReady
  productRows.io.enq.valid := inverseValid && products.map(_.io.in.ready).reduce(_ && _)
  productRows.io.enq.bits := inverseRows.io.deq.bits
  for ((lane, i) <- products.zipWithIndex) {
    operation(lane, Fp32Operation.mul, inverseRows.io.deq.bits.values(2 * (i / 4))(i % 4),
      inverse.io.out.bits.result, 0.U)
    lane.io.in.valid := inverseValid && productReady
  }

  private val sumReady = sums.map(_.io.in.ready).reduce(_ && _) && sumRows.io.enq.ready
  private val productsValid = products.map(_.io.out.valid).reduce(_ && _) && productRows.io.deq.valid
  products.foreach(_.io.out.ready := productsValid && sumReady)
  productRows.io.deq.ready := products.map(_.io.out.valid).reduce(_ && _) && sumReady
  sumRows.io.enq.valid := productsValid && sums.map(_.io.in.ready).reduce(_ && _)
  sumRows.io.enq.bits := productRows.io.deq.bits
  for ((lane, i) <- sums.zipWithIndex) {
    operation(lane, Fp32Operation.fmadd, productRows.io.deq.bits.weight,
      productRows.io.deq.bits.values(2 * (i / 4) + 1)(i % 4), products(i).io.out.bits.result)
    lane.io.in.valid := productsValid && sumReady
  }

  private val sumsValid = sums.map(_.io.out.valid).reduce(_ && _)
  io.out.valid := sumsValid && sumRows.io.deq.valid
  sums.foreach(_.io.out.ready := io.out.ready && io.out.valid)
  sumRows.io.deq.ready := io.out.ready && sumsValid
  io.out.bits.tag := sumRows.io.deq.bits.tag
  for (p <- 0 until pairs; c <- 0 until 4) {
    val row = sumRows.io.deq.bits
    val a = row.values(2 * p)(c)
    val b = row.values(2 * p + 1)(c)
    io.out.bits.values(p)(c) := Mux(row.weight(30, 0) === 0.U || a === b, a,
      Mux(row.weight === "h3f800000".U, b, sums(4 * p + c).io.out.bits.result))
  }
}

/** Two-axis FP32 filtering with the same separately rounded inverse/product
  * and fused sum as TextureLinearFilterUnit. It accepts four decoded FP32
  * texels and unquantized FP32 weights, with axis zero varying fastest.
  *
  * Global cancellation closes admission until every accepted row, including
  * the immutable public result, retires. Hidden rows receive sticky epoch
  * cancellation, while invalid-weight faults keep priority. No FMA is flushed.
  */
class TextureBilinearFilterPipeline(entries: Int = 128) extends Module {
  require(entries >= 1)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureLinearFilterRequest(2)))
    val out = Decoupled(new TextureLinearFilterResult)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val x = Module(new TexturePairPipeline(2, 65))
  private val y = Module(new TexturePairPipeline(1, 33))
  private val public = Module(new Queue(new TextureLinearFilterResult, 1, pipe = true))
  private val count = RegInit(0.U(log2Ceil(entries + 1).W))
  private val draining = RegInit(false.B)
  io.busy := count =/= 0.U
  when(io.cancel && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }

  private val invalid = io.in.bits.axes =/= 2.U || io.in.bits.weights.map(w =>
    (w(31) && w(30, 0).orR) || w(30, 0) > "h3f800000".U).reduce(_ || _)
  x.io.in.valid := io.in.valid && !io.cancel && !draining && count < entries.U
  io.in.ready := x.io.in.ready && !io.cancel && !draining && count < entries.U
  x.io.in.bits.values := io.in.bits.values
  x.io.in.bits.weight := io.in.bits.weights(0)
  x.io.in.bits.tag := Cat(io.in.bits.weights(1), invalid, io.in.bits.tag)
  // A full two-row buffer stops the reverse-ready path at this axis
  // boundary. Empty flow-through preserves the unstalled arithmetic latency;
  // pipe=false keeps y's arithmetic credit tree off x's admission path.
  private val axisRows = Module(new TextureBilinearAxisRows)
  axisRows.io.enq <> x.io.out
  y.io.in.valid := axisRows.io.deq.valid
  axisRows.io.deq.ready := y.io.in.ready
  y.io.in.bits.values := axisRows.io.deq.bits.values
  y.io.in.bits.weight := axisRows.io.deq.bits.tag(64, 33)
  y.io.in.bits.tag := axisRows.io.deq.bits.tag(32, 0)

  public.io.enq.valid := y.io.out.valid
  y.io.out.ready := public.io.enq.ready
  public.io.enq.bits.tag := y.io.out.bits.tag(31, 0)
  public.io.enq.bits.status := Mux(y.io.out.bits.tag(32), TextureImageFetchStatus.format.U,
    Mux(draining || io.cancel, TextureImageResourceStatus.cancelled.U, 0.U))
  public.io.enq.bits.rgba := Mux(public.io.enq.bits.status === 0.U, y.io.out.bits.values(0),
    0.U.asTypeOf(public.io.enq.bits.rgba))
  io.out <> public.io.deq
  switch(Cat(io.in.fire, io.out.fire)) {
    is("b10".U) { count := count + 1.U }
    is("b01".U) { count := count - 1.U }
  }
  assert(count <= entries.U)
  when(io.out.valid) { assert(io.busy) }
}
