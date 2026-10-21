package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.trap.TrapCause
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitTrapTerminationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for((name,instruction)<-Seq("ebreak"->BigInt("00100073",16),"ecall"->BigInt("00000073",16),
      "unsupported-csr"->BigInt("00101073",16),"illegal"->BigInt("ffffffff",16));fpu<-Seq(false,true))
  it should s"finish a $name shader fault exactly once and permit healthy reuse fpu=$fpu" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),enableFpuBackend=fpu,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,finishOnTrap=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B);d.io.l1InvalidateDone.ready.poke(true.B)
      d.io.globalAtomicRequest.ready.poke(true.B)
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.Queue.empty[Reply];var cycle=0;var traps=0;var writes=0
      def tick(): Unit={
        val reply=pending.headOption.filter(_.due<=cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
        if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue
          assert(address==0x1000 || address==0x2000,s"unexpected instruction request $address")
          assert(!q.isWrite.peek().litToBoolean,"trap-only shader unexpectedly wrote memory")
          val data=if(address==0x1000)instruction else BigInt("3050007300700293",16) // addi x5,7;cease
          pending.enqueue(Reply(q.transactionId.peek().litValue,data,cycle+5))
        }
        if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean)traps+=1
        if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==5){
          d.io.committedWriteback.bits.data.expect(7.U);writes+=1
        }
        d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<500){tick();n+=1};assert(condition,s"$name shader stalled at cycle=$cycle traps=$traps active=${d.io.active.peek().litValue}")}
      def launch(pc: Int): Unit={
        d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits));d.io.kernel.bits.kernelPc.poke(pc.U)
        d.io.kernel.bits.kernargAddress.poke(0x8000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke(1.U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
      }
      launch(0x1000);until(d.io.trap.valid.peek().litToBoolean)
      d.io.trap.bits.pc.expect(0x1000.U);d.io.trap.bits.tval.expect(instruction.U)
      d.io.trap.bits.cause.expect(TrapCause.illegalInstruction.U);d.io.trap.bits.warpId.expect(0.U)
      d.io.trap.bits.activeMask.expect(1.U);d.io.trap.bits.laneFaultMask.expect(1.U)
      val held=d.io.trap.bits.peek().litValue
      for(_<-0 until 9){d.io.trap.valid.expect(true.B);assert(d.io.trap.bits.peek().litValue==held)
        d.io.completion.valid.expect(false.B);tick()}
      d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect(false.B)
      d.io.active.expect(0.U);val completion=d.io.completion.bits.peek().litValue
      for(_<-0 until 7){tick();assert(d.io.completion.bits.peek().litValue==completion)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      launch(0x2000);until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.success.expect(true.B);d.io.active.expect(0.U)
      assert(traps==1 && writes==1 && pending.isEmpty)
      d.io.completion.ready.poke(true.B);tick()
      info(s"CU_TRAP_TERMINATION instruction=$name fpu=$fpu oneAttributedTrap=true heldTrap=true failedCompletion=true healthyReuse=true noDuplicateFinish=true")
    }
  }
}
