package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedNativeRgba8SamplingSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private val all=BigInt(15);private val previous=(0 until 4).map(n=>BigInt(0xabc00000L+n))
  private def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  private def instruction(vector: Boolean,param: Boolean,field: Int,integer: Boolean=false,query: Boolean=false): BigInt={
    val shift=if(vector)26 else 25;val vm=if(vector)BigInt(1)<<25 else BigInt(0)
    (BigInt(if(param)3 else if(query)6 else if(integer)5 else 4)<<shift)|vm|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(field)<<12)|
      (if(param)BigInt(0)else BigInt(10)<<7)|(if(vector)0x2b else 0x0b)
  }
  private class Driver(val d: SharedNativeTextureHarness) {
    val lanes=d.io.native.vectorIn.bits.issued.vs1Data.length
    private val all=(BigInt(1)<<lanes)-1;private val previous=(0 until lanes).map(n=>BigInt(0xabc00000L+n))
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.native.memoryRequest.bits,d.io.native.memoryResponse.bits,d.io.native.memoryRequest.valid,d.io.native.memoryRequest.ready,d.io.native.memoryResponse.valid,d.io.native.memoryResponse.ready)
    memory.beforeRequest=r=>assert(!r.write && r.tag<2 && r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
    var warp=1;var checks=0;var before=0
    val groupMemory=new Memory(d.io.dataRequest.bits,d.io.dataResponse.bits,d.io.dataRequest.valid,d.io.dataRequest.ready,d.io.dataResponse.valid,d.io.dataResponse.ready){override val bytes=memory.bytes}
    val walkMemory=new Memory(d.io.pageWalk.bits,d.io.pageWalkResponse.bits,d.io.pageWalk.valid,d.io.pageWalk.ready,d.io.pageWalkResponse.valid,d.io.pageWalkResponse.ready){override val bytes=memory.bytes}
    def tick(): Unit={
      groupMemory.faultAt=memory.faultAt;groupMemory.holdRequests=memory.holdRequests;groupMemory.holdResponses=memory.holdResponses
      memory.drive();groupMemory.drive();walkMemory.drive();memory.capture();groupMemory.capture();walkMemory.capture();d.clock.step()
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"floating execution stalled: ${memory.seen.takeRight(8)}")}
    def context(): Unit={d.io.native.resources.version.poke(1.U);d.io.native.resources.address.poke(root.U);identity(d.io.native.identity)}
    def put(a: BigInt,v: BigInt): Unit=memory.putWord(a,v)
    def byte(a: BigInt,v: Int): Unit={memory.bytes(a)=v}
    def clear(): Unit={d.io.native.busy.expect(false.B);d.io.native.clearParameters.poke(true.B);tick();d.io.native.clearParameters.poke(false.B)}
    def issue(vector: Boolean,param: Boolean,field: Int,left: Seq[BigInt],right: Seq[BigInt],mask: BigInt=all,integer: Boolean=false,query: Boolean=false): Unit={
      context();before=memory.seen.size
      if(vector){zero(d.io.native.vectorIn.bits);val q=d.io.native.vectorIn.bits
        q.issued.decode.instruction.poke(instruction(true,param,field,integer,query).U);q.issued.decode.warpId.poke(warp.U)
        q.issued.decode.pc.poke(0x200.U);q.issued.decode.activeMask.poke(all.U);q.executionMask.poke(mask.U)
        q.issued.vs1Data.zip(left).foreach{case(a,b)=>a.poke(b.U)};q.issued.vs2Data.zip(right).foreach{case(a,b)=>a.poke(b.U)}
        q.issued.oldVdData.zip(previous).foreach{case(a,b)=>a.poke(b.U)}
        d.io.native.vectorIn.valid.poke(true.B);until(d.io.native.vectorIn.ready.peek().litToBoolean);tick();d.io.native.vectorIn.valid.poke(false.B);zero(q)
      }else{zero(d.io.native.in.bits);val q=d.io.native.in.bits
        q.decode.instruction.poke(instruction(false,param,field,integer,query).U);q.decode.warpId.poke(warp.U);q.decode.pc.poke(0x200.U);q.decode.activeMask.poke(all.U)
        q.rs1Data.poke(left.head.U);q.rs2Data.poke(right.head.U)
        d.io.native.in.valid.poke(true.B);until(d.io.native.in.ready.peek().litToBoolean);tick();d.io.native.in.valid.poke(false.B);zero(q)
      }
      zero(d.io.native.resources);zero(d.io.native.identity)
    }
    def retire(vector: Boolean,param: Boolean,expected: Seq[BigInt],fault: Boolean=false,faultAddress: Option[BigInt]=None): Unit={
      until(if(vector)d.io.native.vectorCommit.valid.peek().litToBoolean else d.io.native.commit.valid.peek().litToBoolean)
      if(vector){val q=d.io.native.vectorCommit.bits;q.writeback.warpId.expect(warp.U);q.writeback.vd.expect((if(param)0 else 10).U)
        q.writesVd.expect((!param && !fault).B);q.writesScalar.expect(param.B);q.scalarData.expect(0.U)
        q.writeback.data.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      }else{val q=d.io.native.commit.bits;q.warpId.expect(warp.U);q.rd.expect((if(param)0 else 10).U);q.writeRd.expect((param || !fault).B);q.data.expect(expected.head.U)}
      faultAddress.foreach(a=>d.io.native.faultAddress.expect(a.U))
      d.io.native.responseFault.expect(fault.B);assert(memory.pending.isEmpty && memory.reply.isEmpty && groupMemory.pending.isEmpty && groupMemory.reply.isEmpty && walkMemory.pending.isEmpty && walkMemory.reply.isEmpty)
      val held=if(vector)d.io.native.vectorCommit.bits.peek().litValue else d.io.native.commit.bits.peek().litValue
      d.io.native.cancel.poke(true.B);d.io.native.invalidate.poke(true.B)
      for(_<-0 until 3){tick();assert((if(vector)d.io.native.vectorCommit.bits.peek().litValue else d.io.native.commit.bits.peek().litValue)==held)}
      if(vector)d.io.native.vectorCommit.ready.poke(true.B)else d.io.native.commit.ready.poke(true.B)
      tick();d.io.native.vectorCommit.ready.poke(false.B);d.io.native.commit.ready.poke(false.B);d.io.native.cancel.poke(false.B);d.io.native.invalidate.poke(false.B)
      d.io.native.busy.expect(false.B);checks+=1
    }
    def parameters(words: Seq[BigInt],vector: Boolean=true,mask: BigInt=all,pairs: Seq[Int]=0 until 8): Unit={
      for(pair<-pairs){val start=memory.seen.size;issue(vector,true,pair,Seq.fill(lanes)(words(2*pair)),Seq.fill(lanes)(words(2*pair+1)),mask)
        retire(vector,true,Seq.fill(lanes)(BigInt(0)));assert(memory.seen.size==start,"parameter writes must remain on chip")}
    }
    def sample(c: Int=0,vector: Boolean=false,mask: BigInt=all,units: Seq[BigInt]=Seq.fill(lanes)(BigInt(0)),control: Int=1): Unit=
      issue(vector,false,c,units,Seq.fill(lanes)(BigInt(control)),mask)
  }

  private def aligned(t: Driver,stage: Int,raw: BigInt=0xff804020L): View={
    val v=install(t.put,t.byte,0,3,0,stage,raw=raw,swizzle=Seq(3,2,5,0))
    t.put(levels+17*4,16);t.put(v.address,0);t.put(0x20050,raw)
    v.copy(address=0x20050)
  }
  private def swizzle(color: Seq[BigInt]): Seq[BigInt]={val opts=color++Seq(BigInt(0),fp(1f));Seq(3,2,5,0).map(opts)}
  for(stage<-0 to 1)it should s"execute aligned scalar and masked vector nearest and bilinear RGBA8 with one final swizzle stage=$stage" in {
    simulate(new SharedNativeTextureHarness(GpuConfig(lanes=4,warps=2),stage)){d=>
      val t=new Driver(d);roots(t.put,stage);val v=aligned(t,stage);t.clear()
      t.parameters(Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0)))
      for(c<-0 until 4){t.sample(c);t.retire(false,false,Seq(v.expected(c)))
        t.sample(c,vector=true,mask=5);t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)v.expected(c)else previous(l)))}
      val groups=d.io.groupRequests.peek().litValue;val reads=d.io.dataReads.peek().litValue
      assert(groups==12 && reads>0 && t.walkMemory.seen.isEmpty)
      t.put(samplers+7*4,1)
      t.parameters((Seq(fp(0.5f),fp(0.5f))++Seq.fill(14)(BigInt(0))).updated(5,fp(1f)))
      val decoded=ImageFormatReference.decode("RGBA8",v.raw)
      val filtered=TextureFilterReference.filter(Seq.fill(3)(Seq.fill(4)(BigInt(0))) :+ decoded,Seq(fp(0.5f),fp(0.5f)),2)
      val expected=swizzle(filtered)
      for(c<-0 until 4){t.sample(c);t.retire(false,false,Seq(expected(c)))
        t.sample(c,vector=true,mask=5);t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)expected(c)else previous(l)))}
      assert(d.io.groupRequests.peek().litValue==24 && d.io.dataReads.peek().litValue>reads)
      info(s"SHARED_NATIVE_RGBA8 stage=$stage actualNativeOpcodes=true scalarVector=true maskedOldVd=true nearestAndBilinear=true realBankedDataReads=true finalSwizzleOnce=true groups24=true")
    }
  }
  for(stage<-0 to 1)it should s"blend two shared mip results before applying the final native swizzle stage=$stage" in {
    simulate(new SharedNativeTextureHarness(GpuConfig(lanes=4,warps=2),stage)){d=>
      val t=new Driver(d);roots(t.put,stage);val v=aligned(t,stage);val second=BigInt(0x4080ff10L)
      val level1=(0 until 32).map(n=>t.memory.word(levels+n*4)).toArray
      level1(6)=1;level1(8)=1;level1(12)=0x20200;level1(13)=1;level1(14)=1;level1(17)=4
      NativeTextureFetchTestData.words(t.put,levels+128,level1.toSeq,32)
      t.put(images+10*4,1);t.put(images+20*4,levels+256);t.put(samplers+7*4,5);t.put(0x20200,second);t.clear()
      t.parameters((Seq(fp(0.5f),fp(0.5f))++Seq.fill(14)(BigInt(0))).updated(5,fp(0.5f)))
      val first=TextureFilterReference.filter(Seq.fill(3)(Seq.fill(4)(BigInt(0))) :+ ImageFormatReference.decode("RGBA8",v.raw),Seq(fp(0.5f),fp(0.5f)),2)
      val blend=TextureFilterReference.filter(Seq(first,ImageFormatReference.decode("RGBA8",second)),Seq(fp(0.5f)),1)
      val expected=swizzle(blend)
      for(c<-0 until 4){t.sample(c);t.retire(false,false,Seq(expected(c)))
        t.sample(c,vector=true,mask=5);t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)expected(c)else previous(l)))}
      assert(d.io.groupRequests.peek().litValue==24 && d.io.dataReads.peek().litValue>0)
      info(s"SHARED_NATIVE_TRILINEAR stage=$stage levels2=true spatialBeforeMip=true nativeMipFMA=true swizzleAfterMip=true scalarVector=true groups24=true")
    }
  }
  for(stage<-0 to 1)it should s"retain numeric work and prevent late shared cache publication across held-offer invalidation and cancellation stage=$stage" in {
    simulate(new SharedNativeTextureHarness(GpuConfig(lanes=4,warps=2),stage)){d=>
      val t=new Driver(d);roots(t.put,stage);val v=aligned(t,stage);t.clear()
      val words=Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0));t.parameters(words)
      t.groupMemory.holdRequests=true;t.sample(vector=true,mask=5);t.until(d.io.dataRequest.valid.peek().litToBoolean)
      d.io.native.invalidate.poke(true.B);t.tick();d.io.native.invalidate.poke(false.B)
      for(_<-0 until 8)t.tick()
      t.groupMemory.holdRequests=false
      t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)v.expected(0)else previous(l)))
      val newer=BigInt(0x22446688L);t.put(v.address,newer);t.parameters(words);t.sample()
      val expected=swizzle(ImageFormatReference.decode("RGBA8",newer)).head;t.retire(false,false,Seq(expected))
      t.parameters(words);t.groupMemory.holdRequests=true;t.sample(vector=true);t.until(d.io.dataRequest.valid.peek().litToBoolean)
      d.io.native.cancel.poke(true.B);t.tick();d.io.native.cancel.poke(false.B)
      for(_<-0 until 8)t.tick()
      t.groupMemory.holdRequests=false;t.retire(true,false,Seq.fill(4)(BigInt(0)),fault=true)
      t.parameters(words);t.sample();t.retire(false,false,Seq(expected))
      info(s"SHARED_NATIVE_DRAIN stage=$stage heldDataOfferImmutable=true invalidationKeepsNumericResult=true latePublicationPurged=true cancellationFaultAtomic=true changedBackingObserved=true healthyReuse=true")
    }
  }
}
