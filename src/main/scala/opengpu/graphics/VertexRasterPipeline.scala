package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexRasterDraw(p: VertexShaderStageConfig) extends Bundle {
  val vertex=new VertexViewportDraw(p);val raster=new Fp32RasterState
}
class VertexRasterCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val source=new VertexViewportCompletion
  val inputParts=UInt(67.W);val rasterParts=UInt(67.W);val culledParts=UInt(67.W)
  val outputQuads=UInt(99.W);val coveredLanes=UInt(101.W);val helperLanes=UInt(101.W);val coveredSamples=UInt(103.W)
  val rasterQuads=UInt(99.W);val discardedQuads=UInt(99.W);val interpolationOperations=UInt(115.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
  val rasterFaultValid=Bool();val interpolationFault=Bool();val faultSequence=UInt(64.W);val faultPrimitiveId=UInt(32.W);val faultInstanceId=UInt(32.W)
  val faultPartIndex=UInt(3.W);val faultX=SInt(32.W);val faultY=SInt(32.W);val faultLane=UInt(2.W)
}

/** Shared-CU GPU vertex execution through clipping, viewport, primitive raster
  * and general FP32 quad interpolation. This owner retains VM context through
  * final fragment input and held completion, after upstream payload release.
  * Native bin allocation and fragment-shader launch are separate consumers.
  */
class VertexRasterPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,centroidInput: Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits,"viewport and raster subpixel scales must match")
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexRasterDraw(p)));val out=Decoupled(new Fp32RasterizedQuad(p,g))
    val centroidMask=if(centroidInput)Some(Input(UInt((p.varyings*4).W)))else None
    val completion=Decoupled(new VertexRasterCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexViewportPipeline(config,p,shared,viewport,useSramBlackBoxes,payload,payloadOwner))
  private val raster=Module(new Fp32PrimitiveRasterStage(p,g))
  private val descriptor=Reg(new VertexRasterDraw(p));private val summary=Reg(new VertexViewportCompletion)
  private val centroidMask=Reg(UInt((p.varyings*4).W))
  private val result=Reg(new VertexRasterCompletion)
  private val active=RegInit(false.B);private val offered=RegInit(false.B);private val started=RegInit(false.B)
  private val sourceDone=RegInit(false.B);private val done=RegInit(false.B);private val status=RegInit(0.U(3.W))
  private val received=RegInit(0.U(67.W));private val rasterParts=RegInit(0.U(67.W));private val culled=RegInit(0.U(67.W))
  private val quads=RegInit(0.U(99.W));private val covered=RegInit(0.U(101.W));private val helpers=RegInit(0.U(101.W))
  private val samples=RegInit(0.U(103.W));private val raw=RegInit(0.U(99.W));private val discarded=RegInit(0.U(99.W))
  private val operations=RegInit(0.U(115.W));private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val fault=RegInit(false.B);private val interpolationFault=RegInit(false.B)
  private val faultSequence=RegInit(0.U(64.W));private val faultPrimitive=RegInit(0.U(32.W));private val faultInstance=RegInit(0.U(32.W));private val faultPart=RegInit(0.U(3.W))
  private val faultX=RegInit(0.S(32.W));private val faultY=RegInit(0.S(32.W));private val faultLane=RegInit(0.U(2.W))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val rasterFault=raster.io.completion.valid && raster.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || rasterFault || sourceFault
  io.draw.ready:= !active && !externalCancel;io.busy:=active
  when(io.draw.fire){
    centroidMask:=io.centroidMask.getOrElse(0.U)
    descriptor:=io.draw.bits;active:=true.B;offered:=true.B;started:=false.B;sourceDone:=false.B;done:=false.B
    val topology=io.draw.bits.vertex.geometry.topology
    val qualifiers= !(io.centroidMask.getOrElse(0.U)>>(io.draw.bits.vertex.geometry.vertex.varyingSlots<<2)).orR
    status:=Mux(Fp32RasterState.legal(io.draw.bits.raster,g) && topology<=6.U && qualifiers,0.U,1.U)
    received:=0.U;rasterParts:=0.U;culled:=0.U;quads:=0.U;covered:=0.U;helpers:=0.U;samples:=0.U;raw:=0.U;discarded:=0.U;operations:=0.U
    arithmeticFlags:=0.U;packingFlags:=0.U;fault:=false.B;interpolationFault:=false.B
    faultSequence:=0.U;faultPrimitive:=0.U;faultInstance:=0.U;faultPart:=0.U;faultX:=0.S;faultY:=0.S;faultLane:=0.U
  }
  source.io.draw.valid:=offered;source.io.draw.bits:=descriptor.vertex
  when(source.io.draw.fire){offered:=false.B;started:=true.B}
  source.io.cancel:=active && started && stop;raster.io.cancel:=active && stop
  raster.io.in.valid:=active && !stop && source.io.out.valid && raster.io.in.ready
  raster.io.in.bits.primitive:=source.io.out.bits;raster.io.in.bits.state:=descriptor.raster
  raster.io.in.bits.centroidMask:=centroidMask
  source.io.out.ready:=active && (stop || raster.io.in.ready)
  when(source.io.out.fire){received:=received+1.U}
  io.out<>raster.io.out
  when(io.out.fire){quads:=quads+1.U}
  raster.io.completion.ready:=active
  when(raster.io.completion.fire){
    val r=raster.io.completion.bits
    rasterParts:=rasterParts+1.U;when(r.culled){culled:=culled+1.U}
    covered:=covered+r.coveredLanes;helpers:=helpers+r.helperLanes;samples:=samples+r.coveredSamples
    raw:=raw+r.rasterQuads;discarded:=discarded+r.discardedQuads;operations:=operations+r.arithmeticOperations
    arithmeticFlags:=arithmeticFlags|r.arithmeticStatus;packingFlags:=packingFlags|r.packingStatus
    when(r.status=/=0.U && status===0.U && !externalCancel){
      status:=r.status;fault:=true.B;interpolationFault:=r.interpolationFault;faultSequence:=r.sequence
      faultPrimitive:=r.primitiveId;faultInstance:=r.instanceId;faultPart:=r.partIndex
      faultX:=r.faultX;faultY:=r.faultY;faultLane:=r.faultLane
    }
  }
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){
    summary:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.preparedParts===received,"raster source lost a window primitive")
    when(source.io.completion.bits.status=/=0.U && status===0.U && !externalCancel && !rasterFault){status:=source.io.completion.bits.status}
  }
  when(active && externalCancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !offered && !source.io.busy && !raster.io.busy && !done){
    result.identity:=descriptor.vertex.geometry.vertex.input.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status);result.source:=summary
    result.inputParts:=received;result.rasterParts:=rasterParts;result.culledParts:=culled
    result.outputQuads:=quads;result.coveredLanes:=covered;result.helperLanes:=helpers;result.coveredSamples:=samples
    result.rasterQuads:=raw;result.discardedQuads:=discarded;result.interpolationOperations:=operations
    result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags
    result.rasterFaultValid:=fault;result.interpolationFault:=interpolationFault;result.faultSequence:=faultSequence
    result.faultPrimitiveId:=faultPrimitive;result.faultInstanceId:=faultInstance;result.faultPartIndex:=faultPart
    result.faultX:=faultX;result.faultY:=faultY;result.faultLane:=faultLane;done:=true.B
  }
  io.completion.valid:=done;io.completion.bits:=result
  when(done){assert(sourceDone && !source.io.busy && !raster.io.busy,"draw completion escaped geometry/raster drain")}
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>source.io.shared;io.memoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.memoryResponse
  payload.foreach{_=>io.payload.get<>source.io.payload.get;source.io.payload.get.cancel:=started && io.payload.get.cancel}
}
