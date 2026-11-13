package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class StreamingColorCancellationProbe(ports: Int) extends StreamingTypedTileColorLanes(ports) {
  val arithmeticOffered=IO(Output(Bool()));arithmeticOffered:=blendOffered
  val arithmeticBusy=IO(Output(Bool()));arithmeticBusy:=blend.io.busy
  val arithmeticReply=IO(Output(Bool()));arithmeticReply:=blend.io.out.valid
}

private class StreamingAdvancedColorProbe(ports: Int) extends StreamingTypedTileColorLanes(ports,true) {
  val advancedOfferObserved=IO(Output(Bool()));advancedOfferObserved:=advancedOffered
  val advancedBusyObserved=IO(Output(Bool()));advancedBusyObserved:=advancedBusy
  val advancedReplyObserved=IO(Output(Bool()));advancedReplyObserved:=advancedReply
  val ordinaryReplyObserved=IO(Output(Bool()));ordinaryReplyObserved:=blend.io.out.valid
  val resultCollisionObserved=IO(Output(Bool()));resultCollisionObserved:=resultCollision
  val ordinaryBlockedObserved=IO(Output(Bool()));ordinaryBlockedObserved:=ordinaryResultBlocked
  val advancedBlockedObserved=IO(Output(Bool()));advancedBlockedObserved:=advancedResultBlocked
}

