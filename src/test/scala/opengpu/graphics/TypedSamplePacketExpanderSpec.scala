package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedSamplePacketExpanderSpec extends AnyFlatSpec {
  import TypedColorReference.fp
  private case class Tile(mode: Int,log2: Int=4,x: Int=0,y: Int=0,channels: Int=15){
    val side=1<<log2;val count=1<<mode;val colorBase=side*side*count*4
  }
  private case class Work(seed: Int,dense: Boolean=false){
    val tag=(seed*239)&65535
    def mask(l: Int,t: Tile): Int=if(dense)(1<<t.count)-1 else (seed*7+l*3)&((1<<t.count)-1)
    def color(l: Int): Boolean=dense || (seed+l)%3!=0
    def raw(l: Int,c: Int): BigInt=(BigInt("7f012345",16)+seed*1009+l*271+c*37)&BigInt("ffffffff",16)
    def z(l: Int): BigInt=fp((l+1)/9.0)
    def sampleZ(l: Int,s: Int): BigInt=fp((l*4+s+1)/31.0)
    def sampled: Boolean=seed%2==0
    def overrideDepth: Boolean=seed%3==0
    def local(l: Int,t: Tile): (Int,Int)=(2*((seed*3)%(t.side/2))+l%2,2*((seed*5)%(t.side/2))+l/2)
  }
  private case class Expected(w: Work,packet: Int,tests: BigInt,blend: BigInt)
  private class Driver(val d: TypedSamplePacketExpander){
    var tile=Tile(0);var cycle=0
    d.io.bind.valid.poke(false.B);d.io.bind.bits.poke(0.U.asTypeOf(d.io.bind.bits))
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
    def bind(t: Tile,mutate: TypedTilePixelDescriptor=>Unit=_=>(),supported: Boolean=true): Unit={
      tile=t;d.io.drained.expect(true.B);d.io.bind.ready.expect(true.B)
      val b=d.io.bind.bits;b.poke(0.U.asTypeOf(b));b.version.poke(1.U);b.colors.version.poke(1.U)
      b.colors.tileLog2.poke(t.log2.U);b.colors.sampleMode.poke(t.mode.U)
      b.colors.tileWidth.poke(t.side.U);b.colors.tileHeight.poke(t.side.U)
      b.colors.originX.poke(t.x.S);b.colors.originY.poke(t.y.S);b.colors.reservedBytes.poke(t.colorBase.U)
      b.colors.colors(0).enabled.poke(true.B);b.colors.colors(0).format.poke(TypedColorFormat.id("RGBA8").U)
      b.colors.colors(0).offset.poke(t.colorBase.U);b.colors.colors(0).channelMask.poke(t.channels.U)
      b.depth.format.poke(TypedDepthFormat.d24s8.U);mutate(b);d.io.bind.valid.poke(true.B);step()
      d.io.bind.valid.poke(false.B);b.poke(0.U.asTypeOf(b));d.io.supported.expect(supported.B)
    }
    def offer(w: Work): Unit={
      val in=d.io.in.bits;in.poke(0.U.asTypeOf(in));in.tag.poke(w.tag.U)
      val q=in.quad.color.quad;q.sampleMode.poke(tile.mode.U);q.colorSlots.poke(1.U);q.frontFacing.poke((w.seed%2==0).B)
      for(l<-0 until 4){val p=q.lanes(l);val(x,y)=w.local(l,tile)
        p.x.poke((tile.x+x).S);p.y.poke((tile.y+y).S);p.coverageMask.poke(w.mask(l,tile).U);p.colorMask.poke((if(w.color(l))1 else 0).U)
        p.depth.poke(w.z(l).U);p.sampleDepthValid.poke(w.sampled.B);p.depthOverride.poke(w.overrideDepth.B)
        for(s<-0 until 4)p.sampleDepth(s).poke(w.sampleZ(l,s).U)
        for(c<-0 until 4)p.colors(0)(c).poke(w.raw(l,c).U)
        in.quad.color.samples(l).poke(w.mask(l,tile).U)
      }
      val state=in.quad.state;state.version.poke(1.U);state.depthEnable.poke((w.seed%2==0).B)
      state.depthWrite.poke((w.seed%3==0).B);state.depthFunction.poke((w.seed%8).U);state.stencilEnable.poke((w.seed%5!=0).B)
      for((f,i)<-Seq(state.front,state.back).zipWithIndex){
        f.function.poke(((w.seed+i)%8).U);f.reference.poke(((w.seed*17+i)&255).U)
        f.readMask.poke(((w.seed*31+i)&255).U);f.writeMask.poke(((w.seed*43+i)&255).U)
        f.fail.poke(((w.seed+i+1)%8).U);f.depthFail.poke(((w.seed+i+2)%8).U);f.depthPass.poke(((w.seed+i+3)%8).U)
      }
      TypedBlendReference.poke(in.quad.color.blend,TypedBlendReference.State(src=w.seed%15,dst=(w.seed+3)%15,
        sa=(w.seed+5)%15,da=(w.seed+7)%15,eq=w.seed%5,ea=(w.seed+2)%5,
        constant=Vector.tabulate(4)(c=>fp(((w.seed+c)%9)/8.0)),enable=w.seed%2==0))
      d.io.in.valid.poke(true.B)
    }
    def expected(w: Work): Vector[Expected]={
      val tests=d.io.in.bits.quad.state.peek().litValue;val blend=d.io.in.bits.quad.color.blend.peek().litValue
      Vector.tabulate(tile.count)(p=>Expected(w,p,tests,blend))
    }
    def check(e: Expected): Unit={
      val out=d.io.out.bits;val w=e.w
      out.tag.expect(w.tag.U);out.packet.expect(e.packet.U);out.last.expect((e.packet==tile.count-1).B)
      out.channelMask.expect(tile.channels.U);out.frontFacing.expect((w.seed%2==0).B)
      assert(out.tests.peek().litValue==e.tests);assert(out.blend.peek().litValue==e.blend)
      var active=0;var colored=0;val indices=Vector.tabulate(4){i=>
        val lane=if(tile.mode==0)i else if(tile.mode==1)e.packet*2+i/2 else e.packet
        val sample=if(tile.mode==0)0 else if(tile.mode==1)i%2 else i
        val(x,y)=w.local(lane,tile);val index=(y*tile.side+x)*tile.count+sample
        out.lanes(i).expect(lane.U);out.samples(i).expect(sample.U);out.indices(i).expect(index.U)
        out.colors(i).zipWithIndex.foreach{case(v,c)=>v.expect(w.raw(lane,c).U)}
        out.depths(i).expect((if(w.sampled && !w.overrideDepth)w.sampleZ(lane,sample)else w.z(lane)).U)
        if((w.mask(lane,tile)&(1<<sample))!=0){active|=1<<i;if(w.color(lane) && tile.channels!=0)colored|=1<<i}
        index
      }
      out.active.expect(active.U);out.colorActive.expect(colored.U)
      for(pair<-0 until 2){
        assert(indices(pair*2)%2==0 && indices(pair*2+1)==indices(pair*2)+1)
        out.words(pair).expect((tile.colorBase/8+indices(pair*2)/2).U)
        out.words(pair+2).expect((indices(pair*2)/2).U)
      }
    }
    def step(): Unit={d.clock.step();cycle+=1}
    def run(work: Vector[Work],ready: Int=>Boolean=_=>true,dense: Boolean=false): Unit={
      val pending=mutable.Queue.empty[Expected];var sent=0;var received=0;var steps=0;var previous= -1
      var held: Option[BigInt]=None
      while((sent<work.size || pending.nonEmpty) && steps<20000){
        if(sent<work.size)offer(work(sent))else {d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))}
        d.io.out.ready.poke(ready(cycle).B)
        if(sent<work.size && d.io.in.ready.peek().litToBoolean){pending ++= expected(work(sent));sent+=1}
        val out=d.io.out
        held.foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v,"held sample packet changed")})
        if(out.valid.peek().litToBoolean){
          assert(pending.nonEmpty);check(pending.front);out.bits.cancelled.expect(false.B)
          if(ready(cycle)){
            if(dense && previous>=0)assert(cycle==previous+1,"packet stream inserted a throughput bubble")
            previous=cycle;pending.dequeue();received+=1;held=None
          }else held=Some(out.bits.peek().litValue)
        }
        step();steps+=1
      }
      assert(steps<20000 && received==work.size*tile.count);d.io.in.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }
  it should "emit one four-sample packet per clock with canonical paired words at1x2x4x" in {
    simulate(new TypedSamplePacketExpander){dut=>val d=new Driver(dut)
      for(mode<-0 to 2){d.bind(Tile(mode));d.run(Vector.tabulate(256>>mode)(i=>Work(i,true)),dense=true)}
      info("TYPED_SAMPLE_PACKETS modes=1x2x4x packets=768 samples=3072 responseII=1 pairedColorWords=2 pairedDepthWords=2 exactOrder=true")
    }
  }
  it should "snapshot sparse masks, signed origins, front back states and effective sample depth through stalls" in {
    simulate(new TypedSamplePacketExpander){dut=>val d=new Driver(dut)
      for(mode<-0 to 2;log<-Seq(3,4);channels<-Seq(0,5,15)){
        d.bind(Tile(mode,log,Int.MinValue,Int.MaxValue-15,channels))
        d.run(Vector.tabulate(17)(i=>Work(i+100)),c=>c%7>=3)
      }
    }
  }
  it should "decline unsupported descriptor layouts before accepting a native quad" in {
    simulate(new TypedSamplePacketExpander){dut=>val d=new Driver(dut)
      val cases=Vector[TypedTilePixelDescriptor=>Unit](
        b=>b.colors.sampleMode.poke(3.U),b=>b.colors.tileLog2.poke(2.U),
        b=>b.colors.colors(0).format.poke(TypedColorFormat.id("RGBA16F").U),
        b=>b.colors.colors(1).enabled.poke(true.B),b=>b.depth.format.poke(TypedDepthFormat.d32f.U),
        b=>b.stencil.format.poke(TypedDepthFormat.s8.U),b=>b.colors.originX.poke(1.S),
        b=>b.depth.offset.poke(4.U),b=>b.colors.colors(0).offset.poke(4.U))
      for(change<-cases){
        d.bind(Tile(0),change,false);d.offer(Work(0,true))
        for(_<-0 until 3){dut.io.in.ready.expect(false.B);dut.io.out.valid.expect(false.B);dut.io.drained.expect(true.B);d.step()}
        dut.io.in.valid.poke(false.B)
      }
      d.bind(Tile(0));d.run(Vector(Work(0,true)))
    }
  }
  it should "drain every accepted quad packet through sticky cancellation and rebind only after complete drain" in {
    simulate(new TypedSamplePacketExpander){dut=>val d=new Driver(dut);d.bind(Tile(2));dut.io.out.ready.poke(false.B)
      val expected=mutable.Queue.empty[Expected];var sent=0;var cycles=0
      while(cycles<16){d.offer(Work(sent,true));if(dut.io.in.ready.peek().litToBoolean){expected ++= d.expected(Work(sent,true));sent+=1};d.step();cycles+=1}
      assert(sent==2);dut.io.out.valid.expect(true.B);val held=dut.io.out.bits.peek().litValue
      dut.io.cancel.poke(true.B);dut.io.bind.valid.poke(true.B)
      for(_<-0 until 8){dut.io.in.ready.expect(false.B);dut.io.bind.ready.expect(false.B);d.step();assert(dut.io.out.bits.peek().litValue==held)}
      dut.io.bind.valid.poke(false.B);dut.io.cancel.poke(false.B);dut.io.in.valid.poke(false.B)
      var cancelled=0;var sawCancelled=false;var steps=0
      while(expected.nonEmpty && steps<100){
        dut.io.in.ready.expect(false.B);val ready=steps%3!=0;dut.io.out.ready.poke(ready.B)
        if(dut.io.out.valid.peek().litToBoolean){
          d.check(expected.front);val c=dut.io.out.bits.cancelled.peek().litToBoolean
          if(sawCancelled)assert(c);if(c)sawCancelled=true
          if(ready){if(c)cancelled+=1;expected.dequeue()}
        }
        d.step();steps+=1
      }
      assert(expected.isEmpty && cancelled>0);dut.io.drained.expect(true.B)
      d.bind(Tile(0,3,-32,48));d.run(Vector(Work(1000,true)),dense=true)
    }
  }
}
