package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Physical, single-layer selected image view. Allocation bounds include every
  *64-byte bus line; limit/extent constrain the selected image's useful bytes.
  * Permission bits are trusted allocation metadata, not an MMU replacement.
  */
class TypedImageView extends Bundle {
  val base=UInt(32.W);val limit=UInt(33.W);val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W)
  val imageWidth=UInt(32.W);val imageHeight=UInt(32.W);val rowPitch=UInt(32.W)
  val readable=Bool();val writable=Bool();val store=Bool()
}
class TypedTileImageDescriptor extends Bundle {
  val version=UInt(8.W);val pixels=new TypedTilePixelDescriptor
  /** Colors0..3, primary depth/stencil4, separate stencil5. */
  val views=Vec(6,new TypedImageView)
}
class TypedTileImageTransferCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultAddress=UInt(32.W)
  val loadBytes=UInt(64.W);val issuedStoreBytes=UInt(64.W);val storedBytes=UInt(64.W)
  val readLines=UInt(64.W);val writeLines=UInt(64.W)
}
object TypedTileImageRules {
  def enabled(d: TypedTilePixelDescriptor): Vec[Bool]=VecInit(d.colors.colors.map(_.enabled)++Seq(d.depth.format=/=0.U,d.stencil.format=/=0.U))
  def bytes(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(v=>TypedColorFormat.bytes(v.format))++Seq(TypedDepthFormat.bytes(d.depth.format).pad(5),TypedDepthFormat.bytes(d.stencil.format).pad(5)))
  def offsets(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(_.offset)++Seq(d.depth.offset,d.stencil.offset))
  def loads(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(_.loadOp)++Seq(d.depth.loadOp,d.stencil.loadOp))
  def invalid(d: TypedTileImageDescriptor,p: TypedTileColorConfig): Bool={
    val tile=d.pixels.colors;val en=enabled(d.pixels);val size=bytes(d.pixels);val load=loads(d.pixels)
    val needed=d.views.zipWithIndex.map{case(v,i)=>en(i) && (v.store || load(i)===AttachmentLoadOp.load.U)}
    val end=d.views.zipWithIndex.map{case(v,i)=>v.base.pad(68)+(v.imageHeight-1.U)*v.rowPitch+v.imageWidth*(1.U(5.W)<<tile.sampleMode)*size(i)}
    val invalidView=d.views.zipWithIndex.map{case(v,i)=>needed(i) && (
      v.imageWidth===0.U || v.imageHeight===0.U || tile.originX<0.S || tile.originY<0.S ||
      tile.originX.asUInt.pad(34)+tile.tileWidth>v.imageWidth || tile.originY.asUInt.pad(34)+tile.tileHeight>v.imageHeight ||
      v.imageWidth*(1.U(5.W)<<tile.sampleMode)*size(i)>v.rowPitch ||
      v.allocationBase(5,0)=/=0.U || v.allocationLimit(5,0)=/=0.U || v.allocationLimit>(BigInt(1)<<32).U ||
      v.allocationLimit<=v.allocationBase || v.base<v.allocationBase || v.limit>v.allocationLimit || v.limit<=v.base || end(i)>v.limit ||
      (load(i)===AttachmentLoadOp.load.U && !v.readable) || (v.store && !v.writable))}.reduce(_ || _)
    val alias=(for(a<-0 until 6;b<-a+1 until 6)yield needed(a) && needed(b) && d.views(a).store && d.views(b).store && d.views(a).base<end(b) && d.views(b).base<end(a)).reduce(_ || _)
    d.version=/=1.U || TypedTilePixelRules.invalid(d.pixels,p) || invalidView || alias
  }
}

/** Serialized physical line gather/scatter. Binding survives draw/refill work;
  * flush true exports stored views, flush false closes after failure. All line
  * acknowledgements, irrevocable private offers and refill replies drain before
  * completion. The enclosing owner must stop new draws before starting flush.
  */
