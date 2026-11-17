package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Captured GVS1 root. These addresses refer to private typed relocations;
  * parsing a record does not grant code, resource or launch authority. */
class TessellationVertexSourceRoot extends Bundle {
  val identity=new GraphicsWorkIdentity
  val sequenceIndex=UInt(32.W);val drawIndex=UInt(32.W);val taskIdBase=UInt(32.W);val patchCount=UInt(32.W)
  val drawBase=UInt(32.W);val drawLimit=UInt(33.W)
  val codeBase=UInt(32.W);val codeLimit=UInt(33.W);val entry=UInt(32.W)
  val sliceVersion=UInt(8.W);val firstOrdinal=UInt(32.W);val patchesPerInstance=UInt(32.W)
  val firstInstance=UInt(32.W);val instanceCount=UInt(32.W)
  val layout=new TessellationPatchLayout;val vertexAbi=UInt(8.W)
  val components=Vec(64,new TessellationVertexComponent)
}
object TessellationVertexSourceLayout {
  val magic=BigInt("47565301",16);val version=1;val words=128;val bytes=512;val lines=8
  val header=Seq(magic,BigInt(bytes),BigInt(version),BigInt(16),BigInt(16),BigInt(16),
    BigInt(64),BigInt(32),BigInt(16),BigInt(8),BigInt(2))
  case class Field(name:String,word:Int,value:UInt,shift:Int=0){
    val width=value.getWidth;val words=(shift+width+31)/32
    require(width>0 && word>=0 && word+words<=128 && word%16+words<=16)
  }
  def fields(r:TessellationVertexSourceRoot):Seq[Field]=Seq(
    Field("identity.context",16,r.identity.context),Field("identity.pass",17,r.identity.pass),
    Field("identity.generation",18,r.identity.generation),Field("sequenceIndex",19,r.sequenceIndex),
    Field("drawIndex",20,r.drawIndex),Field("taskIdBase",21,r.taskIdBase),Field("patchCount",22,r.patchCount),
    Field("draw.base",24,r.drawBase),Field("draw.limit",25,r.drawLimit),
    Field("code.base",27,r.codeBase),Field("code.limit",28,r.codeLimit),Field("code.entry",30,r.entry),
    Field("slice.version",32,r.sliceVersion),Field("slice.firstOrdinal",33,r.firstOrdinal),
    Field("slice.patchesPerInstance",34,r.patchesPerInstance),Field("slice.firstInstance",35,r.firstInstance),
    Field("slice.instanceCount",36,r.instanceCount),
    Field("layout.inputVertices",40,r.layout.inputVertices),Field("layout.inputComponents",41,r.layout.inputComponents),
    Field("layout.outputVertices",42,r.layout.outputVertices),Field("layout.outputComponents",43,r.layout.outputComponents),
    Field("layout.patchComponents",44,r.layout.patchComponents),Field("vertexAbi",45,r.vertexAbi))++
    r.components.zipWithIndex.flatMap{case(c,i)=>Seq(Field(s"components[$i].kind",64+i,c.kind),
      Field(s"components[$i].component",64+i,c.component,2))}
}
class TessellationVertexSourceRequest extends Bundle {
  /** Scope from the accepted native owner, never from the decoded root. */
  val view=new VertexInputView;val privateBase=UInt(32.W);val privateLimit=UInt(33.W)
  /** Expected values captured from the accepted new GNT7/8/9 sequence. */
  val identity=new GraphicsWorkIdentity
  val sequenceIndex=UInt(32.W);val drawIndex=UInt(32.W);val taskIdBase=UInt(32.W);val patchCount=UInt(32.W)
  val layout=new TessellationPatchLayout
}
class TessellationVertexSourceReadCompletion extends Bundle {
  /** 0 captured, 1 malformed/bounds/scope, 2 descriptor memory, 4 cancelled. */
  val status=UInt(3.W);val faultAddress=UInt(32.W);val readBytes=UInt(10.W)
  val root=new TessellationVertexSourceRoot
}

/** Read the exact immutable GVS1 root over the existing tag-zero record route.
  * Retain an offered read, its real response and the completion on cancellation.
  * The enclosing owner must next read the original GND and match RX entry,
  * original draw ranges, VS ABI/resources and component provenance before
  * offering any source task. This reader does not do that work or enable a
  * production route. All referenced owners outlive the complete patch chain.
  */
