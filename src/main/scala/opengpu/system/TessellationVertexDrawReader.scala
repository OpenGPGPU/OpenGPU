package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationVertexDrawRequest extends Bundle {
  /** Root accepted by the GVS reader, with its original exact logical view. */
  val root=new TessellationVertexSourceRoot
  val sourceView=new VertexInputView
  /** Accepted owner's immutable private scope, not a decoded address grant. */
  val privateBase=UInt(32.W);val privateLimit=UInt(33.W)
}
class TessellationVertexDrawCompletion(p:VertexShaderStageConfig) extends Bundle {
  /** 0 captured, 1 encoding/bounds/scope, 2 descriptor memory, 4 cancelled. */
  val status=UInt(3.W);val faultAddress=UInt(32.W);val readBytes=UInt(12.W)
  val draw=new VertexShaderDraw(p)
}

/** Capture only the original VS draw from an exact private GND1, using the
  * production GND field map and its complete canonical word checks. No input
  * is rebased to a patch: the separate range sequencer owns original-relative
  * slices. This is neither whole-frame admission nor shader/RX authorization.
  *
  * A held tag-zero offer and every accepted response survive cancellation.
  * The enclosing native owner pins all records, original resources, code and
  * GUR scopes through the complete VS/TCS/TES/patch retirement. CPU physical
  * aliases and aliases with unrelated frame/TCS/TES resources still require
  * the protected outer BUILD; VA checks here cannot replace that provenance.
  */
