package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Resolved internal sampler state, independent of image storage. FP32 LOD
  * clamps retain their bits, including infinities and reversed clamp ranges.
  * NaNs are rejected by this internal contract. This is not a GL enum layout.
  */
class TextureSamplerState extends Bundle {
  val minFilter=UInt(3.W);val magFilter=UInt(1.W)
  val wrapS=UInt(2.W);val wrapT=UInt(2.W);val wrapR=UInt(2.W)
  val minLod=UInt(32.W);val maxLod=UInt(32.W)
  val compareEnable=Bool();val compareFunction=UInt(3.W)
}
object TextureSamplerState {
  val nearest=0;val linear=1;val nearestMipNearest=2;val linearMipNearest=3;val nearestMipLinear=4;val linearMipLinear=5
  val repeat=0;val clamp=1;val mirroredRepeat=2
  def requiresMips(s: TextureSamplerState): Bool=s.minFilter>=nearestMipNearest.U
  def nearestOnly(s: TextureSamplerState): Bool=s.magFilter===0.U &&
    (s.minFilter===nearest.U || s.minFilter===nearestMipNearest.U)
  def legal(s: TextureSamplerState): Bool=s.minFilter<=linearMipLinear.U &&
    s.wrapS<=mirroredRepeat.U && s.wrapT<=mirroredRepeat.U && s.wrapR<=mirroredRepeat.U &&
    !Fp32ViewportState.nan(s.minLod) && !Fp32ViewportState.nan(s.maxLod)
  /** Core ES3 storage filterability. Raw FP32 and integer formats require
    * nearest-only sampling; extensions can add a separately versioned policy.
    */
  def compatible(s: TextureSamplerState,format: UInt,expectedType: UInt): Bool={
    val filterable=TypedColorFormat.kind(format)===0.U && TypedColorFormat.encoding(format)=/=4.U
    TypedColorFormat.valid(format) && expectedType===TypedColorFormat.kind(format) &&
      !s.compareEnable && (filterable || nearestOnly(s))
  }
}
class TextureSamplerRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val state=new TextureSamplerState
}
object TextureSamplerLayout {
  val magic=BigInt("47585301",16);val words=16;val bytes=64
  def fields(d: TextureSamplerRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),
      Field("identity.generation",5,d.identity.generation),Field("handle",6,d.handle),
      Field("state.minFilter",7,d.state.minFilter),Field("state.magFilter",8,d.state.magFilter),
      Field("state.wrapS",9,d.state.wrapS),Field("state.wrapT",10,d.state.wrapT),Field("state.wrapR",11,d.state.wrapR),
      Field("state.minLod",12,d.state.minLod),Field("state.maxLod",13,d.state.maxLod),
      Field("state.compareEnable",14,d.state.compareEnable),Field("state.compareFunction",15,d.state.compareFunction))
  }
}
class TextureSamplerBindingRequest extends Bundle {
  val table=new TextureImageLevelTable;val handle=UInt(32.W);val tag=UInt(32.W)
}
class TextureSamplerBindingResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val tag=UInt(32.W)
  val status=UInt(3.W);val validSampler=Bool();val state=new TextureSamplerState
  val hit=Bool();val readLines=UInt(1.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}
/** Single immutable GXS1 cache. Offered reads survive cancellation and stalls;
  * results and leases stay owned until retirement. Parent pins publication/VM
  * and invalidates on rebinding, even when addresses and identity are reused.
  */
class TextureSamplerBindingUnit(config: GpuConfig=GpuConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureSamplerBindingRequest));val out=Decoupled(new TextureSamplerBindingResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,offer,waitReply,verify,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureSamplerBindingRequest)
  private val snapshot=Reg(new TextureSamplerRecord);private val result=Reg(new TextureSamplerBindingResult)
  private val status=RegInit(0.U(3.W));private val malformed=RegInit(false.B);private val address=Reg(UInt(32.W))
  private val memory=Reg(new ComputeMemoryRequest(config,64,2));private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val lines=RegInit(0.U(1.W));private val fault=RegInit(false.B);private val noFill=RegInit(false.B);private val hit=RegInit(false.B)
  private val cached=RegInit(false.B);private val cachedKey=Reg(new TextureSamplerBindingRequest);private val cachedState=Reg(new TextureSamplerState)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=memory
  io.memoryResponse.ready:=pending && io.memoryResponse.bits.transactionId===0.U
  when(io.invalidate){cached:=false.B;when(active){noFill:=true.B}}
  when(io.cancel){cached:=false.B;when(active && status===0.U){status:=TextureImageResourceStatus.cancelled.U}}
  when(io.memoryRequest.fire){offered:=false.B;pending:=true.B;phase:=State.waitReply}
  private val fields=TextureSamplerLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot,fields,TextureSamplerLayout.words)
  private val bad=NativeTypedGraphicsLayout.receive(fields,TextureSamplerLayout.words,TextureSamplerLayout.magic,
    0.U,io.memoryResponse.bits.readData,io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop)
  when(io.memoryResponse.fire){
    pending:=false.B;lines:=1.U;malformed:=bad;phase:=State.verify
    when(io.memoryResponse.bits.fault && status===0.U && !io.cancel){status:=TextureImageResourceStatus.memory.U;fault:=true.B}
  }
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!offered && !pending,"sampler admission abandoned a memory lease")
      request:=io.in.bits;snapshot:=0.U.asTypeOf(snapshot);status:=0.U;malformed:=false.B;fault:=false.B
      lines:=0.U;hit:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      val a=request.table;val begin=a.base.pad(64)+(request.handle.pad(64)<<6);val end=begin+64.U
      val legal=a.version===1.U && a.base(5,0)===0.U && a.limit(5,0)===0.U &&
        a.limit>a.base && a.limit<=(BigInt(1)<<32).U && end<=a.limit
      val same=cachedKey.table.asUInt===a.asUInt && cachedKey.handle===request.handle
      address:=begin(31,0)
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=TextureImageResourceStatus.view.U;phase:=State.finish}
        .elsewhen(cached && same && !io.invalidate && !noFill){snapshot.state:=cachedState;hit:=true.B;phase:=State.finish}
        .otherwise{
          cached:=false.B;memory:=0.U.asTypeOf(memory);memory.address:=begin(31,0);memory.sizeLog2:=6.U
          memory.byteMask:=Fill(64,1.U(1.W));memory.cachePolicy:=CachePolicy.uncached;offered:=true.B;phase:=State.offer
        }
    }
    is(State.verify){
      when(!stop && (malformed || snapshot.identity.asUInt=/=request.table.identity.asUInt || snapshot.handle=/=request.handle)){
        status:=TextureImageResourceStatus.encoding.U
      }.elsewhen(!stop && !TextureSamplerState.legal(snapshot.state)){status:=TextureImageResourceStatus.view.U}
      phase:=State.finish
    }
    is(State.finish){
      assert(!offered && !pending,"sampler completion escaped read drain")
      result:=0.U.asTypeOf(result);result.identity:=request.table.identity;result.handle:=request.handle;result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.readLines:=lines;result.hit:=hit;result.faultAddressValid:=fault;result.faultAddress:=Mux(fault,address,0.U)
      when(!stop){result.validSampler:=true.B;result.state:=snapshot.state
        when(!noFill && !io.invalidate){cached:=true.B;cachedKey:=request;cachedState:=snapshot.state}}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!offered && !pending && !io.memoryRequest.valid,"held sampler result owns a drained reader")}
}
