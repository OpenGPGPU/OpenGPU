package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class AtomicCounterVectorCuServiceSpec extends AnyFlatSpec {
  for((translated,denied,walkFault,dataFault)<-Seq((false,false,false,false),(true,false,false,false),
    (true,true,false,false),(true,false,true,false),(false,false,false,true))) {
    it should s"execute contended counter lanes with a captured descriptor translated=$translated denied=$denied walkFault=$walkFault dataFault=$dataFault" in {
      simulate(new AtomicCounterVectorCuService(GpuConfig(lanes=4,warps=2))) {d=>
        d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        d.io.kernelStart.poke(false.B);d.io.kernelCompletion.poke(false.B);d.io.cancel.poke(false.B)
        d.io.flush.valid.poke(false.B);d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
        d.io.in.valid.poke(false.B);d.io.commit.ready.poke(false.B);d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
        d.io.atomicRequest.ready.poke(false.B);d.io.atomicResponse.valid.poke(false.B)
        d.io.atomicResponse.bits.poke(0.U.asTypeOf(d.io.atomicResponse.bits))
        val task=d.io.task;task.enabled.poke(true.B);task.table.version.poke(1.U);task.table.base.poke(0x8000.U);task.table.limit.poke(0x8040.U)
        task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
        val satp=if(translated)BigInt("80000090",16)else BigInt(0)
        d.io.satp.poke(satp.U);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
        task.enabled.poke(false.B);task.table.base.poke(0xdead0000L.U);d.io.satp.poke(0.U)
        d.io.enabled.expect(true.B);d.io.atomicSatp.expect(satp.U)
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->0,7->1,8->1,9->1,10->0x2000,11->0x2020,13->0x2000,14->0x2040))words(i)=v
        val record=words.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
        case class Reply(id:BigInt,data:BigInt,due:Int,fault:Boolean)
        case class AtomicReply(warp:BigInt,old:BigInt,due:Int)
        val replies=mutable.Queue.empty[Reply];val atomicReplies=mutable.Queue.empty[AtomicReply]
        val counters=Array.fill[BigInt](8)(0);var cycle=0;var walks=0;var descriptors=0;var atomics=0
        def tick():Unit={
          d.io.memoryRequest.ready.poke((cycle%3!=0).B)
          val reply=replies.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)
            d.io.memoryResponse.bits.fault.poke(r.fault.B)})
          if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)replies.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val m=d.io.memoryRequest.bits;val id=m.transactionId.peek().litValue;val a=m.address.peek().litValue;m.isWrite.expect(false.B)
            val value=if(id==1){assert(translated && a==0x90000);walks+=1;(BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc3)}
              else{assert(id==0 && a==(if(translated)0x408000 else 0x8000));descriptors+=1;record}
            replies.enqueue(Reply(id,value,cycle+3,if(id==1)walkFault else dataFault))
          }
          d.io.atomicRequest.ready.poke((cycle%4!=0).B)
          val ar=atomicReplies.headOption.filter(_.due<=cycle);d.io.atomicResponse.valid.poke(ar.nonEmpty.B)
          ar.foreach(r=>{d.io.atomicResponse.bits.warpId.poke(r.warp.U);d.io.atomicResponse.bits.oldValue.poke(r.old.U)})
          if(ar.nonEmpty && d.io.atomicResponse.ready.peek().litToBoolean)atomicReplies.dequeue()
          if(d.io.atomicRequest.valid.peek().litToBoolean && d.io.atomicRequest.ready.peek().litToBoolean){
            val a=d.io.atomicRequest.bits;val address=a.address.peek().litValue;assert(address>=0x2000 && address<0x2020 && (address&3)==0)
            a.operation.expect(AtomicMemoryOp.add);d.io.atomicSatp.expect(satp.U)
            val index=((address-0x2000)/4).toInt;val old=counters(index)
            counters(index)=(old+a.operand.peek().litValue)&BigInt("ffffffff",16)
            atomicReplies.enqueue(AtomicReply(a.warpId.peek().litValue,old,cycle+3));atomics+=1
          }
          d.clock.step();cycle+=1
        }
        def issue(fn:Int):Unit={val q=d.io.in.bits;q.poke(0.U.asTypeOf(q))
          q.issued.decode.instruction.poke(((BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(5)<<7)|0x2b).U)
          q.issued.decode.warpId.poke(1.U);q.issued.decode.pc.poke(0x100.U);q.issued.decode.activeMask.poke(15.U)
          q.executionMask.poke(15.U);q.sideEffectMask.poke(15.U)
          for(i<-0 until 4){q.issued.vs1Data(i).poke(3.U);q.issued.oldVdData(i).poke((100+i).U)}
          d.io.in.valid.poke(true.B);assert(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
        }
        def complete(expected:Seq[Int],fault:Boolean=false):Unit={var n=0
          while(!d.io.commit.valid.peek().litToBoolean && n<10000){tick();n+=1};d.io.commit.valid.expect(true.B)
          d.io.fault.valid.expect(fault.B);d.io.commit.bits.writesVd.expect((!fault).B)
          if(fault){d.io.fault.bits.faultMask.expect(1.U);d.io.fault.bits.addresses(0).expect(0x8000.U)
            d.io.fault.bits.pageFault.expect(denied.B);d.io.fault.bits.isStore.expect(true.B)}
          else for(i<-0 until 4)d.io.commit.bits.writeback.data(i).expect(expected(i).U)
          val held=d.io.commit.bits.peek().litValue;val heldFault=d.io.fault.bits.peek().litValue
          for(_<-0 until 3){tick();assert(d.io.commit.bits.peek().litValue==held)
            if(fault){d.io.fault.valid.expect(true.B);assert(d.io.fault.bits.peek().litValue==heldFault)}}
          d.io.commit.ready.poke(true.B);tick();d.io.commit.ready.poke(false.B)
        }
        issue(18)
        val faulted=denied || walkFault || dataFault
        if(faulted)complete(Seq.empty,fault=true)
        else{complete(Seq(0,1,2,3));issue(19);complete(Seq(3,2,1,0));issue(17);complete(Seq(0,0,0,0))}
        assert((if(translated)walks>0 else walks==0) && descriptors==(if(denied || walkFault)0 else if(dataFault)1 else 12) &&
          atomics==(if(faulted)0 else 12) && replies.isEmpty && atomicReplies.isEmpty && counters(3)==0)
        d.io.kernelCompletion.poke(true.B);tick();d.io.kernelCompletion.poke(false.B);d.io.busy.expect(false.B)
      }
    }
  }
}
