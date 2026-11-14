package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class NativeGeometryPrimitivePipelineTask(p: VertexShaderStageConfig,commandIdWidth: Int) extends Bundle {
  val input=new NativeGeometryPrimitiveRequest(p)
  val commandId=UInt(commandIdWidth.W)
  val firstSequence=UInt(64.W)
}

/** Explicit scalar-GS client of the physical shared CU pool. The caller owns
  * validated immutable GNG/code/GUR2 metadata and commandId through the held
  * result. Six-vertex input is captured before the first invocation launches;
  * output, uniforms, traps and physical task completion drain before return.
  * This component is not enabled by any native product constructor. It does
  * not implement the remaining texture/image/storage geometry resource ABI.
  */
class NativeGeometryPrimitivePipeline(config: GpuConfig,p: VertexShaderStageConfig,
    commandIdWidth: Int,numComputeUnits: Int,rawOutputs:Boolean=false) extends Module {
  require(numComputeUnits>0 && commandIdWidth>0)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new NativeGeometryPrimitivePipelineTask(p,commandIdWidth)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p))
    val completion=Decoupled(new GeometryPrimitivePipelineResult)
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val idle :: inputWait :: running :: report :: Nil=Enum(4)
  private val phase=RegInit(idle)
  private val saved=Reg(new NativeGeometryPrimitivePipelineTask(p,commandIdWidth))
  private val result=Reg(new GeometryPrimitivePipelineResult)
  private val sequence=Reg(UInt(64.W));private val cancelled=RegInit(false.B)
  private val outputFault=RegInit(false.B);private val mapperBusy=RegInit(false.B)
  private val workerRetired=RegInit(false.B)
  private val input=Module(new NativeGeometryInputMapper(p))
  private val scheduler=Module(new GeometrySharedCuScheduler(config,commandIdWidth,numComputeUnits))
  private val output=Module(new GeometryPrimitiveOutput(p,flatShading= !rawOutputs))
  private val uniform=Module(new ShaderUniformExecuteUnit(config,GeometryUniformResources.stage,geometryAvailable=true))
  private val active=phase=/=idle && phase=/=report
  private val stopping=cancelled || io.cancel || outputFault
  io.busy:=phase=/=idle
  input.io.in.valid:=phase===idle && io.task.valid && !io.cancel
  input.io.in.bits:=io.task.bits.input
  io.task.ready:=phase===idle && !io.cancel && input.io.in.ready
  when(io.task.fire){
    saved:=io.task.bits;result:=0.U.asTypeOf(result);cancelled:=false.B;outputFault:=false.B;mapperBusy:=false.B;workerRetired:=false.B
    sequence:=io.task.bits.firstSequence;result.nextSequence:=io.task.bits.firstSequence
    result.taskId:=io.task.bits.input.taskId;result.inputPrimitiveId:=io.task.bits.input.primitive.primitiveId
    phase:=inputWait
  }
  when(active && io.cancel){cancelled:=true.B}
  scheduler.io.task.valid:=phase===inputWait && input.io.task.valid && !stopping
  scheduler.io.task.bits:=0.U.asTypeOf(scheduler.io.task.bits)
  scheduler.io.task.bits.geometry:=input.io.task.bits.invocation
  scheduler.io.task.bits.commandId:=saved.commandId
  scheduler.io.task.bits.kernel.kernelPc:=saved.input.program.pc
  input.io.task.ready:=phase===inputWait && (stopping || scheduler.io.task.ready)
  input.io.fault.ready:=phase===inputWait
  when(phase===inputWait && input.io.fault.fire){
    result.status:=Mux(cancelled || io.cancel,GeometryInvocationStatus.cancelled,GeometryInvocationStatus.invalid);phase:=report
  }
  when(phase===inputWait && input.io.task.fire){
    when(stopping){result.status:=Mux(outputFault,GeometryInvocationStatus.workerFault,GeometryInvocationStatus.cancelled);phase:=report}
      .otherwise{phase:=running}
  }
  scheduler.io.cancel:=stopping
  // Once offered to the physical pool, a kernel plus geometry binding is an
  // indivisible owner even if cancellation arrives before pool acceptance.
  // The controller's worker is live after this enqueue and must still receive
  // its actual tagged completion; the queue may not withdraw the pool offer.
  private val launches=Module(new Queue(new Bundle {
    val kernel=chiselTypeOf(scheduler.io.launch.bits)
    val geometry=new GeometryInvocationLaunch
  },1))
  launches.io.enq.valid:=scheduler.io.launch.valid;launches.io.enq.bits.kernel:=scheduler.io.launch.bits
  launches.io.enq.bits.geometry:=scheduler.io.geometryTask.bits
  scheduler.io.launch.ready:=launches.io.enq.ready
  when(launches.io.enq.fire){assert(scheduler.io.geometryTask.valid)}
  io.shared.launch.valid:=launches.io.deq.valid;io.shared.launch.bits:=launches.io.deq.bits.kernel
  launches.io.deq.ready:=io.shared.launch.ready
  when(launches.io.deq.fire){workerRetired:=false.B}
  io.shared.geometryTask.get.valid:=launches.io.deq.valid;io.shared.geometryTask.get.bits:=launches.io.deq.bits.geometry
  scheduler.io.kernelCompletion<>io.shared.completion;scheduler.io.event<>io.shared.geometryEvent.get
  when(io.shared.completion.fire){workerRetired:=true.B
    when(!io.shared.completion.bits.success && !cancelled && !io.cancel){outputFault:=true.B}}
  scheduler.io.owner:=io.shared.owned;scheduler.io.taskIds:=io.shared.taskIds
  io.shared.geometryCancel.get:=scheduler.io.cancelCu
  // The decoder's captured native-uniform route requires both permissions.
  // Only the immutable scalar GS profile is admitted by the private code
  // validator; its other native texture/image/storage operations are rejected.
  io.shared.nativeResources:=saved.input.program.uniforms.version===GeometryUniformResources.version.U
  io.shared.uniformResources:=saved.input.program.uniforms.version===GeometryUniformResources.version.U
  io.shared.invalidate<>io.invalidate;io.invalidateDone<>io.shared.invalidateDone

  private val selected=VecInit((0 until numComputeUnits).map(i=>active && io.shared.owned(i) && io.shared.taskIds(i)===saved.commandId))
  assert(PopCount(selected)<=1.U,"one GS invocation can own only one physical CU")
  private val client=Reg(UInt(math.max(1,log2Ceil(numComputeUnits)).W))
  uniform.io.in.valid:=(0 until numComputeUnits).map(i=>selected(i) && io.shared.texture(i).scalar.valid).reduce(_ || _)
  uniform.io.in.bits:=Mux1H(selected,io.shared.texture.map(_.scalar.bits))
  uniform.io.vectorIn.valid:=(0 until numComputeUnits).map(i=>selected(i) && io.shared.texture(i).vector.valid).reduce(_ || _)
  uniform.io.vectorIn.bits:=Mux1H(selected,io.shared.texture.map(_.vector.bits))
  when(uniform.io.in.fire || uniform.io.vectorIn.fire){client:=PriorityEncoder(selected)}
  uniform.io.resources:=saved.input.program.uniforms;uniform.io.identity:=saved.input.program.identity
  // This scalar GS dialect has no writable staging or ordinary memory access.
  uniform.io.privateBase:=0.U;uniform.io.privateLimit:=0.U
  // An issued CU instruction must transfer before cancellation reaches the
  // service, just as held memory requests and launches must first transfer.
  uniform.io.cancel:=uniform.io.busy && stopping
  io.memoryRequest<>uniform.io.memoryRequest;uniform.io.memoryResponse<>io.memoryResponse
  private val matchingClient=VecInit((0 until numComputeUnits).map(i=>selected(i) && client===i.U))
  // A cancelled/faulted worker may retire its physical CU before a held
  // instruction result drains. Its real tagged completion authorizes local
  // discard; never send that old result to a later task using the same CU.
  private val discard=stopping && (workerRetired || io.shared.completion.fire) && !matchingClient.asUInt.orR
  uniform.io.commit.ready:=discard || Mux1H(matchingClient,io.shared.texture.map(_.scalarWriteback.ready))
  uniform.io.vectorCommit.ready:=discard || Mux1H(matchingClient,io.shared.texture.map(_.vectorWriteback.ready))
  for(i<-0 until numComputeUnits){val port=io.shared.texture(i)
    port.scalar.ready:=selected(i) && uniform.io.in.ready;port.vector.ready:=selected(i) && uniform.io.vectorIn.ready
    port.scalarWriteback.valid:=uniform.io.commit.valid && matchingClient(i);port.scalarWriteback.bits:=uniform.io.commit.bits
    port.vectorWriteback.valid:=uniform.io.vectorCommit.valid && matchingClient(i);port.vectorWriteback.bits:=uniform.io.vectorCommit.bits
  }
  when((uniform.io.commit.valid || uniform.io.vectorCommit.valid) && uniform.io.status=/=0.U &&
      uniform.io.status=/=ShaderUniformStatus.cancelled.U){outputFault:=true.B}
  io.shared.trap.ready:=active
  // The scalar texture backend terminates a cancelled uniform instruction
  // through its tagged load-fault path (writeRd=false). Drain that physical
  // trap without upgrading an already cancelled task; faults observed before
  // cancellation remain latched and retain priority in the held result.
  private val trapFault=io.shared.trap.fire && !cancelled && !io.cancel
  when(io.shared.trap.fire){assert(io.shared.trap.bits.taskId===saved.commandId)}
  when(trapFault){outputFault:=true.B}

  output.io.in.valid:=phase===running && scheduler.io.primitive.valid
  scheduler.io.primitive.ready:=phase===running && output.io.in.ready
  output.io.in.bits:=0.U.asTypeOf(output.io.in.bits)
  output.io.in.bits.geometry:=scheduler.io.primitive.bits
  private val source=output.io.in.bits.source;private val primitive=saved.input.primitive
  source.identity:=primitive.identity;source.primitiveId:=primitive.primitiveId
  source.instanceId:=primitive.instanceId;source.baseInstance:=primitive.baseInstance
  output.io.in.bits.taskId:=saved.input.taskId;output.io.in.bits.sequence:=sequence
  output.io.in.bits.outputComponents:=saved.input.program.outputComponents
  output.io.in.bits.builtinOutputMask:=saved.input.program.builtinOutputMask
  output.io.in.bits.varyingSlots:=saved.input.program.fragmentVaryingSlots
  output.io.in.bits.flatMask:=saved.input.program.flatMask
  for(i<-0 until 4+4*p.varyings)output.io.in.bits.componentSources(i):=saved.input.program.outputSources(i)
  io.primitive<>output.io.out;output.io.fault.ready:=phase===running
  when(output.io.in.fire){mapperBusy:=true.B
    when(sequence.andR){outputFault:=true.B}.otherwise{sequence:=sequence+1.U}}
  when(io.primitive.fire){mapperBusy:=false.B;result.producedPrimitives:=result.producedPrimitives+1.U}
  when(output.io.fault.fire){mapperBusy:=false.B;outputFault:=true.B}
  scheduler.io.completion.ready:=phase===running && !mapperBusy && !output.io.in.valid && !uniform.io.busy && !io.memoryRequest.valid && !launches.io.deq.valid
  when(scheduler.io.completion.fire){
    result.completedInvocations:=scheduler.io.completion.bits.completedInvocations;result.nextSequence:=sequence
    result.status:=Mux(outputFault || trapFault,GeometryInvocationStatus.workerFault,
      Mux(cancelled || io.cancel,GeometryInvocationStatus.cancelled,scheduler.io.completion.bits.status))
    phase:=report
  }
  io.completion.valid:=phase===report;io.completion.bits:=result
  when(io.completion.fire){assert(!uniform.io.busy);phase:=idle}
}
