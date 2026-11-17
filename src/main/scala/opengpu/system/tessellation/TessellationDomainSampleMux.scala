package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.TypedTilePixelQuadCompletion

/** Forward sample completions from the active tessellation domain. The caller
  * retains its selection until the active owner's completions have drained. */
class TessellationDomainSampleMux extends Module {
  val io=IO(new Bundle {
    val isoline=Flipped(Decoupled(new TypedTilePixelQuadCompletion))
    val triangle=Flipped(Decoupled(new TypedTilePixelQuadCompletion))
    val selected=Input(Bool());val out=Decoupled(new TypedTilePixelQuadCompletion)
  })
  io.out.valid:=Mux(io.selected,io.triangle.valid,io.isoline.valid)
  io.out.bits:=Mux(io.selected,io.triangle.bits,io.isoline.bits)
  io.isoline.ready:=io.out.ready && !io.selected
  io.triangle.ready:=io.out.ready && io.selected
  when(io.selected){assert(!io.isoline.valid,"inactive isoline owner offered a sample")}
    .otherwise{assert(!io.triangle.valid,"inactive triangle owner offered a sample")}
  private val held=RegInit(false.B);private val heldSelection=Reg(Bool())
  when(held){assert(io.out.valid && io.selected===heldSelection,"held tessellation sample changed owner")}
  when(io.out.valid && !io.out.ready){held:=true.B;heldSelection:=io.selected}
    .elsewhen(io.out.fire){held:=false.B}
}
