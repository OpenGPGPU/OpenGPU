package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Real CU instruction/data caches, translation and banked L2. Only lower
  * memory is modeled; no atomic request or invalidation is answered by a stub.
  */
class GpuSystemGlobalAtomicVmSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "serialize four translated CU atomics through real L2 coherence and recover attributed faults" in {
    // Keep the architectural lane/warp counts and all four L2 banks. Small
    // caches bound simulation cost; this is not a product capacity/rate check.
    simulate(new GpuSystem(GpuConfig(l2Sets=8, l2Ways=2), numComputeUnits=4,
      instructionCacheSets=4, instructionCacheWays=1,
      vectorCacheSets=4, vectorCacheWays=1)) { d =>
      zero(d.io)
      d.io.graphicsDrained.poke(true.B)
      d.io.graphicsHostResponse.ready.poke(true.B)
      d.io.graphicsShaderResponse.ready.poke(true.B)
      d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.graphicsShaderAtomicResponse.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

      val memory=mutable.LongMap.empty[Int]
      def put(address: Long, value: BigInt): Unit =
        for(i <- 0 until 4) memory(address+i)=((value >> (8*i)) & 255).toInt
      def word(address: Long): BigInt =
        (0 until 4).map(i => BigInt(memory.getOrElse(address+i,0)) << (8*i)).sum
      def line(address: Long): BigInt =
        (0 until 64).map(i => BigInt(memory.getOrElse(address+i,0)) << (8*i)).sum
      def program(address: Long, words: Seq[BigInt]): Unit =
        words.zipWithIndex.foreach { case(v,i) => put(address+4*i,v) }
      val virtual=BigInt(0x00804004)
      val cease=BigInt("30500073",16)
      // x5=virtual; load x11, or amoadd.w x10,x6,(x5) with x6=7.
      program(0x1000,Seq(BigInt(0x008042b7),BigInt(0x00428293),BigInt(0x0002a583),cease))
      program(0x2000,Seq(BigInt(0x008042b7),BigInt(0x00428293),BigInt(0x00700313),
        BigInt(0x0062a52f),cease))
      def mapping(root: Long, physicalPage: Long, flags: Int): Unit = {
        put(root+8,(BigInt((root+0x1000)>>12)<<10)|1)
        put(root+0x1010,(BigInt(physicalPage>>12)<<10)|flags)
      }
      mapping(0x30000,0x90000,0xc7)  // cached readable/writable
      mapping(0x40000,0x90000,0x2c7) // uncached alias of the SAME physical word
      mapping(0x50000,0x90000,0x243) // readable, store permission denied
      mapping(0x60000,0x90000,0x2c7) // physical PTE-fetch failure
      mapping(0x70000,0xa0000,0x2c7) // physical AMO read failure
      mapping(0x80000,0xb0000,0x2c7) // physical AMO write failure
      put(0x90000,0x11223344); put(0x90004,100); put(0x90008,0x55667788)
      put(0xb0004,55)

      case class Reply(id: BigInt, data: BigInt, fault: Boolean, due: Int,
                       write: Option[(Long,BigInt,BigInt)])
      val pending=mutable.ArrayBuffer.empty[Reply]
      var held=Option.empty[Reply]
      var heldRequest=Option.empty[BigInt]
      var cycle=0
      var allowReplies=false
      var readFault=Set.empty[Long]
      var writeFault=Set.empty[Long]
      var phase=""
      var expectedLoad=Option.empty[BigInt]
      val loads=mutable.Map.empty[Int,BigInt]
      val atomics=mutable.Map.empty[Int,BigInt]
      val acknowledgedOld=mutable.Set.empty[BigInt]
      val completions=mutable.Map.empty[Int,Boolean]
      var readRequests=0; var writeRequests=0; var writeAcks=0; var traps=0
      var heldWriteCycles=0

      def tick(): Unit = {
        d.io.memoryRequest.ready.poke((cycle%7<4).B)
        if(allowReplies && held.isEmpty && cycle%11>=6) {
          val i=pending.lastIndexWhere(_.due<=cycle)
          if(i>=0) held=Some(pending.remove(i))
        }
        d.io.memoryResponse.valid.poke(held.nonEmpty.B)
        held.foreach { r =>
          d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U)
          d.io.memoryResponse.bits.fault.poke(r.fault.B)
        }
        // Publication happens at the actual accepted write reply, never at
        // the offered/accepted write request. Failed writes change no bytes.
        val accepted=held.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        if(accepted) {
          val r=held.get
          r.write.foreach { case(address,data,mask) =>
            if(!r.fault) {
              assert(address==0x90000 && mask==0xf0,s"unexpected AMO write $address mask=$mask phase=$phase")
              val next=(data>>32)&BigInt("ffffffff",16)
              assert(next==word(0x90004)+7,s"lost or repeated RMW in $phase")
              acknowledgedOld+=word(0x90004)
              for(i<-0 until 64 if mask.testBit(i)) memory(address+i)=((data>>(8*i))&255).toInt
            }
            writeAcks+=1
          }
        }
        for(cu<-0 until 4) {
          val wb=d.io.committedWriteback(cu)
          if(wb.valid.peek().litToBoolean) {
            if(wb.bits.rd.peek().litValue==11) {
              assert(expectedLoad.nonEmpty && !loads.contains(cu),s"unexpected/repeated load CU$cu $phase")
              val v=wb.bits.data.peek().litValue
              assert(v==expectedLoad.get,s"stale coherent alias CU$cu in $phase: $v != ${expectedLoad.get}")
              loads(cu)=v
            }
            if(wb.bits.rd.peek().litValue==10) {
              val old=wb.bits.data.peek().litValue
              assert(!atomics.contains(cu) && acknowledgedOld.remove(old),
                s"AMO retired before successful lower acknowledgement or duplicated CU$cu old=$old phase=$phase")
              atomics(cu)=old
            }
          }
          if(d.io.trap(cu).valid.peek().litToBoolean && d.io.trap(cu).ready.peek().litToBoolean) traps+=1
        }
        if(d.io.commandCompletion.valid.peek().litToBoolean && d.io.commandCompletion.ready.peek().litToBoolean) {
          val c=d.io.commandCompletion.bits; val id=c.commandId.peek().litValue.toInt
          assert(id>=0 && id<4 && !completions.contains(id),s"wrong/duplicate completion $id $phase")
          completions(id)=c.success.peek().litToBoolean
          c.status.expect((if(completions(id))0 else 1).U)
        }
        val q=d.io.memoryRequest
        heldRequest.foreach { bits => q.valid.expect(true.B); assert(q.bits.peek().litValue==bits) }
        val request=if(q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
          val address=q.bits.address.peek().litValue.toLong
          assert(address<0x00800000L,s"virtual address escaped to lower memory: $address")
          val writing=q.bits.isWrite.peek().litToBoolean
          val write=if(writing)Some((address,q.bits.writeData.peek().litValue,q.bits.byteMask.peek().litValue))else None
          if(writing) {
            writeRequests+=1
            assert(address==0x90000L || address==0xb0000L)
            q.bits.byteMask.expect(0xf0.U)
          } else readRequests+=1
          val fault=if(writing)writeFault(address& ~63L)else readFault(address& ~63L)
          Some(Reply(q.bits.transactionId.peek().litValue,if(writing)BigInt(0)else line(address),fault,
            cycle+(if(writing)47 else 5+(address%13).toInt),write))
        }else None
        heldRequest=if(q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean)Some(q.bits.peek().litValue)else None
        if(pending.exists(r=>r.write.nonEmpty && r.due>cycle))heldWriteCycles+=1
        if(accepted)held=None
        d.clock.step(); cycle+=1
        request.foreach(pending+=_)
      }
      def until(label: String)(condition: => Boolean): Unit = {
        val start=cycle
        while(!condition && cycle-start<16000)tick()
        assert(condition,s"$label stalled in $phase at $cycle busy=${d.io.busyComputeUnits.peek().litValue} pending=${pending.size} held=$held loads=$loads atomics=$atomics completions=$completions")
      }
      def round(name: String, root: Long, pc: Int, load: Option[BigInt]=None,
                cause: Option[Int]=None, failRead: Set[Long]=Set.empty,
                failWrite: Set[Long]=Set.empty): Unit = {
        phase=name; expectedLoad=load; readFault=failRead; writeFault=failWrite
        loads.clear();atomics.clear();completions.clear();acknowledgedOld.clear()
        val beforeWrites=writeRequests;val beforeAcks=writeAcks;val beforeTraps=traps
        val before=word(0x90004)
        // Change roots/ASIDs only at the real context-idle boundary. AMOs
        // must walk their own root without requiring a data-TLB flush.
        d.io.vmContextIdle.expect(true.B)
        d.io.vectorSatp.poke(((BigInt(1)<<31)|(BigInt(root>>12)<<22)|(root>>12)).U)
        allowReplies=false
        for(id<-0 until 4) {
          d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
          d.io.command.bits.commandId.poke(id.U)
          d.io.command.bits.launch.kernelPc.poke(pc.U)
          for(i<-0 until 3) {
            d.io.command.bits.launch.gridSize(i).poke(1.U)
            d.io.command.bits.launch.localSize(i).poke(1.U)
          }
          d.io.command.valid.poke(true.B)
          until("submit")(d.io.command.ready.peek().litToBoolean)
          tick();d.io.command.valid.poke(false.B)
        }
        until("all four CUs own work")(d.io.busyComputeUnits.peek().litValue==15)
        allowReplies=true
        cause.foreach { code =>
          until("four precise traps")(d.io.trap.forall(_.valid.peek().litToBoolean))
          val values=d.io.trap.map(_.bits.peek().litValue)
          for(_<-0 until 9) {
            for(cu<-0 until 4) {
              d.io.trap(cu).valid.expect(true.B)
              d.io.trap(cu).bits.cause.expect(code.U)
              d.io.trap(cu).bits.tval.expect(virtual.U)
              d.io.trap(cu).bits.pc.expect(0x200c.U)
              d.io.trap(cu).bits.activeMask.expect(1.U)
              assert(d.io.trap(cu).bits.peek().litValue==values(cu))
            }
            d.io.commandCompletion.valid.expect(false.B);tick()
          }
          d.io.trap.foreach(_.ready.poke(true.B))
          until("trap drain")(traps-beforeTraps==4)
          d.io.trap.foreach(_.ready.poke(false.B))
        }
        until("held public completion")(d.io.commandCompletion.valid.peek().litToBoolean)
        val completion=d.io.commandCompletion.bits.peek().litValue
        for(_<-0 until 9) {
          d.io.commandCompletion.valid.expect(true.B)
          assert(d.io.commandCompletion.bits.peek().litValue==completion)
          d.io.vmContextIdle.expect(false.B);tick()
        }
        d.io.commandCompletion.ready.poke(true.B)
        until("four completions")(completions.size==4)
        d.io.commandCompletion.ready.poke(false.B)
        until("full context drain")(d.io.vmContextIdle.peek().litToBoolean)
        assert(pending.isEmpty && held.isEmpty && heldRequest.isEmpty && acknowledgedOld.isEmpty)
        assert(completions.values.forall(_ == cause.isEmpty))
        assert(traps-beforeTraps==(if(cause.nonEmpty)4 else 0))
        if(load.nonEmpty) {
          assert(loads.keySet==Set(0,1,2,3) && atomics.isEmpty)
          assert(writeRequests==beforeWrites && writeAcks==beforeAcks)
        }else if(cause.isEmpty) {
          assert(atomics.keySet==Set(0,1,2,3))
          assert(atomics.values.toSet==(0 until 4).map(i=>before+7*i).toSet)
          assert(word(0x90004)==before+28)
          assert(writeRequests-beforeWrites==4 && writeAcks-beforeAcks==4)
        }else {
          assert(atomics.isEmpty && word(0x90004)==before && word(0xb0004)==55)
          val expected=if(failWrite.nonEmpty)4 else 0
          assert(writeRequests-beforeWrites==expected && writeAcks-beforeAcks==expected)
        }
        assert(word(0x90000)==BigInt(0x11223344) && word(0x90008)==BigInt(0x55667788))
      }

