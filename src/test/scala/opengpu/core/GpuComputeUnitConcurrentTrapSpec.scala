package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitConcurrentTrapSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(fpu<-Seq(false,true))
  it should s"serialize CSR and load-fault ownership across two warps and reuse both destinations fpu=$fpu" in {
    simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),enableFpuBackend=fpu,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,finishOnTrap=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B);d.io.l1InvalidateDone.ready.poke(true.B);d.io.globalAtomicRequest.ready.poke(true.B)
      val code=Seq(0->BigInt("02041063",16),4->BigInt("00101573",16), // bne x8,0,+32; csrrw x10,fflags,x0
        32->BigInt("00009637",16),36->BigInt("00062583",16)).foldLeft(BigInt(0)){case(v,(offset,word))=>v|(word<<(offset*8))}
      val healthy=BigInt("305000730090059300700513",16) // addi x10,7; addi x11,9; cease
      case class Reply(id: BigInt,data: BigInt,fault: Boolean,due: Int)
      val replies=mutable.Queue.empty[Reply];val traps=mutable.Set.empty[Int];val writes=mutable.ArrayBuffer.empty[(Int,Int)]
      var cycle=0;var allowTraps=false;var faults=0
      def tick(): Unit={
        d.io.trap.ready.poke((allowTraps && cycle%5==2).B)
        d.io.memoryRequest.ready.poke((cycle%7<5).B)
        val response=replies.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
        if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)replies.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue;q.isWrite.expect(false.B)
          assert(Set[BigInt](0x1000,0x2000,0x9000)(address),s"unowned CU access $address")
          val failed=address==0x9000;if(failed)faults+=1
          replies.enqueue(Reply(q.transactionId.peek().litValue,if(address==0x1000)code else if(address==0x2000)healthy else BigInt(0),failed,cycle+7))
        }
        if(d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean){
          val q=d.io.trap.bits;val warp=q.warpId.peek().litValue.toInt;assert(traps.add(warp),s"duplicate trap warp$warp")
          q.activeMask.expect(15.U);q.laneFaultMask.expect(15.U)
          q.pc.expect((if(warp==0)0x1004 else 0x1024).U)
          q.cause.expect((if(warp==0)2 else 5).U);q.tval.expect((if(warp==0)BigInt("00101573",16)else BigInt(0x9000)).U)
        }
        if(d.io.committedWriteback.valid.peek().litToBoolean){
          val q=d.io.committedWriteback.bits;val rd=q.rd.peek().litValue.toInt
          if(rd==10 || rd==11){q.data.expect((if(rd==10)7 else 9).U);writes+=q.warpId.peek().litValue.toInt->rd}
        }
        d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<1500){tick();n+=1};assert(condition,s"CU trap ownership stalled at $cycle traps=$traps")}
      def launch(pc: Int): Unit={
        d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits));d.io.kernel.bits.kernelPc.poke(pc.U);d.io.kernel.bits.kernargAddress.poke(0x8000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)8 else 1).U)}
        d.io.kernel.valid.poke(true.B);until(d.io.kernel.ready.peek().litToBoolean);tick();d.io.kernel.valid.poke(false.B)
      }
      launch(0x1000);until(d.io.trap.valid.peek().litToBoolean)
      val held=d.io.trap.bits.peek().litValue
      for(_<-0 until 29){d.io.trap.valid.expect(true.B);assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);tick()}
      allowTraps=true;until(d.io.completion.valid.peek().litToBoolean)
      assert(traps==Set(0,1) && faults==1 && writes.isEmpty && replies.isEmpty)
      d.io.completion.bits.success.expect(false.B);d.io.active.expect(0.U)
      val done=d.io.completion.bits.peek().litValue;for(_<-0 until 7){tick();assert(d.io.completion.bits.peek().litValue==done)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      launch(0x2000);until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.success.expect(true.B);d.io.active.expect(0.U)
      assert(writes.toSet==Set(0->10,0->11,1->10,1->11) && writes.size==4 && traps.size==2)
      d.io.completion.ready.poke(true.B);tick()
      info(s"CU_CONCURRENT_TRAPS fpu=$fpu CSRrd10=true loadFaultRd11=true twoWarps=true heldTrapStable=true oneFaultPerOwner=true noFabricatedDestinationWrites=true destinationsReused=true")
    }
  }
}
