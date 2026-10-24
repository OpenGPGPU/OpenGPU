package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedTypedSampleTileStoreSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,encode}
  private val p=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,streamingWordPorts=8)
  private val clearColor=Vector(fp(0.2),fp(0.4),fp(0.6),fp(1.0))
  private val source=Vector(fp(0.8),fp(0.1),fp(0.3),fp(0.5))
  private val blend=TypedBlendReference.State()
  private val wordMask=(BigInt(1)<<32)-1
  private def repeated(v: BigInt): BigInt=(v<<32)|v
  private class Driver(val d: SharedTypedSampleTileStore){
    var cycles=0;var refills=0;var sampleMode=0
    d.io.begin.valid.poke(false.B);d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.end.valid.poke(false.B);d.io.end.bits.poke(true.B)
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
    d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.maintenance.poke(false.B)
    d.io.memory.valid.poke(false.B);d.io.memory.bits.poke(0.U.asTypeOf(d.io.memory.bits));d.io.reply.ready.poke(false.B)
    d.io.refill.ready.poke(false.B);d.io.refillReply.valid.poke(false.B);d.io.refillReply.bits.poke(0.U.asTypeOf(d.io.refillReply.bits))
    d.io.depth.get.active.poke(false.B);d.io.depth.get.views.poke(0.U.asTypeOf(d.io.depth.get.views))
    for(r<-d.io.depth.get.request){r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))}
    d.io.depth.get.response.foreach(_.ready.poke(false.B))
    d.samples.active.poke(false.B);d.samples.bind.valid.poke(false.B)
    d.samples.bind.bits.poke(0.U.asTypeOf(d.samples.bind.bits));d.samples.in.valid.poke(false.B)
    d.samples.in.bits.poke(0.U.asTypeOf(d.samples.in.bits));d.samples.out.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def tick(): Unit={
      if(d.io.refill.valid.peek().litToBoolean && d.io.refill.ready.peek().litToBoolean)refills+=1
      d.clock.step();cycles+=1
    }
    def until(ready: => Boolean): Unit={var n=0;while(!ready && n<2000){tick();n+=1};assert(ready,s"shared tile stalled at $cycles")}
    def begin(load: Boolean=false,mode: Int=0,tileLog2: Int=3): Unit={
      sampleMode=mode
      val b=d.io.begin.bits;b.poke(0.U.asTypeOf(b));b.version.poke(1.U)
      val edge=1<<tileLog2;val reserve=4*(1<<(2*tileLog2+mode))
      b.tileLog2.poke(tileLog2.U);b.tileWidth.poke(edge.U);b.tileHeight.poke(edge.U)
      b.sampleMode.poke(mode.U);b.reservedBytes.poke(reserve.U)
      val c=b.colors(0);c.enabled.poke(true.B);c.format.poke(TypedColorFormat.id("RGBA8").U)
      c.offset.poke(reserve.U);c.channelMask.poke(15.U);c.loadOp.poke(1.U)
      c.clear.zip(clearColor).foreach{case(a,v)=>a.poke(v.U)}
      val views=d.io.depth.get.views;views.poke(0.U.asTypeOf(views))
      views(0).format.poke(TypedDepthFormat.d24s8.U);views(0).loadOp.poke((if(load)0 else 1).U)
      views(0).clearDepth.poke(fp(1.0).U);views(0).clearStencil.poke(5.U)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean)
      d.samples.eligible.expect((!load).B)
      val binding=d.samples.bind.bits;binding.poke(0.U.asTypeOf(binding));binding.version.poke(1.U)
      binding.colors.poke(b.peek());binding.depth.poke(views(0).peek());binding.stencil.poke(views(1).peek())
    }
    def enter(): Unit={
      d.samples.readyToEnter.expect(true.B);d.samples.active.poke(true.B)
      d.samples.bind.valid.poke(true.B);until(d.samples.bind.ready.peek().litToBoolean)
      tick();d.samples.bind.valid.poke(false.B);d.samples.supported.expect(true.B)
    }
    def leave(): Unit={d.samples.drained.expect(true.B);d.samples.active.poke(false.B);tick()}
    def quad(q: TypedTileColorQuad): Unit={
      q.poke(0.U.asTypeOf(q));q.quad.colorSlots.poke(1.U);q.quad.frontFacing.poke(true.B)
      q.quad.sampleMode.poke(sampleMode.U)
      TypedBlendReference.poke(q.blend,blend)
      for(l<-0 until 4){
        val covered=(1<<(1<<sampleMode))-1
        q.samples(l).poke(covered.U);val v=q.quad.lanes(l)
        v.x.poke((l%2).S);v.y.poke((l/2).S);v.coverageMask.poke(covered.U);v.colorMask.poke(1.U)
        v.depth.poke(fp(0.5).U);v.colors(0).zip(source).foreach{case(a,z)=>a.poke(z.U)}
      }
    }
    def offerNative(tag: Int,readonly: Boolean=false): Unit={
      val b=d.samples.in.bits;b.poke(0.U.asTypeOf(b));b.tag.poke(tag.U);quad(b.quad.color)
      val state=b.quad.state;state.version.poke(1.U);state.depthEnable.poke(true.B)
      state.depthWrite.poke((!readonly).B);state.depthFunction.poke(6.U);state.stencilEnable.poke((!readonly).B)
      for(f<-Seq(state.front,state.back)){
        f.function.poke(6.U);f.readMask.poke(255.U);f.writeMask.poke(255.U)
        f.fail.poke(5.U);f.depthFail.poke(5.U);f.depthPass.poke(5.U)
      }
      if(readonly)b.quad.color.quad.lanes.foreach(_.colorMask.poke(0.U))
      d.samples.in.valid.poke(true.B)
    }
    def native(tag: Int,readonly: Boolean=false,cancel: Boolean=false): Unit={
      offerNative(tag,readonly)
      until(d.samples.in.ready.peek().litToBoolean);tick();d.samples.in.valid.poke(false.B)
      if(cancel){for(_<-0 until 10)tick();d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)}
      until(d.samples.out.valid.peek().litToBoolean)
      val r=d.samples.out.bits;r.tag.expect(tag.U);r.last.expect(true.B);r.result.status.expect((if(cancel)4 else 0).U)
      if(!cancel){
        r.result.tested.expect(15.U);r.result.passing.expect(15.U);r.result.dsReads.expect(4.U)
        r.result.dsFills.expect((if(tag==0)2 else 0).U)
        r.result.depthWritten.expect((if(readonly)0 else 15).U)
        r.result.stencilWritten.expect((if(readonly)0 else 15).U)
        r.result.colorWritten.expect((if(readonly)0 else 15).U)
      }
      val held=r.peek().litValue
      for(_<-0 until 4){tick();assert(d.samples.out.bits.peek().litValue==held)
        if(cancel)d.io.completion.valid.expect(false.B)}
      d.samples.out.ready.poke(true.B);tick();d.samples.out.ready.poke(false.B)
      d.samples.drained.expect(true.B)
    }
    def read(address: Int,value: BigInt,dirty: Boolean): Unit={
      d.io.maintenance.poke(true.B);val b=d.io.memory.bits
      b.poke(0.U.asTypeOf(b));b.address.poke(address.U)
      d.io.memory.valid.poke(true.B);until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
      until(d.io.reply.valid.peek().litToBoolean)
      d.io.reply.bits.available.expect(true.B);d.io.reply.bits.fault.expect(false.B)
      d.io.reply.bits.data.expect(value.U);d.io.reply.bits.dirty.expect(dirty.B)
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);d.io.maintenance.poke(false.B)
    }
    def finish(status: Int=0): Unit={
      if(status==0){d.io.end.valid.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "share native clear updates, ordered aliases and generic color writes in one canonical SRAM" in {
    simulate(new SharedTypedSampleTileStore(p)){dut=>
      val d=new Driver(dut);d.begin();d.enter();d.native(0);d.native(1);d.leave()
      var color=encode("RGBA8",clearColor)._1
      for(_<-0 until 2)color=encode("RGBA8",TypedBlendReference.blend("RGBA8",source,color,blend))._1
      val z=TypedDepthReference.encode(fp(0.5),4)
      for(a<-Seq(0,4))d.read(a,repeated((BigInt(5)<<24)|z),dirty=true)
      for(a<-Seq(32,36))d.read(a,repeated(color),dirty=true)
      // The generic owner must gather the native result, not a private array
      // or the original virtual clear. Its output still holds normal quad order.
      d.quad(dut.io.in.bits);dut.io.in.valid.poke(true.B)
      d.until(dut.io.in.ready.peek().litToBoolean);d.tick();dut.io.in.valid.poke(false.B)
      d.until(dut.io.out.valid.peek().litToBoolean);dut.io.out.bits.status.expect(0.U)
      dut.io.out.ready.poke(true.B);d.tick();dut.io.out.ready.poke(false.B)
      color=encode("RGBA8",TypedBlendReference.blend("RGBA8",source,color,blend))._1
      for(a<-Seq(32,36))d.read(a,repeated(color),dirty=true)
      assert(d.refills==0);d.finish()
      info("SHARED_SAMPLE_TILE nativeAlias=true genericGather=true canonicalDirty=true exactImage=true singleArray=true")
    }
  }
  it should "sustain four complete native sample updates per clock through the shared policy at every MSAA mode" in {
    simulate(new SharedTypedSampleTileStore(p)){dut=>
      val d=new Driver(dut)
      for(mode<-0 to 2){
        d.begin(mode=mode,tileLog2=4);d.enter()
        def run(quads: Int,base: Int,cold: Boolean): (Int,Int)={
          var sent=0;var retired=0;var clocks=0;var offered= -1
          val replies=scala.collection.mutable.ArrayBuffer.empty[Int]
          val packets=quads<<mode
          dut.samples.out.ready.poke(true.B)
          while(retired<packets && clocks<packets*10+500){
            if(sent<quads){
              if(offered!=sent){
                d.offerNative(base+sent)
                for(l<-0 until 4){val lane=dut.samples.in.bits.quad.color.quad.lanes(l)
                  lane.x.poke(((sent%8)*2+l%2).S);lane.y.poke((((sent/8)%8)*2+l/2).S)}
                offered=sent
              }
            }else dut.samples.in.valid.poke(false.B)
            if(dut.samples.in.valid.peek().litToBoolean && dut.samples.in.ready.peek().litToBoolean)sent+=1
            if(dut.samples.out.valid.peek().litToBoolean){
              val r=dut.samples.out.bits
              r.tag.expect((base+(retired>>mode)).U);r.packet.expect((retired% (1<<mode)).U)
              r.last.expect((retired% (1<<mode)==(1<<mode)-1).B)
              r.result.status.expect(0.U);r.result.tested.expect(15.U);r.result.passing.expect(15.U)
              r.result.colorWritten.expect(15.U);r.result.depthWritten.expect(15.U);r.result.stencilWritten.expect(15.U)
              r.result.dsReads.expect(4.U);r.result.dsFills.expect((if(cold)2 else 0).U)
              replies+=clocks;retired+=1
            }
            d.tick();clocks+=1
          }
          assert(sent==quads && retired==packets);dut.samples.in.valid.poke(false.B);dut.samples.drained.expect(true.B)
          val longest=replies.foldLeft((0,0,-2)){case((best,n,last),now)=>val next=if(now==last+1)n+1 else 1;(best.max(next),next,now)}._1
          (clocks,longest)
        }
        val cold=run(64,1000,cold=true)
        assert(cold._2==(64<<mode),s"shared cold clear mode=$mode inserted sample retirement gaps: $cold")
        val hot=run(128,2000,cold=false)
        assert(hot._2==(128<<mode),s"shared native mode=$mode failed four complete sample updates per clock: $hot")
        d.leave()
        var color=encode("RGBA8",clearColor)._1
        for(_<-0 until 3)color=encode("RGBA8",TypedBlendReference.blend("RGBA8",source,color,blend))._1
        val depth=(BigInt(250)<<24)|TypedDepthReference.encode(fp(0.5),4)
        val words=128<<mode
        // Cross both rows of every quad and every native sample plane, plus
        // attachment boundaries; the lower SRAM service checks every word.
        val selected=(Vector(0,1,words-2,words-1)++Vector.tabulate(32)(i=>(i*37)%words)).distinct
        for(a<-selected){d.read(a,repeated(depth),dirty=true);d.read(words+a,repeated(color),dirty=true)}
        assert(d.refills==0);d.finish()
        info(s"SHARED_NATIVE_RATE mode=$mode coldQuads=64 coldCycles=${cold._1} coldLongestFourSampleRetirements=${cold._2} hotQuads=128 hotSamples=${512<<mode} hotCycles=${hot._1} longestFourSampleRetirements=${hot._2} canonicalValidDirty=true actualWriteACKs=true")
      }
    }
  }

  it should "materialize read-only clear words and drain cancellation before rebinding a cold-load fallback" in {
    simulate(new SharedTypedSampleTileStore(p)){dut=>
      val d=new Driver(dut);d.begin();d.enter();d.native(0,readonly=true);d.native(1,readonly=true);d.leave()
      for(a<-Seq(0,4))d.read(a,repeated(BigInt("05ffffff",16)),dirty=true)
      val clear=encode("RGBA8",clearColor)._1
      for(a<-Seq(32,36))d.read(a,repeated(clear),dirty=false)
      d.finish();d.begin();d.enter();d.native(2,cancel=true);d.leave();d.finish(4)
      d.begin(load=true);dut.samples.readyToEnter.expect(false.B)
      // A cold load retains generic refill ownership and its clean residency.
      val depth=dut.io.depth.get;depth.active.poke(true.B)
      depth.request(0).bits.address.poke(0.U);depth.request(0).bits.tag.poke(91.U)
      depth.request(0).valid.poke(true.B);dut.io.refill.ready.poke(true.B)
      d.until(dut.io.refill.valid.peek().litToBoolean);dut.io.refill.bits.address.expect(0.U)
      d.tick();dut.io.refill.ready.poke(false.B)
      val loaded=BigInt("123456789abcdef0",16)
      dut.io.refillReply.valid.poke(true.B);dut.io.refillReply.bits.data.poke(loaded.U)
      d.until(dut.io.refillReply.ready.peek().litToBoolean);d.tick();dut.io.refillReply.valid.poke(false.B)
      d.until(depth.request(0).ready.peek().litToBoolean);d.tick();depth.request(0).valid.poke(false.B)
      d.until(depth.response(0).valid.peek().litToBoolean)
      depth.response(0).bits.data.expect(loaded.U);depth.response(0).bits.fault.expect(false.B)
      depth.response(0).bits.initialized.expect(true.B)
      depth.response(0).ready.poke(true.B);d.tick();depth.response(0).ready.poke(false.B)
      d.until(depth.drained.peek().litToBoolean);depth.active.poke(false.B)
      d.read(0,loaded,dirty=false);assert(d.refills==1);d.finish()
      info("SHARED_SAMPLE_TILE readOnlyInit=true virtualColorClean=true cancelDrain=true healthyRebind=true coldLoadFallback=true")
    }
  }
}