      round("populate four cached aliases",0x30000,0x1000,load=Some(100))
      // Model a noncoherent producer, leaving all four private aliases stale.
      put(0x90004,1000)
      round("uncached contended AMOs",0x40000,0x2000)
      round("read after actual L1 invalidations",0x30000,0x1000,load=Some(1028))
      round("cached contended AMOs",0x30000,0x2000)
      round("denied translation",0x50000,0x2000,cause=Some(15))
      round("PTE access faults",0x60000,0x2000,cause=Some(7),failRead=Set(0x60000L))
      round("physical AMO read faults",0x70000,0x2000,cause=Some(7),failRead=Set(0xa0000L))
      round("physical AMO write faults",0x80000,0x2000,cause=Some(7),failWrite=Set(0xb0000L))
      round("healthy command ID and owner reuse",0x40000,0x2000)
      assert(word(0x90004)==1084 && traps==16 && writeRequests==16 && writeAcks==16)
      assert(heldWriteCycles>=16*40 && readRequests>0)
      info(s"MULTICU_GLOBAL_AMO PASS CUs=4 lanes=16 warps=8 kernels=36 successfulRMWs=12 preciseFaults=16 lowerWriteReplies=16 cycles=$cycle productCapacityQualified=false clockQualified=false FPSQualified=false")
    }
  }
}
