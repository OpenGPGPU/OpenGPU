package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class WindowPrimitiveBinProducerSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val p=VertexShaderStageConfig()
  private val g=GraphicsConfig(screenWidth=20,screenHeight=17,subPixelBits=8)
  private class Driver(val d: WindowPrimitiveBinProducer) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    case class Append(tile: BigInt,handle: BigInt,state: BigInt,sequence: BigInt,token: BigInt)
    val appends=mutable.ArrayBuffer.empty[Append]
    var pending=Option.empty[(Int,Append)];var offered=false;var holdAppend=false;var holdAck=false
    var rejectTile=Option.empty[BigInt];var corruptAck=false;var held=Option.empty[BigInt]
    def tick(): Unit={
      memory.drive();d.io.append.ready.poke((!holdAppend && memory.cycle%3!=0 && pending.isEmpty).B)
      if(pending.exists(_._1<=memory.cycle) && !holdAck)offered=true
      d.io.appended.valid.poke(offered.B)
      pending.foreach{case(_,a)=>val b=d.io.appended.bits;identity(b.identity);b.tile.poke(a.tile.U)
        b.reference.primitive.poke(a.handle.U);b.reference.state.poke(a.state.U);b.reference.sequence.poke(a.sequence.U)
        b.reference.token.poke((a.token+(if(corruptAck)1 else 0)).U)
        b.status.poke((if(rejectTile.contains(a.tile))PrimitiveBinStatus.exhausted else 0).U)}
      val valid=d.io.append.valid.peek().litToBoolean;val bits=d.io.append.bits.peek().litValue
      held.foreach(v=>assert(valid && bits==v,"bin offer changed under backpressure"))
      held=if(valid && !d.io.append.ready.peek().litToBoolean)Some(bits)else None
      if(valid && d.io.append.ready.peek().litToBoolean){
        assert(memory.pending.isEmpty && memory.reply.isEmpty,"reference escaped record acknowledgements")
        val a=d.io.append.bits;val r=a.reference
        val next=Append(a.tile.peek().litValue,r.primitive.peek().litValue,r.state.peek().litValue,r.sequence.peek().litValue,r.token.peek().litValue)
        appends+=next;pending=Some((memory.cycle+4,next))
      }
      if(offered && d.io.appended.ready.peek().litToBoolean){pending=None;offered=false}
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean,limit: Int=10000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"timeout at $n")}
    def begin(capacity: Int=32,base: BigInt=0x10000,width: Int=20,height: Int=17): Unit={
      zero(d.io.begin.bits);val b=d.io.begin.bits;b.version.poke(1.U);b.arena.version.poke(1.U);identity(b.arena.identity)
      b.arena.base.poke(base.U);b.arena.limit.poke((base+d.recordBytes*capacity).U);b.screenWidth.poke(width.U);b.screenHeight.poke(height.U);b.tileLog2.poke(3.U)
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.in.ready.peek().litToBoolean)
    }
    def prepare(sequence: Int,kind: Int,coordinates: Seq[(BigInt,BigInt)],guard: Boolean=false): Map[String,BigInt]={
      fill(d.io.in.bits,sequence+31);val r=d.io.in.bits;zero(r.raster);r.raster.version.poke(1.U)
      r.sequence.poke(sequence.U);r.state.poke((0x90000000L+sequence).U);r.token.poke((0xa0000000L+sequence).U)
      val m=r.primitive.clipped.primitive;m.kind.poke(kind.U);m.vertexCount.poke((kind+1).U);m.varyingSlots.poke(16.U)
      r.primitive.guardBandRequired.poke(guard.B)
      for(i<-0 until 3){val(x,y)=coordinates(i.min(coordinates.size-1));r.primitive.vertices(i).screenX.poke((x*256).S)
        r.primitive.vertices(i).screenY.poke((y*256).S);r.primitive.vertices(i).fixedValid.poke((!guard).B)
        m.vertices(i).pointSizeWritten.poke(false.B)}
      values(r)
    }
    def send(): Unit={d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
    def idle(): Unit=until(d.io.in.ready.peek().litToBoolean)
    def end(): Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
    def result(status: Int): Unit={until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      assert(memory.pending.isEmpty && memory.reply.isEmpty && pending.isEmpty)
      val bits=d.io.completion.bits.peek().litValue
      for(_<-0 until 4){tick();assert(d.io.completion.bits.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)}
  }
  behavior of "WindowPrimitiveBinProducer"
  it should "store each general primitive once before ordered conservative multi-tile fanout" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin()
      t.prepare(0,2,Seq((1,1),(18,1),(1,16)));t.send();t.idle()
      t.prepare(1,1,Seq((8,-4),(8,12)));t.send();t.idle()
      t.prepare(2,0,Seq((15,8)));d.io.in.bits.raster.pointSize.poke("h40800000".U);t.send();t.idle()
      t.prepare(3,2,Seq((-20,-20),(-10,-20),(-10,-10)));t.send();t.idle()
      t.prepare(4,1,Seq((0,0),(1,1)),guard=true);t.send();t.idle();t.end()
      val expected=Seq((0,0,0 until 9),(1,1,Seq(0,1,3,4)),(2,2,Seq(1,2,4,5)),(4,3,0 until 9))
        .flatMap{case(seq,handle,tiles)=>tiles.map(tile=>(BigInt(tile),BigInt(handle),BigInt(seq)))}
      val actual=t.appends.map(a=>(a.tile,a.handle,a.sequence)).toSeq
      assert(actual.size==expected.size)
      for(tile<-0 until 9)assert(actual.filter(_._1==tile)==expected.filter(_._1==tile),s"tile $tile lost primitive order")
      assert(t.appends.forall(a=>a.state==0x90000000L+a.sequence && a.token==0xa0000000L+a.sequence))
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.accepted.expect(5.U);d.io.completion.bits.stored.expect(4.U)
      d.io.completion.bits.references.expect(26.U);assert(t.memory.seen.size==4*d.recordBytes/64)
      t.result(0)
    }
  }
  it should "clip signed extreme bounds and scissors without losing boundary points or overflowing tile ids" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin()
      t.prepare(0,2,Seq((-(BigInt(1)<<54),0),(BigInt(1)<<54,0),(0,BigInt(1)<<54)))
      val r=d.io.in.bits.raster;r.scissorEnable.poke(true.B);r.scissorMinX.poke(8.U);r.scissorMinY.poke(8.U)
      r.scissorMaxX.poke(16.U);r.scissorMaxY.poke(16.U);t.send();t.idle()
      t.prepare(1,0,Seq((20,17)));d.io.in.bits.primitive.clipped.primitive.vertices(0).pointSizeWritten.poke(true.B)
      d.io.in.bits.primitive.clipped.primitive.vertices(0).pointSize.poke("h7f800000".U);t.send();t.idle();t.end()
      assert(t.appends.filter(_.sequence==0).map(_.tile).toSeq==Seq(BigInt(4)))
      assert(t.appends.filter(_.sequence==1).map(_.tile).toSeq==(0 until 9).map(BigInt(_)))
      t.result(0)
    }
  }
  it should "return the exact unpublished record on arena exhaustion and reject sequence wrap on reuse" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin(capacity=1)
      t.prepare(0,0,Seq((2,2)));t.send();t.idle()
      val retry=t.prepare(1,2,Seq((1,1),(18,1),(1,16)));t.send()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.restartValid.expect(true.B)
      assert(values(d.io.completion.bits.restart)==retry);assert(t.appends.size==1);assert(t.memory.seen.size==d.recordBytes/64);t.result(2)
      t.begin();t.prepare(Int.MaxValue,0,Seq((1,1)));t.send();t.idle()
      t.prepare(0,0,Seq((1,1)));t.send();t.result(1)
    }
  }
  it should "drain held bin offers and replies on cancel and reject mismatched or exhausted bin replies" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin();t.holdAppend=true
      t.prepare(0,2,Seq((1,1),(18,1),(1,16)));t.send();t.until(d.io.append.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);for(_<-0 until 6){t.tick();d.io.append.valid.expect(true.B);d.io.completion.valid.expect(false.B)}
      t.holdAck=true;t.holdAppend=false;t.until(t.appends.nonEmpty)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdAck=false;t.result(4);assert(t.appends.size==1);d.io.cancel.poke(false.B)
      t.begin();t.corruptAck=true;t.prepare(0,0,Seq((1,1)));t.send();t.result(7)
      t.corruptAck=false;t.begin();t.rejectTile=Some(BigInt(1));t.prepare(0,2,Seq((1,1),(18,1),(1,16)));t.send()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.binStatus.expect(PrimitiveBinStatus.exhausted.U)
      d.io.completion.bits.references.expect(1.U);d.io.completion.bits.restartValid.expect(false.B);t.result(5)
    }
  }
  it should "stop before bin publication on record memory faults and recover after cancellation during a store" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin();t.memory.faultAt=Set(BigInt(0x10040))
      t.prepare(0,0,Seq((1,1)));t.send();t.result(3);assert(t.appends.isEmpty)
      t.memory.faultAt=Set.empty;t.begin();t.memory.holdRequests=true;t.prepare(0,0,Seq((1,1)));t.send()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 6){t.tick();d.io.memoryRequest.valid.expect(true.B);d.io.completion.valid.expect(false.B)}
      t.memory.holdRequests=false;t.result(4);assert(t.appends.isEmpty);d.io.cancel.poke(false.B)
      t.begin();t.prepare(0,0,Seq((1,1)));t.send();t.idle();t.end();t.result(0);assert(t.appends.size==1)
    }
  }

  it should "merge an eight-record batch by tile and retain order across the following partial batch" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin()
      for(seq<-0 until 9){t.prepare(seq,2,Seq((0,0),(19,0),(0,16)));t.send()}
      t.end();t.until(d.io.completion.valid.peek().litToBoolean)
      val expected=(0 until 9).flatMap(tile=>(0 until 8).map(seq=>(BigInt(tile),BigInt(seq)))) ++
        (0 until 9).map(tile=>(BigInt(tile),BigInt(8)))
      assert(t.appends.map(a=>(a.tile,a.sequence)).toSeq==expected)
      assert(t.appends.forall(a=>a.handle==a.sequence && a.state==0x90000000L+a.sequence && a.token==0xa0000000L+a.sequence))
      d.io.completion.bits.accepted.expect(9.U);d.io.completion.bits.stored.expect(9.U)
      d.io.completion.bits.references.expect(81.U)
      assert(t.memory.seen.size==9*d.recordBytes/64);t.result(0)
    }
  }

  it should "flush an idle partial batch without waiting for another input or EOF" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin()
      t.prepare(0,2,Seq((0,0),(19,0),(0,16)));t.send()
      t.until(t.appends.size==9);t.idle();d.io.completion.valid.expect(false.B)
      t.prepare(1,0,Seq((1,1)));t.send();t.end();t.result(0)
      assert(t.appends.filter(_.tile==0).map(_.sequence).toSeq==Seq(BigInt(0),BigInt(1)))
      assert(t.appends.size==10)
    }
  }

  it should "finish every buffered reference before returning the exact record rejected by arena exhaustion" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin(capacity=3)
      for(seq<-0 until 3){t.prepare(seq,2,Seq((0,0),(19,0),(0,16)));t.send()}
      val rejected=t.prepare(3,2,Seq((0,0),(19,0),(0,16)));t.send()
      t.until({
        val terminal=d.io.completion.valid.peek().litToBoolean
        if(!terminal && t.appends.size<27)d.io.cancelSource.expect(false.B)
        terminal || t.appends.size==27
      })
      t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.restartValid.expect(true.B)
      assert(values(d.io.completion.bits.restart)==rejected)
      d.io.completion.bits.accepted.expect(4.U);d.io.completion.bits.stored.expect(3.U)
      d.io.completion.bits.references.expect(27.U)
      for(tile<-0 until 9)assert(t.appends.filter(_.tile==tile).map(_.sequence).toSeq==(0 until 3).map(BigInt(_)))
      assert(t.memory.seen.size==3*d.recordBytes/64);t.result(2)
    }
  }

  it should "discard unoffered buffered references while draining a cancelled later record store" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin()
      t.prepare(0,2,Seq((0,0),(19,0),(0,16)));t.send();t.idle()
      t.memory.holdRequests=true;t.prepare(1,0,Seq((1,1)));t.send()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B);d.io.memoryRequest.valid.expect(true.B)}
      t.memory.holdRequests=false;t.result(4);assert(t.appends.isEmpty)
      d.io.cancel.poke(false.B);t.begin();t.prepare(0,0,Seq((1,1)));t.send();t.end();t.result(0)
      assert(t.appends.size==1 && t.appends.head.handle==0)
    }
  }

  it should "invalidate an eight-record batch on a rejected tile without claiming a restartable prefix" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d);t.begin();t.rejectTile=Some(BigInt(1))
      for(seq<-0 until 8){t.prepare(seq,2,Seq((0,0),(19,0),(0,16)));t.send()}
      t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.references.expect(8.U);d.io.completion.bits.stored.expect(8.U)
      d.io.completion.bits.restartValid.expect(false.B);d.io.completion.bits.faultTile.expect(1.U)
      d.io.completion.bits.binStatus.expect(PrimitiveBinStatus.exhausted.U)
      assert(t.appends.map(a=>(a.tile,a.sequence)).toSeq==
        (0 until 8).map(seq=>(BigInt(0),BigInt(seq))) :+ (BigInt(1),BigInt(0)))
      t.result(5)
    }
  }

  it should "retain the single-record configuration without a vector-index alias" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g,batchEntries=1)){d=>val t=new Driver(d);t.begin()
      for(seq<-0 until 2){t.prepare(seq,2,Seq((0,0),(19,0),(0,16)));t.send()}
      t.end();t.result(0)
      val expected=(0 until 2).flatMap(seq=>(0 until 9).map(tile=>(BigInt(tile),BigInt(seq))))
      assert(t.appends.map(a=>(a.tile,a.sequence)).toSeq==expected)
    }
  }

  it should "store one boundary record and reach tile 65535 in the complete wide grid" in {
    // One triangle bound and one tile only: no full-frame raster or grid walk.
    simulate(new WindowPrimitiveBinProducer(p=p,g=g.copy(screenWidth=2048,screenHeight=2048))){d=>
      val t=new Driver(d);t.begin(width=2048,height=2048)
      t.prepare(0,2,Seq((2047,2047),(2047,2047),(2047,2047)));t.send();t.end();t.result(0)
      assert(t.appends.size==1 && t.appends.head.tile==65535 && t.appends.head.handle==0)
      assert(t.memory.seen.size==d.recordBytes/64)
    }
  }

  it should "cancel every bounds preparation cut before publishing a record or reference" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d)
      for(cut<-0 until 5){
        t.begin();t.prepare(0,2,Seq((0,0),(19,0),(0,16)));t.send()
        for(_<-0 until cut)t.tick()
        d.io.cancel.poke(true.B);t.result(4);d.io.cancel.poke(false.B)
        assert(t.appends.isEmpty && t.memory.seen.isEmpty)
      }
      t.begin();t.prepare(0,0,Seq((1,1)));t.send();t.end();t.result(0)
      assert(t.appends.size==1)
    }
  }

  it should "retain the last complete record below 4GiB before rejecting the next buffered input" in {
    simulate(new WindowPrimitiveBinProducer(p=p,g=g)){d=>val t=new Driver(d)
      val limit=BigInt(1)<<32;val base=limit-2*d.recordBytes
      t.begin(capacity=2,base=base)
      for(seq<-0 until 2){t.prepare(seq,0,Seq((1,1)));t.send()}
      val rejected=t.prepare(2,0,Seq((1,1)));t.send()
      t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.references.expect(2.U);d.io.completion.bits.stored.expect(2.U)
      assert(values(d.io.completion.bits.restart)==rejected)
      assert(t.memory.seen.forall(r=>r.address>=base && r.address<limit))
      assert(t.memory.seen.size==2*d.recordBytes/64);t.result(2)
    }
  }
}

/** Explicit local full-capacity component emission; no frame is rendered. */
object EmitNativeWindowBinTiming extends App {
  val n=NativeBinnedGraphicsConfig.fourEngine
  circt.stage.ChiselStage.emitSystemVerilogFile(new WindowPrimitiveBinReplayPass(
    config=opengpu.config.GpuConfig(l2Sets=512,l2StoreEntries=8),p=n.vertex,
    g=GraphicsConfig(screenWidth=1280,screenHeight=720),engines=n.engines,
    pageBytes=n.pageBytes,writeCredits=n.writeCredits,tileSlots=n.tileSlots,
    pagedCapacity=n.pagedCapacity,useDirectorySramBlackBoxes=true),
    Array("--target-dir",args(0)),
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
