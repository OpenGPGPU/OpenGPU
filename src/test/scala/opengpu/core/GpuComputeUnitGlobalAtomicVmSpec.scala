package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.AtomicMemoryOp
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Instruction-driven single-warp control/fault coverage. The external atomic
  * responder checks physical requests; separate L2 tests cover actual RMWs.
  */
class GpuComputeUnitGlobalAtomicVmSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  for (payload <- Seq(false, true)) {
    it should s"translate instruction-driven AMOs and recover precise faults with payload guard=$payload" in {
      simulate(new GpuComputeUnit(GpuConfig(), instructionCacheSets=4,
        instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1,
        finishOnTrap=true, shaderPayload=if(payload)Some(ShaderPayloadConfig(owners=2,ports=2))else None)) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.l1InvalidateDone.ready.poke(true.B)
        d.io.payload.foreach { p =>
          p.task.valid.poke(true.B);p.task.bits.base.poke(0x200000.U)
          p.task.bits.bytes.poke(288.U)
        }
        val virtual = BigInt(0x00804004)
        val program = Seq(BigInt(0x008042b7), BigInt(0x00428293), // lui/addi x5,virtual
          BigInt(0x00700313), BigInt(0x0062a52f), BigInt(0x30500073)) // x6=7;amoadd.w x10;cease
        val code = program.zipWithIndex.foldLeft(BigInt(0)) { case (v,(word,i)) => v | (word << (32*i)) }
        case class Reply(id: BigInt, data: BigInt, fault: Boolean, due: Int)
        val pending = mutable.Queue.empty[Reply]
        var cycle=0;var mode="first";var atomicDue= -1;var atomicFault=false
        var atomicRequests=0;var commits=0;var traps=0;var pteReads=0
        var heldRequest: Option[BigInt]=None
        def tick(): Unit = {
          d.io.memoryRequest.ready.poke((cycle%7<5).B)
          d.io.globalAtomicRequest.ready.poke((cycle%11==10).B)
          val reply=pending.headOption.filter(_.due<=cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r =>
            d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)
          }
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue
            q.isWrite.expect(false.B)
            val root=if(mode=="first")0x30000 else 0x40000
            val table=root+0x1000
            val data=if(address==0x1000){q.sizeLog2.expect(6.U);code} else {
              pteReads+=1
              q.sizeLog2.expect(2.U);q.cachePolicy.expect(CachePolicy.uncached)
              assert(address==root+8 || address==table+16,s"unexpected PTE address $address mode=$mode")
              if(address==root+8)(BigInt(table>>12)<<10)|1
              else {
                val ppn=if(mode=="first")0x90 else 0xa0
                val flags=if(mode=="denied")0x243 else 0x2c7 // uncached; V|R|A[,W|D]
                (BigInt(ppn)<<10)|flags
              }
            }
            pending.enqueue(Reply(q.transactionId.peek().litValue,data,
              mode=="pte-access" && address==root+8,cycle+5))
          }
          val q=d.io.globalAtomicRequest
          heldRequest.foreach { v =>q.valid.expect(true.B);assert(q.bits.peek().litValue==v) }
          if(q.valid.peek().litToBoolean){
            assert(mode!="denied" && mode!="pte-access","failed translation offered a physical AMO")
            q.bits.address.expect((if(mode=="first")0x90004 else 0xa0004).U)
            q.bits.warpId.expect(0.U);q.bits.operand.expect(7.U)
            q.bits.operation.expect(AtomicMemoryOp.add);q.bits.cachePolicy.expect(CachePolicy.uncached)
            if(q.ready.peek().litToBoolean){
              assert(atomicDue<0);atomicDue=cycle+9;atomicFault=mode=="atomic-access"
              atomicRequests+=1;heldRequest=None
            }else heldRequest=Some(q.bits.peek().litValue)
          }
          val atomicReply=atomicDue>=0 && cycle>=atomicDue
          d.io.globalAtomicResponse.valid.poke(atomicReply.B)
          d.io.globalAtomicResponse.bits.warpId.poke(0.U)
          d.io.globalAtomicResponse.bits.oldValue.poke(41.U)
          d.io.globalAtomicResponse.bits.fault.poke(atomicFault.B)
          d.io.globalAtomicResponse.bits.pageFault.poke(false.B)
          if(atomicReply && d.io.globalAtomicResponse.ready.peek().litToBoolean)atomicDue= -1
          if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==10){
            assert(Set("first","remap","healthy")(mode),s"faulted AMO committed in $mode")
            d.io.committedWriteback.bits.data.expect(41.U);commits+=1
          }
          if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean)traps+=1
          d.clock.step();cycle+=1
        }
        def until(condition: => Boolean): Unit = {
          var n=0;while(!condition && n<1800){tick();n+=1}
          assert(condition,s"AMO stalled mode=$mode cycle=$cycle pending=${pending.size}")
        }
        def launch(next: String): Unit = {
          mode=next;d.io.vectorSatp.poke((BigInt(1)<<31 | (if(mode=="first")0x30 else 0x40)).U)
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits));d.io.kernel.bits.kernelPc.poke(0x1000.U)
          for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
          d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean)
          tick();d.io.kernel.valid.poke(false.B)
        }
        for(next<-Seq("first","remap","denied","pte-access","atomic-access","healthy")){
          val beforeRequests=atomicRequests;val beforeCommits=commits;val beforePtes=pteReads
          launch(next)
          val fault=Set("denied","pte-access","atomic-access")(next)
          if(fault){
            until(d.io.trap.valid.peek().litToBoolean)
            d.io.trap.bits.pc.expect(0x100c.U);d.io.trap.bits.tval.expect(virtual.U)
            d.io.trap.bits.cause.expect((if(next=="denied")15 else 7).U)
            d.io.trap.bits.warpId.expect(0.U);d.io.trap.bits.activeMask.expect(1.U)
            val held=d.io.trap.bits.peek().litValue
            for(_<-0 until 7){d.io.trap.valid.expect(true.B);assert(d.io.trap.bits.peek().litValue==held)
              d.io.completion.valid.expect(false.B);tick()}
            d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B)
          }
          until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect((!fault).B)
          assert(pending.isEmpty && atomicDue<0 && heldRequest.isEmpty)
          assert(commits-beforeCommits==(if(fault)0 else 1))
          assert(atomicRequests-beforeRequests==(if(next=="denied" || next=="pte-access")0 else 1))
          assert(pteReads-beforePtes==(if(next=="pte-access")1 else 2),"every AMO must walk its captured root")
          d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        }
        assert(traps==3 && commits==3 && atomicRequests==4)
      }
    }
  }
}
