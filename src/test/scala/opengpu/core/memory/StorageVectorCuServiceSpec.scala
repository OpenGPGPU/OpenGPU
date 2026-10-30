package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class StorageVectorCuServiceSpec extends AnyFlatSpec {
  behavior of "StorageVectorCuService"
  it should "execute vector stores and loads through task-owned GPU descriptors" in {
    simulate(new StorageVectorCuService(GpuConfig(lanes = 2, warps = 1))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.kernelStart.poke(false.B); d.io.kernelCompletion.poke(false.B)
      d.io.warpStart.valid.poke(false.B); d.io.warpStart.bits.poke(0.U)
      d.io.flush.valid.poke(false.B); d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
      d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B); d.io.commit.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.task.enabled.poke(true.B); d.io.task.table.version.poke(1.U)
      d.io.task.table.base.poke(0x4000.U); d.io.task.table.limit.poke(0x4100.U)
      d.io.task.table.identity.context.poke(7.U); d.io.task.table.identity.pass.poke(11.U)
      d.io.task.table.identity.generation.poke(13.U); d.io.satp.poke(0.U)
      d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B)
      def record(i: Int): BigInt = {
        val words=Array.fill[BigInt](16)(0); val base=0x10000+i*0x1000
        for ((n,v)<-Seq[(Int, BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->i,7->1,8->1,9->1,10->base,11->(base+256),13->base,14->(base+256))) words(n)=v
        words.zipWithIndex.map{case(v,n)=>v<<(32*n)}.sum
      }
      val bytes=mutable.Map.empty[Int,Int]; var cycle=0; var descriptors=0; var stores=0; var loads=0
      case class Reply(id: BigInt, data: BigInt, write: Option[(Int,BigInt,BigInt)])
      var pending: Option[Reply]=None
      def tick(): Unit = {
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)}
        val taken=pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        if(taken)pending.get.write.foreach{case(a,data,mask)=>for(i<-0 until 64 if mask.testBit(i))bytes(a+i)=((data>>(8*i))&255).toInt}
        val req=if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          assert(pending.isEmpty || taken)
          val q=d.io.memoryRequest.bits;val id=q.transactionId.peek().litValue;val a=q.address.peek().litValue.toInt
          if(id==0){assert(a==0x4000 || a==0x4040);descriptors+=1;Some(Reply(id,record((a-0x4000)/64),None))}
          else {assert(id==1 && (a==0x10000 || a==0x11000))
            if(q.isWrite.peek().litToBoolean){stores+=1;Some(Reply(id,0,Some((a,q.writeData.peek().litValue,q.byteMask.peek().litValue))))}
            else {loads+=1;Some(Reply(id,(0 until 64).map(i=>BigInt(bytes.getOrElse(a+i,0))<<(8*i)).sum,None))}}
        }else None
        if(taken)pending=None
        d.clock.step();cycle+=1;req.foreach(x=>pending=Some(x))
      }
      def until(label: String)(f: => Boolean): Unit={var n=0;while(!f && n<1000){tick();n+=1};assert(f,s"$label timed out")}
      def issue(fn: Int): Unit={
        val q=d.io.in.bits;q.poke(0.U.asTypeOf(q))
        q.issued.decode.instruction.poke(((BigInt(fn)<<26)|(BigInt(1)<<25)|0x2b|(if(fn==15)5<<7 else 0)).U)
        q.issued.decode.warpId.poke(0.U);q.issued.decode.pc.poke(0x100.U);q.issued.decode.activeMask.poke(3.U)
        q.executionMask.poke(3.U);q.sideEffectMask.poke(3.U)
        for(i<-0 until 2){q.issued.vs1Data(i).poke((if(fn==14)11+i else i).U);q.issued.vs2Data(i).poke((4*i).U)}
        d.io.in.valid.poke(true.B);until("instruction admission")(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      }
      def commit(load: Boolean): Unit={
        until("commit")(d.io.commit.valid.peek().litToBoolean);d.io.fault.valid.expect(false.B)
        d.io.commit.bits.writesVd.expect(load.B);d.io.commit.bits.writesScalar.expect((!load).B)
        if(load){d.io.commit.bits.writeback.data(0).expect(11.U);d.io.commit.bits.writeback.data(1).expect(12.U)}
        val held=d.io.commit.bits.peek().litValue;tick();assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B);tick();d.io.commit.ready.poke(false.B)
      }
      issue(14);commit(load=false);issue(16);commit(load=false);issue(15);commit(load=true)
      assert(descriptors==4 && stores==2 && loads==2 && pending.isEmpty)
      d.io.busy.expect(false.B);d.io.kernelCompletion.poke(true.B);d.clock.step()
    }
  }
}
