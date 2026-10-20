package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Post-transform data is owned by the pass, independently of VS scratch.
  * state names the immutable fragment/test/blend state owned by that pass.
  * sequence is the pass-wide bin order, distinct from the draw-local 64-bit
  * primitive sequence and clipped-part index retained in primitive.
  */
class WindowPrimitiveRecord(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new WindowPrimitive(p)
  val raster=new Fp32RasterState
  val state=UInt(32.W);val sequence=UInt(32.W);val token=UInt(32.W)
}

/** Internal, configuration-specific word format. Each leaf has its own
  * little-endian 32-bit word(s), in lexicographic Record / numeric Vec order;
  * no field crosses a line. Narrow signed values are sign-extended. This is
  * deliberately independent of asUInt packing, and is not a host command ABI.
  * Header: magic, byte size, version, varying capacity, context, pass,
  * generation, handle, then eight zero words. All unused words are zero.
  */
object WindowPrimitiveRecordLayout {
  val magic=BigInt("47575001",16)
  case class Field(name: String,word: Int,value: Data) {
    val width=value.getWidth;val words=(width+31)/32
    require(width>0 && width<=64)
  }
  def leaves(d: Data,prefix: String=""): Seq[(String,Data)]=d match {
    case v: Vec[_]=>v.zipWithIndex.toSeq.flatMap{case(x,i)=>leaves(x,s"$prefix[$i]")}
    case r: Record=>r.elements.toSeq.sortBy(_._1).flatMap{case(n,x)=>leaves(x,if(prefix.isEmpty)n else s"$prefix.$n")}
    case x=>Seq(prefix->x)
  }
  def fields(d: WindowPrimitiveRecord): Seq[Field]={
    var word=16
    val (added,legacy)=leaves(d).partition{case(name,_)=>name.startsWith("raster.polygonOffset")}
    (legacy++added).map{case(n,x)=>val count=(x.getWidth+31)/32
      if(word%16+count>16)word=(word+15)/16*16
      val f=Field(n,word,x);word+=count;f}
  }
  def lineCount(d: WindowPrimitiveRecord): Int={val fs=fields(d);(fs.last.word+fs.last.words+15)/16}
}

class WindowPrimitiveRecordRequest(p: VertexShaderStageConfig) extends Bundle {
  val arena=new PrimitiveStateDescriptor
  /** Record ordinal, not a byte offset or a legacy 64-byte line handle. */
  val handle=UInt(32.W);val write=Bool();val record=new WindowPrimitiveRecord(p)
}
class WindowPrimitiveRecordResult(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val write=Bool()
  /** Graphics status: 0 success, 1 bounds, 3 memory, 4 cancel, 7 encoding. */
  val status=UInt(3.W);val lines=UInt(32.W)
  /** Present only after a successful complete read; never a partial record. */
  val recordValid=Bool();val record=new WindowPrimitiveRecord(p)
}

/** One bounded record transfer; instantiate independent readers per engine.
  * A store is published only after every line acknowledgement. An errored
  * store may have modified memory and invalidates the enclosing pass. Reads
  * validate header, all padding and the complete identity before publication.
  * Cancellation drains an already offered request and its acknowledgement;
  * held result bits survive cancellation. The pass owner must retain the
  * arena and state allocation until all readers/tiles have retired.
  */
