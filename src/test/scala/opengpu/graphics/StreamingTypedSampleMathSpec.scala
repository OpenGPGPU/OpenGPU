package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class StreamingTypedSampleMathSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,encode}
  private case class Face(function: Int=6,reference: Int=19,readMask: Int=255,writeMask: Int=255,
    fail: Int=0,depthFail: Int=0,depthPass: Int=0)
  private case class Work(token: Int,colors: Vector[Vector[BigInt]],depths: Vector[BigInt],oldColors: Vector[BigInt],oldDS: Vector[BigInt],
    blend: TypedBlendReference.State,active: Int=15,colorActive: Int=15,channels: Int=15,
    depth: Boolean=true,writeDepth: Boolean=true,function: Int=6,stencil: Boolean=true,
    front: Boolean=true,a: Face=Face(),b: Face=Face(),cancelled: Boolean=false,invalid: Boolean=false)
  private case class Expected(colors: Vector[BigInt],ds: Vector[BigInt],tested: Int,passing: Int,sfail: Int,zfail: Int,cwrite: Int,zwrite: Int,swrite: Int,status: Int)
  private val mask32=(BigInt(1)<<32)-1
  private val mask64=(BigInt(1)<<64)-1
  private val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x3f7fffffL,0x3f800000L,0x7f800000L,0xff800000L,0x7fc00001L).map(BigInt(_))
  private def pack(records: Vector[BigInt]): Vector[BigInt]=Vector.tabulate(2)(p=>records(2*p)|(records(2*p+1)<<32))
  private def work(i: Int,sparse: Boolean=false): Work={
    val oldColors=Vector.tabulate(4)(s=>Vector.tabulate(4)(c=>BigInt((i*17+s*47+c*61)&255)).zipWithIndex.foldLeft(BigInt(0)){
      case(v,(byte,c))=>v|(byte<<(c*8))})
    val oldDS=Vector.tabulate(4)(s=>((BigInt(i*977+s*134567)&0xffffff)|(BigInt((i*13+s*79)&255)<<24)))
    val colors=Vector.tabulate(4)(s=>Vector.tabulate(4)(c=>if(sparse && i%5==0)special((i+s+c)%special.size)
      else fp(((i*7+s*11+c*29)%140-20)/100.0)))
    val depths=Vector.tabulate(4)(s=>if(sparse && i%11==0)special((i+s)%9)else fp(((i+s*13)%120-10)/100.0))
    val state=TypedBlendReference.State(i%15,(i/15)%15,(i+7)%15,(i/15+11)%15,(i/225)%5,(i/225+2)%5,
      Vector.tabulate(4)(c=>fp(((i+c*17)%140-20)/100.0)),!sparse || i%9!=0)
    def face(seed: Int)=Face((seed/8)%8,(seed*31)&255,if(seed%3==0)0x55 else 255,
      if(seed%5==0)0 else if(seed%7==0)0xa5 else 255,seed%8,(seed/64)%8,(seed/512)%8)
    Work((i*37)&255,colors,depths,pack(oldColors),pack(oldDS),state,
      if(sparse)i%16 else 15,if(sparse)(i*7)%16 else 15,if(sparse)(i/16)%16 else 15,
      !sparse || i%7!=0,!sparse || i%5!=0,if(sparse)(i/8)%8 else 6,!sparse || i%13!=0,
      i%2==0,if(sparse)face(i)else Face(depthPass=i%8),if(sparse)face(i+117)else Face(depthPass=(i+3)%8))
  }
  private def expected(w: Work): Expected={
    val color=Array.tabulate(4)(s=>(w.oldColors(s/2)>>(32*(s%2)))&mask32)
    val ds=Array.tabulate(4)(s=>(w.oldDS(s/2)>>(32*(s%2)))&mask32)
    var tested=0;var passing=0;var sfail=0;var zfail=0;var cw=0;var zw=0;var sw=0
    if(!w.cancelled && !w.invalid)for(s<-0 until 4 if (w.active&(1<<s))!=0){
      tested|=1<<s
      val f=if(w.front)w.a else w.b;val oldS=((ds(s)>>24)&255).toInt
      val sp= !w.stencil || TypedDepthReference.compare(f.reference&f.readMask,oldS&f.readMask,f.function)
      val z=TypedDepthReference.encode(w.depths(s),4)
      val dp= !w.depth || TypedDepthReference.compare(z,ds(s)&0xffffff,w.function)
      val pass=sp && dp
      if(!sp)sfail|=1<<s else if(!dp)zfail|=1<<s
      if(pass)passing|=1<<s
      val op=if(!sp)f.fail else if(!dp)f.depthFail else f.depthPass
      val ns=(TypedDepthReference.operation(oldS,f.reference,op)&f.writeMask)|(oldS&(~f.writeMask&255))
      if(w.stencil && ns!=oldS){sw|=1<<s;ds(s)=(ds(s)&0xffffff)|(BigInt(ns)<<24)}
      if(pass && w.depth && w.writeDepth){zw|=1<<s;ds(s)=(ds(s)&BigInt("ff000000",16))|z}
      if(pass && (w.colorActive&(1<<s))!=0 && w.channels!=0){
        cw|=1<<s
        val value=encode("RGBA8",TypedBlendReference.blend("RGBA8",w.colors(s),color(s),w.blend))._1
        val mask=(0 until 4).filter(c=>(w.channels&(1<<c))!=0).foldLeft(BigInt(0))((v,c)=>v|(BigInt(255)<<(8*c)))
        color(s)=(color(s)&(mask32^mask))|(value&mask)
      }
    }
    Expected(pack(color.toVector),pack(ds.toVector),tested,passing,sfail,zfail,cw,zw,sw,if(w.cancelled)4 else if(w.invalid)6 else 0)
  }
  private def offer(d: StreamingTypedSampleMath,w: Work): Unit={
    val b=d.io.in.bits;b.token.poke(w.token.U);b.cancelled.poke(w.cancelled.B)
    b.active.poke(w.active.U);b.colorActive.poke(w.colorActive.U);b.channelMask.poke(w.channels.U)
    b.frontFacing.poke(w.front.B);b.tests.version.poke((if(w.invalid)0 else 1).U)
    b.tests.depthEnable.poke(w.depth.B);b.tests.depthWrite.poke(w.writeDepth.B);b.tests.depthFunction.poke(w.function.U)
    b.tests.stencilEnable.poke(w.stencil.B)
    def face(a: TypedStencilFace,f: Face): Unit={
      a.function.poke(f.function.U);a.reference.poke(f.reference.U);a.readMask.poke(f.readMask.U);a.writeMask.poke(f.writeMask.U)
      a.fail.poke(f.fail.U);a.depthFail.poke(f.depthFail.U);a.depthPass.poke(f.depthPass.U)
    }
    face(b.tests.front,w.a);face(b.tests.back,w.b);TypedBlendReference.poke(b.blend,w.blend)
    for(s<-0 until 4){b.depths(s).poke(w.depths(s).U);b.colors(s).zip(w.colors(s)).foreach{case(a,v)=>a.poke(v.U)}}
    for(p<-0 until 2){b.oldColors(p).poke(w.oldColors(p).U);b.oldDepthStencil(p).poke(w.oldDS(p).U)}
    d.io.in.valid.poke(true.B)
  }
  private def check(d: StreamingTypedSampleMath,w: Work,cancelled: Boolean=false): Unit={
    val e=expected(w);val r=d.io.out.bits;val result=r.result
    val suppress=cancelled || e.status!=0
    r.token.expect(w.token.U);result.status.expect((if(cancelled && e.status==0)4 else e.status).U)
    r.colors.zip(if(suppress)w.oldColors else e.colors).foreach{case(a,v)=>a.expect(v.U)}
    // Suppressed plans carry no write permission; their DS data is irrelevant.
    if(!suppress)r.depthStencil.zip(e.ds).foreach{case(a,v)=>a.expect(v.U)}
    result.tested.expect(e.tested.U);result.passing.expect(e.passing.U)
    result.stencilRejected.expect(e.sfail.U);result.depthRejected.expect(e.zfail.U)
    val cw=if(suppress)0 else e.cwrite;val zw=if(suppress)0 else e.zwrite;val sw=if(suppress)0 else e.swrite
    result.colorWritten.expect(cw.U);result.depthWritten.expect(zw.U);result.stencilWritten.expect(sw.U)
    def pairs(mask: Int): Int=(if((mask&3)!=0)1 else 0)|(if((mask&12)!=0)2 else 0)
    r.colorWrite.expect(pairs(cw).U);r.depthStencilWrite.expect(pairs(zw|sw).U)
    result.dsReads.expect(0.U);result.dsFills.expect(0.U);result.faultOffset.expect(0.U)
  }
  private def initialize(d: StreamingTypedSampleMath): Unit={
    d.io.begin.poke(false.B);d.io.cancel.poke(false.B);d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
    d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
  }
  private def stream(d: StreamingTypedSampleMath,works: Vector[Work],stalls: Boolean=false,dense: Boolean=false): Unit={
    var sent=0;var received=0;var cycle=0;var lastReply= -1;var firstReply= -1;var held: Option[BigInt]=None;var pendingOffer=false
    while(received<works.size && cycle<works.size*12+300){
      if(sent<works.size && (!stalls || cycle%5!=2 || pendingOffer))offer(d,works(sent))else d.io.in.valid.poke(false.B)
      val ready= !stalls || cycle%11>=5;d.io.out.ready.poke(ready.B)
      if(dense && sent<works.size)d.io.in.ready.expect(true.B)
      held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v,"held sample writeback changed")})
      if(d.io.out.valid.peek().litToBoolean){
        assert(received<sent,"sample output escaped before input ownership")
        check(d,works(received))
        if(ready){
          if(firstReply<0)firstReply=cycle
          if(dense && lastReply>=0)assert(cycle==lastReply+1,"sample math inserted a reply bubble")
          lastReply=cycle;received+=1;held=None
        }else held=Some(d.io.out.bits.peek().litValue)
      }
      pendingOffer=d.io.in.valid.peek().litToBoolean && !d.io.in.ready.peek().litToBoolean
      if(sent<works.size && d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean)sent+=1
      d.clock.step();cycle+=1
    }
    assert(sent==works.size && received==works.size,s"sample math did not drain: $sent/$received at $cycle")
    d.io.in.valid.poke(false.B);d.io.drained.expect(true.B)
    if(dense)info(s"STREAMING_SAMPLE_MATH packets=${works.size} samples=${works.size*4} inputII=1 outputII=1 firstReplyClocks=$firstReply enabledBlendDepthStencil=true exactFP32andBytes=true")
  }
  it should "accept and retire four complete blended depth stencil sample plans per clock" in {
    simulate(new StreamingTypedSampleMath){d=>initialize(d);stream(d,Vector.tabulate(1125)(i=>work(i)),dense=true)}
  }
  it should "preserve sparse masks, front and back tests, disabled state and packed neighbors through stalls" in {
    simulate(new StreamingTypedSampleMath){d=>initialize(d)
      val cases=Vector.tabulate(2048)(i=>work(i,sparse=true)) ++
        Vector(work(4001,true).copy(invalid=true),work(4002,true).copy(cancelled=true))
      stream(d,cases,stalls=true)
      info(s"STREAMING_SAMPLE_MATH sparsePackets=${cases.size} compareFunctions=8 stencilOperations=8 channelMasks=16 frontBack=true disabledDepthWrites=true invalidAndCancelled=true heldWriteback=true")
    }
  }
  it should "drain owned packets after cancellation and keep a held writeback immutable before healthy reuse" in {
    simulate(new StreamingTypedSampleMath){d=>initialize(d)
      val accepted=mutable.Queue.empty[Work];var sent=0;var cycles=0
      while((sent<16 || d.io.in.ready.peek().litToBoolean) && cycles<256){
        val w=work(sent);offer(d,w)
        if(d.io.in.ready.peek().litToBoolean){accepted.enqueue(w);sent+=1}
        d.clock.step();cycles+=1
      }
      assert(sent>=16 && sent<128);d.io.in.valid.poke(false.B);d.io.out.valid.expect(true.B)
      val first=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step(4)
      assert(d.io.out.bits.peek().litValue==first,"cancel changed an offered sample writeback")
      d.io.cancel.poke(false.B)
      var retired=0;var extra=false;var held: Option[BigInt]=None;var earlyCancelled=0;var lateCancelled=0;var preserved=0
      while((accepted.nonEmpty || !extra) && cycles<2000){
        if(!extra)offer(d,work(5000).copy(cancelled=true))else d.io.in.valid.poke(false.B)
        val ready=cycles%4!=0;d.io.out.ready.poke(ready.B)
        held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v)})
        if(d.io.out.valid.peek().litToBoolean){
          assert(accepted.nonEmpty);val status=d.io.out.bits.result.status.peek().litValue.toInt
          // The two output queue entries offered before cancel stay immutable.
          assert(status==0 || status==4)
          val early=status==4 && d.io.out.bits.result.tested.peek().litValue==0
          if(early)check(d,accepted.front.copy(cancelled=true))
          else check(d,accepted.front,cancelled=status==4)
          if(ready){
            if(early)earlyCancelled+=1 else if(status==4)lateCancelled+=1 else preserved+=1
            accepted.dequeue();retired+=1;held=None
          }else held=Some(d.io.out.bits.peek().litValue)
        }
        if(!extra && d.io.in.ready.peek().litToBoolean){accepted.enqueue(work(5000).copy(cancelled=true));extra=true}
        d.clock.step();cycles+=1
      }
      assert(accepted.isEmpty && extra && retired==sent+1 && earlyCancelled>0 && lateCancelled>0 && preserved>0);d.io.in.valid.poke(false.B);d.io.drained.expect(true.B)
      d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
      stream(d,Vector.tabulate(64)(i=>work(6000+i)),dense=true)
      info(s"STREAMING_SAMPLE_MATH cancelledOwnedPackets=${sent+1} earlyCancelled=$earlyCancelled lateCancelled=$lateCancelled preservedOffers=$preserved internalDrainAfterCancel=true heldOutputImmutable=true completeCohortDrain=true healthyReuse=true")
    }
  }
}
