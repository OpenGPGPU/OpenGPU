package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Real default-capacity fragment Stage with a bounded memory/shared-task peer.
  * The peer publishes legal nonzero18field shader outputs before its successful
  * shared completion. No CU/ISA/FPU execution is claimed; SRAM logical capacity
  * and synchronous port behavior remain full, using the behavioral SRAM model.
  */
class FragmentOutputRowsConsumerQualificationSpec extends AnyFlatSpec {
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
    var exports=0;var launches=0;private var activeQuads=0;private var epoch=0
    private def color(lane: Int,field: Int): BigInt=BigInt(0x3f000000L)+(epoch<<16)+((field+1)<<12)+((lane+1)<<1)
    private def depth(lane: Int): BigInt=BigInt(0x3e800000L)+(epoch<<14)+(lane<<8)
    private def outputWord(lane: Int,field: Int): BigInt=if(field<16)color(lane,field)else if(field==16)depth(lane)else BigInt(63)
    private def publishKernelResult(): Unit={
      for(field<-0 until 18;lane<-0 until activeQuads*4;b<-0 until 4){
        val address=scratch+(inputs+field)*stride+lane*4+b
        memory(address)=((outputWord(lane,field)>>(8*b))&255).toInt
      }
    }
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
        val out=d.io.out.bits;out.sequence.expect((17+exports).U);out.colorSlots.expect(4.U)
        out.identity.context.expect(7.U);out.identity.pass.expect(8.U);out.identity.generation.expect((9+epoch).U)
        out.frontFacing.expect(false.B);out.sampleMode.expect(0.U);out.primitiveId.expect(0.U);out.instanceId.expect(0.U);out.partIndex.expect(0.U)
        for((l,i)<-out.lanes.zipWithIndex){val lane=exports*4+i
          l.x.expect((10+exports*2+i%2).S);l.y.expect((20+exports*2+i/2).S)
          l.depth.expect(depth(lane).U);l.colorMask.expect(15.U);l.depthOverride.expect(true.B)
          l.coverageMask.expect(1.U);l.helper.expect(false.B);l.discarded.expect(false.B)
          for((value,field)<-l.colors.flatten.zipWithIndex)value.expect(color(lane,field).U)
        }
        if(d.io.out.ready.peek().litToBoolean)exports+=1
      }
      assert(d.io.performance.stagingOutstanding.peek().litValue<=2)
      d.clock.step();cycle+=1
      if(invalidateDone)invalidate=None
      nextInvalidate.foreach(v=>invalidate=Some(v))
      if(completed)kernel=false
      if(launched){assert(!kernel);publishKernelResult();kernel=true;launches+=1}
    }
    def until(condition: => Boolean): Unit={var n=0
      while(!condition && n<10000){tick();n+=1}
      assert(condition,s"real fragment stage stalled cycle=$cycle pending=${pending.keys} requests=${accesses.size}")
    }
    private def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke((9+epoch).U)}
    def begin(quads: Int): Unit={
      require(quads>=1 && quads<=4);activeQuads=quads;epoch+=1
      assert(pending.isEmpty && replies.isEmpty && offered.isEmpty && held.isEmpty)
      accesses.clear();exports=0;launches=0;memoryReady=true
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(false.B)
      d.io.out.ready.poke(false.B)
      val draw=d.io.draw.bits;zero(draw);identity(draw.identity);draw.version.poke(1.U);draw.allowDepthOverride.poke(true.B)
      draw.shaderPc.poke(0x1000.U);draw.varyingSlots.poke(16.U);draw.colorSlots.poke(4.U);draw.colorMask.poke(15.U)
      draw.scratch.base.poke(scratch.U);draw.scratch.limit.poke((scratch+prefix).U)
      draw.scratch.allocationBase.poke(scratch.U);draw.scratch.allocationLimit.poke((scratch+prefix).U)
      draw.sampler.texWidth.poke(1.U);draw.sampler.texHeight.poke(1.U)
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
      for(quad<-0 until quads){
      val q=d.io.in.bits;zero(q);identity(q.values.identity);q.values.sequence.poke((17+quad).U)
      q.values.varyingSlots.poke(16.U)
      for((l,i)<-q.values.lanes.zipWithIndex){
        l.x.poke((10+quad*2+i%2).S);l.y.poke((20+quad*2+i/2).S);l.covered.poke(true.B);l.coverageMask.poke(1.U)
        l.windowDepth.poke("h3f000000".U);l.fragCoordW.poke("h3f800000".U);l.perspectiveValid.poke(true.B)
        l.reciprocalW.significand.poke((1<<23).U);l.reciprocalW.exponent.poke(0.S)
        for(v<-0 until 16;c<-0 until 4)l.varyings(v)(c).poke((quad*10000+i*1000+v*4+c).U)
      }
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      }
      identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(quads.U);d.io.inputEnd.bits.status.poke(0.U)
      d.io.inputEnd.valid.poke(true.B);until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)
    }
    def finish(status: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 3){tick();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until(!d.io.busy.peek().litToBoolean)
      assert(pending.isEmpty && replies.isEmpty && offered.isEmpty)
      for(b<-0 until 64)assert(memory(scratch+prefix+b)==0xa5,"fragment prefix guard changed")
    }
  }

  it should "consume only initialized quad rows at all partial default-capacity output widths" in {
    simulate(new FragmentShaderStage(config=GpuConfig(lanes=16,warps=8),shared=SharedCuShaderConfig(4))){d=>
      val t=new Peer(d)
      // A full window before/after the partial windows makes stale SRAM rows
      // observable if the real Stage ever reads outside its captured width.
      for(quads<-Seq(4,1,2,3,4)){
        t.begin(quads);t.until(d.io.out.valid.peek().litToBoolean)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 9){t.tick();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);t.finish(0)
        assert(t.exports==quads && t.launches==1)
        d.io.performance.stagingOutstanding.expect(0.U)
        info(s"FRAGMENT_OUTPUT_CONSUMER lanes=${quads*4} physicalCapacity=128 stride=512 varyings=16 colors=4 rawFields=18 exports=${t.exports} launches=1 heldClocks=9 cacheReadBoundsAssertion=true pendingDrained=true guardBytes=64")
      }
    }
  }
}
