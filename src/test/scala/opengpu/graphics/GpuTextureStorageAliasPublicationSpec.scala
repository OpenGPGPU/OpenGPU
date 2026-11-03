package opengpu.graphics

import opengpu.system.GpuSystem

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Local integration qualification: real CU instructions, private caches and
  * banked L2; only lower memory is modeled. No API memory-model claim is made.
  */
class GpuTextureStorageAliasPublicationSpec extends AnyFlatSpec {
  import NativeTextureFetchTestData._
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "publish storage-written texels to a polling CU through shared L2" in {
    // Small two-CU caches keep this an ordering check, not a product
    // capacity or throughput qualification.
    simulate(new GpuSystem(GpuConfig(lanes=4, warps=2, l2Sets=8, l2Ways=2), numComputeUnits=2,
      storageAvailable=true, computeTextureAvailable=true, transactionsPerCu=16,
      instructionCacheSets=4, instructionCacheWays=1,
      vectorCacheSets=4, vectorCacheWays=1)) { d =>
      for((translated,policy) <- Seq((false,0),(true,0),(true,2))) {
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
      def addi(rd: Int, value: Int): BigInt = (BigInt(value)<<20)|(BigInt(rd)<<7)|0x13
      def load(rd: Int, offset: Int): BigInt =
        (BigInt(offset)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|0x03
      def store(offset: Int): BigInt =
        (BigInt(offset>>5)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(2)<<12)|(BigInt(offset&31)<<7)|0x23
      def bne(rs1: Int, rs2: Int, offset: Int): BigInt = {
        val imm=offset&0x1fff
        (BigInt((imm>>12)&1)<<31)|(BigInt((imm>>5)&63)<<25)|
          (BigInt(rs2)<<20)|(BigInt(rs1)<<15)|0x1000|
          BigInt(((imm>>1)&15)<<8)|BigInt(((imm>>11)&1)<<7)|0x63
      }
      def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
        (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
      def storage(fn: Int, vs1: Int, vs2: Int = 0, rd: Int = 0): BigInt =
        (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
      def texturePut(a:BigInt,v:BigInt):Unit=put(a.toLong,v)
      def textureByte(a:BigInt,v:Int):Unit={memory(a.toLong)=v}
      roots(texturePut,stage=2);install(texturePut,textureByte,0,14,stage=2)
      coordinates(texturePut,x=0,y=0)
      val descriptor=Array.fill[BigInt](16)(0)
      // Two Sv32 superpages map different virtual addresses onto one physical
      // page: texture uses 0x20000; storage uses 0x420000 when translated.
      val storageBase=if(translated) BigInt(0x420000) else BigInt(0x20000)
      for((i,v) <- Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,
        3->7,4->11,5->13,6->0,7->1,8->1,9->1,10->storageBase,11->(storageBase+4096),
        13->storageBase,14->(storageBase+4096))) descriptor(i)=v
      descriptor.zipWithIndex.foreach { case(v,i) => put(0xa000 + 4*i,v) }
      if(translated) {
        put(0x90000,0xcf | (policy<<8));put(0x90004,0xcf | (policy<<8))
        d.io.vectorSatp.poke("h80000090".U)
      }
      val base=BigInt(0x000202b7) // lui x5,0x20
      val fence=BigInt(0x0330000f)
      val cease=BigInt(0x30500073)
      val packetAddress=BigInt("0c008393",16) // addi x7,x1,192
      val unit0=addi(2,0)
      val first=scalar(0,rd=11,rs1=7,rs2=2)
      val after=scalar(0,rd=12,rs1=7,rs2=2)
      program(0x1000,Seq(packetAddress,unit0,first,base,load(10,68),cease))
      for(epoch <- 1 to 2) {
        // The producer starts only after the peer reads the old texel and flag.
        program(epoch*0x2000,Seq(packetAddress,unit0,first,base,addi(9,epoch),
          load(10,68),bne(10,9,-4),fence,after,cease))
        val dataWrite=Seq(BigInt("c100f057",16),vector(23,3,2,0),
          vector(23,3,3,epoch*7),addi(7,60),vector(23,4,4,7),
          storage(14,3),storage(16,2,4))
        program(epoch*0x2000+0x1000,Seq(base) ++ dataWrite ++
          Seq(fence,addi(6,epoch),store(68),cease))
      }
      put(0x20038,0x11223344);put(0x2003c,0)
      put(0x20040,0x11223344);put(0x20044,0);put(0x20048,0x55667788)

      case class Reply(id: BigInt, address: Long, data: BigInt, due: Int,
                       write: Option[(BigInt,BigInt)])
      val pending=mutable.ArrayBuffer.empty[Reply]
      var held=Option.empty[Reply]
      var heldRequest=Option.empty[BigInt]
      var cycle=0
      var allowReplies=false
      var epoch=0
      var dataAcknowledged=false
      var flagAcknowledged=false
      var pageWalkReads=0
      var writeRequests=0
      var writeAcks=0
      var delayedDataCycles=0
      var heldCompletionCycles=0
      val oldData=mutable.Set.empty[Int]
      val oldFlag=mutable.Set.empty[Int]
      val pollCounts=mutable.Map.empty[Int,Int]
      val newFlag=mutable.Set.empty[Int]
      val newData=mutable.Set.empty[Int]
      val completed=mutable.Set.empty[Int]

      def tick(): Unit = {
        d.io.memoryRequest.ready.poke((cycle%7<4).B)
        if(allowReplies && held.isEmpty && cycle%11>=6) {
          val i=pending.lastIndexWhere { r =>
            val producerFetch=epoch>0 && r.address==epoch*0x2000L+0x1000
            r.due<=cycle && (!producerFetch ||
              (oldData.size==1 && oldFlag.size==1 && pollCounts.values.forall(_>=2)))
          }
          if(i>=0) held=Some(pending.remove(i))
        }
        d.io.memoryResponse.valid.poke(held.nonEmpty.B)
        held.foreach { r =>
          d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U)
          d.io.memoryResponse.bits.fault.poke(false.B)
        }
        val accepted=held.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        if(accepted) held.get.write.foreach { case(data,mask) =>
          val address=held.get.address
          assert(epoch>0)
          if(address==0x20000) {
            assert(mask==(BigInt(15)<<60))
            assert(!dataAcknowledged && !flagAcknowledged)
            assert(((data>>480)&BigInt("ffffffff",16))==epoch*7)
            dataAcknowledged=true
          } else {
            assert(address==0x20040 && mask==0xf0 && dataAcknowledged && !flagAcknowledged)
            assert(((data>>32)&BigInt("ffffffff",16))==epoch)
            flagAcknowledged=true
          }
          // Deliberately publish only when the write response is accepted.
          for(i <- 0 until 64 if mask.testBit(i)) memory(address+i)=((data>>(8*i))&255).toInt
          writeAcks+=1
        }
        for(cu <- 0 until 2) {
          d.io.trap(cu).valid.expect(false.B)
          val wb=d.io.committedWriteback(cu)
          if(wb.valid.peek().litToBoolean) {
            val rd=wb.bits.rd.peek().litValue.toInt
            val value=wb.bits.data.peek().litValue
            if(rd==11) {
              assert(!oldData(cu) && value==math.max(0,epoch-1)*7,s"old data CU$cu epoch=$epoch value=$value")
              oldData+=cu
            }
            if(rd==10) {
              if(value==math.max(0,epoch-1)) {
                oldFlag+=cu
                pollCounts(cu)=pollCounts.getOrElse(cu,0)+1
              }
              else {
                assert(epoch>0 && value==epoch && dataAcknowledged,s"flag overtook data acknowledgement CU$cu")
                newFlag+=cu
              }
            }
            if(rd==12) {
              assert(epoch>0 && newFlag(cu) && dataAcknowledged && !newData(cu))
              assert(value==epoch*7,s"stale data after published flag CU$cu epoch=$epoch value=$value")
              newData+=cu
            }
          }
        }
        if(d.io.commandCompletion.valid.peek().litToBoolean && d.io.commandCompletion.ready.peek().litToBoolean) {
          val c=d.io.commandCompletion.bits
          val id=c.commandId.peek().litValue.toInt
          assert(id>=0 && id<2 && !completed(id))
          c.success.expect(true.B); c.status.expect(0.U); completed+=id
          if(epoch>0 && id==1) assert(flagAcknowledged,"producer completed before flag acknowledgement")
        }
        val q=d.io.memoryRequest
        heldRequest.foreach { bits => q.valid.expect(true.B); assert(q.bits.peek().litValue==bits) }
        val request=if(q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
          val address=q.bits.address.peek().litValue.toLong
          val writing=q.bits.isWrite.peek().litToBoolean
          if(!writing && address==0x90000)pageWalkReads+=1
          val write=if(writing)Some((q.bits.writeData.peek().litValue,q.bits.byteMask.peek().litValue))else None
          if(writing) {
            assert(epoch>0 && (address==0x20000 || address==0x20040))
            if(address==0x20000) q.bits.byteMask.expect((BigInt(15)<<60).U)
            else q.bits.byteMask.expect(0xf0.U)
            if(address==0x20040) assert(dataAcknowledged,"flag store overtook fenced data acknowledgement")
            writeRequests+=1
          }
          Some(Reply(q.bits.transactionId.peek().litValue,address,if(writing)BigInt(0)else line(address),
            cycle+(if(writing)73 else 5+(address%13).toInt),write))
        } else None
        heldRequest=if(q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean)Some(q.bits.peek().litValue)else None
        if(pending.exists(r=>r.address==0x20000 && r.write.nonEmpty && r.due>cycle))delayedDataCycles+=1
        if(accepted)held=None
        d.clock.step(); cycle+=1; request.foreach(pending+=_)
      }
      def until(label: String)(condition: => Boolean): Unit = {
        val start=cycle
        while(!condition && cycle-start<16000)tick()
        assert(condition,s"$label stalled epoch=$epoch cycle=$cycle busy=${d.io.busyComputeUnits.peek().litValue} oldData=$oldData oldFlag=$oldFlag newFlag=$newFlag newData=$newData completed=$completed pending=$pending")
      }
      for(round <- 0 to 2) {
        epoch=round; allowReplies=false; dataAcknowledged=false; flagAcknowledged=false
        oldData.clear(); oldFlag.clear(); newFlag.clear(); newData.clear(); completed.clear()
        pollCounts.clear()
        d.io.vmContextIdle.expect(true.B)
        for(id <- 0 until 2) {
          d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
          d.io.command.bits.commandId.poke(id.U)
          val task=d.io.command.bits.storage.get
          task.enabled.poke(true.B); task.table.version.poke(1.U)
          task.table.base.poke(0xa000.U); task.table.limit.poke(0xa100.U)
          task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U)
          task.table.identity.generation.poke(13.U)
          val texture=d.io.command.bits.computeTexture.get
          texture.enabled.poke(true.B);texture.resources.version.poke(1.U);texture.resources.address.poke(root.U)
          texture.identity.context.poke(7.U);texture.identity.pass.poke(11.U);texture.identity.generation.poke(13.U)
          d.io.command.bits.launch.kernargAddress.poke((packet-192).U)
          val pc=if(epoch==0)0x1000 else epoch*0x2000+(if(id==1)0x1000 else 0)
          d.io.command.bits.launch.kernelPc.poke(pc.U)
          for(i <- 0 until 3) {
            d.io.command.bits.launch.gridSize(i).poke(1.U)
            d.io.command.bits.launch.localSize(i).poke(1.U)
          }
          d.io.command.valid.poke(true.B)
          until("submit")(d.io.command.ready.peek().litToBoolean)
          tick(); d.io.command.valid.poke(false.B)
        }
        until("both CUs active")(d.io.busyComputeUnits.peek().litValue==3)
        allowReplies=true
        until("held public completion")(d.io.commandCompletion.valid.peek().litToBoolean)
        val completion=d.io.commandCompletion.bits.peek().litValue
        for(_ <- 0 until 9) {
          d.io.commandCompletion.valid.expect(true.B)
          assert(d.io.commandCompletion.bits.peek().litValue==completion)
          d.io.vmContextIdle.expect(false.B); tick(); heldCompletionCycles+=1
        }
        d.io.commandCompletion.ready.poke(true.B)
        until("two completions")(completed.size==2)
        d.io.commandCompletion.ready.poke(false.B)
        until("context drain")(d.io.vmContextIdle.peek().litToBoolean)
        assert(pending.isEmpty && held.isEmpty && heldRequest.isEmpty)
        if(epoch==0)assert(oldData.size==2 && oldFlag.size==2 && writeRequests==0)
        else {
          assert(oldData.size==1 && oldData==oldFlag && oldData==newFlag && oldData==newData)
          assert(dataAcknowledged && flagAcknowledged && writeRequests==epoch*2 && writeAcks==epoch*2)
          assert(word(0x2003c)==epoch*7 && word(0x20044)==epoch)
        }
        assert(word(0x20038)==BigInt(0x11223344) && word(0x20040)==BigInt(0x11223344) && word(0x20048)==BigInt(0x55667788))
      }
      assert(delayedDataCycles>=140 && heldCompletionCycles==27)
      assert(if(translated)pageWalkReads>0 else pageWalkReads==0)
      info(s"TEXTURE_STORAGE_ALIAS PASS translated=$translated policy=$policy CUs=2 lanes=4 warps=2 kernels=6 publications=2 consumers=2 delayedDataAck=true realL1L2=true exactGuards=true heldCompletion=true ownerReuse=true cycles=$cycle")
      }
    }
  }
}
