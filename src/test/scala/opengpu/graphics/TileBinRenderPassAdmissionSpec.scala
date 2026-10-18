package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinRenderPassAdmissionSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private val cfg=GraphicsConfig(screenWidth=32,screenHeight=32,tileSize=16,tileAttachments=true)
  it should "prevalidate a disjoint partial 8x8 grid and preserve its attachment identity" in {
    simulate(new TileBinRenderPassAdmission(cfg,8,pageBytes=128)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      pokeBinding(d.io.in.bits,32,32,21,13,mode=1,originX=8,originY=16)
      d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      var cycles=0
      while (!d.io.out.valid.peek().litToBoolean && cycles<200) { d.clock.step(); cycles+=1 }
      d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(0.U)
      val covered=scala.collection.mutable.Set.empty[(Int,Int)]
      for (i <- 0 until 6) {
        val t=d.io.out.bits.tiles(i); val x=8+(i%3)*8; val y=16+(i/3)*8
        val w=if (i%3==2) 5 else 8; val h=if (i/3==1) 5 else 8
        t.originX.expect(x.U); t.originY.expect(y.U)
        t.tileWidth.expect(w.U); t.tileHeight.expect(h.U); t.sampleMode.expect(1.U)
        t.identity.context.expect(3.U); t.identity.pass.expect(7.U); t.identity.generation.expect(11.U)
        t.color.base.expect(colorBase.U); t.depth.base.expect(depthBase.U)
        for (py <- y until y+h; px <- x until x+w) assert(covered.add((px,py)))
      }
      assert(covered.size==21*13)
      val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 5) { d.clock.step(); assert(d.io.out.bits.peek().litValue==held) }
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      d.io.in.ready.expect(true.B)
    }
  }

  it should "reject malformed grids and memory aliases before accepting a new binding" in {
    simulate(new TileBinRenderPassAdmission(cfg,8,pageBytes=128)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mutations=Seq[TileBinRenderPassDescriptor=>Unit](
        b=>b.version.poke(2.U),b=>b.extentW.poke(0.U),b=>b.extentW.poke(33.U),
        b=>b.replay.bins.tileCount.poke(1.U),b=>b.attachments.originX.poke(1.U),
        b=>b.attachments.identity.pass.poke(8.U),b=>b.attachments.sampleMode.poke(3.U),
        b=>b.attachments.color.limit.poke((colorBase+4).U),
        b=>b.replay.records.base.poke(colorBase.U),
        b=>b.replay.bins.pool.base.poke(depthBase.U),
        b=>b.attachments.color.limit.poke((depthBase+64).U))
      for (mutate <- mutations) {
        pokeBinding(d.io.in.bits); mutate(d.io.in.bits)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        var n=0; while (!d.io.out.valid.peek().litToBoolean && n<200) { d.clock.step(); n+=1 }
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(1.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }

  it should "drain an in-flight tile check on cancellation and allow healthy reuse" in {
    simulate(new TileBinRenderPassAdmission(cfg,8,pageBytes=128)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (cancel <- Seq(true,false)) {
        pokeBinding(d.io.in.bits)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        d.clock.step(5); d.io.cancel.poke(cancel.B); d.clock.step(); d.io.cancel.poke(false.B)
        var n=0; while (!d.io.out.valid.peek().litToBoolean && n<200) { d.clock.step(); n+=1 }
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect((if (cancel) 2 else 0).U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }
  it should "prevalidate full 720p grids without retaining a per-tile descriptor table" in {
    val full=cfg.copy(screenWidth=1280,screenHeight=720)
    simulate(new TileBinRenderPassAdmission(full,4,pagedCapacity=16384,pageBytes=128)) { d=>
      assert(d.io.out.bits.tiles.length==0)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (edge <- Seq(8,16); mode <- Seq(0,2)) {
        pokeBinding(d.io.in.bits,1280,720,1280,720,mode=mode)
        val b=d.io.in.bits; val count=((1280+edge-1)/edge)*((720+edge-1)/edge)
        b.replay.bins.version.poke(2.U); b.replay.bins.tileCount.poke(count.U)
        b.replay.bins.directoryBase.poke(0x8000000L.U)
        b.replay.bins.directoryLimit.poke((0x8000000L+64L*count).U)
        b.attachments.tileLog2.poke((if (edge==8) 3 else 4).U)
        b.attachments.color.base.poke(0x1000000L.U)
        b.attachments.color.limit.poke((0x1000000L+1280L*720*4*(1<<mode)).U)
        b.attachments.depth.base.poke(0x2000000L.U)
        b.attachments.depth.limit.poke((0x2000000L+1280L*720*4*(1<<mode)).U)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.clock.step(200000)
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(0.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }

  it should "reject paged metadata aliases, short backing and last-tile image overflow before traffic" in {
    simulate(new TileBinRenderPassAdmission(cfg,2,programmable=true,pagedCapacity=32,pageBytes=128)) { d=>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val cases=Seq[TileBinRenderPassDescriptor=>Unit](
        _.replay.bins.directoryBase.poke((0x80000L+4).U),
        _.replay.bins.directoryLimit.poke((0x80000L+64).U),
        b=>{ b.replay.bins.directoryBase.poke(colorBase.U); b.replay.bins.directoryLimit.poke((colorBase+1024).U) },
        b=>{ b.replay.bins.directoryBase.poke(0x90000.U); b.replay.bins.directoryLimit.poke(0x90400.U) },
        _.attachments.color.limit.poke((colorBase+32*31*4).U))
      for (mutate <- cases) {
        pokeBinding(d.io.in.bits,32,32,32,32)
        val b=d.io.in.bits
        b.replay.bins.version.poke(2.U); b.replay.bins.directoryBase.poke(0x80000.U)
        b.replay.bins.directoryLimit.poke(0x80400.U)
        b.shaderBase.poke(0x90000.U); b.shaderLimit.poke(0x98000.U); b.shaderEngineStride.poke(0x4000.U)
        mutate(b)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(500)
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(1.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }

  it should "validate every spill segment and reject reserved-envelope aliases before tile admission" in {
    simulate(new TileBinRenderPassAdmission(cfg,8,pageBytes=128)) { d=>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val cases=Seq[(Int,TileBinRenderPassDescriptor=>Unit)](
        (0,_=>()),(1,_.replay.bins.pool.spillCount.poke(9.U)),
        (1,_.replay.bins.pool.spills(0).base.poke((binBase+128).U)),
        (1,_.replay.bins.pool.spills(0).limit.poke((binBase+0x180).U)),
        (1,_.replay.bins.pool.spills(7).base.poke((binBase+0x3000).U)),
        (1,_.replay.bins.pool.limit.poke((binBase+0x3000).U)),
        (1,b=>{b.attachments.color.base.poke((binBase+0x1000).U); b.attachments.color.limit.poke((binBase+0x1800).U)}))
      for ((status,mutate) <- cases) {
        pokeBinding(d.io.in.bits)
        val b=d.io.in.bits; val p=b.replay.bins.pool
        p.version.poke(2.U); p.initialLimit.poke((binBase+256).U); p.spillCount.poke(1.U)
        p.spills(0).base.poke((binBase+0x2000).U); p.spills(0).limit.poke((binBase+0x2100).U)
        p.limit.poke((binBase+0x2100).U); mutate(b)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.clock.step(200)
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(status.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }

}
