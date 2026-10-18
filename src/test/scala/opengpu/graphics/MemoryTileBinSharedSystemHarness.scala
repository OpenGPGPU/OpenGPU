package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}

/** Validation composition for memory submission through actual shared CUs,
  * coherent L2 and Sv32. It does not advertise a production MMIO opcode. */
private[graphics] class MemoryTileBinSharedSystemHarness extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2,l2StoreEntries=4)
  private val count=2
  private val pool=SharedCuShaderConfig(count,parallelFragments=true)
  private val stagingSlots=SharedCuShaderConfig.kernelWordTransactions(Some(pool))
  private val renderSlots=30
  private val walkBase=renderSlots+2*stagingSlots
  private val hostSlots=1<<log2Ceil(walkBase+3)
  private val systemSlots=GpuSystem.totalMemoryTransactions(count,4,hostSlots)
  val io=IO(new Bundle {
    val render=new MemoryTileBinRenderPipelineIO(cfg,2,renderSlots,stagingSlots,Some(pool),8)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,systemSlots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,systemSlots)))
    val owned=Output(UInt(count.W))
    val engineOwned=Output(Vec(2,UInt(count.W)))
    val invalidateInstructionCache=Input(Bool())
    val idle=Output(Bool())
  })
  private val graphics=GraphicsConfig(screenWidth=16,screenHeight=8,
    tileSize=16,tileAttachments=true,omInflight=2,fragmentBatchLimit=8)
  private val renderer=Module(new MemoryTileBinRenderPipeline(graphics,cfg,
    pageBytes=128,writeCredits=2,tileSlots=4,storeCredits=2,
    fragCore=true,sharedShader=Some(pool))).io
  private val system=Module(new GpuSystem(cfg,numComputeUnits=count,enableFpuBackend=true,
    graphicsHostTransactions=hostSlots,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,enableGraphicsKernels=true))
  private def tieInputs(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(tieInputs)
    case v: Vec[_]=>v.foreach(tieInputs)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tieInputs(renderer); tieInputs(system.io)
  for ((name,data) <- io.render.elements) data <> renderer.elements(name)
  renderer.shared.get <> system.io.graphicsKernels.get
  io.owned:=system.io.graphicsKernels.get.owned
  io.engineOwned:=renderer.sharedOwners.get
  system.io.graphicsDrained:= !renderer.busy
  system.io.instructionSatp:=io.render.instructionSatp
  system.io.vectorSatp:=io.render.vectorSatp
  system.io.instructionTlbFlush:=io.render.instructionTlbFlush
  system.io.vectorTlbFlush:=io.render.vectorTlbFlush
  system.io.invalidateInstructionCache:=io.invalidateInstructionCache
  // Match the enclosing host's registered memory transport: GpuSystem's
  // arbitration is Decoupled, while the external memory offer is irrevocable.
  private val memoryQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,systemSlots),2,pipe=true))
  memoryQueue.io.enq <> system.io.memoryRequest
  io.memoryRequest <> memoryQueue.io.deq
  system.io.memoryResponse <> io.memoryResponse
  io.idle:= !renderer.busy && !system.io.busyComputeUnits.orR &&
    !system.io.commandProcessorBusy && !memoryQueue.io.deq.valid
  // Shared mode has no private graphics CU/cache client. Real shader memory,
  // probes and atomics flow through the CUs inside GpuSystem.
  system.io.graphicsShaderRequest.valid:=false.B
  system.io.graphicsShaderRequest.bits:=0.U.asTypeOf(system.io.graphicsShaderRequest.bits)
  system.io.graphicsShaderResponse.ready:=true.B
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  system.io.graphicsShaderL1InvalidateDone.valid:=false.B
  system.io.graphicsShaderL1InvalidateDone.bits:=0.U.asTypeOf(system.io.graphicsShaderL1InvalidateDone.bits)
  for (e <- 0 until 2) {
    assert(!renderer.kernelMemReq(e).valid)
    assert(!renderer.kernelGlobalAtomicRequest(e).valid)
  }
  private val clients=Seq(
    Module(new TranslatedLineClient(cfg,renderSlots,preserveCachePolicy=true)),
    Module(new TranslatedLineClient(cfg,stagingSlots,preserveCachePolicy=true)),
    Module(new TranslatedLineClient(cfg,stagingSlots,preserveCachePolicy=true)))
  clients(0).io.in <> renderer.memoryRequest
  renderer.memoryResponse <> clients(0).io.out
  for (e <- 0 until 2) {
    clients(e+1).io.in <> renderer.kernelWordMemReq(e)
    renderer.kernelWordMemResp(e) <> clients(e+1).io.out
  }
  for (client <- clients) {
    client.io.satp:=io.render.vectorSatp
    client.io.flush:=io.render.vectorTlbFlush
  }
  private val requests=clients.map(_.io.memReq)++clients.map(_.io.pageWalk)
  private val responses=clients.map(_.io.memResp)++clients.map(_.io.pageWalkResp)
  private val bases=Seq(0,renderSlots,renderSlots+stagingSlots,walkBase,walkBase+1,walkBase+2)
  private val sizes=Seq(renderSlots,stagingSlots,stagingSlots,1,1,1)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(cfg,64,hostSlots),6))
  private val queue=Module(new Queue(new ComputeMemoryRequest(cfg,64,hostSlots),2,pipe=true))
  for (i <- 0 until 6) {
    arbiter.io.in(i).valid:=requests(i).valid
    arbiter.io.in(i).bits:=requests(i).bits
    arbiter.io.in(i).bits.transactionId:=requests(i).bits.transactionId.pad(log2Ceil(hostSlots))+bases(i).U
    requests(i).ready:=arbiter.io.in(i).ready
    val lower=system.io.graphicsHostResponse
    val selected=lower.bits.transactionId>=bases(i).U && lower.bits.transactionId<(bases(i)+sizes(i)).U
    responses(i).valid:=lower.valid && selected
    responses(i).bits:=lower.bits
    responses(i).bits.transactionId:=lower.bits.transactionId-bases(i).U
  }
  queue.io.enq <> arbiter.io.out
  system.io.graphicsHostRequest <> queue.io.deq
  system.io.graphicsHostResponse.ready:=TileBinSharedResponseRouting.ready(
    system.io.graphicsHostResponse.bits.transactionId,bases,sizes,responses.map(_.ready))
  when(system.io.graphicsHostResponse.fire) {
    assert(PopCount(VecInit(responses.map(_.fire)))===1.U,
      "shared response may retire only through its selected client")
  }
}
