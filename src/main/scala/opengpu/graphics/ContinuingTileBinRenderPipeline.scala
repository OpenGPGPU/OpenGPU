package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig

class ContinuingTileBinRenderPipelineIO(gpuConfig: GpuConfig,engines: Int,transactions: Int,
  kernelWords: Int,sharedShader: Option[SharedCuShaderConfig])
  extends TileBinRenderDatapathIO(gpuConfig,engines,transactions,kernelWords,sharedShader) {
  val begin=Flipped(Decoupled(new TileBinContinuationDescriptor))
  val append=Flipped(Decoupled(new TileBinAppend))
  val appended=Decoupled(new TileBinContinuationAppendResult)
  val end=Flipped(Decoupled(Bool()))
  val cancel=Input(Bool())
  val tileDone=Vec(engines,Decoupled(new TileBinContinuationTileResult))
  val completion=Decoupled(new TileBinContinuationCompletion)
}

/** Stream-owned sealing and bounded automatic continuation of bin exhaustion.
  * This submission interface has no external seal: only input end or arena
  * exhaustion establishes a batch boundary. Per-batch tile events are tagged;
  * the final stream summary alone validates the image. A positive restart
  * limit needs both attachments stored. Intermediate resolves are suppressed.
  * VM, immutable records and all backing remain owned through final completion,
  * including the gaps between drained/rebound child batches.
  */
class ContinuingTileBinRenderPipeline(
  config: GraphicsConfig,
  gpuConfig: GpuConfig = GpuConfig(l2StoreEntries=4),
  engines: Int = 2,pageBytes: Int = 4096,writeCredits: Int = 8,
  tileSlots: Int = 8,cacheEntries: Int = 8,storeCredits: Int = 8,
  fragCore: Boolean = false,sharedShader: Option[SharedCuShaderConfig] = None,
  useTileSramBlackBoxes: Boolean = false,pagedCapacity: Int = 0,
  coalesceAttachments: Boolean = true
) extends Module {
  private val transactions=writeCredits+2*engines+(if (pagedCapacity>0) 1 else 0)+engines*(2*(storeCredits+1)+1)
  private val words=SharedCuShaderConfig.kernelWordTransactions(sharedShader)
  val io=IO(new ContinuingTileBinRenderPipelineIO(gpuConfig,engines,transactions,words,sharedShader))
  private val controller=Module(new TileBinContinuationController(engines,pagedCapacity>0))
  private val child=Module(new TileBinRenderPipeline(config,gpuConfig,engines,pageBytes,
    writeCredits,tileSlots,cacheEntries,storeCredits,fragCore,sharedShader,useTileSramBlackBoxes,
    pagedCapacity,deferResolveUntilEnd=true,coalesceAttachments=coalesceAttachments))
  private val controls=Set("begin","append","appended","end","cancel","tileDone","completion","busy")
  for ((name,data) <- io.elements if !controls(name)) { data <> child.io.elements(name) }
  controller.io.begin <> io.begin; controller.io.append <> io.append
  io.appended <> controller.io.appended; controller.io.end <> io.end
  controller.io.cancel:=io.cancel; io.tileDone <> controller.io.tileDone
  io.completion <> controller.io.completion; io.busy:=controller.io.busy
  child.io.begin <> controller.io.childBegin; child.io.append <> controller.io.childAppend
  controller.io.childAppended <> child.io.appended
  child.io.seal.valid:=false.B; child.io.seal.bits:=0.U
  child.io.end <> controller.io.childEnd; child.io.cancel:=controller.io.childCancel
  controller.io.childTileDone <> child.io.tileDone
  controller.io.childCompletion <> child.io.completion
}
