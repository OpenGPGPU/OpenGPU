package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TransformFeedbackExportSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val p=VertexShaderStageConfig()
  private val fullFlat=(BigInt(1)<<64)-1
  private def raw(seed: Int,vertex: Int,index: Int): BigInt=
    (BigInt("80000000",16)+seed*0x100000+vertex*256+index)&0xffffffffL
  private class Driver(val d: TransformFeedbackExport) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.out.ready.poke(true.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,
      d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    val outputs=mutable.ArrayBuffer.empty[Map[String,BigInt]]
    var heldOut=Option.empty[BigInt];var heldResult=Option.empty[BigInt]
    def tick(): Unit={
      memory.drive()
      for((port,old)<-Seq((d.io.out,heldOut))){
        old.foreach(x=>assert(port.valid.peek().litToBoolean && port.bits.peek().litValue==x))
      }
      heldOut=if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)
        Some(d.io.out.bits.peek().litValue)else None
      heldResult.foreach(x=>assert(d.io.result.valid.peek().litToBoolean && d.io.result.bits.peek().litValue==x))
      heldResult=if(d.io.result.valid.peek().litToBoolean && !d.io.result.ready.peek().litToBoolean)
        Some(d.io.result.bits.peek().litValue)else None
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean)outputs+=values(d.io.out.bits)
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<12000){tick();n+=1}
      assert(condition,s"TF stalled cycle=${memory.cycle} memory=${memory.pending}")}
    def readReply(status: Int,active: Boolean): Map[String,BigInt]={
      until(d.io.commandReply.valid.peek().litToBoolean)
      val r=d.io.commandReply.bits;r.status.expect(status.U);r.active.expect(active.B)
      val state=values(r.state);val held=r.peek().litValue
      for(_<-0 until 3){tick();assert(r.peek().litValue==held)}
      d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B);state
    }
    def submitCommand(action: Int,status: Int=0,active: Boolean=true): Map[String,BigInt]={
      d.io.command.bits.action.poke(action.U);d.io.command.valid.poke(true.B)
      until(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      readReply(status,active)
    }
    def begin(kind: Int=2,counts: Seq[Int]=Seq(64),selectors: Seq[Int]=0 until 64,
        base: BigInt=0x1003c,bytes: BigInt=8192,context: Int=7,cookie: Int=1,root: BigInt=0,
        interleaved: Boolean=true,status: Int=0): Map[String,BigInt]={
      zero(d.io.command.bits);val s=d.io.command.bits.state;val q=s.descriptor
      s.root.poke(root.U);q.context.poke(context.U);q.cookie.poke(cookie.U);q.program.poke(9.U)
      q.kind.poke(kind.U);q.interleaved.poke(interleaved.B);q.buffers.poke(counts.size.U)
      for(i<-0 until 4){q.components(i).poke(counts.lift(i).getOrElse(0).U)
        q.views(i).base.poke((base+i*0x1000).U);q.views(i).limit.poke((base+i*0x1000+bytes).U)}
      for(i<-0 until 64)q.selectors(i).poke(selectors.lift(i).getOrElse(0).U)
      submitCommand(TransformFeedbackAction.begin,status,active=status==0)
    }
    def action(action: Int,context: Int=7,cookie: Int=1,root: BigInt=0,status: Int=0,
        active: Boolean=true,program: Int=9): Map[String,BigInt]={
      zero(d.io.command.bits);val s=d.io.command.bits.state;s.root.poke(root.U)
      s.descriptor.context.poke(context.U);s.descriptor.cookie.poke(cookie.U);s.descriptor.program.poke(program.U)
      submitCommand(action,status,active)
    }
    def restore(saved: Map[String,BigInt],status: Int=0): Unit={
      for((name,leaf)<-leaves(d.io.command.bits.state))leaf match{
        case b: Bool=>b.poke((saved(name)!=0).B)
        case u: UInt=>u.poke(saved(name).U)
      }
      submitCommand(TransformFeedbackAction.restore,status,active=status==0)
    }
    def offer(seed: Int=1,kind: Int=2,context: Int=7,root: BigInt=0,program: Int=9,
        discard: Boolean=false,flat: BigInt=fullFlat,mode: Int=0,cookie: Int=0): Unit={
      val q=d.io.in.bits;zero(q);q.context.poke(context.U);q.root.poke(root.U);q.program.poke(program.U)
      q.mode.poke(mode.U);q.cookie.poke(cookie.U)
      q.rasterDiscard.poke(discard.B);val v=q.primitive;identity(v.identity)
      v.sequence.poke(seed.U);v.primitiveId.poke(seed.U);v.kind.poke(kind.U);v.vertexCount.poke((kind+1).U)
      v.provokingVertex.poke(kind.U);v.varyingSlots.poke(16.U);v.flatMask.poke(flat.U)
      for(vertex<-0 to kind){val r=v.vertices(vertex);identity(r.identity);r.ordinal.poke(vertex.U)
        r.pointSize.poke(raw(seed,vertex,68).U);r.pointSizeWritten.poke(true.B)
        for(c<-0 until 4)r.position(c).poke(raw(seed,vertex,c).U)
        for(c<-0 until 64)r.varyings(c/4)(c%4).poke(raw(seed,vertex,c+4).U)}
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
    }
    def finish(status: Int=0,captured: Boolean=true,bytes: Int=768): Unit={
      until(d.io.result.valid.peek().litToBoolean)
      d.io.result.bits.status.expect(status.U);d.io.result.bits.captured.expect(captured.B)
      d.io.result.bits.bytes.expect(bytes.U)
      for(_<-0 until 5){tick();d.io.command.ready.expect(false.B)}
      d.io.result.ready.poke(true.B);tick();d.io.result.ready.poke(false.B)
      until(!d.io.busy.peek().litToBoolean)
    }
    def expectWords(base: BigInt,words: Seq[BigInt]): Unit=
      for((expected,i)<-words.zipWithIndex)assert(memory.word(base+i*4)==expected,
        s"TF word ${(base+i*4).toString(16)} got=${memory.word(base+i*4).toString(16)} expected=${expected.toString(16)}")
  }

  it should "retain peer contexts and roots while aborting an owned store and preserve its primary memory fault" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      for(fault<-Seq(false,true)){
        val root=BigInt(0x80000030L)
        t.begin(kind=0,counts=Seq(4),selectors=0 until 4,context=10,root=root)
        t.begin(kind=0,counts=Seq(4),selectors=0 until 4,context=20,cookie=2,root=root,base=0x2003c)
        t.begin(kind=0,counts=Seq(4),selectors=0 until 4,context=10,cookie=3,root=root+1,base=0x3003c)
        t.memory.holdRequests=true;t.offer(kind=0,context=10,root=root,mode=1,cookie=1)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.memoryRequest.bits.peek().litValue;d.io.abort.poke(true.B)
        for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.memorySatp.expect(root.U)}
        if(fault)t.memory.faultAt=Set(BigInt(0x10000))
        t.memory.holdRequests=false;t.finish(if(fault)3 else 4,captured=false,bytes=0)
        d.io.abort.poke(false.B);t.memory.faultAt=Set.empty;d.io.activeObjects.expect(3.U)
        val failed=t.action(TransformFeedbackAction.end,context=10,root=root,status=if(fault)3 else 4,active=false)
        assert(failed("primitives")==0 && failed("vertices")==0 && failed("offsets[0]")==0)
        for((context,cookie,vm)<-Seq((20,2,root),(10,3,root+1))){
          t.offer(kind=0,context=context,root=vm,mode=1,cookie=cookie);t.finish(bytes=16)
          val healthy=t.action(TransformFeedbackAction.end,context=context,cookie=cookie,root=vm,active=false)
          assert(healthy("primitives")==1 && healthy("vertices")==1 && healthy("offsets[0]")==16)
        }
        d.io.activeObjects.expect(0.U)
      }
    }
  }

  it should "preserve leases across rejected native scopes and an aborted private control command" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d);val root=BigInt(0x80000030L)
      t.begin(kind=0,counts=Seq(1),selectors=Seq(0),root=root)
      t.begin(kind=0,counts=Seq(1),selectors=Seq(0),context=8,cookie=2,root=root,base=0x2003c)
      for((mode,cookie,program,kind,status)<-Seq((1,999,9,0,2),(1,1,10,0,2),(1,1,9,1,2),(2,1,9,0,5))){
        t.offer(kind=kind,root=root,program=program,mode=mode,cookie=cookie)
        t.until(d.io.result.valid.peek().litToBoolean);d.io.abort.poke(true.B)
        t.finish(status,captured=false,bytes=0);d.io.abort.poke(false.B);d.io.activeObjects.expect(2.U)
      }
      zero(d.io.command.bits);val c=d.io.command.bits;c.action.poke(TransformFeedbackAction.pause.U)
      c.state.root.poke(root.U);c.state.descriptor.context.poke(7.U)
      c.state.descriptor.cookie.poke(1.U);c.state.descriptor.program.poke(9.U)
      d.io.command.valid.poke(true.B);t.until(d.io.command.ready.peek().litToBoolean);t.tick();d.io.command.valid.poke(false.B)
      d.io.abort.poke(true.B);t.readReply(4,active=true);d.io.abort.poke(false.B)
      for((context,cookie)<-Seq(7->1,8->2)){
        t.offer(kind=0,context=context,root=root,mode=1,cookie=cookie);t.finish(bytes=4)
        val healthy=t.action(TransformFeedbackAction.end,context=context,cookie=cookie,root=root,active=false)
        assert(healthy("primitives")==1 && healthy("offsets[0]")==4)
      }
      d.io.activeObjects.expect(0.U)
    }
  }

  it should "capture all64 raw interleaved components before flat shading with dense masked stores and acknowledged counts" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      val selectors=Seq(68)++(0 until 63).reverse
      t.begin(selectors=selectors,root=0x80000030L);val base=BigInt(0x1003c)
      for(a<-base-4 until base+1600)t.memory.bytes(a)=0xa7
      t.memory.holdRequests=true;t.offer(root=0x80000030L)
      t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memorySatp.expect(0x80000030L.U);d.io.command.ready.expect(false.B)
      d.io.in.bits.root.poke(0x80000031L.U);d.io.command.bits.state.root.poke(0x80000031L.U)
      val offered=d.io.memoryRequest.bits.peek().litValue
      for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offered);d.io.memorySatp.expect(0x80000030L.U)}
      t.memory.holdRequests=false;t.memory.holdResponses=true
      t.until(t.memory.pending.nonEmpty);for(_<-0 until 5){t.tick();d.io.result.valid.expect(false.B)}
      t.memory.holdResponses=false;t.finish()
      t.expectWords(base,(0 until 3).flatMap(v=>selectors.map(raw(1,v,_))))
      assert(t.memory.word(base-4)==0xa7a7a7a7L && t.memory.word(base+768)==0xa7a7a7a7L)
      assert(t.outputs.size==1)
      for(v<-0 until 3;c<-0 until 64)assert(t.outputs.head(s"vertices[$v].varyings[${c/4}][${c%4}]")==raw(1,2,c+4))
      for(v<-0 until 3;c<-0 until 4)assert(t.outputs.head(s"vertices[$v].position[$c]")==raw(1,v,c))
      t.offer(seed=2,discard=true,root=0x80000030L);t.finish();assert(t.outputs.size==1)
      t.expectWords(base+768,(0 until 3).flatMap(v=>selectors.map(raw(2,v,_))))
      val saved=t.action(TransformFeedbackAction.end,active=false,root=0x80000030L)
      assert(saved("primitives")==2 && saved("vertices")==6 && saved("offsets[0]")==1536)
      assert(t.memory.seen.forall(r=>r.write && r.tag==0 && (r.address&63)==0))
      assert(t.memory.seen.size==26,"dense1536-byte output should use26 masked lines at offset60")
      info("TRANSFORM_FEEDBACK interleavedComponents=64 rawPositionVaryingPointSize=true beforeFlat=true guardedBytes=true acknowledgedPrimitives=2 capturedVertices=6 outputBytes=1536 rasterDiscard=true coalescedLines=26")
    }
  }

  it should "pack four separate bindings and preflight every destination before any primitive store" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      val counts=Seq(4,3,2,1);val selectors=(0 until 16).map(i=>(i*7)%69)
      t.begin(kind=0,counts=counts,selectors=selectors,interleaved=false,bytes=32)
      for(seed<-1 to 2){t.offer(seed=seed,kind=0);t.finish(bytes=40)}
      for(b<-0 until 4)t.expectWords(BigInt(0x1003c)+b*0x1000,(1 to 2).flatMap(s=>(0 until counts(b)).map(c=>raw(s,0,selectors(b*4+c)))))
      val before=t.memory.seen.size;t.offer(seed=3,kind=0);t.finish(status=1,captured=false,bytes=0)
      assert(t.memory.seen.size==before,"one full binding must reject the complete primitive before any other binding writes")
      val saved=t.action(TransformFeedbackAction.end,status=1,active=false)
      assert(saved("primitives")==2 && saved("vertices")==2)
      for(b<-0 until 4)assert(saved(s"offsets[$b]")==counts(b)*8)
      t.begin(kind=1,counts=Seq(1),selectors=Seq(0),cookie=2)
      t.offer(kind=1,program=10);t.finish(status=2,captured=false,bytes=0)
      t.action(TransformFeedbackAction.end,cookie=2,status=2,active=false)
      t.begin(kind=1,counts=Seq(1),selectors=Seq(0),cookie=3)
      t.offer(kind=1);t.finish(bytes=8);t.action(TransformFeedbackAction.end,cookie=3,active=false)
      info("TRANSFORM_FEEDBACK separateBindings=4 rawPackedOrder=true allDestinationsPreflight=true noPartialBoundsStore=true programFault=true exactHealthyReuse=true")
    }
  }

  it should "retain paused objects and restore exact checkpoints without leaking counts across contexts roots or cookies" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      for(i<-0 until 8){t.begin(kind=0,counts=Seq(1),selectors=Seq(0),context=10+i,root=0x80000030L,
        base=0x20000+i*0x2000);t.offer(kind=0,context=10+i,root=0x80000030L);t.finish(bytes=4)}
      d.io.activeObjects.expect(8.U)
      t.begin(kind=0,counts=Seq(1),context=99,status=6)
      t.action(TransformFeedbackAction.pause,context=10,root=0x80000030L)
      val before=t.memory.seen.size;t.offer(kind=0,context=10,root=0x80000030L);t.finish(captured=false,bytes=0)
      t.offer(kind=0,context=11,root=0x80000031L);t.finish(captured=false,bytes=0)
      assert(t.memory.seen.size==before)
      val saved=t.action(TransformFeedbackAction.evict,context=10,root=0x80000030L,active=false)
      assert(saved("paused")==1 && saved("primitives")==1 && saved("offsets[0]")==4)
      t.restore(saved);t.action(TransformFeedbackAction.resume,context=10,root=0x80000030L)
      t.offer(seed=7,kind=0,context=10,root=0x80000030L);t.finish(bytes=4)
      val ended=t.action(TransformFeedbackAction.end,context=10,root=0x80000030L,active=false)
      assert(ended("primitives")==2 && ended("vertices")==2 && ended("offsets[0]")==8)
      t.expectWords(0x20000,Seq(raw(1,0,0),raw(7,0,0)))
      for(i<-1 until 8){val row=t.action(TransformFeedbackAction.end,context=10+i,root=0x80000030L,active=false)
        assert(row("primitives")==1 && row("vertices")==1)}
      d.io.activeObjects.expect(0.U)
      info("TRANSFORM_FEEDBACK activeObjects=8 ninthRejected=true scopeRootAndCookie=true pauseResume=true explicitEvictRestore=true independentCursors=true")
    }
  }

  it should "preserve offered stores and primary memory faults across cancellation then release every object for reuse" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      for(fault<-Seq(false,true)){
        t.begin(kind=0,counts=Seq(4),base=0x10000)
        t.memory.holdRequests=true;t.offer(kind=0,root=0)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val offer=d.io.memoryRequest.bits.peek().litValue
        d.io.cancel.poke(true.B)
        for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.result.valid.expect(false.B)}
        if(fault)t.memory.faultAt=Set(BigInt(0x10000))
        t.memory.holdRequests=false;t.finish(status=if(fault)3 else 4,captured=false,bytes=0)
        d.io.activeObjects.expect(0.U);d.io.cancel.poke(false.B);t.memory.faultAt=Set.empty
        t.begin(kind=0,counts=Seq(4),cookie=2,base=0x20000)
        t.offer(kind=0);t.finish(bytes=16)
        val result=t.action(TransformFeedbackAction.end,cookie=2,active=false)
        assert(result("primitives")==1 && result("offsets[0]")==16)
      }
      assert(t.memory.pending.isEmpty && t.memory.reply.isEmpty)
    }
  }

  it should "validate layouts and reject fourGiB cursor overflow before physical address truncation" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      t.begin(counts=Seq(0),status=1);t.begin(counts=Seq(65),status=1)
      t.begin(counts=Seq(5),interleaved=false,status=1)
      t.begin(counts=Seq(1),selectors=Seq(69),status=1)
      t.begin(counts=Seq(1),base=0x10001,status=1)
      t.begin(kind=0,counts=Seq(1),selectors=Seq(0),base=0xfffffffcL,bytes=4)
      t.offer(kind=0);t.finish(bytes=4)
      assert(t.memory.seen.last.address==0xffffffc0L && t.memory.seen.last.mask==(BigInt(15)<<60))
      val before=t.memory.seen.size;t.offer(kind=0);t.finish(status=1,captured=false,bytes=0)
      assert(t.memory.seen.size==before)
      val row=t.action(TransformFeedbackAction.end,status=1,active=false)
      assert(row("offsets[0]")==4 && row("primitives")==1)
    }
  }

  it should "allow another bound object while paused and fence immutable public geometry through reset" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      t.begin(kind=0,counts=Seq(1));t.action(TransformFeedbackAction.pause)
      t.begin(kind=0,counts=Seq(1),cookie=2,base=0x20000)
      t.action(TransformFeedbackAction.resume,status=5)
      d.io.out.ready.poke(false.B);t.offer(kind=0);t.until(d.io.out.valid.peek().litToBoolean)
      val offered=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==offered)}
      d.io.result.bits.captured.expect(true.B);d.io.result.bits.status.expect(0.U)
      d.io.result.ready.poke(true.B);t.tick();d.io.result.ready.poke(false.B)
      for(_<-0 until 4){t.tick();d.io.busy.expect(true.B);d.io.activeObjects.expect(2.U)}
      d.io.out.ready.poke(true.B);t.tick();t.until(!d.io.busy.peek().litToBoolean)
      d.io.activeObjects.expect(0.U)
    }
  }

  it should "retain the selected object identity through admission reset before any export offer" in {
    simulate(new TransformFeedbackExport()){d=>val t=new Driver(d)
      val root=BigInt(0x80000030L)
      t.begin(kind=0,counts=Seq(1),context=23,cookie=37,root=root)
      t.offer(kind=0,context=23,root=root)
      d.io.cancel.poke(true.B)
      d.io.in.bits.context.poke(999.U);d.io.in.bits.root.poke(0x80000031L.U)
      t.until(d.io.result.valid.peek().litToBoolean)
      d.io.result.bits.context.expect(23.U);d.io.result.bits.root.expect(root.U)
      d.io.result.bits.cookie.expect(37.U)
      t.finish(status=4,captured=false,bytes=0)
      assert(t.memory.seen.isEmpty,"admission reset must not issue a new export store")
      d.io.activeObjects.expect(0.U);d.io.cancel.poke(false.B)
      t.action(TransformFeedbackAction.end,context=23,cookie=37,root=root,status=5,active=false)
      t.begin(kind=0,counts=Seq(1),context=23,cookie=38,root=root)
      t.offer(kind=0,context=23,root=root);t.finish(bytes=4)
      val saved=t.action(TransformFeedbackAction.end,context=23,cookie=38,root=root,active=false)
      assert(saved("primitives")==1 && saved("offsets[0]")==4)
      val before=t.memory.seen.size
      zero(d.io.command.bits);val next=d.io.command.bits.state
      next.root.poke(root.U);next.descriptor.context.poke(23.U);next.descriptor.cookie.poke(99.U)
      next.descriptor.program.poke(9.U);next.descriptor.kind.poke(0.U)
      next.descriptor.interleaved.poke(true.B);next.descriptor.buffers.poke(1.U)
      next.descriptor.components(0).poke(1.U);next.descriptor.views(0).base.poke(0x10000.U)
      next.descriptor.views(0).limit.poke(0x20000.U)
      d.io.command.bits.action.poke(TransformFeedbackAction.begin.U);d.io.command.valid.poke(true.B)
      t.until(d.io.command.ready.peek().litToBoolean);t.tick();d.io.command.valid.poke(false.B)
      d.io.cancel.poke(true.B);next.root.poke(0x80000031L.U);next.descriptor.cookie.poke(100.U)
      val cancelled=t.readReply(4,active=false)
      assert(cancelled("root")==root && cancelled("descriptor.cookie")==99)
      t.until(!d.io.busy.peek().litToBoolean);d.io.activeObjects.expect(0.U)
      assert(t.memory.seen.size==before);d.io.cancel.poke(false.B)
    }
  }
}
