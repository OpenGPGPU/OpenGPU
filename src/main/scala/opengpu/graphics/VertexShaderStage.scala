package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,L2SramArray}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

case class VertexShaderStageConfig(input: VertexInputFetchConfig=VertexInputFetchConfig(),varyings: Int=16) {
  require(varyings>=1 && varyings<=16)
  val outputWords: Int=4+4*varyings
  def prefixBytes(capacity: Int,attributes: Int,varyingSlots: Int): Int=
    (4*attributes+3+4+4*varyingSlots)*capacity*4
}
class VertexShaderSampler extends Bundle {
  val format=UInt(PixelFormat.width.W);val base=UInt(32.W)
  val texWidth=UInt(14.W);val texHeight=UInt(14.W);val clamp=Bool()
  val maxLevel=UInt(4.W);val minLevel=UInt(4.W);val bias=SInt(5.W)
}
/** Internal shader ABI version 1. With S=4*physical-CU-thread-capacity:
  * inputs are 4*attributeSlots SoA arrays, then vertexId/instanceId/baseInstance;
  * outputs are four FP32 clip-position arrays, then 4*varyingSlots raw arrays.
  * Uniforms begin after that prefix. Disabled varying components export zero.
  * The kernel retains x1/x8/x9's existing kernarg/local-base/capacity ABI.
  * This does not change the legacy eight-field vertex shader ABI.
  */
class VertexShaderDraw(p: VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W)
  val input=new VertexInputDraw(p.input)
  val shaderPc=UInt(32.W)
  val scratch=new VertexInputView
  val attributeSlots=UInt(5.W);val varyingSlots=UInt(5.W)
  val varyingMask=UInt((4*p.varyings).W)
  val sampler=new VertexShaderSampler
}
class ShadedVertexRecord(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity
  val ordinal=UInt(32.W);val vertexId=UInt(32.W)
  val instanceId=UInt(32.W);val baseInstance=UInt(32.W)
  val restart=Bool()
  val position=Vec(4,UInt(32.W))
  val varyings=Vec(p.varyings,Vec(4,UInt(32.W)))
}
object VertexShaderStatus {
  val shaderFault=5
}
class VertexShaderCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity
  val status=UInt(3.W)
  val faultAddress=UInt(32.W)
  val input=new VertexInputCompletion
  val vertices=UInt(64.W);val restarts=UInt(64.W);val batches=UInt(64.W)
  val localBatches=UInt(64.W);val spilledBatches=UInt(64.W)
  val stagingWriteBytes=UInt(64.W);val stagingReadBytes=UInt(64.W)
  val maxLocalX=UInt(16.W)
}

/** Ordered generic vertex execution on an enclosing shared CU pool. A batch's
  * scratch/payload reservation survives every exported vertex. Restart tokens
  * flush older work and remain in stream order. Metadata and a transposed
  * sixteen-vertex readback window use synchronous single-port SRAMs; input
  * records stay in the fetcher's private slot until their scatter writes ack.
  * Full-interface/full-capacity payloads may exceed 64 KiB and spill explicitly.
  */
