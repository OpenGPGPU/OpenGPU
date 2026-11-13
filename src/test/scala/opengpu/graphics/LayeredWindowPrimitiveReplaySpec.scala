package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class LayeredWindowPrimitiveReplaySpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  private val p=VertexShaderStageConfig()
  private val cfg=GpuConfig(lanes=4,warps=1)
  private class Driver(val d:LayeredWindowPrimitiveReplayPipeline) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val fields=WindowPrimitiveRecordLayout.fields(d.io.out.bits.replay.record)
    val recordBytes=WindowPrimitiveRecordLayout.lineCount(d.io.out.bits.replay.record)*64
    var layers=256;var empty=false;var holdBegin=false;var holdOutput=false;var holdEnd=false;var holdImage=false
    var current=Option.empty[Int];var pending=Option.empty[(Int,Int)];var busyTail=0;var layerAccepted=0
    var begins=0;var images=0;val observed=mutable.ArrayBuffer.empty[(Int,Int,Int)]
    var recordLayers=Vector(0,255,0,1);var wide=false
    var expectedChain=BigInt(0)
    var heldRecord=Option.empty[BigInt];var heldBegin=Option.empty[BigInt];var heldEnd=false
    def identity(x:GraphicsWorkIdentity):Unit={x.context.poke(7.U);x.pass.poke(8.U);x.generation.poke(9.U)}
    def install(badLayer:Boolean=false,wideData:Boolean=false,oneLayer:Boolean=false):Unit={
      wide=wideData;recordLayers=if(oneLayer)Vector.fill(4)(0)else Vector(0,255,if(badLayer)256 else 0,1)
      for(h<-0 until 4){
        val base=BigInt(0x200000+h*recordBytes)
        for(w<-0 until recordBytes/4)memory.putWord(base+w*4,0)
        val layer=recordLayers(h)
        val header=Vector[BigInt](WindowPrimitiveRecordLayout.magic,recordBytes,if(h==0)1 else 2,16,7,8,9,h,
          if(h==0)0 else 100+h,if(h==0)0 else h,layer)
        for((word,i)<-header.zipWithIndex)memory.putWord(base+i*4,word)
        val values=Map[String,BigInt]("sequence"->(10*(h+1)),"state"->h,"token"->(200+h),
          "primitive.clipped.primitive.identity.context"->7,"primitive.clipped.primitive.identity.pass"->8,
          "primitive.clipped.primitive.identity.generation"->9,"primitive.clipped.primitive.primitiveId"->(93+h))++
          (if(wide)for(v<-0 until 3;s<-0 until 16;c<-0 until 4)yield
            s"primitive.clipped.primitive.vertices[$v].varyings[$s][$c]"->varying(h,v,s,c)else Seq.empty)
        for(f<-fields;w<-0 until f.words){
          val raw=values.getOrElse(f.name,BigInt(0))&((BigInt(1)<<(f.words*32))-1)
          memory.putWord(base+(f.word+w)*4,(raw>>(w*32))&0xffffffffL)
        }
      }
      val header=Vector[BigInt](PrimitiveBinPageLayout.magic,0,4,7,8,9,3,0)
      for((word,i)<-header.zipWithIndex)memory.putWord(0x400000+i*4,word)
      for(h<-0 until 4;(word,i)<-Vector[BigInt](h,h,10*(h+1),200+h).zipWithIndex)
        memory.putWord(0x400020+h*16+i*4,word)
    }
    private def varying(h:Int,v:Int,s:Int,c:Int):BigInt=BigInt(0x60000000L+h*0x1000000L+v*0x10000L+s*0x100L+c)
    def tick():Unit={
      memory.drive()
      if(d.io.cancelLayer.peek().litToBoolean && current.nonEmpty && pending.isEmpty)pending=Some((4,layerAccepted))
      d.io.layerBegin.ready.poke((!holdBegin && current.isEmpty && busyTail==0).B)
      d.io.layerBusy.poke((current.nonEmpty || busyTail>0).B)
      d.io.out.ready.poke((!holdOutput && memory.cycle%5!=0).B);d.io.layerEnd.ready.poke((!holdEnd).B)
      d.io.layerCompletion.valid.poke((pending.nonEmpty && !holdImage).B)
      zero(d.io.layerCompletion.bits);identity(d.io.layerCompletion.bits.identity);d.io.layerCompletion.bits.tile.poke(3.U)
      pending.foreach{case(status,count)=>
        d.io.layerCompletion.bits.status.poke(status.U);d.io.layerCompletion.bits.accepted.poke(count.U)
        d.io.layerCompletion.bits.issued.poke(count.U);d.io.layerCompletion.bits.completed.poke(count.U)
      }
      val begin=d.io.layerBegin.valid.peek().litToBoolean;val beginBits=d.io.layerBegin.bits.peek().litValue
      heldBegin.foreach(v=>assert(begin && beginBits==v,"held layer binding changed"))
      heldBegin=if(begin && !d.io.layerBegin.ready.peek().litToBoolean)Some(beginBits)else None
      if(begin && d.io.layerBegin.ready.peek().litToBoolean){
        assert(beginBits==begins,"layer scans did not remain contiguous")
        current=Some(beginBits.toInt);layerAccepted=0;begins+=1
      }
      val valid=d.io.out.valid.peek().litToBoolean;val bits=if(valid || heldRecord.nonEmpty)d.io.out.bits.peek().litValue else BigInt(0)
      heldRecord.foreach(v=>assert(valid && bits==v,"held selected record changed through cancellation"))
      heldRecord=if(valid && !d.io.out.ready.peek().litToBoolean)Some(bits)else None
      if(valid && d.io.out.ready.peek().litToBoolean){
        val r=d.io.out.bits;val h=r.original.reference.primitive.peek().litValue.toInt
        r.original.identity.context.expect(7.U);r.original.identity.pass.expect(8.U);r.original.identity.generation.expect(9.U)
        r.original.tile.expect(3.U);r.original.ordinal.expect(h.U)
        r.original.reference.sequence.expect((10*(h+1)).U);r.original.reference.state.expect(h.U);r.original.reference.token.expect((200+h).U)
        r.replay.source.ordinal.expect(layerAccepted.U)
        assert(r.replay.source.reference.peek().litValue==r.original.reference.peek().litValue)
        r.replay.record.sequence.expect((10*(h+1)).U);r.replay.record.primitive.clipped.primitive.primitiveId.expect((93+h).U)
        if(wide)for(v<-0 until 3;s<-0 until 16;c<-0 until 4)
          r.replay.record.primitive.clipped.primitive.vertices(v).varyings(s)(c).expect(varying(h,v,s,c).U)
        if(!d.io.cancelLayer.peek().litToBoolean){
          assert(current.contains(recordLayers(h)),"record reached another layer")
          observed+=((current.get,h,layerAccepted));layerAccepted+=1
        }
      }
      val end=d.io.layerEnd.valid.peek().litToBoolean
      if(heldEnd){assert(end);d.io.layerEnd.bits.expect(true.B)}
      heldEnd=end && !d.io.layerEnd.ready.peek().litToBoolean
      if(end && d.io.layerEnd.ready.peek().litToBoolean && pending.isEmpty && current.nonEmpty)pending=Some((0,layerAccepted))
      if(d.io.layerCompletion.valid.peek().litToBoolean && d.io.layerCompletion.ready.peek().litToBoolean){
        pending=None;current=None;busyTail=3;images+=1
      }
      assert(!memory.seen.lastOption.exists(_.write),"layer scan wrote source records")
      memory.capture();d.clock.step();if(busyTail>0)busyTail-=1
    }
    def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<200000){tick();n+=1}
      assert(condition,s"$label stalled cycle=${memory.cycle} begins=$begins images=$images refs=${observed.size}")}
    def start(count:Int=256,noReferences:Boolean=false,exhausted:Boolean=false):Unit={
      assert(current.isEmpty && pending.isEmpty && busyTail==0);layers=count;empty=noReferences
      begins=0;images=0;observed.clear();heldRecord=None;heldBegin=None;heldEnd=false
      val q=d.io.begin.bits;zero(q);q.layers.poke(count.U)
      q.arena.version.poke(1.U);identity(q.arena.identity);q.arena.base.poke(0x200000.U);q.arena.limit.poke((0x200000+4*recordBytes).U)
      q.pool.version.poke(1.U);identity(q.pool.identity);q.pool.base.poke(0x400000.U);q.pool.limit.poke(0x400080.U);q.pool.pageSizeLog2.poke(7.U)
      identity(q.chain.identity);q.chain.tile.poke(3.U);q.chain.prefixUsable.poke(true.B)
      q.chain.headValid.poke((!empty).B);q.chain.head.poke((if(empty)0 else 0x400000).U)
      q.chain.tail.poke((if(empty)0 else 0x400000).U);q.chain.committedCount.poke((if(empty)0 else 4).U)
      if(exhausted){q.chain.status.poke(PrimitiveBinStatus.exhausted.U);q.chain.restartValid.poke(true.B)
        q.chain.restart.primitive.poke(0xfffffff0L.U);q.chain.restart.state.poke(0x80000001L.U)
        q.chain.restart.sequence.poke(0xfffffff1L.U);q.chain.restart.token.poke(0xfedcba98L.U)}
      expectedChain=q.chain.peek().litValue
      d.io.begin.valid.poke(true.B);until("tile binding")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      zero(q)
    }
    def finish(status:Int=0):Unit={
      until("final tile completion")(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;r.status.expect(status.U);r.identity.context.expect(7.U);r.tile.expect(3.U)
      assert(r.chain.peek().litValue==expectedChain,"original head/count/tail/restart ownership changed")
      if(status==0){
        r.completedLayers.expect(layers.U);r.scanned.expect((if(empty)0 else 4*layers).U)
        r.selected.expect((if(empty)0 else 4).U)
        r.recordReadLines.expect((if(empty)0 else 4L*layers*recordBytes/64).U)
        val expected=if(empty)Vector.empty else recordLayers.distinct.sorted.flatMap{layer=>
          recordLayers.indices.filter(recordLayers(_)==layer).zipWithIndex.map{case(h,local)=>(layer,h,local)}}
        assert(observed.toVector==expected && begins==layers && images==layers)
      }
      assert(memory.pending.isEmpty && memory.reply.isEmpty && current.isEmpty && pending.isEmpty && busyTail==0)
      val held=r.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
    }
    def allLayers():Unit={for(i<-0 until layers)until(s"layer $i retirement")(images>i);finish()}
  }
  it should "retain one real full-capacity record chain across all 256 layer scans and empty-layer handoffs" in {
    // The image peer models only the handshake; actual clears/stores/resolves
    // require the separate composed typed-image execution fixture.
    simulate(new LayeredWindowPrimitiveReplayPipeline(cfg,p,128)){d=>val t=new Driver(d)
      t.install();t.start();t.allLayers();assert(t.memory.seen.forall(!_.write))
      val reads=t.memory.seen.size;t.start(noReferences=true);t.allLayers();assert(t.memory.seen.size==reads)
      t.start();t.allLayers()
    }
  }
  it should "drain held layer record and image ownership through single-pulse cancel and real read faults" in {
    simulate(new LayeredWindowPrimitiveReplayPipeline(cfg,p,128)){d=>val t=new Driver(d)
      t.install();t.holdBegin=true;t.start();t.until("held layer begin")(d.io.layerBegin.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdBegin=false;t.finish(4)
      t.holdOutput=true;t.start();t.until("held selected reference")(d.io.out.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdOutput=false;t.finish(4)
      t.holdEnd=true;t.start(noReferences=true);t.until("held layer EOF")(d.io.layerEnd.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdEnd=false;t.finish(4)
      t.holdImage=true;t.start(noReferences=true);t.until("delayed image retirement")(t.pending.nonEmpty)
      for(_<-0 until 11){t.tick();assert(t.begins==1);d.io.completion.valid.expect(false.B)}
      t.holdImage=false;t.allLayers()
      for(pageFault<-Seq(true,false)){
        t.memory.faultAt=Set(if(pageFault)BigInt(0x400000)else BigInt(0x200000+t.recordBytes))
        t.start();t.until("retained source fault")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultStage.expect((if(pageFault)2 else 3).U)
        d.io.completion.bits.image.status.expect(4.U);t.finish(3);t.memory.faultAt=Set.empty
      }
      t.install(badLayer=true);t.start();t.finish(7)
      t.install();t.start();t.allLayers()
    }
  }
  it should "preserve all sixteen varying slots and original exhausted-prefix restart ownership" in {
    simulate(new LayeredWindowPrimitiveReplayPipeline(cfg,p,128)){d=>val t=new Driver(d)
      t.install(wideData=true,oneLayer=true);t.start(count=1);t.allLayers()
      // A usable exhausted empty prefix still binds and retires every image
      // layer. Its original restart tuple is returned unchanged, without any
      // fabricated successful chain or replacement record publication.
      val reads=t.memory.seen.size;t.start(noReferences=true,exhausted=true)
      for(i<-0 until 256)t.until(s"empty exhausted prefix layer $i")(t.images>i)
      t.until("exhausted prefix completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultStage.expect(1.U);t.finish(3)
      assert(t.memory.seen.size==reads)
      for(pageFault<-Seq(true,false)){
        t.holdImage=true;t.memory.faultAt=Set(if(pageFault)BigInt(0x400000)else BigInt(0x200000+t.recordBytes))
        t.start(count=1);t.until("primary fault before late external cancel")(d.io.fault.valid.peek().litToBoolean)
        d.io.fault.bits.status.expect(3.U);d.io.fault.bits.stage.expect((if(pageFault)2 else 3).U)
        d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
        assert(t.pending.exists(_._1==4),"real source fault did not cancel its image owner")
        for(_<-0 until 11){t.tick();d.io.completion.valid.expect(false.B)}
        t.holdImage=false;t.until("drained primary fault")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultStage.expect((if(pageFault)2 else 3).U)
        d.io.completion.bits.image.status.expect(4.U);t.finish(3);t.memory.faultAt=Set.empty
      }
      t.start(count=1);t.allLayers()
    }
  }
}
