package opengpu.graphics

import chisel3._
import chisel3.util._

/** Resident H3 backend with explicit quiescent attachment maintenance access.
  * The attachment owner initializes every addressed sample before submitting
  * quads, and owns valid/dirty/refill/export policy. Maintenance is exclusive;
  * callers drain both interfaces before changing maintenance or beginning a
  * tile. Sample mode is bound on begin in both the merge and physical storage.
  */
class ResidentTileSampleBackend(useBlackBoxes: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val begin=Input(Bool());val sampleMode=Input(UInt(2.W));val cancel=Input(Bool())
    val in=Flipped(Decoupled(new TileSampleQuad))
    val out=Decoupled(new TileSampleQuadCompletion)
    val maintenance=Input(Bool())
    val read=Vec(4,Flipped(Decoupled(new BankedTileSampleRead)))
    val response=Vec(4,Decoupled(new BankedTileSampleReply))
    val write=Vec(4,Flipped(Decoupled(new BankedTileSampleWrite)))
    val drained=Output(Bool())
    val acceptedSamples=Output(UInt(3.W));val committedSamples=Output(UInt(3.W))
  })
  private val samples=Module(new BankedTileSampleSram(useBlackBoxes))
  private val merge=Module(new TileSampleQuadMerge)
  merge.io.begin:=io.begin;merge.io.sampleMode:=io.sampleMode;merge.io.cancel:=io.cancel
  samples.io.begin:=io.begin;samples.io.sampleMode:=io.sampleMode
  merge.io.in.valid:=io.in.valid && !io.maintenance
  merge.io.in.bits:=io.in.bits;io.in.ready:=merge.io.in.ready && !io.maintenance
  io.out <> merge.io.out
  for(p <- 0 until 4) {
    samples.io.read(p).valid:=Mux(io.maintenance,io.read(p).valid,merge.io.read(p).valid)
    samples.io.read(p).bits:=Mux(io.maintenance,io.read(p).bits,merge.io.read(p).bits)
    merge.io.read(p).ready:=samples.io.read(p).ready && !io.maintenance
    io.read(p).ready:=samples.io.read(p).ready && io.maintenance
    samples.io.write(p).valid:=Mux(io.maintenance,io.write(p).valid,merge.io.write(p).valid)
    samples.io.write(p).bits:=Mux(io.maintenance,io.write(p).bits,merge.io.write(p).bits)
    merge.io.write(p).ready:=samples.io.write(p).ready && !io.maintenance
    io.write(p).ready:=samples.io.write(p).ready && io.maintenance
    merge.io.response(p).valid:=samples.io.response(p).valid && !io.maintenance
    merge.io.response(p).bits:=samples.io.response(p).bits
    io.response(p).valid:=samples.io.response(p).valid && io.maintenance
    io.response(p).bits:=samples.io.response(p).bits
    samples.io.response(p).ready:=Mux(io.maintenance,io.response(p).ready,merge.io.response(p).ready)
  }
  private val previousMaintenance=RegNext(io.maintenance,false.B)
  when(io.maintenance=/=previousMaintenance) {
    assert(merge.io.drained && samples.io.drained,"maintenance ownership requires complete backend drain")
  }
  when(io.maintenance){assert(merge.io.drained,"maintenance cannot interrupt a quad")}
  io.drained:=merge.io.drained && samples.io.drained
  io.acceptedSamples:=merge.io.acceptedSamples;io.committedSamples:=merge.io.committedSamples
}
