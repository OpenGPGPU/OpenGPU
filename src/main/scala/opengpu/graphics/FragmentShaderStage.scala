package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,L2SramArray}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

case class FragmentShaderConfig(varyings: Int=16,colors: Int=4) {
  require(varyings>=1 && varyings<=16 && colors>=1 && colors<=4)
  val vertices=VertexShaderStageConfig(varyings=varyings)
  val maxOutputWords=4*colors+2
  def prefixBytes(capacity: Int,varyingSlots: Int,colorSlots: Int): Int=4*capacity*(8+4*varyingSlots+4*colorSlots)
}
/** Internal version1 SoA ABI, stride=4*physical-CU-thread-capacity:
  * inputs: fragCoord.xyzw, flags(front/helper/perspective/sampleMode), coverage,
  * then4*varyingSlots raw words. Outputs:4*colorSlots raw words, depth, control.
  * Control bits:0 emit,1 depth override,5:2 color mask;31:6 reserved zero.
  * Uniforms follow the complete prefix. Legacy nine-array ABI is unchanged.
  */
class FragmentShaderDraw(p: FragmentShaderConfig) extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity;val shaderPc=UInt(32.W)
  val scratch=new VertexInputView;val varyingSlots=UInt(5.W);val colorSlots=UInt(3.W)
  val colorMask=UInt(4.W);val colorTypes=Vec(p.colors,UInt(2.W));val allowDepthOverride=Bool();val sampleMode=UInt(2.W)
  val sampler=new VertexShaderSampler
}
/** Pure descriptor checks shared by the standalone client and draw admission. */
object FragmentShaderDescriptor {
  def errors(draw: FragmentShaderDraw,p: FragmentShaderConfig,g: GraphicsConfig,config: GpuConfig): (Bool,Bool)={
    val s=draw.scratch;val stride=config.lanes*config.warps*4
    val prefix=(8.U+&(draw.varyingSlots<<2)+&(draw.colorSlots<<2))*stride.U
    val end=s.base+&prefix
    val view=s.allocationBase(5,0)===0.U && s.allocationLimit(5,0)===0.U && s.allocationLimit<=(BigInt(1)<<32).U &&
      s.allocationLimit>s.allocationBase && s.base>=s.allocationBase && s.base(5,0)===0.U && s.limit>=s.base && s.limit<=s.allocationLimit
    val types=draw.colorTypes.zipWithIndex.map{case(t,i)=>i.U>=draw.colorSlots || t=/=3.U}.reduce(_ && _)
    val fields=draw.varyingSlots<=p.varyings.U && draw.colorSlots<=p.colors.U && !(draw.colorMask>>draw.colorSlots).orR &&
      types && draw.sampleMode<=log2Ceil(g.maxSampleCount).U
    val sharedAlias=s.base<(config.sharedMemoryBase+config.sharedMemoryBytes).U && end>config.sharedMemoryBase.U
    val invalid=draw.version=/=1.U || !fields || !view || draw.shaderPc===0.U || draw.shaderPc(1,0)=/=0.U || sharedAlias
    val bounds=end>s.limit || end>(BigInt(1)<<32).U || ((end+63.U)&(~63.U(40.W)))>s.allocationLimit
    (invalid,bounds)
  }
}
class FragmentInputCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val quads=UInt(99.W)
}
private class FragmentLaneMetadata(g: GraphicsConfig) extends Bundle {
  val x=SInt(32.W);val y=SInt(32.W);val depth=UInt(32.W);val coverage=UInt(g.maxSampleCount.W)
}
private class FragmentQuadMetadata(g: GraphicsConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val frontFacing=Bool();val sampleMode=UInt(2.W);val lanes=Vec(4,new FragmentLaneMetadata(g))
}
class FragmentShaderLane(p: FragmentShaderConfig,g: GraphicsConfig) extends Bundle {
  val x=SInt(32.W);val y=SInt(32.W);val depth=UInt(32.W);val coverageMask=UInt(g.maxSampleCount.W)
  val helper=Bool();val discarded=Bool();val depthOverride=Bool();val colorMask=UInt(4.W)
  val colors=Vec(p.colors,Vec(4,UInt(32.W)))
}
class FragmentShaderQuad(p: FragmentShaderConfig,g: GraphicsConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val frontFacing=Bool();val sampleMode=UInt(2.W);val colorSlots=UInt(3.W);val colorTypes=Vec(p.colors,UInt(2.W))
  val lanes=Vec(4,new FragmentShaderLane(p,g))
}
class FragmentShaderCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val inputQuads=UInt(99.W);val outputQuads=UInt(99.W);val batches=UInt(99.W);val localBatches=UInt(99.W);val globalBatches=UInt(99.W);val spilledBatches=UInt(99.W)
  val shadedLanes=UInt(101.W);val exportedLanes=UInt(101.W);val discardedLanes=UInt(101.W);val helperLanes=UInt(101.W);val coveredSamples=UInt(103.W)
  val stagingWriteBytes=UInt(128.W);val stagingReadBytes=UInt(128.W);val maxLocalX=UInt(16.W)
  /** 1 descriptor,2 input,3 staging,4 shader,5 texture,6 export,7 source. */
  val faultSource=UInt(3.W);val faultAddress=UInt(32.W);val faultQuadValid=Bool()
  val faultSequence=UInt(64.W);val faultPrimitiveId=UInt(32.W);val faultInstanceId=UInt(32.W);val faultPartIndex=UInt(3.W);val faultLane=UInt(2.W)
}

