package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class NativeGeometryVertexTask(p: VertexShaderStageConfig,commandIdWidth: Int) extends Bundle {
  val draw=new VertexGeometryDraw(p)
  val program=new NativeGeometryProgram
  val commandId=UInt(commandIdWidth.W);val taskId=UInt(32.W);val firstSequence=UInt(64.W)
}
class NativeGeometryVertexCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 1=static admission/arguments, 2=VS/assembly, 3=GS. */
  val faultStage=UInt(2.W)
  val source=new PrimitiveAssemblyCompletion
  val submittedPrimitives=UInt(64.W);val completedInvocations=UInt(70.W)
  val emittedPrimitives=UInt(78.W);val nextSequence=UInt(64.W)
}

/** Explicit native VS -> raw ordinary/adjacency assembly -> scalar GS owner.
  * The caller retains the validated immutable program and every draw resource
  * until the held result. Full-job reference preflight remains the enclosing
  * reader's responsibility. This component is not a product constructor and
  * does not yet perform clipping, binning or geometry texture/image/storage.
  */
class NativeGeometryVertexPipeline(config: GpuConfig,p: VertexShaderStageConfig,
    shared: SharedCuShaderConfig,useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false,
    allowOrdinary: Boolean=false) extends Module {
  require(shared.taskIdWidth>=1)
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new NativeGeometryVertexTask(p,sharedConfig.taskIdWidth)))
    /** Held together with task; an ordinary draw bypasses GS execution. */
    val ordinary=if(allowOrdinary)Some(Input(Bool()))else None
    val out=Decoupled(new GeometryMappedPrimitive(p))
    val completion=Decoupled(new NativeGeometryVertexCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val vertex=Module(new VertexShaderStage(config,p,shared,useSramBlackBoxes,payload,payloadOwner,groupedTexture))
  private val ordinary=Module(new PrimitiveAssemblyStage(p,flatShading=allowOrdinary))
  private val adjacent=Module(new GeometryAdjacencyAssemblyStage(p))
  private val geometry=Module(new NativeGeometryPrimitivePipeline(config,p,shared.taskIdWidth,shared.numComputeUnits))
  private val router=Module(new GeometryFragmentShaderRouter(config,shared.numComputeUnits,shared.taskIdWidth,payload))
  private val arguments=Module(new IndirectDrawReader(config))
  router.io.geometry<>geometry.io.shared;router.io.fragment<>vertex.io.shared;io.shared<>router.io.pool
  geometry.io.invalidate.valid:=false.B;geometry.io.invalidate.bits:=0.U.asTypeOf(geometry.io.invalidate.bits)
  geometry.io.invalidateDone.ready:=true.B
  if(groupedTexture)io.grouped.get<>vertex.io.grouped.get

  private object Phase extends ChiselEnum {val idle,validate,argumentOffer,argumentWait,running,report=Value}
  private val phase=RegInit(Phase.idle)
  private val task=Reg(new NativeGeometryVertexTask(p,shared.taskIdWidth))
  private val ordinaryTask=if(allowOrdinary)Some(RegInit(false.B))else None
  private val geometryEnabled= !ordinaryTask.getOrElse(false.B)
  private val result=Reg(new NativeGeometryVertexCompletion)
  private val summary=Reg(new PrimitiveAssemblyCompletion)
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W))
  private val cancelled=RegInit(false.B);private val resolved=RegInit(false.B)
  private val vertexOffered=RegInit(false.B);private val assemblyOffered=RegInit(false.B)
  private val vertexStarted=RegInit(false.B);private val assemblyStarted=RegInit(false.B)
  private val sourceDone=RegInit(false.B);private val geometryActive=RegInit(false.B)
  private val sourceFaultPending=RegInit(false.B)
  private val received=RegInit(0.U(64.W));private val submitted=RegInit(0.U(64.W))
  private val invocations=RegInit(0.U(70.W));private val emitted=RegInit(0.U(78.W))
  private val sequence=Reg(UInt(64.W));private val indirectLines=RegInit(0.U(2.W))
  private val payloadContext=payload.map(_=>Reg(UInt(32.W)))
  private val active=phase=/=Phase.idle && phase=/=Phase.report
  private val useAdjacent=geometryEnabled && task.draw.topology>=10.U
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val sourceCompletion=Wire(Decoupled(new PrimitiveAssemblyCompletion))
  private val sourceCancel=Mux(useAdjacent,adjacent.io.cancelSource,ordinary.io.cancelSource)
  private val sourceFailure=sourceCompletion.valid && sourceCompletion.bits.status=/=0.U
  private val geometryFailure=geometry.io.completion.valid && geometry.io.completion.bits.status=/=GeometryInvocationStatus.success &&
    geometry.io.completion.bits.status=/=GeometryInvocationStatus.cancelled
  private val abort=status=/=0.U || cancelled || externalCancel || geometryFailure
  private val stop=abort || sourceCancel || sourceFailure
  // Source cancellation requests precede its error summary. A GS cancelled by
  // that request must not replace the original VS/input error with cancelled.
  when(active && sourceCancel && !abort){sourceFaultPending:=true.B}
  io.task.ready:=phase===Phase.idle && !externalCancel;io.busy:=phase=/=Phase.idle
  when(active && externalCancel){cancelled:=true.B
    when(status===0.U){status:=VertexInputStatus.cancelled.U;faultStage:=0.U}}
  private def fail(code: UInt,stage: Int): Unit=when(status===0.U && !cancelled && !externalCancel){status:=code;faultStage:=stage.U}
  when(io.task.fire){
    task:=io.task.bits;summary:=0.U.asTypeOf(summary);result:=0.U.asTypeOf(result)
    ordinaryTask.foreach(_:=io.ordinary.get)
    status:=0.U;faultStage:=0.U;cancelled:=false.B;resolved:=false.B
    vertexOffered:=false.B;assemblyOffered:=false.B;vertexStarted:=false.B;assemblyStarted:=false.B
    sourceDone:=false.B;geometryActive:=false.B;sourceFaultPending:=false.B;received:=0.U;submitted:=0.U;invocations:=0.U;emitted:=0.U
    sequence:=io.task.bits.firstSequence;indirectLines:=0.U;phase:=Phase.validate
    payload.foreach(_=>payloadContext.get:=io.payload.get.context)
  }
  payload.foreach { _=>
    io.payload.get<>vertex.io.payload.get;vertex.io.payload.get.context:=payloadContext.get
    vertex.io.payload.get.cancel:=vertexStarted && stop
    router.io.clientPayload.get(0):=0.U.asTypeOf(Valid(new ShaderPayloadBinding(config,payload.get)))
    router.io.clientPayload.get(1):=vertex.io.payload.get.task
    io.payload.get.task:=router.io.poolPayload.get;io.payloadPins.get:=router.io.payloadPins.get
  }

  private val d=task.draw;private val v=d.vertex;private val g=task.program
  private val prefixBytes=(7.U+&(v.attributeSlots<<2)+&(v.varyingSlots<<2)+&(v.version===2.U).asUInt)*(config.lanes*config.warps*4).U
  private val prefixEnd=v.scratch.base.pad(48)+prefixBytes
  private val vertexErrors=VertexShaderDescriptor.errors(v,p,config,prefixEnd)
  private val parameterEnd=d.indirect.view.base+&Mux(d.indirect.mode===1.U,16.U,20.U)
  private def scratchAlias(base: UInt,limit: UInt): Bool=v.scratch.base<limit && prefixEnd>base
  private val uniformAlias=g.uniforms.version=/=0.U && scratchAlias(g.uniforms.address,g.uniforms.address.pad(33)+64.U)
  private val programLegal=NativeGeometryProgramLayout.legal(g,p) && NativeGeometryProgramLayout.topologyMatches(g,d.topology) &&
    g.identity.asUInt===v.input.identity.asUInt && g.vertexVaryingSlots===v.varyingSlots &&
    !scratchAlias(g.code.base,g.code.limit) && !uniformAlias
  when(phase===Phase.validate){
    when(stop){sourceDone:=true.B;phase:=Phase.running}
      .elsewhen((geometryEnabled && !programLegal) || (!geometryEnabled && d.topology>6.U) ||
        vertexErrors._1 || vertexErrors._2 || !IndirectDrawControl.legal(d.indirect,v.input.indexFormat) ||
        (d.indirect.mode=/=0.U && scratchAlias(d.indirect.view.base,parameterEnd))){
        fail(VertexInputStatus.invalidDescriptor.U,1);sourceDone:=true.B;phase:=Phase.running
      }.elsewhen(d.indirect.mode=/=0.U && !resolved){phase:=Phase.argumentOffer}
      .otherwise{vertexOffered:=true.B;assemblyOffered:=true.B;phase:=Phase.running}
  }
  arguments.io.in.valid:=phase===Phase.argumentOffer;arguments.io.in.bits:=d.indirect
  arguments.io.out.ready:=phase===Phase.argumentWait;arguments.io.cancel:=active && stop
  when(arguments.io.in.fire){phase:=Phase.argumentWait}
  when(arguments.io.out.fire){
    val r=arguments.io.out.bits;indirectLines:=r.lines
    when(stop || r.status=/=0.U){
      fail(r.status,1);sourceDone:=true.B;phase:=Phase.running
    }.otherwise{
      task.draw.vertex.input.count:=r.count;task.draw.vertex.input.instances:=r.instances
      task.draw.vertex.input.baseInstance:=0.U
      task.draw.vertex.input.firstVertex:=Mux(d.indirect.mode===1.U,r.first,0.U)
      task.draw.vertex.input.firstIndex:=Mux(d.indirect.mode===2.U,r.first,0.U)
      task.draw.vertex.input.baseVertex:=r.baseVertex;resolved:=true.B;phase:=Phase.validate
    }
  }

  vertex.io.draw.valid:=vertexOffered;vertex.io.draw.bits:=v
  when(vertex.io.draw.fire){vertexOffered:=false.B;vertexStarted:=true.B}
  vertex.io.cancel:=vertexStarted && stop
  private val assemblyDraw=Wire(new PrimitiveAssemblyDraw(p));assemblyDraw:=0.U.asTypeOf(assemblyDraw)
  assemblyDraw.version:=1.U;assemblyDraw.identity:=v.input.identity;assemblyDraw.topology:=d.topology
  assemblyDraw.count:=v.input.count;assemblyDraw.instances:=v.input.instances;assemblyDraw.baseInstance:=v.input.baseInstance
  assemblyDraw.varyingSlots:=v.varyingSlots // GS inputs retain raw, pre-flat VS values.
  if(allowOrdinary)assemblyDraw.flatMask:=Mux(geometryEnabled,0.U,d.flatMask)
  ordinary.io.draw.valid:=assemblyOffered && !useAdjacent;ordinary.io.draw.bits:=assemblyDraw
  adjacent.io.draw.valid:=assemblyOffered && useAdjacent;adjacent.io.draw.bits:=assemblyDraw
  when(ordinary.io.draw.fire || adjacent.io.draw.fire){assemblyOffered:=false.B;assemblyStarted:=true.B}
  ordinary.io.cancel:=assemblyStarted && !useAdjacent && abort;adjacent.io.cancel:=assemblyStarted && useAdjacent && abort
  ordinary.io.in.valid:=vertex.io.out.valid && !useAdjacent;ordinary.io.in.bits:=vertex.io.out.bits
  adjacent.io.in.valid:=vertex.io.out.valid && useAdjacent;adjacent.io.in.bits:=vertex.io.out.bits
  vertex.io.out.ready:=Mux(useAdjacent,adjacent.io.in.ready,ordinary.io.in.ready)
  ordinary.io.end.valid:=vertex.io.completion.valid && !useAdjacent;ordinary.io.end.bits:=vertex.io.completion.bits
  adjacent.io.end.valid:=vertex.io.completion.valid && useAdjacent;adjacent.io.end.bits:=vertex.io.completion.bits
  vertex.io.completion.ready:=Mux(useAdjacent,adjacent.io.end.ready,ordinary.io.end.ready)
  sourceCompletion.valid:=Mux(useAdjacent,adjacent.io.completion.valid,ordinary.io.completion.valid)
  sourceCompletion.bits:=Mux(useAdjacent,adjacent.io.completion.bits,ordinary.io.completion.bits)
  ordinary.io.completion.ready:=sourceCompletion.ready && !useAdjacent
  adjacent.io.completion.ready:=sourceCompletion.ready && useAdjacent
  sourceCompletion.ready:=active && !sourceDone
  when(sourceCompletion.fire){summary:=sourceCompletion.bits;sourceDone:=true.B
    assert(sourceCompletion.bits.primitives===received,"native GS source lost an assembled primitive")
    when(sourceCompletion.bits.status=/=0.U){fail(sourceCompletion.bits.status,2)}}

  private val assembled=Wire(Decoupled(new GeometryInputPrimitive(p)))
  assembled.valid:=Mux(useAdjacent,adjacent.io.out.valid,ordinary.io.out.valid)
  assembled.bits:=adjacent.io.out.bits
  when(!useAdjacent){
    val q=ordinary.io.out.bits;assembled.bits:=0.U.asTypeOf(assembled.bits)
    assembled.bits.identity:=q.identity;assembled.bits.sequence:=q.sequence;assembled.bits.primitiveId:=q.primitiveId
    assembled.bits.instanceId:=q.instanceId;assembled.bits.baseInstance:=q.baseInstance
    assembled.bits.topology:=q.topology;assembled.bits.kind:=q.kind;assembled.bits.vertexCount:=q.vertexCount
    assembled.bits.varyingSlots:=q.varyingSlots
    for(i<-0 until 3)assembled.bits.vertices(i):=q.vertices(i)
  }
  ordinary.io.out.ready:=assembled.ready && !useAdjacent;adjacent.io.out.ready:=assembled.ready && useAdjacent
  // Admit only into the mapper's private idle slot. No child offer can be
  // stranded by cancellation while a prior physical invocation is draining.
  geometry.io.task.valid:=active && geometryEnabled && !stop && !geometryActive && assembled.valid && geometry.io.task.ready
  geometry.io.task.bits:=0.U.asTypeOf(geometry.io.task.bits)
  geometry.io.task.bits.input.primitive:=assembled.bits;geometry.io.task.bits.input.program:=g
  geometry.io.task.bits.input.taskId:=task.taskId;geometry.io.task.bits.commandId:=task.commandId
  geometry.io.task.bits.firstSequence:=sequence
  assembled.ready:=active && (stop || (!geometryActive && geometry.io.task.ready))
  when(assembled.fire){received:=received+1.U}
  when(geometry.io.task.fire){geometryActive:=true.B;submitted:=submitted+1.U}
  geometry.io.cancel:=geometryActive && stop
  io.out<>geometry.io.primitive
  if(allowOrdinary){when(!geometryEnabled){
    io.out.valid:=ordinary.io.out.valid
    io.out.bits:=0.U.asTypeOf(io.out.bits)
    io.out.bits.primitive:=ordinary.io.out.bits
    io.out.bits.inputPrimitiveId:=ordinary.io.out.bits.primitiveId
    geometry.io.primitive.ready:=false.B
    // An offered ordinary primitive remains owned through cancellation too.
    assembled.ready:=io.out.ready
    when(io.out.fire){sequence:=sequence+1.U}
  }}
  when(io.out.fire){emitted:=emitted+1.U}
  geometry.io.completion.ready:=active && geometryActive
  when(geometry.io.completion.fire){
    val r=geometry.io.completion.bits;geometryActive:=false.B;sequence:=r.nextSequence
    invocations:=invocations+r.completedInvocations
    when(r.status=/=GeometryInvocationStatus.success &&
      !(r.status===GeometryInvocationStatus.cancelled && (sourceFaultPending || sourceCancel || sourceFailure))){
      fail(Mux(r.status===GeometryInvocationStatus.cancelled,VertexInputStatus.cancelled.U,
        Mux(r.status===GeometryInvocationStatus.invalid,VertexInputStatus.invalidDescriptor.U,5.U)),3)
    }
  }

  private val memory=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val queued=Module(new Queue(new ComputeMemoryRequest(config,64,8),2,pipe=true))
  memory.io.in(0).valid:=vertex.io.memoryRequest.valid;memory.io.in(0).bits:=vertex.io.memoryRequest.bits
  vertex.io.memoryRequest.ready:=memory.io.in(0).ready
  memory.io.in(1).valid:=geometry.io.memoryRequest.valid;memory.io.in(1).bits:=geometry.io.memoryRequest.bits
  memory.io.in(1).bits.transactionId:=4.U;geometry.io.memoryRequest.ready:=memory.io.in(1).ready
  queued.io.enq<>memory.io.out
  private val parameterOwner=phase===Phase.argumentOffer || phase===Phase.argumentWait
  io.memoryRequest.valid:=Mux(parameterOwner,arguments.io.memoryRequest.valid,queued.io.deq.valid)
  io.memoryRequest.bits:=queued.io.deq.bits
  when(parameterOwner){io.memoryRequest.bits:=arguments.io.memoryRequest.bits;io.memoryRequest.bits.transactionId:=0.U}
  arguments.io.memoryRequest.ready:=parameterOwner && io.memoryRequest.ready
  queued.io.deq.ready:= !parameterOwner && io.memoryRequest.ready
  private val tag=io.memoryResponse.bits.transactionId
  arguments.io.memoryResponse.valid:=parameterOwner && io.memoryResponse.valid
  arguments.io.memoryResponse.bits:=io.memoryResponse.bits;arguments.io.memoryResponse.bits.transactionId:=0.U
  vertex.io.memoryResponse.valid:= !parameterOwner && tag<4.U && io.memoryResponse.valid
  vertex.io.memoryResponse.bits:=io.memoryResponse.bits
  geometry.io.memoryResponse.valid:= !parameterOwner && tag===4.U && io.memoryResponse.valid
  geometry.io.memoryResponse.bits:=io.memoryResponse.bits;geometry.io.memoryResponse.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(parameterOwner,arguments.io.memoryResponse.ready,
    Mux(tag<4.U,vertex.io.memoryResponse.ready,tag===4.U && geometry.io.memoryResponse.ready))
  when(io.memoryResponse.valid){assert(Mux(parameterOwner,tag===0.U,tag<=4.U),"native GS response lost its owner")}
  when(active && sourceDone && !vertexOffered && !assemblyOffered && !geometryActive &&
    !vertex.io.busy && !ordinary.io.busy && !adjacent.io.busy && !geometry.io.busy && !arguments.io.busy &&
    queued.io.count===0.U && router.io.drained){
    result.identity:=v.input.identity;result.status:=Mux((cancelled || externalCancel) && status===0.U,VertexInputStatus.cancelled.U,status)
    result.faultStage:=faultStage;result.source:=summary;result.source.indirectLines:=indirectLines
    result.submittedPrimitives:=submitted;result.completedInvocations:=invocations
    result.emittedPrimitives:=emitted;result.nextSequence:=sequence;phase:=Phase.report
  }
  io.completion.valid:=phase===Phase.report;io.completion.bits:=result
  when(io.completion.fire){phase:=Phase.idle}
}
