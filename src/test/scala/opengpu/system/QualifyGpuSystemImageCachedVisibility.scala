package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.TextureImageFormat
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.DoNotDiscover
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Local integration qualification: real CU instructions, private caches and
  * banked L2; only lower memory is modeled. No API memory-model claim is made.
  */
@DoNotDiscover
class GpuSystemImageCachedVisibilityQualification extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "publish fenced image stores to the writing CU and three cached polling peers with delayed acknowledgements and reuse" in {
    require(!GraphicsTestResolution.productQualification)
    // Preserve architectural lanes/warps and four L2 banks. Bounded caches
    // keep this an ordering check, not a product capacity/rate qualification.
    simulate(new GpuSystem(GpuConfig(l2Sets=8, l2Ways=2), numComputeUnits=4, imageAvailable=true, transactionsPerCu=8,
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
      // Put instructions in L2 sets4/5, away from data set0 and flag set1.
      // Otherwise a cold producer instruction line could evict/invalidate the
      // cached data before imageStore and hide a missing image invalidation.
      val base=BigInt(0x000902b7) // lui x5,0x90
      val fence=BigInt(0x0330000f) // fence rw,rw
      val cease=BigInt(0x30500073)
      program(0x1100,Seq(base,load(11,4),load(10,68),cease))
      def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int=0): BigInt =
        (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
      def image(fn: Int, pair: Int=0, vs1: Int=0, vs2: Int=0): BigInt =
        (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(pair)<<12)|0x2b
      val format=TextureImageFormat.id("R32UI")
      val control=(format<<6)|40 // unit0, uint, explicit R32UI format
      val descriptor=0x8000L
      val record=Array.fill[BigInt](64)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->BigInt("47495501",16),1->256,2->1,
        3->7,4->11,5->13,7->1,8->1,9->4,10->1,11->1,12->format,13->format,
        14->1,22->0x90000,23->0x91000,25->0x90004,26->1,27->1,28->1,29->1,30->4,37->4))record(i)=v
      record.zipWithIndex.foreach{case(v,i)=>put(descriptor+4*i,v)}
      for(epoch <- 1 to 2) {
        // Consumers cache the old data before polling a different cache line.
        // The producer fetch is withheld until every consumer observes old flag.
        program(epoch*0x2000+0x100,Seq(base,load(11,4),addi(9,epoch),load(10,68),
          bne(10,9,-4),fence,load(12,4),cease))
        // The writer also owns a cached old copy. Its only data write is a
        // real imageStore; after the fence, an ordinary load on that same CU
        // must observe the image value before it publishes the polling flag.
        val imageSetup=Seq(BigInt("0104f057",16),vector(23,3,2,0),
          BigInt(((control+0x800)& ~0xfff)|(14<<7)|0x37),
          BigInt(((control&0xfff).toLong<<20)|(14<<15)|(14<<7)|0x13),
          vector(23,4,3,14),image(8,vs1=2,vs2=3),image(8,pair=1,vs1=2,vs2=2),
          vector(23,3,4,epoch*7),image(8,pair=2,vs1=4,vs2=2),image(8,pair=3,vs1=2,vs2=2))
        program(epoch*0x2000+0x1100,Seq(base,load(11,4))++imageSetup++
          Seq(image(10,vs1=2,vs2=2),fence,load(12,4),addi(6,epoch),store(68),cease))
      }
      for(address <- Seq(0x90000L,0x90040L)) {
        put(address,0x11223344); put(address+4,0); put(address+8,0x55667788)
      }

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
      var dataReadRequests=0
      var beforeImageReadCount=0
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
            val producerFetch=epoch>0 && r.address==epoch*0x2000L+0x1100
            r.due<=cycle && (!producerFetch ||
              (oldData.size>=3 && oldFlag.size==3 && pollCounts.values.forall(_>=2)))
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
          assert(epoch>0 && mask==0xf0)
          if(address==0x90000) {
            assert(!dataAcknowledged && !flagAcknowledged)
            assert(((data>>32)&BigInt("ffffffff",16))==epoch*7)
            dataAcknowledged=true
          } else {
            assert(address==0x90040 && dataAcknowledged && !flagAcknowledged)
            assert(((data>>32)&BigInt("ffffffff",16))==epoch)
            flagAcknowledged=true
          }
          // Deliberately publish only when the write response is accepted.
          for(i <- 0 until 64 if mask.testBit(i)) memory(address+i)=((data>>(8*i))&255).toInt
          writeAcks+=1
        }
        for(cu <- 0 until 4) {
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
              assert(epoch>0 && (cu==3 || newFlag(cu)) && dataAcknowledged && !newData(cu))
              assert(value==epoch*7,s"stale data after published flag CU$cu epoch=$epoch value=$value")
              newData+=cu
            }
          }
        }
        if(d.io.commandCompletion.valid.peek().litToBoolean && d.io.commandCompletion.ready.peek().litToBoolean) {
          val c=d.io.commandCompletion.bits
          val id=c.commandId.peek().litValue.toInt
          assert(id>=0 && id<4 && !completed(id))
          c.success.expect(true.B); c.status.expect(0.U); completed+=id
          if(epoch>0 && id==3) assert(flagAcknowledged,"producer completed before flag acknowledgement")
        }
        val q=d.io.memoryRequest
        heldRequest.foreach { bits => q.valid.expect(true.B); assert(q.bits.peek().litValue==bits) }
        val request=if(q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
          val address=q.bits.address.peek().litValue.toLong
          val writing=q.bits.isWrite.peek().litToBoolean
          val write=if(writing)Some((q.bits.writeData.peek().litValue,q.bits.byteMask.peek().litValue))else None
          if(!writing && address==0x90000){
            if(epoch>0)assert(dataAcknowledged,"post-image cache refill overtook its store acknowledgement")
            dataReadRequests+=1
          }
          if(writing) {
            if(address==0x90000){
              assert(dataReadRequests==beforeImageReadCount,"data backing was refetched before the image store; cached witness was lost")
              assert(oldData.size==4 && oldFlag.toSet==Set(0,1,2),"image store escaped complete old-copy prewarming")
              q.bits.cachePolicy.expect(2.U);q.bits.cacheClient.expect(false.B)
            }
            assert(epoch>0 && (address==0x90000 || address==0x90040))
            q.bits.byteMask.expect(0xf0.U)
            if(address==0x90040) assert(dataAcknowledged,"flag store overtook fenced data acknowledgement")
            writeRequests+=1
          }
          Some(Reply(q.bits.transactionId.peek().litValue,address,if(writing)BigInt(0)else line(address),
            cycle+(if(writing)73 else 5+(address%13).toInt),write))
        } else None
        heldRequest=if(q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean)Some(q.bits.peek().litValue)else None
        if(pending.exists(r=>r.address==0x90000 && r.write.nonEmpty && r.due>cycle))delayedDataCycles+=1
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
        pollCounts.clear();beforeImageReadCount=dataReadRequests
        d.io.vmContextIdle.expect(true.B)
        for(id <- 0 until 4) {
          d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
          d.io.command.bits.commandId.poke(id.U)
          val pc=if(epoch==0)0x1100 else epoch*0x2000+0x100+(if(id==3)0x1000 else 0)
          d.io.command.bits.launch.kernelPc.poke(pc.U)
          val images=d.io.command.bits.images.get
          if(epoch>0 && id==3){
            images.enabled.poke(true.B);images.table.version.poke(1.U)
            images.table.base.poke(descriptor.U);images.table.limit.poke((descriptor+256).U)
            images.table.identity.context.poke(7.U);images.table.identity.pass.poke(11.U)
            images.table.identity.generation.poke(13.U)
          }
          for(i <- 0 until 3) {
            d.io.command.bits.launch.gridSize(i).poke(1.U)
            d.io.command.bits.launch.localSize(i).poke(1.U)
          }
          d.io.command.valid.poke(true.B)
          until("submit")(d.io.command.ready.peek().litToBoolean)
          tick(); d.io.command.valid.poke(false.B)
        }
        until("all four CUs active")(d.io.busyComputeUnits.peek().litValue==15)
        allowReplies=true
        until("held public completion")(d.io.commandCompletion.valid.peek().litToBoolean)
        val completion=d.io.commandCompletion.bits.peek().litValue
        for(_ <- 0 until 9) {
          d.io.commandCompletion.valid.expect(true.B)
          assert(d.io.commandCompletion.bits.peek().litValue==completion)
          d.io.vmContextIdle.expect(false.B)
          if(epoch>0)d.io.imageLease.get.expect(true.B)
          tick(); heldCompletionCycles+=1
        }
        d.io.commandCompletion.ready.poke(true.B)
        until("four completions")(completed.size==4)
        d.io.commandCompletion.ready.poke(false.B)
        until("context drain")(d.io.vmContextIdle.peek().litToBoolean)
        assert(pending.isEmpty && held.isEmpty && heldRequest.isEmpty)
        d.io.imageLease.get.expect(false.B)
        if(epoch==0){
          assert(oldData.size==4 && oldFlag.size==4 && writeRequests==0 && dataReadRequests>0)
        }else {
          // L2 deliberately drops a line on store allocation. The subsequent
          // refill must wait for ACK; pre-store old copies were already proved.
          assert(dataReadRequests>beforeImageReadCount)
          assert(oldData.toSet==Set(0,1,2,3) && newData==oldData)
          assert(oldFlag.toSet==Set(0,1,2) && newFlag==oldFlag)
          assert(dataAcknowledged && flagAcknowledged && writeRequests==epoch*2 && writeAcks==epoch*2)
          assert(word(0x90004)==epoch*7 && word(0x90044)==epoch)
        }
        for(address <- Seq(0x90000L,0x90040L))
          assert(word(address)==BigInt(0x11223344) && word(address+8)==BigInt(0x55667788))
      }
      assert(delayedDataCycles>=140 && heldCompletionCycles==27)
      info(s"IMAGE_CACHED_VISIBILITY PASS CUs=4 lanes=16 warps=8 kernels=12 publications=2 peerConsumers=6 ownCUConsumers=2 realImageStores=2 dataLineResidentAtImageStore=true imageLeaseHeld=true delayedDataAck=true realL1L2=true exactGuards=true heldCompletion=true ownerReuse=true cycles=$cycle productCapacityQualified=false clockQualified=false FPSQualified=false")
    }
  }
}

object QualifyGpuSystemImageCachedVisibility {
  def main(args: Array[String]): Unit={
    require(args.length==2 && args(0)=="--reports" && !GraphicsTestResolution.productQualification)
    val suite=new GpuSystemImageCachedVisibilityQualification
    require(suite.testNames.size==1)
    require(org.scalatest.tools.Runner.run(Array("-s",classOf[GpuSystemImageCachedVisibilityQualification].getName,"-oD","-u",args(1))))
    val report=scala.xml.XML.loadFile(new java.io.File(args(1),"TEST-"+classOf[GpuSystemImageCachedVisibilityQualification].getName+".xml"))
    val cases=report \\ "testcase"
    require((report \ "@tests").text=="1" && cases.size==1 && cases.map(n=>(n \ "@name").text).toSet==suite.testNames.toSet &&
      Seq("errors","failures","skipped").forall(a=>report.attribute(a).forall(_.text=="0")) && Seq("failure","error","skipped").forall(n=>(report \\ n).isEmpty))
    println("IMAGE_CACHED_VISIBILITY PASS cases=1 APIQualified=false clockQualified=false FPSQualified=false")
  }
}
