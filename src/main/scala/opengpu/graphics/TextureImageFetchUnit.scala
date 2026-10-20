package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

object TextureImageFetchStatus {val format=6}
object TextureImageFetchFaultSource {val none=0;val descriptor=1;val data=2;val layout=3}
class TextureImageDataFetchRequest extends Bundle {
  val resource=new TextureImageResourceAddressRequest
  val block4x4=Bool();val blockBytes=UInt(5.W)
}
class TextureImageDataFetchResult extends TextureImageResourceAddressResult {
  val raw=UInt(128.W);val dataReadLines=UInt(2.W);val faultSource=UInt(2.W)
}

/** Descriptor IDs0/1 and data IDs2/3 remain owned until replies drain.
  * Data fills cover one or two aligned64-byte lines containing the checked
  * byte span. Only those1..16 bytes are gathered into raw; all other output
  * bits are zero. These cache-line fills are not byte-sized bus transactions.
  * Parent translation/permissions must cover both participating pages.
  */
class TextureImageDataFetchUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageDataFetchRequest));val out=Decoupled(new TextureImageDataFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,offerLocation,waitLocation,checkLayout,readData,assemble,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageDataFetchRequest)
  private val metadata=Reg(new TextureImageResourceAddressResult);private val result=Reg(new TextureImageDataFetchResult)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(2.W))
  private val faultAddress=Reg(UInt(32.W));private val faultValid=RegInit(false.B)
  private val raw=Reg(UInt(128.W));private val words=Reg(Vec(2,UInt(512.W)))
  private val lineBase=Reg(UInt(32.W));private val lineCount=Reg(UInt(2.W));private val issued=RegInit(0.U(2.W))
  private val lines=RegInit(0.U(2.W));private val lineMap=Reg(Vec(2,UInt(1.W)))
  private val live=RegInit(VecInit(Seq.fill(2)(false.B)));private val pending=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val offered=RegInit(false.B);private val dataRequest=Reg(new ComputeMemoryRequest(config,64,4))
  private val locationStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val drained= !offered && !live.asUInt.orR && !pending.asUInt.orR
  private val location=Module(new TextureImageResourceAddressUnit(config,p))
  location.io.in.valid:=phase===State.offerLocation;location.io.in.bits:=request.resource
  // An irrevocable offer must be admitted before its child can be cancelled.
  location.io.cancel:=locationStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  location.io.invalidate:=io.invalidate || stop
  location.io.out.ready:=phase===State.waitLocation
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result
  io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U;source:=0.U;faultValid:=false.B}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,4),2))
  arbiter.io.in(0).valid:=location.io.memoryRequest.valid;arbiter.io.in(0).bits:=location.io.memoryRequest.bits
  arbiter.io.in(0).bits.transactionId:=location.io.memoryRequest.bits.transactionId.pad(2)
  location.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=offered;arbiter.io.in(1).bits:=dataRequest;io.memoryRequest<>arbiter.io.out
  private val replyTag=io.memoryResponse.bits.transactionId
  location.io.memoryResponse.valid:=io.memoryResponse.valid && !replyTag(1)
  location.io.memoryResponse.bits:=io.memoryResponse.bits;location.io.memoryResponse.bits.transactionId:=replyTag(0)
  io.memoryResponse.ready:=Mux(replyTag(1),pending(replyTag(0)),location.io.memoryResponse.ready)
  when(arbiter.io.in(1).fire){offered:=false.B;pending(dataRequest.transactionId(0)):=true.B}
  private val dataReply=io.memoryResponse.fire && replyTag(1)
  when(dataReply){val tag=replyTag(0);live(tag):=false.B;pending(tag):=false.B;lines:=lines+1.U
    when(io.memoryResponse.bits.fault && status===0.U && !io.cancel){
      status:=TextureImageResourceStatus.memory.U;source:=TextureImageFetchFaultSource.data.U
      faultAddress:=(lineBase+(lineMap(tag)<<6))(31,0);faultValid:=true.B
    }
    when(!io.memoryResponse.bits.fault && !stop){words(lineMap(tag)):=io.memoryResponse.bits.readData}
  }
  private val free= ~live.asUInt;private val freeTag=PriorityEncoder(free)
  // The fault response and next-offer construction can share an edge. Include
  // that observed fault here instead of waiting for the registered status.
  when(phase===State.readData && !stop && !(dataReply && io.memoryResponse.bits.fault) && !offered && issued<lineCount && free.orR){
    val address=lineBase+&(issued<<6)
    assert(!address(32),"checked data span must not wrap a line fill past4GiB")
    dataRequest:=0.U.asTypeOf(dataRequest);dataRequest.address:=address(31,0)
    dataRequest.sizeLog2:=6.U;dataRequest.byteMask:=Fill(64,1.U(1.W));dataRequest.cachePolicy:=CachePolicy.uncached
    dataRequest.transactionId:=Cat(1.U(1.W),freeTag)
    live(freeTag):=true.B;lineMap(freeTag):=issued(0);issued:=issued+1.U;offered:=true.B
  }
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(drained && !location.io.busy,"fetch admission must retain old descriptor/data ownership")
      request:=io.in.bits;metadata:=0.U.asTypeOf(metadata);status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U
      raw:=0.U;words.foreach(_:=0.U);issued:=0.U;lines:=0.U;locationStarted:=false.B;phase:=State.check
    }}
    is(State.check){
      val packed=Seq(1,2,3,4,6,8,12,16).map(n=>request.blockBytes===n.U).reduce(_ || _)
      val legal=Mux(request.block4x4,request.blockBytes===8.U || request.blockBytes===16.U,packed)
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=TextureImageFetchStatus.format.U;source:=TextureImageFetchFaultSource.layout.U;phase:=State.finish}
        .otherwise{phase:=State.offerLocation}
    }
    is(State.offerLocation){when(location.io.in.fire){locationStarted:=true.B;phase:=State.waitLocation}}
    is(State.waitLocation){when(location.io.out.fire){
      metadata:=location.io.out.bits;locationStarted:=false.B
      val child=location.io.out.bits.status
      // A child preserves an earlier primary fault through later cancellation.
      when((child=/=0.U && child=/=TextureImageResourceStatus.cancelled.U) || !stop){
        status:=child;source:=Mux(child===0.U || child===TextureImageResourceStatus.cancelled.U,0.U,TextureImageFetchFaultSource.descriptor.U)
        faultAddress:=location.io.out.bits.faultAddress;faultValid:=location.io.out.bits.faultAddressValid
      }
      phase:=Mux(stop || child=/=0.U,State.finish,State.checkLayout)
    }}
    is(State.checkLayout){
      when(stop){phase:=State.finish}
        .elsewhen(metadata.view.block4x4=/=request.block4x4 || metadata.bytes=/=request.blockBytes){
          status:=TextureImageFetchStatus.format.U;source:=TextureImageFetchFaultSource.layout.U;phase:=State.finish
        }.otherwise{
          lineBase:=Cat(metadata.address(31,6),0.U(6.W))
          lineCount:=Mux((metadata.address(5,0)+&metadata.bytes)>64.U,2.U,1.U);phase:=State.readData
        }
    }
    is(State.readData){when((stop || issued===lineCount) && drained){phase:=Mux(stop,State.finish,State.assemble)}}
    is(State.assemble){
      val gathered=(Cat(words(1),words(0))>>(metadata.address(5,0)<<3))(127,0)
      val enabled=((1.U(17.W)<<metadata.bytes)-1.U)(15,0)
      raw:=Cat((0 until 16).reverse.map(i=>gathered(i*8+7,i*8)&Fill(8,enabled(i))))
      phase:=State.finish
    }
    is(State.finish){
      assert(drained && !location.io.busy,"fetch completion escaped descriptor/data drain")
      result:=0.U.asTypeOf(result);val q=request.resource
      result.identity:=q.lookup.table.identity;result.handle:=q.lookup.handle;result.tag:=q.tag;result.level:=q.lookup.level
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.readLines:=metadata.readLines;result.hit:=metadata.hit;result.dataReadLines:=lines;result.faultSource:=source
      result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.viewValid:=true.B;result.view:=metadata.view;result.address:=metadata.address;result.bytes:=metadata.bytes
        result.blockX:=metadata.blockX;result.blockY:=metadata.blockY;result.raw:=raw}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(drained && !location.io.busy && !io.memoryRequest.valid,"held fetch result must own a drained memory path")}
}