class StreamingTypedTileColorLanesSpec extends AnyFlatSpec {
  import TypedColorReference.{names,format,encode,fp}
  private val full=(BigInt(1)<<64)-1
  private val initial=BigInt("3f0000003f000000",16)
  private case class Reply(at: Int,tag: BigInt,data: BigInt,write: Boolean,mask: BigInt,fault: Boolean,address: Int=0)
  private class Driver(d: StreamingTypedTileColorLanes,ports: Int){
    private val requests=d.io.lanes.map(_.request).toVector++d.io.extra.toVector.flatMap(_.request)
    private val responses=d.io.lanes.map(_.response).toVector++d.io.extra.toVector.flatMap(_.response)
    for(lane<-d.io.lanes){lane.start.poke(false.B);lane.cancel.poke(false.B)
      lane.descriptor.poke(0.U.asTypeOf(lane.descriptor));lane.input.poke(0.U.asTypeOf(lane.input))}
    requests.foreach(_.ready.poke(false.B));responses.foreach(r=>{r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))})
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var firstOrdinaryResult= -1;var advancedResults=Vector.empty[Int]
    var collisions=0;var ordinaryBlocked=0;var advancedBlocked=0
    def source(kind: Int,lane: Int,color: Int): Vector[BigInt]=kind match{
      case 1=>Vector(BigInt(257+lane),BigInt(65539+color),BigInt("fedcba98",16),BigInt(3))
      case 2=>Vector(BigInt("fffffffd",16),BigInt(32769+lane),BigInt("80000000",16),BigInt(2))
      case _=>Vector(fp(0.1+lane*0.1),fp(0.7),fp(0.3+color*0.1),fp(0.5))
    }
    def run(fs: Vector[String],mode: Int=2,blend: Boolean=false,mask: Int=15,
        cancelHeldWrite: Boolean=false,faultWrite: Boolean=false,
        cancelWhen: Option[()=>Boolean]=None,advanced: Option[Vector[Int]]=None,ordinaryLastReadHoldUntil: Int=0): Int={
      firstOrdinaryResult= -1;advancedResults=Vector.empty;collisions=0;ordinaryBlocked=0;advancedBlocked=0
      var advancedWasValid=false
      val memory=mutable.Map.empty[Int,BigInt].withDefaultValue(initial)
      val expected=mutable.Map.empty[Int,BigInt].withDefaultValue(initial)
      val allowed=mutable.Map.empty[Int,BigInt].withDefaultValue(BigInt(0))
      val committed=mutable.Map.empty[Int,BigInt].withDefaultValue(BigInt(0))
      def colorSource(kind: Int,lane: Int,color: Int): Vector[BigInt]=
        if(advanced.nonEmpty && kind==0)Vector(fp(0.125+lane*0.0625),fp(0.25),fp(0.375),fp(0.75))
        else source(kind,lane,color)
      val samples=if(advanced.nonEmpty)Vector.fill(4)((1<<(1<<mode))-1)else if(mode==0)Vector.fill(4)(1)else if(mode==1)Vector(3,1,2,3)else Vector(15,7,5,9)
      val state=TypedBlendReference.State(enable=blend)
      val expectedWrites=Array.fill(4)(0);val expectedBits=Array.fill(4)(0);val expectedLocations=Array.fill(4)(0)
      var expectedReads=0
      for(lane<-0 until 4;color<-0 until 4;sample<-0 until (1<<mode) if (samples(lane)&(1<<sample))!=0){
        val f=format(fs(color));val values=colorSource(f.kind,lane,color)
        val address=color*4096+((((2+lane/2)*8+2+lane%2)<<mode)+sample)*f.bytes
        val old=(0 until f.bytes).foldLeft(BigInt(0))((v,b)=>v|(((initial>>(8*((address+b)%8)))&255)<<(8*b)))
        val blended=advanced match {
          case Some(equations) if equations(color)>=0=>
            val src=values.map(x=>TypedColorReference.value(x).toFloat)
            val dst=TypedBlendReference.decode(fs(color),old).map(x=>TypedColorReference.value(x).toFloat)
            if(equations(color)<11)TypedAdvancedColorBlendReference.expected(src,dst,equations(color))
            else TypedAdvancedHslBlendReference.expected(src,dst,equations(color)-11)
          case _=>TypedBlendReference.blend(fs(color),values,old,state)
        }
        val (value,bits)=encode(fs(color),blended,mask)
        if(bits!=0){
          expectedLocations(lane)+=1;expectedBits(lane)+=bits.bitCount
          val words=mutable.Set.empty[Int]
          for(b<-0 until f.bytes){val a=address+b;val shift=8*(a%8);val m=((bits>>(8*b))&255)<<shift
            if(m!=0){val word=a/8;words+=word;allowed(word)|=m
              expected(word)=(expected(word)&(full^m))|(((value>>(8*b))&255)<<shift&m)}}
          expectedWrites(lane)+=words.size
          if(blend && f.kind==0)expectedReads+=(address%8+f.bytes+7)/8
        }
      }
      for(lane<-d.io.lanes){
        lane.cancel.poke(false.B);lane.descriptor.poke(0.U.asTypeOf(lane.descriptor));lane.input.poke(0.U.asTypeOf(lane.input))
        val desc=lane.descriptor;val in=lane.input
        desc.version.poke(1.U);desc.originX.poke((-10).S);desc.originY.poke((-8).S)
        desc.tileLog2.poke(3.U);desc.tileWidth.poke(8.U);desc.tileHeight.poke(8.U);desc.sampleMode.poke(mode.U)
        in.quad.sampleMode.poke(mode.U);in.quad.colorSlots.poke(4.U);TypedBlendReference.poke(in.blend,state)
        advanced.foreach{equations=>
          in.blend.enable.poke(false.B);in.blend.version.poke(TypedBlendState.advancedIndexedVersion.U)
          in.blend.srcRGB.poke(0.U);in.blend.dstRGB.poke(0.U);in.blend.srcAlpha.poke(0.U);in.blend.dstAlpha.poke(0.U)
          in.blend.equationRGB.poke(0.U);in.blend.equationAlpha.poke(0.U)
          for(c<-0 until 4){
            val ordinary=BigInt(if(state.enable)1 else 0)|(BigInt(state.src)<<1)|(BigInt(state.dst)<<5)|
              (BigInt(state.sa)<<9)|(BigInt(state.da)<<13)|(BigInt(state.eq)<<17)|(BigInt(state.ea)<<20)
            val control=if(equations(c)<0)ordinary else BigInt(1)|(BigInt(1)<<27)|(BigInt(equations(c))<<28)
            in.blend.buffers(c).poke((control|(BigInt(15)<<23)).U)
          }
        }
        for(c<-0 until 4){val f=format(fs(c));val view=desc.colors(c)
          view.enabled.poke(true.B);view.format.poke(TypedColorFormat.id(fs(c)).U)
          view.offset.poke((c*4096).U);view.channelMask.poke(mask.U);in.quad.colorTypes(c).poke(f.kind.U)}
        for(l<-0 until 4){val pixel=in.quad.lanes(l)
          pixel.x.poke((-8+l%2).S);pixel.y.poke((-6+l/2).S)
          pixel.coverageMask.poke(samples(l).U);pixel.colorMask.poke(15.U);in.samples(l).poke(samples(l).U)
          for(c<-0 until 4)pixel.colors(c).zip(colorSource(format(fs(c)).kind,l,c)).foreach{case(v,x)=>v.poke(x.U)}}
        lane.start.poke(true.B)
      }
      requests.foreach(_.ready.poke(false.B));responses.foreach(_.valid.poke(false.B))
      d.clock.step();d.io.lanes.foreach(_.start.poke(false.B))
      val pending=Array.fill[Option[Reply]](ports)(None);val held=Array.fill[Option[BigInt]](ports)(None)
      val ackWrites=Array.fill(4)(0);val ackBits=Array.fill(4)(0)
      var cycle=0;var reads=0;var cancelledAt= -1;var cancelWrites=0;var offeredCancelWrites=0
      var failed=false;var failedOffset=BigInt(0)
      var cancelledLocations=Option.empty[Vector[BigInt]]
      // Up to 64 advanced owners, each below 128 arithmetic steps and 64
      // clocks/step, plus codec and word handshakes. Ordinary limits stay put.
      val limit=if(advanced.nonEmpty)800000 else 10000
      while(!d.io.lanes.head.done.peek().litToBoolean && cycle<limit){
        d match {
          case p: StreamingAdvancedColorProbe=>
            if(p.ordinaryReplyObserved.peek().litToBoolean && firstOrdinaryResult<0)firstOrdinaryResult=cycle
            val av=p.advancedReplyObserved.peek().litToBoolean
            if(av && !advancedWasValid)advancedResults :+=cycle
            advancedWasValid=av
            if(p.resultCollisionObserved.peek().litToBoolean)collisions+=1
            if(p.ordinaryBlockedObserved.peek().litToBoolean)ordinaryBlocked+=1
            if(p.advancedBlockedObserved.peek().litToBoolean)advancedBlocked+=1
          case _=>()
        }
        if(cancelledAt<0 && ((cancelHeldWrite && requests.exists(r=>r.valid.peek().litToBoolean && r.bits.write.peek().litToBoolean)) ||
            cancelWhen.exists(_()))){
          cancelledAt=cycle
          cancelledLocations=Some(d.io.lanes.map(_.locations.peek().litValue).toVector)
          offeredCancelWrites=requests.count(r=>r.valid.peek().litToBoolean && r.bits.write.peek().litToBoolean)
        }
        val blocked=cancelledAt>=0 && cycle-cancelledAt<7
        d.io.lanes.foreach(_.cancel.poke((cycle==cancelledAt).B))
        for(p<-0 until ports){
          requests(p).ready.poke((!blocked && (cycle+p)%4!=0).B)
          val response=responses(p);val reply=pending(p).filter(r=>r.at<=cycle &&
            !(p>=4 && !r.write && (r.address&1)==1 && cycle<ordinaryLastReadHoldUntil))
          response.valid.poke(reply.nonEmpty.B)
          reply.foreach{r=>response.bits.tag.poke(r.tag.U);response.bits.data.poke(r.data.U);response.bits.fault.poke(r.fault.B)}
          held(p).foreach(v=>{requests(p).valid.expect(true.B);assert(requests(p).bits.peek().litValue==v,"held generic word offer changed")})
          if(reply.nonEmpty && response.ready.peek().litToBoolean){val r=reply.get
            if(r.write && !r.fault){ackWrites(p%4)+=1;ackBits(p%4)+=r.mask.bitCount};pending(p)=None}
          val request=requests(p)
          if(request.valid.peek().litToBoolean){
            if(request.ready.peek().litToBoolean){
              assert(pending(p).isEmpty,"generic word port reused an unacknowledged owner")
              val r=request.bits;val address=r.address.peek().litValue.toInt;val write=r.write.peek().litToBoolean
              val bits=r.mask.peek().litValue;val fail=faultWrite && !failed && p==4 && write
              if(fail){failed=true;failedOffset=address*8}
              if(write){
                assert(bits!=0 && (bits& ~allowed(address))==0,"generic write escaped selected packed fields")
                if(cancelledAt>=0)cancelWrites+=1
                if(!fail){memory(address)=(memory(address)&(full^bits))|(r.data.peek().litValue&bits);committed(address)|=bits}
              }else reads+=1
              pending(p)=Some(Reply(cycle+2+(p+cycle)%5,r.tag.peek().litValue,if(write)BigInt(0)else memory(address),write,bits,fail,address))
              held(p)=None
            }else held(p)=Some(request.bits.peek().litValue)
          }
        }
        if(blocked)d.io.lanes.foreach(_.done.expect(false.B))
        d.clock.step();cycle+=1
        cancelledLocations.foreach(values=>d.io.lanes.zip(values).foreach{case(lane,value)=>
          lane.locations.expect(value.U)})
      }
      assert(cycle<limit && pending.forall(_.isEmpty) && held.forall(_.isEmpty),s"generic contexts failed to drain at $cycle")
      responses.foreach(_.valid.poke(false.B));d.io.lanes.foreach(_.cancel.poke(false.B))
      for(l<-0 until 4){
        d.io.lanes(l).done.expect(true.B);d.io.lanes(l).writes.expect(ackWrites(l).U);d.io.lanes(l).bits.expect(ackBits(l).U)
        d.io.lanes(l).fault.expect((failed && l==0).B)
        if(failed && l==0)d.io.lanes(l).faultOffset.expect(failedOffset.U)
      }
      for((word,bits)<-committed)assert(((memory(word)^expected(word))&bits)==0,"committed generic data differs from reference")
      if(!cancelHeldWrite && cancelWhen.isEmpty && !faultWrite){
        assert(memory.toMap==expected.toMap && reads==expectedReads,s"generic image/read mismatch formats=$fs mode=$mode blend=$blend mask=$mask reads=$reads expected=$expectedReads")
        for(l<-0 until 4){d.io.lanes(l).writes.expect(expectedWrites(l).U);d.io.lanes(l).bits.expect(expectedBits(l).U)
          d.io.lanes(l).locations.expect(expectedLocations(l).U)
          d.io.lanes(l).touched.expect((if(expectedLocations(l)==0)0 else samples(l)).U)}
      }
      if(cancelHeldWrite || cancelWhen.nonEmpty)
        assert(cancelledAt>=0 && cancelWrites<=offeredCancelWrites,"cancel created writes beyond already offered ports")
      if(cancelWhen.nonEmpty)assert(offeredCancelWrites==0 && cancelWrites==0,"arithmetic-phase cancel exposed a write")
      if(faultWrite)assert(failed,"write fault fixture never reached its selected port")
      cycle
    }
  }
  for(ports<-Seq(4,8)){
    it should s"complete all64 owners across independent advanced and ordinary routes with faults and cancellation ports=$ports" in {
      simulate(new StreamingAdvancedColorProbe(ports)){d=>val t=new Driver(d,ports)
        val formats=Vector.fill(4)("RGBA32F")
        val mixed=Some(Vector(8,-1,14,-1))
        val cycles=t.run(formats,blend=true,advanced=mixed)
        t.run(formats,blend=true,advanced=Some(Vector(0,8,11,14)))
        if(ports==8){
          // Channels 0/2 carry advanced MRTs and 1/3 ordinary MRTs. Hold only
          // ordinary final destination words, preserving the real arithmetic
          // and every owner, then align its fixed pipeline tail to a reply.
          def reset(): Unit={d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)}
          reset();t.run(formats,blend=true,advanced=mixed,ordinaryLastReadHoldUntil=512)
          val tail=t.firstOrdinaryResult-512
          assert(tail>0 && t.advancedResults.size>=2)
          val release=t.advancedResults(1)-tail
          assert(release>512)
          reset();t.run(formats,blend=true,advanced=mixed,ordinaryLastReadHoldUntil=release)
          assert(t.collisions>=2 && t.ordinaryBlocked>0 && t.advancedBlocked>0,
            s"both result routes must contend: overlap=${t.collisions} ordinaryBlocked=${t.ordinaryBlocked} advancedBlocked=${t.advancedBlocked}")
          info(s"ADVANCED_RESULT_CONTENTION collisions=${t.collisions} ordinaryBlocked=${t.ordinaryBlocked} advancedBlocked=${t.advancedBlocked} boundedWait=1")
          reset();t.run(formats,blend=true,advanced=mixed,ordinaryLastReadHoldUntil=release,
            cancelWhen=Some(()=>d.resultCollisionObserved.peek().litToBoolean))
          assert(t.collisions>0,"cancellation did not reach a competing held-result cycle")
          t.run(formats,blend=true,advanced=Some(Vector(11,-1,0,-1)))
        }
        t.run(formats,blend=true,advanced=Some(Vector(0,-1,11,-1)),faultWrite=ports==8)
        for(phase<-0 until 3){
          t.run(formats,blend=true,advanced=mixed,cancelWhen=Some(()=>phase match{
            case 0=>d.advancedOfferObserved.peek().litToBoolean
            case 1=>d.advancedBusyObserved.peek().litToBoolean
            case _=>d.advancedReplyObserved.peek().litToBoolean
          }))
          t.run(formats,blend=true,advanced=Some(Vector(11,-1,0,-1)))
        }
        info(s"ADVANCED_PARALLEL_COLOR ports=$ports owners=64 advancedAndOrdinary=true independentOracles=true cycles=$cycles memoryBackpressure=true arithmeticCancelPhases=3 fault=${ports==8} healthyReuse=true")
      }
    }
  }

  for(ports<-Seq(4,8)){
    it should s"drain arithmetic-phase cancellation without creating word offers and allow reuse ports=$ports" in {
      simulate(new StreamingColorCancellationProbe(ports)){d=>
        val t=new Driver(d,ports);val fs=Vector("RGBA8","RGBA16F","RGBA32F","R32F")
        val phases=Seq[()=>Boolean](
          ()=>d.arithmeticOffered.peek().litToBoolean,
          ()=>d.arithmeticBusy.peek().litToBoolean && !d.arithmeticOffered.peek().litToBoolean,
          ()=>d.arithmeticReply.peek().litToBoolean)
        for(observed<-phases){t.run(fs,blend=true,cancelWhen=Some(observed));t.run(fs,blend=true)}
        info(s"STREAMING_COLOR_ARITHMETIC_CANCEL ports=$ports offered=true busy=true returning=true newWrites=0 exactHealthyReuse=true")
      }
    }

    it should s"commit all typed formats and sparse MRT samples through reordered held word replies ports=$ports" in {
      simulate(new StreamingTypedTileColorLanes(ports)){d=>
        val t=new Driver(d,ports);var quads=0
        for(fs<-names.grouped(4);blend<-Seq(false,true)){t.run(fs,blend=blend);quads+=1}
        for(mode<-0 to 2)t.run(Vector("RGBA8","RGB8","RGB16F","RGBA32F"),mode=mode,blend=true)
        t.run(Vector("R8","R16I","R32F","RGB565"),blend=true,mask=8)
        info(s"STREAMING_COLOR_CONTEXTS ports=$ports formats=36 mrtQuads=$quads msaaModes=3 absentChannelNoReads=true exactImages=true exactACKCounters=true")
      }
    }
  }
  it should "drain held write cancellation and write faults before healthy context reuse" in {
    simulate(new StreamingTypedTileColorLanes(8)){d=>
      val t=new Driver(d,8);val fs=Vector("RGBA8","RGB8","RGB16F","RGBA32F")
      t.run(fs,blend=true,cancelHeldWrite=true);t.run(fs,blend=true)
      t.run(fs,blend=true,faultWrite=true);t.run(fs,blend=true)
      info("STREAMING_COLOR_DRAIN heldWrite=true stickyCancel=true writeFault=true originalOffset=true prefixImage=true actualACKCounters=true healthyReuse=true")
    }
  }
}
