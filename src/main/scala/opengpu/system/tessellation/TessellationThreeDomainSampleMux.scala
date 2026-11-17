package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.TypedTilePixelQuadCompletion

/** Forward sample completions from the active tessellation domain. The caller
  * retains its selection until the active owner's completions have drained. */
class TessellationThreeDomainSampleMux extends Module {
  val io=IO(new Bundle {
    val domains=Flipped(Vec(3,Decoupled(new TypedTilePixelQuadCompletion)))
    val selected=Input(UInt(2.W));val out=Decoupled(new TypedTilePixelQuadCompletion)
  })
  assert(io.selected<3.U,"invalid tessellation sample owner")
  io.out.valid:=io.domains(io.selected).valid;io.out.bits:=io.domains(io.selected).bits
  for(i<-0 until 3){
    io.domains(i).ready:=io.out.ready && io.selected===i.U
    when(io.selected=/=i.U){assert(!io.domains(i).valid,"inactive tessellation domain offered a sample")}
  }
  private val held=RegInit(false.B);private val heldSelection=Reg(UInt(2.W))
  when(held){assert(io.out.valid && io.selected===heldSelection,"held tessellation sample changed owner")}
  when(io.out.valid && !io.out.ready){held:=true.B;heldSelection:=io.selected}.elsewhen(io.out.fire){held:=false.B}
}
