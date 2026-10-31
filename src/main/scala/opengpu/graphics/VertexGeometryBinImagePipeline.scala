package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system._

/** Internal vertex-to-geometry/bin/image owner. The caller validates resource
  * provenance and aliasing and pins allocations until completion. Native host
  * submission, grouped textures and payload leases are separate interfaces.
  */
class VertexGeometryBinImagePipeline(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    engines:Int=2,pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false) extends Module {
  private val sharedConfig=shared
  private val passTransactions=1<<log2Ceil(VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)+32*engines)
  val memoryTransactions=1<<log2Ceil(passTransactions+4)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredGeometryBinImageDescriptor))
    val draw=Flipped(Decoupled(new VertexGeometryBinDraw(config,p,sharedConfig.taskIdWidth)))
    val drawCompletion=Decoupled(new GeometryWindowBinDrawResult);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion));val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(engines,Decoupled(new LayeredBinTileCompletion));val completion=Decoupled(new LayeredGeometryBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true))
    val geometryTexture=Vec(sharedConfig.numComputeUnits,new SharedCuTexturePort(config))
    val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val pass=Module(new LayeredGeometryBinDrawImagePipeline(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes))
  private val vertex=Module(new VertexGeometryStage(config,p,shared,useSramBlackBoxes))
  private val ingress=Module(new VertexGeometryBinDrawIngress(config,p,shared.taskIdWidth))
  private val terminal=Module(new VertexGeometryBinTerminalBridge(config,p,shared.taskIdWidth))
  private val router=Module(new GeometryVertexShaderRouter(config,shared.numComputeUnits,shared.taskIdWidth,engines+1))
  router.io.geometry<>pass.io.shared;router.io.vertex<>vertex.io.shared;io.shared<>router.io.pool
  private val active=RegInit(false.B);private val vertexStarted=RegInit(false.B);private val downstreamAbort=RegInit(false.B)
  private val drawStatus=RegInit(0.U(3.W))
  private val drawFailure=ingress.io.completion.valid && ingress.io.completion.bits.status=/=0.U
  pass.io.begin<>io.begin
  when(io.begin.fire){active:=true.B;vertexStarted:=false.B;downstreamAbort:=false.B;drawStatus:=0.U}
  when(active && drawFailure && drawStatus===0.U){drawStatus:=ingress.io.completion.bits.status}
  when(active && (pass.io.sourceCancel || pass.io.completion.valid)){downstreamAbort:=true.B}
  ingress.io.begin.valid:=io.draw.valid && active && !io.cancel && drawStatus===0.U && !drawFailure && !pass.io.completion.valid
  ingress.io.begin.bits:=io.draw.bits;io.draw.ready:=active && !io.cancel && drawStatus===0.U && !drawFailure && !pass.io.completion.valid && ingress.io.begin.ready
  vertex.io.draw<>ingress.io.vertexDraw
  when(vertex.io.draw.fire){vertexStarted:=true.B}
  when(vertex.io.completion.fire){vertexStarted:=false.B}
  ingress.io.vertexOut<>vertex.io.out;ingress.io.vertexCompletion<>vertex.io.completion
  terminal.io.draw<>ingress.io.geometryDraw;pass.io.draw<>terminal.io.targetDraw
  terminal.io.primitive<>ingress.io.primitive;pass.io.primitive<>terminal.io.targetPrimitive
  terminal.io.inputEnd<>ingress.io.inputEnd;pass.io.inputEnd<>terminal.io.targetEnd
  terminal.io.targetCompletion<>pass.io.drawCompletion;ingress.io.geometryCompletion<>terminal.io.completion
  terminal.io.terminal:=pass.io.completion.valid
  ingress.io.cancel:=io.cancel || downstreamAbort
  vertex.io.cancel:=vertexStarted && (ingress.io.vertexCancel || pass.io.sourceCancel || io.cancel || pass.io.completion.valid)
  pass.io.cancel:=io.cancel || ingress.io.geometryCancel || drawFailure || drawStatus=/=0.U
  io.drawCompletion<>ingress.io.completion
  pass.io.end.valid:=io.end.valid && active && !io.draw.valid && !ingress.io.busy && !terminal.io.busy && !io.cancel && drawStatus===0.U && !drawFailure
  pass.io.end.bits:=io.end.bits
  io.end.ready:=pass.io.end.ready && active && !io.draw.valid && !ingress.io.busy && !terminal.io.busy && !io.cancel && drawStatus===0.U && !drawFailure
  io.primitiveDone<>pass.io.primitiveDone;io.out<>pass.io.out;io.tileDone<>pass.io.tileDone
  io.geometryTexture<>pass.io.geometryTexture;io.geometryTrap<>pass.io.geometryTrap
  pass.io.geometryInvalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>pass.io.geometryInvalidateDone
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  arbiter.io.in(0).valid:=pass.io.memoryRequest.valid;arbiter.io.in(0).bits:=pass.io.memoryRequest.bits
  pass.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=vertex.io.memoryRequest.valid;arbiter.io.in(1).bits:=vertex.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=vertex.io.memoryRequest.bits.transactionId+&passTransactions.U
  vertex.io.memoryRequest.ready:=arbiter.io.in(1).ready
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  val passResponse=io.memoryResponse.bits.transactionId<passTransactions.U
  val vertexResponse=io.memoryResponse.bits.transactionId>=passTransactions.U && io.memoryResponse.bits.transactionId<(passTransactions+4).U
  pass.io.memoryResponse.valid:=io.memoryResponse.valid && passResponse;pass.io.memoryResponse.bits:=io.memoryResponse.bits
  vertex.io.memoryResponse.valid:=io.memoryResponse.valid && vertexResponse;vertex.io.memoryResponse.bits:=io.memoryResponse.bits
  vertex.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-passTransactions.U
  io.memoryResponse.ready:=Mux(passResponse,pass.io.memoryResponse.ready,vertexResponse && vertex.io.memoryResponse.ready)
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned vertex geometry image response")}
  private val drained= !ingress.io.busy && !terminal.io.busy && !vertex.io.busy && router.io.drained && inFlight===0.U && queue.io.count===0.U
  io.completion.valid:=pass.io.completion.valid && drained;io.completion.bits:=pass.io.completion.bits
  when(drawStatus=/=0.U){io.completion.bits.status:=drawStatus}
    .elsewhen(drawFailure){io.completion.bits.status:=ingress.io.completion.bits.status}
  pass.io.completion.ready:=io.completion.ready && drained
  when(io.completion.fire){active:=false.B}
  io.busy:=active || ingress.io.busy || terminal.io.busy || vertex.io.busy
}
