package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Accepted task ownership, rather than later live pins, selects the sampler. */
class GpuComputeUnitTexturePolicySpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  for (vector <- Seq(false, true); (lanes,warps,sets,ways) <- Seq((4,2,4,1),(16,8,64,2))) {
    it should s"snapshot texture permission across held service, faults and same-CU owner changes vector=$vector lanes=$lanes warps=$warps" in {
      val config = GpuConfig(lanes=lanes, warps=warps)
      val fullMask = (BigInt(1)<<lanes)-1
      simulate(new GpuComputeUnit(config, finishOnTrap=true, taskTextureAvailability=true,
        instructionCacheSets=sets, instructionCacheWays=ways, vectorCacheSets=sets, vectorCacheWays=ways)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.l1InvalidateDone.ready.poke(true.B); d.io.globalAtomicRequest.ready.poke(true.B)
        val opcode = BigInt(if(vector) "0600052b" else "0200050b", 16)
        val cease = BigInt("30500073",16)
        val code = mutable.Map.empty[BigInt,BigInt]
        case class Read(id: BigInt, data: BigInt, due: Int)
        case class Sample(warp: BigInt, pc: BigInt, mask: BigInt, due: Int)
        val reads = mutable.Queue.empty[Read]
        val samples = mutable.Queue.empty[Sample]
        var cycle=0; var requests=0; var traps=0; var healthyWrites=0
        var allowed=false; var trapPc=BigInt(0)
        def tick(): Unit = {
          d.io.memoryRequest.ready.poke((cycle%5 != 0).B)
          val reply=reads.headOption.filter(_.due<=cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { q => d.io.memoryResponse.bits.transactionId.poke(q.id.U); d.io.memoryResponse.bits.readData.poke(q.data.U) }
          val popRead=reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
          val incoming=if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q=d.io.memoryRequest.bits; val a=q.address.peek().litValue
            assert(!q.isWrite.peek().litToBoolean)
            Some(Read(q.transactionId.peek().litValue,
              (0 until 16).map(i=>code.getOrElse(a+4*i,cease)<<(32*i)).sum,cycle+5))
          } else None
          d.io.texSample.ready.poke((cycle%7 != 0).B)
          d.io.vectorTexSample.ready.poke((cycle%7 != 0).B)
          val sample=if(vector && d.io.vectorTexSample.valid.peek().litToBoolean && d.io.vectorTexSample.ready.peek().litToBoolean) {
            val q=d.io.vectorTexSample.bits
            assert(((q.issued.decode.instruction.peek().litValue>>7)&31)==10)
            q.executionMask.expect(fullMask.U)
            Some(Sample(q.issued.decode.warpId.peek().litValue,q.issued.decode.pc.peek().litValue,
              q.issued.decode.activeMask.peek().litValue,cycle+13))
          } else if(!vector && d.io.texSample.valid.peek().litToBoolean && d.io.texSample.ready.peek().litToBoolean) {
            val q=d.io.texSample.bits
            assert(q.decode.decoded.rd.peek().litValue==10)
            Some(Sample(q.decode.warpId.peek().litValue,q.decode.pc.peek().litValue,
              q.decode.activeMask.peek().litValue,cycle+13))
          } else None
          sample.foreach(_ => assert(allowed,"unavailable task reached the sampler"))
          val result=samples.headOption.filter(_.due<=cycle)
          d.io.texWriteback.valid.poke((!vector && result.nonEmpty).B)
          d.io.vectorTexWriteback.valid.poke((vector && result.nonEmpty).B)
          result.foreach { q =>
            if(vector) {
              val p=d.io.vectorTexWriteback.bits
              p.poke(0.U.asTypeOf(p));p.pc.poke(q.pc.U);p.warpActiveMask.poke(q.mask.U)
              p.writesVd.poke(true.B);p.writeback.warpId.poke(q.warp.U);p.writeback.vd.poke(10.U)
              p.writeback.data.foreach(_.poke(0x12345678.U))
            } else {
              val p=d.io.texWriteback.bits
              p.warpId.poke(q.warp.U);p.nextPc.poke((q.pc+4).U);p.activeMask.poke(q.mask.U)
              p.writeRd.poke(true.B);p.rd.poke(10.U);p.data.poke(0x12345678.U)
            }
          }
          val popSample=result.nonEmpty && (if(vector) d.io.vectorTexWriteback.ready else d.io.texWriteback.ready).peek().litToBoolean
          if(d.io.trap.valid.peek().litToBoolean) {
            assert(!allowed,"serviced texture instruction faulted")
            d.io.trap.bits.cause.expect(2.U);d.io.trap.bits.pc.expect(trapPc.U)
            d.io.trap.bits.tval.expect(opcode.U);d.io.trap.bits.activeMask.expect(fullMask.U)
            if(d.io.trap.ready.peek().litToBoolean) traps+=1
          }
          if(vector && d.io.committedVectorWriteback.valid.peek().litToBoolean &&
              d.io.committedVectorWriteback.bits.vd.peek().litValue==10 &&
              d.io.committedVectorWriteback.bits.data(0).peek().litValue==7) {
            d.io.committedVectorWriteback.bits.data.foreach(_.expect(7.U));healthyWrites+=1
          }
          if(!vector && d.io.committedWriteback.valid.peek().litToBoolean &&
              d.io.committedWriteback.bits.rd.peek().litValue==10 &&
              d.io.committedWriteback.bits.data.peek().litValue==7) healthyWrites+=1
          d.clock.step();cycle+=1
          if(popRead)reads.dequeue();incoming.foreach(reads.enqueue(_))
          if(popSample)samples.dequeue();sample.foreach { q=>samples.enqueue(q);requests+=1 }
        }
        def until(label: String)(condition: => Boolean): Unit = {
          val start=cycle
          while(!condition && cycle-start<3000) tick()
          assert(condition,s"$label stalled: cycle=$cycle active=${d.io.active.peek().litValue}")
        }
        for((permission,frame)<-Seq(false,true,false,true).zipWithIndex) {
          allowed=permission
          val pc=0x1000+frame*0x200
          val prefix=if(vector) Seq(BigInt("0104f057",16)) else Seq.empty
          val suffix=BigInt(if(vector) "5e03b557" else "00700513",16) // vmv.v.i v10,7 or addi x10,x0,7
          (prefix++Seq(opcode,suffix,cease)).zipWithIndex.foreach{case(word,i)=>code(BigInt(pc+4*i))=word}
          trapPc=BigInt(pc+4*prefix.size)
          val oldRequests=requests;val oldTraps=traps;val oldWrites=healthyWrites
          d.io.trap.ready.poke(false.B);d.io.textureEnabled.get.poke(permission.B)
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
          d.io.kernel.bits.kernelPc.poke(pc.U);d.io.kernel.bits.kernargAddress.poke(0x8000.U)
          for(i<-0 until 3) {
            d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)lanes*warps else 1).U)
          }
          d.io.kernel.valid.poke(true.B);until("kernel acceptance")(d.io.kernel.ready.peek().litToBoolean)
          tick();d.io.kernel.valid.poke(false.B)
          // The launch policy may change immediately after the accepted task.
          d.io.textureEnabled.get.poke((!permission).B)
          if(!permission) {
            until("attributed rejection")(d.io.trap.valid.peek().litToBoolean)
            val held=d.io.trap.bits.peek().litValue
            for(_<-0 until 9) { assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);tick() }
            d.io.trap.ready.poke(true.B)
          }
          until("task completion")(d.io.completion.valid.peek().litToBoolean)
          d.io.completion.bits.success.expect(permission.B);d.io.active.expect(0.U)
          val held=d.io.completion.bits.peek().litValue
          for(_<-0 until 7) { assert(d.io.completion.bits.peek().litValue==held);tick() }
          assert(requests-oldRequests==(if(permission)warps else 0))
          assert(traps-oldTraps==(if(permission)0 else warps))
          assert(healthyWrites-oldWrites==(if(permission)warps else 0))
          assert(reads.isEmpty && samples.isEmpty)
          d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        }
        info(s"CU_TEXTURE_TASK_POLICY vector=$vector lanes=$lanes warps=$warps residentThreads=${lanes*warps} defaultCaches=${sets==64 && ways==2} acceptedTaskSnapshot=true livePinInverted=true allWarpOwnership=true " +
          "delayedSamplerReplies=true preciseIllegalTraps=true heldCompletions=true destinationAndTaskReuse=true")
      }
    }
  }
}
