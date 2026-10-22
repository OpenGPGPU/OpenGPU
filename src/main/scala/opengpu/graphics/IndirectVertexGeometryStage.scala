package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig

/** Resolve GPU-memory draw arguments before the shader and assembler observe
  * the draw. Parameter reads exclusively borrow vertex transaction zero; the
  * resolved child cannot start until every parameter reply has drained. No
  * enclosing memory namespace or shader invocation ABI is widened.
  */
class VertexGeometryStage(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false,
    flatShading: Boolean=true,transformFeedback: Boolean=false) extends Module {
  private val direct=Module(new DirectVertexGeometryStage(config,p,shared,useSramBlackBoxes,payload,
    payloadOwner,groupedTexture,flatShading,transformFeedback))
  val io=IO(chiselTypeOf(direct.io))
  io<>direct.io
  private val reader=Module(new IndirectDrawReader(config))
  private object Phase extends ChiselEnum {val idle,arguments,waitArguments,offerChild,child,error=Value}
  private val phase=RegInit(Phase.idle)
  private val draw=Reg(new VertexGeometryDraw(p));private val error=Reg(new PrimitiveAssemblyCompletion)
  private val canceled=RegInit(false.B);private val lines=RegInit(0.U(2.W))
  private val context=payload.map(_=>Reg(UInt(32.W)))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B) ||
    io.feedback.map(_.cancel).getOrElse(false.B)
  private val stop=externalCancel || canceled
  private val started=phase===Phase.child
  when(externalCancel && phase=/=Phase.idle){canceled:=true.B}
  io.draw.ready:=phase===Phase.idle && direct.io.draw.ready && !externalCancel
  direct.io.draw.valid:=phase===Phase.offerChild;direct.io.draw.bits:=draw
  direct.io.draw.bits.indirect:=0.U.asTypeOf(direct.io.draw.bits.indirect)
  direct.io.cancel:=started && stop
  io.busy:=phase=/=Phase.idle || direct.io.busy || reader.io.busy
  io.completion.valid:=phase===Phase.error || (started && direct.io.completion.valid)
  io.completion.bits:=Mux(phase===Phase.error,error,direct.io.completion.bits)
  io.completion.bits.indirectLines:=lines
  direct.io.completion.ready:=started && io.completion.ready
  payload.foreach{_=>
    direct.io.payload.get.context:=Mux(phase===Phase.idle,io.payload.get.context,context.get)
    direct.io.payload.get.cancel:=started && stop
    when(io.draw.fire){context.get:=io.payload.get.context}
  }
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.draw.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,direct.io.feedback.get,
      Mux(phase===Phase.idle,io.feedback.get.scope,scope),phase===Phase.idle)
    io.feedback.get.busy:=direct.io.feedback.get.busy || phase=/=Phase.idle
    // An already offered child must be accepted before reset can cancel it.
    // Remember a reset pulse rather than losing it during that handshake.
    val resetPending=RegInit(false.B)
    when(io.feedback.get.cancel){resetPending:=true.B}
    direct.io.feedback.get.cancel:=(io.feedback.get.cancel || resetPending) && phase=/=Phase.offerChild
    when(direct.io.feedback.get.cancel){resetPending:=false.B}
  }
  private val parameterOwner=phase===Phase.arguments || phase===Phase.waitArguments
  val parameterRequest=Wire(chiselTypeOf(io.memoryRequest.bits))
  parameterRequest:=reader.io.memoryRequest.bits;parameterRequest.transactionId:=0.U
  io.memoryRequest.valid:=Mux(parameterOwner,reader.io.memoryRequest.valid,direct.io.memoryRequest.valid)
  io.memoryRequest.bits:=Mux(parameterOwner,parameterRequest,direct.io.memoryRequest.bits)
  reader.io.memoryRequest.ready:=parameterOwner && io.memoryRequest.ready
  direct.io.memoryRequest.ready:= !parameterOwner && io.memoryRequest.ready
  reader.io.memoryResponse.valid:=parameterOwner && io.memoryResponse.valid
  reader.io.memoryResponse.bits:=io.memoryResponse.bits;reader.io.memoryResponse.bits.transactionId:=0.U
  direct.io.memoryResponse.valid:= !parameterOwner && io.memoryResponse.valid
  io.memoryResponse.ready:=Mux(parameterOwner,reader.io.memoryResponse.ready,direct.io.memoryResponse.ready)
  when(parameterOwner && io.memoryResponse.valid){assert(io.memoryResponse.bits.transactionId===0.U)}
  reader.io.in.valid:=phase===Phase.arguments;reader.io.in.bits:=draw.indirect
  reader.io.out.ready:=phase===Phase.waitArguments;reader.io.cancel:=stop
  private def fail(code: UInt,address: UInt,identity: GraphicsWorkIdentity): Unit={
    error:=0.U.asTypeOf(error);error.identity:=identity;error.status:=code
    error.source.identity:=identity;error.source.status:=code;error.source.faultAddress:=address
    error.source.input.identity:=identity;error.source.input.status:=code;error.source.input.faultAddress:=address
    phase:=Phase.error
  }
  when(io.draw.fire){
    draw:=io.draw.bits;canceled:=false.B;lines:=0.U
    val d=io.draw.bits;val c=d.indirect
    val prefix=d.vertex.scratch.base.pad(48)+(7.U+&(d.vertex.attributeSlots<<2)+&
      (d.vertex.varyingSlots<<2)+&(d.vertex.version===2.U).asUInt)*(config.lanes*config.warps*4).U
    val parameterEnd=c.view.base+&Mux(c.mode===1.U,16.U,20.U)
    val alias=d.vertex.scratch.base<parameterEnd && prefix>c.view.base
    when(!IndirectDrawControl.legal(c,d.vertex.input.indexFormat) || (c.mode=/=0.U && alias)){
      fail(VertexInputStatus.invalidDescriptor.U,c.view.base,d.vertex.input.identity)
    }.otherwise{phase:=Mux(c.mode===0.U,Phase.offerChild,Phase.arguments)}
  }
  when(reader.io.in.fire){phase:=Phase.waitArguments}
  when(reader.io.out.fire){
    val r=reader.io.out.bits;lines:=r.lines
    when(r.status=/=0.U && r.status=/=VertexInputStatus.cancelled.U){fail(r.status,r.faultAddress,draw.vertex.input.identity)}
      .elsewhen(stop || r.status===VertexInputStatus.cancelled.U){fail(VertexInputStatus.cancelled.U,0.U,draw.vertex.input.identity)}
      .otherwise{
        val i=draw.vertex.input;i.count:=r.count;i.instances:=r.instances;i.baseInstance:=0.U
        i.firstVertex:=Mux(draw.indirect.mode===1.U,r.first,0.U)
        i.firstIndex:=Mux(draw.indirect.mode===2.U,r.first,0.U)
        i.baseVertex:=r.baseVertex;phase:=Phase.offerChild
      }
  }
  when(direct.io.draw.fire){phase:=Phase.child}
  when(io.completion.fire){phase:=Phase.idle}
}
