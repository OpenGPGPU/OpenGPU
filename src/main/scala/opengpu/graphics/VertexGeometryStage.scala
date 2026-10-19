package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexGeometryDraw(p: VertexShaderStageConfig) extends Bundle {
  val vertex=new VertexShaderDraw(p)
  val topology=UInt(4.W);val flatMask=UInt((4*p.varyings).W)
}

/** Generic GPU input/shader/assembly stream. Admission snapshots both stages;
  * an offered child draw is retained until accepted, then cancellation may
  * reach that child. Completion retains host ownership through primitive drain.
  * Rasterization, clipping and native host descriptors are separate interfaces.
  */
class VertexGeometryStage(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexGeometryDraw(p)))
    val out=Decoupled(new AssembledPrimitive(p))
    val completion=Decoupled(new PrimitiveAssemblyCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val vertex=Module(new VertexShaderStage(config,p,shared,useSramBlackBoxes,payload,payloadOwner))
  private val assembly=Module(new PrimitiveAssemblyStage(p))
  private val descriptor=Reg(new VertexGeometryDraw(p));private val active=RegInit(false.B)
  private val vertexOffered=RegInit(false.B);private val assemblyOffered=RegInit(false.B)
  private val vertexStarted=RegInit(false.B);private val assemblyStarted=RegInit(false.B)
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  io.draw.ready:= !active && !externalCancel;io.busy:=active
  when(io.draw.fire) {
    descriptor:=io.draw.bits;active:=true.B;vertexOffered:=true.B;assemblyOffered:=true.B
    vertexStarted:=false.B;assemblyStarted:=false.B
  }
  vertex.io.draw.valid:=vertexOffered;vertex.io.draw.bits:=descriptor.vertex
  when(vertex.io.draw.fire){vertexOffered:=false.B;vertexStarted:=true.B}
  assembly.io.draw.valid:=assemblyOffered;assembly.io.draw.bits.version:=1.U
  assembly.io.draw.bits.identity:=descriptor.vertex.input.identity
  assembly.io.draw.bits.count:=descriptor.vertex.input.count;assembly.io.draw.bits.instances:=descriptor.vertex.input.instances
  assembly.io.draw.bits.baseInstance:=descriptor.vertex.input.baseInstance
  assembly.io.draw.bits.varyingSlots:=descriptor.vertex.varyingSlots
  assembly.io.draw.bits.topology:=descriptor.topology;assembly.io.draw.bits.flatMask:=descriptor.flatMask
  when(assembly.io.draw.fire){assemblyOffered:=false.B;assemblyStarted:=true.B}
  vertex.io.cancel:=vertexStarted && (externalCancel || assembly.io.cancelSource)
  assembly.io.cancel:=assemblyStarted && externalCancel
  assembly.io.in<>vertex.io.out;assembly.io.end<>vertex.io.completion
  io.out<>assembly.io.out;io.completion<>assembly.io.completion
  io.shared<>vertex.io.shared;io.memoryRequest<>vertex.io.memoryRequest;vertex.io.memoryResponse<>io.memoryResponse
  payload.foreach { _=>
    io.payload.get<>vertex.io.payload.get
    vertex.io.payload.get.cancel:=vertexStarted && io.payload.get.cancel
  }
  when(io.completion.fire){assert(vertexStarted && assemblyStarted);active:=false.B}
}
