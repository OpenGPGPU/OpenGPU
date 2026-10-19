package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.ComputeMemoryRequest
import opengpu.system.SharedCuShaderConfig

object TileBinCapabilityFlags {val BankedSamples=20;val LocalPayloads=21}

class MemoryTileBinRenderPipelineIO(config: GpuConfig,engines: Int,transactions: Int,
  kernelWords: Int,sharedShader: Option[SharedCuShaderConfig],commandIdWidth: Int)
  extends TileBinRenderDatapathIO(config,engines,transactions,kernelWords,sharedShader) {
  val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
  val cancel=Input(Bool())
  val completion=Decoupled(new TileBinJobCompletion(commandIdWidth))
  /** Versioned, constant resource record for an enclosing host register map.
    * This does not alter the legacy 32-bit RenderHost capability word. */
  val capabilityWords=Output(Vec(8,UInt(32.W)))
  /** Diagnostic pulses; host completion never waits for a tile-event reader. */
  val tileEvents=Output(Vec(engines,Valid(new TileBinContinuationTileResult)))
}

/** One bounded memory submission, descriptor/append fetch and complete bin
  * rendering lifecycle. Both request classes use the enclosing context VM.
  * Memory and staging must remain translated/pinned through busy and final
  * completion; publication/coherence is the submitting owner's responsibility.
  * This engine is separate from the existing opcode-7 single-tile host ABI.
  */
class MemoryTileBinRenderPipeline(config: GraphicsConfig,
  gpuConfig: GpuConfig=GpuConfig(l2StoreEntries=4),commandIdWidth: Int=8,
  engines: Int=2,pageBytes: Int=4096,writeCredits: Int=8,
  tileSlots: Int=8,cacheEntries: Int=8,storeCredits: Int=8,
  fragCore: Boolean=false,sharedShader: Option[SharedCuShaderConfig]=None,
  useTileSramBlackBoxes: Boolean=false,pagedCapacity: Int=16384,
  coalesceAttachments: Boolean=true,bankedAttachments: Boolean=false) extends Module {
  private val readerIds=TileBinJobMemoryLayout.transactions
  private val renderIds=writeCredits+2*engines+(if (pagedCapacity>0) 1 else 0)+engines*(2*(storeCredits+1)+1)
  private val total=readerIds+renderIds
  private val idBits=log2Ceil(total).max(1)
  val io=IO(new MemoryTileBinRenderPipelineIO(gpuConfig,engines,total,
    SharedCuShaderConfig.kernelWordTransactions(sharedShader),sharedShader,commandIdWidth))
  private val reader=Module(new TileBinJobReader(gpuConfig,commandIdWidth,pagedCapacity>0,fragCore))
  private val renderer=Module(new ContinuingTileBinRenderPipeline(config,gpuConfig,engines,pageBytes,
    writeCredits,tileSlots,cacheEntries,storeCredits,fragCore,sharedShader,useTileSramBlackBoxes,
    pagedCapacity,coalesceAttachments,bankedAttachments))
  private val excluded=Set("memoryRequest","memoryResponse","busy","submit","cancel","completion","tileEvents","capabilityWords")
  for ((name,data) <- io.elements if !excluded(name)) { data <> renderer.io.elements(name) }
  reader.io.submit <> io.submit; reader.io.cancel:=io.cancel
  io.completion <> reader.io.completion
  renderer.io.begin <> reader.io.begin; renderer.io.append <> reader.io.append
  reader.io.appended <> renderer.io.appended; renderer.io.end <> reader.io.end
  renderer.io.cancel:=reader.io.cancelCore; reader.io.renderCompletion <> renderer.io.completion
  for (e <- 0 until engines) {
    renderer.io.tileDone(e).ready:=true.B
    io.tileEvents(e).valid:=renderer.io.tileDone(e).fire
    io.tileEvents(e).bits:=renderer.io.tileDone(e).bits
  }
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(gpuConfig,64,total),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(gpuConfig,64,total),2,pipe=true))
  private val sources=Seq(reader.io.memoryRequest,renderer.io.memoryRequest)
  private val destinations=Seq(reader.io.memoryResponse,renderer.io.memoryResponse)
  for (i <- 0 until 2) {
    val base=if (i==0) 0 else readerIds
    val count=if (i==0) readerIds else renderIds
    arbiter.io.in(i).valid:=sources(i).valid
    arbiter.io.in(i).bits:=sources(i).bits
    arbiter.io.in(i).bits.transactionId:=sources(i).bits.transactionId.pad(idBits)+base.U(idBits.W)
    sources(i).ready:=arbiter.io.in(i).ready
    val selected=io.memoryResponse.bits.transactionId>=base.U && io.memoryResponse.bits.transactionId<(base+count).U
    destinations(i).valid:=io.memoryResponse.valid && selected
    destinations(i).bits:=io.memoryResponse.bits
    destinations(i).bits.transactionId:=io.memoryResponse.bits.transactionId-base.U
  }
  queue.io.enq <> arbiter.io.out; io.memoryRequest <> queue.io.deq
  io.memoryResponse.ready:=Mux(io.memoryResponse.bits.transactionId<readerIds.U,reader.io.memoryResponse.ready,
    Mux(io.memoryResponse.bits.transactionId<total.U,renderer.io.memoryResponse.ready,false.B))
  io.busy:=reader.io.busy || renderer.io.busy || queue.io.deq.valid
  // GBC1: size, job magic, descriptor/append sizes, execution flags,
  // tile capacity/resident entries, page/credit sizes, restart/ID limits.
  private val flags=engines | (log2Ceil(config.maxSampleCount)<<8) |
    (if (pagedCapacity>0) 1<<16 else 0) | (if (fragCore) 1<<17 else 0) |
    (if (sharedShader.nonEmpty) 1<<18 else 0) | (if (coalesceAttachments) 1<<19 else 0) |
    (if (bankedAttachments) 1<<TileBinCapabilityFlags.BankedSamples else 0) |
    (if (sharedShader.exists(_.shaderPayload.nonEmpty)) 1<<TileBinCapabilityFlags.LocalPayloads else 0)
  io.capabilityWords:=VecInit(Seq(BigInt("47424301",16),BigInt(8),TileBinJobMemoryLayout.magic,
    BigInt(TileBinJobMemoryLayout.bytes)|(BigInt(TileBinJobMemoryLayout.appendBytes)<<16),
    BigInt(flags),BigInt(if (pagedCapacity>0) pagedCapacity else tileSlots)|(BigInt(tileSlots)<<16),
    BigInt(log2Ceil(pageBytes))|(BigInt(writeCredits)<<8)|(BigInt(storeCredits)<<16)|(BigInt(readerIds)<<24),
    BigInt(65535)|(BigInt(total)<<16)).map(_.U(32.W)))
}
