package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** GXI1 supplies authoritative format and level-table identity. Dimensions
  * describe a logical level0; no storage for unused levels is required.
  */
class TextureImageBindingRecord extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W)
  val target=UInt(3.W);val format=UInt(6.W);val baseLevel=UInt(4.W);val maxLevel=UInt(4.W)
  val rootWidth=UInt(14.W);val rootHeight=UInt(14.W);val rootDepth=UInt(14.W);val layers=UInt(16.W)
  val allocationBase=UInt(32.W);val allocationLimit=UInt(33.W)
  val levelsVersion=UInt(8.W);val levelsBase=UInt(32.W);val levelsLimit=UInt(33.W);val firstLevelHandle=UInt(32.W)
  val swizzle=new TextureImageSwizzleState
}
object TextureImageBindingLayout {
  val magic=BigInt("47584901",16);val words=32;val bytes=128
  def fields(d: TextureImageBindingRecord): Seq[NativeTypedGraphicsLayout.Field]={
    import NativeTypedGraphicsLayout.Field
    Seq(Field("identity.context",3,d.identity.context),Field("identity.pass",4,d.identity.pass),Field("identity.generation",5,d.identity.generation),
      Field("handle",6,d.handle),Field("target",7,d.target),Field("format",8,d.format),Field("baseLevel",9,d.baseLevel),Field("maxLevel",10,d.maxLevel),
      Field("rootWidth",11,d.rootWidth),Field("rootHeight",12,d.rootHeight),Field("rootDepth",13,d.rootDepth),Field("layers",14,d.layers),
      Field("allocationBase",15,d.allocationBase),Field("allocationLimit",16,d.allocationLimit),Field("levelsVersion",18,d.levelsVersion),
      Field("levelsBase",19,d.levelsBase),Field("levelsLimit",20,d.levelsLimit),Field("firstLevelHandle",22,d.firstLevelHandle),
      Field("swizzle.version",23,d.swizzle.version),Field("swizzle.r",24,d.swizzle.r),Field("swizzle.g",25,d.swizzle.g),
      Field("swizzle.b",26,d.swizzle.b),Field("swizzle.a",27,d.swizzle.a))
  }
}
class TextureImageBindingRequest extends Bundle {
  val table=new TextureImageLevelTable;val handle=UInt(32.W);val level=UInt(32.W);val tag=UInt(32.W)
  /** From captured sampler policy. False validates selected level only. */
  val requireMipChain=Bool()
  /** General sampling starts at the captured base level before choosing LOD.
    * This internal mode requires the unused explicit level to be zero.
    */
  val selectBaseLevel=Bool()
}
object TextureImageBindingFaultStage {
  val none=0;val image=1;val level=2;val data=3;val layout=4;val coordinate=5
}
class TextureImageBindingResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val handle=UInt(32.W);val level=UInt(32.W);val tag=UInt(32.W)
  val status=UInt(3.W);val faultStage=UInt(3.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
  val validBinding=Bool();val hit=Bool();val imageReadLines=UInt(2.W);val levelReadLines=UInt(6.W);val levelsChecked=UInt(5.W)
  val format=UInt(6.W);val baseLevel=UInt(4.W);val lastLevel=UInt(4.W);val mipChainChecked=Bool()
  val selected=new TextureImageLevelLookup;val view=new TextureImageLevelView
  val swizzle=new TextureImageSwizzleState
}
private[graphics] object TextureImageMipRange {
  /** For positive roots, base+floor(log2(max(1,root>>base))) equals
    * max(base,floor(log2(root))). Zero roots use floor0; admission rejects
    * zero dimensions separately. Array layer counts never enter this bound.
    */
  def last(rootWidth: UInt,rootHeight: UInt,rootDepth: UInt,target: UInt,base: UInt,maximum: UInt): UInt={
    require(Seq(rootWidth,rootHeight,rootDepth).forall(_.getWidth==14))
    val xy=Mux(rootWidth>rootHeight,rootWidth,rootHeight)
    val largest=Mux(target===3.U && rootDepth>xy,rootDepth,xy)
    val logarithm=Mux(largest===0.U,0.U,13.U-PriorityEncoder(Reverse(largest)))(3,0)
    val natural=Mux(base>logarithm,base,logarithm)
    Mux(maximum<natural,maximum,natural)
  }
}

