package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.NativeLayeredVertexBinImageCompletion

class NativeLayeredCompletionCapture extends Bundle {
  val commandId=UInt(32.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W);val faultDraw=UInt(32.W)
  val descriptorReadBytes=UInt(64.W);val drawReadBytes=UInt(64.W)
  val issuedDraws=UInt(32.W);val completedDraws=UInt(32.W)
  val frame=new NativeLayeredVertexBinImageCompletion(VertexShaderStageConfig())
}
object NativeLayeredCompletionEncoding {
  case class Field(name:String,word:Int,value:Data){
    val width=value.getWidth;val words=(width+31)/32
    require(width>0 && width<=512 && word%16+words<=16)
  }
  def fields(d:NativeLayeredCompletionCapture):Seq[Field]={
    var word=3
    WindowPrimitiveRecordLayout.leaves(d).map{case(name,value)=>
      val n=(value.getWidth+31)/32
      if(word%16+n>16)word=(word+15)/16*16
      val f=Field(name,word,value);word+=n;f
    }
  }
  def check(d:NativeLayeredCompletionCapture,fs:Seq[Field]):Unit={
    require(fs.map(_.name).distinct.size==fs.size)
    require(fs.map(_.value).toSet==NativeTypedGraphicsLayout.leaves(d).toSet && fs.size==NativeTypedGraphicsLayout.leaves(d).size)
    val used=fs.flatMap(f=>f.word until f.word+f.words)
    require(used.distinct.size==used.size && used.forall(w=>w>=3 && w<NativeLayeredGraphicsLayout.completionWords))
  }
  def words(d:NativeLayeredCompletionCapture):Vec[UInt]={
    val result=Wire(Vec(NativeLayeredGraphicsLayout.completionWords,UInt(32.W)));result.foreach(_:=0.U)
    result(0):=NativeLayeredGraphicsLayout.completionMagic.U
    result(1):=NativeLayeredGraphicsLayout.completionBytes.U;result(2):=NativeLayeredGraphicsLayout.completionVersion.U
    val fs=fields(d);check(d,fs)
    for(f<-fs){val raw=f.value match {case s:SInt=>s.pad(f.words*32).asUInt;case b:Bool=>Cat(0.U((f.words*32-1).W),b);case _=>f.value.asUInt.pad(f.words*32)}
      for(i<-0 until f.words)result(f.word+i):=raw(32*i+31,32*i)}
    result
  }
}

/** Serial, acknowledged GNY2 publication. Snapshot the exact accepted owner
  * tuple once; neither a late cancel nor a new context can redirect its writes.
  * One outstanding line survives stalls/faults until its real reply drains.
  * The old coarse result is offered only after the last successful write ACK,
  * or after a precise write-fault result. Failed preflight never grants a view
  * and therefore performs no completion writes.
  */
class NativeLayeredCompletionWriter(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Module {
  require(p.input.attributes==16 && p.input.bindings==16 && p.varyings==16,
    "GNY2 has fixed complete native shader/record capacities")
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new NativeBinnedGraphicsCompletion(commandIdWidth,p)))
    val frame=Input(new NativeLayeredVertexBinImageCompletion(p))
    val view=Input(new NativeLayeredCompletionView);val viewValid=Input(Bool())
    val out=Decoupled(new NativeBinnedGraphicsCompletion(commandIdWidth,p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,complete=Value}
  private val phase=RegInit(State.idle);private val capture=Reg(new NativeLayeredCompletionCapture)
  private val result=Reg(new NativeBinnedGraphicsCompletion(commandIdWidth,p))
  private val view=Reg(new NativeLayeredCompletionView)
  private val line=RegInit(0.U(log2Ceil(NativeLayeredGraphicsLayout.completionBytes/64).W))
  private val words=NativeLayeredCompletionEncoding.words(capture)
  private val lines=VecInit(words.grouped(16).map(ws=>Cat(ws.reverse)).toSeq)
  private val address=view.base.pad(33)+(line<<6)
  io.in.ready:=phase===State.idle;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.complete;io.out.bits:=result
  io.memoryRequest.valid:=phase===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=address;io.memoryRequest.bits.writeData:=lines(line)
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W));io.memoryRequest.bits.isWrite:=true.B
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryResponse.ready:=phase===State.waitReply
  when(io.in.fire){
    result:=io.in.bits;view:=io.view;line:=0.U
    capture.commandId:=io.in.bits.commandId;capture.identity:=io.in.bits.identity;capture.status:=io.in.bits.status
    capture.faultStage:=io.in.bits.faultStage;capture.faultAddress:=io.in.bits.faultAddress;capture.faultDraw:=io.in.bits.faultDraw
    capture.descriptorReadBytes:=io.in.bits.descriptorReadBytes;capture.drawReadBytes:=io.in.bits.drawReadBytes
    capture.issuedDraws:=io.in.bits.issuedDraws;capture.completedDraws:=io.in.bits.completedDraws;capture.frame:=io.frame
    when(io.viewValid){assert(NativeLayeredGraphicsLayout.completionLegal(io.view),"GNY2 writer acquired an unadmitted view")
      phase:=State.offer}.otherwise{assert(io.in.bits.status=/=0.U,"successful GNJ2 omitted its completion lease");phase:=State.complete}
  }
  when(io.memoryRequest.fire){assert(address+64.U<=view.limit,"GNY2 write escaped its admitted view");phase:=State.waitReply}
  when(io.memoryResponse.fire){
    assert(io.memoryResponse.bits.transactionId===0.U,"GNY2 response belonged to a different writer")
    when(io.memoryResponse.bits.fault){result.status:=3.U;result.faultStage:=2.U;result.faultAddress:=address(31,0);phase:=State.complete}
      .elsewhen(line===(NativeLayeredGraphicsLayout.completionBytes/64-1).U){phase:=State.complete}
      .otherwise{line:=line+1.U;phase:=State.offer}
  }
  when(io.out.fire){phase:=State.idle}
}
