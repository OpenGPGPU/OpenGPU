package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** An owner snapshots the table and pins its VM until the held result retires.
  * Handles index128-byte records. Invalidate on rebinding, including reuse of
  * the same addresses and identity. Identity is provenance, not a page table.
  */
class TextureImageLevelTable extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity
  val base=UInt(32.W);val limit=UInt(33.W)
}
class TextureImageLevelLookup extends Bundle {
  val table=new TextureImageLevelTable;val handle=UInt(32.W);val level=UInt(4.W)
}
class TextureImageLevelRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val view=new TextureImageLevelView
}
object TextureImageLevelLayout {
  val magic=BigInt("47584c01",16);val words=32;val bytes=128
  def fields(d: TextureImageLevelRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),
      Field("identity.generation",5,d.identity.generation),Field("handle",6,d.handle),
      Field("view.target",7,d.view.target),Field("view.level",8,d.view.level),
      Field("view.allocationBase",9,d.view.allocationBase),Field("view.allocationLimit",10,d.view.allocationLimit),
      Field("view.base",12,d.view.base),Field("view.texWidth",13,d.view.texWidth),Field("view.texHeight",14,d.view.texHeight),
      Field("view.texDepth",15,d.view.texDepth),Field("view.layers",16,d.view.layers),
      Field("view.rowPitch",17,d.view.rowPitch),Field("view.slicePitch",19,d.view.slicePitch),
      Field("view.layerPitch",21,d.view.layerPitch),Field("view.block4x4",23,d.view.block4x4),Field("view.blockBytes",24,d.view.blockBytes))
  }
}
class TextureImageResourceAddressRequest extends Bundle {
  val lookup=new TextureImageLevelLookup;val tag=UInt(32.W)
  val x=UInt(32.W);val y=UInt(32.W);val z=UInt(32.W);val layer=UInt(32.W);val face=UInt(3.W)
}
object TextureImageResourceStatus {
  val ok=0;val view=1;val bounds=2;val memory=3;val cancelled=4;val coordinate=5;val encoding=7
}
class TextureImageResourceAddressResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val tag=UInt(32.W);val level=UInt(4.W)
  val status=UInt(3.W);val readLines=UInt(2.W);val hit=Bool();val viewValid=Bool();val view=new TextureImageLevelView
  val address=UInt(32.W);val bytes=UInt(5.W);val blockX=UInt(2.W);val blockY=UInt(2.W)
  val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}

/** Load a complete immutable GPU-memory level, then compute a checked span.
  * Two tagged uncached reads may return in either order. Partial, malformed,
  * faulted or cancelled views never publish addresses or populate the cache.
  * One shared address pipeline validates the whole level and actual coordinates.
  * The cache key includes table identity/base/limit/version, handle and level;
  * changing coordinates or caller tags does not change descriptor identity.
  * This serial admission owner does not replace the streaming address pipeline
  * used after binding. It issues descriptor reads only, never texture-data reads.
  */
class TextureImageResourceAddressUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageResourceAddressRequest))
    val out=Decoupled(new TextureImageResourceAddressResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,fetch,verify,offerAddress,waitAddress,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageResourceAddressRequest)
  private val snapshot=Reg(new TextureImageLevelRecord);private val span=Reg(new TextureImageAddressResult)
  private val result=Reg(new TextureImageResourceAddressResult);private val status=RegInit(0.U(3.W))
  private val faultAddress=RegInit(0.U(32.W))
  private val start=Reg(UInt(33.W));private val issued=RegInit(0.U(2.W));private val lines=RegInit(0.U(2.W))
  private val live=RegInit(VecInit(Seq.fill(2)(false.B)));private val pending=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val lineMap=Reg(Vec(2,UInt(1.W)));private val offered=RegInit(false.B)
  private val memory=Reg(new ComputeMemoryRequest(config,64,2));private val badEncoding=RegInit(false.B)
  private val cached=RegInit(false.B);private val cachedKey=Reg(new TextureImageLevelLookup)
  private val cachedView=Reg(new TextureImageLevelView);private val noFill=RegInit(false.B);private val hit=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val drained= !offered && !live.asUInt.orR && !pending.asUInt.orR
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.complete;io.out.bits:=result
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=memory
  private val tag=io.memoryResponse.bits.transactionId
  io.memoryResponse.ready:=pending(tag)
  private val fields=TextureImageLevelLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot,fields,TextureImageLevelLayout.words)
  private val receive=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val malformed=NativeTypedGraphicsLayout.receive(fields,TextureImageLevelLayout.words,TextureImageLevelLayout.magic,
    lineMap(tag),io.memoryResponse.bits.readData,receive)
  when(io.invalidate){cached:=false.B;when(active){noFill:=true.B}}
  when(io.cancel){cached:=false.B;when(active && status===0.U){status:=TextureImageResourceStatus.cancelled.U}}
  when(io.memoryRequest.fire){offered:=false.B;pending(memory.transactionId):=true.B}
  when(io.memoryResponse.fire){live(tag):=false.B;pending(tag):=false.B;lines:=lines+1.U
    when(io.memoryResponse.bits.fault){cached:=false.B;when(status===0.U && !io.cancel){status:=TextureImageResourceStatus.memory.U
        faultAddress:=(start+(lineMap(tag)<<6))(31,0)}}
    when(receive && malformed){badEncoding:=true.B}}
  private val free= ~live.asUInt;private val freeTag=PriorityEncoder(free)
  when(phase===State.fetch && !stop && !offered && issued<2.U && free.orR){
    memory:=0.U.asTypeOf(memory);memory.address:=(start+(issued<<6))(31,0)
    memory.sizeLog2:=6.U;memory.byteMask:=Fill(64,1.U(1.W));memory.cachePolicy:=CachePolicy.uncached;memory.transactionId:=freeTag
    live(freeTag):=true.B;lineMap(freeTag):=issued(0);issued:=issued+1.U;offered:=true.B
  }
  private val address=Module(new TextureImageAddressUnit(p,pipelineBounds=true))
  address.io.in.valid:=phase===State.offerAddress
  address.io.in.bits.view:=snapshot.view;address.io.in.bits.tag:=request.tag
  address.io.in.bits.x:=request.x;address.io.in.bits.y:=request.y;address.io.in.bits.z:=request.z
  address.io.in.bits.layer:=request.layer;address.io.in.bits.face:=request.face
  address.io.out.ready:=phase===State.waitAddress
  when(address.io.out.fire){
    span:=address.io.out.bits
    assert(address.io.out.bits.tag===request.tag && address.io.out.bits.level===snapshot.view.level)
    when(!stop){status:=MuxLookup(address.io.out.bits.status,TextureImageResourceStatus.encoding.U)(Seq(
      TextureImageAddressStatus.ok.U->TextureImageResourceStatus.ok.U,
      TextureImageAddressStatus.invalidView.U->TextureImageResourceStatus.view.U,
      TextureImageAddressStatus.coordinate.U->TextureImageResourceStatus.coordinate.U,
      TextureImageAddressStatus.bounds.U->TextureImageResourceStatus.bounds.U))}
  }
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(drained && !address.io.busy,"new texture view must not abandon old ownership")
      request:=io.in.bits;snapshot:=0.U.asTypeOf(snapshot);span:=0.U.asTypeOf(span)
      status:=0.U;faultAddress:=0.U;issued:=0.U;lines:=0.U;badEncoding:=false.B;noFill:=io.invalidate;hit:=false.B;phase:=State.check
    }}
    is(State.check){
      val a=request.lookup.table;val begin=a.base.pad(64)+(request.lookup.handle.pad(64)<<7)
      val end=begin+TextureImageLevelLayout.bytes.U
      val legal=a.version===1.U && a.base(5,0)===0.U && a.limit(5,0)===0.U &&
        a.limit>a.base && a.limit<=(BigInt(1)<<32).U && (a.limit-a.base)(6,0)===0.U && end<=a.limit
      val cacheHit=cached && !noFill && !io.invalidate && cachedKey.asUInt===request.lookup.asUInt
      start:=begin(32,0)
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=TextureImageResourceStatus.view.U;phase:=State.finish}
        .elsewhen(cacheHit){snapshot.identity:=a.identity;snapshot.handle:=request.lookup.handle;snapshot.view:=cachedView
          hit:=true.B;phase:=State.offerAddress}
        .otherwise{cached:=false.B;phase:=State.fetch}
    }
    is(State.fetch){when((stop || issued===2.U) && drained){phase:=Mux(stop,State.finish,State.verify)}}
    is(State.verify){
      when(stop){phase:=State.finish}
        .elsewhen(badEncoding || snapshot.identity.asUInt=/=request.lookup.table.identity.asUInt ||
          snapshot.handle=/=request.lookup.handle || snapshot.view.level=/=request.lookup.level){
          status:=TextureImageResourceStatus.encoding.U;phase:=State.finish
        }.otherwise{phase:=State.offerAddress}
    }
    is(State.offerAddress){when(address.io.in.fire){phase:=State.waitAddress}}
    is(State.waitAddress){when(address.io.out.fire){phase:=State.finish}}
    is(State.finish){
      assert(drained && !address.io.busy,"texture resource result escaped descriptor/address drain")
      result:=0.U.asTypeOf(result);result.identity:=request.lookup.table.identity;result.handle:=request.lookup.handle
      result.tag:=request.tag;result.level:=request.lookup.level;result.readLines:=lines;result.hit:=hit
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.faultAddressValid:=status===TextureImageResourceStatus.memory.U
      result.faultAddress:=Mux(status===TextureImageResourceStatus.memory.U,faultAddress,0.U)
      when(!stop){
        result.viewValid:=true.B;result.view:=snapshot.view;result.address:=span.address;result.bytes:=span.bytes
        result.blockX:=span.blockX;result.blockY:=span.blockY
        when(!noFill && !io.invalidate){cached:=true.B;cachedKey:=request.lookup;cachedView:=snapshot.view}
      }
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(drained && !address.io.busy,"held texture view result lost ownership")}
}
