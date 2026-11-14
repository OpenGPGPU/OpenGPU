package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

/** Draft GNT1 record: fixed 16-lane/eight-warp and 16-varying capacities.
  * Every named scalar occupies complete little-endian words and is contained
  * in one cache line. Words not assigned to a field must be zero.
  */
object TessellationNativeSequenceLayout {
  val magic=BigInt("474e5401",16);val version=1;val words=512;val bytes=words*4;val lines=bytes/64
  val uniformMagic=BigInt("474e5404",16);val uniformVersion=4
  case class Field(name:String,word:Int,value:Data) {
    val width=value.getWidth;val words=(width+31)/32
    require(width>0 && words<=16 && word%16+words<=16)
  }
  def fields(draw:TessellationPatchSequenceDescriptor,uniforms:Boolean=false):Seq[Field]={
    var word=16
    val result=WindowPrimitiveRecordLayout.leaves(draw).filterNot(x=>TessellationUniformSequenceLayout.isUniformField(x._1)).map{case(name,value)=>
      val n=(value.getWidth+31)/32
      if(word%16+n>16)word=(word+15)/16*16
      val f=Field(name,word,value);word+=n;f}
    require(word<=words,"native tessellation record exceeds its fixed size")
    if(uniforms){
      require(word<=TessellationUniformSequenceLayout.firstWord,"old domain fields overlap the uniform extension")
      result++TessellationUniformSequenceLayout.fields(draw.template.source.viewport.system.uniforms).map{case(n,w,v)=>Field(n,w,v)}
    }else result
  }
  /** Reject truncation and every nonzero unsupported vector element. */
  def fits(source:Data,target:Data,path:String=""):Bool=(source,target) match {
    case (a:Record,b:Record)=>b.elements.toSeq.map{case(k,v)=>fits(a.elements(k),v,if(path.isEmpty)k else s"$path.$k")}.reduce(_ && _)
    case (a:Vec[_],b:Vec[_])=>
      require(a.length>=b.length)
      (b.indices.map(i=>fits(a(i),b(i),s"$path[$i]"))++a.drop(b.length).map(x=> !x.asUInt.orR)).reduceOption(_ && _).getOrElse(true.B)
    case (a:SInt,b:SInt)=>
      require(a.getWidth>=b.getWidth)
      if(a.getWidth==b.getWidth)true.B else a===a.asUInt(b.getWidth-1,0).asSInt.pad(a.getWidth)
    // Native helper bits use 16 lanes per warp and are checked/repacked below.
    case (_:UInt,_:UInt) if path.endsWith(".invocations.helpers")=>true.B
    case (a:UInt,b:UInt)=>
      require(a.getWidth>=b.getWidth)
      if(a.getWidth==b.getWidth)true.B else !a(a.getWidth-1,b.getWidth).orR
    case _=>throw new IllegalArgumentException("incompatible tessellation draw fields")
  }
}

class TessellationNativeSequenceRequest(idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val frameIdentity=new GraphicsWorkIdentity;val view=new VertexInputView
}
class TessellationNativeSequenceCompletion(idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val status=UInt(3.W);val frameStopped=Bool();val childValid=Bool();val faultAddress=UInt(32.W);val readBytes=UInt(16.W)
  val child=new TessellationPatchSequenceCompletion
}

/** Read one private draw snapshot before offering it to the frame owner.
  * The enclosing command owner pins the descriptor and resources, checks
  * descriptor aliasing, retains the frame identity and tag, and drains the
  * frame independently. An offered request or draw survives cancellation.
  * A terminal frameStopped signal flushes an unaccepted draw only after the
  * frame has stopped accepting work; accepted child work still drains.
  */
