package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.util.BlockPrefixAdder
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Single-layer selected image view in the enclosing profile's address space.
  * GNC2 translates these addresses through Sv32; legacy/internal profiles may
  * bind trusted physical addresses. Allocation bounds include every
  *64-byte bus line; limit/extent constrain the selected image's useful bytes.
  * Permission bits constrain descriptor access and do not replace MMU checks.
  */
class TypedImageView extends Bundle {
  val base=UInt(32.W);val limit=UInt(33.W);val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W)
  val imageWidth=UInt(32.W);val imageHeight=UInt(32.W);val rowPitch=UInt(32.W)
  val readable=Bool();val writable=Bool();val store=Bool()
}
class TypedImageResolve extends Bundle {
  val enable=Bool();val destination=new TypedImageView
}
class TypedTileImageDescriptor extends Bundle {
  val version=UInt(8.W);val pixels=new TypedTilePixelDescriptor
  /** Colors0..3, primary depth/stencil4, separate stencil5. */
  val views=Vec(6,new TypedImageView)
  /** Same format, same coordinates, single-sample destination; store must be true when enabled. */
  val resolves=Vec(6,new TypedImageResolve)
}
class TypedTileImageTransferCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultAddress=UInt(32.W)
  val loadBytes=UInt(64.W);val issuedStoreBytes=UInt(64.W);val storedBytes=UInt(64.W)
  val readLines=UInt(64.W);val writeLines=UInt(64.W)
  val issuedResolveBytes=UInt(64.W);val resolvedBytes=UInt(64.W);val resolvedPixels=UInt(64.W)
}
object TypedTileImageRules {
  def enabled(d: TypedTilePixelDescriptor): Vec[Bool]=VecInit(d.colors.colors.map(_.enabled)++Seq(d.depth.format=/=0.U,d.stencil.format=/=0.U))
  def bytes(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(v=>TypedColorFormat.bytes(v.format))++Seq(TypedDepthFormat.bytes(d.depth.format).pad(5),TypedDepthFormat.bytes(d.stencil.format).pad(5)))
  def offsets(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(_.offset)++Seq(d.depth.offset,d.stencil.offset))
  def loads(d: TypedTilePixelDescriptor): Vec[UInt]=VecInit(d.colors.colors.map(_.loadOp)++Seq(d.depth.loadOp,d.stencil.loadOp))
  def invalid(d: TypedTileImageDescriptor,p: TypedTileColorConfig): Bool={
    val tile=d.pixels.colors;val en=enabled(d.pixels);val size=bytes(d.pixels);val load=loads(d.pixels)
    val needed=d.views.zipWithIndex.map{case(v,i)=>en(i) && (v.store || load(i)===AttachmentLoadOp.load.U)}
    def end(v: TypedImageView,i: Int,mode: UInt): UInt=
      v.base.pad(68)+(v.imageHeight-1.U)*v.rowPitch+v.imageWidth*(1.U(5.W)<<mode)*size(i)
    def bad(v: TypedImageView,i: Int,mode: UInt,read: Bool,write: Bool): Bool={
      v.imageWidth===0.U || v.imageHeight===0.U || tile.originX<0.S || tile.originY<0.S ||
      tile.originX.asUInt.pad(34)+tile.tileWidth>v.imageWidth || tile.originY.asUInt.pad(34)+tile.tileHeight>v.imageHeight ||
      v.imageWidth*(1.U(5.W)<<mode)*size(i)>v.rowPitch ||
      v.allocationBase(5,0)=/=0.U || v.allocationLimit(5,0)=/=0.U || v.allocationLimit>(BigInt(1)<<32).U ||
      v.allocationLimit<=v.allocationBase || v.base<v.allocationBase || v.limit>v.allocationLimit || v.limit<=v.base || end(v,i,mode)>v.limit ||
      (read && !v.readable) || (write && !v.writable)
    }
    val ends=d.views.zipWithIndex.map{case(v,i)=>end(v,i,tile.sampleMode)}
    val invalidView=d.views.zipWithIndex.map{case(v,i)=>needed(i) && bad(v,i,tile.sampleMode,load(i)===AttachmentLoadOp.load.U,v.store)}.reduce(_ || _)
    val alias=(for(a<-0 until 6;b<-a+1 until 6)yield needed(a) && needed(b) && d.views(a).store && d.views(b).store && d.views(a).base<ends(b) && d.views(b).base<ends(a)).reduce(_ || _)
    val invalidResolve=d.resolves.zipWithIndex.map{case(r,i)=>r.enable && (!en(i) || !r.destination.store || bad(r.destination,i,0.U,false.B,true.B))}.reduce(_ || _)
    val resolveAlias=(for(a<-0 until 6;b<-0 until 6)yield {
      val r=d.resolves(a);val v=r.destination
      val sourceOverlap=needed(b) && v.base<ends(b) && d.views(b).base<end(v,a,0.U)
      val other=d.resolves(b);val outputOverlap=(a!=b).B && other.enable && v.base<end(other.destination,b,0.U) && other.destination.base<end(v,a,0.U)
      // Resolve reads can be lazy after source stores, so preserve their backing snapshot.
      val lateSourceAlias=d.resolves(a).enable && load(a)===AttachmentLoadOp.load.U && needed(a) && needed(b) && d.views(b).store && (a!=b).B &&
        d.views(a).base<ends(b) && d.views(b).base<ends(a)
      (r.enable && (sourceOverlap || outputOverlap)) || lateSourceAlias
    }).reduce(_ || _)
    d.version=/=1.U || TypedTilePixelRules.invalid(d.pixels,p) || invalidView || alias || invalidResolve || resolveAlias
  }
}

