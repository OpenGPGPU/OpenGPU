package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Real fragment-stage control with a bounded memory/shared-task peer. No CU
  * instruction model is needed to exercise staging ownership and drain.
  */
class FragmentReturnedCreditQualificationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private case class Access(cycle: Int,id: Int,address: BigInt,write: Boolean,data: BigInt,mask: BigInt)
  private class Peer(val d: FragmentShaderStage) {
    val scratch=BigInt(0x100000);val stride=128*4;val inputs=6+4*16
    val prefix=(inputs+4*4+2)*stride
    val memory=mutable.Map.empty[BigInt,Int]
    val pending=mutable.Map.empty[Int,Access]
    val accesses=mutable.ArrayBuffer.empty[Access]
    private case class Reply(id: Int,fault: Boolean,due: Int)
    private val replies=mutable.ArrayBuffer.empty[Reply]
    private var offered: Option[Reply]=None
    private var offeredData=BigInt(0)
    private var held: Option[BigInt]=None
    private var invalidate: Option[CacheLineInvalidate]=None
    private var kernel=false
    var cycle=0;var memoryReady=true;var autoReply: Access=>Boolean=_=>true
    var exports=0;var launches=0
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    d.io.shared.launch.ready.poke(true.B);d.io.shared.invalidate.ready.poke(true.B)
    d.io.shared.completion.bits.success.poke(true.B);d.io.out.ready.poke(true.B)
    for(b<-0 until 64)memory(scratch+prefix+b)=0xa5
    def isInput(a: Access): Boolean=a.write && a.address<scratch+inputs*stride
    private def read(a: BigInt): BigInt=(0 until 64).foldLeft(BigInt(0))((v,b)=>
      v|(BigInt(memory.getOrElse(a+b,0))<<(8*b)))
    def reply(id: Int,fault: Boolean=false): Unit={
      assert(pending.contains(id) && !replies.exists(_.id==id) && !offered.exists(_.id==id))
      replies+=Reply(id,fault,cycle)
    }
    def tick(): Unit={
      d.io.memoryRequest.ready.poke(memoryReady.B)
      if(offered.isEmpty){val index=replies.indexWhere(_.due<=cycle)
        if(index>=0){
          offered=Some(replies.remove(index))
          val a=pending(offered.get.id);offeredData=if(a.write)BigInt(0)else read(a.address)
        }}
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach{r=>
        d.io.memoryResponse.bits.transactionId.poke(r.id.U)
        d.io.memoryResponse.bits.readData.poke(offeredData.U)
        d.io.memoryResponse.bits.fault.poke(r.fault.B)
      }
      d.io.shared.invalidateDone.valid.poke(invalidate.nonEmpty.B)
      invalidate.foreach(v=>d.io.shared.invalidateDone.bits.poke(v))
      d.io.shared.completion.valid.poke(kernel.B)
      val nextInvalidate=if(d.io.shared.invalidate.valid.peek().litToBoolean)
        Some(d.io.shared.invalidate.bits.peek())else None
      val invalidateDone=invalidate.nonEmpty && d.io.shared.invalidateDone.ready.peek().litToBoolean
      val launched=d.io.shared.launch.valid.peek().litToBoolean
      val completed=kernel && d.io.shared.completion.ready.peek().litToBoolean
      val request=d.io.memoryRequest;val valid=request.valid.peek().litToBoolean
      val bits=if(valid || held.nonEmpty)request.bits.peek().litValue else BigInt(0)
      held.foreach(old=>assert(valid && bits==old,"held real-stage offer changed or was withdrawn"))
      held=if(valid && !memoryReady)Some(bits)else None
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){
        val r=offered.get
        val a=pending.remove(r.id).getOrElse(fail("reply lost its accepted ID"))
        // An accepted request owns its ID, but storage commits only with a
        // successful acknowledgement. A fault must leave every byte intact.
        if(!r.fault && a.write)for(b<-0 until 64 if a.mask.testBit(b))
          memory(a.address+b)=((a.data>>(8*b))&255).toInt
        offered=None
      }
      if(valid && memoryReady){
        val r=request.bits
        val a=Access(cycle,r.transactionId.peek().litValue.toInt,r.address.peek().litValue,
          r.isWrite.peek().litToBoolean,r.writeData.peek().litValue,r.byteMask.peek().litValue)
        assert(a.id<2 && !pending.contains(a.id),"real stage reused an unacknowledged external ID")
        pending(a.id)=a;accesses+=a
        if(autoReply(a))replies+=Reply(a.id,false,cycle+2)
      }
      if(d.io.out.valid.peek().litToBoolean){
        d.io.out.bits.sequence.expect(17.U);d.io.out.bits.colorSlots.expect(4.U)
        for(l<-d.io.out.bits.lanes){l.depth.expect("h3f000000".U);l.colorMask.expect(15.U)
          l.colors.flatten.foreach(_.expect(0.U))}
        exports+=1
      }
      assert(d.io.performance.stagingOutstanding.peek().litValue<=2)
      d.clock.step();cycle+=1
      if(invalidateDone)invalidate=None
      nextInvalidate.foreach(v=>invalidate=Some(v))
      if(completed)kernel=false
      if(launched){assert(!kernel);kernel=true;launches+=1}
    }
    def until(condition: => Boolean): Unit={var n=0
      while(!condition && n<10000){tick();n+=1}
      assert(condition,s"real fragment stage stalled cycle=$cycle pending=${pending.keys} requests=${accesses.size}")
    }
    private def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
    def begin(): Unit={
      assert(pending.isEmpty && replies.isEmpty && offered.isEmpty && held.isEmpty)
      accesses.clear();exports=0;launches=0;memoryReady=true
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(false.B)
      val draw=d.io.draw.bits;zero(draw);identity(draw.identity);draw.version.poke(1.U)
      draw.shaderPc.poke(0x1000.U);draw.varyingSlots.poke(16.U);draw.colorSlots.poke(4.U);draw.colorMask.poke(15.U)
      draw.scratch.base.poke(scratch.U);draw.scratch.limit.poke((scratch+prefix).U)
      draw.scratch.allocationBase.poke(scratch.U);draw.scratch.allocationLimit.poke((scratch+prefix).U)
      draw.sampler.texWidth.poke(1.U);draw.sampler.texHeight.poke(1.U)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      val q=d.io.in.bits;zero(q);identity(q.values.identity);q.values.sequence.poke(17.U)
      q.values.varyingSlots.poke(16.U)
      for((l,i)<-q.values.lanes.zipWithIndex){
        l.x.poke((i%2).S);l.y.poke((i/2).S);l.covered.poke(true.B);l.coverageMask.poke(1.U)
        l.windowDepth.poke("h3f000000".U);l.fragCoordW.poke("h3f800000".U);l.perspectiveValid.poke(true.B)
        l.reciprocalW.significand.poke((1<<23).U);l.reciprocalW.exponent.poke(0.S)
        for(v<-0 until 16;c<-0 until 4)l.varyings(v)(c).poke((i*1000+v*4+c).U)
      }
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(1.U);d.io.inputEnd.bits.status.poke(0.U)
      d.io.inputEnd.valid.poke(true.B);until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)
    }
    def seed(): Unit={
      autoReply=a=> !isInput(a);begin();until(accesses.count(isInput)==2)
      for(_<-0 until 8)tick()
      assert(pending.keySet==Set(0,1));d.io.performance.stagingOutstanding.expect(2.U)
      assert(accesses.filter(isInput).map(_.address).toVector==Vector(scratch,scratch+stride))
    }
    def respond(id: Int,fault: Boolean=false): Unit={reply(id,fault);until(!pending.contains(id))}
    def finish(status: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 3){tick();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until(!d.io.busy.peek().litToBoolean)
      assert(pending.isEmpty && replies.isEmpty && offered.isEmpty)
      for(b<-0 until 64)assert(memory(scratch+prefix+b)==0xa5,"fragment prefix guard changed")
    }
    def healthy(): Unit={autoReply=_=>true;begin();finish(0);assert(exports==1 && launches==1)}
  }

  it should "return only successful input credits while preserving held offers, cancellation and readback" in {
    val readback=mutable.Map.empty[Boolean,Int]
    for(fast<-Seq(false,true)){
      simulate(new FragmentShaderStage(config=GpuConfig(),shared=SharedCuShaderConfig(4),inputReturnCredit=fast)){d=>
        val t=new Peer(d)
        // Both IDs are owned and the original serial coalescer's next row is
        // ready. External response admission is followed by its existing input
        // reply FIFO, the registered offer and the existing output FIFO.
        t.seed();t.respond(0);val externalReply=t.cycle-1
        t.tick();d.io.performance.stagingOutstanding.expect(1.U)
        t.tick();d.io.performance.stagingOutstanding.expect((if(fast)2 else 1).U)
        t.until(t.accesses.count(t.isInput)==3)
        val third=t.accesses.filter(t.isInput).last
        assert(third.id==0 && third.cycle-externalReply==(if(fast)3 else 4),"successful return missed its next registered offer")
        d.io.cancel.poke(true.B);t.autoReply=_=>true
        t.pending.keys.toVector.foreach(t.reply(_));t.finish(4);assert(t.exports==0);t.healthy()

        // A held externally offered ID and the next private offered ID must
        // survive another return and cancellation with unchanged request bits.
        t.seed();t.memoryReady=false;t.respond(0)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean);val offer=d.io.memoryRequest.bits.peek().litValue
        t.respond(1);for(_<-0 until 8){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==offer)}
        d.io.cancel.poke(true.B);for(_<-0 until 5)t.tick()
        t.autoReply=_=>true;t.memoryReady=true;t.finish(4)
        assert(t.accesses.count(t.isInput)==4 && t.exports==0);t.healthy()

        for(fault<-Seq(true,false)){
          t.seed();t.respond(0,fault)
          // Cancellation arrives on the internal reply handshake clock.
          if(!fault)d.io.cancel.poke(true.B)
          for(_<-0 until 8){t.tick();d.io.completion.valid.expect(false.B)}
          assert(t.accesses.count(t.isInput)==2,"failed/canceled reply admitted a new input row")
          t.respond(1);t.finish(if(fault)3 else 4);assert(t.exports==0);t.healthy()
        }

        // Return succeeds at R; cancellation at R+1 must retain the new offer
        // that fast control reserved at R, including when its receiver stalls.
        t.seed();t.respond(0);t.tick();d.io.cancel.poke(true.B);t.memoryReady=false
        for(_<-0 until 6)t.tick()
        d.io.memoryRequest.valid.expect(fast.B)
        t.respond(1);d.io.completion.valid.expect(false.B)
        t.autoReply=_=>true;t.memoryReady=true;t.finish(4)
        assert(t.accesses.count(t.isInput)==(if(fast)3 else 2));t.healthy()

        // The output reader owns its unchanged credit policy. Only its peer
        // acknowledgements are held here; every stage operation remains real.
        t.autoReply=_.write;t.begin();t.until(t.accesses.count(!_.write)==2)
        for(_<-0 until 8)t.tick()
        val id=t.accesses.find(!_.write).get.id;t.respond(id);val replied=t.cycle-1
        t.until(t.accesses.count(!_.write)==3)
        readback(fast)=t.accesses.filter(!_.write).last.cycle-replied
        t.autoReply=_=>true;t.pending.keys.toVector.foreach(t.reply(_));t.finish(0)
        assert(t.exports==1)
        info(s"FRAGMENT_RETURNED_CREDIT enabled=$fast default128=true components64=true colors4=true nextRegisteredOffer=true heldOffer=true failedReturnNoRefill=true cancelAfterReturnDrain=true healthyReuse=true")
      }
    }
    assert(readback(false)==readback(true),s"input credit changed readback timing: $readback")
  }
}