class TypedTileImageTransfer(config: GpuConfig=GpuConfig(),p: TypedTileColorConfig=TypedTileColorConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTileImageDescriptor));val flush=Flipped(Decoupled(Bool()))
    val refill=Flipped(Decoupled(new TypedTileColorRefill(p)));val refillReply=Decoupled(new TypedTileColorRefillReply)
    val memory=Decoupled(new TypedTileColorMemoryRequest(p));val reply=Flipped(Decoupled(new TypedTileColorMemoryReply))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TypedTileImageTransferCompletion);val cancel=Input(Bool());val cancelSource=Output(Bool())
    val bound=Output(Bool());val busy=Output(Bool());val status=Output(UInt(3.W));val faultAddress=Output(UInt(32.W))
  })
  private object State extends ChiselEnum {val idle,validate,active,refillStart,refillByte,refillReply,scan,localOffer,localWait,storePrepare,busOffer,busWait,advance,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTileImageDescriptor);private val result=Reg(new TypedTileImageTransferCompletion)
  private val status=RegInit(0.U(3.W));private val fault=RegInit(0.U(32.W));private val bound=RegInit(false.B)
  private val closing=RegInit(false.B);private val refilling=RegInit(false.B);private val view=RegInit(0.U(3.W))
  private val row=RegInit(0.U(5.W));private val offset=RegInit(0.U(17.W));private val byte=RegInit(0.U(4.W))
  private val word=Reg(UInt(p.addressBits.W));private val data=Reg(UInt(64.W));private val refillResult=Reg(new TypedTileColorRefillReply)
  private val localRequest=Reg(new TypedTileColorMemoryRequest(p));private val localOffered=RegInit(false.B);private val localPending=RegInit(false.B)
  private val busRequest=Reg(new ComputeMemoryRequest(config,64,1));private val busOffered=RegInit(false.B);private val busPending=RegInit(false.B)
  private val cacheValid=RegInit(false.B);private val cacheLine=Reg(UInt(32.W));private val cacheData=Reg(UInt(512.W))
  private val chunk=Reg(UInt(4.W))
  private val refillUseful=RegInit(0.U(4.W));private val loadBytes=RegInit(0.U(64.W));private val issuedBytes=RegInit(0.U(64.W));private val storedBytes=RegInit(0.U(64.W))
  private val readLines=RegInit(0.U(64.W));private val writeLines=RegInit(0.U(64.W))
  private val tile=descriptor.pixels.colors;private val enabled=TypedTileImageRules.enabled(descriptor.pixels)
  private val sizes=TypedTileImageRules.bytes(descriptor.pixels);private val offsets=TypedTileImageRules.offsets(descriptor.pixels);private val loads=TypedTileImageRules.loads(descriptor.pixels)
  private val rowBytes=(Mux(tile.tileLog2===3.U,8.U(5.W),16.U(5.W))<<tile.sampleMode)*sizes(view)
  private val activeBytes=(tile.tileWidth<<tile.sampleMode)*sizes(view)
  private val mapping=descriptor.views(view)
  private val address=(mapping.base+(tile.originY.asUInt+&row)*mapping.rowPitch+
    tile.originX.asUInt*(1.U(5.W)<<tile.sampleMode)*sizes(view)+offset+byte)(31,0)
  private val line=Cat(address(31,6),0.U(6.W))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private def fail(code: UInt,address: UInt): Unit=when(status===0.U && !io.cancel){status:=code;fault:=address}
  private def offerBus(write: Boolean,n: UInt=0.U): Unit={
    busRequest:=0.U.asTypeOf(busRequest);busRequest.address:=line;busRequest.isWrite:=write.B;busRequest.sizeLog2:=6.U;busRequest.cachePolicy:=CachePolicy.uncached
    when(write.B){busRequest.writeData:=(data>>(byte<<3)).pad(512)<<(address(5,0)<<3)
      busRequest.byteMask:=(((1.U(65.W)<<n.pad(4)(3,0))-1.U)<<address(5,0))(63,0)}
    chunk:=n;busOffered:=true.B;phase:=State.busOffer
  }
  private def nextWord(): Unit={
    when(offset+8.U>=activeBytes){offset:=0.U;when(row+1.U===tile.tileHeight){row:=0.U;view:=view+1.U}.otherwise{row:=row+1.U}}
      .otherwise{offset:=offset+8.U}
    phase:=State.scan
  }
  io.begin.ready:=phase===State.idle && !io.cancel;io.bound:=bound;io.busy:=phase=/=State.idle
  io.cancelSource:=active && stop;io.status:=status;io.faultAddress:=fault
  io.refill.ready:=phase===State.active;io.refillReply.valid:=phase===State.refillReply;io.refillReply.bits:=refillResult
  io.flush.ready:=phase===State.active && !io.refill.valid
  io.memory.valid:=localOffered;io.memory.bits:=localRequest;io.reply.ready:=localPending
  io.memoryRequest.valid:=busOffered;io.memoryRequest.bits:=busRequest;io.memoryResponse.ready:=busPending
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(active && io.cancel && status===0.U){status:=4.U}
  when(io.memory.fire){localOffered:=false.B;localPending:=true.B;phase:=State.localWait}
  when(io.memoryRequest.fire){busOffered:=false.B;busPending:=true.B;phase:=State.busWait
    when(busRequest.isWrite){issuedBytes:=issuedBytes+chunk;writeLines:=writeLines+1.U}.otherwise{readLines:=readLines+1.U}}
  when(io.reply.fire){localPending:=false.B
    when(io.reply.bits.fault){fail(3.U,0.U)}
    when(!stop && !io.reply.bits.fault){
      val lazyDepth=Mux(view===4.U,TypedTilePixelRules.initialWord(descriptor.pixels.depth),TypedTilePixelRules.initialWord(descriptor.pixels.stencil))
      when(io.reply.bits.available){data:=io.reply.bits.data}.otherwise{data:=lazyDepth}
      byte:=0.U
      when(io.reply.bits.dirty || loads(view)=/=AttachmentLoadOp.load.U){
        when(!io.reply.bits.available && view<4.U){fail(3.U,0.U)}.otherwise{phase:=State.storePrepare}
      }.otherwise{nextWord()}
    }
  }
  when(io.memoryResponse.fire){assert(io.memoryResponse.bits.transactionId===0.U,"unowned physical image response");busPending:=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,busRequest.address)}
    when(busRequest.isWrite && !io.memoryResponse.bits.fault){storedBytes:=storedBytes+chunk}
    when(!stop && !io.memoryResponse.bits.fault){
      when(busRequest.isWrite){byte:=byte+chunk;cacheValid:=false.B;phase:=State.storePrepare}
        .otherwise{cacheLine:=busRequest.address;cacheData:=io.memoryResponse.bits.readData;cacheValid:=true.B;phase:=State.refillByte}
    }
  }
  when(!stop || phase===State.idle || phase===State.validate || phase===State.active || phase===State.refillReply || phase===State.finish || phase===State.complete){
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;fault:=0.U;bound:=false.B;closing:=false.B;refilling:=false.B
      cacheValid:=false.B;loadBytes:=0.U;issuedBytes:=0.U;storedBytes:=0.U;readLines:=0.U;writeLines:=0.U;phase:=State.validate}}
    is(State.validate){when(TypedTileImageRules.invalid(descriptor,p)){fail(1.U,0.U);phase:=State.finish}
      .elsewhen(stop){phase:=State.finish}.otherwise{bound:=true.B;phase:=State.active}}
    is(State.active){when(io.refill.fire){word:=io.refill.bits.address;refilling:=true.B;data:=0.U;byte:=0.U;refillUseful:=0.U;refillResult:=0.U.asTypeOf(refillResult);phase:=State.refillStart}
      when(io.flush.fire){closing:=true.B;view:=0.U;row:=0.U;offset:=0.U;cacheValid:=false.B
        phase:=Mux(io.flush.bits && !stop,State.scan,State.finish)}}
    is(State.refillStart){
      val matches=VecInit((0 until 6).map(i=>enabled(i) && (word<<3)>=offsets(i) && (word<<3)<offsets(i)+&(((1.U(6.W)<<tile.tileLog2)<<tile.sampleMode)*sizes(i)*(1.U(6.W)<<tile.tileLog2))))
      val selected=PriorityEncoder(matches)
      val selectedRowBytes=((1.U(6.W)<<tile.tileLog2)<<tile.sampleMode)*sizes(selected)
      when(!matches.asUInt.orR || loads(selected)=/=AttachmentLoadOp.load.U){fail(3.U,0.U)}.otherwise{
        val local=(word<<3)-offsets(selected);view:=selected;row:=local/selectedRowBytes;offset:=local%selectedRowBytes;phase:=State.refillByte}
    }
    is(State.refillByte){when(byte===8.U){refillResult.data:=data;refillResult.fault:=false.B;phase:=State.refillReply}
      .elsewhen(row>=tile.tileHeight || offset+byte>=activeBytes){byte:=byte+1.U}
      .elsewhen(cacheValid && cacheLine===line){
        val next=(cacheData>>(address(5,0)<<3))(7,0);data:=data|(next.pad(64)<<(byte<<3));refillUseful:=refillUseful+1.U;byte:=byte+1.U
      }.otherwise{offerBus(false)}}
    is(State.refillReply){when(io.refillReply.fire){when(!refillResult.fault){loadBytes:=loadBytes+refillUseful};refilling:=false.B;phase:=State.active}}
    is(State.scan){when(view===6.U){phase:=State.finish}
      .elsewhen(!enabled(view) || !mapping.store){view:=view+1.U;row:=0.U;offset:=0.U}
      .otherwise{localRequest:=0.U.asTypeOf(localRequest);localRequest.address:=(offsets(view)+row*rowBytes+offset)>>3;localOffered:=true.B;phase:=State.localOffer}}
    is(State.storePrepare){val useful=Mux(activeBytes-offset>=8.U,8.U,activeBytes-offset)
      when(byte>=useful){nextWord()}.otherwise{val left=useful-byte;val lineLeft=64.U(7.W)-address(5,0);offerBus(true,Mux(left<lineLeft,left,lineLeft))}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=tile.identity;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultAddress:=fault
      result.loadBytes:=loadBytes;result.issuedStoreBytes:=issuedBytes;result.storedBytes:=storedBytes;result.readLines:=readLines;result.writeLines:=writeLines;phase:=State.complete}
    is(State.complete){when(io.completion.fire){bound:=false.B;phase:=State.idle}}
  }
  }
  when(active && stop && !localOffered && !localPending && !busOffered && !busPending && phase=/=State.finish && phase=/=State.refillReply){
    when(refilling){refillResult.data:=0.U;refillResult.fault:=true.B;phase:=State.refillReply}
      .elsewhen(closing){phase:=State.finish}
  }
  when(io.completion.valid){assert(!localOffered && !localPending && !busOffered && !busPending && !refilling,"image transfer completion escaped owned traffic")}
}