/** Serialized line gather/scatter in the profile's address space. Binding survives draw/refill work;
  * flush true exports stored views then resolves enabled destinations; flush
  * false closes after failure. Resolve source store/discard is independent. All line
  * acknowledgements, irrevocable private offers and refill replies drain before
  * completion, including resolve arithmetic. The enclosing owner must stop new
  * draws before starting flush. One-word resolve gather cache is read-only; cold
  * source words need not be installed or dirtied in the resident slot.
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
  private object State extends ChiselEnum {val idle,validate,prepareClear,waitClear,active,refillStart,refillLocate,refillAddress,refillByte,refillReply,scan,localOffer,localWait,storeAddress,storePrepare,busOffer,busWait,advance,resolveScan,resolveByte,resolveOffer,resolveWait,resolveStore,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTileImageDescriptor);private val result=Reg(new TypedTileImageTransferCompletion)
  private val status=RegInit(0.U(3.W));private val fault=RegInit(0.U(32.W));private val bound=RegInit(false.B)
  private val closing=RegInit(false.B);private val refilling=RegInit(false.B);private val view=RegInit(0.U(3.W))
  private val row=RegInit(0.U(5.W));private val offset=RegInit(0.U(17.W));private val byte=RegInit(0.U(4.W))
  // Admission limits rows to 8/16 and row strides to 16*4*16=1024 bytes.
  // Decode a refill's row in four bounded compare/subtract steps instead of a
  // descriptor-dependent combinational divide/remainder (including RGB strides).
  private val refillStride=Reg(UInt(11.W));private val refillBit=Reg(UInt(2.W))
  private val refillPhysicalBase=Reg(UInt(32.W))
  private val slotEnds=Reg(Vec(6,UInt(17.W)));private val slotStrides=Reg(Vec(6,UInt(11.W)))
  private val slotSizes=Reg(Vec(6,UInt(5.W)));private val slotActiveBytes=Reg(Vec(6,UInt(11.W)))
  private val imageOrigins=Reg(Vec(12,UInt(32.W)))
  private val word=Reg(UInt(p.addressBits.W));private val data=Reg(UInt(64.W));private val refillResult=Reg(new TypedTileColorRefillReply)
  private val localRequest=Reg(new TypedTileColorMemoryRequest(p));private val localOffered=RegInit(false.B);private val localPending=RegInit(false.B)
  private val busRequest=Reg(new ComputeMemoryRequest(config,64,1));private val busOffered=RegInit(false.B);private val busPending=RegInit(false.B)
  private val cacheValid=RegInit(false.B);private val cacheLine=Reg(UInt(32.W));private val cacheData=Reg(UInt(512.W))
  private val chunk=Reg(UInt(4.W))
  private val storeAddress=Reg(UInt(32.W));private val storeData=Reg(UInt(64.W))
  private val admission=Module(new StagedTypedImageAdmission(p));private val admissionIssued=RegInit(false.B)
  private val clearPreparation=Module(new PreparedTypedDepths(1))
  private val clearIndex=RegInit(false.B);private val clearPending=RegInit(false.B)
  private val clearWords=Reg(Vec(2,UInt(64.W)))
  private val clearView=Mux(clearIndex,descriptor.pixels.stencil,descriptor.pixels.depth)
  clearPreparation.io.in.valid:=phase===State.prepareClear && status===0.U && !io.cancel
  clearPreparation.io.in.bits.raw(0):=clearView.clearDepth
  clearPreparation.io.in.bits.format:=clearView.format;clearPreparation.io.in.bits.tag:=0.U
  when(clearPreparation.io.in.valid){clearPending:=true.B}
  when(clearPreparation.io.out.valid){clearPending:=false.B}
  private val resolving=RegInit(false.B);private val resolveWriting=RegInit(false.B)
  private val column=RegInit(0.U(5.W));private val sample=RegInit(0.U(2.W));private val recordByte=RegInit(0.U(5.W))
  private val samples=Reg(Vec(4,UInt(128.W)));private val resolved=Reg(UInt(128.W));private val written=RegInit(0.U(5.W))
  private val sourceValid=RegInit(false.B);private val sourceWord=Reg(UInt(p.addressBits.W));private val sourceData=Reg(UInt(64.W))
  private val issuedResolve=RegInit(0.U(64.W));private val resolvedBytes=RegInit(0.U(64.W));private val resolvedPixels=RegInit(0.U(64.W))
  private val resolver=Module(new TypedSampleResolve)
  resolver.io.in.valid:=phase===State.resolveOffer;resolver.io.in.bits:=0.U.asTypeOf(resolver.io.in.bits)
  resolver.io.in.bits.samples:=samples;resolver.io.in.bits.sampleMode:=descriptor.pixels.colors.sampleMode
  resolver.io.in.bits.depth:=view>=4.U;resolver.io.in.bits.format:=descriptor.pixels.colors.colors(view(1,0)).format
  resolver.io.out.ready:=phase===State.resolveWait;resolver.io.cancel:=io.cancel || status=/=0.U
  when(resolver.io.in.fire){phase:=State.resolveWait}
  when(resolver.io.out.fire){when(resolver.io.out.bits.status=/=0.U){status:=3.U}
    resolved:=resolver.io.out.bits.value;written:=0.U;phase:=State.resolveStore}
  private val refillUseful=RegInit(0.U(4.W));private val loadBytes=RegInit(0.U(64.W));private val issuedBytes=RegInit(0.U(64.W));private val storedBytes=RegInit(0.U(64.W))
  private val readLines=RegInit(0.U(64.W));private val writeLines=RegInit(0.U(64.W))
  // Preserve the original modulo64-bit accounting without long ripple carries
  // from response faults or format-dependent commit conditions.
  private def addCount(counter: UInt,amount: UInt): UInt={
    val add=Module(new BlockPrefixAdder(64))
    add.io.a:=counter;add.io.b:=amount.pad(64);add.io.carryIn:=false.B
    add.io.sum(63,0)
  }
  private val nextLoadBytes=addCount(loadBytes,refillUseful)
  private val nextIssuedBytes=addCount(issuedBytes,chunk);private val nextStoredBytes=addCount(storedBytes,chunk)
  private val nextIssuedResolve=addCount(issuedResolve,chunk);private val nextResolvedBytes=addCount(resolvedBytes,chunk)
  private val nextResolvedPixels=addCount(resolvedPixels,1.U)
  private val nextReadLines=addCount(readLines,1.U);private val nextWriteLines=addCount(writeLines,1.U)
  private val tile=descriptor.pixels.colors;private val enabled=TypedTileImageRules.enabled(descriptor.pixels)
  private val decodedSizes=TypedTileImageRules.bytes(descriptor.pixels);private val sizes=slotSizes;private val offsets=TypedTileImageRules.offsets(descriptor.pixels);private val loads=TypedTileImageRules.loads(descriptor.pixels)
  private val rowBytes=slotStrides(view)
  private val activeBytes=slotActiveBytes(view)
  private val mapping=Mux(resolveWriting,descriptor.resolves(view).destination,descriptor.views(view))
  private def addAddress(a: UInt,b: UInt): UInt={
    val add=Module(new BlockPrefixAdder(32))
    add.io.a:=a.pad(32)(31,0);add.io.b:=b.pad(32)(31,0);add.io.carryIn:=false.B
    add.io.sum(31,0)
  }
  private val origin=imageOrigins(Mux(resolveWriting,view+&6.U,view.pad(4)))
  private val rowAddress=addAddress(origin,row*mapping.rowPitch)
  private val wordAddress=addAddress(rowAddress,offset)
  private val address=addAddress(rowAddress,offset+&byte)
  private val refillAddress=addAddress(refillPhysicalBase,byte)
  private val line=Cat(refillAddress(31,6),0.U(6.W))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  admission.io.in.valid:=phase===State.validate && !admissionIssued && !stop
  admission.io.in.bits:=descriptor;admission.io.out.ready:=phase===State.validate && admissionIssued
  admission.io.cancel:=stop
  when(admission.io.in.fire){admissionIssued:=true.B}
  private def fail(code: UInt,address: UInt): Unit=when(status===0.U && !io.cancel){status:=code;fault:=address}
  private def offerBus(write: Boolean,n: UInt=0.U): Unit={
    busRequest:=0.U.asTypeOf(busRequest);busRequest.address:=(if(write)Cat(storeAddress(31,6),0.U(6.W))else line);busRequest.isWrite:=write.B;busRequest.sizeLog2:=6.U;busRequest.cachePolicy:=CachePolicy.uncached
    when(write.B){busRequest.writeData:=storeData.pad(512)<<(storeAddress(5,0)<<3)
      busRequest.byteMask:=(((1.U(65.W)<<n.pad(4)(3,0))-1.U)<<storeAddress(5,0))(63,0)}
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
    when(busRequest.isWrite){when(resolveWriting){issuedResolve:=nextIssuedResolve}.otherwise{issuedBytes:=nextIssuedBytes};writeLines:=nextWriteLines}.otherwise{readLines:=nextReadLines}}
  when(io.reply.fire){localPending:=false.B
    when(io.reply.bits.fault){fail(3.U,0.U)}
    when(!stop && !io.reply.bits.fault){
      val lazyDepth=Mux(view===4.U,clearWords(0),clearWords(1))
      when(resolving){
        when(io.reply.bits.available){sourceData:=io.reply.bits.data;sourceWord:=localRequest.address;sourceValid:=true.B;phase:=State.resolveByte}
          .elsewhen(loads(view)===AttachmentLoadOp.load.U){data:=0.U;byte:=0.U;refillUseful:=0.U;phase:=State.refillAddress}
          .elsewhen(view>=4.U){sourceData:=lazyDepth;sourceWord:=localRequest.address;sourceValid:=true.B;phase:=State.resolveByte}
          .otherwise{fail(3.U,0.U)}
      }.otherwise{
        when(io.reply.bits.available){data:=io.reply.bits.data}.otherwise{data:=lazyDepth}
        byte:=0.U
        when(io.reply.bits.dirty || loads(view)=/=AttachmentLoadOp.load.U){
          when(!io.reply.bits.available && view<4.U){fail(3.U,0.U)}.otherwise{phase:=State.storeAddress}
        }.otherwise{nextWord()}
      }
    }
  }
  when(io.memoryResponse.fire){assert(io.memoryResponse.bits.transactionId===0.U,"unowned physical image response");busPending:=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,busRequest.address)}
    when(busRequest.isWrite && !io.memoryResponse.bits.fault){when(resolveWriting){resolvedBytes:=nextResolvedBytes
      when(written+&byte+chunk>=sizes(view)){resolvedPixels:=nextResolvedPixels}
    }.otherwise{storedBytes:=nextStoredBytes}}
    when(!stop && !io.memoryResponse.bits.fault){
      when(busRequest.isWrite){byte:=byte+chunk;cacheValid:=false.B;phase:=State.storeAddress}
        .otherwise{cacheLine:=busRequest.address;cacheData:=io.memoryResponse.bits.readData;cacheValid:=true.B;phase:=State.refillByte}
    }
  }
  when(!stop || phase===State.idle || phase===State.validate || phase===State.prepareClear || phase===State.waitClear || phase===State.active || phase===State.refillReply || phase===State.finish || phase===State.complete){
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;fault:=0.U;bound:=false.B;closing:=false.B;refilling:=false.B;resolving:=false.B;resolveWriting:=false.B;sourceValid:=false.B
      admissionIssued:=false.B;clearIndex:=false.B
      issuedResolve:=0.U;resolvedBytes:=0.U;resolvedPixels:=0.U
      cacheValid:=false.B;loadBytes:=0.U;issuedBytes:=0.U;storedBytes:=0.U;readLines:=0.U;writeLines:=0.U;phase:=State.validate}}
    is(State.validate){
      when(admission.io.out.fire){admissionIssued:=false.B
        when(stop || admission.io.out.bits.cancelled){phase:=State.finish}
          .elsewhen(admission.io.out.bits.invalid){fail(1.U,0.U);phase:=State.finish}
          .otherwise{
            // Admission proves tileLog2 is3/4, mode is0/1/2 and every enabled slot
            // extent fits the configured backing. Capture geometry before use.
            imageOrigins:=admission.io.out.bits.origins
            val edge=Mux(tile.tileLog2===3.U,8.U(5.W),16.U(5.W))
            for(i<-0 until 6){
              val stride=(edge<<tile.sampleMode)*decodedSizes(i)
              slotSizes(i):=decodedSizes(i)
              slotActiveBytes(i):=(tile.tileWidth<<tile.sampleMode)*decodedSizes(i)
              slotStrides(i):=stride
              slotEnds(i):=offsets(i)+&Mux(tile.tileLog2===3.U,stride<<3,stride<<4)
            }
            phase:=State.prepareClear
          }
      }.elsewhen(stop && !admissionIssued){phase:=State.finish}
    }
    is(State.prepareClear){when(stop){phase:=State.finish}.otherwise{phase:=State.waitClear}}
    is(State.waitClear){when(clearPreparation.io.out.valid){
      clearWords(clearIndex):=TypedTilePixelRules.initialWord(clearView,clearPreparation.io.out.bits.values(0))
      when(stop){phase:=State.finish}
        .elsewhen(!clearIndex){clearIndex:=true.B;phase:=State.prepareClear}
        .otherwise{bound:=true.B;phase:=State.active}
    }}
    is(State.active){when(io.refill.fire){word:=io.refill.bits.address;refilling:=true.B;data:=0.U;byte:=0.U;refillUseful:=0.U;refillResult:=0.U.asTypeOf(refillResult);phase:=State.refillStart}
      when(io.flush.fire){closing:=true.B;view:=0.U;row:=0.U;offset:=0.U;cacheValid:=false.B
        phase:=Mux(io.flush.bits && !stop,State.scan,State.finish)}}
    is(State.refillStart){
      val matches=VecInit((0 until 6).map(i=>enabled(i) && (word<<3)>=offsets(i) && (word<<3)<slotEnds(i)))
      val selected=PriorityEncoder(matches)
      val selectedRowBytes=slotStrides(selected)
      when(!matches.asUInt.orR || loads(selected)=/=AttachmentLoadOp.load.U){fail(3.U,0.U)}.otherwise{
        view:=selected;row:=0.U;offset:=(word<<3)-offsets(selected)
        refillStride:=selectedRowBytes;refillBit:=3.U;phase:=State.refillLocate}
    }
    is(State.refillLocate){
      val stride=(refillStride<<refillBit)(13,0)
      when(offset>=stride){offset:=offset-stride;row:=row|(1.U(5.W)<<refillBit)(4,0)}
      when(refillBit===0.U){phase:=State.refillAddress}.otherwise{refillBit:=refillBit-1.U}
    }
    is(State.refillAddress){refillPhysicalBase:=wordAddress;phase:=State.refillByte}
    is(State.refillByte){when(byte===8.U){
      when(resolving){sourceData:=data;sourceWord:=localRequest.address;sourceValid:=true.B;loadBytes:=nextLoadBytes;phase:=State.resolveByte}
        .otherwise{refillResult.data:=data;refillResult.fault:=false.B;phase:=State.refillReply}}
      .elsewhen(row>=tile.tileHeight || offset+byte>=activeBytes){byte:=byte+1.U}
      .elsewhen(cacheValid && cacheLine===line){
        val next=(cacheData>>(refillAddress(5,0)<<3))(7,0);data:=data|(next.pad(64)<<(byte<<3));refillUseful:=refillUseful+1.U;byte:=byte+1.U
      }.otherwise{offerBus(false)}}
    is(State.refillReply){when(io.refillReply.fire){when(!refillResult.fault){loadBytes:=nextLoadBytes};refilling:=false.B;phase:=State.active}}
    is(State.scan){when(view===6.U){resolving:=true.B;view:=0.U;row:=0.U;column:=0.U;sourceValid:=false.B;phase:=State.resolveScan}
      .elsewhen(!enabled(view) || !mapping.store){view:=view+1.U;row:=0.U;offset:=0.U}
      .otherwise{localRequest:=0.U.asTypeOf(localRequest);localRequest.address:=(offsets(view)+row*rowBytes+offset)>>3;localOffered:=true.B;phase:=State.localOffer}}
    is(State.storeAddress){storeAddress:=address;storeData:=data>>(byte<<3);phase:=State.storePrepare}
    is(State.storePrepare){val remaining=Mux(resolveWriting,sizes(view)-written,activeBytes-offset);val useful=Mux(remaining>=8.U,8.U,remaining)
      when(byte>=useful){
        when(resolveWriting){written:=written+useful;resolveWriting:=false.B;phase:=State.resolveStore}.otherwise{nextWord()}}.otherwise{val left=useful-byte;val lineLeft=64.U(7.W)-storeAddress(5,0);offerBus(true,Mux(left<lineLeft,left,lineLeft))}}
    is(State.resolveScan){when(view===6.U){phase:=State.finish}
      .elsewhen(!descriptor.resolves(view).enable){view:=view+1.U;row:=0.U;column:=0.U;sourceValid:=false.B}
      .otherwise{samples:=0.U.asTypeOf(samples);sample:=0.U;recordByte:=0.U;phase:=State.resolveByte}}
    is(State.resolveByte){
      val sourceOffset=((column<<tile.sampleMode)+&sample)*sizes(view)+recordByte
      val localByte=offsets(view)+row*rowBytes+sourceOffset;val selectedWord=(localByte>>3)(p.addressBits-1,0)
      val rawOnly=view>=4.U || TypedColorFormat.kind(descriptor.pixels.colors.colors(view(1,0)).format)=/=0.U
      when(recordByte===sizes(view)){
        when(rawOnly || sample+&1.U===(1.U(3.W)<<tile.sampleMode)){phase:=State.resolveOffer}
          .otherwise{sample:=sample+1.U;recordByte:=0.U}
      }.elsewhen(sourceValid && sourceWord===selectedWord){
        val next=(sourceData>>(localByte(2,0)<<3))(7,0)
        samples(sample):=samples(sample)|(next.pad(128)<<(recordByte(3,0)<<3));recordByte:=recordByte+1.U
      }.otherwise{
        offset:=Cat(sourceOffset.pad(17)(16,3),0.U(3.W));localRequest:=0.U.asTypeOf(localRequest)
        localRequest.address:=selectedWord;localOffered:=true.B;phase:=State.localOffer
      }
    }
    is(State.resolveStore){when(written>=sizes(view)){
      when(column+1.U===tile.tileWidth){column:=0.U;sourceValid:=false.B
        when(row+1.U===tile.tileHeight){row:=0.U;view:=view+1.U}.otherwise{row:=row+1.U}}
        .otherwise{column:=column+1.U}
      phase:=State.resolveScan
    }.otherwise{resolveWriting:=true.B;offset:=column*sizes(view)+written;byte:=0.U
      data:=(resolved>>(written(3,0)<<3))(63,0);phase:=State.storeAddress}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=tile.identity;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultAddress:=fault
      result.issuedResolveBytes:=issuedResolve;result.resolvedBytes:=resolvedBytes;result.resolvedPixels:=resolvedPixels
      result.loadBytes:=loadBytes;result.issuedStoreBytes:=issuedBytes;result.storedBytes:=storedBytes;result.readLines:=readLines;result.writeLines:=writeLines;phase:=State.complete}
    is(State.complete){when(io.completion.fire){bound:=false.B;phase:=State.idle}}
  }
  }
  when(active && stop && !localOffered && !localPending && !busOffered && !busPending && !resolver.io.busy && phase=/=State.resolveOffer && phase=/=State.finish && phase=/=State.refillReply){
    when(refilling){refillResult.data:=0.U;refillResult.fault:=true.B;phase:=State.refillReply}
      .elsewhen(closing){phase:=State.finish}
  }
  when(io.completion.valid){assert(!clearPending && !admission.io.busy && !localOffered && !localPending && !busOffered && !busPending && !refilling && !resolver.io.busy,"image transfer completion escaped owned traffic")}
}
