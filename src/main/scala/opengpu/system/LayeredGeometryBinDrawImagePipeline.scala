package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

/** Connect assembled primitive draws to a layered geometry/bin/image pass.
  * The caller drains the primitive source after sourceCancel and retains all
  * allocations until pass completion; native submission is still external.
  */
class LayeredGeometryBinDrawImagePipeline(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    engines:Int=2,pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false) extends Module {
  private val sharedConfig=shared
  val memoryTransactions=1<<log2Ceil(VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)+32*engines)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredGeometryBinImageDescriptor))
    val draw=Flipped(Decoupled(new GeometryWindowBinTask(config,p,sharedConfig.taskIdWidth)))
    val primitive=Flipped(Decoupled(new AssembledPrimitive(p)));val inputEnd=Flipped(Decoupled(new PrimitiveAssemblyCompletion))
    val drawCompletion=Decoupled(new GeometryWindowBinDrawResult);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion));val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(engines,Decoupled(new LayeredBinTileCompletion));val completion=Decoupled(new LayeredGeometryBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true))
    val geometryTexture=Vec(sharedConfig.numComputeUnits,new SharedCuTexturePort(config))
    val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val sourceCancel=Output(Bool());val busy=Output(Bool())
  })
  private val pass=Module(new LayeredGeometryBinImagePipeline(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes))
  private val stream=Module(new GeometryWindowBinDrawStream(config,p,shared.taskIdWidth))
  private val bridge=Module(new GeometryBinTaskTerminalBridge(config,p,shared.taskIdWidth))
  private val active=RegInit(false.B);private val cancelPending=RegInit(false.B)
  pass.io.begin.valid:=io.begin.valid && !stream.io.busy && !cancelPending && !io.cancel;pass.io.begin.bits:=io.begin.bits
  io.begin.ready:=pass.io.begin.ready && !stream.io.busy && !cancelPending && !io.cancel
  when(io.begin.fire){active:=true.B;cancelPending:=false.B}
  val drawAllowed=active && !cancelPending && !io.cancel && !pass.io.completion.valid && pass.io.task.ready
  stream.io.begin.valid:=io.draw.valid && drawAllowed;stream.io.begin.bits:=io.draw.bits
  io.draw.ready:=drawAllowed && stream.io.begin.ready
  stream.io.primitive<>io.primitive;stream.io.inputEnd<>io.inputEnd
  bridge.io.task<>stream.io.childTask;pass.io.task<>bridge.io.targetTask
  bridge.io.targetCompletion<>pass.io.taskCompletion;stream.io.childCompletion<>bridge.io.completion
  bridge.io.terminal:=pass.io.completion.valid
  io.drawCompletion<>stream.io.completion
  stream.io.cancel:=io.cancel || cancelPending || pass.io.completion.valid
  io.sourceCancel:=stream.io.sourceCancel
  when(active && (io.cancel || stream.io.sourceCancel)){cancelPending:=true.B}
  pass.io.cancel:=active && cancelPending && (!stream.io.busy || stream.io.childCancel)
  pass.io.end.valid:=io.end.valid && active && !stream.io.busy && !cancelPending && !io.cancel;pass.io.end.bits:=io.end.bits
  io.end.ready:=pass.io.end.ready && active && !stream.io.busy && !cancelPending && !io.cancel
  io.completion.valid:=pass.io.completion.valid && !stream.io.busy;io.completion.bits:=pass.io.completion.bits
  pass.io.completion.ready:=io.completion.ready && !stream.io.busy
  when(io.completion.fire){active:=false.B;cancelPending:=false.B}
  io.busy:=active || stream.io.busy
  io.primitiveDone<>pass.io.primitiveDone;io.out<>pass.io.out;io.tileDone<>pass.io.tileDone
  io.shared<>pass.io.shared;pass.io.geometryTexture<>io.geometryTexture;io.geometryTrap<>pass.io.geometryTrap
  pass.io.geometryInvalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>pass.io.geometryInvalidateDone
  io.memoryRequest<>pass.io.memoryRequest;pass.io.memoryResponse<>io.memoryResponse
}
