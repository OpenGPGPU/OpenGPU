package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Real instruction execution with an adversarial external memory responder.
  * This covers CU ordering, not the external L2 RMW/coherence implementation.
  */
class GpuComputeUnitFenceOrderingSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "order fenced stores and atomics through delayed acknowledgements and precise faults" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4, warps=2), finishOnTrap=true,
      instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(at: BigInt, value: BigInt): Unit =
        for (i <- 0 until 4) memory(at+i) = ((value >> (8*i)) & 255).toInt
      def word(at: BigInt): BigInt =
        (0 until 4).foldLeft(BigInt(0))((v,i) => v | (BigInt(memory.getOrElse(at+i,0)) << (8*i)))
      def line(at: BigInt): BigInt =
        (0 until 64).foldLeft(BigInt(0))((v,i) => v | (BigInt(memory.getOrElse(at+i,0)) << (8*i)))
      val code = Seq("000092b7", "00700313", "0062a023", "0330000f", // x5=0x9000; x6=7; sw; fence rw,rw
        "0002a503", "04028293", "0062a5af", "0330000f", // lw x10; x5+=64; amoadd.w x11; fence
        "0002a603", "0000a3b7", "00a3a023", "00b3a223", "00c3a423", "30500073")
      code.zipWithIndex.foreach { case (v,i) => put(0x1000+4*i, BigInt(v,16)) }
      put(0x9000,0); put(0x9040,41)
      for (at <- 0x9ff0 until 0xa040) memory(BigInt(at)) = 0xa5
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt,
        write: Boolean, fault: Boolean, due: Int)
      val pending = mutable.Queue.empty[Reply]
      var cycle=0; var mode=""; var firstStoreSeen= -1
      var storeAcknowledged=false; var atomicAcknowledged=false
      var atomicDue= -1; var atomicOld=BigInt(0)
      var atomicRequests=0; var stores=0; var traps=0
      val commits=mutable.Map.empty[Int,BigInt]
      var heldMemory: Option[BigInt]=None; var heldAtomic: Option[BigInt]=None
      def tick(): Unit = {
        val request=d.io.memoryRequest
        if(request.valid.peek().litToBoolean && request.bits.isWrite.peek().litToBoolean &&
            request.bits.address.peek().litValue==0x9000 && firstStoreSeen<0) firstStoreSeen=cycle
        val holdStore=firstStoreSeen>=0 && cycle-firstStoreSeen<13
        request.ready.poke((!holdStore && cycle%7!=0).B)
        d.io.globalAtomicRequest.ready.poke((cycle%11==10).B)
        val reply=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach { r =>
          d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U)
          d.io.memoryResponse.bits.fault.poke(r.fault.B)
        }
        // Publish writes only on acknowledgement, never on request acceptance.
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){
          val r=pending.dequeue()
          if(r.write && !r.fault) for(i<-0 until 64 if r.mask.testBit(i))
            memory(r.address+i)=((r.data>>(8*i))&255).toInt
          if(r.write && r.address==0x9000) storeAcknowledged=true
        }
        heldMemory.foreach(v => {request.valid.expect(true.B); assert(request.bits.peek().litValue==v)})
        if(request.valid.peek().litToBoolean){
          val q=request.bits; val at=q.address.peek().litValue; val write=q.isWrite.peek().litToBoolean
          assert(Set[BigInt](0x1000,0x9000,0x9040,0xa000)(at),s"unexpected address $at")
          if(at==0x9040) assert(atomicAcknowledged,"post-fence load overtook atomic acknowledgement")
          if(at==0xa000) assert(mode=="healthy" && atomicAcknowledged,"faulted or unacknowledged work published output")
          if(request.ready.peek().litToBoolean){
            if(write){
              assert(at==0x9000 || at==0xa000)
              val allowed=if(at==0x9000)BigInt(15) else BigInt(0xfff)
              assert((q.byteMask.peek().litValue & ~allowed)==0)
              stores+=1
            }
            pending.enqueue(Reply(q.transactionId.peek().litValue,at,
              if(write)q.writeData.peek().litValue else line(at),q.byteMask.peek().litValue,
              write,write && at==0x9000 && mode=="store-fault",cycle+(if(write)23 else 5)))
            heldMemory=None
          }else heldMemory=Some(q.peek().litValue)
        }
        val atomic=d.io.globalAtomicRequest
        heldAtomic.foreach(v => {atomic.valid.expect(true.B);assert(atomic.bits.peek().litValue==v)})
        if(atomic.valid.peek().litToBoolean){
          assert(storeAcknowledged && mode!="store-fault","AMO overtook or followed a faulted store")
          atomic.bits.address.expect(0x9040.U); atomic.bits.operand.expect(7.U)
          atomic.bits.operation.expect(AtomicMemoryOp.add); atomic.bits.warpId.expect(0.U)
          if(atomic.ready.peek().litToBoolean){
            assert(atomicDue<0); atomicOld=word(0x9040); atomicDue=cycle+31
            atomicRequests+=1; heldAtomic=None
          }else heldAtomic=Some(atomic.bits.peek().litValue)
        }
        val atomicReply=atomicDue>=0 && cycle>=atomicDue
        d.io.globalAtomicResponse.valid.poke(atomicReply.B)
        d.io.globalAtomicResponse.bits.warpId.poke(0.U)
        d.io.globalAtomicResponse.bits.oldValue.poke(atomicOld.U)
        d.io.globalAtomicResponse.bits.fault.poke((mode=="atomic-fault").B)
        d.io.globalAtomicResponse.bits.pageFault.poke(false.B)
        if(atomicReply && d.io.globalAtomicResponse.ready.peek().litToBoolean){
          if(mode!="atomic-fault") put(0x9040,atomicOld+7)
          atomicDue= -1; atomicAcknowledged=true
        }
        if(d.io.committedWriteback.valid.peek().litToBoolean){
          val q=d.io.committedWriteback.bits; val rd=q.rd.peek().litValue.toInt
          if(Set(10,11,12)(rd)){
            assert(storeAcknowledged && mode!="store-fault")
            if(rd!=10) assert(atomicAcknowledged && mode=="healthy")
            assert(!commits.contains(rd));commits(rd)=q.data.peek().litValue
          }
        }
        if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) traps+=1
        d.clock.step(); cycle+=1
      }
      def until(condition: => Boolean): Unit = {
        var n=0;while(!condition && n<4000){tick();n+=1}
        assert(condition,s"fence stalled mode=$mode cycle=$cycle pending=${pending.size}")
      }
      for(next<-Seq("store-fault","atomic-fault","healthy")){
        mode=next; firstStoreSeen= -1; storeAcknowledged=false; atomicAcknowledged=false
        commits.clear(); val beforeAtomics=atomicRequests; val beforeStores=stores
        d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits)); d.io.kernel.bits.kernelPc.poke(0x1000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
        if(mode!="healthy"){
          until(d.io.trap.valid.peek().litToBoolean)
          d.io.trap.bits.pc.expect((if(mode=="store-fault")0x1008 else 0x1018).U)
          d.io.trap.bits.cause.expect(7.U)
          d.io.trap.bits.tval.expect((if(mode=="store-fault")0x9000 else 0x9040).U)
          val held=d.io.trap.bits.peek().litValue
          for(_<-0 until 7){assert(d.io.trap.bits.peek().litValue==held);d.io.trap.valid.expect(true.B)
            d.io.completion.valid.expect(false.B);tick()}
          d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B)
        }
        until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect((mode=="healthy").B)
        assert(pending.isEmpty && atomicDue<0 && heldMemory.isEmpty && heldAtomic.isEmpty)
        assert(atomicRequests-beforeAtomics==(if(mode=="store-fault")0 else 1))
        assert(stores-beforeStores==(if(mode=="healthy")4 else 1))
        val expected=if(mode=="store-fault")Map.empty[Int,BigInt]
          else if(mode=="atomic-fault")Map(10->BigInt(7)) else Map(10->BigInt(7),11->BigInt(41),12->BigInt(48))
        assert(commits.toMap==expected,s"wrong ordered results $commits expected $expected")
        if(mode=="healthy"){
          assert(word(0xa000)==7 && word(0xa004)==41 && word(0xa008)==48 && word(0x9040)==48)
        }else assert((0xa000 until 0xa00c).forall(at=>memory(BigInt(at))==0xa5))
        for(at<-(0x9ff0 until 0xa000)++(0xa00c until 0xa040)) assert(memory(BigInt(at))==0xa5)
        for(_<-0 until 7){d.io.completion.valid.expect(true.B);tick()}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      assert(traps==2)
      info("CU_FENCE_ORDERING kernels=3 delayedStoreAck=true delayedAtomicAck=true preciseFaults=2 noFaultPublication=true healthyReuse=true exactWords=true")
    }
  }
}
