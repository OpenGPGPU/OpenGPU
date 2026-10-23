package opengpu.util

import chisel3._

/** Exact 64-bit event counter with carry-save state.
  *
  * A plain `counter + 1.U` commonly becomes a 64-bit ripple chain in the
  * open-source ASAP7 flow. Keeping the count as `sum + carry` makes the
  * every-cycle feedback update bitwise; the carry-propagate addition exists
  * only on the observation output. A registered observer may still need the
  * optional prefix adder to bound its observation path. The architectural value and one-event-per-cycle throughput
  * remain unchanged.
  */
class CarrySaveEventCounter(
  width: Int = 64
) extends Module {
  require(width > 0)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val increment = Input(Bool())
    val value = Output(UInt(width.W))
  })

  private val accumulator = Module(new CarrySaveAccumulator(width))
  accumulator.io.clear := io.clear
  accumulator.io.addend := io.increment.asUInt
  io.value := accumulator.io.value
}

/** Exact wide accumulator with carry-save state.
  *
  * Same rationale as [[CarrySaveEventCounter]], but accepts an arbitrary
  * addend each cycle (use 0.U when idle). `sum + carry` is unchanged when the
  * addend is zero, so observation stays bit-exact.
  */
class CarrySaveAccumulator(
  width: Int = 64,
  prefixValue: Boolean = false,
  initialValue: BigInt = 0,
  registeredObservation: Boolean = false
) extends Module {
  require(width > 0)
  require(initialValue >= 0 && initialValue < (BigInt(1) << width))

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val addend = Input(UInt(width.W))
    val value = Output(UInt(width.W))
    /** Exact observation delayed by two clocks; clear retains priority. */
    val registeredValue = if(registeredObservation)Some(Output(UInt(width.W)))else None
  })

  private val sum = RegInit(initialValue.U(width.W))
  private val carry = RegInit(0.U(width.W))
  private val addend = io.addend
  private val nextSum = sum ^ carry ^ addend
  private val carryGenerate = (sum & carry) | (sum & addend) | (carry & addend)
  private val nextCarry = (carryGenerate << 1)(width - 1, 0)

  when(io.clear) {
    sum := initialValue.U
    carry := 0.U
  }.otherwise {
    sum := nextSum
    carry := nextCarry
  }

  if(registeredObservation){
    // Capture independent short additions before combining their carries.
    // Wide completion fields must not rebuild a full-width carry chain.
    val chunkBits=32
    val sizes=(0 until width by chunkBits).map(lo=>math.min(chunkBits,width-lo))
    val parts=sizes.zipWithIndex.map{case(bits,i)=>
      val lo=i*chunkBits;val initial=(initialValue>>lo)&((BigInt(1)<<bits)-1)
      val part=RegInit(initial.U((bits+1).W))
      when(io.clear){part:=initial.U}.otherwise{part:=sum(lo+bits-1,lo)+&carry(lo+bits-1,lo)}
      part
    }
    var carryIn: Bool=false.B
    val completed=parts.zip(sizes).map{case(part,bits)=>
      val incoming=carryIn
      carryIn=part(bits) || (part(bits-1,0).andR && incoming)
      (part(bits-1,0)+incoming.asUInt)(bits-1,0)
    }
    val observed=RegInit(initialValue.U(width.W))
    when(io.clear){observed:=initialValue.U}.otherwise{observed:=chisel3.util.Cat(completed.reverse)}
    io.registeredValue.get:=observed
  }

  if (prefixValue) {
    val finish = Module(new BlockPrefixAdder(width))
    finish.io.a := sum; finish.io.b := carry; finish.io.carryIn := false.B
    io.value := finish.io.sum(width-1,0)
  } else io.value := (sum +& carry)(width - 1, 0)
}
