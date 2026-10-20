package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureSampleExecuteUnitSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private val all=BigInt(15);private val previous=(0 until 4).map(n=>BigInt(0xabc00000L+n))
  private def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  private def instruction(vector: Boolean,param: Boolean,field: Int,integer: Boolean=false): BigInt={
    val shift=if(vector)26 else 25;val vm=if(vector)BigInt(1)<<25 else BigInt(0)
    (BigInt(if(param)3 else if(integer)5 else 4)<<shift)|vm|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(field)<<12)|
      (if(param)BigInt(0)else BigInt(10)<<7)|(if(vector)0x2b else 0x0b)
  }
  private class Driver(val d: NativeShaderTextureUnit) {
    val lanes=d.io.vectorIn.bits.issued.vs1Data.length
    private val all=(BigInt(1)<<lanes)-1;private val previous=(0 until lanes).map(n=>BigInt(0xabc00000L+n))
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>assert(!r.write && r.tag<2 && r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
    var warp=1;var checks=0;var before=0
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<30000){tick();n+=1};assert(p,s"floating execution stalled: ${memory.seen.takeRight(8)}")}
    def context(): Unit={d.io.resources.version.poke(1.U);d.io.resources.address.poke(root.U);identity(d.io.identity)}
    def put(a: BigInt,v: BigInt): Unit=memory.putWord(a,v)
    def byte(a: BigInt,v: Int): Unit={memory.bytes(a)=v}
    def clear(): Unit={d.io.busy.expect(false.B);d.io.clearParameters.poke(true.B);tick();d.io.clearParameters.poke(false.B)}
    def issue(vector: Boolean,param: Boolean,field: Int,left: Seq[BigInt],right: Seq[BigInt],mask: BigInt=all,integer: Boolean=false): Unit={
      context();before=memory.seen.size
      if(vector){zero(d.io.vectorIn.bits);val q=d.io.vectorIn.bits
        q.issued.decode.instruction.poke(instruction(true,param,field,integer).U);q.issued.decode.warpId.poke(warp.U)
        q.issued.decode.pc.poke(0x200.U);q.issued.decode.activeMask.poke(all.U);q.executionMask.poke(mask.U)
        q.issued.vs1Data.zip(left).foreach{case(a,b)=>a.poke(b.U)};q.issued.vs2Data.zip(right).foreach{case(a,b)=>a.poke(b.U)}
        q.issued.oldVdData.zip(previous).foreach{case(a,b)=>a.poke(b.U)}
        d.io.vectorIn.valid.poke(true.B);until(d.io.vectorIn.ready.peek().litToBoolean);tick();d.io.vectorIn.valid.poke(false.B);zero(q)
      }else{zero(d.io.in.bits);val q=d.io.in.bits
        q.decode.instruction.poke(instruction(false,param,field,integer).U);q.decode.warpId.poke(warp.U);q.decode.pc.poke(0x200.U);q.decode.activeMask.poke(all.U)
        q.rs1Data.poke(left.head.U);q.rs2Data.poke(right.head.U)
        d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
      }
      zero(d.io.resources);zero(d.io.identity)
    }
    def retire(vector: Boolean,param: Boolean,expected: Seq[BigInt],fault: Boolean=false): Unit={
      until(if(vector)d.io.vectorCommit.valid.peek().litToBoolean else d.io.commit.valid.peek().litToBoolean)
      if(vector){val q=d.io.vectorCommit.bits;q.writeback.warpId.expect(warp.U);q.writeback.vd.expect((if(param)0 else 10).U)
        q.writesVd.expect((!param && !fault).B);q.writesScalar.expect(param.B);q.scalarData.expect(0.U)
        q.writeback.data.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      }else{val q=d.io.commit.bits;q.warpId.expect(warp.U);q.rd.expect((if(param)0 else 10).U);q.writeRd.expect((param || !fault).B);q.data.expect(expected.head.U)}
      d.io.responseFault.expect(fault.B);assert(memory.pending.isEmpty && memory.reply.isEmpty)
      val held=if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue
      d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
      for(_<-0 until 3){tick();assert((if(vector)d.io.vectorCommit.bits.peek().litValue else d.io.commit.bits.peek().litValue)==held)}
      if(vector)d.io.vectorCommit.ready.poke(true.B)else d.io.commit.ready.poke(true.B)
      tick();d.io.vectorCommit.ready.poke(false.B);d.io.commit.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B)
      d.io.busy.expect(false.B);checks+=1
    }
    def parameters(words: Seq[BigInt],vector: Boolean=true,mask: BigInt=all,pairs: Seq[Int]=0 until 8): Unit={
      for(pair<-pairs){val start=memory.seen.size;issue(vector,true,pair,Seq.fill(lanes)(words(2*pair)),Seq.fill(lanes)(words(2*pair+1)),mask)
        retire(vector,true,Seq.fill(lanes)(BigInt(0)));assert(memory.seen.size==start,"parameter writes must remain on chip")}
    }
    def sample(c: Int=0,vector: Boolean=false,mask: BigInt=all,units: Seq[BigInt]=Seq.fill(lanes)(BigInt(0)),control: Int=1): Unit=
      issue(vector,false,c,units,Seq.fill(lanes)(BigInt(control)),mask)
  }
  for(stage<-0 to 1)it should s"capture scalar and vector operands on chip and sample every format through native credits stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      var samples=0
      for(format<-0 until 64){t.warp=format%2
        val v=install(t.put,t.byte,0,format,0,stage,swizzle=Seq(3,2,5,0));val width=if(format>=54)7 else 2;val height=if(format>=54)5 else 2
        val words=Seq(fp(1.5f/width),fp(1.5f/height))++Seq.fill(14)(BigInt(0))
        t.parameters(words,vector=format%2==0)
        for(c<-0 until 4){t.sample(c);t.retire(false,false,Seq(v.expected(c)));samples+=1
          t.sample(c,vector=true,mask=5);t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)v.expected(c)else previous(l)));samples+=1}
      }
      assert(samples==512)
      info(s"NATIVE_FLOAT_OPERANDS stage=$stage formats64=true components4=true samples=$samples scalarVectorParameterWrites=true scalarVectorSamples=true maskedInactiveOldVd=true onChipPairs8=true noParameterMemoryReads=true warpBanks2=true taskReset=true physicalCredits2=true immutableRetirement=true")
    }
  }
  for(lanes<-Seq(8,16))it should s"derive helper groups and high active lanes from the shader lane count lanes=$lanes" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=lanes,warps=2),stage=1)){d=>val t=new Driver(d);roots(t.put,stage=1);t.clear()
      val views=(0 until lanes/4).map{g=>val v=install(t.put,t.byte,g,3,0,1,raw=BigInt(0x12345679L)+g*BigInt(0x11111111L))
        t.put(samplers+g*64+7*4,1);v}
      val coordinates=(0 until lanes).map{l=>val step=if((l/4)%2==0)0.75f else 0.25f
        (fp(if(l%2==0)0.5f-step else 0.5f),fp(if(l%4<2)0.5f-step else 0.5f))}
      t.issue(true,true,0,coordinates.map(_._1),coordinates.map(_._2),(BigInt(1)<<lanes)-1)
      t.retire(true,true,Seq.fill(lanes)(BigInt(0)))
      t.parameters(Seq.fill(16)(BigInt(0)),pairs=1 until 8)
      val selected=(0 until lanes/4).foldLeft(BigInt(0))((m,g)=>m.setBit(g*4+3))
      t.sample(vector=true,mask=selected,units=(0 until lanes).map(l=>BigInt(l/4)),control=0)
      val expected=(0 until lanes).map{l=>if(!selected.testBit(l))BigInt(0xabc00000L+l)else{
        val raw=views(l/4).expected(0)
        if((l/4)%2==0)fp(java.lang.Float.intBitsToFloat(raw.toInt)*0.25f)else raw
      }}
      t.retire(true,false,expected)
      t.parameters(Seq[BigInt](1,1)++Seq.fill(14)(BigInt(0)))
      t.issue(true,false,0,(0 until lanes).map(l=>BigInt(l/4)),Seq.fill(lanes)(BigInt(0)),mask=selected,integer=true)
      t.retire(true,false,(0 until lanes).map(l=>if(selected.testBit(l))views(l/4).expected(0)else BigInt(0xabc00000L+l)))
      info(s"NATIVE_INTEGER_LANE_GROUPS lanes=$lanes highMaskBits=true perQuadBindings=true inactiveOldVd=true")
      info(s"NATIVE_FLOAT_LANE_GROUPS lanes=$lanes completeQuads=${lanes/4} highMaskBits=true perQuadBindings=true independentGradientMinMag=true inactiveOldVd=true logicalVLENFromGpuConfig=true")
    }
  }
  it should "retain explicit sampling for one shader lane and reject unavailable implicit helper groups before reads" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=1,warps=2),stage=1)){d=>val t=new Driver(d);roots(t.put,stage=1);val v=install(t.put,t.byte,0,3,0,1);t.clear()
      t.parameters(Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0)))
      t.sample();t.retire(false,false,Seq(v.expected(0)))
      t.sample(vector=true);t.retire(true,false,Seq(v.expected(0)))
      t.sample(vector=true,control=0);t.retire(true,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(Seq[BigInt](1,1)++Seq.fill(14)(BigInt(0)))
      for(vector<-Seq(false,true)){
        t.issue(vector,false,0,Seq(BigInt(0)),Seq(BigInt(0)),integer=true);t.retire(vector,false,Seq(v.expected(0)))}
      info("NATIVE_INTEGER_SINGLE_LANE scalarVector=true capturedCoordinates=true noHelperRequirement=true")
      info("NATIVE_FLOAT_SINGLE_LANE explicitScalarVector=true implicitUnavailableBeforeReads=true noSecondVLEN=true")
    }
  }
  for(stage<-0 to 1)it should s"retain nonzero lambda gradient layer comparison offset and helper operand semantics stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      def fresh(format: Int=3,target: Int=0,raw: BigInt=BigInt("fedcba98765432100123456789abcdef",16)): View={
        t.memory.bytes.keysIterator.filter(a=>a>=0x20000 && a<0x21000).toVector.foreach(t.memory.bytes.remove)
        install(t.put,t.byte,0,format,target,stage,raw=raw)
      }
      val base=Seq(fp(0.5f),fp(0.5f))++Seq.fill(14)(BigInt(0))
      var view=fresh()
      val scalar=java.lang.Float.intBitsToFloat(view.expected(0).toInt)
      val quarter=fp(scalar*0.25f)
      t.put(samplers+7*4,1) // min LINEAR, mag NEAREST: lambda sign changes the sampled value.
      t.parameters(base.updated(5,fp(1f)));t.sample();t.retire(false,false,Seq(quarter))
      for(word<-7 to 12){
        t.parameters(base.updated(word,fp(0.75f)));t.sample(control=2)
        t.retire(false,false,Seq(if(word==9 || word==12)view.expected(0)else quarter))
      }
      def helper(bias: Float,step: Float): Unit={
        val lo=fp(0.5f-step);val hi=fp(0.5f)
        t.issue(true,true,0,Seq(lo,hi,lo,hi),Seq(lo,lo,hi,hi));t.retire(true,true,Seq.fill(4)(BigInt(0)))
        t.parameters(base.updated(6,fp(bias)),pairs=1 until 8)
        t.sample(vector=true,mask=8,control=0)
      }
      helper(0f,0.75f);t.retire(true,false,previous.take(3):+(if(stage==1)quarter else view.expected(0)))
      if(stage==1){helper(0f,0.25f);t.retire(true,false,previous.take(3):+view.expected(0))
        helper(2f,0.25f);t.retire(true,false,previous.take(3):+quarter)}
      for(target<-0 to 3){
        view=fresh(target=target)
        val coords=if(target==2)Seq(fp(-0.5f),fp(-0.5f),fp(-1f))else Seq.fill(3)(fp(0.75f))
        val words=coords++Seq(if(target==1)fp(2f)else BigInt(0))++Seq.fill(12)(BigInt(0))
        t.parameters(words);t.sample();t.retire(false,false,Seq(view.expected(0)))
      }
      view=fresh(target=3);t.put(samplers+7*4,1)
      val volume=Seq.fill(3)(fp(0.5f))++Seq.fill(13)(BigInt(0))
      for(word<-Seq(9,12)){t.parameters(volume.updated(word,fp(0.75f)));t.sample(control=2)
        t.retire(false,false,Seq(fp(java.lang.Float.intBitsToFloat(view.expected(0).toInt)*0.125f)))}
      view=fresh()
      t.parameters((Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0))).updated(13,BigInt(15)))
      t.sample();t.retire(false,false,Seq(BigInt(0))) // signed -1 X offset selects the unpopulated adjacent texel.
      fresh(format=49,raw=BigInt(0x7fff));t.put(samplers+14*4,1)
      for(ref<-Seq(0f,1f)){t.parameters((Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0))).updated(4,fp(ref)))
        t.sample(control=5);t.retire(false,false,Seq(if(ref==0f)fp(1f)else BigInt(0)))}
      info(s"NATIVE_FLOAT_OPERAND_SEMANTICS stage=$stage explicitLambdaSelectsMinFilter=true allGradientWords6=true unused2DZIgnored=true volumeZGradients2=true originalHelpers=true fragmentBiasBeforeClamps=true arrayLayer2=true cubeFace5=true signedIntegerOffset=true shadowReference=true")
    }
  }
  for(stage<-0 to 1)it should s"reject stale incomplete and late-lane operands while draining native sample cancellation stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);val v=install(t.put,t.byte,0,3,0,stage)
      val words=Seq(fp(0.75f),fp(0.75f))++Seq.fill(14)(BigInt(0))
      t.clear();t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words,pairs=Seq(0,7));t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words);t.warp=0;t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.warp=1;t.sample();t.retire(false,false,Seq(v.expected(0)))
      t.parameters(words,pairs=Seq(0));t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words,pairs=1 until 8);t.sample();t.retire(false,false,Seq(v.expected(0)))
      t.clear();t.parameters(words,mask=13);t.sample(vector=true,mask=3)
      t.retire(true,false,Seq.fill(4)(BigInt(0)),fault=true);assert(t.memory.seen.size>t.before,"first active lane must have completed before incomplete lane faults")
      t.sample(vector=true,mask=5);t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)v.expected(0)else previous(l)))
      t.sample(vector=true,mask=0);t.retire(true,false,previous);assert(t.memory.seen.size==t.before)
      t.sample(vector=true,mask=5,control=0)
      t.retire(true,false,if(stage==1)Seq.fill(4)(BigInt(0))else(0 until 4).map(l=>if(l==0 || l==2)v.expected(0)else previous(l)),fault=stage==1)
      if(stage==1)assert(t.memory.seen.size==t.before,"missing helpers must fail before root reads")
      t.parameters(words);t.sample(vector=true,control=0);t.retire(true,false,Seq.fill(4)(v.expected(0)))
      t.clear();t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words);t.sample(control=8);t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words.updated(13,BigInt(0x1000)));t.sample();t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before)
      t.parameters(words);t.memory.faultAt=Set(v.address& ~BigInt(63));t.sample(vector=true)
      t.retire(true,false,Seq.fill(4)(BigInt(0)),fault=true);t.memory.faultAt=Set.empty
      var cancelled=0;var held=0
      for(cut<-Seq(0,1,5,10,20,40,80,160,320,640,1280,2560)){
        t.sample(vector=true);for(_<-0 until cut)t.tick();val complete=d.io.vectorCommit.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.retire(true,false,if(complete)Seq.fill(4)(v.expected(0))else Seq.fill(4)(BigInt(0)),fault= !complete)
        if(complete)held+=1 else cancelled+=1
        t.sample(vector=true);t.retire(true,false,Seq.fill(4)(v.expected(0)))
      }
      assert(cancelled>0 && held>0)
      info(s"NATIVE_FLOAT_OPERAND_LIFETIME stage=$stage incompleteBeforeReads=true warpIsolation=true pair0RestartsTuple=true taskResetSameIdentity=true zeroMaskNoReads=true lateLaneFaultAtomic=true missingHelpers=true reservedControlsRejected=true cancellationCuts12=true cancelled=$cancelled held=$held healthyReuse=true")
    }
  }
  for(stage<-0 to 1)it should s"fetch integer texels from captured operands across every native format stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      var samples=0
      for(format<-0 until 64){t.warp=format%2
        val target=if(format>=54 || ImageFormatReference.format(ImageFormatReference.names(format)).depth)0 else format%4
        val v=install(t.put,t.byte,0,format,target,stage,swizzle=Seq(3,2,5,0))
        val words=Seq[BigInt](1,1,if(target==3)1 else 0,if(target==1)2 else 0,if(target==2)5 else 0,0)++Seq.fill(10)(BigInt(0))
        t.parameters(words,vector=format%2==0)
        for(c<-0 until 4){
          t.issue(false,false,c,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true);t.retire(false,false,Seq(v.expected(c)));samples+=1
          t.issue(true,false,c,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),mask=5,integer=true)
          t.retire(true,false,(0 until 4).map(l=>if(l==0 || l==2)v.expected(c)else previous(l)));samples+=1
        }
      }
      assert(samples==512 && !t.memory.seen.exists(r=>r.address>=packet && r.address<packet+4096))
      info(s"NATIVE_INTEGER_OPERANDS stage=$stage samples=$samples formats64=true components4=true scalarVector=true targets4=true rawCoordinates32=true onChipParameters=true noCoordinatePacketReads=true maskedInactiveOldVd=true warpBanks2=true")
    }
  }
  for(stage<-0 to 1)it should s"apply checked signed integer offsets and retain captured fetch bounds and task lifetime stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      val format=ImageFormatReference.names.indexOf("RGBA32UI");var view=install(t.put,t.byte,0,format,0,stage)
      val base=Seq[BigInt](1,1,0,0,0,0)++Seq.fill(10)(BigInt(0))
      def fetch(words: Seq[BigInt],expected: BigInt,fault: Boolean=false,control: Int=0): Unit={
        t.parameters(words);t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(control)),integer=true);t.retire(false,false,Seq(expected),fault)
      }
      fetch(base.updated(0,BigInt(0)).updated(13,BigInt(1)),view.expected(0));fetch(base.updated(0,BigInt(2)).updated(13,BigInt(15)),view.expected(0))
      fetch(base.updated(0,BigInt(0xffffffffL)).updated(13,BigInt(2)),view.expected(0))
      fetch(base.updated(0,BigInt(0)).updated(13,BigInt(15)),0,fault=true);fetch(base.updated(0,BigInt(0x7fffffffL)).updated(13,BigInt(7)),0,fault=true)
      fetch(base.updated(5,BigInt(0x10000000L)),0,fault=true);fetch(base.updated(4,BigInt(0x10000005L)),0,fault=true)
      fetch(base.updated(13,BigInt(0x1000)),0,fault=true);assert(t.memory.seen.size==t.before)
      for(word<-6 to 12){fetch(base.updated(word,1),0,fault=true);assert(t.memory.seen.size==t.before)}
      fetch(base,0,fault=true,control=1);assert(t.memory.seen.size==t.before)
      for(target<-0 to 3){view=install(t.put,t.byte,0,format,target,stage)
        val words=base.updated(3,BigInt(if(target==1)2 else 0)).updated(4,BigInt(if(target==2)5 else 0))
        if(target==3)fetch(words.updated(13,BigInt(256)),view.expected(0))else fetch(words.updated(13,BigInt(256)),0,fault=true)
        if(target==2)fetch(words.updated(13,BigInt(1)),0,fault=true)
      }
      view=install(t.put,t.byte,0,format,0,stage);t.parameters(base)
      t.issue(true,false,0,Seq(BigInt(0),BigInt(0),BigInt(0),BigInt(0xffffffffL)),Seq.fill(4)(BigInt(0)),integer=true)
      t.retire(true,false,Seq.fill(4)(BigInt(0)),fault=true)
      t.issue(true,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),mask=0,integer=true);t.retire(true,false,previous);assert(t.memory.seen.size==t.before)
      t.clear();t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true)
      t.retire(false,false,Seq(BigInt(0)),fault=true);assert(t.memory.seen.size==t.before);fetch(base,view.expected(0))
      info(s"NATIVE_INTEGER_BOUNDS stage=$stage signedOffsetBeforeBounds=true negativeCoordinatePlusOffset=true highCoordinateAndLevelRejected=true fullFaceWidth=true unusedOperandWordsRejected=true reservedControls=true targetSpecificOffsets=true lateLaneFaultAtomic=true zeroMaskNoReads=true taskReset=true healthyReuse=true")
    }
  }
  for(stage<-0 to 1)it should s"drain captured integer fetch cancellation and memory faults before reuse stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      val view=install(t.put,t.byte,0,3,0,stage);t.parameters(Seq[BigInt](1,1)++Seq.fill(14)(BigInt(0)))
      var cancelled=0;var held=0
      for(cut<-Seq(0,1,3,7,15,25,40,60,90,140,700,1200)){
        t.issue(true,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true)
        for(_<-0 until cut if !d.io.vectorCommit.valid.peek().litToBoolean)t.tick()
        val complete=d.io.vectorCommit.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.retire(true,false,if(complete)Seq.fill(4)(view.expected(0))else Seq.fill(4)(BigInt(0)),fault= !complete)
        if(complete)held+=1 else cancelled+=1
        t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true);t.retire(false,false,Seq(view.expected(0)))
      }
      t.memory.faultAt=Set(view.address& ~BigInt(63))
      t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true);t.retire(false,false,Seq(BigInt(0)),fault=true)
      t.memory.faultAt=Set.empty
      t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true);t.retire(false,false,Seq(view.expected(0)))
      info(s"NATIVE_INTEGER_DRAIN stage=$stage cuts12=true cancelled=$cancelled held=$held memoryFault=true immutableHeldRetirement=true physicalCredits2=true healthyReuse=true")
    }
  }

  for(stage<-0 to 1)it should s"add the captured image base level to shader integer LOD without wrapping stage=$stage" in {
    simulate(new NativeShaderTextureUnit(GpuConfig(lanes=4,warps=2),stage)){d=>val t=new Driver(d);roots(t.put,stage);t.clear()
      install(t.put,t.byte,0,3,0,stage)
      t.put(images+10*4,15);t.put(images+11*4,8);t.put(images+12*4,8);t.put(images+20*4,levels+16*128)
      t.put(samplers+7*4,2) // Mipmapped nearest keeps every selected defined level complete.
      for(level<-0 until 16){val size=(8>>level).max(1);val address=BigInt(0x20000+level*256)
        NativeTextureFetchTestData.words(t.put,levels+level*128,Seq[BigInt](BigInt("47584c01",16),128,1,7,11,13,
          level,0,level,0x20000,0x21000,0,address,size,size,1,1,size*4,0,0,0,0,0,0,4),32)
        for(y<-0 until size;x<-0 until size)t.put(address+(y*size+x)*4,BigInt(0xff000000L+level+1))
      }
      var checks=0
      for(base<-1 until 16){t.put(images+9*4,base)
        for(lod<-0 to (3-base).max(0)){
          t.parameters(Seq.fill(16)(BigInt(0)).updated(5,BigInt(lod)))
          for(vector<-Seq(false,true)){
            t.issue(vector,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true)
            val expected=ImageFormatReference.decode("RGBA8",BigInt(0xff000000L+base+lod+1))(0)
            withClue(s"relative LOD stage=$stage base=$base lod=$lod vector=$vector reads=${t.memory.seen.takeRight(10)} "){t.retire(vector,false,Seq.fill(4)(expected))};checks+=1
          }
        }
        if(base<=2)for(min<-0 to 1){
          t.put(samplers+7*4,min);t.parameters(Seq.fill(16)(BigInt(0)).updated(5,BigInt(1)))
          val expected=ImageFormatReference.decode("RGBA8",BigInt(0xff000000L+base+2))(0)
          def query(bad: Boolean): Unit={
            t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true)
            t.retire(false,false,Seq(if(bad)BigInt(0)else expected),fault=bad);checks+=1
          }
          query(false)
          t.put(levels+base*128+13*4,0);query(true)
          val texel=BigInt(0x20000+(base+1)*256)
          assert(!t.memory.seen.drop(t.before).exists(_.address==texel),"failed base validation issued a selected texel read")
          t.put(levels+base*128+13*4,(8>>base).max(1));query(false)
        }
        t.put(samplers+7*4,2)
        for(lod<-Seq(BigInt(16-base),BigInt(0xffffffffL),BigInt(0x10000000L))){
          t.parameters(Seq.fill(16)(BigInt(0)).updated(5,lod))
          t.issue(false,false,0,Seq.fill(4)(BigInt(0)),Seq.fill(4)(BigInt(0)),integer=true)
          t.retire(false,false,Seq(BigInt(0)),fault=true);checks+=1
        }
      }
      info(s"NATIVE_INTEGER_RELATIVE_LOD stage=$stage checks=$checks baseLevels15=true relativeLOD0to2=true exactDifferentMipTexels=true fullWidthInvalidAndNegativeRejected=true noLevelWrap=true scalarVector=true nonmipBaseValidationAndSelectedView=true")
    }
  }

}
