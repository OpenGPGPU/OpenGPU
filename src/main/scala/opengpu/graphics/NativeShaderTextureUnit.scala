package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class NativeShaderTextureIO(config: GpuConfig,groupedTexture: Boolean=false,uniformAvailable: Boolean=false) extends Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)));val vectorIn=Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit=Decoupled(new ScalarCommitRequest(config));val vectorCommit=Decoupled(new VectorCommitRequest(config))
    val resources=Input(new ShaderTextureResources);val identity=Input(new GraphicsWorkIdentity)
    val uniforms=if(uniformAvailable)Some(Input(new ShaderUniformResources))else None
    val privateBase=if(uniformAvailable)Some(Input(UInt(32.W)))else None
    val privateLimit=if(uniformAvailable)Some(Input(UInt(33.W)))else None
    val texFormat=Input(UInt(PixelFormat.width.W));val texBase=Input(UInt(32.W));val texWidth=Input(UInt(14.W));val texHeight=Input(UInt(14.W))
    val wrapClamp=Input(Bool());val coordinatePolicy=Input(new TextureCoordinatePolicy)
    val texMaxLevel=Input(UInt(4.W));val lodBias=Input(SInt(5.W));val minLevel=Input(UInt(4.W))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val clearParameters=Input(Bool());val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val coordinateFault=Output(Bool());val responseFault=Output(Bool());val faultAddress=Output(UInt(32.W))
}

/** One native task's legacy sampling, typed fetch and optional uniform service.
  * Grouped floating instructions share one metadata engine and bounded lane
  * contexts. Legacy, typed and uniform execution remain exclusive. The two external line slots
  * remain shared and no enclosing memory fabric is widened.
  */
