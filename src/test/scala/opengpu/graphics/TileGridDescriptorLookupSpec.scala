package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileGridDescriptorLookupSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private def initialize(d: TileGridDescriptorLookup): Unit = {
    d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U)
    d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
    d.io.grid.poke(0.U.asTypeOf(d.io.grid))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def bind(d: TileGridDescriptorLookup,w: Int,h: Int,edge: Int=16,
    x: Int=0,y: Int=0,ew: Int=0,eh: Int=0): (Int,Int) = {
    val width=if (ew==0) w-x else ew; val height=if (eh==0) h-y else eh
    pokeBinding(d.io.grid)
    d.io.grid.screenW.poke(w.U); d.io.grid.screenH.poke(h.U)
    d.io.grid.extentW.poke(width.U); d.io.grid.extentH.poke(height.U)
    d.io.grid.attachments.originX.poke(x.U); d.io.grid.attachments.originY.poke(y.U)
    d.io.grid.attachments.tileLog2.poke((if (edge==8) 3 else 4).U)
    val columns=(width+edge-1)/edge; val rows=(height+edge-1)/edge
    d.io.grid.replay.bins.tileCount.poke((columns*rows).U)
    (columns,rows)
  }
  private def offer(d: TileGridDescriptorLookup,tile: Long): Unit = {
    d.io.in.bits.poke(tile.U); d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
    d.clock.step(); d.io.in.valid.poke(false.B)
  }
  private def consume(d: TileGridDescriptorLookup): Unit = {
    d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
    d.io.busy.expect(false.B); d.io.in.ready.expect(true.B)
  }

  for (edge <- Seq(8,16)) {
    it should s"regenerate every tile in a complete 720p grid with edge=$edge" in {
      simulate(new TileGridDescriptorLookup) { d=>
        initialize(d); val (cols,rows)=bind(d,1280,720,edge)
        for (tile <- 0 until cols*rows) {
          offer(d,tile); d.clock.step(21)
          d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(0.U)
          val t=d.io.out.bits.descriptor
          t.originX.expect(((tile%cols)*edge).U); t.originY.expect(((tile/cols)*edge).U)
          t.tileWidth.expect(edge.U); t.tileHeight.expect(edge.U)
          d.io.out.bits.tile.expect(tile.U)
          t.identity.context.expect(3.U); t.identity.pass.expect(7.U); t.identity.generation.expect(11.U)
          consume(d)
        }
        info(s"GRID_LOOKUP edge=$edge columns=$cols rows=$rows descriptors=${cols*rows}")
      }
    }
  }

  it should "snapshot partial nonzero-origin grids and hold complete descriptors under backpressure" in {
    simulate(new TileGridDescriptorLookup) { d=>
      initialize(d)
      for (tile <- 0 until 6) {
        val (cols,_)=bind(d,64,64,8,8,16,21,13)
        offer(d,tile); d.io.grid.poke(0.U.asTypeOf(d.io.grid)); d.clock.step(21)
        val t=d.io.out.bits.descriptor
        d.io.out.bits.status.expect(0.U)
        t.originX.expect((8+(tile%cols)*8).U); t.originY.expect((16+(tile/cols)*8).U)
        t.tileWidth.expect((if (tile%cols==2) 5 else 8).U)
        t.tileHeight.expect((if (tile/cols==1) 5 else 8).U)
        t.color.base.expect(colorBase.U); t.depth.clearValue.expect(depthClear.U)
        val held=d.io.out.bits.peek().litValue
        d.io.cancel.poke(true.B); d.clock.step(4)
        assert(d.io.out.bits.peek().litValue==held)
        d.io.cancel.poke(false.B); consume(d)
      }
    }
  }

  it should "handle wide column counts without truncating the last legal tile" in {
    simulate(new TileGridDescriptorLookup) { d=>
      initialize(d); val (cols,rows)=bind(d,65520,256)
      for (tile <- Seq(0,cols-1,cols,cols*rows-1)) {
        offer(d,tile); d.clock.step(21); d.io.out.bits.status.expect(0.U)
        d.io.out.bits.descriptor.originX.expect(((tile%cols)*16).U)
        d.io.out.bits.descriptor.originY.expect(((tile/cols)*16).U); consume(d)
      }
    }
  }

  it should "reject malformed shapes, wrapped extents and out-of-range ordinals" in {
    simulate(new TileGridDescriptorLookup) { d=>
      initialize(d)
      val cases=Seq[(Long,TileBinRenderPassDescriptor=>Unit)](
        (3600,_=>()),(0xffffffffL,_=>()),(0,_.extentW.poke(0.U)),
        (0,_.replay.bins.tileCount.poke(1.U)),(0,_.attachments.tileLog2.poke(5.U)),
        (0,b=>{ b.attachments.originX.poke(65520.U); b.extentW.poke(128.U) }))
      for ((tile,mutate) <- cases) {
        bind(d,1280,720); mutate(d.io.grid); offer(d,tile); d.clock.step(21)
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(PrimitiveBinStatus.invalidDescriptor.U)
        consume(d)
      }
    }
  }

  it should "complete cancellations during division and permit an unrelated next lookup" in {
    simulate(new TileGridDescriptorLookup) { d=>
      initialize(d)
      for (cancel <- Seq(true,false)) {
        bind(d,1280,720); offer(d,3599); d.clock.step(8)
        d.io.cancel.poke(cancel.B); d.clock.step(); d.io.cancel.poke(false.B); d.clock.step(21)
        d.io.out.valid.expect(true.B)
        d.io.out.bits.status.expect((if (cancel) PrimitiveBinStatus.cancelled else 0).U)
        consume(d)
      }
    }
  }
}
