package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureImageResourceAddressUnitSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=4,warps=2)
  private val end4G=BigInt(1)<<32
  private case class View(target: Int=0,level: Int=0,base: BigInt=0x10003b,allocationBase: BigInt=0x100000,
    allocationLimit: BigInt=0x200000,w: Int=7,h: Int=5,depth: Int=1,layers: Int=1,
    row: BigInt=33,slice: BigInt=0,layer: BigInt=0,block: Boolean=false,bytes: Int=4) {
    def values: Map[String,BigInt]=Map("target"->BigInt(target),"level"->BigInt(level),"base"->base,
      "allocationBase"->allocationBase,"allocationLimit"->allocationLimit,"texWidth"->BigInt(w),"texHeight"->BigInt(h),
      "texDepth"->BigInt(depth),"layers"->BigInt(layers),"rowPitch"->row,"slicePitch"->slice,"layerPitch"->layer,
      "block4x4"->BigInt(if(block)1 else 0),"blockBytes"->BigInt(bytes))
    def address(x: BigInt,y: BigInt,z: BigInt,l: BigInt,face: Int): BigInt={
      val step=if(block)4 else 1
      base+(x/step)*bytes+(y/step)*row+(if(target==3)z*slice else BigInt(0))+
        (if(target==1)l*layer else if(target==2)BigInt(face)*layer else BigInt(0))
    }
  }
  private def view(target: Int,level: Int): View={
    val width=1.max((if(target==3)256 else 2048)>>level)
    val block=level%4==3;val bytes=if(block)8 else Vector(1,2,3,4,6,8,12,16)(level%8)
    val blocks=if(block)(width+3)/4 else width;val row=BigInt(blocks*bytes+5)
    val plane=(blocks-1)*row+blocks*bytes;val depth=if(target==3)width else 1;val layers=if(target==1)256 else 1
    val slice=if(target==3)plane+17 else BigInt(0);val layer=if(target==1 || target==2)plane+29 else BigInt(0)
    val span=plane+(depth-1)*slice+(if(target==2)5 else layers-1)*layer
    View(target,level,w=width,h=width,depth=depth,layers=layers,row=row,slice=slice,layer=layer,block=block,bytes=bytes,
      allocationLimit=BigInt(0x10003b)+span+7)
  }
  private class Driver(val d: TextureImageResourceAddressUnit) {
    import WindowRecordTest._
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    var peak=0;val replies=mutable.ArrayBuffer.empty[BigInt]
    var lastTag=BigInt(0);var lastHandle=BigInt(0);var lastLevel=0;var lastGeneration=13;var lastContext=7;var lastPass=11
    memory.beforeRequest=r=>{assert(!r.write && r.mask==(BigInt(1)<<64)-1)
      d.io.memoryRequest.bits.sizeLog2.expect(6.U);d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)}
    def tick(): Unit={memory.drive()
      if(memory.reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)replies+=memory.reply.get.address
      peak=peak.max(memory.pending.size+memory.reply.size);memory.capture();d.clock.step()}
    def until(condition: =>Boolean,limit: Int=1000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"resource timeout n=$n")}
    def install(v: View=View(),handle: BigInt=0,base: BigInt=0x2000,generation: Int=13,context: Int=7,passId: Int=11): Unit={
      val address=base+handle*128
      // Explicit ABI encoding is independent of TextureImageLevelLayout.fields.
      val words=Vector[BigInt](BigInt("47584c01",16),128,1,context,passId,generation,handle,v.target,v.level,v.allocationBase,
        v.allocationLimit&0xffffffffL,v.allocationLimit>>32,v.base,v.w,v.h,v.depth,v.layers,
        v.row&0xffffffffL,v.row>>32,v.slice&0xffffffffL,v.slice>>32,v.layer&0xffffffffL,v.layer>>32,
        if(v.block)1 else 0,v.bytes).padTo(32,BigInt(0))
      for((word,i)<-words.zipWithIndex)memory.putWord(address+i*4,word)
    }
    def query(handle: BigInt=0,level: Int=0,tag: BigInt=5,x: BigInt=0,y: BigInt=0,z: BigInt=0,layer: BigInt=0,face: Int=0,
      base: BigInt=0x2000,limit: BigInt=0x4000,version: Int=1,generation: Int=13,context: Int=7,passId: Int=11): Unit={
      val q=d.io.in.bits;q.lookup.table.version.poke(version.U);identity(q.lookup.table.identity)
      q.lookup.table.identity.context.poke(context.U);q.lookup.table.identity.pass.poke(passId.U)
      q.lookup.table.identity.generation.poke(generation.U);q.lookup.table.base.poke(base.U);q.lookup.table.limit.poke(limit.U)
      q.lookup.handle.poke(handle.U);q.lookup.level.poke(level.U);q.tag.poke(tag.U)
      q.x.poke(x.U);q.y.poke(y.U);q.z.poke(z.U);q.layer.poke(layer.U);q.face.poke(face.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      zero(d.io.in.bits);lastTag=tag;lastHandle=handle;lastLevel=level;lastGeneration=generation;lastContext=context;lastPass=passId
    }
    def check(status: Int,v: View=View(),hit: Boolean=false,lines: Int=2,x: BigInt=0,y: BigInt=0,z: BigInt=0,layer: BigInt=0,face: Int=0,faultAddress: BigInt=0x2040): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits
      o.status.expect(status.U);o.tag.expect(lastTag.U);o.handle.expect(lastHandle.U);o.level.expect(lastLevel.U)
      o.identity.context.expect(lastContext.U);o.identity.pass.expect(lastPass.U);o.identity.generation.expect(lastGeneration.U)
      o.hit.expect(hit.B);o.readLines.expect(lines.U);o.viewValid.expect((status==0).B)
      o.faultAddressValid.expect((status==3).B);o.faultAddress.expect((if(status==3)faultAddress else BigInt(0)).U)
      if(status==0){assert(values(o.view)==v.values);o.address.expect(v.address(x,y,z,layer,face).U);o.bytes.expect(v.bytes.U)
        o.blockX.expect((if(v.block)x%4 else BigInt(0)).U);o.blockY.expect((if(v.block)y%4 else BigInt(0)).U)
      }else{assert(values(o.view).values.forall(_==0));o.address.expect(0.U);o.bytes.expect(0.U);o.blockX.expect(0.U);o.blockY.expect(0.U)}
      assert(memory.pending.isEmpty && memory.reply.isEmpty);d.io.busy.expect(true.B);d.io.in.ready.expect(false.B)
    }
    def pop(): Unit={d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B)}
    def invalidate(): Unit={d.io.invalidate.poke(true.B);tick();d.io.invalidate.poke(false.B)}
  }
  it should "load all target mip records with reordered tags and return exact checked cached spans" in {
    simulate(new TextureImageResourceAddressUnit(config)){d=>val t=new Driver(d);var queries=0
      for(target<-0 to 3;level<-0 until 16)t.install(view(target,level),target*16+level)
      for(target<-0 to 3;level<-0 until 16){val v=view(target,level);val handle=target*16+level
        val x=v.w-1;val y=v.h-1;val z=if(target==3)v.depth-1 else 0;val layer=if(target==1)255 else 0;val face=if(target==2)5 else 0
        t.memory.holdResponses=true;t.query(handle,level,100+handle,x,y,z,layer,face)
        t.until(t.memory.pending.size==2);for(_<-0 until 8)t.tick();t.memory.holdResponses=false
        t.check(0,v,x=x,y=y,z=z,layer=layer,face=face);assert(t.replies.takeRight(2)==Seq(BigInt(0x2040)+handle*128,BigInt(0x2000)+handle*128));t.pop()
        val count=t.memory.seen.size;t.query(handle,level,200+handle);t.check(0,v,hit=true,lines=0);t.pop()
        assert(t.memory.seen.size==count);queries+=2
      }
      assert(t.peak==2 && t.memory.seen.size==128)
      info(s"TEXTURE_LEVEL_RESOURCE targets=4 levels=16 queries=$queries descriptorReads128=true reorderedTwoTags=true exactAddressAndBlockLocation=true immutableViews=true coordinateIndependentCacheKey=true")
    }
  }
  it should "retain exact cache identity and prevent invalidated fills from becoming reusable views" in {
    simulate(new TextureImageResourceAddressUnit(config)){d=>val t=new Driver(d);val old=View();val fresh=old.copy(base=0x14003b)
      t.install(old);t.query();t.check(0,old);t.pop()
      t.query(version=0);t.check(1,lines=0);t.pop()
      t.query();t.check(0,old,hit=true,lines=0);t.pop();t.install(fresh)
      t.query(tag=6,x=6,y=4);t.check(0,old,hit=true,lines=0,x=6,y=4);t.pop()
      t.invalidate();t.query(tag=7);t.check(0,fresh);t.pop()
      t.query(level=1);t.check(7);t.pop()
      t.install(fresh.copy(level=1));t.query(level=1);t.check(0,fresh.copy(level=1));t.pop()
      t.install(fresh);t.query();t.check(0,fresh);t.pop()
      t.query(limit=0x4100);t.check(0,fresh);t.pop() // Different arena limit is a miss.
      t.query(generation=14);t.check(7);t.pop();t.install(fresh,generation=14)
      t.query(generation=14);t.check(0,fresh);t.pop()
      t.query(generation=14,context=8);t.check(7);t.pop()
      t.install(fresh,generation=14,context=8);t.query(generation=14,context=8);t.check(0,fresh);t.pop()
      t.query(generation=14,context=8,passId=12);t.check(7);t.pop()
      t.install(fresh,generation=14,context=8,passId=12)
      t.query(generation=14,context=8,passId=12);t.check(0,fresh);t.pop()
      t.install(fresh,generation=14);t.query(generation=14);t.check(0,fresh);t.pop()
      t.invalidate();t.memory.holdResponses=true;t.query(generation=14);t.until(t.memory.pending.size==2)
      t.invalidate();for(_<-0 until 8)t.tick();t.memory.holdResponses=false;t.check(0,fresh)
      val held=d.io.out.bits.peek().litValue;d.io.invalidate.poke(true.B);d.io.cancel.poke(true.B)
      for(_<-0 until 9){t.tick();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
      t.pop();d.io.invalidate.poke(false.B);d.io.cancel.poke(false.B)
      t.query(generation=14);t.check(0,fresh);t.pop()
      t.install(fresh,base=0x3000,generation=14);t.query(base=0x3000,limit=0x5000,generation=14);t.check(0,fresh);t.pop()
      info("TEXTURE_LEVEL_CACHE contextPassGenerationLevelAndArenaBounds=true versionCheckedBeforeCache=true changedTagCoordinatesHit=true explicitInvalidation=true invalidateDuringFill=true noStaleRepopulation=true immutableHeldResult=true")
    }
  }
  it should "accept records and images ending at 4 GiB and reject invalid lookup envelopes before reads" in {
    simulate(new TextureImageResourceAddressUnit(config)){d=>val t=new Driver(d);val high=end4G-128;t.install(base=high)
      t.query(base=high,limit=end4G);t.check(0);t.pop();assert(t.memory.seen.map(_.address).toSeq==Seq(high,high+64))
      val count=t.memory.seen.size
      for((base,limit,handle,version)<-Vector((high,end4G,BigInt(1),1),(BigInt(0x2001),BigInt(0x4001),BigInt(0),1),
        (BigInt(0x2000),BigInt(0x20c0),BigInt(0),1),(BigInt(0x2000),BigInt(0x4000),BigInt(0xffffffffL),1),
        (BigInt(0x2000),end4G+128,BigInt(0),1),(BigInt(0x2000),BigInt(0x2000),BigInt(0),1),
        (BigInt(0x2000),BigInt(0x4000),BigInt(0),0))){t.query(handle=handle,base=base,limit=limit,version=version);t.check(1,lines=0);t.pop()}
      assert(t.memory.seen.size==count)
      t.install(base=0x2040);t.query(base=0x2040,limit=0x20c0);t.check(0);t.pop()
      val full=View(target=1,base=0,allocationBase=0,allocationLimit=end4G,w=2048,h=2048,layers=256,row=8192,layer=16777216)
      t.install(full);t.query(x=2047,y=2047,layer=255);t.check(0,full,x=2047,y=2047,layer=255);d.io.out.bits.address.expect("hfffffffc".U);t.pop()
      info("TEXTURE_LEVEL_BOUNDS exactRecord4GiBEnd=true fullArray4GiBEnd=true alignment64Not128=true invalidEnvelopes7=true noInvalidDescriptorReads=true noHandleArithmeticWrap=true")
    }
  }
  it should "reject corrupt headers canonical padding view bounds and coordinates without publishing partial state" in {
    simulate(new TextureImageResourceAddressUnit(config)){d=>val t=new Driver(d)
      val corrupt=(Vector(0->BigInt(0),1->BigInt(64),2->BigInt(2),3->BigInt(9),5->BigInt(12),6->BigInt(1),8->BigInt(1))++
        (25 until 32).map(_->BigInt(1))++Vector(7->BigInt(8),8->BigInt(16),11->BigInt(2),13->BigInt(16384),
          14->BigInt(16384),15->BigInt(16384),16->BigInt(65536),18->BigInt(2),20->BigInt(2),22->BigInt(2),23->BigInt(2),24->BigInt(32)))
      for((word,value)<-corrupt){t.install();t.memory.putWord(0x2000+word*4,value);t.invalidate();t.query();t.check(7);t.pop()}
      val semantic=Vector(View(target=4)->1,View(w=0)->1,View(h=0)->1,View(depth=0)->1,View(layers=0)->1,
        View(bytes=5)->1,View(block=true)->1,View(slice=1)->1,View(row=27)->2,View(allocationLimit=0x100040)->2,
        View(allocationLimit=end4G+1)->2,View(target=2,w=7,h=5,layer=1000)->1)
      for((v,status)<-semantic){t.install(v);t.invalidate();t.query();t.check(status);t.pop();t.install();t.query();t.check(0);t.pop()}
      t.install();t.invalidate();t.query(x=0xffffffffL);t.check(5);t.pop();t.query();t.check(0);t.pop()
      t.query(y=5);t.check(5,hit=true,lines=0);t.pop();t.query();t.check(0,hit=true,lines=0);t.pop()
      info(s"TEXTURE_LEVEL_VALIDATION corrupt=${corrupt.size} semantic=${semantic.size} statusEncoding7=true statusView1=true statusBounds2=true statusCoordinate5=true zeroFaultSpans=true noPartialViewCache=true exactHealthyReuse=true")
    }
  }
  it should "drain held reads memory faults and cancelled address work before allowing healthy reuse" in {
    simulate(new TextureImageResourceAddressUnit(config)){d=>val t=new Driver(d);t.install();t.memory.holdRequests=true;t.query()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.out.valid.expect(false.B)}
      t.memory.holdRequests=false;t.check(4,lines=1);t.pop();d.io.cancel.poke(false.B)
      t.query();t.check(0);t.pop();t.invalidate()
      t.memory.holdResponses=true;t.memory.faultAt=Set(BigInt(0x2040));t.query();t.until(t.memory.pending.size==2)
      for(_<-0 until 11)t.tick();t.memory.holdResponses=false;t.check(3);t.pop();t.memory.faultAt=Set.empty
      t.query();t.check(0);t.pop();t.invalidate()
      t.memory.faultAt=Set(BigInt(0x2000));t.query();t.check(3,faultAddress=0x2000);t.pop()
      t.install(base=0);t.memory.faultAt=Set(BigInt(0));t.query(base=0,limit=128);t.check(3,faultAddress=0);t.pop()
      t.memory.faultAt=Set.empty
      // Cut only after this query's two replies and address-input acceptance.
      // A cumulative count can accidentally cancel before any new read.
      val before=t.replies.size;t.query()
      t.until(t.replies.size==before+2 && t.memory.pending.isEmpty && t.memory.reply.isEmpty)
      for(_<-0 until 3)t.tick()
      d.io.out.valid.expect(false.B);d.io.busy.expect(true.B)
      d.io.cancel.poke(true.B);t.check(4);t.pop();d.io.cancel.poke(false.B)
      t.query();t.check(0);t.pop()
      info("TEXTURE_LEVEL_DRAIN heldOffer13=true noWithdrawnRead=true delayedFaultedTags=true exactFirstFaultLine=true validFaultAddressZero=true cancelledAddressPipeline=true allOwnershipDrained=true noCacheFromFailure=true exactHealthyReuse=true")
    }
  }
  it should "apply configured image and layer limits to GPU loaded views" in {
    val p=TextureImageAddressConfig(max2D=128,max3D=32,maxLayers=4)
    simulate(new TextureImageResourceAddressUnit(config,p)){d=>val t=new Driver(d)
      val array=View(target=1,w=128,h=128,layers=4,row=512,layer=65536,allocationLimit=0x200000)
      val volume=View(target=3,w=32,h=32,depth=32,row=128,slice=4096)
      for((v,status)<-Vector(array->0,array.copy(w=129)->1,array.copy(layers=5)->1,volume->0,
        volume.copy(w=33)->1,volume.copy(h=33)->1,volume.copy(depth=33)->1)){
        t.install(v);t.invalidate();t.query();t.check(status,v);t.pop()
      }
      info("TEXTURE_LEVEL_LIMITS dimension128=true volume32=true layers4=true allAxisBoundaryAndPlusOne=true GPURecords=true")
    }
  }
}
