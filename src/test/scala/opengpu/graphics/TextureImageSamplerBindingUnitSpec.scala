package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class ImageSamplerHarness(composed: Boolean=true,translated: Boolean=false) extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageSamplerBindingRequest));val out=Decoupled(new TextureImageSamplerBindingResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
  })
  private val req=Wire(Decoupled(new ComputeMemoryRequest(config,64,8)))
  private val res=Wire(Flipped(Decoupled(new ComputeMemoryResponse(64,8))))
  if(composed){val f=Module(new TextureImageSamplerBindingUnit(config))
    f.io.in<>io.in;io.out<>f.io.out;f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate;io.busy:=f.io.busy
    req<>f.io.memoryRequest;f.io.memoryResponse<>res
  }else{val f=Module(new TextureSamplerBindingUnit(config))
    f.io.in.valid:=io.in.valid;io.in.ready:=f.io.in.ready;f.io.in.bits.table:=io.in.bits.samplers
    f.io.in.bits.handle:=io.in.bits.samplerHandle;f.io.in.bits.tag:=io.in.bits.tag
    io.out.valid:=f.io.out.valid;f.io.out.ready:=io.out.ready;io.out.bits:=0.U.asTypeOf(io.out.bits)
    io.out.bits.sampler:=f.io.out.bits;io.out.bits.status:=f.io.out.bits.status;io.out.bits.validBinding:=f.io.out.bits.validSampler
    io.out.bits.faultAddressValid:=f.io.out.bits.faultAddressValid;io.out.bits.faultAddress:=f.io.out.bits.faultAddress
    f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate;io.busy:=f.io.busy
    req.valid:=f.io.memoryRequest.valid;req.bits:=f.io.memoryRequest.bits;req.bits.transactionId:=4.U;f.io.memoryRequest.ready:=req.ready
    f.io.memoryResponse.valid:=res.valid;f.io.memoryResponse.bits:=res.bits;f.io.memoryResponse.bits.transactionId:=0.U;res.ready:=f.io.memoryResponse.ready
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

class TextureImageSamplerBindingUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val end4G=BigInt(1)<<32
  private class Driver(val d: ImageSamplerHarness,val composed: Boolean=true) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>{assert(!r.write && r.tag<=8)
      if(r.tag!=8){assert(r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
        d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)}}
    var currentTag=BigInt(0)
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean,limit: Int=4000): Unit={var n=0;while(!p && n<limit){tick();n+=1};assert(p,s"image/sampler timeout reads=${memory.seen.takeRight(6)}")}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
    def sampler(min: Int=0,mag: Int=0,s: Int=0,t: Int=0,r: Int=0,lo: BigInt=0xc47a0000L,hi: BigInt=0x447a0000L,
      compare: Int=0,function: Int=3,base: BigInt=0x3000,handle: BigInt=0,context: Int=7): Unit={
      val words=Seq[BigInt](BigInt("47585301",16),64,1,context,11,13,handle,min,mag,s,t,r,lo,hi,compare,function)
      for((w,n)<-words.zipWithIndex)memory.putWord(base+handle*64+n*4,w)
    }
    def image(format: Int=3,base: BigInt=0x1000,handle: BigInt=0,brokenMip: Boolean=false,levels: BigInt=0x2000): Unit={
      val bytes=TypedColorReference.format(TypedColorReference.names(format)).bytes
      val words=Seq[BigInt](BigInt("47584901",16),128,1,7,11,13,handle,0,format,0,15,4,4,1,1,
        0x100000,0x110000,0,1,levels,levels+384,0,0).padTo(32,BigInt(0))
      for((w,n)<-words.zipWithIndex)memory.putWord(base+handle*128+n*4,w)
      for(level<-0 to 2){val size=4>>level;val row=size*bytes
        val data=Seq[BigInt](BigInt("47584c01",16),128,1,7,11,13,level,0,level,0x100000,0x110000,0,
          0x100000+level*0x1000,size,if(brokenMip && level==2)2 else size,1,1,row,0,0,0,0,0,0,bytes).padTo(32,BigInt(0))
        for((w,n)<-data.zipWithIndex)memory.putWord(levels+level*128+n*4,w)}
    }
    def query(tag: BigInt=0x80000001L,level: BigInt=0,kind: Int=0,sbase: BigInt=0x3000,slimit: BigInt=0x4000,
      shandle: BigInt=0,ibase: BigInt=0x1000,ilimit: BigInt=0x1800,ihandle: BigInt=0,context: Int=7,sversion: Int=1): Unit={
      val q=d.io.in.bits;zero(q);identity(q.images.identity);identity(q.samplers.identity);q.samplers.identity.context.poke(context.U)
      q.images.version.poke(1.U);q.images.base.poke(ibase.U);q.images.limit.poke(ilimit.U)
      q.samplers.version.poke(sversion.U);q.samplers.base.poke(sbase.U);q.samplers.limit.poke(slimit.U)
      q.imageHandle.poke(ihandle.U);q.samplerHandle.poke(shandle.U);q.level.poke(level.U);q.tag.poke(tag.U);q.expectedType.poke(kind.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q);currentTag=tag
    }
    def check(status: Int=0,source: Int=0,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;withClue(s"tag=$currentTag expectedStatus=$status "){o.status.expect(status.U)};o.validBinding.expect((status==0).B)
      o.faultAddressValid.expect(fault.nonEmpty.B);o.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      if(composed){o.tag.expect(currentTag.U);o.faultSource.expect(source.U);identityCheck(o.identity)
        if(status!=0){assert(values(o.sampler).values.forall(_==0));assert(values(o.image).values.forall(_==0))}}
      else{o.sampler.tag.expect(currentTag.U);if(status!=0){o.sampler.validSampler.expect(false.B);assert(values(o.sampler.state).values.forall(_==0))}}
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.busy.expect(true.B);d.io.in.ready.expect(false.B)
    }
    def identityCheck(x: GraphicsWorkIdentity): Unit={x.context.expect(7.U);x.pass.expect(11.U);x.generation.expect(13.U)}
    def pop(): Unit={d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)}
  }
  it should "load all sampler filter wrap combinations with exact LOD bits and tag independent immutable cache reuse" in {
    simulate(new ImageSamplerHarness(composed=false)){d=>val t=new Driver(d,false);var cases=0
      for(min<-0 to 5;mag<-0 to 1;s<-0 to 2;v<-0 to 2;r<-0 to 2){
        val lo=if(cases%2==0)BigInt(0x7f800000L)else BigInt(0xc47a0000L)
        val hi=if(cases%2==0)BigInt(0xff800000L)else BigInt(0x447a0000L)
        t.sampler(min,mag,s,v,r,lo,hi,compare=cases%2,function=cases%8);t.invalidate()
        t.query(tag=cases);t.check();val q=d.io.out.bits.sampler
        q.state.minFilter.expect(min.U);q.state.magFilter.expect(mag.U);q.state.wrapS.expect(s.U);q.state.wrapT.expect(v.U);q.state.wrapR.expect(r.U)
        q.state.minLod.expect(lo.U);q.state.maxLod.expect(hi.U);q.state.compareEnable.expect((cases%2==1).B);q.state.compareFunction.expect((cases%8).U)
        q.readLines.expect(1.U);q.hit.expect(false.B);t.pop();val reads=t.memory.seen.size
        t.query(tag=0xf0000000L+cases);t.check();q.readLines.expect(0.U);q.hit.expect(true.B);assert(t.memory.seen.size==reads);t.pop();cases+=1
      }
      assert(cases==324);info(s"SAMPLER_DESCRIPTOR combinations=$cases exactLodAndComparison=true infinityAndReversedClampPreserved=true cacheTagIndependent=true")
    }
  }
  it should "reject malformed sampler encodings semantic enums NaNs and overflowing tables before publishing state" in {
    simulate(new ImageSamplerHarness(composed=false)){d=>val t=new Driver(d,false);var bad=0
      val corrupt=Seq((0,BigInt(0)),(1,BigInt(128)),(2,BigInt(2)),(3,BigInt(8)),(6,BigInt(1)),
        (7,BigInt(8)),(8,BigInt(2)),(9,BigInt(4)),(10,BigInt(4)),(11,BigInt(4)),(14,BigInt(2)),(15,BigInt(8)))
      for((word,value)<-corrupt){t.sampler();t.memory.putWord(0x3000+word*4,value);t.invalidate();t.query();t.check(7);t.pop();bad+=1}
      for((word,value)<-Seq((7,BigInt(6)),(7,BigInt(7)),(9,BigInt(3)),(10,BigInt(3)),(11,BigInt(3)),
        (12,BigInt(0x7fc00001L)),(13,BigInt(0xff800001L)))){
        t.sampler();t.memory.putWord(0x3000+word*4,value);t.invalidate();t.query();t.check(1);t.pop();bad+=1}
      t.sampler(base=end4G-64);t.query(sbase=end4G-64,slimit=end4G);t.check();t.pop()
      val n=t.memory.seen.size
      for((base,limit,handle,version)<-Seq((BigInt(0x3001),BigInt(0x4000),BigInt(0),1),
        (BigInt(0x3000),BigInt(0x3041),BigInt(0),1),(BigInt(0x3000),BigInt(0x3000),BigInt(0),1),
        (BigInt(0x3000),end4G+64,BigInt(0),1),(end4G-64,end4G,BigInt(1),1),
        (BigInt(0x3000),BigInt(0x4000),BigInt(0xffffffffL),1),(BigInt(0x3000),BigInt(0x4000),BigInt(0),2))){
        t.query(sbase=base,slimit=limit,shandle=handle,sversion=version);t.check(1);t.pop()}
      assert(n==t.memory.seen.size);t.sampler();t.query();t.check();t.pop()
      info(s"SAMPLER_REJECTION corruptions=$bad tables=7 exact4GiBEnd=true noInvalidReads=true healthyReuse=true")
    }
  }
  it should "derive sampler dependent mip completeness and enforce every packed format filter and shader numeric type" in {
    simulate(new ImageSamplerHarness()){d=>val t=new Driver(d)
      t.image(brokenMip=true);t.sampler(min=0);t.query();t.check();d.io.out.bits.image.levelsChecked.expect(1.U);t.pop()
      t.sampler(min=2);t.invalidate();t.query(tag=0x101);t.check(6,2);t.pop()
      // Selecting another level without mip filtering must still validate base.
      t.image();t.sampler();t.invalidate();t.memory.putWord(0x2000+13*4,3)
      t.query(tag=0x102,level=1);t.check(6,2);t.pop();t.image();t.invalidate();t.query(level=1);t.check()
      d.io.out.bits.image.level.expect(1.U);d.io.out.bits.image.view.texWidth.expect(2.U);t.pop()
      var cases=0
      for(format<-0 until 36;min<-0 to 5;mag<-0 to 1){
        t.image(format);t.sampler(min,mag);t.invalidate()
        val info=TypedColorReference.format(TypedColorReference.names(format))
        // This oracle uses storage names, independently of RTL encoding lookup.
        val name=TypedColorReference.names(format);val filterable=info.kind==0 && !name.endsWith("32F")
        val allowed=filterable || (mag==0 && (min==0 || min==2))
        t.query(kind=info.kind);t.check(if(allowed)0 else 6,if(allowed)0 else 3)
        if(allowed)d.io.out.bits.image.mipChainChecked.expect((min>=2).B)
        t.pop();cases+=1
      }
      for(kind<-0 to 2){t.image(if(kind==0)3 else if(kind==1)10 else 9);t.sampler();t.invalidate()
        // Wrong scalar/vector result interpretation is never silently converted.
        val actual=TypedColorReference.format(TypedColorReference.names(if(kind==0)3 else if(kind==1)10 else 9)).kind
        t.query(kind=(actual+1)%3);t.check(6,3);t.pop()}
      t.image();t.sampler(compare=1);t.invalidate();t.query();t.check(6,3);t.pop()
      val before=t.memory.seen.size;t.query(tag=0x103,context=8);t.check(1,3);t.pop();t.query(tag=0x104,kind=3);t.check(1,3);t.pop()
      assert(t.memory.seen.size==before)
      info(s"IMAGE_SAMPLER_POLICY formatFilterPairs=$cases samplerDerivedChain=true baseCompleteness=true selectedViewRetained=true shaderNumericType=true colorComparisonRejected=true identityBeforeReads=true noTexelReads=true")
    }
  }
  it should "invalidate every sampler cache identity field and prevent invalidated active fills from repopulating" in {
    simulate(new ImageSamplerHarness(composed=false)){d=>val t=new Driver(d,false)
      t.sampler();t.query();t.check();t.pop()
      t.sampler(base=0x5000,handle=3,min=5);t.query(sbase=0x5000,slimit=0x6000,shandle=3);t.check()
      d.io.out.bits.sampler.state.minFilter.expect(5.U);d.io.out.bits.sampler.hit.expect(false.B);t.pop()
      t.sampler(context=8);t.query(context=8);t.check();t.pop()
      t.sampler();t.invalidate();t.memory.delay=20;t.query();t.until(t.memory.pending.nonEmpty);t.invalidate();t.check();t.pop()
      val n=t.memory.seen.size;t.query();t.check();d.io.out.bits.sampler.hit.expect(false.B);t.pop();assert(t.memory.seen.size==n+1)
      // Changing only the table limit must miss too, even with the same record.
      t.query(slimit=0x3800);t.check();d.io.out.bits.sampler.hit.expect(false.B)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.out.bits.peek().litValue==held)}
      t.pop();d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B)
      info("SAMPLER_CACHE keyTableAndIdentityAndHandle=true invalidatedFillNotReused=true heldCompletionImmutable=true")
    }
  }
  for(composed<-Seq(false,true))
  it should s"drain stalled offers and cancellation cuts then retain healthy reuse with composed=$composed" in {
    simulate(new ImageSamplerHarness(composed)){d=>val t=new Driver(d,composed);t.sampler(min=2);t.image()
      for(cut<-0 until 90){
        t.invalidate();t.memory.delay=13;t.query(tag=cut)
        for(_<-0 until cut if !d.io.out.valid.peek().litToBoolean)t.tick()
        val completed=d.io.out.valid.peek().litToBoolean
        val held=if(completed)Some(d.io.out.bits.peek().litValue)else None
        d.io.cancel.poke(true.B);t.until(d.io.out.valid.peek().litToBoolean)
        if(completed)assert(d.io.out.bits.peek().litValue==held.get)
        else t.check(4,if(composed)d.io.out.bits.faultSource.peek().litValue.toInt else 0)
        val stable=d.io.out.bits.peek().litValue;for(_<-0 until 3){t.tick();assert(d.io.out.bits.peek().litValue==stable)}
        t.pop();d.io.cancel.poke(false.B)
        t.query(tag=1000+cut);t.check();t.pop()
      }
      t.invalidate();t.memory.holdRequests=true;t.query()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);t.tick();val offered=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered)}
      t.memory.holdRequests=false;t.until(d.io.out.valid.peek().litToBoolean);t.check(4,if(composed)d.io.out.bits.faultSource.peek().litValue.toInt else 0);t.pop();d.io.cancel.poke(false.B)
      t.query();t.check();t.pop()
      info(s"SAMPLER_LIFETIME composed=$composed cancellationCuts=90 healthyReuses=91 irrevocableOffer=true pendingReplyDrain=true heldCompletionStable=true snapshotPoisoned=true")
    }
  }
  it should "preserve primary sampler image and level faults including valid address zero across later cancellation" in {
    simulate(new ImageSamplerHarness()){d=>val t=new Driver(d);t.sampler(min=2);t.image()
      for((sbase,ibase,fault,source)<-Seq((BigInt(0),BigInt(0x1000),BigInt(0),1),
        (BigInt(0x3000),BigInt(0),BigInt(0),2),(BigInt(0x3000),BigInt(0x1000),BigInt(0x2000),2))){
        t.sampler(min=2,base=sbase);t.image(base=ibase);t.invalidate();t.memory.faultAt=Set(fault)
        val before=t.memory.seen.size;t.query(sbase=sbase,slimit=sbase+4096,ibase=ibase,ilimit=ibase+2048)
        t.until(t.memory.seen.drop(before).exists(_.address==fault) && t.memory.pending.isEmpty && t.memory.reply.isEmpty)
        d.io.cancel.poke(true.B);t.check(3,source,Some(fault));t.pop();d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty
        t.sampler(min=2);t.image();t.invalidate();t.query();t.check();t.pop()
      }
      info("IMAGE_SAMPLER_FAULT primaryFaultAcrossCancel=true samplerAndImageAddressZero=true levelFault=true exactHealthyReuse=true")
    }
  }
  it should "translate independent sampler image and level tables and reject denied pages without physical reads" in {
    simulate(new ImageSamplerHarness(translated=true)){d=>val t=new Driver(d)
      val root=BigInt(0x90000);val pt=BigInt(0xa0000);t.memory.putWord(root,(pt>>12)<<10|1)
      def map(vpn: Int,pa: BigInt,flags: Int=0x43): Unit=t.memory.putWord(pt+vpn*4,(pa>>12)<<10|flags)
      map(1,0x50000);map(2,0x60000);map(3,0x70000)
      t.sampler(min=2,base=0x70000);t.image(base=0x50000)
      for(n<-0 until 384)t.memory.bytes(BigInt(0x60000+n))=t.memory.bytes.getOrElse(BigInt(0x2000+n),0)
      d.io.satp.poke(((BigInt(1)<<31)|(root>>12)).U);t.query();t.check();t.pop()
      def flush(): Unit={zero(d.io.flush.bits);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B);t.invalidate()}
      for((vpn,pa,source,fault)<-Seq((3,BigInt(0x70000),1,BigInt(0x3000)),(1,BigInt(0x50000),2,BigInt(0x1000)),
        (2,BigInt(0x60000),2,BigInt(0x2000)))){
        map(vpn,pa,0x49);flush();val before=t.memory.seen.size;t.query();t.check(3,source,Some(fault));t.pop()
        assert(!t.memory.seen.drop(before).exists(r=>r.tag<8 && r.address>=pa && r.address<pa+4096))
        map(vpn,pa);flush();t.query();t.check();t.pop()
      }
      info("IMAGE_SAMPLER_VM realSv32=true samplerImageLevelPermissions=true walkerID8=true virtualFaultAddresses=true noDeniedPhysicalReads=true flushRecovery=true")
    }
  }
}
