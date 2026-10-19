package opengpu.graphics

import chisel3._
import chisel3.util._

class FragmentQuadPayload(config: GraphicsConfig) extends Bundle {
  val quad=new FragmentQuad(config)
  val depths=Vec(4,Vec(config.maxSampleCount,UInt(30.W)))
}

/** Compatibility service for scalar texture sampling after quad rasterization.
  * Helpers and zero-coverage lanes are consumed without texture side effects.
  */
class FragmentQuadSerializer(config: GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentQuadPayload(config)))
    val out=Decoupled(new RasterFragment(config))
    val depths=Output(Vec(config.maxSampleCount,UInt(30.W)))
    val drained=Output(Bool())
  })
  private val payload=Reg(new FragmentQuadPayload(config))
  private val remaining=RegInit(0.U(4.W))
  private val lane=PriorityEncoder(remaining)
  private val nextRemaining=remaining & (remaining-1.U)
  io.out.valid:=remaining.orR
  io.out.bits:=payload.quad.lanes(lane)
  io.depths:=payload.depths(lane)
  io.in.ready:= !remaining.orR || (io.out.ready && !nextRemaining.orR)
  io.drained:= !remaining.orR
  when(io.out.fire){remaining:=nextRemaining}
  when(io.in.fire) {
    payload:=io.in.bits
    remaining:=VecInit(io.in.bits.quad.lanes.map(p=>p.covered && p.coverageMask.orR)).asUInt
  }
}