class TextureImageTypedFetchRequest extends Bundle {val resource=new TextureImageResourceAddressRequest;val format=UInt(6.W)}
class TextureImageTypedFetchResult extends Bundle {
  val sample=new TextureImageDataFetchResult;val rgba=Vec(4,UInt(32.W));val colorType=UInt(2.W)
}
class TextureImageDecodeRequest extends TypedColorDecodeRequest {val blockX=UInt(2.W);val blockY=UInt(2.W)}
/** Register extraction, numeric preparation and final rounding separately.
  * The existing TypedColorDecoder and its users keep their original latency.
  */
private[graphics] class TextureImageFetchDecoder extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageDecodeRequest));val out=Decoupled(new TypedColorDecodeResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offerBlock,waitBlock,select,prepare,decode,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageDecodeRequest)
  private val result=Reg(new TypedColorDecodeResult);private val c=RegInit(0.U(2.W))
  private val raw=Reg(UInt(32.W));private val bits=Reg(UInt(6.W));private val encoding=Reg(UInt(4.W))
  private val blockRgba=Reg(Vec(4,UInt(12.W)))
  private val block=Module(new TextureCompressedBlockDecoder)
  block.io.in.valid:=phase===State.offerBlock;block.io.in.bits.raw:=request.value
  block.io.in.bits.codec:=TextureImageFormat.codec(request.format);block.io.in.bits.signed:=TextureImageFormat.encoding(request.format)===14.U
  block.io.in.bits.x:=request.blockX;block.io.in.bits.y:=request.blockY;block.io.in.bits.tag:=request.tag
  block.io.out.ready:=phase===State.waitBlock
  when(phase===State.offerBlock && block.io.in.fire){phase:=State.waitBlock}
  when(phase===State.waitBlock && block.io.out.fire){assert(block.io.out.bits.tag===request.tag);blockRgba:=block.io.out.bits.rgba
    result.status:=block.io.out.bits.status;phase:=Mux(block.io.out.bits.status===0.U,State.select,State.respond)}
  private val sharedExponent=Reg(UInt(5.W))
  private val preparedValue=Reg(UInt(32.W));private val normalized=Reg(Bool())
  private val mantissa=Reg(UInt(24.W));private val guard=Reg(Bool());private val exponent=Reg(UInt(8.W))
  private val negative=Reg(Bool());private val zero=Reg(Bool())
  private val present=Reg(Bool());private val alpha=Reg(Bool());private val defaultValue=Reg(UInt(32.W))
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    result.colorType:=TextureImageFormat.kind(io.in.bits.format);c:=0.U
    when(TextureImageFormat.valid(io.in.bits.format)){phase:=Mux(TextureImageFormat.block4x4(io.in.bits.format),State.offerBlock,State.select)}.otherwise{result.status:=1.U;phase:=State.respond}}
  when(phase===State.select){
    val width=TextureImageFormat.componentWidth(request.format,c)
    val shift=TextureImageFormat.componentOffset(request.format,c)
    val uncompressed=(request.value>>shift)(31,0)&((1.U(33.W)<<width)-1.U)(31,0)
    val compressed=Mux(TextureImageFormat.encoding(request.format)===14.U,blockRgba(c).asSInt.pad(32).asUInt,blockRgba(c).pad(32))
    raw:=Mux(TextureImageFormat.block4x4(request.format),compressed,uncompressed)
    bits:=width;encoding:=TextureImageFormat.encoding(request.format);present:=width=/=0.U;alpha:=c===3.U
    sharedExponent:=request.value(31,27)
    defaultValue:=Mux(c===3.U,Mux(TextureImageFormat.kind(request.format)===0.U,"h3f800000".U,1.U),0.U)
    phase:=State.prepare
  }
  private val signed=((raw<<(32.U-bits))(31,0).asSInt>>(32.U-bits)).asUInt
  private val decoded=MuxLookup(encoding,raw)(Seq(0.U->TypedColorDecode.unorm(raw,bits),2.U->signed,
    3.U->TypedColorDecode.half(raw(15,0)),5.U->Mux(alpha,TypedColorDecode.unorm(raw,8.U),Srgb8Decode.values(raw(7,0))),
    11.U->TypedColorDecode.unorm(raw,8.U),12.U->Mux(alpha,TypedColorDecode.unorm(raw,8.U),Srgb8Decode.values(raw(7,0)))))
  // Signed normalized and16/24-bit depth need more than the legacy40
  // fractional bits. Eighty bits retain all rounding information even at D24's
  // smallest nonzero value. Odd denominators cannot produce binary midpoints.
  private val magnitude=Mux(raw(7),(0.U(8.W)-raw(7,0))(7,0),raw(7,0))
  private val eacMagnitude=Mux(raw(31),(0.U(32.W)-raw)(31,0),raw)
  private val numerator=Mux(encoding===6.U,Mux(magnitude>127.U,127.U,magnitude).pad(32),Mux(encoding===14.U,eacMagnitude,raw))
  private val fractionBits=Mux(encoding===6.U,7.U,Mux(encoding===14.U,10.U,bits))
  private val repeated=MuxLookup(fractionBits,0.U(80.W))(Seq(7,10,11,16,24).map{b=>
    val count=(80+b-1)/b;val size=count*b;b.U->Fill(count,numerator(b-1,0))(size-1,size-80)})
  private val leading=PriorityEncoder(Reverse(repeated))
  private val shifted=(repeated<<leading)(79,0)
  private def miniFloat(n: UInt,fractionBits: Int): UInt={
    val fraction=n(fractionBits-1,0);val e=n(fractionBits+4,fractionBits)
    val leading=PriorityEncoder(Reverse(fraction))
    val sub=(fraction.pad(24)<<(leading+&(24-fractionBits).U))(22,0)
    Mux(e===31.U,Mux(fraction.orR,"h7fc00000".U,"h7f800000".U),
      Mux(e===0.U,Mux(fraction.orR,Cat(0.U(1.W),(112.U(8.W)-leading)(7,0),sub),0.U),
        Cat(0.U(1.W),(e+&112.U)(7,0),fraction,0.U((23-fractionBits).W))))
  }
  private val sharedMantissa=raw(8,0)
  private val sharedLeading=PriorityEncoder(Reverse(sharedMantissa))
  private val sharedFraction=(sharedMantissa.pad(24)<<(sharedLeading+&15.U))(22,0)
  private val sharedValue=Mux(sharedMantissa.orR,
    Cat(0.U(1.W),(111.U(8.W)+sharedExponent-sharedLeading)(7,0),sharedFraction),0.U)
  when(phase===State.prepare){
    normalized:=encoding===6.U || encoding===9.U || encoding===13.U || encoding===14.U
    mantissa:=shifted(79,56);guard:=shifted(55);exponent:=126.U-leading
    zero:=numerator===0.U;negative:=((encoding===6.U && raw(7)) || (encoding===14.U && raw(31))) && numerator=/=0.U
    preparedValue:=Mux(encoding===7.U,Mux(bits===10.U,miniFloat(raw,5),miniFloat(raw,6)),
      Mux(encoding===8.U,sharedValue,decoded))
    phase:=State.decode
  }
  private val rounded=mantissa+&guard
  private val normalizedValue=Mux(zero,0.U,Cat(negative,(exponent+rounded(24))(7,0),rounded(22,0)))
  when(phase===State.decode){result.rgba(c):=Mux(present,Mux(normalized,normalizedValue,preparedValue),defaultValue)
    when(c===3.U){phase:=State.respond}.otherwise{c:=c+1.U;phase:=State.select}}
  when(io.out.fire){phase:=State.idle}
}
/** Typed64-format fetch. Format is captured at admission; its byte size must
  * agree with the GPU level layout before texture data can be read. Integer
  * outputs remain raw typed values; normalized/half/sRGB and packed/depth values use image decode.
  */
class TextureImageTypedFetchUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageTypedFetchRequest));val out=Decoupled(new TextureImageTypedFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,offerFetch,waitFetch,offerDecode,waitDecode,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageTypedFetchRequest)
  private val sample=Reg(new TextureImageDataFetchResult);private val result=Reg(new TextureImageTypedFetchResult)
  private val rgba=Reg(Vec(4,UInt(32.W)));private val colorType=Reg(UInt(2.W));private val status=RegInit(0.U(3.W))
  private val fetchStarted=RegInit(false.B);private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val fetch=Module(new TextureImageDataFetchUnit(config,p));private val decoder=Module(new TextureImageFetchDecoder)
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest<>fetch.io.memoryRequest;fetch.io.memoryResponse<>io.memoryResponse
  fetch.io.invalidate:=io.invalidate || stop;fetch.io.cancel:=fetchStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  fetch.io.in.valid:=phase===State.offerFetch;fetch.io.in.bits.resource:=request.resource
  fetch.io.in.bits.block4x4:=TextureImageFormat.block4x4(request.format);fetch.io.in.bits.blockBytes:=TextureImageFormat.bytes(request.format)
  fetch.io.out.ready:=phase===State.waitFetch
  decoder.io.in.valid:=phase===State.offerDecode;decoder.io.in.bits.value:=sample.raw
  decoder.io.in.bits.format:=request.format;decoder.io.in.bits.tag:=request.resource.tag(7,0)
  decoder.io.in.bits.blockX:=sample.blockX;decoder.io.in.bits.blockY:=sample.blockY
  decoder.io.out.ready:=phase===State.waitDecode
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!fetch.io.busy && !decoder.io.busy,"typed fetch cannot abandon child work")
      request:=io.in.bits;sample:=0.U.asTypeOf(sample);rgba.foreach(_:=0.U);colorType:=0.U
      status:=0.U;fetchStarted:=false.B;phase:=State.check
    }}
    is(State.check){when(stop){phase:=State.finish}.elsewhen(!TextureImageFormat.valid(request.format)){
      status:=TextureImageFetchStatus.format.U;phase:=State.finish
    }.otherwise{phase:=State.offerFetch}}
    is(State.offerFetch){when(fetch.io.in.fire){fetchStarted:=true.B;phase:=State.waitFetch}}
    is(State.waitFetch){when(fetch.io.out.fire){
      sample:=fetch.io.out.bits;fetchStarted:=false.B;val child=fetch.io.out.bits.status
      when((child=/=0.U && child=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=child}
      phase:=Mux(stop || child=/=0.U,State.finish,State.offerDecode)
    }}
    is(State.offerDecode){when(decoder.io.in.fire){phase:=State.waitDecode}}
    is(State.waitDecode){when(decoder.io.out.fire){
      assert(decoder.io.out.bits.tag===request.resource.tag(7,0))
      when(!stop){rgba:=decoder.io.out.bits.rgba;colorType:=decoder.io.out.bits.colorType
        when(decoder.io.out.bits.status=/=0.U){status:=TextureImageFetchStatus.format.U}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!fetch.io.busy && !decoder.io.busy,"typed completion escaped fetch/decode drain")
      result:=0.U.asTypeOf(result);result.sample:=sample;val q=request.resource
      result.sample.identity:=q.lookup.table.identity;result.sample.handle:=q.lookup.handle
      result.sample.tag:=q.tag;result.sample.level:=q.lookup.level
      result.sample.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      when(stop){result.sample.viewValid:=false.B;result.sample.view:=0.U.asTypeOf(result.sample.view)
        result.sample.address:=0.U;result.sample.bytes:=0.U;result.sample.blockX:=0.U;result.sample.blockY:=0.U;result.sample.raw:=0.U
        when(status===TextureImageFetchStatus.format.U){result.sample.faultSource:=TextureImageFetchFaultSource.layout.U}
      }.otherwise{result.rgba:=rgba;result.colorType:=colorType}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!fetch.io.busy && !decoder.io.busy,"held typed result must retain drained children")}
}
