package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Uniform test ports, optionally composed with the real Sv32 line client. */
private class ImageFetchHarness(typed: Boolean=true,translated: Boolean=false,captured: Boolean=false) extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Bundle {
      val resource=new TextureImageResourceAddressRequest;val format=UInt(6.W)
      val block4x4=Bool();val blockBytes=UInt(5.W);val view=new TextureImageLevelView
    }))
    val out=Decoupled(new TextureImageTypedFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
  })
  private val req=Wire(Decoupled(new ComputeMemoryRequest(config,64,4)))
  private val res=Wire(Flipped(Decoupled(new ComputeMemoryResponse(64,4))))
  if(typed){val f=Module(new TextureImageTypedFetchUnit(config,capturedViewInput=captured))
    f.io.in.valid:=io.in.valid;f.io.in.bits.resource:=io.in.bits.resource;f.io.in.bits.format:=io.in.bits.format
    if(captured)f.io.capturedView.get:=io.in.bits.view
    io.in.ready:=f.io.in.ready;io.out<>f.io.out;io.busy:=f.io.busy;f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate
    req<>f.io.memoryRequest;f.io.memoryResponse<>res
  }else{val f=Module(new TextureImageDataFetchUnit(config,capturedViewInput=captured))
    f.io.in.valid:=io.in.valid;f.io.in.bits.resource:=io.in.bits.resource
    f.io.in.bits.block4x4:=io.in.bits.block4x4;f.io.in.bits.blockBytes:=io.in.bits.blockBytes
    if(captured)f.io.capturedView.get:=io.in.bits.view
    io.in.ready:=f.io.in.ready;io.out.valid:=f.io.out.valid;f.io.out.ready:=io.out.ready
    io.out.bits:=0.U.asTypeOf(io.out.bits);io.out.bits.sample:=f.io.out.bits
    io.busy:=f.io.busy;f.io.cancel:=io.cancel;f.io.invalidate:=io.invalidate
    req<>f.io.memoryRequest;f.io.memoryResponse<>res
  }
  if(translated){val vm=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
    vm.io.in<>req;res<>vm.io.out;vm.io.satp:=io.satp;vm.io.flush<>io.flush
    val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
    arb.io.in(0).valid:=vm.io.memReq.valid;arb.io.in(0).bits:=vm.io.memReq.bits
    arb.io.in(0).bits.transactionId:=vm.io.memReq.bits.transactionId.pad(3);vm.io.memReq.ready:=arb.io.in(0).ready
    arb.io.in(1).valid:=vm.io.pageWalk.valid;arb.io.in(1).bits:=vm.io.pageWalk.bits
    arb.io.in(1).bits.transactionId:=4.U;vm.io.pageWalk.ready:=arb.io.in(1).ready
    io.memoryRequest<>arb.io.out
    val walk=io.memoryResponse.bits.transactionId===4.U
    vm.io.memResp.valid:=io.memoryResponse.valid && !walk;vm.io.memResp.bits:=io.memoryResponse.bits
    vm.io.pageWalkResp.valid:=io.memoryResponse.valid && walk;vm.io.pageWalkResp.bits:=io.memoryResponse.bits
    vm.io.pageWalkResp.bits.transactionId:=0.U
    io.memoryResponse.ready:=Mux(walk,vm.io.pageWalkResp.ready,vm.io.memResp.ready)
  }else{
    io.memoryRequest.valid:=req.valid;io.memoryRequest.bits:=req.bits;req.ready:=io.memoryRequest.ready
    res.valid:=io.memoryResponse.valid;res.bits:=io.memoryResponse.bits;io.memoryResponse.ready:=res.ready
  }
}

class TextureImageFetchUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private case class View(base: BigInt=0x10003c,bytes: Int=16,block: Boolean=false,target: Int=0,
    w: Int=1,h: Int=1,depth: Int=1,layers: Int=1,row: BigInt=16,slice: BigInt=0,layer: BigInt=0) {
    def address(x: Int=0,y: Int=0,z: Int=0,l: Int=0,face: Int=0): BigInt=
      base+(x/(if(block)4 else 1))*bytes+(y/(if(block)4 else 1))*row+z*slice+(if(target==2)face else l)*layer
    def end: BigInt=address(w-1,h-1,depth-1,layers-1,if(target==2)5 else 0)+bytes
  }
  private class Driver(val d: ImageFetchHarness,vm: Boolean=false) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val replies=mutable.ArrayBuffer.empty[(BigInt,BigInt)];var lastTag=BigInt(0);var lastFormat=35;var installed=View();var lastX=0;var lastY=0
    memory.beforeRequest=r=>{assert(!r.write);assert(r.tag<=4)
      if(r.tag!=4){assert(r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
        d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)}
    }
    def tick(): Unit={memory.drive();if(memory.reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=memory.reply.get;replies+=((r.address,r.tag))}
      memory.capture();d.clock.step()}
    def until(p: =>Boolean,n: Int=1200): Unit={var i=0;while(!p && i<n){tick();i+=1};assert(p,s"fetch timeout cycles=$i tag=$lastTag reads=${memory.seen.takeRight(6)}")}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
    def install(v: View,physical: BigInt=0x2000): Unit={
      installed=v
      // Independent explicit little-endian GXL1 encoding, including exact allocation bounds.
      val words=Vector[BigInt](BigInt("47584c01",16),128,1,7,11,13,0,v.target,0,v.base,
        v.end&0xffffffffL,v.end>>32,v.base,v.w,v.h,v.depth,v.layers,
        v.row&0xffffffffL,v.row>>32,v.slice&0xffffffffL,v.slice>>32,v.layer&0xffffffffL,v.layer>>32,
        if(v.block)1 else 0,v.bytes).padTo(32,BigInt(0))
      for((word,i)<-words.zipWithIndex)memory.putWord(physical+4*i,word)
    }
    def payload(address: BigInt,bytes: Int,raw: BigInt): Unit={
      val lo=address& ~BigInt(63);val hi=(address+bytes-1)& ~BigInt(63)
      for(i<-0 until (hi-lo+64).toInt)memory.bytes(lo+i)=0xa7
      for(i<-0 until bytes)memory.bytes(address+i)=((raw>>(8*i))&255).toInt
    }
    def query(format: Int=35,bytes: Int=16,block: Boolean=false,tag: BigInt=0x12340005L,
      x: BigInt=0,y: BigInt=0,z: Int=0,layer: Int=0,face: Int=0,table: BigInt=0x2000): Unit={
      val q=d.io.in.bits;zero(q);val r=q.resource
      r.lookup.table.version.poke(1.U);identity(r.lookup.table.identity)
      r.lookup.table.base.poke(table.U);r.lookup.table.limit.poke((table+128).U)
      r.lookup.handle.poke(0.U);r.lookup.level.poke(0.U);r.tag.poke(tag.U)
      r.x.poke(x.U);r.y.poke(y.U);r.z.poke(z.U);r.layer.poke(layer.U);r.face.poke(face.U)
      q.format.poke(format.U);q.block4x4.poke(block.B);q.blockBytes.poke(bytes.U)
      val v=installed;q.view.target.poke(v.target.U);q.view.level.poke(0.U);q.view.base.poke(v.base.U)
      q.view.allocationBase.poke(v.base.U);q.view.allocationLimit.poke(v.end.U)
      q.view.texWidth.poke(v.w.U);q.view.texHeight.poke(v.h.U);q.view.texDepth.poke(v.depth.U);q.view.layers.poke(v.layers.U)
      q.view.rowPitch.poke(v.row.U);q.view.slicePitch.poke(v.slice.U);q.view.layerPitch.poke(v.layer.U)
      q.view.block4x4.poke(v.block.B);q.view.blockBytes.poke(v.bytes.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      zero(q);lastTag=tag;lastFormat=format;lastX=x.toInt;lastY=y.toInt
    }
    def check(status: Int=0,address: BigInt=0x10003c,bytes: Int=16,raw: BigInt=0,typed: Boolean=true,
      source: Int=0,fault: Option[BigInt]=None): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;val s=o.sample
      s.status.expect(status.U);s.tag.expect(lastTag.U);s.identity.context.expect(7.U);s.identity.pass.expect(11.U);s.identity.generation.expect(13.U)
      s.handle.expect(0.U);s.level.expect(0.U);s.viewValid.expect((status==0).B);s.faultSource.expect(source.U)
      s.faultAddressValid.expect(fault.nonEmpty.B);s.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      if(status==0){s.address.expect(address.U);s.bytes.expect(bytes.U);s.raw.expect(raw.U)
        val n=if((address%64).toInt+bytes>64)2 else 1;s.dataReadLines.expect(n.U)
        if(typed){val compressed=lastFormat>=54
          o.colorType.expect((if(compressed)0 else ImageFormatReference.format(ImageFormatReference.names(lastFormat)).kind).U)
          val expected=if(compressed)CompressedImageReference.decode(lastFormat,raw,lastX&3,lastY&3)else ImageFormatReference.decode(ImageFormatReference.names(lastFormat),raw)
          o.rgba.zip(expected).foreach{case(a,b)=>a.expect(b.U)}}
      }else{assert(values(s.view).values.forall(_==0));s.address.expect(0.U);s.bytes.expect(0.U);s.raw.expect(0.U)
        o.rgba.foreach(_.expect(0.U));o.colorType.expect(0.U)}
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.busy.expect(true.B);d.io.in.ready.expect(false.B)
    }
    def pop(): Unit={d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)}
    def dataReads: Int=memory.seen.count(r=>r.tag==2 || r.tag==3)
  }
  it should "fetch every format from a captured view without rereading mutable descriptor memory" in {
    simulate(new ImageFetchHarness(captured=true)){d=>val t=new Driver(d);val random=new scala.util.Random(0x43415054);var queries=0
      for(format<-0 until 64;offset<-Seq(0,1,60,63)){
        val compressed=format>=54;val info=if(compressed)None else Some(ImageFormatReference.format(ImageFormatReference.names(format)))
        val bytes=if(compressed)CompressedImageReference.format(format).bytes else info.get.bytes
        val target=if(compressed || info.exists(_.depth))format%3 else format%4
        val w=if(compressed)7 else 2;val h=if(compressed){if(target==2)7 else 5}else 2
        val row=2*bytes+3;val plane=(if(compressed)1 else h-1)*row+2*bytes
        val v=View(base=BigInt(0x100000)+offset,bytes=bytes,block=compressed,target=target,w=w,h=h,
          depth=if(target==3)3 else 1,layers=if(target==1)3 else 1,row=row,
          slice=if(target==3)plane+7 else 0,layer=if(target==1 || target==2)plane+11 else 0)
        val z=v.depth-1;val layer=v.layers-1;val face=if(target==2)5 else 0;val address=v.address(w-1,h-1,z,layer,face)
        val raw=BigInt(bytes*8,random);t.install(v);t.payload(address,bytes,raw)
        t.memory.putWord(0x2000,0);t.memory.faultAt=Set(0x2000,0x2040);val before=t.memory.seen.size
        t.query(format,bytes,compressed,x=w-1,y=h-1,z=z,layer=layer,face=face,tag=queries)
        t.check(address=address,bytes=bytes,raw=raw);d.io.out.bits.sample.readLines.expect(0.U)
        assert(t.memory.seen.drop(before).forall(r=>r.tag==2 || r.tag==3))
        val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
        for(_<-0 until 3){t.tick();assert(d.io.out.bits.peek().litValue==held)}
        t.pop();d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B);queries+=1
      }
      info(s"CAPTURED_TEXTURE_FETCH queries=$queries formats64=true offsets4=true allTargets=true poisonedDescriptorMemoryNeverRead=true capturedInputView=true exactDecodedValues=true heldResultImmutable=true")
    }
  }
  it should "drain captured fetch offers and reject invalid snapshots without descriptor or unsafe data reads" in {
    simulate(new ImageFetchHarness(captured=true)){d=>val t=new Driver(d);val v=View();t.install(v);t.payload(v.base,16,42)
      for((bad,status,source)<-Seq((v.copy(row=15),2,1),(v.copy(bytes=8,row=8),6,3))){
        t.install(bad);val before=t.memory.seen.size;t.query();t.check(status,source=source);t.pop();assert(t.memory.seen.size==before)}
      t.install(v);t.memory.holdRequests=true;t.query();t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val offered=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offered);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4);t.pop();d.io.cancel.poke(false.B)
      var cancelled=0;var held=0
      for(cut<-0 until 80){t.query();for(_<-0 until cut if !d.io.out.valid.peek().litToBoolean)t.tick()
        val done=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B)
        t.check(if(done)0 else 4,raw=42);t.pop();d.io.cancel.poke(false.B)
        if(done)held+=1 else cancelled+=1
      }
      assert(cancelled>0 && held>0);t.query();t.check(raw=42);t.pop()
      info(s"CAPTURED_TEXTURE_DRAIN cuts80=true cancelled=$cancelled held=$held heldDataOffer13=true malformedSnapshotBeforeReads=true exactHealthyReuse=true")
    }
  }
  it should "translate captured cross-page data without requiring a mapped descriptor and enforce every page permission" in {
    simulate(new ImageFetchHarness(captured=true,translated=true)){d=>val t=new Driver(d,vm=true)
      val root=BigInt(0x80000);val pt=BigInt(0x90000);t.memory.putWord(root,(pt>>12)<<10|1)
      def map(vpn: Int,pa: BigInt,flags: Int=0x43): Unit=t.memory.putWord(pt+vpn*4,(pa>>12)<<10|flags)
      map(0x100,0x60000);map(0x101,0x70000);d.io.satp.poke(((BigInt(1)<<31)|(root>>12)).U)
      val v=View(base=0x100ffc);val raw=BigInt("fedcba98765432100123456789abcdef",16);t.install(v)
      for(i<-0 until 16)t.memory.bytes(if(i<4)BigInt(0x60ffc)+i else BigInt(0x70000)+i-4)=((raw>>(8*i))&255).toInt
      t.query();t.check(address=v.base,raw=raw);t.pop()
      assert(t.memory.seen.filter(_.tag<4).map(_.address).toSet==Set(BigInt(0x60fc0),BigInt(0x70000)))
      map(0x101,0x70000,0x49);zero(d.io.flush.bits);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B)
      val before=t.memory.seen.size;t.query();t.check(3,source=2,fault=Some(BigInt(0x101000)));t.pop()
      assert(!t.memory.seen.drop(before).exists(r=>r.tag<4 && r.address==0x70000))
      map(0x101,0x70000);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B)
      t.query();t.check(address=v.base,raw=raw);t.pop()
      info("CAPTURED_TEXTURE_VM realSv32=true descriptorUnmapped=true noncontiguousDataPages=true deniedSecondPageNoPhysicalRead=true virtualFaultAddress=true flushRecovery=true")
    }
  }
  it should "fetch every typed storage format at every line offset with exact guarded bytes and fresh data on cache hits" in {
    simulate(new ImageFetchHarness()){d=>val t=new Driver(d);val random=new scala.util.Random(0x47584c01)
      var queries=0;var crossings=0
      for((name,id)<-ImageFormatReference.names.zipWithIndex;offset<-0 until 64){
        val bytes=ImageFormatReference.format(name).bytes;val v=View(base=BigInt(0x100000)+offset,bytes=bytes,row=bytes)
        val raw=BigInt(bytes*8,random);t.install(v);t.payload(v.base,bytes,raw);t.invalidate()
        t.query(format=id,tag=BigInt(0xfedc0000L)+queries);t.check(address=v.base,bytes=bytes,raw=raw)
        d.io.out.bits.sample.hit.expect(false.B);d.io.out.bits.sample.readLines.expect(2.U)
        val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
        for(_<-0 until 2){t.tick();assert(d.io.out.bits.peek().litValue==held)}
        d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B);t.pop();queries+=1
        if(offset+bytes>64)crossings+=1
      }
      val v=View();t.install(v);t.invalidate();t.payload(v.base,16,0x1234);t.query();t.check(raw=0x1234);t.pop()
      val count=t.memory.seen.size;val fresh=BigInt("fedcba98765432100123456789abcdef",16)
      t.payload(v.base,16,fresh);t.query(tag=0xabcdef12L);t.check(raw=fresh)
      d.io.out.bits.sample.hit.expect(true.B);d.io.out.bits.sample.readLines.expect(0.U);t.pop()
      assert(t.memory.seen.size==count+2)
      info(s"TEXTURE_FETCH_FORMATS formats54=true offsets64=true queries=$queries crossings=$crossings exactGuardedRaw=true independentTypedOracle=true immutableHeldResult=true descriptorHitFreshData=true capturedFormatAndTag=true")
    }
  }
  it should "gather raw compressed blocks and selected cube array volume spans including the 4 GiB boundary" in {
    simulate(new ImageFetchHarness(typed=false)){d=>val t=new Driver(d);var queries=0
      for((bytes,block)<-Seq((0,false),(5,false),(31,false),(4,true),(12,true))){
        val n=t.memory.seen.size;t.query(bytes=bytes,block=block);t.check(6,typed=false,source=3);t.pop()
        assert(t.memory.seen.size==n,"invalid expected layout must fail before descriptor reads")}
      for(bytes<-Seq(8,16);offset<-0 until 64){val v=View(base=BigInt(0x110000)+offset,bytes=bytes,block=true,w=7,h=5,row=2*bytes+3)
        val address=v.address(6,4);val raw=(BigInt(1)<<(bytes*8))-1
        t.install(v);t.payload(address,bytes,raw);t.invalidate();t.query(bytes=bytes,block=true,x=6,y=4)
        t.check(address=address,bytes=bytes,raw=raw,typed=false);d.io.out.bits.sample.blockX.expect(2.U);d.io.out.bits.sample.blockY.expect(0.U);t.pop();queries+=1}
      for(target<-1 to 3){val v=View(target=target,w=2,h=2,depth=if(target==3)3 else 1,layers=if(target==1)3 else 1,
        row=35,slice=if(target==3)91 else 0,layer=if(target==1 || target==2)99 else 0)
        val z=if(target==3)2 else 0;val layer=if(target==1)2 else 0;val face=if(target==2)5 else 0
        val address=v.address(1,1,z,layer,face);t.install(v);t.payload(address,16,BigInt("800000007fc000013f80000000000001",16));t.invalidate()
        t.query(x=1,y=1,z=z,layer=layer,face=face);t.check(address=address,raw=BigInt("800000007fc000013f80000000000001",16),typed=false);t.pop();queries+=1}
      val v=View(base=(BigInt(1)<<32)-4,bytes=4,row=4);t.install(v);t.payload(v.base,4,0xff123456L);t.invalidate()
      t.query(bytes=4);t.check(address=v.base,bytes=4,raw=0xff123456L,typed=false);t.pop();queries+=1
      assert(t.memory.seen.forall(_.address<(BigInt(1)<<32)))
      info(s"TEXTURE_FETCH_RAW queries=$queries invalidLayouts5BeforeReads=true blockSizes8And16=true offsets64=true selectedTargets4=true exact4GiBEnd=true noWrappedLine=true")
    }
  }
  it should "reject mismatched format layouts descriptors and coordinates before texture data reads then recover" in {
    simulate(new ImageFetchHarness()){d=>val t=new Driver(d);t.install(View())
      for(v<-Seq(View(bytes=8,row=8),View(block=true,w=4,h=4),View(w=0),View())){
        t.install(v);t.invalidate();val n=t.dataReads;t.query(x=if(v==View())BigInt(0xffffffffL)else BigInt(0))
        val status=if(v.w==0)1 else if(v==View())5 else 6
        t.check(status,source=if(status==6)3 else 1);t.pop();assert(t.dataReads==n)}
      t.install(View());t.memory.putWord(0x2000,0);t.invalidate();val n=t.dataReads;t.query();t.check(7,source=1);t.pop();assert(t.dataReads==n)
      t.install(View());t.payload(0x10003c,16,17);t.query();t.check(raw=17);t.pop()
      info("TEXTURE_FETCH_REJECT allSixBitIdsAssigned=true packedByteMismatch=true blockMismatch=true invalidView=true fullWidthCoordinate=true corruptHeader=true noRejectedDataReads=true exactHealthyReuse=true")
    }
  }
  it should "attribute descriptor and data faults drain reordered tags and retain the primary fault through cancellation" in {
    simulate(new ImageFetchHarness()){d=>val t=new Driver(d);val v=View();t.install(v);t.payload(v.base,16,42)
      for((address,source)<-Seq((BigInt(0x2000),1),(BigInt(0x2040),1),(BigInt(0x100000),2),(BigInt(0x100040),2))){
        t.invalidate();t.memory.faultAt=Set(address);t.query();t.check(3,source=source,fault=Some(address));t.pop()
        t.memory.faultAt=Set.empty;t.query();t.check(raw=42);t.pop()}
      // Hold both data tags, return second first, then cancel after its fault was accepted.
      t.invalidate();t.query();t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.transactionId.peek().litValue>=2)
      t.memory.holdResponses=true;t.until(t.memory.pending.count(_.tag>=2)==2)
      for(_<-0 until 8)t.tick();t.memory.faultAt=Set(BigInt(0x100040));t.memory.holdResponses=false
      val before=t.replies.size;t.until(t.replies.size>before);assert(t.replies.last==(BigInt(0x100040),BigInt(3)))
      d.io.cancel.poke(true.B);t.check(3,source=2,fault=Some(BigInt(0x100040)));t.pop();d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty
      val zero=View(base=0,bytes=4,row=4);t.install(zero);t.invalidate();t.memory.faultAt=Set(BigInt(0));t.query(format=3)
      t.check(3,source=2,fault=Some(BigInt(0)));t.pop();t.memory.faultAt=Set.empty
      t.install(v);t.invalidate();t.query();t.check(raw=42);t.pop()
      // A prompt first-line fault must not create a new second-line offer on
      // the same edge. Previously offered requests would still have to drain.
      t.memory.delay=0;t.memory.faultAt=Set(BigInt(0x100000));val n=t.dataReads
      t.query();t.check(3,source=2,fault=Some(BigInt(0x100000)));t.pop()
      assert(t.dataReads==n+1,"first observed fault created an additional data offer")
      info("TEXTURE_FETCH_FAULT descriptorLines2=true dataLines2=true disjointTags=true reorderedReplies=true firstFaultBeforeCancel=true promptFaultStopsNewOffer=true addressZeroValid=true noFaultPayload=true allTagsDrained=true exactHealthyReuse=true")
    }
  }
  it should "retain backpressured data offers and cancel across admission fetching decoding and held results" in {
    simulate(new ImageFetchHarness()){d=>val t=new Driver(d);val v=View();t.install(v);t.payload(v.base,16,0x1234)
      // Establish a cached descriptor, then stall the first data offer across cancellation.
      t.query();t.check(raw=0x1234);t.pop();t.memory.holdRequests=true;t.query()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);assert(d.io.memoryRequest.bits.transactionId.peek().litValue>=2)
      val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.memoryRequest.valid.expect(true.B);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4);t.pop();d.io.cancel.poke(false.B)
      var cancelled=0;var completed=0
      for(cut<-0 until 100){t.invalidate();t.query();for(_<-0 until cut)t.tick()
        val done=d.io.out.valid.peek().litToBoolean;val held=if(done)d.io.out.bits.peek().litValue else BigInt(0)
        d.io.cancel.poke(true.B);if(done){t.check(raw=0x1234);assert(d.io.out.bits.peek().litValue==held);completed+=1}else{t.check(4);cancelled+=1}
        t.pop();d.io.cancel.poke(false.B);t.query();t.check(raw=0x1234);t.pop()}
      assert(cancelled>0 && completed>0)
      info(s"TEXTURE_FETCH_CANCEL cuts100=true cancelled=$cancelled alreadyHeld=$completed heldDataOffer13=true descriptorAndDataAndDecodeDrain=true heldSuccessImmutable=true exactHealthyReuse=true")
    }
  }
  it should "translate descriptor and cross-page texel reads enforce each page permission and recover after flush" in {
    simulate(new ImageFetchHarness(translated=true)){d=>val t=new Driver(d,vm=true)
      val root=BigInt(0x80000);val pt=BigInt(0x90000);val descriptor=BigInt(0x50000)
      t.memory.putWord(root,(pt>>12)<<10|1)
      def map(vpn: Int,physical: BigInt,flags: Int=0x43): Unit=t.memory.putWord(pt+vpn*4,(physical>>12)<<10|flags)
      map(2,descriptor);map(0x100,0x60000);map(0x101,0x70000)
      d.io.satp.poke(((BigInt(1)<<31)|(root>>12)).U)
      val v=View(base=0x100ffc);val raw=BigInt("fedcba98765432100123456789abcdef",16)
      t.install(v,descriptor)
      for(i<-0 until 16){val pa=if(i<4)BigInt(0x60ffc)+i else BigInt(0x70000)+i-4;t.memory.bytes(pa)=((raw>>(8*i))&255).toInt}
      t.query();t.check(address=v.base,raw=raw);t.pop()
      assert(t.memory.seen.filter(_.tag<4).map(_.address).toSet==Set(descriptor,descriptor+64,BigInt(0x60fc0),BigInt(0x70000)))
      def flush(): Unit={zero(d.io.flush.bits);d.io.flush.valid.poke(true.B);t.tick();d.io.flush.valid.poke(false.B);t.invalidate()}
      // Execute-only leaf is valid but cannot satisfy a texture read.
      map(0x101,0x70000,0x49);flush();val before=t.memory.seen.size;t.query()
      t.check(3,source=2,fault=Some(BigInt(0x101000)));t.pop()
      assert(!t.memory.seen.drop(before).exists(r=>r.tag<4 && r.address==0x70000))
      map(0x101,0x70000);flush();t.query();t.check(address=v.base,raw=raw);t.pop()
      // Deny the descriptor page too; no texture physical request may escape.
      map(2,descriptor,0x49);flush();val n=t.dataReads;t.query();t.check(3,source=1,fault=Some(BigInt(0x2000)));t.pop();assert(t.dataReads==n)
      map(2,descriptor);flush();t.query();t.check(address=v.base,raw=raw);t.pop()
      info("TEXTURE_FETCH_VM realSv32LineClient=true disjointWalkerID=true descriptorTranslation=true noncontiguousTexelPages=true secondPageReadPermission=true descriptorReadPermission=true noDeniedPhysicalRead=true virtualFaultAddress=true flushRecovery=true")
    }
  }
}
