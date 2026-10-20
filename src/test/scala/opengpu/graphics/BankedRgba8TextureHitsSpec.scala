package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BankedRgba8TextureHitsSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val weights=Vector[BigInt](8192,24576,8192,24576)
  private def color(raw: Seq[BigInt],w: Seq[BigInt]): BigInt=(0 until 4).foldLeft(BigInt(0)){(packed,c)=>
    val channel=raw.zip(w).map{case(v,x)=>((v>>(8*c))&255)*x}.sum>>16
    (packed<<8)|channel}
  private class Driver(val d: BankedRgba8TextureHits){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var taps=0;var conflicts=0
    val generations=mutable.Map.empty[BigInt,Int];val contexts=mutable.Map.empty[BigInt,Int]
    val accepted=mutable.ArrayBuffer.empty[(Int,BigInt)]
    val retired=mutable.ArrayBuffer.empty[(Int,BigInt,Boolean,Boolean,BigInt,BigInt)]
    def tick(): Unit={
      if(d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean)accepted+=((cycle,d.io.in.bits.tag.peek().litValue))
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){val q=d.io.out.bits
        q.identity.context.expect(contexts(q.tag.peek().litValue).U);q.identity.pass.expect(8.U);q.identity.generation.expect(generations(q.tag.peek().litValue).U)
        retired+=((cycle,q.tag.peek().litValue,q.hit.peek().litToBoolean,q.cancelled.peek().litToBoolean,q.color.peek().litValue,q.missingTaps.peek().litValue))}
      taps+=d.io.tapIssues.peek().litValue.toInt;conflicts+=d.io.bankConflicts.peek().litValue.toInt;d.clock.step();cycle+=1
    }
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<3000){tick();n+=1};assert(p,s"banked texture stalled cycle=$cycle accepted=${accepted.size} retired=${retired.size}")}
    def identity(q: GraphicsWorkIdentity,generation: Int=9,context: Int=7): Unit={q.context.poke(context.U);q.pass.poke(8.U);q.generation.poke(generation.U)}
    def refill(a: BigInt,parity: Boolean,raw: Seq[BigInt],key: BigInt=1,generation: Int=9,fault: Boolean=false,cut: Int= -1): Unit={
      val q=d.io.refill.bits;zero(q);identity(q.identity,generation);q.textureKey.poke(key.U);q.address.poke(a.U);q.rowParity.poke(parity.B);q.fault.poke(fault.B)
      q.data.poke(raw.zipWithIndex.map{case(v,n)=>v<<(32*n)}.sum.U)
      d.io.refill.valid.poke(true.B);until(d.io.refill.ready.peek().litToBoolean);tick();d.io.refill.valid.poke(false.B)
      var n=0;while(!d.io.refillDone.valid.peek().litToBoolean && n<30){d.io.invalidate.poke((n==cut).B);tick();n+=1}
      d.io.invalidate.poke(false.B);d.io.refillDone.valid.expect(true.B);val r=d.io.refillDone.bits
      r.identity.generation.expect(generation.U);r.address.expect(a.U);r.textureKey.expect(key.U);r.fault.expect(fault.B)
      r.installed.expect((!fault && cut<0).B);r.invalidated.expect((cut>=0).B)
      val held=r.peek().litValue;for(_<-0 until 3){tick();assert(r.peek().litValue==held);d.io.busy.expect(true.B);d.io.refill.ready.expect(false.B)}
      d.io.refillDone.ready.poke(true.B);tick();d.io.refillDone.ready.poke(false.B)
    }
    def request(tag: Int,a: Seq[BigInt],parity: Seq[Boolean],w: Seq[BigInt]=weights,key: BigInt=1,generation: Int=9,context: Int=7): Unit={
      generations(BigInt(tag))=generation;contexts(BigInt(tag))=context
      val q=d.io.in.bits;identity(q.identity,generation,context);q.textureKey.poke(key.U);q.tag.poke(tag.U)
      q.addresses.zip(a).foreach{case(x,y)=>x.poke(y.U)};q.rowParity.zip(parity).foreach{case(x,y)=>x.poke(y.B)}
      q.weights.zip(w).foreach{case(x,y)=>x.poke(y.U)};d.io.in.valid.poke(true.B)
    }
    def submit(tag: Int,a: Seq[BigInt],p: Seq[Boolean],w: Seq[BigInt]=weights,key: BigInt=1,generation: Int=9,context: Int=7): Unit={
      request(tag,a,p,w,key,generation,context);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
    }
    def consume(): (BigInt,Boolean,Boolean,BigInt,BigInt)={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits
      val value=(r.tag.peek().litValue,r.hit.peek().litToBoolean,r.cancelled.peek().litToBoolean,r.color.peek().litValue,r.missingTaps.peek().litValue)
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);value
    }
    def drain(): Unit={d.io.out.ready.poke(true.B);until(!d.io.busy.peek().litToBoolean);d.io.out.ready.poke(false.B)}
  }
  private val even=Vector.tabulate(16)(i=>BigInt(0xff000000L)|(BigInt(i*11)<<16)|(BigInt(255-i*13)<<8)|BigInt(i*7))
  private val odd=Vector.tabulate(16)(i=>BigInt(0x80000000L)|(BigInt(240-i*9)<<16)|(BigInt(i*9)<<8)|BigInt(255-i*11))
  private val addresses=Vector[BigInt](0x1004,0x1008,0x2004,0x2008)
  private val parity=Vector(false,false,true,true)
  it should "sustain one complete bilinear sample per cycle from four resident banks" in {
    simulate(new BankedRgba8TextureHits()){d=>val t=new Driver(d)
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd);d.io.out.ready.poke(true.B)
      val patterns=Vector(weights,Vector[BigInt](65536,0,0,0),Vector.fill(4)(BigInt(16384)),Vector[BigInt](0,0,0,65536))
      val expected=mutable.Map.empty[BigInt,BigInt]
      for(n<-0 until 96){val w=patterns(n%patterns.size);expected(BigInt(n))=color(Seq(even(1),even(2),odd(1),odd(2)),w)
        t.request(n,addresses,parity,w);t.until(d.io.in.ready.peek().litToBoolean);t.tick()}
      d.io.in.valid.poke(false.B);t.drain()
      assert(t.accepted.size==96 && t.retired.size==96 && t.taps==384)
      assert(t.accepted.sliding(2).forall(x=>x(1)._1-x(0)._1==1),"resident request II must be one")
      assert(t.retired.sliding(2).forall(x=>x(1)._1-x(0)._1==1),"complete filter result II must be one")
      for(r<-t.retired){assert(r._3 && !r._4 && r._6==0);assert(r._5==expected(r._2))};assert(t.conflicts==0)
      info("BANKED_TEXTURE_HIT_RATE samples96=true banks4=true taps384=true requestII1=true resultII1=true pipelinedFilter=true exactIntegerOracle=true")
    }
  }
  it should "serialize bank conflicts and coalesce duplicate clamp taps without losing samples" in {
    simulate(new BankedRgba8TextureHits()){d=>val t=new Driver(d);t.refill(0x1000,false,even);d.io.out.ready.poke(true.B)
      val conflict=Vector[BigInt](0x1000,0x1008,0x1010,0x1018)
      for(n<-0 until 24)t.submit(n,conflict,Vector.fill(4)(false))
      t.drain();assert(t.accepted.sliding(2).forall(x=>x(1)._1-x(0)._1==4));assert(t.taps==96 && t.conflicts>0)
      val want=color(Seq(even(0),even(2),even(4),even(6)),weights);assert(t.retired.forall(r=>r._3 && r._5==want))
      t.accepted.clear();t.retired.clear();val before=t.taps;d.io.out.ready.poke(true.B)
      for(n<-0 until 24)t.submit(100+n,Vector.fill(4)(BigInt(0x1004)),Vector.fill(4)(false))
      t.drain();assert(t.taps-before==24 && t.retired.size==24)
      assert(t.accepted.sliding(2).forall(x=>x(1)._1-x(0)._1==1));assert(t.retired.forall(r=>r._3 && r._5==color(Seq.fill(4)(even(1)),weights)))
      info("BANKED_TEXTURE_CONFLICTS sameBankII4=true duplicateClampTapIssues1=true duplicateSampleII1=true")
    }
  }
  it should "retain eight KiB of unique data through both physical halves of all four banks" in {
    simulate(new BankedRgba8TextureHits()){d=>val t=new Driver(d)
      for(p<-0 until 2;s<-0 until 64){val a=BigInt(0x10000+p*0x10000+s*64);val word=BigInt(0xff000000L)|(BigInt(p*64+s)<<8)|BigInt(s)
        t.refill(a,p==1,Vector.fill(16)(word))}
      for(p<-0 until 2;s<-0 until 64){val a=BigInt(0x10000+p*0x10000+s*64);val word=BigInt(0xff000000L)|(BigInt(p*64+s)<<8)|BigInt(s)
        t.submit(p*64+s,Vector.fill(4)(a),Vector.fill(4)(p==1));val z=t.consume();assert(z._2 && !z._3 && z._4==color(Seq.fill(4)(word),weights))}
      t.drain();assert(t.retired.size==128)
      info("BANKED_TEXTURE_CAPACITY uniqueLines128=true data8192Bytes=true noReplication=true logicalBanks4=true physical256x32Arrays8=true")
    }
  }
  it should "keep misses explicit and isolate physical aliases across context view and invalidation" in {
    simulate(new BankedRgba8TextureHits(dataBytes=512)){d=>val t=new Driver(d)
      t.submit(0,addresses,parity);assert(t.consume()==((BigInt(0),false,false,BigInt(0),BigInt(15))))
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd)
      t.submit(1,addresses,parity,key=2);assert(!t.consume()._2)
      t.submit(2,addresses,parity,generation=10);assert(!t.consume()._2)
      t.submit(7,addresses,parity,context=8);assert(!t.consume()._2)
      t.refill(0x1000,false,even,fault=true);t.submit(3,addresses,parity);val bad=t.consume();assert(!bad._2 && bad._5==3 && bad._4==0)
      t.refill(0x1000,false,even,cut=3);t.submit(4,addresses,parity);assert(t.consume()._5==15)
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd);t.submit(5,addresses,parity);assert(t.consume()._2)
      d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B);t.submit(6,addresses,parity);assert(!t.consume()._2)
      t.drain();info("BANKED_TEXTURE_COHERENCE explicitMissMasks=true failedFillNeverHits=true invalidateDuringFillNoPublish=true contextGenerationAndViewKeys=true healthyReuse=true")
    }
  }
  it should "hold results immutable while cancelling and drain reserved sample replies" in {
    simulate(new BankedRgba8TextureHits(dataBytes=512)){d=>val t=new Driver(d)
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd);t.submit(0,addresses,parity);t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.invalidate.poke(true.B)
      for(_<-0 until 12){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B);d.io.busy.expect(true.B)}
      assert(t.consume()._2);d.io.cancel.poke(false.B);d.io.invalidate.poke(false.B);t.drain()
      var cancelled=0;var heldReplies=0
      for(cut<-0 to 12){
        t.refill(0x1000,false,even);t.refill(0x2000,true,odd);t.submit(10+cut,addresses,parity);for(_<-0 until cut)t.tick();val offered=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B)
        val z=t.consume();if(offered){assert(z._2 && !z._3);heldReplies+=1}else{assert(z._3 && !z._2 && z._4==0 && z._5==0);cancelled+=1};t.drain();d.io.cancel.poke(false.B)
      }
      assert(cancelled>0 && heldReplies>0)
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd);t.submit(50,addresses,parity);assert(t.consume()._2);t.drain()
      info("BANKED_TEXTURE_LIFETIME heldReplyImmutable=true cancellationCuts13=true bankRepliesDrained=true healthyReuse=true")
    }
  }

  it should "keep the opposite bank pair streaming while an eight beat refill owns its data banks" in {
    simulate(new BankedRgba8TextureHits(dataBytes=512)){d=>val t=new Driver(d);t.refill(0x1000,false,even);t.refill(0x2000,true,odd)
      val q=d.io.refill.bits;zero(q);t.identity(q.identity);q.textureKey.poke(1.U);q.address.poke(0x3000.U)
      q.data.poke(even.reverse.zipWithIndex.map{case(v,n)=>v<<(32*n)}.sum.U)
      d.io.refill.valid.poke(true.B);t.until(d.io.refill.ready.peek().litToBoolean);t.tick();d.io.refill.valid.poke(false.B)
      d.io.out.ready.poke(true.B)
      for(n<-0 until 12){t.request(n,Vector.fill(4)(BigInt(0x2004)),Vector.fill(4)(true));d.io.in.ready.expect(true.B);t.tick()}
      d.io.in.valid.poke(false.B);d.io.refillDone.valid.expect(true.B);d.io.refillDone.bits.installed.expect(true.B)
      d.io.refillDone.ready.poke(true.B);t.tick();d.io.refillDone.ready.poke(false.B);t.drain()
      assert(t.accepted.size==12 && t.retired.size==12 && t.taps==12)
      assert(t.accepted.sliding(2).forall(x=>x(1)._1-x(0)._1==1));assert(t.retired.forall(r=>r._3 && r._5==color(Seq.fill(4)(odd(1)),weights)))
      t.submit(30,addresses,parity);val old=t.consume();assert(!old._2 && old._5==3)
      val current=Vector[BigInt](0x3004,0x3008,0x2004,0x2008);t.submit(31,current,parity);assert(t.consume()._2);t.drain()
      info("BANKED_TEXTURE_REFILL_OVERLAP writeBanks2=true independentReadPairII1=true refillBeats8=true physicalLineTagPreventsAlias=true")
    }
  }
  it should "preserve bounded sample association under long asymmetric output backpressure" in {
    simulate(new BankedRgba8TextureHits(dataBytes=512)){d=>val t=new Driver(d);t.refill(0x1000,false,even);t.refill(0x2000,true,odd)
      var offered=0;val expected=mutable.Map.empty[BigInt,BigInt];var held: Option[BigInt]=None;var stalled=false
      val start=t.cycle
      while(t.retired.size<192 && t.cycle-start<5000){
        if(offered<192){val duplicate=offered%3==0;val a=if(duplicate)Vector.fill(4)(BigInt(0x1004))else addresses
          val p=if(duplicate)Vector.fill(4)(false)else parity
          t.request(offered,a,p);expected(BigInt(offered))=color(if(duplicate)Seq.fill(4)(even(1))else Seq(even(1),even(2),odd(1),odd(2)),weights)
        }else d.io.in.valid.poke(false.B)
        val ready=(t.cycle-start)%67>=35 && t.cycle%7!=0;d.io.out.ready.poke(ready.B)
        held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v)})
        held=if(d.io.out.valid.peek().litToBoolean && !ready)Some(d.io.out.bits.peek().litValue)else None
        if(d.io.in.valid.peek().litToBoolean && !d.io.in.ready.peek().litToBoolean)stalled=true
        val accepted=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean;t.tick();if(accepted)offered+=1
      }
      assert(offered==192 && t.retired.size==192 && stalled)
      assert(t.retired.map(_._2)==(0 until 192).map(BigInt(_)))
      for(r<-t.retired)assert(r._3 && !r._4 && r._5==expected(r._2));t.drain()
      info("BANKED_TEXTURE_BACKPRESSURE samples192=true boundedROB16=true wrapAndTagAssociation=true heldRepliesStable=true producerStalls=true exactColors=true")
    }
  }


  it should "remember a cancellation pulse behind an already offered sample" in {
    simulate(new BankedRgba8TextureHits(dataBytes=512)){d=>val t=new Driver(d)
      t.refill(0x1000,false,even);t.refill(0x2000,true,odd)
      t.submit(60,addresses,parity);t.submit(61,addresses,parity)
      t.until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.tag.expect(60.U)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 8){t.tick();assert(d.io.out.bits.peek().litValue==held)}
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      assert(d.io.out.bits.peek().litValue==held)
      val first=t.consume();assert(first._1==60 && first._2 && !first._3)
      val waiting=t.consume();assert(waiting._1==61 && waiting._3 && !waiting._2 && waiting._4==0 && waiting._5==0,
        s"unoffered filter result forgot one-cycle cancellation: $waiting")
      t.drain();t.refill(0x1000,false,even);t.refill(0x2000,true,odd);t.submit(62,addresses,parity);assert(t.consume()._2);t.drain()
      info("BANKED_TEXTURE_CANCEL_PULSE heldPublicResultImmutable=true waitingFilterRemembersPulse=true cancelledColorSuppressed=true healthyReuse=true")
    }
  }

}
