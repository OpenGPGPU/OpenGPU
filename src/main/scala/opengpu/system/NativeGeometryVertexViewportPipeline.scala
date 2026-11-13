package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

object NativeGeometryVertexViewportPipeline {
  // Up to 2^64 source primitives, 32 invocations and 256 emitted vertices,
  // followed by at most seven clipped triangle parts. Keep the inclusive
  // endpoint and the ordinary viewport's thirteen operation-count guard bits.
  val mappedCountWidth=78
  val partCountWidth=81
  val arithmeticCountWidth=partCountWidth+13
}
class NativeGeometryVertexViewportTask(p: VertexShaderStageConfig,idWidth: Int) extends Bundle {
  val source=new NativeGeometryVertexTask(p,idWidth)
  val viewport=new Fp32ViewportState
}
class NativeGeometryVertexViewportCompletion extends Bundle {
  import NativeGeometryVertexViewportPipeline._
  val identity=new GraphicsWorkIdentity
  val status=UInt(3.W)
  /** 1=viewport admission, 2=VS/GS source, 3=clip, 4=viewport execution. */
  val faultStage=UInt(3.W)
  val source=new NativeGeometryVertexCompletion
  val clipFaultValid=Bool();val clipFault=new PrimitiveClipCompletion
  val clipFaultGeometry=new WindowPrimitiveGeometryMetadata
  val viewportFaultValid=Bool();val viewportFault=new ViewportCompletion
  val viewportFaultGeometry=new WindowPrimitiveGeometryMetadata
  val mappedInputs=UInt(mappedCountWidth.W)
  val clipInputs=UInt(mappedCountWidth.W)
  val droppedMapped=UInt(mappedCountWidth.W)
  val clipOutputs=UInt(partCountWidth.W)
  val clipCulled=UInt(mappedCountWidth.W)
  val viewportInputs=UInt(partCountWidth.W)
  val droppedClipped=UInt(partCountWidth.W)
  val preparedParts=UInt(partCountWidth.W)
  val viewportCulled=UInt(partCountWidth.W)
  val guardBandParts=UInt(partCountWidth.W)
  val arithmeticOperations=UInt(arithmeticCountWidth.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
}

/** Explicit opt-in VS/GS source followed by the existing full clip/viewport
  * stages. Geometry identity travels with every clip fan part. This owns no
  * product routing or private graph publication; the enclosing owner retains
  * source metadata/resources and the viewport until the held result drains.
  */
class NativeGeometryVertexViewportPipeline(config: GpuConfig,p: VertexShaderStageConfig,
    shared: SharedCuShaderConfig,viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,
    payloadOwner: Int=0,groupedTexture: Boolean=false) extends Module {
  import NativeGeometryVertexViewportPipeline._
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new NativeGeometryVertexViewportTask(p,sharedConfig.taskIdWidth)))
    val out=Decoupled(new GeometryWindowPrimitive(p))
    val completion=Decoupled(new NativeGeometryVertexViewportCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,
      sharedConfig.numComputeUnits,geometryAvailable=true))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new NativeGeometryVertexPipeline(config,p,shared,useSramBlackBoxes,
    payload,payloadOwner,groupedTexture))
  private val clip=Module(new PrimitiveClipStage(p,useSramBlackBoxes))
  private val prep=Module(new Fp32ViewportStage(p,viewport))
  private val task=Reg(new NativeGeometryVertexViewportTask(p,shared.taskIdWidth))
  private val summary=Reg(new NativeGeometryVertexCompletion)
  private val result=Reg(new NativeGeometryVertexViewportCompletion)
  private val active=RegInit(false.B);private val offered=RegInit(false.B)
  private val started=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(3.W))
  private val counts=Reg(new NativeGeometryVertexViewportCompletion)
  private val clipInputId=Reg(UInt(32.W));private val clipInvocation=Reg(UInt(6.W));private val clipLayer=Reg(UInt(32.W))
  private val prepInputId=Reg(UInt(32.W));private val prepInvocation=Reg(UInt(6.W));private val prepLayer=Reg(UInt(32.W))
  private val payloadContext=payload.map(_=>Reg(UInt(32.W)))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val clipFault=clip.io.completion.valid && clip.io.completion.bits.status=/=0.U && clip.io.completion.bits.status=/=4.U
  private val prepFault=prep.io.completion.valid && prep.io.completion.bits.status=/=0.U && prep.io.completion.bits.status=/=4.U
  private val stop=status=/=0.U || externalCancel || sourceFault || clipFault || prepFault
  private def fail(code: UInt,stage: Int): Unit=
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage.U}

  io.task.ready:= !active && !externalCancel;io.busy:=active
  when(io.task.fire){
    task:=io.task.bits;active:=true.B;started:=false.B;done:=false.B
    counts:=0.U.asTypeOf(counts);summary:=0.U.asTypeOf(summary)
    val legal=Fp32ViewportState.legal(io.task.bits.viewport)
    offered:=legal;sourceDone:= !legal;status:=Mux(legal,0.U,1.U);faultStage:=Mux(legal,0.U,1.U)
    payload.foreach(_=>payloadContext.get:=io.payload.get.context)
  }
  source.io.task.valid:=offered;source.io.task.bits:=task.source
  when(source.io.task.fire){offered:=false.B;started:=true.B}
  source.io.cancel:=active && started && stop
  clip.io.cancel:=active && stop;prep.io.cancel:=active && stop
  io.shared<>source.io.shared;io.memoryRequest<>source.io.memoryRequest
  source.io.memoryResponse<>io.memoryResponse
  if(groupedTexture)io.grouped.get<>source.io.grouped.get
  payload.foreach { _=>
    io.payload.get<>source.io.payload.get;source.io.payload.get.context:=payloadContext.get
    source.io.payload.get.cancel:=active && started && stop;io.payloadPins.get:=source.io.payloadPins.get
  }

  clip.io.in.valid:=active && !stop && source.io.out.valid && clip.io.in.ready
  clip.io.in.bits:=source.io.out.bits.primitive
  source.io.out.ready:=active && (stop || clip.io.in.ready)
  when(source.io.out.fire){counts.mappedInputs:=counts.mappedInputs+1.U
    when(!clip.io.in.fire){counts.droppedMapped:=counts.droppedMapped+1.U}}
  when(clip.io.in.fire){counts.clipInputs:=counts.clipInputs+1.U
    clipInputId:=source.io.out.bits.inputPrimitiveId;clipInvocation:=source.io.out.bits.invocationId
    clipLayer:=source.io.out.bits.layer}
  prep.io.in.valid:=active && !stop && clip.io.out.valid && prep.io.in.ready
  prep.io.in.bits.clipped:=clip.io.out.bits;prep.io.in.bits.viewport:=task.viewport
  clip.io.out.ready:=active && (stop || prep.io.in.ready)
  when(clip.io.out.fire){counts.clipOutputs:=counts.clipOutputs+1.U
    when(!prep.io.in.fire){counts.droppedClipped:=counts.droppedClipped+1.U}}
  when(prep.io.in.fire){counts.viewportInputs:=counts.viewportInputs+1.U
    prepInputId:=clipInputId;prepInvocation:=clipInvocation;prepLayer:=clipLayer}
  io.out.valid:=prep.io.out.valid;io.out.bits.window:=prep.io.out.bits
  io.out.bits.inputPrimitiveId:=prepInputId;io.out.bits.invocationId:=prepInvocation;io.out.bits.layer:=prepLayer
  prep.io.out.ready:=io.out.ready
  when(io.out.fire){counts.preparedParts:=counts.preparedParts+1.U}
  prep.io.completion.ready:=active
  when(prep.io.completion.fire){val r=prep.io.completion.bits
    counts.arithmeticOperations:=counts.arithmeticOperations+r.arithmeticOperations
    counts.arithmeticStatus:=counts.arithmeticStatus|r.arithmeticStatus
    counts.packingStatus:=counts.packingStatus|r.packingStatus
    when(r.culled){counts.viewportCulled:=counts.viewportCulled+1.U}
    when(r.output && r.guardBandRequired){counts.guardBandParts:=counts.guardBandParts+1.U}
    when(r.status=/=0.U && r.status=/=4.U){fail(r.status,4)
      when(status===0.U && !externalCancel){
        counts.viewportFaultValid:=true.B;counts.viewportFault:=r
        counts.viewportFaultGeometry.inputPrimitiveId:=prepInputId
        counts.viewportFaultGeometry.invocationId:=prepInvocation;counts.viewportFaultGeometry.layer:=prepLayer
      }}}
  clip.io.completion.ready:=active
  when(clip.io.completion.fire){
    val r=clip.io.completion.bits
    when(r.culled){counts.clipCulled:=counts.clipCulled+1.U}
    when(r.status=/=0.U && r.status=/=4.U){fail(r.status,3)
      when(status===0.U && !externalCancel){
        counts.clipFaultValid:=true.B;counts.clipFault:=r
        counts.clipFaultGeometry.inputPrimitiveId:=clipInputId
        counts.clipFaultGeometry.invocationId:=clipInvocation;counts.clipFaultGeometry.layer:=clipLayer
      }}}
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){summary:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.emittedPrimitives===counts.mappedInputs,"native GS viewport lost a mapped primitive")
    when(source.io.completion.bits.status=/=0.U &&
      !(source.io.completion.bits.status===4.U && (clipFault || prepFault))){fail(source.io.completion.bits.status,2)}}
  when(active && externalCancel && !done && status===0.U){status:=4.U;faultStage:=0.U}
  when(active && sourceDone && !offered && !source.io.busy && !clip.io.busy && !prep.io.busy && !done){
    result:=counts;result.identity:=task.source.draw.vertex.input.identity
    result.status:=Mux(externalCancel && status===0.U,4.U,status);result.faultStage:=faultStage
    result.source:=summary;done:=true.B
  }
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
}
