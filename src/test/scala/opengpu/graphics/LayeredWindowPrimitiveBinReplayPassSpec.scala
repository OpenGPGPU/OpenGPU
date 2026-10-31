package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class LayeredWindowPrimitiveBinReplayPassSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=math.min(20,GraphicsTestResolution.width),screenHeight=math.min(17,GraphicsTestResolution.height),subPixelBits=8)
  private val layerTiles=((g.screenWidth+7)/8)*((g.screenHeight+7)/8)
  private def design=new WindowPrimitiveBinReplayPass(p=p,g=g,engines=2,pageBytes=128,writeCredits=2,tileSlots=2,pagedCapacity=64,layered=true)
  private class Driver(val d: WindowPrimitiveBinReplayPass) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val expected=mutable.Map.empty[Int,Map[String,BigInt]]
    val outputs=mutable.ArrayBuffer.empty[(Int,Int,Int)]
    val tiles=mutable.ArrayBuffer.empty[(Int,Int,Int,Int)]
    val held=Array.fill[Option[BigInt]](2)(None)
    var holdEngine0=false;var holdTiles=false;var checkPayload=true;var heldTile=Option.empty[BigInt]
    def tick(): Unit={
      memory.drive()
      for(e<-0 until 2){val o=d.io.out(e);o.ready.poke((!(e==0 && holdEngine0) && memory.cycle%5!=e).B)
        val valid=o.valid.peek().litToBoolean;val bits=if(valid || held(e).nonEmpty)o.bits.peek().litValue else BigInt(0)
        held(e).foreach(v=>assert(valid && bits==v,"held full-record replay changed"))
        held(e)=if(valid && !o.ready.peek().litToBoolean)Some(bits)else None
        if(valid && o.ready.peek().litToBoolean){val seq=o.bits.source.reference.sequence.peek().litValue.toInt
          val tile=o.bits.source.tile.peek().litValue.toInt
          if(checkPayload)assert(values(o.bits.record)==expected(seq),s"record payload tile=$tile seq=$seq")
          identityExpect(o.bits.source.identity)
          val prior=outputs.filter(_._1==tile).map(_._2);assert(prior.lastOption.forall(_<seq))
          outputs+=((tile,seq,e))
        }
      }
      d.io.tileDone.ready.poke((!holdTiles && memory.cycle%3!=0).B)
      val valid=d.io.tileDone.valid.peek().litToBoolean;val bits=if(valid || heldTile.nonEmpty)d.io.tileDone.bits.peek().litValue else BigInt(0)
      heldTile.foreach(v=>assert(valid && bits==v,"held tile result changed"))
      heldTile=if(valid && !d.io.tileDone.ready.peek().litToBoolean)Some(bits)else None
      if(valid && d.io.tileDone.ready.peek().litToBoolean){val t=d.io.tileDone.bits
        val tile=t.bins.tile.peek().litValue.toInt;val engine=t.bins.engine.peek().litValue.toInt
        assert(!d.io.out(engine).valid.peek().litToBoolean,"tile completed before its last record")
        tiles+=((tile,engine,t.bins.status.peek().litValue.toInt,t.recordStatus.peek().litValue.toInt))}
      memory.capture();d.clock.step()
    }
    def identityExpect(i: GraphicsWorkIdentity): Unit={i.context.expect(7.U);i.pass.expect(11.U);i.generation.expect(13.U)}
    def until(condition: =>Boolean,limit: Int=40000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"timeout n=$n outputs=${outputs.size} tiles=${tiles.size} memory=${memory.pending.size}")}
    def prepareBegin(): Unit={zero(d.io.begin.bits);val q=d.io.begin.bits;val b=q.bins;val a=q.producer
      a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity);a.arena.base.poke(0x10000.U)
      a.arena.limit.poke((0x10000+d.recordBytes*32).U);a.screenWidth.poke(g.screenWidth.U);a.screenHeight.poke(g.screenHeight.U);a.tileLog2.poke(3.U)
      b.version.poke(2.U);b.tileCount.poke((2*layerTiles).U);b.directoryBase.poke(0x80000.U);b.directoryLimit.poke((0x80000+2*layerTiles*64).U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x40000.U);b.pool.limit.poke(0x44000.U);b.pool.pageSizeLog2.poke(7.U)
    }
    def submitBegin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)}
    def begin(poolPages: Int=128): Unit={expected.clear();outputs.clear();tiles.clear();prepareBegin();d.io.layerCount.get.poke(2.U)
      d.io.begin.bits.bins.pool.limit.poke((0x40000+128*poolPages).U)
      submitBegin();d.io.layerCount.get.poke(3.U);until(d.io.in.ready.peek().litToBoolean)}
    def send(seq: Int,kind: Int,coords: Seq[(Int,Int)],guard: Boolean=false,size: BigInt=0,layer:BigInt=0,geometry:Boolean=true): Unit={
      val r=d.io.in.bits;fill(r,seq+73);zero(r.raster);r.raster.version.poke(1.U);r.raster.pointSize.poke(size.U)
      r.sequence.poke(seq.U);r.state.poke((0x90000000L+seq).U);r.token.poke((0xa0000000L+seq).U)
      r.geometry.valid.poke(geometry.B)
      if(geometry){r.geometry.bits.inputPrimitiveId.poke((0xf0000000L+seq).U)
        r.geometry.bits.invocationId.poke((63-seq%64).U);r.geometry.bits.layer.poke(layer.U)}
      else zero(r.geometry.bits)
      val m=r.primitive.clipped.primitive;m.kind.poke(kind.U);m.vertexCount.poke((kind+1).U);m.varyingSlots.poke(16.U)
      r.primitive.guardBandRequired.poke(guard.B)
      for(i<-0 until 3){val(x,y)=coords(i.min(coords.size-1));r.primitive.vertices(i).screenX.poke((BigInt(x)*256).S)
        r.primitive.vertices(i).screenY.poke((BigInt(y)*256).S);r.primitive.vertices(i).fixedValid.poke((!guard).B)
        m.vertices(i).pointSizeWritten.poke(false.B)}
      expected(seq)=values(r);d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
    }
    def sourceIdle(): Unit=until(d.io.in.ready.peek().litToBoolean)
    def end(): Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
    def result(status: Int): Unit={until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty);val bits=d.io.completion.bits.peek().litValue
      for(_<-0 until 5){tick();assert(d.io.completion.bits.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)}
    def healthy(): Unit={begin();send(0,0,Seq((math.min(1,g.screenWidth-1),math.min(1,g.screenHeight-1))));sourceIdle();end();result(0);assert(outputs.map(x=>(x._1,x._2)).toSeq==Seq((0,0)))}
  }
  it should "isolate layer bins and reject out-of-range layers before record stores" in {
    simulate(design){d=>val t=new Driver(d);t.begin()
      t.send(0,2,Seq((0,0),(1,0),(0,1)),guard=true,layer=0);t.sourceIdle()
      t.send(1,2,Seq((0,0),(1,0),(0,1)),guard=true,layer=1);t.sourceIdle()
      t.send(2,2,Seq((0,0),(1,0),(0,1)),guard=true,geometry=false);t.sourceIdle();t.end();t.result(0)
      val expected=(0 until layerTiles).map(tile=>(tile,0)).toSet ++
        (layerTiles until 2*layerTiles).map(tile=>(tile,1)).toSet ++ (0 until layerTiles).map(tile=>(tile,2)).toSet
      assert(t.outputs.map(x=>(x._1,x._2)).toSet==expected && t.outputs.size==3*layerTiles)
      assert(t.tiles.size==2*layerTiles && t.tiles.forall(x=>x._3==0 && x._4==0))
      t.begin();val before=t.memory.seen.count(r=>r.write && r.address>=0x10000 && r.address<0x40000)
      t.send(0,0,Seq((1,1)),layer=BigInt("ffffffff",16));t.result(1)
      assert(t.memory.seen.count(r=>r.write && r.address>=0x10000 && r.address<0x40000)==before)
      t.healthy()
      t.prepareBegin();d.io.layerCount.get.poke(0.U);t.submitBegin();t.result(1)
      t.prepareBegin();d.io.layerCount.get.poke(2.U);d.io.begin.bits.bins.tileCount.poke((2*layerTiles-1).U);t.submitBegin();t.result(1)
    }
  }
}