class VertexShaderStage(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0) extends Module {
  require(config.xLen==32 && config.lanes>=4 && config.lanes%4==0)
  private val capacity=config.lanes*config.warps
  require(capacity<=255)
  private val stride=capacity*4
  private val countWidth=log2Ceil(capacity+1)
  private val metaDepth=1<<log2Ceil(capacity)
  private val cacheDepth=1<<log2Ceil(p.outputWords)
  private val cacheAddressWidth=log2Ceil(cacheDepth)
  private val metaAddressWidth=log2Ceil(metaDepth)
  private val sharedConfig=shared
  private val payloadConfig=payload
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexShaderDraw(p)))
    val out=Decoupled(new ShadedVertexRecord(p))
    val completion=Decoupled(new VertexShaderCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val input=Module(new VertexInputFetcher(config,p.input))
  private val shader=Module(new KernelShaderStage(config,true,Some(shared.copy(shaderPayload=None))))
  private val texture=Module(new TexSampleUnit(config))
  private val textureBridge=Module(new OmWordToLinePort(config,maxOutstanding=2))
  private val transport=payload.map(q=>Module(new FragmentPayloadTransport(config,q,payloadOwner,1)))
  private val metadata=Module(new L2SramArray(metaDepth,128,useSramBlackBoxes))
  private val cache=Module(new L2SramArray(cacheDepth,512,useSramBlackBoxes))
  io.shared<>shader.io.shared.get
  shader.io.instructionSatp:=0.U;shader.io.vectorSatp:=0.U
  shader.io.instructionTlbFlush:=0.U.asTypeOf(shader.io.instructionTlbFlush)
  shader.io.vectorTlbFlush:=0.U.asTypeOf(shader.io.vectorTlbFlush)
  shader.io.memoryRequest.ready:=true.B;shader.io.memoryResponse.valid:=false.B
  shader.io.memoryResponse.bits:=0.U.asTypeOf(shader.io.memoryResponse.bits)
  shader.io.globalAtomicRequest.ready:=true.B;shader.io.globalAtomicResponse.valid:=false.B
  shader.io.globalAtomicResponse.bits:=0.U.asTypeOf(shader.io.globalAtomicResponse.bits)
  shader.io.simtBranch.valid:=false.B;shader.io.simtBranch.bits:=0.U.asTypeOf(shader.io.simtBranch.bits)

  private val states=Enum(27)
  private val Seq(idle,validate,collect,allocate,writeInput,waitInput,commitInput,clearOutput,waitClear,
    invalidate,waitLaunch,waitKernel,cacheSlice,waitCacheFirst,cacheSecond,waitCacheSecond,
    metaRead,metaReply,gather,gatherReply)=states.take(20)
  private val Seq(emitVertex,emitRestart,release,waitRelease,drain,finish,complete)=states.drop(20)
  private val phase=RegInit(idle)
  private val draw=Reg(new VertexShaderDraw(p))
  private val result=Reg(new VertexShaderCompletion)
  private val record=Reg(new ShadedVertexRecord(p))
  private val inputResult=Reg(new VertexInputCompletion)
  private val status=RegInit(0.U(3.W));private val faultAddress=Reg(UInt(32.W))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val inputOffered=RegInit(false.B);private val inputStarted=RegInit(false.B);private val inputDone=RegInit(false.B)
  private val allocationOffered=RegInit(false.B);private val allocationStarted=RegInit(false.B)
  private val allocationPrepared=RegInit(false.B);private val resident=RegInit(false.B)
  private val leaseActive=RegInit(false.B);private val finishOffered=RegInit(false.B);private val finishStarted=RegInit(false.B)
  private val invalidateOffered=RegInit(false.B);private val invalidatePending=RegInit(false.B)
  private val launchOffered=RegInit(false.B);private val kernelActive=RegInit(false.B);private val kernelDone=RegInit(false.B)
  private val outputValid=RegInit(false.B)
  private val inputWords=Reg(UInt(7.W));private val outputWords=Reg(UInt(7.W))
  private val inputBytes=Reg(UInt(32.W));private val prefixBytes=Reg(UInt(32.W))
  private val payloadContext=Reg(UInt(32.W))
  private val count=RegInit(0.U(countWidth.W));private val field=RegInit(0.U(7.W))
  private val clearLine=Reg(UInt(32.W))
  private val chunk=RegInit(0.U(countWidth.W));private val emitIndex=RegInit(0.U(countWidth.W))
  private val slice=RegInit(0.U(cacheAddressWidth.W))
  private val firstPart=Reg(UInt(512.W));private val firstShift=Reg(UInt(9.W))
  private val secondBytes=Reg(UInt(7.W));private val secondLine=Reg(UInt(32.W))
  private val memOffered=RegInit(false.B);private val memPending=RegInit(false.B)
  private val memRequest=Reg(new ComputeMemoryRequest(config,64,1))
  private val vertices=RegInit(0.U(64.W));private val restarts=RegInit(0.U(64.W));private val batches=RegInit(0.U(64.W))
  private val localBatches=RegInit(0.U(64.W));private val spilledBatches=RegInit(0.U(64.W))
  private val writeBytes=RegInit(0.U(64.W));private val readBytes=RegInit(0.U(64.W))
  private val maxLocalX=RegInit(0.U(16.W))
  private val active=phase=/=idle && phase=/=complete
  private val stageRequest=Wire(Decoupled(new ComputeMemoryRequest(config,64,1)))
  private val stageResponse=Wire(Decoupled(new ComputeMemoryResponse(64,1)))
  private val fallbackRequest=Wire(Decoupled(new ComputeMemoryRequest(config,64,1)))
  private val fallbackResponse=Wire(Decoupled(new ComputeMemoryResponse(64,1)))
  stageRequest.valid:=memOffered;stageRequest.bits:=memRequest
  stageResponse.ready:=memPending
  when(stageRequest.fire){memOffered:=false.B;memPending:=true.B
    when(memRequest.isWrite){writeBytes:=writeBytes+PopCount(memRequest.byteMask)}
      .otherwise{readBytes:=readBytes+PopCount(memRequest.byteMask)}
  }
  when(stageResponse.fire){memPending:=false.B}
  private val allocationFault=WireDefault(false.B);private val releaseFault=WireDefault(false.B)
  transport match {
    case Some(t)=>
      t.io.stageRequest<>stageRequest;stageResponse<>t.io.stageResponse
      fallbackRequest<>t.io.memoryRequest;t.io.memoryResponse<>fallbackResponse
      val port=io.payload.get
      port.control<>t.io.pool.control;port.controlResponse<>t.io.pool.controlResponse
      port.request<>t.io.pool.request;port.response<>t.io.pool.response;port.task:=t.io.pool.task
      t.io.pool.context:=payloadContext
      t.io.begin.valid:=allocationOffered;t.io.begin.bits.base:=draw.scratch.base
      t.io.begin.bits.bytes:=prefixBytes;t.io.begin.bits.context:=payloadContext
      when(t.io.begin.fire){allocationOffered:=false.B;allocationStarted:=true.B}
      t.io.prepared.ready:=allocationStarted && !allocationPrepared
      allocationFault:=t.io.prepared.valid && t.io.prepared.bits.fault
      when(t.io.prepared.fire){allocationPrepared:=true.B;resident:=t.io.prepared.bits.resident;leaseActive:=true.B}
      t.io.finish.valid:=finishOffered;t.io.finish.bits:=status=/=0.U
      when(t.io.finish.fire){finishOffered:=false.B;finishStarted:=true.B}
      t.io.done.ready:=finishStarted
      releaseFault:=t.io.done.valid && t.io.done.bits
    case None=>
      fallbackRequest<>stageRequest;stageResponse<>fallbackResponse
  }

  private val memoryArbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,4),3))
  private val memoryQueue=Module(new Queue(new ComputeMemoryRequest(config,64,4),1))
  private val sources=Seq(input.io.memoryRequest,fallbackRequest,textureBridge.io.memoryRequest)
  for(i<-0 until 3) {
    memoryArbiter.io.in(i).valid:=sources(i).valid;memoryArbiter.io.in(i).bits:=sources(i).bits
    memoryArbiter.io.in(i).bits.transactionId:=(if(i==2)2.U+sources(i).bits.transactionId else i.U)
    sources(i).ready:=memoryArbiter.io.in(i).ready
  }
  memoryQueue.io.enq<>memoryArbiter.io.out;io.memoryRequest<>memoryQueue.io.deq
  private val tag=io.memoryResponse.bits.transactionId
  input.io.memoryResponse.valid:=io.memoryResponse.valid && tag===0.U
  input.io.memoryResponse.bits:=io.memoryResponse.bits;input.io.memoryResponse.bits.transactionId:=0.U
  fallbackResponse.valid:=io.memoryResponse.valid && tag===1.U
  fallbackResponse.bits:=io.memoryResponse.bits;fallbackResponse.bits.transactionId:=0.U
  textureBridge.io.memoryResponse.valid:=io.memoryResponse.valid && tag>=2.U
  textureBridge.io.memoryResponse.bits:=io.memoryResponse.bits;textureBridge.io.memoryResponse.bits.transactionId:=tag-2.U
  io.memoryResponse.ready:=Mux(tag===0.U,input.io.memoryResponse.ready,Mux(tag===1.U,fallbackResponse.ready,textureBridge.io.memoryResponse.ready))

  private val texturePending=RegInit(0.U(3.W));private val textureInstructions=RegInit(0.U(3.W))
  private val textureRequestFire=textureBridge.io.memoryRequest.fire
  private val textureResponseFire=textureBridge.io.memoryResponse.fire
  texturePending:=texturePending+textureRequestFire.asUInt-textureResponseFire.asUInt
  texture.io.in<>shader.io.texSample;texture.io.vectorIn<>shader.io.vectorTexSample
  private val dropTexture=shader.io.completion.valid && (status=/=0.U || externalCancel || !shader.io.completion.bits.success)
  shader.io.texWriteback.valid:=texture.io.commit.valid && !dropTexture;shader.io.texWriteback.bits:=texture.io.commit.bits
  shader.io.vectorTexWriteback.valid:=texture.io.vectorCommit.valid && !dropTexture;shader.io.vectorTexWriteback.bits:=texture.io.vectorCommit.bits
  texture.io.commit.ready:=dropTexture || shader.io.texWriteback.ready
  texture.io.vectorCommit.ready:=dropTexture || shader.io.vectorTexWriteback.ready
  private val textureStarts=PopCount(Seq(texture.io.in.fire,texture.io.vectorIn.fire))
  private val textureEnds=PopCount(Seq(texture.io.commit.fire,texture.io.vectorCommit.fire))
  textureInstructions:=textureInstructions+textureStarts-textureEnds
  assert(textureEnds<=textureInstructions+textureStarts,"vertex texture completion must retain its instruction")
  textureBridge.io.in<>texture.io.mem.req;texture.io.mem.resp<>textureBridge.io.out
  texture.io.texFormat:=draw.sampler.format;texture.io.texBase:=draw.sampler.base
  texture.io.texWidth:=draw.sampler.texWidth;texture.io.texHeight:=draw.sampler.texHeight
  texture.io.wrapClamp:=draw.sampler.clamp;texture.io.texMaxLevel:=draw.sampler.maxLevel
  texture.io.minLevel:=draw.sampler.minLevel;texture.io.lodBias:=draw.sampler.bias
  private val textureDrained=texturePending===0.U && textureInstructions===0.U &&
    !textureBridge.io.memoryRequest.valid && !shader.io.texSample.valid && !shader.io.vectorTexSample.valid
  shader.io.trap.ready:=true.B
  shader.io.completion.ready:=kernelActive && textureDrained
  private val shaderFault=shader.io.trap.valid || (shader.io.completion.valid && !shader.io.completion.bits.success)
  private val inputFault=input.io.completion.valid && input.io.completion.bits.status=/=0.U
  private val dataFault=stageResponse.valid && stageResponse.bits.fault
  private val textureFault=textureBridge.io.responseFault
  private val stop=status=/=0.U || externalCancel || inputFault || dataFault || textureFault || shaderFault || allocationFault || releaseFault
  transport.foreach(_.io.pool.cancel:=stop)
  input.io.cancel:=stop && inputStarted
  input.io.draw.valid:=inputOffered;input.io.draw.bits:=draw.input
  when(input.io.draw.fire){inputOffered:=false.B;inputStarted:=true.B}
  input.io.completion.ready:=inputStarted && !inputDone
  when(input.io.completion.fire){inputDone:=true.B;inputResult:=input.io.completion.bits}
  input.io.out.ready:=stop && inputStarted
  shader.io.launch.valid:=launchOffered;shader.io.launch.kernelPc:=draw.shaderPc
  shader.io.launch.kernargAddress:=draw.scratch.base;shader.io.launch.localX:=count
  shader.io.launch.localY:=1.U;shader.io.launch.localZ:=1.U
  shader.io.launch.gridX:=1.U;shader.io.launch.gridY:=1.U;shader.io.launch.gridZ:=1.U
  when(shader.io.launch.valid && shader.io.launch.ready) {
    launchOffered:=false.B;kernelActive:=true.B;kernelDone:=false.B;batches:=batches+1.U
    when(resident){localBatches:=localBatches+1.U}
    if(payload.nonEmpty)when(!resident){spilledBatches:=spilledBatches+1.U}
    when(count>maxLocalX){maxLocalX:=count}
  }
  when(shader.io.completion.fire){kernelActive:=false.B;kernelDone:=true.B}
  shader.io.l1Invalidate.valid:=invalidateOffered
  shader.io.l1Invalidate.bits.lineAddress:=opengpu.core.memory.CacheLineInvalidate.allLinesAddress(config)
  when(shader.io.l1Invalidate.fire){invalidateOffered:=false.B;invalidatePending:=true.B}
  shader.io.l1InvalidateDone.ready:=invalidatePending
  when(shader.io.l1InvalidateDone.fire){
    invalidatePending:=false.B
    assert(shader.io.l1InvalidateDone.bits.lineAddress===opengpu.core.memory.CacheLineInvalidate.allLinesAddress(config))
  }

  io.draw.ready:=phase===idle && !externalCancel;io.busy:=phase=/=idle
  io.out.valid:=outputValid;io.out.bits:=record
  io.completion.valid:=phase===complete;io.completion.bits:=result
  when(io.out.fire){outputValid:=false.B;when(record.restart){restarts:=restarts+1.U}.otherwise{vertices:=vertices+1.U}}
  private def fail(code: UInt,address: UInt): Unit={when(status===0.U && !externalCancel){status:=code;faultAddress:=address}}
  private def overlap(a: UInt,end: UInt,b: VertexInputView): Bool=a<b.limit && end>b.base
  private def mask(offset: UInt,bytes: UInt): UInt=((~0.U(64.W))>>(64.U(7.W)-bytes))<<offset
  private def issue(address: UInt,write: Boolean,data: UInt,byteMask: UInt): Unit={
    memRequest:=0.U.asTypeOf(memRequest);memRequest.address:=address
    memRequest.isWrite:=write.B;memRequest.writeData:=data;memRequest.byteMask:=byteMask
    memRequest.sizeLog2:=6.U;memRequest.cachePolicy:=CachePolicy.uncached;memOffered:=true.B
  }
  private val outputStart=draw.scratch.base+inputBytes
  private val prefixEnd=draw.scratch.base+&prefixBytes
  private val clearFirst=Cat(outputStart(31,6),0.U(6.W))
  private val clearLast=Cat((prefixEnd-1.U)(31,6),0.U(6.W))
  private val clearLow=(~0.U(64.W))<<outputStart(5,0)
  private val clearHigh=Mux(prefixEnd(5,0)===0.U,~0.U(64.W),(~0.U(64.W))>>(64.U-prefixEnd(5,0)))
  private val clearMask=Mux(clearLine===clearFirst,clearLow,~0.U(64.W)) & Mux(clearLine===clearLast,clearHigh,~0.U(64.W))
  private val inputAddress=draw.scratch.base+field*stride.U+(count<<2)
  private val inputData=WireDefault(0.U(32.W))
  for(a<-0 until p.input.attributes;c<-0 until 4)when(field===(a*4+c).U){inputData:=input.io.out.bits.values(a)(c)}
  when(field===(inputWords-3.U)){inputData:=input.io.out.bits.vertexId}
  when(field===(inputWords-2.U)){inputData:=input.io.out.bits.instanceId}
  when(field===(inputWords-1.U)){inputData:=input.io.out.bits.baseInstance}
  private val requested=slice<4.U || ((draw.varyingMask>>(slice-4.U))(0) && slice<outputWords)
  private val chunkCount=Mux(count-chunk>16.U,16.U,count-chunk)
  private val chunkBytes=chunkCount<<2
  private val cacheAddress=outputStart+slice*stride.U+(chunk<<2)
  private val cacheLine=Cat(cacheAddress(31,6),0.U(6.W))
  private val firstBytes=Mux(chunkBytes>64.U-cacheAddress(5,0),64.U-cacheAddress(5,0),chunkBytes)
  private val inputIdentity=input.io.out.bits
  metadata.io.writeEnable:=false.B;metadata.io.writeAddress:=count(metaAddressWidth-1,0)
  metadata.io.writeData:=Cat(inputIdentity.baseInstance,inputIdentity.instanceId,inputIdentity.vertexId,inputIdentity.ordinal)
  metadata.io.readEnable:=phase===metaRead && !stop;metadata.io.readAddress:=emitIndex(metaAddressWidth-1,0)
  cache.io.writeEnable:=false.B;cache.io.writeAddress:=slice;cache.io.writeData:=0.U
  cache.io.readEnable:=phase===gather && requested && !stop;cache.io.readAddress:=slice
  private def nextSlice(): Unit={
    when(slice===outputWords-1.U){emitIndex:=chunk;phase:=metaRead}
      .otherwise{slice:=slice+1.U;phase:=cacheSlice}
  }
  private def recordMetadata(value: UInt): Unit={
    record:=0.U.asTypeOf(record);record.identity:=draw.input.identity
    record.ordinal:=value(31,0);record.vertexId:=value(63,32)
    record.instanceId:=value(95,64);record.baseInstance:=value(127,96)
  }
  private val memoryDrained= !memOffered && !memPending && !memoryQueue.io.deq.valid
  private val controlsDrained= !inputOffered && (!inputStarted || inputDone) &&
    !allocationOffered && (!allocationStarted || allocationPrepared) &&
    !invalidateOffered && !invalidatePending && !launchOffered && !kernelActive && textureDrained

  when(!stop || phase===idle || phase===drain || phase===release || phase===waitRelease || phase===finish || phase===complete) {
  switch(phase) {
    is(idle){when(io.draw.fire){
      draw:=io.draw.bits;status:=0.U;faultAddress:=0.U;count:=0.U;outputValid:=false.B
      inputStarted:=false.B;inputDone:=false.B;inputResult:=0.U.asTypeOf(inputResult);inputResult.identity:=io.draw.bits.input.identity
      vertices:=0.U;restarts:=0.U;batches:=0.U;localBatches:=0.U;spilledBatches:=0.U;writeBytes:=0.U;readBytes:=0.U;maxLocalX:=0.U
      val in=(io.draw.bits.attributeSlots<<2)+3.U;val out=(io.draw.bits.varyingSlots<<2)+4.U
      inputWords:=in;outputWords:=out;inputBytes:=in*stride.U;prefixBytes:=(in+&out)*stride.U
      payloadContext:=io.payload.map(_.context).getOrElse(0.U);phase:=validate
    }}
    is(validate){
      val work=draw.input.count=/=0.U && draw.input.instances=/=0.U
      val s=draw.scratch
      val view=s.allocationBase(5,0)===0.U && s.allocationLimit(5,0)===0.U && s.allocationLimit<=(BigInt(1)<<32).U &&
        s.allocationLimit>s.allocationBase && s.base>=s.allocationBase && s.base(5,0)===0.U && s.limit>=s.base && s.limit<=s.allocationLimit
      val fields=draw.attributeSlots<=p.input.attributes.U && draw.varyingSlots<=p.varyings.U &&
        !(draw.varyingMask>>(draw.varyingSlots<<2)).orR &&
        draw.input.attributes.zipWithIndex.map { case (a,i)=> !a.enabled || i.U<draw.attributeSlots }.reduce(_ && _)
      val aliases=(0 until p.input.bindings).map { i=>
        val used=draw.input.attributes.map(a=>a.enabled && a.bindingIndex===i.U).reduce(_ || _)
        used && overlap(s.base,prefixEnd,draw.input.bindings(i))
      }.reduce(_ || _) || (draw.input.indexFormat=/=0.U && overlap(s.base,prefixEnd,draw.input.indices))
      val sharedAlias=s.base<(config.sharedMemoryBase+config.sharedMemoryBytes).U && prefixEnd>config.sharedMemoryBase.U
      when(draw.version=/=1.U || !fields || (work && (!view || draw.shaderPc===0.U || draw.shaderPc(1,0)=/=0.U || aliases || sharedAlias))) {
        fail(VertexInputStatus.invalidDescriptor.U,s.base);phase:=drain
      }.elsewhen(work && (prefixEnd>s.limit || prefixEnd>(BigInt(1)<<32).U || ((prefixEnd+63.U)&(~63.U(34.W)))>s.allocationLimit)) {
        fail(VertexInputStatus.bounds.U,s.base);phase:=drain
      }.otherwise{inputOffered:=true.B;phase:=collect}
    }
    is(collect){
      when(count===capacity.U || (inputDone && count=/=0.U) || (input.io.out.valid && input.io.out.bits.restart && count=/=0.U)) {
        clearLine:=clearFirst;phase:=Mux(resident,invalidate,clearOutput)
      }.elsewhen(input.io.out.valid && input.io.out.bits.restart) {
        recordMetadata(Cat(inputIdentity.baseInstance,inputIdentity.instanceId,inputIdentity.vertexId,inputIdentity.ordinal))
        record.restart:=true.B;input.io.out.ready:=true.B;outputValid:=true.B;phase:=emitRestart
      }.elsewhen(input.io.out.valid) {
        when(!leaseActive) {
          if(payload.nonEmpty){allocationOffered:=true.B;phase:=allocate}
          else{leaseActive:=true.B;resident:=false.B;field:=0.U;phase:=writeInput}
        }.otherwise{field:=0.U;phase:=writeInput}
      }.elsewhen(inputDone){phase:=finish}
    }
    is(allocate){when(allocationPrepared){field:=0.U;phase:=writeInput}}
    is(writeInput){issue(Cat(inputAddress(31,6),0.U(6.W)),true,inputData.pad(512)<<(inputAddress(5,0)<<3),15.U(64.W)<<inputAddress(5,0));phase:=waitInput}
    is(waitInput){when(stageResponse.fire){when(field===inputWords-1.U){phase:=commitInput}.otherwise{field:=field+1.U;phase:=writeInput}}}
    is(commitInput){
      assert(input.io.out.valid && !input.io.out.bits.restart)
      assert(inputIdentity.identity.asUInt===draw.input.identity.asUInt)
      metadata.io.writeEnable:=true.B;input.io.out.ready:=true.B;count:=count+1.U;phase:=collect
    }
    is(clearOutput){issue(clearLine,true,0.U,clearMask);phase:=waitClear}
    is(waitClear){when(stageResponse.fire){when(clearLine===clearLast){phase:=invalidate}.otherwise{clearLine:=clearLine+64.U;phase:=clearOutput}}}
    is(invalidate){kernelDone:=false.B;invalidateOffered:=true.B;phase:=waitLaunch}
    is(waitLaunch){when(!invalidateOffered && !invalidatePending){launchOffered:=true.B;phase:=waitKernel}}
    is(waitKernel){when(kernelDone){chunk:=0.U;slice:=0.U;phase:=cacheSlice}}
    is(cacheSlice){
      when(!requested){nextSlice()}.otherwise {
        firstShift:=cacheAddress(5,0)<<3;secondBytes:=chunkBytes-firstBytes;secondLine:=cacheLine+64.U
        issue(cacheLine,false,0.U,mask(cacheAddress(5,0),firstBytes));phase:=waitCacheFirst
      }
    }
    is(waitCacheFirst){when(stageResponse.fire){
      val data=stageResponse.bits.readData>>firstShift
      when(secondBytes=/=0.U){firstPart:=data;phase:=cacheSecond}
        .otherwise{cache.io.writeEnable:=true.B;cache.io.writeData:=data;nextSlice()}
    }}
    is(cacheSecond){issue(secondLine,false,0.U,mask(0.U,secondBytes));phase:=waitCacheSecond}
    is(waitCacheSecond){when(stageResponse.fire){
      cache.io.writeEnable:=true.B;cache.io.writeData:=firstPart|(stageResponse.bits.readData<<(512.U-firstShift));nextSlice()
    }}
    is(metaRead){phase:=metaReply}
    is(metaReply){recordMetadata(metadata.io.readData);slice:=0.U;phase:=gather}
    is(gather){when(!requested){
      when(slice===outputWords-1.U){outputValid:=true.B;phase:=emitVertex}.otherwise{slice:=slice+1.U}
    }.otherwise{phase:=gatherReply}}
    is(gatherReply){
      val word=(cache.io.readData>>(emitIndex.pad(4)(3,0)<<5))(31,0)
      when(slice<4.U){record.position(slice(1,0)):=word}
      for(v<-0 until p.varyings;c<-0 until 4)when(slice===(4+4*v+c).U){record.varyings(v)(c):=word}
      when(slice===outputWords-1.U){outputValid:=true.B;phase:=emitVertex}.otherwise{slice:=slice+1.U;phase:=gather}
    }
    is(emitVertex){when(io.out.fire){
      when(emitIndex===count-1.U){phase:=release}
        .elsewhen(emitIndex===chunk+chunkCount-1.U){chunk:=chunk+chunkCount;slice:=0.U;phase:=cacheSlice}
        .otherwise{emitIndex:=emitIndex+1.U;phase:=metaRead}
    }}
    is(emitRestart){when(io.out.fire){phase:=collect}}
    is(release){
      if(payload.nonEmpty){finishOffered:=true.B;phase:=waitRelease}
      else{leaseActive:=false.B;count:=0.U;phase:=Mux(stop,drain,collect)}
    }
    is(waitRelease){transport.foreach { t=>when(t.io.done.fire){
      leaseActive:=false.B;allocationStarted:=false.B;allocationPrepared:=false.B;finishStarted:=false.B;resident:=false.B;count:=0.U
      phase:=Mux(stop,drain,collect)
    }}}
    is(drain){when(memoryDrained && controlsDrained && !outputValid){phase:=Mux(leaseActive,release,finish)}}
    is(finish){
      result.identity:=draw.input.identity;result.status:=Mux(externalCancel && status===0.U,VertexInputStatus.cancelled.U,status)
      result.faultAddress:=faultAddress;result.input:=inputResult;result.vertices:=vertices;result.restarts:=restarts
      result.batches:=batches;result.localBatches:=localBatches;result.spilledBatches:=spilledBatches
      result.stagingWriteBytes:=writeBytes;result.stagingReadBytes:=readBytes;result.maxLocalX:=maxLocalX;phase:=complete
    }
    is(complete){when(io.completion.fire){phase:=idle}}
  }
  }
  when(active && stop && phase=/=drain && phase=/=release && phase=/=waitRelease && phase=/=finish){phase:=drain}
  when(active && allocationFault){fail(VertexInputStatus.bounds.U,draw.scratch.base)}
  when(active && releaseFault){fail(VertexInputStatus.memoryFault.U,draw.scratch.base)}
  when(active && shaderFault){fail(VertexShaderStatus.shaderFault.U,Mux(shader.io.trap.valid,shader.io.trap.bits.tval,draw.shaderPc))}
  when(active && textureFault){fail(VertexInputStatus.memoryFault.U,textureBridge.io.out.bits.addr)}
  when(active && dataFault){fail(VertexInputStatus.memoryFault.U,memRequest.address)}
  when(active && inputFault){fail(input.io.completion.bits.status,input.io.completion.bits.faultAddress)}
  when(active && externalCancel && status===0.U){status:=VertexInputStatus.cancelled.U;faultAddress:=0.U}
}