class NativeShaderTextureUnit(config: GpuConfig=GpuConfig(),stage: Int=0,groupedTexture: Boolean=false,instructionSnapshots: Boolean=true,streamBurst: Boolean=true,uniformAvailable: Boolean=false) extends Module {
  val io=IO(new NativeShaderTextureIO(config,groupedTexture,uniformAvailable))
  private object Owner extends ChiselEnum {val idle,legacy,typed,floating,uniform=Value}
  private val owner=RegInit(Owner.idle);private val preferVector=RegInit(true.B)
  private val legacy=Module(new TexSampleUnit(config,coordinatePolicyInput=true));private val bridge=Module(new OmWordToLinePort(config,maxOutstanding=2))
  private val floating=Module(new TextureSampleExecuteUnit(config,stage,groupedTexture,instructionSnapshots,streamBurst))
  if(groupedTexture){io.grouped.get <> floating.io.grouped.get}
  private val typed=Module(new TexelFetchExecuteUnit(config,stage));private val credits=Module(new TextureLineCreditAdapter(config))
  private val uniform=if(uniformAvailable)Some(Module(new ShaderUniformExecuteUnit(config,stage)))else None
  private val uniformOwner=uniformAvailable.B && owner===Owner.uniform
  private val uniformRequest=Wire(new ComputeMemoryRequest(config,64,16))
  uniformRequest:=0.U.asTypeOf(uniformRequest)
  uniform.foreach{u=>
    uniformRequest:=u.io.memoryRequest.bits
    u.io.resources:=io.uniforms.get;u.io.identity:=io.identity
    u.io.privateBase:=io.privateBase.get;u.io.privateLimit:=io.privateLimit.get
    u.io.cancel:=uniformOwner && io.cancel
    u.io.memoryRequest.ready:=uniformOwner && credits.io.in.ready
    u.io.memoryResponse.valid:=uniformOwner && credits.io.out.valid;u.io.memoryResponse.bits:=credits.io.out.bits
    when(u.io.memoryResponse.fire){assert(credits.io.out.bits.transactionId===0.U,"uniform reply lost its sole logical credit")}
    u.io.commit.ready:=uniformOwner && io.commit.ready;u.io.vectorCommit.ready:=uniformOwner && io.vectorCommit.ready
  }
  private val ownerIdle=owner===Owner.idle || (groupedTexture.B && owner===Owner.floating && !floating.io.busy)
  legacy.io.mem.req<>bridge.io.in;legacy.io.mem.resp<>bridge.io.out
  credits.io.in.valid:=Mux(uniformOwner,uniform.map(_.io.memoryRequest.valid).getOrElse(false.B),Mux(owner===Owner.floating,floating.io.memoryRequest.valid,owner===Owner.typed && typed.io.memoryRequest.valid))
  credits.io.in.bits:=Mux(uniformOwner,uniformRequest,Mux(owner===Owner.floating,floating.io.memoryRequest.bits,typed.io.memoryRequest.bits))
  floating.io.memoryRequest.ready:=owner===Owner.floating && credits.io.in.ready;typed.io.memoryRequest.ready:=owner===Owner.typed && credits.io.in.ready
  floating.io.memoryResponse.valid:=owner===Owner.floating && credits.io.out.valid;floating.io.memoryResponse.bits:=credits.io.out.bits
  typed.io.memoryResponse.valid:=owner===Owner.typed && credits.io.out.valid;typed.io.memoryResponse.bits:=credits.io.out.bits
  credits.io.out.ready:=Mux(uniformOwner,uniform.map(_.io.memoryResponse.ready).getOrElse(false.B),Mux(owner===Owner.floating,floating.io.memoryResponse.ready,owner===Owner.typed && typed.io.memoryResponse.ready))
  floating.io.resources:=io.resources;floating.io.identity:=io.identity;floating.io.invalidate:=io.invalidate
  floating.io.cancel:=owner===Owner.floating && io.cancel;floating.io.clearParameters:=io.clearParameters
  when(io.clearParameters){assert(ownerIdle && !credits.io.busy,"native task reset must wait for all texture ownership")}
  legacy.io.texFormat:=io.texFormat;legacy.io.texBase:=io.texBase;legacy.io.texWidth:=io.texWidth;legacy.io.texHeight:=io.texHeight
  legacy.io.wrapClamp:=io.wrapClamp;legacy.io.coordinatePolicy.get:=io.coordinatePolicy
  legacy.io.texMaxLevel:=io.texMaxLevel;legacy.io.lodBias:=io.lodBias;legacy.io.minLevel:=io.minLevel
  typed.io.resources:=io.resources;typed.io.identity:=io.identity;typed.io.invalidate:=io.invalidate
  // An already-issued instruction must be accepted before it can be cancelled.
  typed.io.cancel:=owner===Owner.typed && io.cancel
  private val chooseVector=io.vectorIn.valid && (!io.in.valid || preferVector)
  private val resourceOwner=owner===Owner.typed || owner===Owner.floating || uniformOwner
  private val floatingAvailable=ownerIdle || (groupedTexture.B && owner===Owner.floating)
  private val scalarUniform=ShaderUniformOpcode.scalar(io.in.bits.decode.instruction)
  private val vectorUniform=ShaderUniformOpcode.vector(io.vectorIn.bits.issued.decode.instruction)
  private val scalarTyped=ShaderTextureFetchOpcode.scalar(io.in.bits.decode.instruction)
  private val vectorTyped=ShaderTextureFetchOpcode.vector(io.vectorIn.bits.issued.decode.instruction)
  private val scalarFloating=ShaderTextureSampleOpcode.scalar(io.in.bits.decode.instruction)
  private val vectorFloating=ShaderTextureSampleOpcode.vector(io.vectorIn.bits.issued.decode.instruction)
  legacy.io.in.valid:=ownerIdle && !io.clearParameters && !chooseVector && io.in.valid && !scalarTyped && !scalarFloating && !scalarUniform;legacy.io.in.bits:=io.in.bits
  legacy.io.vectorIn.valid:=ownerIdle && !io.clearParameters && chooseVector && io.vectorIn.valid && !vectorTyped && !vectorFloating && !vectorUniform;legacy.io.vectorIn.bits:=io.vectorIn.bits
  typed.io.in.valid:=ownerIdle && !io.clearParameters && !chooseVector && io.in.valid && scalarTyped;typed.io.in.bits:=io.in.bits
  typed.io.vectorIn.valid:=ownerIdle && !io.clearParameters && chooseVector && io.vectorIn.valid && vectorTyped;typed.io.vectorIn.bits:=io.vectorIn.bits
  floating.io.in.valid:=floatingAvailable && !io.clearParameters && !chooseVector && io.in.valid && scalarFloating;floating.io.in.bits:=io.in.bits
  floating.io.vectorIn.valid:=floatingAvailable && !io.clearParameters && chooseVector && io.vectorIn.valid && vectorFloating;floating.io.vectorIn.bits:=io.vectorIn.bits
  uniform.foreach{u=>
    u.io.in.valid:=ownerIdle && !io.clearParameters && !chooseVector && io.in.valid && scalarUniform;u.io.in.bits:=io.in.bits
    u.io.vectorIn.valid:=ownerIdle && !io.clearParameters && chooseVector && io.vectorIn.valid && vectorUniform;u.io.vectorIn.bits:=io.vectorIn.bits
  }
  io.in.ready:= !io.clearParameters && !chooseVector && Mux(scalarUniform,ownerIdle && uniform.map(_.io.in.ready).getOrElse(false.B),Mux(scalarFloating,floatingAvailable && floating.io.in.ready,ownerIdle && Mux(scalarTyped,typed.io.in.ready,legacy.io.in.ready)))
  io.vectorIn.ready:= !io.clearParameters && chooseVector && Mux(vectorUniform,ownerIdle && uniform.map(_.io.vectorIn.ready).getOrElse(false.B),Mux(vectorFloating,floatingAvailable && floating.io.vectorIn.ready,ownerIdle && Mux(vectorTyped,typed.io.vectorIn.ready,legacy.io.vectorIn.ready)))
  when(io.in.fire){owner:=Mux(scalarUniform,Owner.uniform,Mux(scalarFloating,Owner.floating,Mux(scalarTyped,Owner.typed,Owner.legacy)));preferVector:=true.B}
  when(io.vectorIn.fire){owner:=Mux(vectorUniform,Owner.uniform,Mux(vectorFloating,Owner.floating,Mux(vectorTyped,Owner.typed,Owner.legacy)));preferVector:=false.B}
  io.commit.valid:=Mux(uniformOwner,uniform.map(_.io.commit.valid).getOrElse(false.B),Mux(owner===Owner.floating,floating.io.commit.valid,Mux(owner===Owner.typed,typed.io.commit.valid,owner===Owner.legacy && legacy.io.commit.valid)))
  io.commit.bits:=Mux(uniformOwner,uniform.map(_.io.commit.bits).getOrElse(0.U.asTypeOf(io.commit.bits)),Mux(owner===Owner.floating,floating.io.commit.bits,Mux(owner===Owner.typed,typed.io.commit.bits,legacy.io.commit.bits)))
  io.vectorCommit.valid:=Mux(uniformOwner,uniform.map(_.io.vectorCommit.valid).getOrElse(false.B),Mux(owner===Owner.floating,floating.io.vectorCommit.valid,Mux(owner===Owner.typed,typed.io.vectorCommit.valid,owner===Owner.legacy && legacy.io.vectorCommit.valid)))
  io.vectorCommit.bits:=Mux(uniformOwner,uniform.map(_.io.vectorCommit.bits).getOrElse(0.U.asTypeOf(io.vectorCommit.bits)),Mux(owner===Owner.floating,floating.io.vectorCommit.bits,Mux(owner===Owner.typed,typed.io.vectorCommit.bits,legacy.io.vectorCommit.bits)))
  legacy.io.commit.ready:=owner===Owner.legacy && io.commit.ready;legacy.io.vectorCommit.ready:=owner===Owner.legacy && io.vectorCommit.ready
  floating.io.commit.ready:=owner===Owner.floating && io.commit.ready;floating.io.vectorCommit.ready:=owner===Owner.floating && io.vectorCommit.ready
  typed.io.commit.ready:=owner===Owner.typed && io.commit.ready;typed.io.vectorCommit.ready:=owner===Owner.typed && io.vectorCommit.ready
  io.memoryRequest.valid:=Mux(resourceOwner,credits.io.memoryRequest.valid,owner===Owner.legacy && bridge.io.memoryRequest.valid)
  io.memoryRequest.bits:=Mux(resourceOwner,credits.io.memoryRequest.bits,bridge.io.memoryRequest.bits)
  credits.io.memoryRequest.ready:=resourceOwner && io.memoryRequest.ready;bridge.io.memoryRequest.ready:=owner===Owner.legacy && io.memoryRequest.ready
  credits.io.memoryResponse.valid:=resourceOwner && io.memoryResponse.valid;credits.io.memoryResponse.bits:=io.memoryResponse.bits
  bridge.io.memoryResponse.valid:=owner===Owner.legacy && io.memoryResponse.valid;bridge.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(resourceOwner,credits.io.memoryResponse.ready,owner===Owner.legacy && bridge.io.memoryResponse.ready)
  io.coordinateFault:=owner===Owner.legacy && legacy.io.coordinateFault.get
  io.responseFault:=(owner===Owner.legacy && bridge.io.responseFault) ||
    (owner===Owner.typed && (typed.io.commit.valid || typed.io.vectorCommit.valid) && typed.io.status=/=0.U) ||
    (owner===Owner.floating && (floating.io.commit.valid || floating.io.vectorCommit.valid) && floating.io.status=/=0.U) ||
    uniform.map(u=>uniformOwner && (u.io.commit.valid || u.io.vectorCommit.valid) && u.io.status=/=0.U).getOrElse(false.B)
  io.faultAddress:=Mux(uniformOwner,uniform.map(_.io.faultAddress).getOrElse(0.U),Mux(owner===Owner.floating,floating.io.faultAddress,Mux(owner===Owner.typed,typed.io.faultAddress,bridge.io.out.bits.addr)))
  io.busy:= !ownerIdle
  when(io.commit.fire || io.vectorCommit.fire){
    if(groupedTexture){when(owner=/=Owner.floating){
      assert(!credits.io.busy && !io.memoryRequest.valid,"native texture retirement abandoned external line ownership")
      owner:=Owner.idle
    }}else{
      assert(!credits.io.busy && !io.memoryRequest.valid,"native texture retirement abandoned external line ownership")
      owner:=Owner.idle
    }
  }
  if(groupedTexture){when(owner===Owner.floating && !floating.io.busy && !io.in.fire && !io.vectorIn.fire){
    assert(!credits.io.busy && !io.memoryRequest.valid,"native floating drain abandoned external line ownership")
    owner:=Owner.idle
  }}
}
