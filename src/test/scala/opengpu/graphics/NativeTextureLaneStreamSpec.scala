package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativeTextureLaneStreamSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private val all=BigInt(65535);private val previous=(0 until 16).map(n=>BigInt(0xabc00000L+n))
  private def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  private def instruction(vector: Boolean,param: Boolean,field: Int,integer: Boolean=false,query: Boolean=false): BigInt={
    val shift=if(vector)26 else 25;val vm=if(vector)BigInt(1)<<25 else BigInt(0)
    (BigInt(if(param)3 else if(query)6 else if(integer)5 else 4)<<shift)|vm|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(field)<<12)|
      (if(param)BigInt(0)else BigInt(10)<<7)|(if(vector)0x2b else 0x0b)
  }

  private class Driver(val d: StreamingNativeTextureHarness) {
    val lanes=d.io.native.vectorIn.bits.issued.vs1Data.length
    private val all=(BigInt(1)<<lanes)-1;private val previous=(0 until lanes).map(n=>BigInt(0xabc00000L+n))
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.native.memoryRequest.bits,d.io.native.memoryResponse.bits,d.io.native.memoryRequest.valid,d.io.native.memoryRequest.ready,d.io.native.memoryResponse.valid,d.io.native.memoryResponse.ready)
    memory.beforeRequest=r=>assert(!r.write && r.tag<2 && r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
    var warp=1;var checks=0;var before=0;var cycle=0
    val admitted=scala.collection.mutable.ArrayBuffer.empty[(Int,BigInt)]
    val groupMemory=new Memory(d.io.dataRequest.bits,d.io.dataResponse.bits,d.io.dataRequest.valid,d.io.dataRequest.ready,d.io.dataResponse.valid,d.io.dataResponse.ready){override val bytes=memory.bytes}
    val walkMemory=new Memory(d.io.pageWalk.bits,d.io.pageWalkResponse.bits,d.io.pageWalk.valid,d.io.pageWalk.ready,d.io.pageWalkResponse.valid,d.io.pageWalkResponse.ready){override val bytes=memory.bytes}
    def tick(): Unit={
      if(d.io.groupFire.peek().litToBoolean)admitted+=((cycle,d.io.groupTag.peek().litValue))
      cycle+=1
      groupMemory.faultAt=memory.faultAt;groupMemory.holdRequests=memory.holdRequests;groupMemory.holdResponses=memory.holdResponses
      memory.drive();groupMemory.drive();walkMemory.drive();memory.capture();groupMemory.capture();walkMemory.capture();d.clock.step()
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<60000){tick();n+=1};assert(p,s"floating execution stalled: ${memory.seen.takeRight(8)}")}
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
    def retire(vector: Boolean,param: Boolean,expected: Seq[BigInt],fault: Boolean=false,faultAddress: Option[BigInt]=None,probeCancel: Boolean=true): Unit={
      until(if(vector)d.io.native.vectorCommit.valid.peek().litToBoolean else d.io.native.commit.valid.peek().litToBoolean)
      if(vector){val q=d.io.native.vectorCommit.bits;q.writeback.warpId.expect(warp.U);q.writeback.vd.expect((if(param)0 else 10).U)
        q.writesVd.expect((!param && !fault).B);q.writesScalar.expect(param.B);q.scalarData.expect(0.U)
        q.writeback.data.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      }else{val q=d.io.native.commit.bits;q.warpId.expect(warp.U);q.rd.expect((if(param)0 else 10).U);q.writeRd.expect((param || !fault).B);q.data.expect(expected.head.U)}
      faultAddress.foreach(a=>d.io.native.faultAddress.expect(a.U))
      d.io.native.responseFault.expect(fault.B);assert(memory.pending.isEmpty && memory.reply.isEmpty && groupMemory.pending.isEmpty && groupMemory.reply.isEmpty && walkMemory.pending.isEmpty && walkMemory.reply.isEmpty)
      val held=if(vector)d.io.native.vectorCommit.bits.peek().litValue else d.io.native.commit.bits.peek().litValue
      if(probeCancel){d.io.native.cancel.poke(true.B);d.io.native.invalidate.poke(true.B)}
      for(_<-0 until 3){tick();assert((if(vector)d.io.native.vectorCommit.bits.peek().litValue else d.io.native.commit.bits.peek().litValue)==held)}
      if(vector)d.io.native.vectorCommit.ready.poke(true.B)else d.io.native.commit.ready.poke(true.B)
      tick();d.io.native.vectorCommit.ready.poke(false.B);d.io.native.commit.ready.poke(false.B);d.io.native.cancel.poke(false.B);d.io.native.invalidate.poke(false.B)
      d.io.native.busy.expect(false.B);checks+=1
    }
    def parameters(words: Seq[BigInt],vector: Boolean=true,mask: BigInt=all,pairs: Seq[Int]=0 until 8,probeCancel: Boolean=true): Unit={
      for(pair<-pairs){val start=memory.seen.size;issue(vector,true,pair,Seq.fill(lanes)(words(2*pair)),Seq.fill(lanes)(words(2*pair+1)),mask)
        retire(vector,true,Seq.fill(lanes)(BigInt(0)),probeCancel=probeCancel);assert(memory.seen.size==start,"parameter writes must remain on chip")}
    }
    def sample(c: Int=0,vector: Boolean=false,mask: BigInt=all,units: Seq[BigInt]=Seq.fill(lanes)(BigInt(0)),control: Int=1): Unit=
      issue(vector,false,c,units,Seq.fill(lanes)(BigInt(control)),mask)
  }

  private val cfg=GpuConfig(lanes=16,warps=2)
  private def aligned(t: Driver,stage: Int,unit: Int=0,swizzle: Seq[Int]=Seq(3,2,5,4)): Unit={
    roots(t.put,stage);install(t.put,t.byte,unit,3,0,stage,swizzle=swizzle)
    val base=BigInt(0x20000)+unit*4096;val level=levels+unit*128
    t.put(level+12*4,base);t.put(level+17*4,64)
    t.put(samplers+unit*64+7*4,1);t.put(samplers+unit*64+8*4,1)
    for(y<-0 until 2;x<-0 until 2)t.put(base+y*64+x*4,BigInt(0xff102030L)+unit*0x111111)
  }
  private def tuples(t: Driver): Unit={
    t.clear();t.parameters(Seq(fp(0.25f),fp(0.25f))++Seq.fill(14)(BigInt(0)))
  }
  private def color(raw: BigInt=BigInt(0xff102030L)): Seq[BigInt]=
    Seq(fp(1f),fp(((raw>>16)&255).toInt.toFloat/255f),fp(1f),BigInt(0))

  for(stage<-0 to 1)it should s"overlap sixteen real native lanes and apply one final image swizzle stage=$stage" in {
    simulate(new StreamingNativeTextureHarness(cfg,stage)){d=>
      val t=new Driver(d);aligned(t,stage);tuples(t)
      for(c<-0 until 4){
        val start=t.admitted.size;val reads=t.memory.seen.size
        t.sample(c,vector=true);t.retire(true,false,Seq.fill(16)(color()(c)))
        val issued=t.admitted.drop(start)
        assert(issued.map(x=>x._2 & 15).toVector==(0 until 16).map(BigInt(_)).toVector)
        assert(issued.sliding(3).exists(xs=>xs.size==3 && xs(2)._1-xs(0)._1==2),"native stream inserted bubbles in every three-lane admission burst")
        val bindingReads=t.memory.seen.drop(reads).count(r=>r.address>=root && r.address<root+128)
        assert(bindingReads==2,s"uniform vector reread its root $bindingReads times")
      }
      d.io.maximumGroups.expect(4.U)
      info(s"NATIVE_LANE_STREAM stage=$stage lanes16=true components4=true actualSharedPoolCredits4=true consecutiveAdmissions=true rootReadOncePerInstruction=true finalSwizzleOnce=true exactVectorResults=true")
    }
  }
  it should "retain four blocked lane replies and cancel a fifth held offer before atomic instruction retirement" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.until(d.io.groupRequests.peek().litValue>=2)
      d.io.holdGroupReplies.poke(true.B);t.until(d.io.groupRequests.peek().litValue==5)
      for(_<-0 until 8){t.tick();d.io.native.vectorCommit.valid.expect(false.B);d.io.groupRequests.expect(5.U)}
      d.io.native.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.native.vectorCommit.valid.expect(false.B)}
      d.io.holdGroupReplies.poke(false.B);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      d.io.maximumGroups.expect(4.U)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head))
      info("NATIVE_LANE_CANCEL heldCredits4=true fifthOfferDrained=true originalTagsCancelled=true atomicWriteSuppression=true healthyReuse=true")
    }
  }
  it should "stop descriptor reuse after invalidation and resume remaining lanes through the general sampler" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.until(d.io.groupRequests.peek().litValue>=2)
      d.io.holdGroupReplies.poke(true.B);t.until(d.io.groupRequests.peek().litValue==5)
      // Observe a full-credit stall before invalidating, so the fifth offer owns placement.
      t.tick()
      d.io.native.invalidate.poke(true.B);t.tick();d.io.native.invalidate.poke(false.B)
      for(_<-0 until 8){t.tick();d.io.native.vectorCommit.valid.expect(false.B)}
      d.io.holdGroupReplies.poke(false.B);t.retire(true,false,Seq.fill(16)(color().head))
      assert(d.io.groupRequests.peek().litValue>=5 && d.io.groupRequests.peek().litValue<=9,"invalidation exceeded the four public credits plus four bounded queued offers")
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head))
      info("NATIVE_LANE_INVALIDATE pendingRowsDrain=true heldOfferPreserved=true noNewTemplateReuse=true generalRemainder=true healthyReuse=true")
    }
  }
  it should "suppress every destination lane on a later streaming data fault and recover with a new instruction" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.issue(true,true,0,Seq.fill(16)(fp(0.25f)),(0 until 16).map(l=>fp(if(l==15)0.75f else 0.25f)))
      t.retire(true,true,Seq.fill(16)(BigInt(0)))
      t.parameters(Seq.fill(16)(BigInt(0)),pairs=1 until 8)
      t.memory.faultAt=Set(BigInt(0x20040))
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true,faultAddress=Some(0x20040))
      assert(t.admitted.exists(x=>(x._2 & 15)==15) && t.groupMemory.seen.exists(_.address==0x20040))
      t.memory.faultAt=Set.empty;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head))
    }
  }
  it should "retain nonuniform bindings masked destinations malformed tuples and task reset semantics" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);aligned(t,1,1);tuples(t)
      val selected=BigInt(0xaaaa)
      t.sample(c=1,vector=true,mask=selected,units=(0 until 16).map(l=>BigInt((l/2)%2)))
      t.retire(true,false,(0 until 16).map(l=>if(selected.testBit(l))color(BigInt(0xff102030L)+((l/2)%2)*0x111111)(1)else previous(l)))
      t.sample(vector=true,mask=0);t.retire(true,false,previous);assert(t.memory.seen.size==t.before)
      // A late lane missing reserved-word validity must suppress the complete instruction.
      t.issue(true,true,0,Seq.fill(16)(fp(0.25f)),Seq.fill(16)(fp(0.25f)),mask=BigInt(1)<<15)
      t.retire(true,true,Seq.fill(16)(BigInt(0)))
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      t.clear();t.sample(vector=true);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      assert(t.memory.seen.size==t.before)
      tuples(t);t.sample(c=3,vector=true,mask=selected)
      t.retire(true,false,(0 until 16).map(l=>if(selected.testBit(l))BigInt(0)else previous(l)))
    }
  }
  it should "sample distinct per-lane texels offsets and bilinear weights from one checked snapshot" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1,swizzle=Seq(0,1,2,3));tuples(t)
      val raw=Seq[BigInt](0xff204060L,0x80402010L,0x4080c020L,0x20e0a080L)
      for(i<-0 until 4)t.put(0x20000+(i/2)*64+(i%2)*4,raw(i))
      val coords=Seq(0.25f,0.5f,0.75f,-0.25f)
      val xs=(0 until 16).map(l=>coords(l%4));val ys=(0 until 16).map(l=>coords(l/4))
      t.issue(true,true,0,xs.map(fp),ys.map(fp));t.retire(true,true,Seq.fill(16)(BigInt(0)))
      t.parameters(Seq.fill(16)(BigInt(0)),pairs=1 until 8)
      val offsets=(0 until 16).map(l=>BigInt(if(l%2==0)0 else 15))
      t.issue(true,true,6,Seq.fill(16)(BigInt(0)),offsets);t.retire(true,true,Seq.fill(16)(BigInt(0)))
      def expected(l: Int,c: Int): BigInt={
        val x=xs(l)*2f-0.5f+(if(l%2==0)0 else -1);val y=ys(l)*2f-0.5f
        val ix=math.floor(x.toDouble).toInt;val iy=math.floor(y.toDouble).toInt
        def mod(n: Int): Int=((n%2)+2)%2
        val colors=for(j<-0 until 2;i<-0 until 2)yield ImageFormatReference.decode("RGBA8",raw(mod(iy+j)*2+mod(ix+i)))
        TextureFilterReference.filter(colors,Seq(fp(x-ix),fp(y-iy)),2)(c)
      }
      for(c<-0 until 4){t.sample(c,vector=true);t.retire(true,false,(0 until 16).map(l=>expected(l,c)))}
      info("NATIVE_LANE_COORDINATES lanes16=true distinctTexels4=true signedOffsets=true repeatWrap=true endpointAndHalfWeights=true exactIndependentFilterOracle=true")
    }
  }
  for(groups<-Seq(2,5))it should s"drain lane queues and held offers under a held reset groups=$groups" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.until(d.io.groupRequests.peek().litValue>=2)
      if(groups==5){d.io.holdGroupReplies.poke(true.B);t.until(d.io.groupRequests.peek().litValue==5);for(_<-0 until 8)t.tick()}
      d.io.globalCancel.poke(true.B);d.io.holdGroupReplies.poke(false.B)
      var n=0;while(!d.io.native.vectorCommit.valid.peek().litToBoolean && n<4096){t.tick();n+=1}
      assert(d.io.native.vectorCommit.valid.peek().litToBoolean,s"held reset stalled: accepted=${d.io.groupRequests.peek().litValue} returned=${d.io.returnedGroups.peek().litValue} sharedBusy=${d.io.sharedBusy.peek().litToBoolean}")
      t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      d.io.globalCancel.poke(false.B);t.tick();t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head))
      info(s"NATIVE_LANE_HELD_RESET groups=$groups cancellationHeldThroughRetirement=true queuedAndHeldOffersDrain=true atomicDestinationSuppression=true healthyReuse=true")
    }
  }

  for(stage<-0 to 1)it should s"prepare another warp while an earlier native instruction retains all four shared credits stage=$stage" in {
    simulate(new StreamingNativeTextureHarness(cfg,stage)){d=>
      val t=new Driver(d);aligned(t,stage);aligned(t,stage,1)
      t.clear()
      for(w<-0 until 2){t.warp=w;t.parameters(Seq(fp(0.25f),fp(0.25f))++Seq.fill(14)(BigInt(0)))}
      t.warp=0;t.sample(vector=true)
      t.until(d.io.groupRequests.peek().litValue>=2)
      d.io.holdGroupReplies.poke(true.B);t.until(d.io.groupRequests.peek().litValue==5)
      val firstRootReads=t.memory.seen.count(r=>r.address>=root && r.address<root+128)
      assert(firstRootReads==2)
      t.warp=1;t.sample(c=1,vector=true,units=Seq.fill(16)(BigInt(1)))
      t.until(t.memory.seen.count(r=>r.address>=root && r.address<root+128)==4)
      d.io.native.vectorCommit.valid.expect(false.B)
      d.io.holdGroupReplies.poke(false.B)
      t.until(d.io.native.vectorCommit.valid.peek().litToBoolean)
      val held=d.io.native.vectorCommit.bits.peek().litValue
      t.until(d.io.groupRequests.peek().litValue==32 && d.io.returnedGroups.peek().litValue==32)
      for(_<-0 until 12){t.tick();assert(d.io.native.vectorCommit.bits.peek().litValue==held)}
      val observed=scala.collection.mutable.Set.empty[Int]
      d.io.native.vectorCommit.ready.poke(true.B)
      var cycles=0
      while(observed.size<2 && cycles<256){
        if(d.io.native.vectorCommit.valid.peek().litToBoolean){
          val q=d.io.native.vectorCommit.bits;val w=q.writeback.warpId.peek().litValue.toInt
          assert(!observed(w));observed+=w;q.writeback.vd.expect(10.U);q.writesVd.expect(true.B)
          val want=if(w==0)color().head else color(BigInt(0xff102030L)+0x111111)(1)
          q.writeback.data.foreach(_.expect(want.U));d.io.native.responseFault.expect(false.B)
        }
        t.tick();cycles+=1
      }
      assert(observed==Set(0,1));d.io.native.vectorCommit.ready.poke(false.B)
      d.io.native.busy.expect(false.B);d.io.sharedBusy.expect(false.B)
      t.clear();t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      info(s"NATIVE_INSTRUCTION_CONTEXTS stage=$stage warps2=true secondInstructionAdmittedBeforeFirstRetirement=true metadataOverlapWhileCreditsFull=true sharedPhysicalCredits4=true groups32=true heldWritebackImmutable=true exactPerWarpResults=true taskResetClearsTuples=true")
    }
  }

  for(stage<-0 to 1)it should s"reuse checked metadata across components warps and changed coordinates without new descriptor reads stage=$stage" in {
    simulate(new StreamingNativeTextureHarness(cfg,stage)){d=>
      val t=new Driver(d);aligned(t,stage)
      val raw=Seq[BigInt](0xff102030L,0xff405060L,0xff708090L,0xffa0b0c0L)
      for(i<-0 until 4)t.put(0x20000+(i/2)*64+(i%2)*4,raw(i))
      t.clear()
      val words=Seq(fp(0.25f),fp(0.25f))++Seq.fill(14)(BigInt(0))
      for(w<-0 until 2){t.warp=w;t.parameters(words,probeCancel=false)}
      t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color(raw.head).head),probeCancel=false)
      val reads=t.memory.seen.size;val groups=t.admitted.size
      assert(t.memory.seen.count(r=>r.address>=root && r.address<root+128)==2)
      t.warp=1;t.sample(c=1,vector=true);t.retire(true,false,Seq.fill(16)(color(raw.head)(1)),probeCancel=false)
      assert(t.memory.seen.size==reads,"cache hit reread descriptors")
      assert(t.admitted.drop(groups).size==16 && t.admitted.drop(groups).forall(_._2.testBit(31)))
      t.warp=0;t.sample(c=2,vector=true);t.retire(true,false,Seq.fill(16)(color(raw.head)(2)),probeCancel=false)
      assert(t.memory.seen.size==reads)
      t.parameters(words.updated(0,fp(0.75f)).updated(1,fp(0.75f)),probeCancel=false)
      t.sample(c=1,vector=true);t.retire(true,false,Seq.fill(16)(color(raw.last)(1)),probeCancel=false)
      assert(t.memory.seen.size==reads,"changed coordinates must reuse metadata, not cached texels/results")
      t.clear();t.parameters(words,probeCancel=false);t.sample(c=1,vector=true)
      t.retire(true,false,Seq.fill(16)(color(raw.head)(1)),probeCancel=false)
      assert(t.memory.seen.count(r=>r.address>=root && r.address<root+128)==4,"task launch retained a checked snapshot")
      info(s"NATIVE_SNAPSHOT_REUSE stage=$stage componentsAndWarpsShareCheckedMetadata=true warmInstructionDescriptorReads0=true coordinatesRemainPerInstruction=true fullVectorResults=true taskResetDropsSnapshots=true physicalCredits4=true")
    }
  }
  for(stage<-0 to 1)it should s"miss changed binding and LOD keys and refresh same-identity publication after invalidation stage=$stage" in {
    simulate(new StreamingNativeTextureHarness(cfg,stage)){d=>
      val t=new Driver(d);aligned(t,stage);aligned(t,stage,1);tuples(t)
      def rootsRead: Int=t.memory.seen.count(r=>r.address>=root && r.address<root+128)
      def sample(unit: Int,expected: BigInt): Unit={
        t.sample(c=1,vector=true,units=Seq.fill(16)(BigInt(unit)))
        t.retire(true,false,Seq.fill(16)(expected),probeCancel=false)
      }
      sample(0,color()(1));assert(rootsRead==2)
      sample(1,color(BigInt(0xff102030L)+0x111111)(1));assert(rootsRead==4)
      t.issue(true,true,2,Seq.fill(16)(BigInt(0)),Seq.fill(16)(fp(1f)))
      t.retire(true,true,Seq.fill(16)(BigInt(0)),probeCancel=false)
      sample(1,color(BigInt(0xff102030L)+0x111111)(1));assert(rootsRead==6)
      sample(1,color(BigInt(0xff102030L)+0x111111)(1));assert(rootsRead==6)
      d.io.native.invalidate.poke(true.B);t.tick();d.io.native.invalidate.poke(false.B)
      val image=images+128;val level=levels+128;val base=BigInt(0x50000);val raw=BigInt(0xffa1b2c3L)
      t.put(image+15*4,base);t.put(image+16*4,base+4096)
      t.put(level+9*4,base);t.put(level+10*4,base+4096);t.put(level+12*4,base)
      for(y<-0 until 2;x<-0 until 2)t.put(base+y*64+x*4,raw)
      sample(1,color(raw)(1));assert(rootsRead==8,"invalidation retained the old checked view")
      assert(t.groupMemory.seen.exists(_.address==base),"new backing never reached the physical cluster")
      sample(1,color(raw)(1));assert(rootsRead==8)
      t.issue(true,true,0,Seq.fill(16)(fp(0.25f)),Seq.fill(16)(fp(0.25f)),mask=BigInt(1)<<15)
      t.retire(true,true,Seq.fill(16)(BigInt(0)),probeCancel=false)
      t.sample(c=1,vector=true,units=Seq.fill(16)(BigInt(1)))
      t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true)
      info(s"NATIVE_SNAPSHOT_PUBLICATION stage=$stage bindingAndFullLodKey=true invalidationDropsOldView=true sameIdentityNewBacking=true invalidTupleCannotHit=true faultAtomicVector=true")
    }
  }

  it should "retain a cached instruction first-lane physical fault and recover without reusing faulted texels" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      val reads=t.memory.seen.size
      t.parameters(Seq(fp(0.25f),fp(0.75f))++Seq.fill(14)(BigInt(0)),probeCancel=false)
      val start=t.admitted.size;t.memory.faultAt=Set(BigInt(0x20040))
      t.sample(vector=true)
      t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true,faultAddress=Some(BigInt(0x20040)),probeCancel=false)
      assert(t.memory.seen.size==reads && t.admitted(start)._2.testBit(31) && (t.admitted(start)._2 & 15)==0)
      assert(t.groupMemory.seen.exists(_.address==0x20040))
      t.memory.faultAt=Set.empty;t.sample(vector=true)
      t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      assert(t.memory.seen.size==reads,"a physical data fault must not masquerade as cached successful texels")
      info("NATIVE_CACHED_FIRST_FAULT firstLane0=true noDescriptorReread=true actualPhysicalFault=true exactFaultAddress=true atomicWriteSuppression=true faultedFillNotInstalled=true healthyReuse=true")
    }
  }
  it should "validate a cached instruction first-lane NaN before atomic retirement" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      val reads=t.memory.seen.size;val start=t.admitted.size
      t.issue(true,true,0,(0 until 16).map(l=>if(l==0)BigInt(0x7fc00000L)else fp(0.25f)),Seq.fill(16)(fp(0.25f)))
      t.retire(true,true,Seq.fill(16)(BigInt(0)),probeCancel=false)
      t.parameters(Seq.fill(16)(BigInt(0)),pairs=1 until 8,probeCancel=false)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true,probeCancel=false)
      assert(t.memory.seen.size==reads && t.admitted(start)._2.testBit(31) && (t.admitted(start)._2 & 15)==0)
      t.parameters(Seq(fp(0.25f),fp(0.25f))++Seq.fill(14)(BigInt(0)),probeCancel=false)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      assert(t.memory.seen.size==reads)
      info("NATIVE_CACHED_NAN firstLane0=true noDescriptorReread=true checkedCoordinateFault=true atomicWriteSuppression=true healthyReuse=true")
    }
  }
  it should "drain a cached instruction under held reset and discard its checked metadata before recovery" in {
    simulate(new StreamingNativeTextureHarness(cfg,1)){d=>
      val t=new Driver(d);aligned(t,1);tuples(t)
      t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      val reads=t.memory.seen.size;val groups=d.io.groupRequests.peek().litValue
      t.sample(vector=true);t.until(d.io.groupRequests.peek().litValue>=groups+1)
      d.io.holdGroupReplies.poke(true.B);t.until(d.io.groupRequests.peek().litValue==groups+4)
      for(_<-0 until 8)t.tick()
      assert(t.memory.seen.size==reads)
      d.io.globalCancel.poke(true.B);d.io.holdGroupReplies.poke(false.B)
      t.retire(true,false,Seq.fill(16)(BigInt(0)),fault=true,probeCancel=false)
      d.io.sharedBusy.expect(false.B)
      d.io.globalCancel.poke(false.B);t.tick();t.sample(vector=true)
      t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      assert(t.memory.seen.count(r=>r.address>=root && r.address<root+128)==4,"reset retained checked metadata")
      info("NATIVE_CACHED_HELD_RESET firstLaneCached=true credits4Held=true queuedOffersDrained=true resetHeldThroughRetirement=true snapshotDiscarded=true healthyReuse=true")
    }
  }
  it should "measure cached instruction warm native throughput with sixteen lanes and eight resident warps" in {
    simulate(new StreamingNativeTextureHarness(GpuConfig(),1)){d=>
      var measuring=false
      val retired=scala.collection.mutable.ArrayBuffer.empty[(Int,Int)]
      val t=new Driver(d){
        override def tick(): Unit={
          if(measuring && d.io.native.vectorCommit.valid.peek().litToBoolean && d.io.native.vectorCommit.ready.peek().litToBoolean){
            val q=d.io.native.vectorCommit.bits;val w=q.writeback.warpId.peek().litValue.toInt
            assert(w>=0 && w<8);q.writesVd.expect(true.B);q.writeback.vd.expect(10.U)
            q.writeback.data.foreach(_.expect(color()(w%4).U));d.io.native.responseFault.expect(false.B)
            retired+=((cycle,w))
          }
          super.tick()
        }
      }
      aligned(t,1);t.clear()
      for(w<-0 until 8){t.warp=w;t.parameters(Seq(fp(0.25f),fp(0.25f))++Seq.fill(14)(BigInt(0)),probeCancel=false)}
      t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(color().head),probeCancel=false)
      val metadataReads=t.memory.seen.size;val dataReads=t.groupMemory.seen.size
      val first=t.admitted.size;val returned=d.io.returnedGroups.peek().litValue;val startCycle=t.cycle
      measuring=true;d.io.native.vectorCommit.ready.poke(true.B)
      for(round<-0 until 4;w<-0 until 8){t.warp=w;t.sample(c=w%4,vector=true)}
      t.until(retired.size==32 && !d.io.native.busy.peek().litToBoolean)
      measuring=false;d.io.native.vectorCommit.ready.poke(false.B)
      assert((0 until 8).forall(w=>retired.count(_._2==w)==4))
      val accepted=t.admitted.drop(first).toVector
      assert(accepted.size==512 && d.io.returnedGroups.peek().litValue-returned==512)
      assert(t.memory.seen.size==metadataReads && t.groupMemory.seen.size==dataReads,"warm benchmark was not entirely descriptor-free and resident")
      val stable=accepted.slice(32,accepted.size-32);val span=stable.last._1-stable.head._1+1
      val gaps=stable.sliding(2).map(x=>x(1)._1-x(0)._1).toVector
      val total=t.cycle-startCycle
      d.io.maximumGroups.expect(4.U);d.io.sharedBusy.expect(false.B)
      info(s"NATIVE_CACHED_WARM_RATE lanes=16 warps=8 contexts=8 clients=1 credits=4 instructions=32 groups=512 totalCycles=$total stableGroups=${stable.size} stableSpanCycles=$span maxAdmissionGap=${gaps.max} warmDescriptorReads=0 warmDataReads=0 targetII1=${span==stable.size}")
    }
  }

  private val sharedWindowPerformance = new org.scalatest.Tag("opengpu.native.sharedwindow")
  for((shared,burst)<-Seq((false,true),(true,false),(true,true)))it should s"measure four-tap shared window native throughput shared=$shared burst=$burst" taggedAs(sharedWindowPerformance) in {
    simulate(new StreamingNativeTextureHarness(GpuConfig(),1,sharedWindow=shared,streamBurst=burst,poolClients=3)){d=>
      var measuring=false
      val raw=Seq[BigInt](0x11233444L,0x55667788L,0x9aaabbcdL,0xddeeff01L)
      val expected=TextureFilterReference.filter(raw.map(x=>ImageFormatReference.decode("RGBA8",x)),Seq(fp(0.5f),fp(0.5f)),2)
      val retired=scala.collection.mutable.ArrayBuffer.empty[Int]
      val replies=scala.collection.mutable.ArrayBuffer.empty[(Int,BigInt)]
      val t=new Driver(d){
        override def tick(): Unit={
          if(measuring && this.d.io.groupReplyFire.peek().litToBoolean){
            this.d.io.groupReplyUniqueTaps.expect(4.U);this.d.io.groupReplyDataReads.expect(0.U)
            replies+=((cycle,this.d.io.groupReplyTag.peek().litValue))
          }
          if(measuring && this.d.io.native.vectorCommit.valid.peek().litToBoolean && this.d.io.native.vectorCommit.ready.peek().litToBoolean){
            val q=this.d.io.native.vectorCommit.bits;val w=q.writeback.warpId.peek().litValue.toInt
            assert(w>=0 && w<8);q.writesVd.expect(true.B);q.writeback.vd.expect(10.U)
            q.writeback.data.foreach(_.expect(expected(w%4).U));this.d.io.native.responseFault.expect(false.B)
            retired+=w
          }
          super.tick()
        }
      }
      aligned(t,1,swizzle=Seq(0,1,2,3));t.put(levels+17*4,8)
      for(n<-0 until 4)t.put(0x20000+n*4,raw(n))
      t.clear()
      for(w<-0 until 8){t.warp=w;t.parameters(Seq(fp(0.5f),fp(0.5f))++Seq.fill(14)(BigInt(0)),probeCancel=false)}
      t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(expected.head),probeCancel=false)
      val descriptors=t.memory.seen.size;val data=t.groupMemory.seen.size;val first=t.admitted.size;val start=t.cycle
      measuring=true;d.io.native.vectorCommit.ready.poke(true.B)
      for(round<-0 until 4;w<-0 until 8){t.warp=w;t.sample(c=w%4,vector=true)}
      t.until(retired.size==32 && !d.io.native.busy.peek().litToBoolean)
      measuring=false;d.io.native.vectorCommit.ready.poke(false.B)
      val accepted=t.admitted.drop(first).toVector
      assert(accepted.size==512 && replies.size==512 && replies.map(_._2).toVector==accepted.map(_._2))
      assert((0 until 8).forall(w=>retired.count(_==w)==4))
      assert(t.memory.seen.size==descriptors && t.groupMemory.seen.size==data)
      val stable=accepted.slice(32,480);val span=stable.last._1-stable.head._1+1
      val gaps=stable.sliding(2).map(x=>x(1)._1-x(0)._1).toVector
      val latencies=accepted.zip(replies).map{case(a,b)=>b._1-a._1}
      val meets=span==stable.size
      info(s"NATIVE_SHARED_WARM_RATE shared=$shared burst=$burst lanes=16 warps=8 contexts=8 clients=3 activeClients=1 taps=4 instructions=32 groups=512 totalCycles=${t.cycle-start} stableGroups=${stable.size} stableSpanCycles=$span maxAdmissionGap=${gaps.max} minReplyLatency=${latencies.min} maxReplyLatency=${latencies.max} warmDescriptorReads=0 warmDataReads=0 targetII1=$meets")
      if(shared && burst)assert(meets && gaps.max==1,"shared warm four-tap native path did not sustain one sample/cycle")
    }
  }
  it should "drain four-tap shared window borrowed leases and held lane offers while reset stays asserted" taggedAs(sharedWindowPerformance) in {
    simulate(new StreamingNativeTextureHarness(GpuConfig(),1,sharedWindow=true,poolClients=3)){d=>
      var draining=false;val retired=scala.collection.mutable.Set.empty[Int]
      val t=new Driver(d){
        override def tick(): Unit={
          if(draining && this.d.io.native.vectorCommit.valid.peek().litToBoolean && this.d.io.native.vectorCommit.ready.peek().litToBoolean){
            val q=this.d.io.native.vectorCommit.bits;val w=q.writeback.warpId.peek().litValue.toInt
            assert(!retired(w));retired+=w;q.writesVd.expect(false.B);q.writeback.data.foreach(_.expect(0.U))
            this.d.io.native.responseFault.expect(true.B)
          }
          super.tick()
        }
      }
      val raw=Seq[BigInt](0x11233444L,0x55667788L,0x9aaabbcdL,0xddeeff01L)
      val expected=TextureFilterReference.filter(raw.map(x=>ImageFormatReference.decode("RGBA8",x)),Seq(fp(0.5f),fp(0.5f)),2)
      aligned(t,1,swizzle=Seq(0,1,2,3));t.put(levels+17*4,8)
      for(n<-0 until 4)t.put(0x20000+n*4,raw(n))
      t.clear()
      for(w<-0 until 8){t.warp=w;t.parameters(Seq(fp(0.5f),fp(0.5f))++Seq.fill(14)(BigInt(0)),probeCancel=false)}
      t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(expected.head),probeCancel=false)
      val initial=d.io.groupRequests.peek().litValue
      d.io.holdGroupReplies.poke(true.B)
      for(w<-0 until 8){t.warp=w;t.sample(vector=true)}
      t.until(d.io.groupRequests.peek().litValue==initial+120)
      for(_<-0 until 16)t.tick()
      d.io.maximumGroups.expect(120.U)
      d.io.globalCancel.poke(true.B);for(_<-0 until 8)t.tick()
      d.io.holdGroupReplies.poke(false.B);d.io.native.vectorCommit.ready.poke(true.B);draining=true
      t.until(retired.size==8 && !d.io.native.busy.peek().litToBoolean && !d.io.sharedBusy.peek().litToBoolean)
      draining=false;d.io.native.vectorCommit.ready.poke(false.B);d.io.globalCancel.poke(false.B);t.tick()
      t.warp=0;t.sample(vector=true);t.retire(true,false,Seq.fill(16)(expected.head),probeCancel=false)
      info("NATIVE_SHARED_NATIVE_RESET warps8=true publicBorrowedLeases120=true otherClientsReserve4Each=true heldLaneOffersDrained=true resetHeldThroughAllRetirements=true atomicVectorFaults8=true healthyReuse=true")
    }
  }

}
