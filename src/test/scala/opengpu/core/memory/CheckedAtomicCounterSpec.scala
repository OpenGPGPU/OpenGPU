package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CheckedAtomicCounterSpec extends AnyFlatSpec {
  behavior of "CheckedAtomicCounter"

  it should "check eight counter offsets, preserve atomic results and drain faults and cancellation" in {
    simulate(new CheckedAtomicCounter()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.io.descriptorRequest.ready.poke(false.B); d.io.descriptorResponse.valid.poke(false.B)
      d.io.descriptorResponse.bits.poke(0.U.asTypeOf(d.io.descriptorResponse.bits))
      d.io.atomicRequest.ready.poke(false.B); d.io.atomicResponse.valid.poke(false.B)
      d.io.atomicResponse.bits.poke(0.U.asTypeOf(d.io.atomicResponse.bits))
      val q=d.io.in.bits; q.table.version.poke(1.U); q.table.base.poke(0x1000.U); q.table.limit.poke(0x1040.U)
      q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(13.U)
      q.warpId.poke(1.U)
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,s"$label stalled")}
      def submit(counter:BigInt,operation:Int,tag:Int):Unit={
        q.counter.poke(counter.U);q.operation.poke(operation.U);q.tag.poke(tag.U)
        d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def record(base:BigInt=0x2000,limit:BigInt=0x2020,allocationBase:BigInt=0x2000,
        allocationLimit:BigInt=0x2040,readable:Int=1,writable:Int=1,identity:Int=7):BigInt={
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->identity,4->11,5->13,
          6->0,7->1,8->readable,9->writable,10->base,11->(limit & 0xffffffffL),12->(limit>>32),
          13->allocationBase,14->(allocationLimit & 0xffffffffL),15->(allocationLimit>>32)))words(i)=v
        words.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
      }
      def descriptor(contents:BigInt,fault:Boolean=false):Unit={
        until("descriptor offer")(d.io.descriptorRequest.valid.peek().litToBoolean)
        d.io.descriptorRequest.bits.address.expect(0x1000.U)
        val held=d.io.descriptorRequest.bits.peek().litValue;d.clock.step(2);assert(d.io.descriptorRequest.bits.peek().litValue==held)
        d.io.descriptorRequest.ready.poke(true.B);d.clock.step();d.io.descriptorRequest.ready.poke(false.B)
        d.io.descriptorResponse.bits.readData.poke(contents.U);d.io.descriptorResponse.bits.fault.poke(fault.B)
        d.io.descriptorResponse.valid.poke(true.B);until("descriptor response")(d.io.descriptorResponse.ready.peek().litToBoolean)
        d.clock.step();d.io.descriptorResponse.valid.poke(false.B);d.io.descriptorResponse.bits.fault.poke(false.B)
      }
      def result(status:Int,tag:Int,value:Option[BigInt]=None):Unit={
        until("result")(d.io.out.valid.peek().litToBoolean);d.io.out.bits.status.expect(status.U);d.io.out.bits.tag.expect(tag.U)
        value.foreach(v=>d.io.out.bits.value.expect(v.U));val held=d.io.out.bits.peek().litValue
        d.clock.step(2);assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      def atomic(address:BigInt,operand:BigInt,old:BigInt,cancel:Boolean=false,fault:Boolean=false):Unit={
        until("atomic offer")(d.io.atomicRequest.valid.peek().litToBoolean)
        d.io.atomicRequest.bits.address.expect(address.U);d.io.atomicRequest.bits.operand.expect(operand.U)
        d.io.atomicRequest.bits.operation.expect(AtomicMemoryOp.add);d.io.atomicRequest.bits.warpId.expect(1.U)
        val held=d.io.atomicRequest.bits.peek().litValue
        if(cancel)d.io.cancel.poke(true.B)
        d.clock.step(2);assert(d.io.atomicRequest.bits.peek().litValue==held)
        d.io.atomicRequest.ready.poke(true.B);d.clock.step();d.io.atomicRequest.ready.poke(false.B)
        d.io.atomicResponse.bits.warpId.poke(1.U);d.io.atomicResponse.bits.oldValue.poke(old.U)
        d.io.atomicResponse.bits.fault.poke(fault.B);d.io.atomicResponse.bits.pageFault.poke(fault.B)
        d.io.atomicResponse.valid.poke(true.B);until("atomic response")(d.io.atomicResponse.ready.peek().litToBoolean)
        d.clock.step();d.io.atomicResponse.valid.poke(false.B);d.io.atomicResponse.bits.fault.poke(false.B)
        d.io.atomicResponse.bits.pageFault.poke(false.B);d.io.cancel.poke(false.B)
      }
      for(counter<-0 until 8;op<-0 until 3){val tag=counter*3+op;submit(counter,op,tag);descriptor(record())
        val operand=if(op==1)BigInt(1)else if(op==2)BigInt("ffffffff",16)else BigInt(0)
        atomic(BigInt(0x2000+counter*4),operand,0);result(AtomicCounterStatus.ok,tag,
          Some(if(op==2)BigInt("ffffffff",16)else BigInt(0)))}
      submit(7,1,30);descriptor(record(base=BigInt("ffffffe0",16),limit=BigInt(1)<<32,
        allocationBase=BigInt("ffffffc0",16),allocationLimit=BigInt(1)<<32))
      atomic(BigInt("fffffffc",16),1,3);result(AtomicCounterStatus.ok,30,Some(3))
      submit(8,0,31);result(AtomicCounterStatus.invalid,31);d.io.descriptorRequest.valid.expect(false.B)
      submit(0,3,32);result(AtomicCounterStatus.invalid,32);d.io.descriptorRequest.valid.expect(false.B)
      submit(7,1,33);descriptor(record(limit=0x201c));result(AtomicCounterStatus.bounds,33);d.io.atomicRequest.valid.expect(false.B)
      submit(0,1,34);descriptor(record(writable=0));result(AtomicCounterStatus.invalid,34);d.io.atomicRequest.valid.expect(false.B)
      submit(0,1,35);descriptor(record(identity=8));result(AtomicCounterStatus.identity,35)
      submit(0,1,36);descriptor(record(),fault=true);until("descriptor fault")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.faultAddressValid.expect(true.B);d.io.out.bits.faultAddress.expect(0x1000.U);result(AtomicCounterStatus.memoryFault,36)
      submit(0,1,37);descriptor(record());atomic(0x2000,1,0,fault=true);until("atomic fault")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.faultAddressValid.expect(true.B);d.io.out.bits.faultAddress.expect(0x2000.U);d.io.out.bits.pageFault.expect(true.B)
      result(AtomicCounterStatus.memoryFault,37)
      submit(0,1,38);descriptor(record());atomic(0x2000,1,9,cancel=true);result(AtomicCounterStatus.cancelled,38)
      submit(0,0,39);descriptor(record());atomic(0x2000,0,10);result(AtomicCounterStatus.ok,39,Some(10))
    }
  }
}
