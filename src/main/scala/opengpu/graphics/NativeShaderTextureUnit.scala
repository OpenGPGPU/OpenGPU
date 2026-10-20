package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** One native task's legacy sampling and typed component-fetch execution.
  * One instruction owns this endpoint through retirement, so the existing
  * two external line slots can be reused without widening enclosing fabrics.
  */
class NativeShaderTextureUnit(config: GpuConfig=GpuConfig(),stage: Int=0) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)));val vectorIn=Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit=Decoupled(new ScalarCommitRequest(config));val vectorCommit=Decoupled(new VectorCommitRequest(config))
    val resources=Input(new ShaderTextureResources);val identity=Input(new GraphicsWorkIdentity)
    val texFormat=Input(UInt(PixelFormat.width.W));val texBase=Input(UInt(32.W));val texWidth=Input(UInt(14.W));val texHeight=Input(UInt(14.W))
    val wrapClamp=Input(Bool());val coordinatePolicy=Input(new TextureCoordinatePolicy)
    val texMaxLevel=Input(UInt(4.W));val lodBias=Input(SInt(5.W));val minLevel=Input(UInt(4.W))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val coordinateFault=Output(Bool());val responseFault=Output(Bool());val faultAddress=Output(UInt(32.W))
  })
  private object Owner extends ChiselEnum {val idle,legacy,typed=Value}
  private val owner=RegInit(Owner.idle);private val preferVector=RegInit(true.B)
  private val legacy=Module(new TexSampleUnit(config,coordinatePolicyInput=true));private val bridge=Module(new OmWordToLinePort(config,maxOutstanding=2))
  private val typed=Module(new TexelFetchExecuteUnit(config,stage));private val credits=Module(new TextureLineCreditAdapter(config))
  legacy.io.mem.req<>bridge.io.in;legacy.io.mem.resp<>bridge.io.out
  credits.io.in<>typed.io.memoryRequest;typed.io.memoryResponse<>credits.io.out
  legacy.io.texFormat:=io.texFormat;legacy.io.texBase:=io.texBase;legacy.io.texWidth:=io.texWidth;legacy.io.texHeight:=io.texHeight
  legacy.io.wrapClamp:=io.wrapClamp;legacy.io.coordinatePolicy.get:=io.coordinatePolicy
  legacy.io.texMaxLevel:=io.texMaxLevel;legacy.io.lodBias:=io.lodBias;legacy.io.minLevel:=io.minLevel
  typed.io.resources:=io.resources;typed.io.identity:=io.identity;typed.io.invalidate:=io.invalidate
  // An already-issued instruction must be accepted before it can be cancelled.
  typed.io.cancel:=owner===Owner.typed && io.cancel
  private val chooseVector=io.vectorIn.valid && (!io.in.valid || preferVector)
  private val scalarTyped=ShaderTextureFetchOpcode.scalar(io.in.bits.decode.instruction)
  private val vectorTyped=ShaderTextureFetchOpcode.vector(io.vectorIn.bits.issued.decode.instruction)
  legacy.io.in.valid:=owner===Owner.idle && !chooseVector && io.in.valid && !scalarTyped;legacy.io.in.bits:=io.in.bits
  legacy.io.vectorIn.valid:=owner===Owner.idle && chooseVector && io.vectorIn.valid && !vectorTyped;legacy.io.vectorIn.bits:=io.vectorIn.bits
  typed.io.in.valid:=owner===Owner.idle && !chooseVector && io.in.valid && scalarTyped;typed.io.in.bits:=io.in.bits
  typed.io.vectorIn.valid:=owner===Owner.idle && chooseVector && io.vectorIn.valid && vectorTyped;typed.io.vectorIn.bits:=io.vectorIn.bits
  io.in.ready:=owner===Owner.idle && !chooseVector && Mux(scalarTyped,typed.io.in.ready,legacy.io.in.ready)
  io.vectorIn.ready:=owner===Owner.idle && chooseVector && Mux(vectorTyped,typed.io.vectorIn.ready,legacy.io.vectorIn.ready)
  when(io.in.fire){owner:=Mux(scalarTyped,Owner.typed,Owner.legacy);preferVector:=true.B}
  when(io.vectorIn.fire){owner:=Mux(vectorTyped,Owner.typed,Owner.legacy);preferVector:=false.B}
  io.commit.valid:=Mux(owner===Owner.typed,typed.io.commit.valid,owner===Owner.legacy && legacy.io.commit.valid)
  io.commit.bits:=Mux(owner===Owner.typed,typed.io.commit.bits,legacy.io.commit.bits)
  io.vectorCommit.valid:=Mux(owner===Owner.typed,typed.io.vectorCommit.valid,owner===Owner.legacy && legacy.io.vectorCommit.valid)
  io.vectorCommit.bits:=Mux(owner===Owner.typed,typed.io.vectorCommit.bits,legacy.io.vectorCommit.bits)
  legacy.io.commit.ready:=owner===Owner.legacy && io.commit.ready;legacy.io.vectorCommit.ready:=owner===Owner.legacy && io.vectorCommit.ready
  typed.io.commit.ready:=owner===Owner.typed && io.commit.ready;typed.io.vectorCommit.ready:=owner===Owner.typed && io.vectorCommit.ready
  io.memoryRequest.valid:=Mux(owner===Owner.typed,credits.io.memoryRequest.valid,owner===Owner.legacy && bridge.io.memoryRequest.valid)
  io.memoryRequest.bits:=Mux(owner===Owner.typed,credits.io.memoryRequest.bits,bridge.io.memoryRequest.bits)
  credits.io.memoryRequest.ready:=owner===Owner.typed && io.memoryRequest.ready;bridge.io.memoryRequest.ready:=owner===Owner.legacy && io.memoryRequest.ready
  credits.io.memoryResponse.valid:=owner===Owner.typed && io.memoryResponse.valid;credits.io.memoryResponse.bits:=io.memoryResponse.bits
  bridge.io.memoryResponse.valid:=owner===Owner.legacy && io.memoryResponse.valid;bridge.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(owner===Owner.typed,credits.io.memoryResponse.ready,owner===Owner.legacy && bridge.io.memoryResponse.ready)
  io.coordinateFault:=owner===Owner.legacy && legacy.io.coordinateFault.get
  io.responseFault:=(owner===Owner.legacy && bridge.io.responseFault) ||
    (owner===Owner.typed && (typed.io.commit.valid || typed.io.vectorCommit.valid) && typed.io.status=/=0.U)
  io.faultAddress:=Mux(owner===Owner.typed,typed.io.faultAddress,bridge.io.out.bits.addr)
  io.busy:=owner=/=Owner.idle
  when(io.commit.fire || io.vectorCommit.fire){
    assert(!credits.io.busy && !io.memoryRequest.valid,"native texture retirement abandoned external line ownership")
    owner:=Owner.idle
  }
}