/** Bounded ordered generic fragment shader client. Complete quads stay aligned
  * through local/spilled SoA batches, physical shared-CU execution and typed raw
  * exports. Private synchronous SRAM retains metadata and a16-lane transpose
  * window; the payload lease lasts through every offered output. Source EOF is
  * explicit, including cancellation/fault drain and empty draws.
  */
class FragmentShaderStage(config: GpuConfig=GpuConfig(),p: FragmentShaderConfig=FragmentShaderConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),g: GraphicsConfig=GraphicsConfig(),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0) extends Module {
  private val capacity=config.lanes*config.warps;private val quadCapacity=capacity/4
  require(config.xLen==32 && config.lanes>=4 && config.lanes%4==0 && capacity<=255)
  private val stride=capacity*4;private val countWidth=log2Ceil(capacity+1)
  private val metaWidth=(new FragmentQuadMetadata(g)).getWidth;private val metaRows=(metaWidth+63)/64
  private val metaDepth=1<<log2Ceil(quadCapacity*metaRows);private val cacheDepth=1<<log2Ceil(p.maxOutputWords*8)
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new FragmentShaderDraw(p)))
    val in=Flipped(Decoupled(new Fp32RasterizedQuad(p.vertices,g)));val inputEnd=Flipped(Decoupled(new FragmentInputCompletion))
    val out=Decoupled(new FragmentShaderQuad(p,g));val completion=Decoupled(new FragmentShaderCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,collect,checkInput,allocate,writeInput,waitInput,writeMetadata,clearOutput,waitClear,initializeOutput,waitInitialize,
      invalidate,waitLaunch,waitKernel,cacheSlice,waitCacheFirst,cacheSecond,waitCacheSecond,writeCache,
      readMetadata,waitMetadata,prepareOutput,readFirst,waitFirst,readSecond,waitSecond,checkOutput,emit,release,waitRelease,drain,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val draw=Reg(new FragmentShaderDraw(p));private val result=Reg(new FragmentShaderCompletion)
  private val inputQuad=Reg(new Fp32RasterizedQuad(p.vertices,g));private val output=Reg(new FragmentShaderQuad(p,g));private val outputValid=RegInit(false.B)
  private val inputDone=RegInit(false.B);private val status=RegInit(0.U(3.W))
  private val count=RegInit(0.U(countWidth.W));private val field=RegInit(0.U(7.W));private val initControl=RegInit(false.B)
  private val inputWords=Reg(UInt(7.W));private val colorWords=Reg(UInt(5.W));private val outputWords=Reg(UInt(5.W))
  private val inputBytes=Reg(UInt(32.W));private val prefixBytes=Reg(UInt(32.W));private val payloadContext=Reg(UInt(32.W))
  private val helperQuads=RegInit(VecInit(Seq.fill(quadCapacity)(0.U(4.W))))
  private val metaRow=RegInit(0.U(math.max(1,log2Ceil(metaRows)).W));private val metaBuffer=Reg(Vec(metaRows,UInt(64.W)))
  private val clearLine=Reg(UInt(32.W));private val chunk=RegInit(0.U(countWidth.W));private val emitIndex=RegInit(0.U(countWidth.W))
  private val firstShift=Reg(UInt(9.W));private val secondBytes=Reg(UInt(7.W));private val secondLine=Reg(UInt(32.W));private val firstPart=Reg(UInt(512.W))
  private val cacheData=Reg(UInt(512.W));private val cacheRow=RegInit(0.U(3.W))
  private val shaderDepth=Reg(Vec(4,UInt(32.W)));private val controls=Reg(Vec(4,UInt(32.W)))
  private val received=RegInit(0.U(99.W));private val exported=RegInit(0.U(99.W));private val batches=RegInit(0.U(99.W))
  private val localBatches=RegInit(0.U(99.W));private val globalBatches=RegInit(0.U(99.W));private val spilledBatches=RegInit(0.U(99.W))
  private val shaded=RegInit(0.U(101.W));private val live=RegInit(0.U(101.W));private val discarded=RegInit(0.U(101.W));private val helpers=RegInit(0.U(101.W));private val samples=RegInit(0.U(103.W))
  private val writeBytes=RegInit(0.U(128.W));private val readBytes=RegInit(0.U(128.W));private val maxLocalX=RegInit(0.U(16.W))
  private val faultSource=RegInit(0.U(3.W));private val faultAddress=RegInit(0.U(32.W));private val faultQuadValid=RegInit(false.B)
  private val faultSequence=RegInit(0.U(64.W));private val faultPrimitive=RegInit(0.U(32.W));private val faultInstance=RegInit(0.U(32.W));private val faultPart=RegInit(0.U(3.W));private val faultLane=RegInit(0.U(2.W))
  private val allocationOffered=RegInit(false.B);private val allocationStarted=RegInit(false.B);private val allocationPrepared=RegInit(false.B)
  private val resident=RegInit(false.B);private val leaseActive=RegInit(false.B);private val finishOffered=RegInit(false.B);private val finishStarted=RegInit(false.B)
  private val invalidateOffered=RegInit(false.B);private val invalidatePending=RegInit(false.B);private val launchOffered=RegInit(false.B);private val kernelActive=RegInit(false.B);private val kernelDone=RegInit(false.B)
  private val memOffered=RegInit(false.B);private val memPending=RegInit(false.B);private val memRequest=Reg(new ComputeMemoryRequest(config,64,1))
  private val shader=Module(new KernelShaderStage(config,true,Some(shared.copy(shaderPayload=None)),helperInvocations=true))
  private val texture=Module(new TexSampleUnit(config));private val textureBridge=Module(new OmWordToLinePort(config,maxOutstanding=2))
  private val transport=payload.map(q=>Module(new FragmentPayloadTransport(config,q,payloadOwner,1)))
  private val metadataBanks=Seq.fill((metaDepth+255)/256)(Module(new L2SramArray(math.min(256,metaDepth),64,useSramBlackBoxes)))
  private val metadataRead=WireDefault(false.B);private val metadataWrite=WireDefault(false.B)
  private val metadataAddress=WireDefault(0.U(log2Ceil(metaDepth).W));private val metadataData=WireDefault(0.U(64.W))
  private val metadataBank=RegEnable(metadataAddress.pad(9)>>8,metadataRead)
  for((bank,i)<-metadataBanks.zipWithIndex) {
    val selected=(metadataAddress.pad(9)>>8)===i.U
    bank.io.readEnable:=metadataRead && selected;bank.io.writeEnable:=metadataWrite && selected
    bank.io.readAddress:=metadataAddress;bank.io.writeAddress:=metadataAddress;bank.io.writeData:=metadataData
  }
  private val metadataReply=if(metadataBanks.size==1)metadataBanks.head.io.readData else VecInit(metadataBanks.map(_.io.readData))(metadataBank)
  private val cache=Module(new L2SramArray(cacheDepth,64,useSramBlackBoxes))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val sourceFault=io.inputEnd.valid && !inputDone && io.inputEnd.bits.status=/=0.U
  private val allocationFault=WireDefault(false.B);private val releaseFault=WireDefault(false.B)
  private val stageRequest=Wire(Decoupled(new ComputeMemoryRequest(config,64,1)));private val stageResponse=Wire(Decoupled(new ComputeMemoryResponse(64,1)))
  private val fallbackRequest=Wire(Decoupled(new ComputeMemoryRequest(config,64,1)));private val fallbackResponse=Wire(Decoupled(new ComputeMemoryResponse(64,1)))
  stageRequest.valid:=memOffered;stageRequest.bits:=memRequest;stageResponse.ready:=memPending
  when(stageRequest.fire){memOffered:=false.B;memPending:=true.B
    when(memRequest.isWrite){writeBytes:=writeBytes+PopCount(memRequest.byteMask)}.otherwise{readBytes:=readBytes+PopCount(memRequest.byteMask)}}
  when(stageResponse.fire){memPending:=false.B}
  transport match {
    case Some(t)=>
      t.io.stageRequest<>stageRequest;stageResponse<>t.io.stageResponse;fallbackRequest<>t.io.memoryRequest;t.io.memoryResponse<>fallbackResponse
      val bus=io.payload.get;bus.control<>t.io.pool.control;bus.controlResponse<>t.io.pool.controlResponse;bus.request<>t.io.pool.request;bus.response<>t.io.pool.response;bus.task:=t.io.pool.task
      t.io.pool.context:=payloadContext;t.io.begin.valid:=allocationOffered;t.io.begin.bits.base:=draw.scratch.base;t.io.begin.bits.bytes:=prefixBytes;t.io.begin.bits.context:=payloadContext
      when(t.io.begin.fire){allocationOffered:=false.B;allocationStarted:=true.B}
      t.io.prepared.ready:=allocationStarted && !allocationPrepared;allocationFault:=t.io.prepared.valid && t.io.prepared.bits.fault
      when(t.io.prepared.fire){allocationPrepared:=true.B;resident:=t.io.prepared.bits.resident;leaseActive:=true.B}
      t.io.finish.valid:=finishOffered;t.io.finish.bits:=status=/=0.U
      when(t.io.finish.fire){finishOffered:=false.B;finishStarted:=true.B}
      t.io.done.ready:=finishStarted;releaseFault:=t.io.done.valid && t.io.done.bits
    case None=>fallbackRequest<>stageRequest;stageResponse<>fallbackResponse
  }
  private val memoryArbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,4),2));private val memoryQueue=Module(new Queue(new ComputeMemoryRequest(config,64,4),1))
  for((source,i)<-Seq(fallbackRequest,textureBridge.io.memoryRequest).zipWithIndex){
    memoryArbiter.io.in(i).valid:=source.valid;memoryArbiter.io.in(i).bits:=source.bits
    memoryArbiter.io.in(i).bits.transactionId:=(if(i==0)0.U else 2.U+source.bits.transactionId);source.ready:=memoryArbiter.io.in(i).ready
  }
  memoryQueue.io.enq<>memoryArbiter.io.out;io.memoryRequest<>memoryQueue.io.deq
  private val tag=io.memoryResponse.bits.transactionId
  fallbackResponse.valid:=io.memoryResponse.valid && tag===0.U;fallbackResponse.bits:=io.memoryResponse.bits;fallbackResponse.bits.transactionId:=0.U
  textureBridge.io.memoryResponse.valid:=io.memoryResponse.valid && tag>=2.U;textureBridge.io.memoryResponse.bits:=io.memoryResponse.bits;textureBridge.io.memoryResponse.bits.transactionId:=tag-2.U
  io.memoryResponse.ready:=Mux(tag===0.U,fallbackResponse.ready,textureBridge.io.memoryResponse.ready)
  when(io.memoryResponse.valid){assert(tag=/=1.U,"fragment memory response used an unreserved ID")}
  io.shared<>shader.io.shared.get
  shader.io.instructionSatp:=0.U;shader.io.vectorSatp:=0.U;shader.io.instructionTlbFlush:=0.U.asTypeOf(shader.io.instructionTlbFlush);shader.io.vectorTlbFlush:=0.U.asTypeOf(shader.io.vectorTlbFlush)
  shader.io.memoryRequest.ready:=true.B;shader.io.memoryResponse.valid:=false.B;shader.io.memoryResponse.bits:=0.U.asTypeOf(shader.io.memoryResponse.bits)
  shader.io.globalAtomicRequest.ready:=true.B;shader.io.globalAtomicResponse.valid:=false.B;shader.io.globalAtomicResponse.bits:=0.U.asTypeOf(shader.io.globalAtomicResponse.bits)
  shader.io.simtBranch.valid:=false.B;shader.io.simtBranch.bits:=0.U.asTypeOf(shader.io.simtBranch.bits)

  private val texturePending=RegInit(0.U(3.W));private val textureInstructions=RegInit(0.U(3.W))
  texturePending:=texturePending+textureBridge.io.memoryRequest.fire.asUInt-textureBridge.io.memoryResponse.fire.asUInt
  texture.io.in<>shader.io.texSample;texture.io.vectorIn<>shader.io.vectorTexSample
  private val dropTexture=shader.io.completion.valid && (status=/=0.U || externalCancel || !shader.io.completion.bits.success)
  shader.io.texWriteback.valid:=texture.io.commit.valid && !dropTexture;shader.io.texWriteback.bits:=texture.io.commit.bits
  shader.io.vectorTexWriteback.valid:=texture.io.vectorCommit.valid && !dropTexture;shader.io.vectorTexWriteback.bits:=texture.io.vectorCommit.bits
  texture.io.commit.ready:=dropTexture || shader.io.texWriteback.ready;texture.io.vectorCommit.ready:=dropTexture || shader.io.vectorTexWriteback.ready
  private val textureStarts=PopCount(Seq(texture.io.in.fire,texture.io.vectorIn.fire))
  private val textureEnds=PopCount(Seq(texture.io.commit.fire,texture.io.vectorCommit.fire))
  textureInstructions:=textureInstructions+textureStarts-textureEnds
  assert(textureEnds<=textureInstructions+textureStarts,"fragment texture completion must retain its instruction")
  textureBridge.io.in<>texture.io.mem.req;texture.io.mem.resp<>textureBridge.io.out
  texture.io.texFormat:=draw.sampler.format;texture.io.texBase:=draw.sampler.base
  texture.io.texWidth:=draw.sampler.texWidth;texture.io.texHeight:=draw.sampler.texHeight
  texture.io.wrapClamp:=draw.sampler.clamp;texture.io.texMaxLevel:=draw.sampler.maxLevel
  texture.io.minLevel:=draw.sampler.minLevel;texture.io.lodBias:=draw.sampler.bias
  private val textureDrained=texturePending===0.U && textureInstructions===0.U &&
    !textureBridge.io.memoryRequest.valid && !shader.io.texSample.valid && !shader.io.vectorTexSample.valid
  shader.io.trap.ready:=true.B;shader.io.completion.ready:=kernelActive && textureDrained
  private val shaderFault=shader.io.trap.valid || (shader.io.completion.valid && !shader.io.completion.bits.success)
  private val dataFault=stageResponse.valid && stageResponse.bits.fault
  private val textureFault=textureBridge.io.responseFault
  private val stop=status=/=0.U || externalCancel || sourceFault || dataFault || shaderFault || textureFault || allocationFault || releaseFault
  transport.foreach(_.io.pool.cancel:=stop)
  shader.io.launch.valid:=launchOffered;shader.io.launch.kernelPc:=draw.shaderPc;shader.io.launch.kernargAddress:=draw.scratch.base
  shader.io.launch.gridX:=1.U;shader.io.launch.gridY:=1.U;shader.io.launch.gridZ:=1.U
  shader.io.launch.localX:=count;shader.io.launch.localY:=1.U;shader.io.launch.localZ:=1.U
  shader.io.launch.invocations.get.helpers:=helperQuads.asUInt
  shader.io.launch.invocations.get.privateBase:=draw.scratch.base;shader.io.launch.invocations.get.privateBytes:=prefixBytes
  when(shader.io.launch.valid && shader.io.launch.ready) {
    launchOffered:=false.B;kernelActive:=true.B;kernelDone:=false.B;batches:=batches+1.U;shaded:=shaded+count
    when(resident){localBatches:=localBatches+1.U}.otherwise{globalBatches:=globalBatches+1.U}
    if(payload.nonEmpty)when(!resident){spilledBatches:=spilledBatches+1.U}
    when(count>maxLocalX){maxLocalX:=count}
  }
  when(shader.io.completion.fire){kernelActive:=false.B;kernelDone:=true.B}
  shader.io.l1Invalidate.valid:=invalidateOffered
  shader.io.l1Invalidate.bits.lineAddress:=opengpu.core.memory.CacheLineInvalidate.allLinesAddress(config)
  when(shader.io.l1Invalidate.fire){invalidateOffered:=false.B;invalidatePending:=true.B}
  shader.io.l1InvalidateDone.ready:=invalidatePending
  when(shader.io.l1InvalidateDone.fire){invalidatePending:=false.B
    assert(shader.io.l1InvalidateDone.bits.lineAddress===opengpu.core.memory.CacheLineInvalidate.allLinesAddress(config))}

  io.draw.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.in.ready:=active && !inputDone && (stop || (phase===State.collect && count<capacity.U))
  io.inputEnd.ready:=active && !inputDone;io.cancelSource:=active && stop
  io.out.valid:=outputValid;io.out.bits:=output;io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(io.in.fire){received:=received+1.U;when(!stop){inputQuad:=io.in.bits;phase:=State.checkInput}}
  when(io.out.fire){outputValid:=false.B;exported:=exported+1.U
    live:=live+PopCount(output.lanes.map(l=>l.coverageMask.orR))
    discarded:=discarded+PopCount(output.lanes.map(_.discarded));helpers:=helpers+PopCount(output.lanes.map(_.helper))
    samples:=samples+output.lanes.map(l=>PopCount(l.coverageMask).pad(103)).reduce(_+_)
  }
  private def fail(code: UInt,source: UInt,address: UInt,quad: Bool=false.B,lane: UInt=0.U): Unit={
    when(status===0.U && !externalCancel){status:=code;faultSource:=source;faultAddress:=address;faultQuadValid:=quad;faultLane:=lane
      when(quad){faultSequence:=inputQuad.values.sequence;faultPrimitive:=inputQuad.values.primitiveId
        faultInstance:=inputQuad.values.instanceId;faultPart:=inputQuad.values.partIndex}}
  }
  when(io.inputEnd.fire){inputDone:=true.B
    when(io.inputEnd.bits.identity.asUInt=/=draw.identity.asUInt || io.inputEnd.bits.quads=/=received+io.in.fire.asUInt){fail(6.U,7.U,0.U)}
    .elsewhen(io.inputEnd.bits.status=/=0.U){fail(io.inputEnd.bits.status,7.U,0.U)}
  }
  private def mask(offset: UInt,bytes: UInt): UInt=(((~0.U(64.W))>>(64.U(7.W)-bytes))<<offset)(63,0)
  private def issue(address: UInt,write: Boolean,data: UInt,byteMask: UInt): Unit={
    assert(!memOffered && !memPending)
    memRequest:=0.U.asTypeOf(memRequest);memRequest.address:=address;memRequest.isWrite:=write.B
    memRequest.writeData:=data;memRequest.byteMask:=byteMask;memRequest.sizeLog2:=6.U
    memRequest.cachePolicy:=CachePolicy.uncached;memOffered:=true.B
  }
  private val outputStart=draw.scratch.base+inputBytes;private val prefixEnd=draw.scratch.base+&prefixBytes
  private val clearFirst=Cat(outputStart(31,6),0.U(6.W));private val clearLast=Cat((prefixEnd-1.U)(31,6),0.U(6.W))
  private val clearLow=(~0.U(64.W))<<outputStart(5,0)
  private val clearHigh=Mux(prefixEnd(5,0)===0.U,~0.U(64.W),(~0.U(64.W))>>(64.U-prefixEnd(5,0)))
  private val clearMask=Mux(clearLine===clearFirst,clearLow,~0.U(64.W)) & Mux(clearLine===clearLast,clearHigh,~0.U(64.W))
  private val inputAddress=draw.scratch.base+field*stride.U+(count<<2)
  private val inputData=Wire(Vec(4,UInt(32.W)))
  for(i<-0 until 4) {
    val l=inputQuad.values.lanes(i)
    def center(value: SInt): UInt={
      val integer=FragmentEdgeFloat(((value.pad(34)<<1)+1.S).pad(64))
      val half=WireDefault(integer);half.exponent:=integer.exponent-1.S
      ClipFloat.toFp32(half).bits
    }
    inputData(i):=0.U
    when(field===0.U){inputData(i):=center(l.x)};when(field===1.U){inputData(i):=center(l.y)}
    when(field===2.U){inputData(i):=l.windowDepth};when(field===3.U){inputData(i):=l.fragCoordW}
    when(field===4.U){inputData(i):=Cat(0.U(27.W),inputQuad.sampleMode,l.perspectiveValid,!l.covered,inputQuad.frontFacing)}
    when(field===5.U){inputData(i):=l.coverageMask}
    for(v<-0 until p.varyings;c<-0 until 4)when(field===(6+4*v+c).U){inputData(i):=l.varyings(v)(c)}
  }
  private val inputMeta=Wire(new FragmentQuadMetadata(g));inputMeta:=0.U.asTypeOf(inputMeta)
  inputMeta.identity:=inputQuad.values.identity;inputMeta.sequence:=inputQuad.values.sequence
  inputMeta.primitiveId:=inputQuad.values.primitiveId;inputMeta.instanceId:=inputQuad.values.instanceId
  inputMeta.partIndex:=inputQuad.values.partIndex;inputMeta.frontFacing:=inputQuad.frontFacing;inputMeta.sampleMode:=inputQuad.sampleMode
  for(i<-0 until 4){val l=inputQuad.values.lanes(i);inputMeta.lanes(i).x:=l.x;inputMeta.lanes(i).y:=l.y
    inputMeta.lanes(i).depth:=l.windowDepth;inputMeta.lanes(i).coverage:=l.coverageMask}
  private val savedMeta=metaBuffer.asUInt(metaWidth-1,0).asTypeOf(new FragmentQuadMetadata(g))
  metadataAddress:=Mux(phase===State.writeMetadata,(count>>2)*metaRows.U+metaRow,(emitIndex>>2)*metaRows.U+metaRow)
  metadataRead:=phase===State.readMetadata && !stop
  metadataData:=(inputMeta.asUInt.pad(metaRows*64)>>(metaRow<<6))(63,0)
  cache.io.writeEnable:=false.B;cache.io.writeAddress:=field*8.U+cacheRow;cache.io.writeData:=(cacheData>>(cacheRow<<6))(63,0)
  private val gatherRow=((emitIndex-chunk)>>1)
  cache.io.readEnable:=(phase===State.readFirst || phase===State.readSecond) && !stop
  cache.io.readAddress:=field*8.U+gatherRow+(phase===State.readSecond).asUInt
  private val chunkCount=Mux(count-chunk>16.U,16.U,count-chunk);private val chunkBytes=chunkCount<<2
  private val cacheAddress=outputStart+field*stride.U+(chunk<<2);private val cacheLine=Cat(cacheAddress(31,6),0.U(6.W))
  private val firstBytes=Mux(chunkBytes>64.U-cacheAddress(5,0),64.U-cacheAddress(5,0),chunkBytes)
  private def gatherPair(first: Int): Unit={
    for(i<-0 until 2){val word=cache.io.readData(32*i+31,32*i)
      for(c<-0 until p.colors;v<-0 until 4)when(field<colorWords && field===(4*c+v).U){output.lanes(first+i).colors(c)(v):=word}
      when(field===colorWords){shaderDepth(first+i):=word};when(field===colorWords+1.U){controls(first+i):=word}
    }
  }
  private def resetBatch(): Unit={
    leaseActive:=false.B;allocationStarted:=false.B;allocationPrepared:=false.B;finishStarted:=false.B;resident:=false.B
    count:=0.U;helperQuads.foreach(_:=0.U);phase:=Mux(stop,State.drain,State.collect)
  }
  private val memoryDrained= !memOffered && !memPending && !memoryQueue.io.deq.valid
  private val controlsDrained= !allocationOffered && (!allocationStarted || allocationPrepared) &&
    !invalidateOffered && !invalidatePending && !launchOffered && !kernelActive && textureDrained

  when(!stop || phase===State.idle || phase===State.drain || phase===State.release || phase===State.waitRelease || phase===State.finish || phase===State.complete) {
    switch(phase) {
      is(State.idle){when(io.draw.fire){
        draw:=io.draw.bits;status:=0.U;count:=0.U;inputDone:=false.B;outputValid:=false.B
        received:=0.U;exported:=0.U;batches:=0.U;localBatches:=0.U;globalBatches:=0.U;spilledBatches:=0.U
        shaded:=0.U;live:=0.U;discarded:=0.U;helpers:=0.U;samples:=0.U;writeBytes:=0.U;readBytes:=0.U;maxLocalX:=0.U
        faultSource:=0.U;faultAddress:=0.U;faultQuadValid:=false.B;faultSequence:=0.U;faultPrimitive:=0.U;faultInstance:=0.U;faultPart:=0.U;faultLane:=0.U
        val in=6.U+(io.draw.bits.varyingSlots<<2);val color=io.draw.bits.colorSlots<<2
        inputWords:=in;colorWords:=color;outputWords:=color+2.U;inputBytes:=in*stride.U;prefixBytes:=(in+&color+2.U)*stride.U
        payloadContext:=io.payload.map(_.context).getOrElse(0.U);helperQuads.foreach(_:=0.U);phase:=State.validate
      }}
      is(State.validate){
        val (invalid,bounds)=FragmentShaderDescriptor.errors(draw,p,g,config)
        when(invalid){fail(1.U,1.U,draw.scratch.base);phase:=State.drain}
          .elsewhen(bounds){fail(2.U,1.U,draw.scratch.base);phase:=State.drain}
          .otherwise{phase:=State.collect}
      }
      is(State.collect){
        when(count===capacity.U || (inputDone && count=/=0.U)){phase:=State.invalidate}
          .elsewhen(inputDone && !io.in.fire){phase:=State.finish}
      }
      is(State.checkInput){
        val q=inputQuad.values;val top=q.lanes(0)
        val sampleCount=1.U(5.W)<<inputQuad.sampleMode
        val bad=VecInit(q.lanes.zipWithIndex.map{case(l,i)=>
          val shape=l.x.pad(33)=/=top.x.pad(33)+(i%2).S || l.y.pad(33)=/=top.y.pad(33)+(i/2).S
          val reciprocal=ClipFloat.legal(l.reciprocalW) && l.reciprocalW.special===0.U && !l.reciprocalW.negative && l.reciprocalW.significand.orR
          val depth=l.windowDepth(30,23)=/=255.U && !PrimitiveClipStage.less(l.windowDepth,0.U) && !PrimitiveClipStage.less("h3f800000".U,l.windowDepth)
          shape || l.covered=/=l.coverageMask.orR || (l.coverageMask>>sampleCount).orR ||
            (l.covered && (!l.perspectiveValid || !reciprocal || !depth))
        })
        val header=q.identity.asUInt=/=draw.identity.asUInt || q.varyingSlots=/=draw.varyingSlots ||
          (q.flatMask>>(draw.varyingSlots<<2)).orR || inputQuad.sampleMode=/=draw.sampleMode || q.partIndex>6.U || top.x(0) || top.y(0)
        when(header || bad.asUInt.orR){fail(6.U,2.U,0.U,true.B,PriorityEncoder(bad));phase:=State.drain}
          .otherwise{
            val helperMask=VecInit(q.lanes.map(l=> !l.covered)).asUInt
            if(quadCapacity==1)helperQuads(0):=helperMask
            else helperQuads((count>>2)(log2Ceil(quadCapacity)-1,0)):=helperMask
            when(!leaseActive){
              if(payload.nonEmpty){allocationOffered:=true.B;phase:=State.allocate}
              else{leaseActive:=true.B;resident:=false.B;clearLine:=clearFirst;phase:=State.clearOutput}
            }.otherwise{field:=0.U;phase:=State.writeInput}
          }
      }
      is(State.allocate){when(allocationPrepared){field:=0.U;clearLine:=clearFirst;phase:=Mux(resident,State.writeInput,State.clearOutput)}}
      is(State.clearOutput){issue(clearLine,true,0.U,clearMask);phase:=State.waitClear}
      is(State.waitClear){when(stageResponse.fire){when(clearLine===clearLast){field:=0.U;phase:=State.writeInput}
        .otherwise{clearLine:=clearLine+64.U;phase:=State.clearOutput}}}
      is(State.writeInput){
        issue(Cat(inputAddress(31,6),0.U(6.W)),true,inputData.asUInt.pad(512)<<(inputAddress(5,0)<<3),65535.U(64.W)<<inputAddress(5,0));phase:=State.waitInput
      }
      is(State.waitInput){when(stageResponse.fire){when(field===inputWords-1.U){initControl:=false.B;phase:=State.initializeOutput}
        .otherwise{field:=field+1.U;phase:=State.writeInput}}}
      is(State.initializeOutput){
        val address=outputStart+(colorWords+initControl.asUInt)*stride.U+(count<<2)
        val words=VecInit(inputQuad.values.lanes.map(l=>Mux(initControl,Mux(l.covered,1.U(32.W)|(draw.colorMask<<2),0.U),l.windowDepth)))
        issue(Cat(address(31,6),0.U(6.W)),true,words.asUInt.pad(512)<<(address(5,0)<<3),65535.U(64.W)<<address(5,0));phase:=State.waitInitialize
      }
      is(State.waitInitialize){when(stageResponse.fire){when(initControl){metaRow:=0.U;phase:=State.writeMetadata}
        .otherwise{initControl:=true.B;phase:=State.initializeOutput}}}
      is(State.writeMetadata){
        metadataWrite:=true.B
        when(metaRow===(metaRows-1).U){count:=count+4.U;phase:=State.collect}.otherwise{metaRow:=metaRow+1.U}
      }
      is(State.invalidate){kernelDone:=false.B;invalidateOffered:=true.B;phase:=State.waitLaunch}
      is(State.waitLaunch){when(!invalidateOffered && !invalidatePending){launchOffered:=true.B;phase:=State.waitKernel}}
      is(State.waitKernel){when(kernelDone){chunk:=0.U;field:=0.U;phase:=State.cacheSlice}}
      is(State.cacheSlice){
        firstShift:=cacheAddress(5,0)<<3;secondBytes:=chunkBytes-firstBytes;secondLine:=cacheLine+64.U
        issue(cacheLine,false,0.U,mask(cacheAddress(5,0),firstBytes));phase:=State.waitCacheFirst
      }
      is(State.waitCacheFirst){when(stageResponse.fire){val data=stageResponse.bits.readData>>firstShift
        when(secondBytes=/=0.U){firstPart:=data;phase:=State.cacheSecond}
          .otherwise{cacheData:=data;cacheRow:=0.U;phase:=State.writeCache}
      }}
      is(State.cacheSecond){issue(secondLine,false,0.U,mask(0.U,secondBytes));phase:=State.waitCacheSecond}
      is(State.waitCacheSecond){when(stageResponse.fire){
        cacheData:=firstPart|(stageResponse.bits.readData<<(512.U-firstShift));cacheRow:=0.U;phase:=State.writeCache
      }}
      is(State.writeCache){cache.io.writeEnable:=true.B
        when(cacheRow===7.U){when(field===outputWords-1.U){emitIndex:=chunk;metaRow:=0.U;phase:=State.readMetadata}
          .otherwise{field:=field+1.U;phase:=State.cacheSlice}}.otherwise{cacheRow:=cacheRow+1.U}
      }
      is(State.readMetadata){phase:=State.waitMetadata}
      is(State.waitMetadata){metaBuffer(metaRow):=metadataReply
        when(metaRow===(metaRows-1).U){phase:=State.prepareOutput}.otherwise{metaRow:=metaRow+1.U;phase:=State.readMetadata}}
      is(State.prepareOutput){
        output:=0.U.asTypeOf(output);output.identity:=savedMeta.identity;output.sequence:=savedMeta.sequence
        output.primitiveId:=savedMeta.primitiveId;output.instanceId:=savedMeta.instanceId;output.partIndex:=savedMeta.partIndex
        output.frontFacing:=savedMeta.frontFacing;output.sampleMode:=savedMeta.sampleMode;output.colorSlots:=draw.colorSlots;output.colorTypes:=draw.colorTypes
        for(i<-0 until 4){output.lanes(i).x:=savedMeta.lanes(i).x;output.lanes(i).y:=savedMeta.lanes(i).y
          output.lanes(i).depth:=savedMeta.lanes(i).depth;output.lanes(i).coverageMask:=savedMeta.lanes(i).coverage
          output.lanes(i).helper:= !savedMeta.lanes(i).coverage.orR}
        field:=0.U;phase:=State.readFirst
      }
      is(State.readFirst){phase:=State.waitFirst}
      is(State.waitFirst){gatherPair(0);phase:=State.readSecond}
      is(State.readSecond){phase:=State.waitSecond}
      is(State.waitSecond){gatherPair(2)
        when(field===outputWords-1.U){phase:=State.checkOutput}.otherwise{field:=field+1.U;phase:=State.readFirst}}
      is(State.checkOutput){
        val bad=VecInit((0 until 4).map{i=>val c=controls(i)
          !output.lanes(i).helper && (c(31,6).orR || (c(0) && ((c(5,2)& ~draw.colorMask).orR ||
            (c(1) && (!draw.allowDepthOverride || Fp32ViewportState.nan(shaderDepth(i)))))))})
        when(bad.asUInt.orR){fail(7.U,6.U,0.U)
          when(status===0.U && !externalCancel){faultQuadValid:=true.B;faultSequence:=output.sequence;faultPrimitive:=output.primitiveId
            faultInstance:=output.instanceId;faultPart:=output.partIndex;faultLane:=PriorityEncoder(bad)}
          phase:=State.drain
        }.otherwise{
          for(i<-0 until 4){val l=output.lanes(i);val c=controls(i);val enabled= !l.helper && c(0)
            l.discarded:= !l.helper && !c(0);l.coverageMask:=Mux(enabled,l.coverageMask,0.U);l.colorMask:=Mux(enabled,c(5,2),0.U)
            l.depthOverride:=enabled && c(1);when(enabled && c(1)){l.depth:=Fp32ViewportState.clampDepth(shaderDepth(i))}
          }
          outputValid:=true.B;phase:=State.emit
        }
      }
      is(State.emit){when(io.out.fire){
        when(emitIndex+4.U===count){phase:=State.release}
          .elsewhen(emitIndex+4.U===chunk+chunkCount){chunk:=chunk+chunkCount;field:=0.U;phase:=State.cacheSlice}
          .otherwise{emitIndex:=emitIndex+4.U;metaRow:=0.U;phase:=State.readMetadata}
      }}
      is(State.release){
        if(payload.nonEmpty){finishOffered:=true.B;phase:=State.waitRelease}else{resetBatch()}
      }
      is(State.waitRelease){transport.foreach{t=>when(t.io.done.fire){resetBatch()}}}
      is(State.drain){when(inputDone && memoryDrained && controlsDrained && !outputValid){phase:=Mux(leaseActive,State.release,State.finish)}}
      is(State.finish){
        result:=0.U.asTypeOf(result);result.identity:=draw.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status)
        result.inputQuads:=received;result.outputQuads:=exported;result.batches:=batches;result.localBatches:=localBatches
        result.globalBatches:=globalBatches;result.spilledBatches:=spilledBatches;result.shadedLanes:=shaded;result.exportedLanes:=live
        result.discardedLanes:=discarded;result.helperLanes:=helpers;result.coveredSamples:=samples
        result.stagingWriteBytes:=writeBytes;result.stagingReadBytes:=readBytes;result.maxLocalX:=maxLocalX
        result.faultSource:=faultSource;result.faultAddress:=faultAddress;result.faultQuadValid:=faultQuadValid
        result.faultSequence:=faultSequence;result.faultPrimitiveId:=faultPrimitive;result.faultInstanceId:=faultInstance
        result.faultPartIndex:=faultPart;result.faultLane:=faultLane;phase:=State.complete
      }
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && stop && phase=/=State.drain && phase=/=State.release && phase=/=State.waitRelease && phase=/=State.finish){phase:=State.drain}
  when(active && allocationFault){fail(2.U,3.U,draw.scratch.base)}
  when(active && releaseFault){fail(3.U,3.U,draw.scratch.base)}
  when(active && shaderFault){fail(5.U,4.U,Mux(shader.io.trap.valid,shader.io.trap.bits.tval,draw.shaderPc))}
  when(active && textureFault){fail(3.U,5.U,textureBridge.io.out.bits.addr)}
  when(active && dataFault){fail(3.U,3.U,memRequest.address)}
  when(active && externalCancel && status===0.U){status:=4.U;faultSource:=0.U;faultAddress:=0.U;faultQuadValid:=false.B}
}
