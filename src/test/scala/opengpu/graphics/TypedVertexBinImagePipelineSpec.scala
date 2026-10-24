package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedVertexBinImagePipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.{fp,format,encode}
  private val cfg=GpuConfig(lanes=4,warps=2)
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  private val f=FragmentShaderConfig(2,4)
  private def design=new TypedVertexBinImagePipeline(cfg,p,f,SharedCuShaderConfig(2),
    g=GraphicsConfig(screenWidth=17,screenHeight=9),engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8)
  private class Driver(val d: TypedVertexBinImagePipeline) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val expected=mutable.Map.empty[BigInt,Int];val tiles=mutable.ArrayBuffer.empty[(Int,Int)]
    val names=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")
    val rgba=Vector(0.1,0.2,0.3,0.4).map(fp)
    var hold0=false;var heldTile=Option.empty[BigInt];var allowFailedTiles=false
    def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def tick(): Unit={
      memory.drive();d.io.shared.launch.valid.expect(false.B)
      assert(d.io.fragmentEvents.phaseWorkers.map(_.peek().litValue).sum==2,
        "two configured native binned fragment workers must be counted exactly once")
      for(e<-0 until 2){d.io.out(e).ready.poke(true.B);d.io.primitiveDone(e).ready.poke(true.B)
        d.io.out(e).valid.expect(false.B);d.io.primitiveDone(e).valid.expect(false.B)
        val q=d.io.tileDone(e);q.ready.poke((!(e==0 && hold0) && memory.cycle%4!=e).B)
        if(e==0){heldTile.foreach(b=>assert(q.valid.peek().litToBoolean && q.bits.peek().litValue==b))
          heldTile=if(q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean)Some(q.bits.peek().litValue)else None}
        if(q.valid.peek().litToBoolean && q.ready.peek().litToBoolean){
          if(!allowFailedTiles){q.bits.status.expect(0.U);q.bits.renderStarted.expect(true.B);q.bits.image.status.expect(0.U)}
          q.bits.image.accepted.expect(0.U)
          tiles+=((q.bits.source.bins.tile.peek().litValue.toInt,e))}
      }
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean,limit: Int=150000): Unit={var n=0;while(!condition && n<limit){tick();n+=1}
      assert(condition,s"typed binned image timeout tiles=$tiles requests=${memory.seen.size} pending=${memory.pending.size}")}
    def prepare(): Unit={
      val dsc=d.io.begin.bits;zero(dsc);expected.clear();tiles.clear();dsc.version.poke(1.U)
      val b=dsc.bins;val a=b.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+32*d.recordBytes).U)
      a.screenWidth.poke(17.U);a.screenHeight.poke(9.U);a.tileLog2.poke(3.U)
      b.bins.version.poke(3.U);b.bins.tileCount.poke(6.U);b.bins.directoryBase.poke(0x500000.U);b.bins.directoryLimit.poke(0x500180.U)
      val pool=b.bins.pool;pool.version.poke(1.U);identity(pool.identity);pool.base.poke(0x400000.U);pool.limit.poke(0x410000.U);pool.pageSizeLog2.poke(7.U)
      val s=dsc.states;s.version.poke(1.U);identity(s.identity);s.base.poke(0x8000.U);s.limit.poke(0x8400.U);dsc.scratchStride.poke(4096.U)
      for(e<-0 until 2){dsc.scratch(e).base.poke((0x100000+e*0x10000).U);dsc.scratch(e).limit.poke((0x102000+e*0x10000).U)}
      val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U)
      val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity);c.tileLog2.poke(3.U);c.tileWidth.poke(8.U);c.tileHeight.poke(8.U);c.reservedBytes.poke(4096.U)
      im.pixels.depth.format.poke(TypedDepthFormat.d32f.U);im.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U);im.pixels.depth.clearDepth.poke(fp(1).U)
      var offset=4096
      for(i<-0 until 4){val v=c.colors(i);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(i)).U);v.offset.poke(offset.U)
        v.channelMask.poke(15.U);v.loadOp.poke(AttachmentLoadOp.clear.U);v.clear.zip(rgba).foreach{case(a,b)=>a.poke(b.U)}
        offset+=((64*format(names(i)).bytes+63)& ~63)}
      for(resolve<-Seq(false,true);i<-0 until 5){
        val bytes=if(i==4)4 else format(names(i)).bytes;val alloc=BigInt((if(resolve)0x700000 else 0x600000)+i*0x10000)
        val base=alloc+(if(resolve)59 else 61);val pitch=17*bytes+7;val end=(base+9*pitch+63)& ~BigInt(63)
        val v=if(resolve){im.resolves(i).enable.poke(true.B);im.resolves(i).destination}else im.views(i)
        v.base.poke(base.U);v.limit.poke((base+8*pitch+17*bytes).U);v.allocationBase.poke(alloc.U);v.allocationLimit.poke(end.U)
        v.imageWidth.poke(17.U);v.imageHeight.poke(9.U);v.rowPitch.poke(pitch.U);v.readable.poke((!resolve).B);v.writable.poke(true.B);v.store.poke(true.B)
        for(a<-alloc until end){memory.bytes(a)=0xa7;expected(a)=0xa7}
        val packed=if(i==4)fp(1)else encode(names(i),rgba)._1
        for(y<-0 until 9;x<-0 until 17;j<-0 until bytes)expected(base+y*pitch+x*bytes+j)=((packed>>(j*8))&255).toInt
      }
    }
    def begin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)}
    def end(): Unit={until(d.io.end.ready.peek().litToBoolean);d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);tick();d.io.end.valid.poke(false.B)}
    def finish(status: Int): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U)
      if(status==0){r.tiles.expect(6.U);r.primitives.expect(0.U);r.source.draws.expect(0.U);r.source.bins.bins.tiles.expect(6.U)
        assert(tiles.size==6 && tiles.map(_._1).toSet==(0 until 6).toSet)
        assert(expected.forall{case(a,b)=>memory.bytes(a)==b},"clear source/resolve bytes or pitched guards differ")
        assert(!memory.seen.exists(q=>q.address>=0x8000 && q.address<0x8400),"empty tiles fetched fragment state")}
      val held=r.peek().litValue
      for(_<-0 until 6){tick();d.io.busy.expect(true.B);d.io.begin.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
  }
  behavior of "TypedVertexBinImagePipeline"
  it should "publish every empty frame tile including partial edges while another engine holds its result" in {
    simulate(design){d=>val t=new Driver(d);t.prepare();t.hold0=true;t.begin();t.end()
      t.until(t.tiles.count(_._2==1)>=3);assert(d.io.tileDone(0).valid.peek().litToBoolean)
      d.io.completion.valid.expect(false.B);t.hold0=false;t.finish(0)
      info("TYPED_VERTEX_BIN_IMAGE emptyFrame=true tiles=6 dimensions17x9=true engines=2 heldEngine0=true exactFourMRTDepth=true sourceResolveGuards=true noShaderTasks=true")
    }
  }
  it should "validate the last partial tile and scratch roles before any bin or image traffic" in {
    simulate(design){d=>val t=new Driver(d)
      for(n<-0 until 3){t.prepare();n match {
        case 0=>d.io.begin.bits.image.views(0).imageWidth.poke(16.U)
        case 1=>d.io.begin.bits.scratch(1).base.poke(0x100000.U);d.io.begin.bits.scratch(1).limit.poke(0x102000.U)
        case _=>d.io.begin.bits.states.base.poke(0x200000.U);d.io.begin.bits.states.limit.poke(0x200400.U)
      };val count=t.memory.seen.size;t.begin();t.finish(1);assert(t.memory.seen.size==count)}
      t.prepare();t.begin();t.end();t.finish(0)
    }
  }
  it should "retain a primary vertex input fault while cancelled bin reports and the held draw result drain" in {
    simulate(design){d=>val t=new Driver(d);t.prepare();t.allowFailedTiles=true;t.begin()
      t.until(d.io.draw.ready.peek().litToBoolean);val q=d.io.draw.bits;zero(q);q.version.poke(1.U)
      q.vertex.viewport.version.poke(1.U);q.vertex.viewport.extentX.poke(17.U);q.vertex.viewport.extentY.poke(9.U);q.vertex.viewport.farDepth.poke(fp(1).U)
      q.vertex.geometry.topology.poke(4.U);val v=q.vertex.geometry.vertex
      v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U)
      v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      v.scratch.base.poke(0x30000.U);v.scratch.limit.poke(0x301e0.U);v.scratch.allocationBase.poke(0x30000.U);v.scratch.allocationLimit.poke(0x30200.U)
      val in=v.input;in.version.poke(1.U);t.identity(in.identity);in.count.poke(3.U);in.instances.poke(1.U)
      val b=in.bindings(0);b.base.poke(0x4000.U);b.limit.poke(0x4030.U);b.allocationBase.poke(0x4000.U);b.allocationLimit.poke(0x4040.U);b.stride.poke(16.U)
      in.attributes(0).enabled.poke(true.B);in.attributes(0).components.poke(4.U);in.attributes(0).format.poke(VertexInputFormat.float32.U)
      q.raster.version.poke(1.U);q.raster.screenW.poke(17.U);q.raster.screenH.poke(9.U);q.raster.frontFaceCCW.poke(true.B)
      t.memory.faultAt=Set(BigInt(0x4000));d.io.draw.valid.poke(true.B);t.tick();d.io.draw.valid.poke(false.B)
      t.until(d.io.drawCompletion.valid.peek().litToBoolean);d.io.drawCompletion.bits.status.expect(3.U)
      val held=d.io.drawCompletion.bits.peek().litValue
      for(_<-0 until 8){t.tick();assert(d.io.drawCompletion.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      d.io.drawCompletion.ready.poke(true.B);t.tick();d.io.drawCompletion.ready.poke(false.B)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultStage.expect(2.U)
      d.io.completion.bits.source.status.expect(3.U);t.finish(3)
      t.memory.faultAt=Set.empty;t.allowFailedTiles=false;t.prepare();t.begin();t.end();t.finish(0)
      info("TYPED_VERTEX_BIN_IMAGE sourceInputFault=true cancelledTilesSecondary=true heldDrawRetained=true noShaderTask=true healthyReuse=true")
    }
  }
}
