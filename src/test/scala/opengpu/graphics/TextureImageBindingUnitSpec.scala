package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class ImageMipRangeHarness extends Module {
  val io=IO(new Bundle {
    val rootWidth=Input(UInt(14.W));val rootHeight=Input(UInt(14.W));val rootDepth=Input(UInt(14.W))
    val target=Input(UInt(3.W));val baseLevel=Input(UInt(4.W));val maxLevel=Input(UInt(4.W));val valueOut=Output(UInt(4.W))
  })
  io.valueOut:=TextureImageMipRange.last(io.rootWidth,io.rootHeight,io.rootDepth,io.target,io.baseLevel,io.maxLevel)
}
private class ImageBindingHarness(fetch: Boolean=true,translated: Boolean=false) extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageBoundFetchRequest));val out=Decoupled(new TextureImageBoundFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
  })
  private val req=Wire(Decoupled(new ComputeMemoryRequest(config,64,8)))
  private val res=Wire(Flipped(Decoupled(new ComputeMemoryResponse(64,8))))
  if(fetch){val f=Module(new TextureImageBoundFetchUnit(config))
    f.io.in<>io.in;io.out<>f.io.out;f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate;io.busy:=f.io.busy
    req<>f.io.memoryRequest;f.io.memoryResponse<>res
  }else{val f=Module(new TextureImageBindingUnit(config))
    f.io.in.valid:=io.in.valid;f.io.in.bits:=io.in.bits.image;io.in.ready:=f.io.in.ready
    io.out.valid:=f.io.out.valid;io.out.bits:=0.U.asTypeOf(io.out.bits);io.out.bits.image:=f.io.out.bits;f.io.out.ready:=io.out.ready
    f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate;io.busy:=f.io.busy
    req.valid:=f.io.memoryRequest.valid;req.bits:=f.io.memoryRequest.bits;f.io.memoryRequest.ready:=req.ready
    f.io.memoryResponse.valid:=res.valid;f.io.memoryResponse.bits:=res.bits;res.ready:=f.io.memoryResponse.ready
  }
  if(translated){val vm=Module(new TranslatedLineClient(config,8,preserveCachePolicy=true))
    vm.io.in<>req;res<>vm.io.out;vm.io.satp:=io.satp;vm.io.flush<>io.flush
    val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
    arb.io.in(0).valid:=vm.io.memReq.valid;arb.io.in(0).bits:=vm.io.memReq.bits
    arb.io.in(0).bits.transactionId:=vm.io.memReq.bits.transactionId.pad(4);vm.io.memReq.ready:=arb.io.in(0).ready
    arb.io.in(1).valid:=vm.io.pageWalk.valid;arb.io.in(1).bits:=vm.io.pageWalk.bits;arb.io.in(1).bits.transactionId:=8.U
    vm.io.pageWalk.ready:=arb.io.in(1).ready;io.memoryRequest<>arb.io.out
    val walk=io.memoryResponse.bits.transactionId===8.U
    vm.io.memResp.valid:=io.memoryResponse.valid && !walk;vm.io.memResp.bits:=io.memoryResponse.bits
    vm.io.pageWalkResp.valid:=io.memoryResponse.valid && walk;vm.io.pageWalkResp.bits:=io.memoryResponse.bits;vm.io.pageWalkResp.bits.transactionId:=0.U
    io.memoryResponse.ready:=Mux(walk,vm.io.pageWalkResp.ready,vm.io.memResp.ready)
  }else{io.memoryRequest.valid:=req.valid;io.memoryRequest.bits:=req.bits;req.ready:=io.memoryRequest.ready
    res.valid:=io.memoryResponse.valid;res.bits:=io.memoryResponse.bits;io.memoryResponse.ready:=res.ready}
}

class TextureImageBindingUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val end4G=BigInt(1)<<32
  it should "derive mip ranges against original dimension reduction for every root size and base level" in {
    simulate(new ImageMipRangeHarness){d=>var cases=0
      def roots(n: Int,target: Int): Unit={
        val w=if(n==0)0 else if(target==1)1 else if(target==3)1.max(n/3)else n
        val h=if(n==0)0 else if(target==0)1 else if(target==3)1.max(n/2)else n
        val z=if(n==0)0 else if(target==3)n else 1
        d.io.rootWidth.poke(w.U);d.io.rootHeight.poke(h.U);d.io.rootDepth.poke(z.U);d.io.target.poke(target.U)
      }
      def check(n: Int,base: Int,maximum: Int): Unit={
        // Independent original rule: reduce dimensions first, then count
        // the remaining positive powers of two and apply the maximum clamp.
        val reduced=1.max(n/(1<<base));var size=reduced;var levels=0
        while(size>1){size/=2;levels+=1}
        val expected=maximum.min(base+levels)
        d.io.baseLevel.poke(base.U);d.io.maxLevel.poke(maximum.U);d.clock.step();d.io.valueOut.expect(expected.U);cases+=1
      }
      for(n<-0 until 16384){roots(n,n%4);for(base<-0 until 16)check(n,base,(n+base*7)%16)}
      val edges=(Seq(0,1,16383)++(0 until 14).flatMap(k=>Seq((1<<k)-1,1<<k,(1<<k)+1))).filter(n=>n>=0 && n<16384).distinct
      for(n<-edges;target<-0 to 3){roots(n,target);for(base<-0 until 16;maximum<-0 until 16)check(n,base,maximum)}
      info(s"IMAGE_BINDING_MIP_RANGE cases=$cases every14BitRoot=true bases16=true allFourTargets=true allMaxClampsAtPowerBoundaries=true originalDimensionReductionOracle=true zeroRootDefined=true")
    }
  }
  private case class View(base: BigInt,w: Int,h: Int,d: Int,layers: Int,row: BigInt,slice: BigInt,layer: BigInt,bytes: Int,target: Int) {
    def address(x: Int=0,y: Int=0,z: Int=0,l: Int=0,face: Int=0): BigInt=base+x*bytes+y*row+z*slice+(if(target==2)face else l)*layer
    def end: BigInt=address(w-1,h-1,d-1,layers-1,if(target==2)5 else 0)+bytes
  }
  private case class Image(target: Int,format: Int,baseLevel: Int,maxLevel: Int,w: Int,h: Int,d: Int,layers: Int,
    lo: BigInt,hi: BigInt,views: Map[Int,View],levelsBase: BigInt=0x2000,levelsLimit: BigInt=0x4000,first: BigInt=3) {
    def last: Int={val size=Seq(1.max(w>>baseLevel),1.max(h>>baseLevel),if(target==3)1.max(d>>baseLevel)else 1).max
      maxLevel.min(baseLevel+(31-java.lang.Integer.numberOfLeadingZeros(size)))}
    def levelAddress(level: Int): BigInt=levelsBase+(first+level)*128
  }
  private def image(target: Int=0,format: Int=35,baseLevel: Int=0,maxLevel: Int=15,w: Int=7,h: Int=5,d: Int=3,layers: Int=3,
    reverse: Boolean=false): Image={
    val bytes=TypedColorReference.format(TypedColorReference.names(format)).bytes
    var cursor=BigInt(0x100003);val views=mutable.Map.empty[Int,View]
    for(level<-(if(reverse)(0 until 16).reverse else 0 until 16)){
      val width=1.max(w>>level);val height=1.max((if(target==2)w else h)>>level)
      val depth=if(target==3)1.max(d>>level)else 1;val n=if(target==1)layers else 1
      val row=BigInt(width*bytes+3);val plane=(height-1)*row+width*bytes
      val slice=if(target==3)plane+7 else BigInt(0);val layer=if(target==1 || target==2)plane+11 else BigInt(0)
      val v=View(cursor+(level*13)%64,width,height,depth,n,row,slice,layer,bytes,target);views(level)=v;cursor=v.end+29
    }
    Image(target,format,baseLevel,maxLevel,w,if(target==2)w else h,if(target==3)d else 1,if(target==1)layers else 1,
      0x100000,cursor+65,views.toMap)
  }
  private class Driver(val d: ImageBindingHarness,val fetch: Boolean=true) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val replies=mutable.ArrayBuffer.empty[(BigInt,BigInt)];var lastTag=BigInt(0);var lastLevel=BigInt(0);var lastHandle=BigInt(0);var lastContext=7
    memory.beforeRequest=r=>{assert(!r.write && r.tag<=8)
      if(r.tag!=8){assert(r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
        d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)}}
    def tick(): Unit={memory.drive();if(memory.reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=memory.reply.get;replies+=((r.address,r.tag))}
      memory.capture();d.clock.step()}
    def until(p: =>Boolean,limit: Int=4000): Unit={var n=0;while(!p && n<limit){tick();n+=1};assert(p,s"binding timeout tag=$lastTag reads=${memory.seen.takeRight(8)}")}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
    def install(i: Image,header: BigInt=0x1000,levelPhysical: BigInt= -1): Unit={
      // Explicit GXI1 and GXL1 encodings are independent of generated layout fields.
      val words=Vector[BigInt](BigInt("47584901",16),128,1,7,11,13,0,i.target,i.format,i.baseLevel,i.maxLevel,
        i.w,i.h,i.d,i.layers,i.lo,i.hi&0xffffffffL,i.hi>>32,1,i.levelsBase,i.levelsLimit&0xffffffffL,i.levelsLimit>>32,i.first).padTo(32,BigInt(0))
      for((word,n)<-words.zipWithIndex)memory.putWord(header+4*n,word)
      for((level,v)<-i.views){val a=if(levelPhysical<0)i.levelAddress(level)else levelPhysical+(i.first+level)*128
        val data=Vector[BigInt](BigInt("47584c01",16),128,1,7,11,13,i.first+level,i.target,level,i.lo,i.hi&0xffffffffL,i.hi>>32,
          v.base,v.w,v.h,v.d,v.layers,v.row&0xffffffffL,v.row>>32,v.slice&0xffffffffL,v.slice>>32,v.layer&0xffffffffL,v.layer>>32,0,v.bytes).padTo(32,BigInt(0))
        for((word,n)<-data.zipWithIndex)memory.putWord(a+4*n,word)}
    }
    def payload(a: BigInt,bytes: Int,raw: BigInt): Unit={val lo=a& ~BigInt(63);val hi=(a+bytes-1)& ~BigInt(63)
      for(n<-0 until(hi-lo+64).toInt)memory.bytes(lo+n)=0xa7
      for(n<-0 until bytes)memory.bytes(a+n)=((raw>>(8*n))&255).toInt}
    def query(level: BigInt=0,chain: Boolean=true,tag: BigInt=0xabc00005L,x: BigInt=0,y: BigInt=0,z: Int=0,layer: Int=0,face: Int=0,
      table: BigInt=0x1000,limit: BigInt=0x1800,version: Int=1,handle: BigInt=0,context: Int=7): Unit={
      val q=d.io.in.bits;zero(q);q.image.table.version.poke(version.U);identity(q.image.table.identity)
      q.image.table.base.poke(table.U);q.image.table.limit.poke(limit.U);q.image.handle.poke(handle.U)
      q.image.table.identity.context.poke(context.U)
      q.image.level.poke(level.U);q.image.tag.poke(tag.U);q.image.requireMipChain.poke(chain.B)
      q.x.poke(x.U);q.y.poke(y.U);q.z.poke(z.U);q.layer.poke(layer.U);q.face.poke(face.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
      lastTag=tag;lastLevel=level;lastHandle=handle;lastContext=context
    }
    def check(status: Int=0,i: Image=image(),raw: BigInt=0,address: BigInt=0,stage: Int=0,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;val b=o.image
      b.status.expect(status.U);b.tag.expect(lastTag.U);b.level.expect(lastLevel.U);b.handle.expect(lastHandle.U);b.identity.context.expect(lastContext.U)
      b.identity.pass.expect(11.U);b.identity.generation.expect(13.U);b.validBinding.expect((status==0).B);b.faultStage.expect(stage.U)
      b.faultAddressValid.expect(fault.nonEmpty.B);b.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      if(status==0){val v=i.views(lastLevel.toInt);b.format.expect(i.format.U);b.baseLevel.expect(i.baseLevel.U);b.lastLevel.expect(i.last.U)
        b.selected.table.base.expect(i.levelsBase.U);b.selected.table.limit.expect(i.levelsLimit.U);b.selected.table.version.expect(1.U)
        b.selected.handle.expect((i.first+lastLevel).U);b.selected.level.expect(lastLevel.U);b.view.base.expect(v.base.U)
        b.view.texWidth.expect(v.w.U);b.view.texHeight.expect(v.h.U);b.view.texDepth.expect(v.d.U);b.view.layers.expect(v.layers.U)
        if(fetch){val s=o.sample.sample;s.status.expect(0.U);s.viewValid.expect(true.B);s.address.expect(address.U);s.bytes.expect(v.bytes.U);s.raw.expect(raw.U)
          val name=TypedColorReference.names(i.format);o.sample.rgba.zip(TypedBlendReference.decode(name,raw)).foreach{case(a,b)=>a.expect(b.U)}
          o.sample.colorType.expect(TypedColorReference.format(name).kind.U)}
      }else{b.format.expect(0.U);assert(values(b.view).values.forall(_==0));assert(values(b.selected).values.forall(_==0))
        if(fetch){o.sample.sample.status.expect(status.U);o.sample.sample.viewValid.expect(false.B);o.sample.sample.address.expect(0.U);o.sample.sample.raw.expect(0.U)
          o.sample.rgba.foreach(_.expect(0.U));o.sample.colorType.expect(0.U)}}
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.busy.expect(true.B);d.io.in.ready.expect(false.B)
    }
    def pop(): Unit={d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)}
    def dataReads: Int=memory.seen.count(r=>r.tag==6 || r.tag==7)
  }
  it should "bind authoritative formats and validate complete target mip chains before fetching typed texels" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val random=new scala.util.Random(0x47584901);var queries=0
      for(target<-0 to 3;format<-0 until 36){val i=image(target,format,reverse=(format%2==0));val level=format%3;val v=i.views(level)
        val x=v.w-1;val y=v.h-1;val z=v.d-1;val l=v.layers-1;val face=if(target==2)5 else 0;val a=v.address(x,y,z,l,face)
        val raw=BigInt(v.bytes*8,random);t.install(i);t.payload(a,v.bytes,raw);t.invalidate()
        t.memory.holdResponses=true;t.query(level,tag=0xfedc0000L+queries,x=x,y=y,z=z,layer=l,face=face)
        t.until(t.memory.pending.size==2);for(_<-0 until 8)t.tick();val before=t.replies.size;t.memory.holdResponses=false
        t.check(i=i,raw=raw,address=a);assert(t.replies.slice(before,before+2)==Seq((BigInt(0x1040),BigInt(3)),(BigInt(0x1000),BigInt(2))))
        d.io.out.bits.image.levelsChecked.expect(3.U);d.io.out.bits.image.levelReadLines.expect(6.U);d.io.out.bits.image.mipChainChecked.expect(true.B)
        val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
        for(_<-0 until 3){t.tick();assert(d.io.out.bits.peek().litValue==held)}
        t.pop();d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B);queries+=1}
      assert(t.memory.seen.exists(_.tag==0) && t.memory.seen.exists(_.tag==7))
      info(s"IMAGE_BINDING_FETCH targets4=true formats36=true queries=$queries completeNPOTMipChains=true reversedDisjointLayouts=true authoritativeFormatNoCallerFormat=true allCubeFacesAndArrayVolume=true exactGuardedTypedTexels=true disjointThreeOwnerTags=true reorderedImageHeader=true immutableHeldResults=true")
    }
  }
  it should "apply base max and dimensional mip rules and distinguish selected-only from full-chain cache identities" in {
    simulate(new ImageBindingHarness(fetch=false)){d=>val t=new Driver(d,fetch=false);var queries=0
      val cases=(0 to 3).map(target=>image(target,format=0,baseLevel=2,w=17,h=9,d=5))++Seq(
        image(1,format=0,w=1,h=1,layers=256),image(0,format=0,maxLevel=1,w=17,h=9),image(0,format=0,baseLevel=15,w=1,h=1),
        image(0,format=0,w=16383,h=16383))
      for(i<-cases){t.install(i);t.invalidate();t.query(i.last);t.check(i=i)
        d.io.out.bits.image.levelsChecked.expect((i.last-i.baseLevel+1).U);t.pop();queries+=1}
      val i=image(baseLevel=1,maxLevel=2);t.install(i);t.invalidate();t.memory.putWord(i.levelAddress(1),0)
      t.query(level=2,chain=false);t.check(i=i);d.io.out.bits.image.levelsChecked.expect(1.U);d.io.out.bits.image.mipChainChecked.expect(false.B);t.pop()
      val n=t.memory.seen.size;t.query(level=2,chain=false,tag=6);t.check(i=i);d.io.out.bits.image.hit.expect(true.B)
      d.io.out.bits.image.imageReadLines.expect(0.U);d.io.out.bits.image.levelReadLines.expect(0.U);t.pop();assert(t.memory.seen.size==n)
      t.query(level=2,chain=true);t.check(7,stage=2);t.pop()
      t.install(i);t.query(level=2,chain=true);t.check(i=i);t.pop()
      t.query(level=1,chain=true);t.check(i=i);d.io.out.bits.image.hit.expect(false.B);t.pop()
      t.query(level=1,version=0);t.check(1,stage=4);t.pop()
      for(level<-Seq(BigInt(0),BigInt(3),BigInt(16),BigInt(0xffffffffL))){val n=t.dataReads;t.query(level);t.check(5,stage=4);t.pop();assert(t.dataReads==n)}
      info(s"IMAGE_BINDING_MIPS profiles=$queries baseMaxClamp=true dimensions16383=true layersDoNotShrinkOrExtendChain=true volumeDepthShrinks=true baseLevel15=true selectedOnlyAllowsUnusedMissingLevels=true modeAndLevelInCacheKey=true tagExcludedFromKey=true fullWidthLevelRejects=true")
    }
  }
  it should "reject malformed image encoding and inconsistent later mips without any texture-data access" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val i=image();val corrupt=Seq(0->BigInt(0),1->BigInt(64),2->BigInt(2),3->BigInt(8),6->BigInt(1),
        7->BigInt(8),8->BigInt(64),9->BigInt(16),11->BigInt(16384),14->BigInt(65536),17->BigInt(2),18->BigInt(256),21->BigInt(2))++(23 until 32).map(_->BigInt(1))
      for((word,bits)<-corrupt){t.install(i);t.memory.putWord(0x1000+4*word,bits);t.invalidate();val n=t.dataReads;t.query();t.check(7,stage=4);t.pop();assert(t.dataReads==n)}
      for((word,bits,status)<-Seq((8,BigInt(36),6),(7,BigInt(4),1),(11,BigInt(0),1),(10,BigInt(0),0),
        (16,BigInt(0),2),(18,BigInt(0),2),(19,BigInt(0x2001),2),(20,BigInt(0x2080),2)) if status!=0){
        t.install(i);t.memory.putWord(0x1000+4*word,bits);t.invalidate();val n=t.dataReads;val before=t.memory.seen.size
        t.query();t.check(status,stage=4);t.pop();assert(t.dataReads==n)
        if(word==8)assert(!t.memory.seen.drop(before).exists(_.tag<2))}
      val later=i.levelAddress(2)
      for((word,bits)<-Seq(13->BigInt(3),14->BigInt(0),24->BigInt(8),9->BigInt(0),16->BigInt(2),12->i.views(0).base)){
        t.install(i);t.memory.putWord(later+word*4,bits);t.invalidate();val n=t.dataReads;t.query(level=0)
        t.until(d.io.out.valid.peek().litToBoolean);assert(d.io.out.bits.image.status.peek().litValue!=0);d.io.out.bits.image.validBinding.expect(false.B)
        assert(t.dataReads==n);assert(values(d.io.out.bits.image.view).values.forall(_==0));d.io.out.bits.sample.rgba.foreach(_.expect(0.U));t.pop()}
      t.install(i);val a=i.views(0).base;t.payload(a,16,42);t.invalidate();t.query();t.check(i=i,raw=42,address=a);t.pop()
      info(s"IMAGE_BINDING_REJECT encodingCorruptions=${corrupt.size} invalidFormatBeforeLevelReads=true levelDimensionFormatAllocationTargetMismatch=true laterMipOverlap=true noDataBeforeCompleteChain=true noPartialBinding=true exactHealthyReuse=true")
    }
  }
  it should "require explicit invalidation retain published formats and prevent invalidated fills from populating the cache" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val old=image(format=3,maxLevel=0);t.install(old);val a=old.views(0).base
      val raw=BigInt(0x3f800000);t.payload(a,4,raw);t.query();t.check(i=old,raw=raw,address=a);t.pop()
      val fresh=old.copy(format=29) // R32F has identical four-byte storage and a distinct interpretation.
      assert(TypedColorReference.names(fresh.format)=="R32F");t.install(fresh)
      val n=t.memory.seen.size;t.query(tag=6);t.check(i=old,raw=raw,address=a);d.io.out.bits.image.hit.expect(true.B);t.pop()
      assert(t.memory.seen.size==n+1)
      t.invalidate();t.memory.holdResponses=true;t.query(tag=7);t.until(t.memory.pending.size==2);t.invalidate()
      for(_<-0 until 8)t.tick();t.memory.holdResponses=false;t.check(i=fresh,raw=raw,address=a);t.pop()
      t.query(tag=8);t.check(i=fresh,raw=raw,address=a);d.io.out.bits.image.hit.expect(false.B);t.pop()
      t.query(tag=9);t.check(i=fresh,raw=raw,address=a);d.io.out.bits.image.hit.expect(true.B);t.pop()
      info("IMAGE_BINDING_CACHE formatIdentityBeyondByteSize=true immutablePublishedSnapshot=true sameAddressRebindInvalidation=true invalidateDuringFill=true noStaleRepopulation=true uncachedFreshTexelReads=true exactHealthyReuse=true")
    }
  }
  it should "attribute header level and texel faults retain primary errors and stop prompt-fault descriptor issue" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val i=image(maxLevel=0);val a=i.views(0).base;t.install(i);t.payload(a,16,42)
      for((fault,stage)<-Seq((BigInt(0x1000),1),(BigInt(0x1040),1),(i.levelAddress(0),2),(i.levelAddress(0)+64,2),(a& ~BigInt(63),3))){
        t.invalidate();t.memory.faultAt=Set(fault);t.query();t.check(3,stage=stage,fault=Some(fault));t.pop()
        t.memory.faultAt=Set.empty;t.query();t.check(i=i,raw=42,address=a);t.pop()}
      t.invalidate();t.memory.delay=0;t.memory.faultAt=Set(BigInt(0x1000));val n=t.memory.seen.size
      t.query();t.check(3,stage=1,fault=Some(BigInt(0x1000)));t.pop();assert(t.memory.seen.size==n+1)
      t.memory.faultAt=Set(i.levelAddress(0));val before=t.memory.seen.size;t.query();t.check(3,stage=2,fault=Some(i.levelAddress(0)));t.pop()
      assert(t.memory.seen.drop(before).count(_.tag<2)==1,"prompt GXL1 fault created an additional descriptor offer")
      t.memory.faultAt=Set.empty;t.memory.delay=4;t.query();t.check(i=i,raw=42,address=a);t.pop()
      info("IMAGE_BINDING_FAULT imageAndLevelAndDataStages=true virtualFaultAddress=true firstFaultPrimary=true promptHeaderStopsIssue=true promptLevelStopsIssue=true allTagsDrained=true exactHealthyReuse=true")
    }
  }
  it should "retain held offers and cancel across complete mip admission texel fetch decode and held completion" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val i=image();val a=i.views(0).base;t.install(i);t.payload(a,16,17)
      t.memory.holdRequests=true;t.query();t.until(d.io.memoryRequest.valid.peek().litToBoolean);val held=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 13){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.memoryRequest.valid.expect(true.B);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4);t.pop();d.io.cancel.poke(false.B)
      var cancelled=0;var complete=0
      for(cut<-0 until 240){t.invalidate();t.query();for(_<-0 until cut)t.tick();val done=d.io.out.valid.peek().litToBoolean
        val held=if(done)d.io.out.bits.peek().litValue else BigInt(0);d.io.cancel.poke(true.B)
        if(done){t.check(i=i,raw=17,address=a);assert(d.io.out.bits.peek().litValue==held);complete+=1}else{t.check(4);cancelled+=1}
        t.pop();d.io.cancel.poke(false.B);t.query();t.check(i=i,raw=17,address=a);t.pop()}
      assert(cancelled>0 && complete>0)
      info(s"IMAGE_BINDING_CANCEL cuts240=true cancelled=$cancelled alreadyHeld=$complete heldHeaderOffer13=true noAbandonedImageLevelTexelDecoder=true allLeasesHeldThroughCompletion=true exactHealthyReuse=true")
    }
  }
  it should "translate image level and cross-page texel records and enforce each read permission before physical access" in {
    simulate(new ImageBindingHarness(translated=true)){d=>val t=new Driver(d)
      val root=BigInt(0x90000);val pt=BigInt(0xa0000);t.memory.putWord(root,(pt>>12)<<10|1)
      def map(vpn: Int,pa: BigInt,flags: Int=0x43): Unit=t.memory.putWord(pt+vpn*4,(pa>>12)<<10|flags)
      map(1,0x50000);map(2,0x60000);map(0x100,0x70000);map(0x101,0x80000)
      d.io.satp.poke(((BigInt(1)<<31)|(root>>12)).U)
      val v=View(0x100ffc,1,1,1,1,16,0,0,16,0);val i=image(w=1,h=1,maxLevel=0).copy(lo=v.base,hi=v.end,views=Map(0->v))
      t.install(i,header=0x50000,levelPhysical=0x60000);val raw=BigInt("fedcba98765432100123456789abcdef",16)
      for(n<-0 until 16){val pa=if(n<4)BigInt(0x70ffc)+n else BigInt(0x80000)+n-4;t.memory.bytes(pa)=((raw>>(8*n))&255).toInt}
      t.query();t.check(i=i,raw=raw,address=v.base);t.pop()
      def flush(): Unit={zero(d.io.flush.bits);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B);t.invalidate()}
      for((vpn,pa,stage,fault)<-Seq((1,BigInt(0x50000),1,BigInt(0x1000)),(2,BigInt(0x60000),2,i.levelAddress(0)),
        (0x101,BigInt(0x80000),3,BigInt(0x101000)))){
        map(vpn,pa,0x49);flush();val n=t.memory.seen.size;t.query();t.check(3,stage=stage,fault=Some(fault));t.pop()
        assert(!t.memory.seen.drop(n).exists(r=>r.tag<8 && r.address>=pa && r.address<pa+4096))
        map(vpn,pa);flush();t.query();t.check(i=i,raw=raw,address=v.base);t.pop()}
      info("IMAGE_BINDING_VM realSv32=true reservedWalkerID8=true imageAndLevelAndDataPermissions=true crossPageNoncontiguousData=true noDeniedPhysicalRead=true virtualFaultAttribution=true flushRecovery=true")
    }
  }
  it should "own exact 4 GiB descriptor and texel ends reject invalid image tables and retain context cache identity" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val i=image(w=1,h=1,maxLevel=0);val a=i.views(0).base
      t.install(i,header=end4G-128);t.payload(a,16,17);t.query(table=end4G-128,limit=end4G);t.check(i=i,raw=17,address=a);t.pop()
      val high=i.copy(levelsBase=end4G-128,levelsLimit=end4G,first=0,views=Map(0->i.views(0)))
      t.install(high);t.query();t.check(i=high,raw=17,address=a);t.pop()
      val v=View(0,2048,2048,1,256,8192,0,16777216,4,1)
      val full=image(target=1,format=3,w=2048,h=2048,layers=256,maxLevel=0).copy(lo=0,hi=end4G,views=Map(0->v),first=0)
      t.install(full);t.payload(end4G-4,4,0x3f800000);t.invalidate();t.query(x=2047,y=2047,layer=255)
      t.check(i=full,raw=0x3f800000,address=end4G-4);t.pop()
      val n=t.memory.seen.size
      for((base,limit,handle)<-Seq((BigInt(0x1001),BigInt(0x1801),BigInt(0)),(BigInt(0x1000),BigInt(0x10c0),BigInt(0)),
        (BigInt(0x1000),BigInt(0x1800),BigInt(0xffffffffL)),(end4G-128,end4G,BigInt(1)),
        (BigInt(0x1000),end4G+128,BigInt(0)),(BigInt(0x1000),BigInt(0x1000),BigInt(0)))){
        t.query(table=base,limit=limit,handle=handle);t.check(1,stage=4);t.pop()}
      assert(t.memory.seen.size==n)
      t.install(i);t.invalidate();t.query();t.check(i=i,raw=17,address=a);t.pop()
      t.query(context=8);t.check(7,stage=4);t.pop()
      t.memory.putWord(0x1000+12,8);for(level<-i.views.keys)t.memory.putWord(i.levelAddress(level)+12,8)
      t.query(context=8);t.check(i=i,raw=17,address=a);d.io.out.bits.image.hit.expect(false.B);t.pop()
      t.query(context=8,tag=6);t.check(i=i,raw=17,address=a);d.io.out.bits.image.hit.expect(true.B);t.pop()
      info("IMAGE_BINDING_BOUNDS exactHeader4GiBEnd=true exactLevelTable4GiBEnd=true fullArray4GiBTexelEnd=true invalidImageEnvelopes6BeforeReads=true noHandleWrap=true contextIdentityBeforeCache=true exactHealthyReuse=true")
    }
  }
  it should "preserve primary faults across nested cancellation including address zero and reject full-width caller coordinates before data" in {
    simulate(new ImageBindingHarness()){d=>val t=new Driver(d);val i=image(maxLevel=0);val a=i.views(0).base
      t.install(i);t.payload(a,16,17)
      for((fault,stage)<-Seq((BigInt(0x1000),1),(i.levelAddress(0),2),(a& ~BigInt(63),3))){
        t.invalidate();t.memory.faultAt=Set(fault);val before=t.replies.size;t.query()
        t.until(t.replies.drop(before).exists(_._1==fault));d.io.cancel.poke(true.B)
        t.check(3,stage=stage,fault=Some(fault));t.pop();d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty
        t.query();t.check(i=i,raw=17,address=a);t.pop()}
      // Independently exercise a zero address at all three nested fault owners.
      val tiny=image(w=1,h=1,maxLevel=0);t.install(tiny,header=0);t.invalidate();t.memory.faultAt=Set(BigInt(0))
      val h=t.replies.size;t.query(table=0,limit=128);t.until(t.replies.drop(h).exists(_._1==0));d.io.cancel.poke(true.B)
      t.check(3,stage=1,fault=Some(BigInt(0)));t.pop();d.io.cancel.poke(false.B)
      val zeroLevel=tiny.copy(levelsBase=0,levelsLimit=128,first=0,views=Map(0->tiny.views(0)))
      t.install(zeroLevel);t.invalidate();val l=t.replies.size;t.query();t.until(t.replies.drop(l).exists(_._1==0));d.io.cancel.poke(true.B)
      t.check(3,stage=2,fault=Some(BigInt(0)));t.pop();d.io.cancel.poke(false.B)
      val v=View(0,1,1,1,1,4,0,0,4,0);val zeroData=image(format=3,w=1,h=1,maxLevel=0).copy(lo=0,hi=4,views=Map(0->v))
      t.install(zeroData);t.payload(0,4,0x3f800000);t.invalidate();val z=t.replies.size;t.query()
      t.until(t.replies.drop(z).exists(_._1==0));d.io.cancel.poke(true.B);t.check(3,stage=3,fault=Some(BigInt(0)))
      t.pop();d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty;t.query();t.check(i=zeroData,raw=0x3f800000,address=0);t.pop()
      t.install(i);t.invalidate();t.query();t.check(i=i,raw=17,address=a);t.pop()
      for((x,y,z,l,face)<-Seq((BigInt(0xffffffffL),BigInt(0),0,0,0),(BigInt(0),BigInt(0xffffffffL),0,0,0),
        (BigInt(7),BigInt(0),0,0,0),(BigInt(0),BigInt(5),0,0,0),(BigInt(0),BigInt(0),1,0,0),
        (BigInt(0),BigInt(0),0,1,0),(BigInt(0),BigInt(0),0,0,1))){
        val before=t.dataReads;t.query(x=x,y=y,z=z,layer=l,face=face);t.check(5,stage=5);t.pop();assert(t.dataReads==before)}
      t.query();t.check(i=i,raw=17,address=a);t.pop()
      info("IMAGE_BINDING_BOUNDARY earlierFaultBeforeNestedCancelStages3=true validZeroFaultAddressStages3=true noTruncatedCallerCoordinates=true invalidAxisCases7=true noInvalidTexelReads=true zeroErrorViewsAndColors=true exactHealthyReuse=true")
    }
  }
}
