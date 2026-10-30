package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class AtomicCounterCuServiceSpec extends AnyFlatSpec {
  for((translated,denied,walkFault,dataFault)<-Seq((false,false,false,false),(true,false,false,false),
    (true,true,false,false),(true,false,true,false),(false,false,false,true))) {
    it should s"capture counter ownership and descriptor translation translated=$translated denied=$denied walkFault=$walkFault dataFault=$dataFault" in {
      simulate(new AtomicCounterCuService(GpuConfig(lanes=4,warps=2))) { d=>
        d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        d.io.kernelStart.poke(false.B);d.io.kernelCompletion.poke(false.B);d.io.cancel.poke(false.B)
        d.io.flush.valid.poke(false.B);d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
        d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
        d.io.memoryRequest.ready.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
        d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
        d.io.atomicRequest.ready.poke(false.B);d.io.atomicResponse.valid.poke(false.B)
        d.io.atomicResponse.bits.poke(0.U.asTypeOf(d.io.atomicResponse.bits))
        val t=d.io.task;t.enabled.poke(true.B);t.table.version.poke(1.U);t.table.base.poke(0x8000.U);t.table.limit.poke(0x8040.U)
        t.table.identity.context.poke(7.U);t.table.identity.pass.poke(11.U);t.table.identity.generation.poke(13.U)
        val satp=if(translated)BigInt("80000090",16)else BigInt(0)
        d.io.satp.poke(satp.U);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
        t.enabled.poke(false.B);t.table.base.poke(0xdead0000L.U);d.io.satp.poke(0.U)
        d.io.enabled.expect(true.B);d.io.atomicSatp.expect(satp.U)
        val q=d.io.in.bits;q.counter.poke(7.U);q.operation.poke(AtomicCounterOperation.increment.U);q.warpId.poke(1.U);q.tag.poke(44.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->0,7->1,8->1,9->1,10->0x2000,11->0x2020,13->0x2000,14->0x2040))words(i)=v
        val record=words.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
        case class Reply(id:BigInt,data:BigInt,due:Int,fault:Boolean)
        val replies=mutable.Queue.empty[Reply];var cycle=0;var walks=0;var descriptors=0;var atomics=0;var atomicDue= -1
        while(!d.io.out.valid.peek().litToBoolean && cycle<10000){
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val reply=replies.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)
            d.io.memoryResponse.bits.fault.poke(r.fault.B)})
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)replies.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val m=d.io.memoryRequest.bits;val id=m.transactionId.peek().litValue;val a=m.address.peek().litValue
            m.isWrite.expect(false.B)
            val value=if(id==1){assert(translated && a==0x90000);walks+=1;(BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc3)}
              else {assert(id==0 && a==(if(translated)0x408000 else 0x8000));descriptors+=1;record}
            replies.enqueue(Reply(id,value,cycle+3,if(id==1)walkFault else dataFault))
          }
          d.io.atomicRequest.ready.poke((cycle%4!=0).B)
          if(d.io.atomicRequest.valid.peek().litToBoolean && d.io.atomicRequest.ready.peek().litToBoolean){
            d.io.atomicRequest.bits.address.expect(0x201c.U);d.io.atomicRequest.bits.operand.expect(1.U)
            d.io.atomicRequest.bits.operation.expect(AtomicMemoryOp.add);d.io.atomicSatp.expect(satp.U)
            atomics+=1;atomicDue=cycle+4
          }
          val respond=atomicDue>=0 && cycle>=atomicDue
          d.io.atomicResponse.valid.poke(respond.B);d.io.atomicResponse.bits.warpId.poke(1.U);d.io.atomicResponse.bits.oldValue.poke(41.U)
          if(respond && d.io.atomicResponse.ready.peek().litToBoolean)atomicDue= -1
          d.clock.step();cycle+=1
        }
        d.io.out.valid.expect(true.B);d.io.out.bits.tag.expect(44.U)
        val faulted=denied || walkFault || dataFault
        d.io.out.bits.status.expect((if(faulted)AtomicCounterStatus.memoryFault else AtomicCounterStatus.ok).U)
        if(faulted){d.io.out.bits.faultAddressValid.expect(true.B);d.io.out.bits.faultAddress.expect(0x8000.U)
          d.io.out.bits.pageFault.expect(denied.B)}
        else d.io.out.bits.value.expect(41.U)
        assert(descriptors==(if(denied || walkFault)0 else 1) && atomics==(if(faulted)0 else 1) &&
          (if(translated)walks>0 else walks==0) && replies.isEmpty && atomicDue<0)
        val held=d.io.out.bits.peek().litValue;d.clock.step(3);assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        d.io.kernelCompletion.poke(true.B);d.clock.step();d.io.kernelCompletion.poke(false.B);d.io.busy.expect(false.B)
      }
    }
  }
}
