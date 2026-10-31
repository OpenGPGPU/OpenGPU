package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class WindowPrimitiveBinReplayPassSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=20,screenHeight=17,subPixelBits=8)
  private def design=new WindowPrimitiveBinReplayPass(p=p,g=g,engines=2,pageBytes=128,writeCredits=2,tileSlots=2,pagedCapacity=16)
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
      a.arena.limit.poke((0x10000+d.recordBytes*32).U);a.screenWidth.poke(20.U);a.screenHeight.poke(17.U);a.tileLog2.poke(3.U)
      b.version.poke(2.U);b.tileCount.poke(9.U);b.directoryBase.poke(0x80000.U);b.directoryLimit.poke((0x80000+9*64).U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x40000.U);b.pool.limit.poke(0x44000.U);b.pool.pageSizeLog2.poke(7.U)
    }
    def submitBegin(): Unit={d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)}
    def begin(poolPages: Int=128): Unit={expected.clear();outputs.clear();tiles.clear();prepareBegin()
      d.io.begin.bits.bins.pool.limit.poke((0x40000+128*poolPages).U)
      submitBegin();until(d.io.in.ready.peek().litToBoolean)}
    def send(seq: Int,kind: Int,coords: Seq[(Int,Int)],guard: Boolean=false,size: BigInt=0): Unit={
      val r=d.io.in.bits;fill(r,seq+73);zero(r.raster);r.raster.version.poke(1.U);r.raster.pointSize.poke(size.U)
      r.sequence.poke(seq.U);r.state.poke((0x90000000L+seq).U);r.token.poke((0xa0000000L+seq).U)
      r.geometry.valid.poke((seq%2==0).B)
      if(seq%2==0){r.geometry.bits.inputPrimitiveId.poke((0xf0000000L+seq).U)
        r.geometry.bits.invocationId.poke((63-seq%64).U);r.geometry.bits.layer.poke((0x80000000L+seq).U)}
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
    def healthy(): Unit={begin();send(0,0,Seq((1,1)));sourceIdle();end();result(0);assert(outputs.map(x=>(x._1,x._2)).toSeq==Seq((0,0)))}
  }
  behavior of "WindowPrimitiveBinReplayPass"
  it should "retain outer renderer credits through publication and return cancelled tiles after credits resume" in {
    simulate(new WindowPrimitiveBinReplayPass(p=p,g=g,engines=2,pageBytes=128,writeCredits=2,
      tileSlots=2,pagedCapacity=16,rendererCredits=true)){d=>val t=new Driver(d)
      t.prepareBegin();d.io.begin.bits.bins.version.poke(3.U);t.submitBegin();t.sourceIdle()
      t.send(0,2,Seq((0,0),(1,0),(0,1)),guard=true);t.sourceIdle();t.end()
      for(_<-0 until 80)t.tick()
      assert(t.outputs.isEmpty && t.tiles.isEmpty);d.io.completion.valid.expect(false.B)
      d.io.rendererPermit.get(1).poke(true.B);t.until(t.tiles.size>=3)
      assert(t.outputs.size==3 && t.outputs.forall(_._3==1));assert(t.tiles.forall(_._2==1))
      d.io.rendererPermit.get(1).poke(false.B)
      for(_<-0 until 80)t.tick()
      assert(t.tiles.size==3 && t.outputs.size==3);d.io.completion.valid.expect(false.B)
      d.io.rendererPermit.get(0).poke(true.B);t.result(0)
      assert(t.tiles.size==9 && t.outputs.size==9)
      assert(t.tiles.count(_._2==0)==6 && t.tiles.count(_._2==1)==3)
      assert(t.outputs.map(_._1).toSet==(0 until 9).toSet)

      for(e<-0 until 2)d.io.rendererPermit.get(e).poke(false.B)
      t.begin();t.send(0,0,Seq((1,1)));t.sourceIdle();t.end();d.io.cancel.poke(true.B)
      for(_<-0 until 20){t.tick();d.io.completion.valid.expect(false.B)}
      assert(t.tiles.isEmpty && t.outputs.isEmpty)
      for(e<-0 until 2)d.io.rendererPermit.get(e).poke(true.B)
      t.result(4);d.io.cancel.poke(false.B);t.healthy()
      info("WINDOW_BIN_RENDERER_CREDITS tiles=9 heldEngine0=true engine1Tiles=3 withheldReuse=true cancelDrainAfterCreditReturn=true healthyReuse=true")
    }
  }
  it should "replay complete generic records in per-tile order through two independent engines and a paged directory" in {
    simulate(design){d=>val t=new Driver(d);t.begin()
      t.send(0,2,Seq((1,1),(18,1),(1,16)));t.sourceIdle()
      t.send(1,1,Seq((8,-4),(8,12)));t.sourceIdle()
      t.send(2,0,Seq((15,8)),size=BigInt("40800000",16));t.sourceIdle()
      t.send(3,2,Seq((-20,-20),(-10,-20),(-10,-10)));t.sourceIdle()
      t.send(4,1,Seq((0,0),(1,1)),guard=true);t.sourceIdle()
      t.holdEngine0=true;t.end();t.until(t.tiles.count(_._2==1)>=3)
      assert(d.io.out(0).valid.peek().litToBoolean);assert(t.outputs.exists(_._3==1));assert(!t.outputs.exists(_._3==0))
      d.io.completion.valid.expect(false.B);t.holdEngine0=false;t.until(d.io.completion.valid.peek().litToBoolean)
      val expected=Seq((0,0 until 9),(1,Seq(0,1,3,4)),(2,Seq(1,2,4,5)),(4,0 until 9)).flatMap{case(seq,tiles)=>tiles.map(tile=>(tile,seq))}.toSet
      assert(t.outputs.map(x=>(x._1,x._2)).toSet==expected);assert(t.outputs.size==26);assert(t.tiles.size==9)
      assert(t.tiles.forall(t=>t._3==0 && t._4==0));d.io.completion.bits.producer.stored.expect(4.U)
      d.io.completion.bits.producer.references.expect(26.U);d.io.completion.bits.resolved.expect(26.U)
      d.io.completion.bits.recordReadLines.expect((26*d.recordBytes/64).U)
      assert(t.memory.seen.count(r=>r.write && r.address>=0x10000 && r.address<0x40000)==4*d.recordBytes/64)
      assert(t.memory.seen.map(_.tag).toSet.contains(BigInt(7)))
      t.result(0)
      info("WINDOW_BIN_REPLAY fullVaryings=16 records=4 references=26 tiles=9 engines=2 pagedSlots=2 independentProgress=true exactPayload=true")
    }
  }
  it should "reject overlapping arenas and mismatched grids or identities before memory traffic" in {
    simulate(design){d=>val t=new Driver(d)
      for(n<-0 until 4){t.prepareBegin();n match{
        case 0=>d.io.begin.bits.producer.arena.base.poke(0x40000.U);d.io.begin.bits.producer.arena.limit.poke(0x44000.U)
        case 1=>d.io.begin.bits.bins.tileCount.poke(8.U)
        case 2=>d.io.begin.bits.bins.pool.identity.pass.poke(12.U)
        case 3=>d.io.begin.bits.bins.resume.poke(true.B)}
        t.submitBegin();t.result(1);assert(t.memory.seen.isEmpty)}
      t.healthy()
    }
  }
  it should "drain record store bin store and replay read faults and recover with exact data" in {
    simulate(design){d=>val t=new Driver(d)
      for(fault<-Seq("recordStore","binStore","binExhaust","recordRead","recordHeader","recordReference")){
        t.begin(poolPages=if(fault=="binExhaust")1 else 128);if(fault=="recordStore")t.memory.faultAt=Set(BigInt(0x10040))
        if(fault=="binStore")t.memory.faultAt=Set(BigInt(0x40000))
        t.send(0,2,Seq((1,1),(18,1),(1,16)))
        if(fault=="recordRead" || fault=="recordHeader" || fault=="recordReference"){
          t.sourceIdle()
          if(fault=="recordRead")t.memory.faultAt=Set(BigInt(0x10040))
          else if(fault=="recordHeader")t.memory.putWord(0x10000,0)
          else {val state=WindowPrimitiveRecordLayout.fields(d.io.in.bits).find(_.name=="state").get
            t.memory.putWord(0x10000+state.word*4,0)}
          t.end()
        }
        t.result(if(fault=="binStore" || fault=="binExhaust")5 else if(fault=="recordHeader" || fault=="recordReference")7 else 3)
        t.memory.faultAt=Set.empty;t.healthy()
      }
    }
  }
  it should "retain held memory offers and full-record outputs across cancel through tile and pass drain" in {
    simulate(design){d=>val t=new Driver(d);t.begin();t.memory.holdRequests=true;t.send(0,0,Seq((1,1)))
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.memoryRequest.valid.expect(true.B);d.io.completion.valid.expect(false.B)}
      t.memory.holdRequests=false;t.result(4);d.io.cancel.poke(false.B);t.healthy()
      t.begin();t.send(0,2,Seq((1,1),(18,1),(1,16)));t.sourceIdle();t.holdEngine0=true;t.end()
      t.until(d.io.out(0).valid.peek().litToBoolean);val held=d.io.out(0).bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.out(0).valid.expect(true.B);assert(d.io.out(0).bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      t.holdEngine0=false;t.holdTiles=true;t.until(d.io.tileDone.valid.peek().litToBoolean)
      for(_<-0 until 8){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdTiles=false;t.result(4);d.io.cancel.poke(false.B);t.healthy()
    }
  }
}