class TessellationNativeSequenceReader(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int,tessellationUniforms:Boolean=false) extends Module {
  require(config.xLen==32 && config.lanes<=16 && config.warps<=8 && idWidth>0 && idWidth<=12)
  private val maxConfig=GpuConfig();private val maxVertex=VertexShaderStageConfig()
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationNativeSequenceRequest(idWidth)))
    val draw=Decoupled(new TessellationPatchSequenceDescriptor(config,p,idWidth))
    val drawCompletion=Flipped(Decoupled(new TessellationPatchSequenceCompletion))
    val childCancel=Output(Bool());val frameStopped=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TessellationNativeSequenceCompletion(idWidth))
  })
  import TessellationNativeSequenceLayout._
  private object State extends ChiselEnum {val idle,offer,waitLine,check,draw,waitChild,complete=Value}
  private val state=RegInit(State.idle);private val request=Reg(new TessellationNativeSequenceRequest(idWidth))
  private val snapshot=Reg(new TessellationPatchSequenceDescriptor(maxConfig,maxVertex,12))
  private val result=Reg(new TessellationNativeSequenceCompletion(idWidth));private val line=Reg(UInt(log2Ceil(lines).W))
  private val cancelled=RegInit(false.B);private val malformed=RegInit(false.B)
  private val layoutFields=TessellationNativeSequenceLayout.fields(snapshot,tessellationUniforms);private val occupied=(layoutFields.flatMap(f=>f.word until f.word+f.words)++Seq(0,1,2)).toSet
  private val raw=io.memoryResponse.bits.readData
  private val invalid=scala.collection.mutable.ArrayBuffer.empty[Bool]
  for((value,word)<-Seq(if(tessellationUniforms)uniformMagic else magic,BigInt(bytes),BigInt(if(tessellationUniforms)uniformVersion else version)).zipWithIndex)
    invalid+=line===(word/16).U && raw((word%16)*32+31,(word%16)*32)=/=value.U
  for(word<-0 until words if !occupied(word))
    invalid+=line===(word/16).U && raw((word%16)*32+31,(word%16)*32).orR
  for(f<-layoutFields){
    val selected=line===(f.word/16).U;val lo=(f.word%16)*32
    val packed=raw(lo+f.words*32-1,lo);val bits=packed(f.width-1,0)
    val canonical=f.value match {case _:SInt=>bits.asSInt.pad(f.words*32).asUInt;case _=>bits.pad(f.words*32)}
    invalid+=selected && packed=/=canonical
    when(state===State.waitLine && io.memoryResponse.fire && !io.memoryResponse.bits.fault && selected){
      f.value match {case s:SInt=>s:=bits.asSInt;case b:Bool=>b:=bits(0);case u:UInt=>u:=bits;case _=>throw new IllegalArgumentException(f.name)}
    }
  }
  private val malformedLine=invalid.reduce(_ || _)
  private val v=io.submit.bits.view;private val end=v.base.pad(33)+&bytes.U
  private val legal=v.base(5,0)===0.U && v.allocationBase(5,0)===0.U && v.allocationLimit(5,0)===0.U &&
    v.allocationLimit>v.allocationBase && v.allocationLimit<=(BigInt(1)<<32).U && v.base>=v.allocationBase &&
    v.limit>=v.base && v.limit<=v.allocationLimit && end<=v.limit && end<=(BigInt(1)<<32).U
  private val target=Wire(new TessellationPatchSequenceDescriptor(config,p,idWidth))
  NativeTypedGraphicsLayout.adapt(snapshot,target)
  target.template.source.viewport.system.patch.commandId:=request.commandId
  private val controlHelpers=snapshot.template.source.viewport.system.patch.control.kernel.invocations.helpers
  private val evaluationHelpers=snapshot.template.source.viewport.system.patch.evaluationKernel.invocations.helpers
  private val allowedHelpers=(for(w<-0 until config.warps;l<-0 until config.lanes)yield BigInt(1)<<(w*16+l)).foldLeft(BigInt(0))(_|_)
  private val unsupportedHelpers=(((BigInt(1)<<128)-1)^allowedHelpers).U(128.W)
  private def repackHelpers(value:UInt):UInt=Cat((0 until config.warps).reverse.map(w=>value(w*16+config.lanes-1,w*16)))
  target.template.source.viewport.system.patch.control.kernel.invocations.helpers:=repackHelpers(controlHelpers)
  target.template.source.viewport.system.patch.evaluationKernel.invocations.helpers:=repackHelpers(evaluationHelpers)
  private val fixedTag=snapshot.template.source.viewport.system.patch.commandId===0.U
  private val sameIdentity=snapshot.template.source.viewport.identity.asUInt===request.frameIdentity.asUInt
  private val uniformScope=snapshot.template.source.viewport.system.uniforms
  private val uniformPresent=uniformScope.control.resources.version=/=0.U || uniformScope.evaluation.resources.version=/=0.U
  private val uniformLegal=if(tessellationUniforms)TessellationUniformTaskResources.legal(uniformScope) &&
    !snapshot.template.source.viewport.system.uniformResources &&
    (!uniformPresent || uniformScope.identity.asUInt===request.frameIdentity.asUInt)
    else uniformScope.asUInt===0.U
  private val fits=TessellationNativeSequenceLayout.fits(snapshot,target) &&
    !(controlHelpers & unsupportedHelpers).orR && !(evaluationHelpers & unsupportedHelpers).orR
  io.submit.ready:=state===State.idle && !io.cancel && !io.frameStopped
  when(io.submit.fire){request:=io.submit.bits;result:=0.U.asTypeOf(result);result.commandId:=io.submit.bits.commandId
    snapshot:=0.U.asTypeOf(snapshot);line:=0.U;cancelled:=false.B;malformed:=false.B
    when(!legal){result.status:=1.U;result.faultAddress:=io.submit.bits.view.base;state:=State.complete}
      .otherwise{state:=State.offer}}
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  io.memoryRequest.valid:=state===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=request.view.base+(line<<6);io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W));io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  when(io.memoryRequest.fire){state:=State.waitLine}
  io.memoryResponse.ready:=state===State.waitLine && io.memoryResponse.bits.transactionId===0.U
  when(io.memoryResponse.fire){result.readBytes:=result.readBytes+64.U
    when(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .elsewhen(io.frameStopped){result.status:=1.U;result.frameStopped:=true.B;state:=State.complete}
      .elsewhen(io.memoryResponse.bits.fault){result.status:=2.U;result.faultAddress:=request.view.base+(line<<6);state:=State.complete}
      .otherwise{when(malformedLine){malformed:=true.B}
        when(line===(lines-1).U){state:=State.check}.otherwise{line:=line+1.U;state:=State.offer}}}
  when(state===State.check){when(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
    .elsewhen(io.frameStopped){result.status:=1.U;result.frameStopped:=true.B;state:=State.complete}
    .elsewhen(malformed || !fits || !sameIdentity || !fixedTag || !uniformLegal){result.status:=1.U;result.faultAddress:=request.view.base;state:=State.complete}
    .otherwise{state:=State.draw}}
  io.draw.valid:=state===State.draw && !io.frameStopped;io.draw.bits:=target
  when(state===State.draw && io.frameStopped){result.status:=Mux(cancelled || io.cancel,4.U,1.U);result.frameStopped:=true.B;state:=State.complete}
  when(io.draw.fire){state:=State.waitChild}
  io.childCancel:=state===State.waitChild && (cancelled || io.cancel)
  io.drawCompletion.ready:=state===State.waitChild
  when(io.drawCompletion.fire){result.childValid:=true.B;result.child:=io.drawCompletion.bits
    val feedbackStatus=io.drawCompletion.bits.child.source.source.feedbackStatus
    val feedbackFault=io.drawCompletion.bits.status===5.U && feedbackStatus=/=0.U && feedbackStatus=/=4.U
    result.status:=Mux(feedbackFault,5.U,Mux(cancelled || io.cancel,4.U,io.drawCompletion.bits.status));state:=State.complete}
  io.completion.valid:=state===State.complete;io.completion.bits:=result;io.busy:=state=/=State.idle
  when(io.completion.fire){state:=State.idle}
}
