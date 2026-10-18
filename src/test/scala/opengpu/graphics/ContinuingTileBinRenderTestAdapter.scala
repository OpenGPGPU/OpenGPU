package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig

/** Test adapter: preserve the legacy memory/image driver while exposing batch
  * tags. Small totals must fit its legacy 32-bit fields. Production software
  * uses ContinuingTileBinRenderPipeline's explicitly scoped 64-bit summary.
  */
private[graphics] class ContinuingTileBinRenderTestAdapter(
  config: GraphicsConfig,gpu: GpuConfig,engines: Int=2,pageBytes: Int=128,
  writeCredits: Int=2,tileSlots: Int=4,cacheEntries: Int=8,storeCredits: Int=2,
  fragCore: Boolean=false,sharedShader: Option[SharedCuShaderConfig]=None,pagedCapacity: Int=0,
  useTileSramBlackBoxes: Boolean=false,coalesceAttachments: Boolean=true
) extends Module {
  private val tx=writeCredits+2*engines+(if (pagedCapacity>0) 1 else 0)+engines*(2*(storeCredits+1)+1)
  val io=IO(new TileBinRenderPipelineIO(gpu,engines,tx,SharedCuShaderConfig.kernelWordTransactions(sharedShader),sharedShader))
  val restartLimit=IO(Input(UInt(16.W)))
  val batches=IO(Output(UInt(17.W))); val restarts=IO(Output(UInt(16.W)))
  val tileBatches=IO(Output(Vec(engines,UInt(16.W))))
  val appendedBatch=IO(Output(UInt(16.W)))
  private val child=Module(new ContinuingTileBinRenderPipeline(config,gpu,engines,pageBytes,
    writeCredits,tileSlots,cacheEntries,storeCredits,fragCore,sharedShader,
    useTileSramBlackBoxes=useTileSramBlackBoxes,pagedCapacity=pagedCapacity,coalesceAttachments=coalesceAttachments))
  private val controls=Set("begin","append","appended","seal","end","cancel","tileDone","completion")
  for ((name,data) <- io.elements if !controls(name)) { data <> child.io.elements(name) }
  child.io.begin.valid:=io.begin.valid; child.io.begin.bits.version:=1.U; child.io.begin.bits.render:=io.begin.bits
  child.io.begin.bits.restartLimit:=restartLimit; io.begin.ready:=child.io.begin.ready
  child.io.append <> io.append; io.appended.valid:=child.io.appended.valid
  io.appended.bits:=child.io.appended.bits.result; child.io.appended.ready:=io.appended.ready
  io.seal.ready:=false.B; chisel3.assert(!io.seal.valid)
  child.io.end <> io.end; child.io.cancel:=io.cancel
  for (e <- 0 until engines) {
    io.tileDone(e).valid:=child.io.tileDone(e).valid
    io.tileDone(e).bits:=child.io.tileDone(e).bits.result
    child.io.tileDone(e).ready:=io.tileDone(e).ready
    tileBatches(e):=child.io.tileDone(e).bits.batch
  }
  appendedBatch:=child.io.appended.bits.batch
  io.completion.valid:=child.io.completion.valid; child.io.completion.ready:=io.completion.ready
  val b=child.io.completion.bits; val t=b.totals
  io.completion.bits:=b.last
  io.completion.bits.identity:=b.identity; io.completion.bits.status:=b.status
  io.completion.bits.tiles:=t.tileVisits; io.completion.bits.draws:=t.draws
  io.completion.bits.loadBytes:=t.loadBytes; io.completion.bits.storeBytes:=t.storeBytes
  io.completion.bits.resolveBytes:=t.resolveBytes
  io.completion.bits.replay.replay.committed:=t.committed
  io.completion.bits.replay.replay.references:=t.references
  io.completion.bits.replay.resolved:=t.resolved
  io.completion.bits.replay.replay.spillTransitions:=t.spillTransitions
  batches:=b.batches; restarts:=b.restarts
  when(io.completion.valid) {
    for (value <- Seq(t.tileVisits,t.draws,t.committed,t.references,t.resolved)) {
    chisel3.assert(value<(BigInt(1)<<32).U,"test adapter totals must fit the legacy driver")
    }
    chisel3.assert(t.spillTransitions<16.U)
  }
}