class TessellationVertexSourceReader(config:GpuConfig) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationVertexSourceRequest))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TessellationVertexSourceReadCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  import TessellationVertexSourceLayout._
  private object State extends ChiselEnum {val idle,offer,waitReply,check,complete=Value}
  private val state=RegInit(State.idle);private val request=Reg(new TessellationVertexSourceRequest)
  private val root=Reg(new TessellationVertexSourceRoot)
  private val result=Reg(new TessellationVertexSourceReadCompletion)
  private val line=Reg(UInt(3.W));private val cancelled=RegInit(false.B);private val malformed=RegInit(false.B)
  private val fieldsMapped=fields(root)
  private val masks=Array.fill(words)(BigInt(0))
  header.indices.foreach(i=>masks(i)=(BigInt(1)<<32)-1)
  fieldsMapped.foreach{f=>
    val mask=((BigInt(1)<<f.width)-1)<<f.shift
    (0 until f.words).foreach(i=>masks(f.word+i)|=(mask>>(32*i))&((BigInt(1)<<32)-1))
  }
  private val raw=io.memoryResponse.bits.readData
  private val invalid=(0 until words).map{w=>
    val v=raw((w%16)*32+31,(w%16)*32)
    line===(w/16).U && (v & (((BigInt(1)<<32)-1)^masks(w)).U(32.W)).orR
  }++header.zipWithIndex.map{case(v,w)=>line===(w/16).U && raw((w%16)*32+31,(w%16)*32)=/=v.U}
  private val malformedLine=invalid.reduce(_ || _)
  for(f<-fieldsMapped){
    val lo=(f.word%16)*32+f.shift
    when(state===State.waitReply && io.memoryResponse.fire && !io.memoryResponse.bits.fault && line===(f.word/16).U){
      f.value:=raw(lo+f.width-1,lo)
    }
  }
  private val v=io.submit.bits.view;private val end=v.base.pad(33)+&bytes.U
  private val legalView=v.base(5,0)===0.U && v.allocationBase(5,0)===0.U && v.allocationLimit(5,0)===0.U &&
    v.allocationLimit>v.allocationBase && v.allocationLimit<=(BigInt(1)<<32).U && v.base>=v.allocationBase &&
    v.limit<=v.allocationLimit && end===v.limit && end<=(BigInt(1)<<32).U &&
    io.submit.bits.privateBase(5,0)===0.U && io.submit.bits.privateLimit(5,0)===0.U &&
    io.submit.bits.privateLimit>io.submit.bits.privateBase && io.submit.bits.privateLimit<=(BigInt(1)<<32).U &&
    v.base>=io.submit.bits.privateBase && end<=io.submit.bits.privateLimit
  private val l=root.layout
  private val layoutLegal=l.inputVertices>=1.U && l.inputVertices<=32.U && l.outputVertices>=1.U && l.outputVertices<=32.U &&
    l.inputComponents<=64.U && l.outputComponents<=64.U && l.patchComponents<=120.U &&
    l.inputVertices*l.inputComponents<=2048.U && l.outputVertices*l.outputComponents+&l.patchComponents<=2048.U
  private val count=root.patchesPerInstance*root.instanceCount
  private val ranges=root.sliceVersion===1.U && count===root.patchCount.pad(64) &&
    Mux(root.patchCount===0.U,root.firstOrdinal===0.U && root.patchesPerInstance===0.U &&
      root.firstInstance===0.U && root.instanceCount===0.U,
      root.taskIdBase.pad(65)+root.patchCount.pad(65)<=(BigInt(1)<<32).U &&
      root.firstOrdinal.pad(65)+(root.patchesPerInstance*l.inputVertices).pad(65)<=(BigInt(1)<<32).U &&
      root.firstInstance+&root.instanceCount<=(BigInt(1)<<32).U)
  private val references=root.drawBase(5,0)===0.U && root.drawLimit===root.drawBase.pad(33)+&2048.U &&
    root.drawBase>=request.privateBase && root.drawLimit<=request.privateLimit && root.drawLimit<=(BigInt(1)<<32).U &&
    root.codeBase(1,0)===0.U && root.codeLimit(1,0)===0.U && root.entry(1,0)===0.U &&
    root.codeLimit>root.codeBase && root.codeLimit-root.codeBase<=2048.U &&
    root.codeBase>=request.privateBase && root.codeLimit<=request.privateLimit && root.codeLimit<=(BigInt(1)<<32).U &&
    root.entry>=root.codeBase && root.entry<root.codeLimit
  private val scope=root.identity.asUInt===request.identity.asUInt && root.sequenceIndex===request.sequenceIndex &&
    root.drawIndex===request.drawIndex && root.taskIdBase===request.taskIdBase && root.patchCount===request.patchCount &&
    root.layout.asUInt===request.layout.asUInt
  private val maps=root.components.zipWithIndex.map{case(m,i)=>Mux(i.U>=l.inputComponents,m.asUInt===0.U,
    (m.kind===0.U && m.component<4.U) || (m.kind===1.U && m.component<64.U) ||
      (m.kind===2.U && m.component===0.U && root.vertexAbi===2.U))}.reduce(_ && _)
  io.submit.ready:=state===State.idle && !io.cancel
  io.memoryRequest.valid:=state===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=request.view.base+(line<<6);io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W));io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryResponse.ready:=state===State.waitReply && io.memoryResponse.bits.transactionId===0.U
  io.completion.valid:=state===State.complete;io.completion.bits:=result;io.busy:=state=/=State.idle
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    request:=io.submit.bits;root:=0.U.asTypeOf(root);result:=0.U.asTypeOf(result)
    line:=0.U;cancelled:=false.B;malformed:=false.B
    when(!legalView){result.status:=1.U;result.faultAddress:=io.submit.bits.view.base;state:=State.complete}
      .otherwise{state:=State.offer}
  }
  when(io.memoryRequest.fire){state:=State.waitReply}
  when(io.memoryResponse.fire){
    result.readBytes:=result.readBytes+64.U
    when(io.memoryResponse.bits.fault){result.status:=2.U;result.faultAddress:=request.view.base+(line<<6);state:=State.complete}
      .elsewhen(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .otherwise{
        when(malformedLine){malformed:=true.B}
        when(line===7.U){state:=State.check}.otherwise{line:=line+1.U;state:=State.offer}
      }
  }
  when(state===State.check){
    when(cancelled || io.cancel){result.status:=4.U}
      .elsewhen(malformed || !layoutLegal || !ranges || !references || !scope || !maps ||
        (root.vertexAbi=/=1.U && root.vertexAbi=/=2.U)){result.status:=1.U;result.faultAddress:=request.view.base}
      .otherwise{result.root:=root}
    state:=State.complete
  }
  when(io.completion.fire){state:=State.idle}
}
