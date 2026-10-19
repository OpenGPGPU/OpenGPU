package opengpu.graphics

import chisel3._
import chisel3.util._

/** A post-shader native quad and the state that owns every expanded sample. */
class TileSamplePixelQuad(maxSampleCount: Int=4) extends Bundle {
  val pixels=Vec(4,new SamplePixel(maxSampleCount))
  val sampleMode=UInt(2.W)
  val originX=UInt(16.W)
  val originY=UInt(16.W)
  val state=new TileSampleMergeState
}

/** Sample-major expansion preserves four-pixel bank parallelism. Each output
  * visits one sample index across the quad, omitting uncovered lanes and empty
  * sample indices. A single-sample quad can replace its predecessor every cycle.
  * Coordinates, coverage, depths and merge state are retained under stalls.
  */
class TileSampleQuadExpander(maxSampleCount: Int=4) extends Module {
  require(Set(1,2,4)(maxSampleCount))
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TileSamplePixelQuad(maxSampleCount)))
    val out=Decoupled(new TileSampleQuad)
    val drained=Output(Bool())
  })
  private val payload=Reg(new TileSamplePixelQuad(maxSampleCount))
  private val remaining=RegInit(0.U(maxSampleCount.W))
  private val ticket=RegInit(0.U(8.W))
  private val sample=if(maxSampleCount==1)0.U(1.W)else PriorityEncoder(remaining)
  private val nextRemaining=remaining & (remaining-1.U)
  private val modeMask=MuxLookup(io.in.bits.sampleMode,0.U(maxSampleCount.W))(
    (0 to log2Ceil(maxSampleCount)).map(m=>m.U->((BigInt(1)<<(1<<m))-1).U(maxSampleCount.W)))
  io.out.valid:=remaining.orR
  io.out.bits.ticket:=ticket
  io.out.bits.state:=payload.state
  io.out.bits.mask:=VecInit(payload.pixels.map(p=>
    if(maxSampleCount==1)p.coverageMask(0)else p.coverageMask(sample))).asUInt
  for(p <- 0 until 4) {
    val x=payload.pixels(p).x-payload.originX
    val y=payload.pixels(p).y-payload.originY
    io.out.bits.index(p):=((Cat(y(3,0),x(3,0))<<payload.sampleMode)|sample)(9,0)
    io.out.bits.color(p):=payload.pixels(p).color
    io.out.bits.depth(p):=payload.pixels(p).depths(sample)(23,0)
  }
  io.in.ready:= !remaining.orR || (io.out.ready && !nextRemaining.orR)
  io.drained:= !remaining.orR
  when(io.out.fire){remaining:=nextRemaining;ticket:=ticket+1.U}
  when(io.in.fire) {
    payload:=io.in.bits
    remaining:=io.in.bits.pixels.map(_.coverageMask).reduce(_|_) & modeMask
    assert(io.in.bits.sampleMode<=log2Ceil(maxSampleCount).U,"quad sample mode exceeds storage capacity")
    for(p <- 0 until 4)when((io.in.bits.pixels(p).coverageMask & modeMask).orR) {
      assert(io.in.bits.pixels(p).x>=io.in.bits.originX && io.in.bits.pixels(p).x-io.in.bits.originX<16.U &&
        io.in.bits.pixels(p).y>=io.in.bits.originY && io.in.bits.pixels(p).y-io.in.bits.originY<16.U,
        "covered quad lane leaves its admitted tile")
    }
  }
}