class WindowPrimitiveRecordTransfer(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    memoryTransactions: Int=1,transactionId: Int=0) extends Module {
  require(config.xLen==32 && transactionId>=0 && transactionId<memoryTransactions)
  val io=IO(new Bundle {
    val request=Flipped(Decoupled(new WindowPrimitiveRecordRequest(p)))
    val result=Decoupled(new WindowPrimitiveRecordResult(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val descriptor=Reg(new WindowPrimitiveRecordRequest(p))
  private val snapshot=Reg(new WindowPrimitiveRecord(p))
  private val fs=WindowPrimitiveRecordLayout.fields(snapshot)
  val recordLines=WindowPrimitiveRecordLayout.lineCount(snapshot)
  val recordBytes=recordLines*64
  private object State extends ChiselEnum {val idle,validate,offer,waitReply,verify,finish,complete=Value}
  private val phase=RegInit(State.idle);private val line=RegInit(0.U(log2Ceil(recordLines).max(1).W))
  private val status=RegInit(0.U(3.W));private val lines=RegInit(0.U(32.W))
  private val badEncoding=RegInit(false.B);private val address=Reg(UInt(33.W))
  private val result=Reg(new WindowPrimitiveRecordResult(p))
  private val words=Wire(Vec(recordLines*16,UInt(32.W)));words.foreach(_:=0.U)
  words(0):=WindowPrimitiveRecordLayout.magic.U;words(1):=recordBytes.U;words(2):=1.U;words(3):=p.varyings.U
  words(4):=descriptor.arena.identity.context;words(5):=descriptor.arena.identity.pass
  words(6):=descriptor.arena.identity.generation;words(7):=descriptor.handle
  for(f<-fs) {
    val bits=f.value match {case s: SInt=>s.pad(f.words*32).asUInt;case b: Bool=>Cat(0.U((f.words*32-1).W),b);case x=>x.asUInt.pad(f.words*32)}
    for(w<-0 until f.words)words(f.word+w):=bits(32*w+31,32*w)
  }
  private val occupied=Set(0,1,2,3,4,5,6,7)++fs.flatMap(f=>f.word until f.word+f.words)
  private val stop=status=/=0.U || io.cancel
  io.request.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.result.valid:=phase===State.complete;io.result.bits:=result
  io.memoryRequest.valid:=phase===State.offer
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=(address+(line<<6))(31,0)
  io.memoryRequest.bits.writeData:=VecInit((0 until recordLines).map(i=>Cat(words.slice(i*16,i*16+16).reverse)))(line)
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W));io.memoryRequest.bits.isWrite:=descriptor.write
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryRequest.bits.transactionId:=transactionId.U
  io.memoryResponse.ready:=phase===State.waitReply
  when(io.cancel && phase=/=State.idle && phase=/=State.complete && status===0.U){status:=4.U}
  switch(phase) {
    is(State.idle){when(io.request.fire){descriptor:=io.request.bits;snapshot:=io.request.bits.record
      status:=0.U;lines:=0.U;line:=0.U;badEncoding:=false.B;phase:=State.validate}}
    is(State.validate){
      val start=descriptor.arena.base.pad(64)+descriptor.handle.pad(64)*recordBytes.U(64.W)
      val end=start+recordBytes.U
      val legal=descriptor.arena.version===1.U && descriptor.arena.base(5,0)===0.U && descriptor.arena.limit(5,0)===0.U &&
        descriptor.arena.limit<=(BigInt(1)<<32).U && descriptor.arena.limit>=descriptor.arena.base && end<=descriptor.arena.limit &&
        (!descriptor.write || snapshot.primitive.clipped.primitive.identity.asUInt===descriptor.arena.identity.asUInt)
      address:=start(32,0)
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=1.U;phase:=State.finish}.otherwise{phase:=State.offer}
    }
    is(State.offer){when(io.memoryRequest.fire){phase:=State.waitReply}}
    is(State.waitReply){when(io.memoryResponse.fire){
      assert(io.memoryResponse.bits.transactionId===transactionId.U,"record response tag is not owned")
      lines:=lines+1.U
      when(io.memoryResponse.bits.fault && status===0.U && !io.cancel){status:=3.U}
      when(!descriptor.write && !io.memoryResponse.bits.fault){
        val incoming=io.memoryResponse.bits.readData.asTypeOf(Vec(16,UInt(32.W)))
        val errors=collection.mutable.ArrayBuffer.empty[Bool]
        for(w<-0 until recordLines*16 if !occupied(w))errors+=((line===(w/16).U) && incoming(w%16)=/=0.U)
        for(w<-0 until 8)errors+=((line===0.U) && incoming(w)=/=words(w))
        for(f<-fs){
          val raw=Cat((0 until f.words).reverse.map(w=>incoming((f.word+w)%16)))
          val value=raw(f.width-1,0)
          val canonical=f.value match {case _: SInt=>value.asSInt.pad(f.words*32).asUInt;case _=>value.pad(f.words*32)}
          errors+=((line===(f.word/16).U) && raw=/=canonical)
          when(line===(f.word/16).U){f.value:=value.asTypeOf(f.value)}
        }
        when(errors.reduce(_ || _)){badEncoding:=true.B}
      }
      when(stop || io.memoryResponse.bits.fault || line===(recordLines-1).U){phase:=State.verify}
        .otherwise{line:=line+1.U;phase:=State.offer}
    }}
    is(State.verify){when(!stop && !descriptor.write && (badEncoding || snapshot.primitive.clipped.primitive.identity.asUInt=/=descriptor.arena.identity.asUInt)){
      status:=7.U};phase:=State.finish}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.arena.identity;result.handle:=descriptor.handle
      result.write:=descriptor.write;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.lines:=lines
      when(!stop && !descriptor.write){result.recordValid:=true.B;result.record:=snapshot};phase:=State.complete}
    is(State.complete){when(io.result.fire){phase:=State.idle}}
  }
}