class TessellationVertexDrawReader(config:GpuConfig,p:VertexShaderStageConfig=VertexShaderStageConfig()) extends Module {
  require(config.xLen==32 && config.lanes==16 && config.warps==8)
  require(p.input.attributes==16 && p.input.bindings==16 && p.varyings==16)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationVertexDrawRequest))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TessellationVertexDrawCompletion(p))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,check,complete=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new TessellationVertexDrawRequest)
  private val record=Reg(new NativeBinnedGraphicsDrawRecord)
  private val result=Reg(new TessellationVertexDrawCompletion(p))
  private val line=Reg(UInt(5.W));private val cancelled=RegInit(false.B);private val malformed=RegInit(false.B)
  private val mapped=NativeBinnedGraphicsLayout.drawFields(record)
  NativeTypedGraphicsLayout.check(record,mapped,NativeBinnedGraphicsLayout.drawWords)
  private val write=state===State.waitReply && io.memoryResponse.fire && !io.memoryResponse.bits.fault
  private val badLine=NativeTypedGraphicsLayout.receive(mapped,NativeBinnedGraphicsLayout.drawWords,
    NativeBinnedGraphicsLayout.drawMagic,line,io.memoryResponse.bits.readData,write)

  private def inside(base:UInt,end:UInt,scopeBase:UInt,scopeEnd:UInt):Bool=
    end>base && end<=(BigInt(1)<<32).U && base>=scopeBase && end<=scopeEnd
  private def scope(base:UInt,end:UInt):Bool=
    base(5,0)===0.U && end(5,0)===0.U && end>base && end<=(BigInt(1)<<32).U
  private def overlap(a:UInt,b:UInt,c:UInt,d:UInt):Bool=a<b && c<d && a<d && c<b
  private val q=io.submit.bits;private val r=q.root;private val sv=q.sourceView
  private val sourceEnd=sv.base.pad(33)+&512.U;private val drawEnd=r.drawBase.pad(33)+&2048.U
  private val legalRequest=scope(q.privateBase,q.privateLimit) && VertexInputDescriptor.validView(sv) &&
    sv.base(5,0)===0.U && sv.limit===sourceEnd && inside(sv.base,sourceEnd,q.privateBase,q.privateLimit) &&
    r.drawBase(5,0)===0.U && r.drawLimit===drawEnd && inside(r.drawBase,drawEnd,q.privateBase,q.privateLimit) &&
    r.codeBase(1,0)===0.U && r.codeLimit(1,0)===0.U && r.entry(1,0)===0.U &&
    inside(r.codeBase,r.codeLimit,q.privateBase,q.privateLimit) && r.codeLimit-r.codeBase<=2048.U &&
    r.entry>=r.codeBase && r.entry<r.codeLimit

  private val root=request.root;private val v=record.draw.vertex.geometry.vertex
  private val prefixWords=7.U+&(v.attributeSlots<<2)+&(v.varyingSlots<<2)+&Mux(v.version===2.U,1.U,0.U)
  private val prefixEnd=v.scratch.base.pad(65)+(prefixWords*512.U).pad(65)
  private val (shaderInvalid,shaderBounds)=VertexShaderDescriptor.errors(v,p,config,prefixEnd)
  private val (inputInvalid,inputBounds)=VertexInputDescriptor.errors(v.input,p.input)
  private val scratch=VertexInputDescriptor.validView(v.scratch) && v.scratch.base(5,0)===0.U &&
    v.scratch.limit(5,0)===0.U && prefixEnd<=v.scratch.limit && prefixEnd<=(BigInt(1)<<32).U &&
    ((prefixEnd+63.U)&((BigInt(1)<<65)-64).U(65.W))<=v.scratch.allocationLimit
  private val g=root.layout
  private val layout=g.inputVertices>=1.U && g.inputVertices<=32.U && g.outputVertices>=1.U && g.outputVertices<=32.U &&
    g.inputComponents<=64.U && g.outputComponents<=64.U && g.patchComponents<=120.U &&
    g.inputVertices*g.inputComponents<=2048.U && g.outputVertices*g.outputComponents+&g.patchComponents<=2048.U
  private val ranges=root.sliceVersion===1.U && (root.patchesPerInstance*root.instanceCount)===root.patchCount.pad(64) &&
    Mux(root.patchCount===0.U,root.firstOrdinal===0.U && root.patchesPerInstance===0.U &&
      root.firstInstance===0.U && root.instanceCount===0.U,
      root.firstOrdinal.pad(65)+(root.patchesPerInstance*g.inputVertices).pad(65)<=v.input.count.pad(65) &&
      root.firstInstance+&root.instanceCount<=v.input.instances.pad(33) &&
      root.taskIdBase.pad(65)+root.patchCount.pad(65)<=(BigInt(1)<<32).U)
  private val maps=root.components.zipWithIndex.map{case(m,i)=>
    val maskIndex=Mux(m.component<64.U,m.component,0.U)(5,0)
    Mux(i.U>=g.inputComponents,m.asUInt===0.U,
      (m.kind===0.U && m.component<4.U) ||
      (m.kind===1.U && m.component<64.U && m.component<(v.varyingSlots<<2) && v.varyingMask(maskIndex)) ||
      (m.kind===2.U && m.component===0.U && v.version===2.U))
  }.reduce(_ && _)
  private val uniform=ShaderUniformResources.legal(v.uniforms) &&
    (v.uniforms.version===0.U || inside(v.uniforms.address,v.uniforms.address.pad(33)+&64.U,request.privateBase,request.privateLimit))
  private val immutable= !overlap(v.scratch.base,prefixEnd,request.sourceView.base,request.sourceView.limit) &&
    !overlap(v.scratch.base,prefixEnd,root.drawBase,root.drawLimit) &&
    !overlap(v.scratch.base,prefixEnd,root.codeBase,root.codeLimit) &&
    !(v.uniforms.version=/=0.U && overlap(v.scratch.base,prefixEnd,v.uniforms.address,v.uniforms.address.pad(33)+&64.U))
  private val closed=v.sampler.asUInt===0.U && v.resources.asUInt===0.U && !v.reuseOutputs &&
    record.draw.vertex.geometry.indirect.asUInt===0.U && !v.input.restart
  private val legal=NativeBinnedGraphicsLayout.headerLegal(record.header) && record.draw.version===1.U &&
    v.input.identity.asUInt===root.identity.asUInt && v.version===root.vertexAbi && v.shaderPc===root.entry &&
    layout && ranges && maps && uniform && immutable && closed && scratch &&
    !shaderInvalid && !shaderBounds && !inputInvalid && !inputBounds

  io.submit.ready:=state===State.idle && !io.cancel
  io.memoryRequest.valid:=state===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  // The complete 2048-byte extent is checked before this bounded addition.
  io.memoryRequest.bits.address:=(request.root.drawBase.pad(33)+(line<<6))(31,0)
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W))
  io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryResponse.ready:=state===State.waitReply && io.memoryResponse.bits.transactionId===0.U
  io.completion.valid:=state===State.complete;io.completion.bits:=result;io.busy:=state=/=State.idle
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    request:=io.submit.bits;record:=0.U.asTypeOf(record);result:=0.U.asTypeOf(result)
    line:=0.U;cancelled:=false.B;malformed:=false.B
    when(!legalRequest){result.status:=1.U;result.faultAddress:=io.submit.bits.root.drawBase;state:=State.complete}
      .otherwise{state:=State.offer}
  }
  when(io.memoryRequest.fire){state:=State.waitReply}
  when(io.memoryResponse.fire){
    result.readBytes:=result.readBytes+64.U
    when(io.memoryResponse.bits.fault){result.status:=2.U;result.faultAddress:=(request.root.drawBase.pad(33)+(line<<6))(31,0);state:=State.complete}
      .elsewhen(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .otherwise{when(badLine){malformed:=true.B}
        when(line===31.U){state:=State.check}.otherwise{line:=line+1.U;state:=State.offer}}
  }
  when(state===State.check){
    when(cancelled || io.cancel){result.status:=4.U}
      .elsewhen(malformed || !legal){result.status:=1.U;result.faultAddress:=request.root.drawBase}
      .otherwise{NativeTypedGraphicsLayout.adapt(v,result.draw)}
    state:=State.complete
  }
  when(io.completion.fire){state:=State.idle}
}