/** Serialized immutable image admission. Header tags2/3 and level tags0/1
  * stay disjoint. Every checked level belongs to one authoritative format,
  * allocation and dimensional sequence. Prior level spans must not overlap.
  * A parent pins tables, publication and VM through held completion.
  */
class TextureImageBindingUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageBindingRequest));val out=Decoupled(new TextureImageBindingResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,readHeader,verifyHeader,checkImage,checkSelectedLevel,levelHandles,levelExtent,checkLevelTable,prepareLevel,offerLevel,waitLevel,verifyLevel,advance,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageBindingRequest)
  private val snapshot=Reg(new TextureImageBindingRecord);private val result=Reg(new TextureImageBindingResult)
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(3.W));private val faultValid=RegInit(false.B)
  private val faultAddress=Reg(UInt(32.W));private val badEncoding=RegInit(false.B)
  private val start=Reg(UInt(33.W));private val issued=RegInit(0.U(2.W));private val headerLines=RegInit(0.U(2.W))
  private val levelLines=RegInit(0.U(6.W));private val checked=RegInit(0.U(5.W))
  private val live=RegInit(VecInit(Seq.fill(2)(false.B)));private val pending=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val lineMap=Reg(Vec(2,UInt(1.W)));private val offered=RegInit(false.B)
  private val headerRequest=Reg(new ComputeMemoryRequest(config,64,4))
  private val currentLevel=Reg(UInt(4.W));private val lastLevel=Reg(UInt(4.W))
  private val firstHandle=Reg(UInt(33.W));private val lastHandle=Reg(UInt(33.W))
  private val levelBegin=Reg(UInt(41.W));private val levelEnd=Reg(UInt(41.W))
  private val levelRequest=Reg(new TextureImageResourceAddressRequest);private val levelResult=Reg(new TextureImageResourceAddressResult)
  private val selectedView=Reg(new TextureImageLevelView);private val selectedLookup=Reg(new TextureImageLevelLookup)
  private val selectedValid=RegInit(false.B);private val levelStarted=RegInit(false.B)
  private val bases=Reg(Vec(16,UInt(32.W)));private val ends=Reg(Vec(16,UInt(33.W)))
  private val cached=RegInit(false.B);private val cachedKey=Reg(new TextureImageBindingRequest)
  private val cachedResult=Reg(new TextureImageBindingResult);private val noFill=RegInit(false.B);private val hit=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val drained= !offered && !live.asUInt.orR && !pending.asUInt.orR
  private val child=Module(new TextureImageResourceAddressUnit(config,p))
  child.io.in.valid:=phase===State.offerLevel;child.io.in.bits:=levelRequest
  child.io.out.ready:=phase===State.waitLevel
  child.io.cancel:=levelStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  child.io.invalidate:=io.invalidate || stop || noFill
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(io.invalidate){cached:=false.B;when(active){noFill:=true.B}}
  when(io.cancel){cached:=false.B;when(active && status===0.U){status:=TextureImageResourceStatus.cancelled.U;faultStage:=0.U;faultValid:=false.B}}
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,4),2))
  arb.io.in(0).valid:=child.io.memoryRequest.valid;arb.io.in(0).bits:=child.io.memoryRequest.bits
  arb.io.in(0).bits.transactionId:=child.io.memoryRequest.bits.transactionId.pad(2);child.io.memoryRequest.ready:=arb.io.in(0).ready
  arb.io.in(1).valid:=offered;arb.io.in(1).bits:=headerRequest;io.memoryRequest<>arb.io.out
  private val replyTag=io.memoryResponse.bits.transactionId;private val headerReply=io.memoryResponse.fire && replyTag(1)
  child.io.memoryResponse.valid:=io.memoryResponse.valid && !replyTag(1);child.io.memoryResponse.bits:=io.memoryResponse.bits
  child.io.memoryResponse.bits.transactionId:=replyTag(0)
  io.memoryResponse.ready:=Mux(replyTag(1),pending(replyTag(0)),child.io.memoryResponse.ready)
  when(arb.io.in(1).fire){offered:=false.B;pending(headerRequest.transactionId(0)):=true.B}
  private val fields=TextureImageBindingLayout.fields(snapshot)
  NativeTypedGraphicsLayout.check(snapshot,fields,TextureImageBindingLayout.words)
  private val receive=headerReply && !io.memoryResponse.bits.fault && !stop
  private val malformed=NativeTypedGraphicsLayout.receive(fields,TextureImageBindingLayout.words,TextureImageBindingLayout.magic,
    lineMap(replyTag(0)),io.memoryResponse.bits.readData,receive)
  when(headerReply){val tag=replyTag(0);live(tag):=false.B;pending(tag):=false.B;headerLines:=headerLines+1.U
    when(io.memoryResponse.bits.fault && status===0.U && !io.cancel){status:=TextureImageResourceStatus.memory.U
      faultStage:=TextureImageBindingFaultStage.image.U;faultValid:=true.B;faultAddress:=(start+(lineMap(tag)<<6))(31,0)}
    when(receive && malformed){badEncoding:=true.B}
  }
  private val free= ~live.asUInt;private val freeTag=PriorityEncoder(free)
  when(phase===State.readHeader && !stop && !(headerReply && io.memoryResponse.bits.fault) && !offered && issued<2.U && free.orR){
    headerRequest:=0.U.asTypeOf(headerRequest);headerRequest.address:=(start+(issued<<6))(31,0)
    headerRequest.sizeLog2:=6.U;headerRequest.byteMask:=Fill(64,1.U(1.W));headerRequest.cachePolicy:=CachePolicy.uncached
    headerRequest.transactionId:=Cat(1.U(1.W),freeTag);live(freeTag):=true.B;lineMap(freeTag):=issued(0);issued:=issued+1.U;offered:=true.B
  }
  private def dimension(n: UInt,level: UInt): UInt=Mux((n>>level)===0.U,1.U,n>>level)
  private val width=dimension(snapshot.rootWidth,currentLevel);private val height=dimension(snapshot.rootHeight,currentLevel)
  private val depth=Mux(snapshot.target===3.U,dimension(snapshot.rootDepth,currentLevel),1.U)
  private val selectedLevel=Mux(request.selectBaseLevel,snapshot.baseLevel,request.level)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(drained && !child.io.busy,"image admission abandoned old ownership")
      request:=io.in.bits;snapshot:=0.U.asTypeOf(snapshot);status:=0.U;faultStage:=0.U;faultValid:=false.B;faultAddress:=0.U
      issued:=0.U;headerLines:=0.U;levelLines:=0.U;checked:=0.U;badEncoding:=false.B;selectedValid:=false.B;selectedView:=0.U.asTypeOf(selectedView)
      selectedLookup:=0.U.asTypeOf(selectedLookup);noFill:=io.invalidate;hit:=false.B;levelStarted:=false.B;phase:=State.check
    }}
    is(State.check){
      val a=request.table;val begin=a.base.pad(64)+(request.handle.pad(64)<<7);val end=begin+128.U
      val legal=a.version===1.U && a.base(5,0)===0.U && a.limit>a.base && a.limit<=(BigInt(1)<<32).U && (a.limit-a.base)(6,0)===0.U && end<=a.limit
      // Tags are not image identity. Compare a separately canonicalized key.
      val cacheKey=WireDefault(request);cacheKey.tag:=0.U
      val cacheHit=cached && !noFill && !io.invalidate && cachedKey.asUInt===cacheKey.asUInt
      start:=begin(32,0)
      when(stop){phase:=State.finish}.elsewhen(!legal || (request.selectBaseLevel && request.level=/=0.U)){status:=TextureImageResourceStatus.view.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .elsewhen(cacheHit){result:=cachedResult;hit:=true.B;phase:=State.finish}
        .otherwise{cached:=false.B;phase:=State.readHeader}
    }
    is(State.readHeader){when((stop || issued===2.U) && drained){phase:=Mux(stop,State.finish,State.verifyHeader)}}
    is(State.verifyHeader){
      when(stop){phase:=State.finish}.elsewhen(badEncoding || snapshot.identity.asUInt=/=request.table.identity.asUInt || snapshot.handle=/=request.handle){
        status:=TextureImageResourceStatus.encoding.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish
      }.otherwise{phase:=State.checkImage}
    }
    is(State.checkImage){
      val volume=snapshot.target===3.U;val array=snapshot.target===1.U;val cube=snapshot.target===2.U
      val dimensions=snapshot.rootWidth=/=0.U && snapshot.rootHeight=/=0.U && snapshot.rootDepth=/=0.U && snapshot.layers=/=0.U &&
        snapshot.rootWidth<=Mux(volume,p.max3D.U,p.max2D.U) && snapshot.rootHeight<=Mux(volume,p.max3D.U,p.max2D.U) &&
        snapshot.rootDepth<=p.max3D.U && (volume || snapshot.rootDepth===1.U) &&
        Mux(array,snapshot.layers<=p.maxLayers.U,snapshot.layers===1.U) && (!cube || snapshot.rootWidth===snapshot.rootHeight)
      lastLevel:=TextureImageMipRange.last(snapshot.rootWidth,snapshot.rootHeight,snapshot.rootDepth,snapshot.target,snapshot.baseLevel,snapshot.maxLevel)
      when(stop){phase:=State.finish}
        .elsewhen(!TextureImageFormat.valid(snapshot.format)){status:=TextureImageFetchStatus.format.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .elsewhen(!TextureImageFormat.targetLegal(snapshot.format,snapshot.target) || !dimensions || snapshot.maxLevel<snapshot.baseLevel || !TextureImageSwizzle.legal(snapshot.swizzle)){status:=TextureImageResourceStatus.view.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .elsewhen(snapshot.allocationLimit<=snapshot.allocationBase || snapshot.allocationLimit>(BigInt(1)<<32).U){
          status:=TextureImageResourceStatus.bounds.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .otherwise{phase:=State.checkSelectedLevel}
    }
    is(State.checkSelectedLevel){
      when(stop){phase:=State.finish}
        .elsewhen(selectedLevel<snapshot.baseLevel || selectedLevel>lastLevel){status:=TextureImageResourceStatus.coordinate.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .otherwise{phase:=State.levelHandles}
    }
    is(State.levelHandles){
      // The explicit32-bit or captured4-bit base level was checked before
      // this narrowing. A32-bit
      // first handle plus a4-bit level fits33 bits, including its carry.
      firstHandle:=snapshot.firstLevelHandle.pad(33)+Mux(request.requireMipChain,snapshot.baseLevel,selectedLevel(3,0))
      lastHandle:=snapshot.firstLevelHandle.pad(33)+Mux(request.requireMipChain,lastLevel,selectedLevel(3,0))
      phase:=Mux(stop,State.finish,State.levelExtent)
    }
    is(State.levelExtent){
      // Preserve handle carry until bounds checking. The full shifted33-bit
      // index,32-bit base and128-byte exclusive end fit within41 bits.
      levelBegin:=snapshot.levelsBase.pad(41)+(firstHandle<<7)
      levelEnd:=snapshot.levelsBase.pad(41)+(lastHandle<<7)+128.U
      phase:=Mux(stop,State.finish,State.checkLevelTable)
    }
    is(State.checkLevelTable){
      val legal=snapshot.levelsVersion===1.U && snapshot.levelsBase(5,0)===0.U && snapshot.levelsLimit>snapshot.levelsBase &&
        snapshot.levelsLimit<=(BigInt(1)<<32).U && (snapshot.levelsLimit-snapshot.levelsBase)(6,0)===0.U &&
        !firstHandle(32) && !lastHandle(32) && levelBegin>=snapshot.levelsBase && levelEnd<=snapshot.levelsLimit
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=TextureImageResourceStatus.bounds.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .otherwise{currentLevel:=Mux(request.requireMipChain,snapshot.baseLevel,selectedLevel(3,0));phase:=State.prepareLevel}
    }
    is(State.prepareLevel){
      levelRequest:=0.U.asTypeOf(levelRequest);levelRequest.lookup.table.version:=snapshot.levelsVersion
      levelRequest.lookup.table.identity:=snapshot.identity;levelRequest.lookup.table.base:=snapshot.levelsBase;levelRequest.lookup.table.limit:=snapshot.levelsLimit
      levelRequest.lookup.handle:=snapshot.firstLevelHandle+currentLevel;levelRequest.lookup.level:=currentLevel;levelRequest.tag:=request.tag
      levelRequest.x:=width-1.U;levelRequest.y:=height-1.U;levelRequest.z:=depth-1.U
      levelRequest.layer:=Mux(snapshot.target===1.U,snapshot.layers-1.U,0.U);levelRequest.face:=Mux(snapshot.target===2.U,5.U,0.U)
      when(stop){phase:=State.finish}.otherwise{phase:=State.offerLevel}
    }
    is(State.offerLevel){when(child.io.in.fire){levelStarted:=true.B;phase:=State.waitLevel}}
    is(State.waitLevel){when(child.io.out.fire){
      levelResult:=child.io.out.bits;levelLines:=levelLines+child.io.out.bits.readLines;levelStarted:=false.B
      val code=child.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){
        // These coordinates come from the image header, so a coordinate
        // rejection indicates inconsistent level dimensions, not user input.
        status:=Mux(code===TextureImageResourceStatus.coordinate.U,TextureImageFetchStatus.format.U,code)
        faultStage:=Mux(code===0.U || code===TextureImageResourceStatus.cancelled.U,0.U,
          Mux(code===TextureImageResourceStatus.coordinate.U,TextureImageBindingFaultStage.layout.U,TextureImageBindingFaultStage.level.U))
        faultValid:=child.io.out.bits.faultAddressValid;faultAddress:=child.io.out.bits.faultAddress
      }
      phase:=Mux(stop || code=/=0.U,State.finish,State.verifyLevel)
    }}
    is(State.verifyLevel){
      val v=levelResult.view;val end=levelResult.address+&levelResult.bytes
      val matched=v.target===snapshot.target && v.texWidth===width && v.texHeight===height && v.texDepth===depth && v.layers===snapshot.layers &&
        v.allocationBase===snapshot.allocationBase && v.allocationLimit===snapshot.allocationLimit && v.block4x4===TextureImageFormat.block4x4(snapshot.format) && v.blockBytes===TextureImageFormat.bytes(snapshot.format)
      val overlap=(0 until 16).map(i=>i.U<checked && v.base<ends(i) && end>bases(i)).reduce(_ || _)
      when(stop){phase:=State.finish}.elsewhen(!matched || overlap){status:=TextureImageFetchStatus.format.U;faultStage:=TextureImageBindingFaultStage.layout.U;phase:=State.finish}
        .otherwise{bases(checked(3,0)):=v.base;ends(checked(3,0)):=end;checked:=checked+1.U
          when(currentLevel===selectedLevel){selectedView:=v;selectedLookup:=levelRequest.lookup;selectedValid:=true.B}
          phase:=State.advance}
    }
    is(State.advance){
      when(stop){phase:=State.finish}.elsewhen(!request.requireMipChain || currentLevel===lastLevel){phase:=State.finish}
        .otherwise{currentLevel:=currentLevel+1.U;phase:=State.prepareLevel}
    }
    is(State.finish){
      assert(drained && !child.io.busy,"image completion escaped descriptor drain")
      val fresh=WireDefault(0.U.asTypeOf(new TextureImageBindingResult))
      fresh.identity:=request.table.identity;fresh.handle:=request.handle;fresh.level:=request.level;fresh.tag:=request.tag
      fresh.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      fresh.faultStage:=faultStage;fresh.faultAddressValid:=faultValid;fresh.faultAddress:=Mux(faultValid,faultAddress,0.U)
      fresh.imageReadLines:=headerLines;fresh.levelReadLines:=levelLines;fresh.levelsChecked:=checked;fresh.hit:=hit
      when(!stop && !hit){assert(selectedValid,"successful image binding lacks selected level")
        fresh.validBinding:=true.B;fresh.level:=selectedLevel;fresh.format:=snapshot.format;fresh.baseLevel:=snapshot.baseLevel;fresh.lastLevel:=lastLevel
        fresh.mipChainChecked:=request.requireMipChain;fresh.selected:=selectedLookup;fresh.view:=selectedView;fresh.swizzle:=snapshot.swizzle}
      when(hit && !stop){fresh:=cachedResult;fresh.tag:=request.tag;fresh.hit:=true.B;fresh.imageReadLines:=0.U;fresh.levelReadLines:=0.U;fresh.levelsChecked:=0.U}
      result:=fresh
      when(!stop && !hit && !noFill && !io.invalidate){cached:=true.B;cachedKey:=request;cachedKey.tag:=0.U;cachedResult:=fresh}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(drained && !child.io.busy && !io.memoryRequest.valid,"held image binding must own drained children")}
}
