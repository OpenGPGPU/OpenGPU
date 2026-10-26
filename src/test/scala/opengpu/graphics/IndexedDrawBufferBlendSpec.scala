package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Small resident images exercise real packing, destination reads and writes. */
class IndexedDrawBufferBlendSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TypedColorReference.{encode,format,fp}
  import TypedBlendReference.State

  private val constants=Vector(0.25,0.5,0.75,1.0).map(fp)
  private val states=Vector(State(enable=false),State(1,1,1,1,3,4),
    State(4,5,1,0),State(11,12,1,1,0,1)).map(_.copy(constant=constants))
  private def control(s: State,mask: Int): BigInt=
    BigInt(if(s.enable)1 else 0)|(BigInt(s.src)<<1)|(BigInt(s.dst)<<5)|
      (BigInt(s.sa)<<9)|(BigInt(s.da)<<13)|(BigInt(s.eq)<<17)|(BigInt(s.ea)<<20)|(BigInt(mask)<<23)
  private val names=Vector("RGBA8","RGB565","RGBA8UI","SRGB8_ALPHA8")
  private val offsets=Vector(0,256,384,640)
  private val clear=Vector(0.25,0.5,0.75,1.0).map(fp)
  private val source=Vector(0.75,0.25,0.5,0.5).map(fp)
  private def values(c: Int,initial: Boolean): Vector[BigInt]=
    if(c==2)(if(initial)Vector(7,11,13,17)else Vector(101,103,107,109)).map(BigInt(_))
    else if(initial)clear else source

  private class Driver(val d: TypedTileColorStore) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val expected=mutable.Map.empty[Int,Int]
    def tick(): Unit={d.io.refill.valid.expect(false.B);d.clock.step()}
    def until(done: =>Boolean): Unit={var n=0;while(!done && n<100000){tick();n+=1};assert(done,s"indexed blend timeout after $n clocks")}
    def begin(mode: Int): Unit={
      val b=d.io.begin.bits;zero(b);b.version.poke(1.U);b.tileLog2.poke(3.U)
      b.tileWidth.poke(8.U);b.tileHeight.poke(8.U);b.sampleMode.poke(mode.U)
      expected.clear()
      for(c<-0 until 4){val v=b.colors(c);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(c)).U)
        // 4x uses a fresh 4 KiB slot; each image retains an aligned, disjoint view.
        val base=offsets(c)*(1<<mode);v.offset.poke(base.U);v.channelMask.poke(15.U);v.loadOp.poke(1.U)
        v.clear.zip(values(c,true)).foreach{case(a,x)=>a.poke(x.U)}
        val bytes=format(names(c)).bytes;val packed=encode(names(c),values(c,true))._1
        for(i<-0 until 64*(1<<mode)*bytes)expected(base+i)=((packed>>(8*(i%bytes)))&255).toInt
      }
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean)
    }
    def packet(mode: Int,rotation: Int,invalid: Option[BigInt]=None): Unit={
      val b=d.io.in.bits;zero(b);b.blend.version.poke(2.U)
      b.blend.constant.zip(constants).foreach{case(a,x)=>a.poke(x.U)}
      val masks=Vector(15,5,10,if(rotation==0)8 else 0)
      for(c<-0 until 4)b.blend.buffers(c).poke(control(states((c+rotation)%4),masks(c)).U)
      invalid.foreach(w=>b.blend.buffers(3).poke(w.U))
      val q=b.quad;q.colorSlots.poke(4.U);q.sampleMode.poke(mode.U)
      for(c<-0 until 4)q.colorTypes(c).poke(format(names(c)).kind.U)
      for(l<-0 until 4){val lane=q.lanes(l);lane.x.poke((l%2).S);lane.y.poke((l/2).S)
        lane.coverageMask.poke(((1<<(1<<mode))-1).U);lane.colorMask.poke(15.U);b.samples(l).poke(((1<<(1<<mode))-1).U)
        for(c<-0 until 4){lane.colors(c).zip(values(c,false)).foreach{case(a,x)=>a.poke(x.U)}
          if(invalid.isEmpty)for(sample<-0 until (1<<mode)){
            val bytes=format(names(c)).bytes;val address=offsets(c)*(1<<mode)+((l/2*8+l%2)*(1<<mode)+sample)*bytes
            val old=(0 until bytes).foldLeft(BigInt(0))((v,i)=>v|(BigInt(expected(address+i))<<(8*i)))
            val blended=TypedBlendReference.blend(names(c),values(c,false),old,states((c+rotation)%4))
            val (packed,mask)=encode(names(c),blended,masks(c));val next=(old& ~mask)|(packed&mask)
            for(i<-0 until bytes)expected(address+i)=((next>>(8*i))&255).toInt
          }
        }
      }
    }
    def submit(status: Int=0): Unit={
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      // Mutating the producer after acceptance must not change the owned state.
      d.io.in.bits.blend.buffers.foreach(_.poke(0.U));d.io.in.bits.blend.constant.foreach(_.poke(0.U))
      until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.status.expect(status.U)
      if(status!=0)d.io.out.bits.wordWrites.expect(0.U)
      val held=d.io.out.bits.peek().litValue;for(_<-0 until 5){tick();assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
    }
    def verify(): Unit={
      for(address<-expected.keys.map(_/8).toSet.toVector.sorted){
        d.io.maintenance.poke(true.B);d.io.memory.valid.poke(true.B);d.io.memory.bits.address.poke(address.U)
        until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
        until(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.available.expect(true.B);d.io.reply.bits.fault.expect(false.B)
        val word=(0 until 8).foldLeft(BigInt(0))((v,i)=>v|(BigInt(expected.getOrElse(address*8+i,0))<<(i*8)))
        assert(d.io.reply.bits.data.peek().litValue==word,s"indexed stored word $address differs")
        d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);d.io.maintenance.poke(false.B)
      }
    }
    def finish(status: Int=0): Unit={
      if(status==0){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }

  for((parallel,ports)<-Seq((false,4),(true,4),(true,8)))
  it should s"retain independent indexed MRT blend and masks with packed neighbors parallel=$parallel ports=$ports" in {
    simulate(new TypedTileColorStore(TypedTileColorConfig(bytes=4096,parallelLanes=parallel,streamingWordPorts=ports))){d=>
      val t=new Driver(d)
      for(mode<-Seq(0,2)){
        t.begin(mode)
        for(rotation<-0 until 2){t.packet(mode,rotation);t.submit();t.verify()}
        t.finish()
      }
      // Malformed unused/disabled controls must fail before any color write.
      for(word<-Seq(BigInt(1)<<31,BigInt(15)<<1,BigInt(5)<<20)){
        t.begin(0);t.packet(0,0,Some(word));t.submit(6);t.finish(6)
      }
      t.begin(0);t.packet(0,1);t.submit();t.verify();t.finish()
      info(s"INDEXED_MRT parallel=$parallel ports=$ports buffers=4 sampleModes1And4=true formats=4 integerBypass=true sRGBLinear=true masksAndPackedNeighbors=true sharedConstants=true snapshotAndHeldResult=true invalidBeforeWrites=3 healthyReuse=true")
    }
  }

  it should "resolve buffer zero before the native sample packet path and retain its mask under stalls" in {
    simulate(new TypedSamplePacketExpander){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val b=d.io.bind.bits;b.version.poke(1.U);b.colors.version.poke(1.U);b.colors.tileLog2.poke(3.U)
      b.colors.tileWidth.poke(8.U);b.colors.tileHeight.poke(8.U);b.colors.sampleMode.poke(2.U)
      b.colors.colors(0).enabled.poke(true.B);b.colors.colors(0).format.poke(TypedColorFormat.id("RGBA8").U)
      b.colors.colors(0).channelMask.poke(7.U);b.depth.format.poke(TypedDepthFormat.d24s8.U)
      d.io.bind.valid.poke(true.B);d.io.bind.ready.expect(true.B);d.clock.step();d.io.bind.valid.poke(false.B)
      val q=d.io.in.bits.quad;q.color.quad.sampleMode.poke(2.U);q.color.blend.version.poke(2.U)
      q.color.blend.buffers(0).poke(control(states(3),5).U);q.color.blend.constant.zip(constants).foreach{case(a,x)=>a.poke(x.U)}
      for(l<-0 until 4){q.color.quad.lanes(l).x.poke((l%2).S);q.color.quad.lanes(l).y.poke((l/2).S)
        q.color.quad.lanes(l).colorMask.poke(1.U);q.color.samples(l).poke(15.U)}
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      q.color.blend.buffers(0).poke(0.U)
      for(packet<-0 until 4){var n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
        d.io.out.valid.expect(true.B);d.io.out.bits.packet.expect(packet.U);d.io.out.bits.channelMask.expect(5.U)
        val state=d.io.out.bits.blend;state.enable.expect(true.B);state.version.expect(1.U)
        state.srcRGB.expect(11.U);state.dstRGB.expect(12.U);state.equationAlpha.expect(1.U)
        state.buffers.foreach(_.expect(0.U));state.constant.zip(constants).foreach{case(a,x)=>a.expect(x.U)}
        val held=d.io.out.bits.peek().litValue;for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      d.io.drained.expect(true.B)
    }
  }
}
