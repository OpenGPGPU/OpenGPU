package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CheckedStorageAtomicSpec extends AnyFlatSpec {
  behavior of "CheckedStorageAtomic"
  it should "check bindings and ranges before forwarding atomic requests and drain cancelled work" in {
    simulate(new CheckedStorageAtomic()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.io.descriptorRequest.ready.poke(false.B); d.io.descriptorResponse.valid.poke(false.B)
      d.io.descriptorResponse.bits.poke(0.U.asTypeOf(d.io.descriptorResponse.bits))
      d.io.atomicRequest.ready.poke(false.B); d.io.atomicResponse.valid.poke(false.B)
      d.io.atomicResponse.bits.poke(0.U.asTypeOf(d.io.atomicResponse.bits))
      val q=d.io.in.bits
      q.table.version.poke(1.U); q.table.base.poke(0x1000.U); q.table.limit.poke(0x1100.U)
      q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(13.U)
      q.warpId.poke(1.U); q.operand.poke("h12345678".U); q.compareValue.poke("h87654321".U)
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,s"$label stalled")}
      def submit(binding:Int,offset:BigInt,op:Int,tag:Int):Unit={
        q.bindingIndex.poke(binding.U); q.offset.poke(offset.U); q.operation.poke(op.U); q.tag.poke(tag.U)
        d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def record(binding:Int,base:BigInt=0x2000,limit:BigInt=0x2040,allocBase:BigInt=0x2000,
                 allocLimit:BigInt=0x2040,read:Int=1,write:Int=1):BigInt={
        val words=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->binding,7->1,8->read,9->write,10->base,11->(limit & 0xffffffffL),12->(limit>>32),
          13->allocBase,14->(allocLimit & 0xffffffffL),15->(allocLimit>>32))) words(i)=v
        words.zipWithIndex.map{case(v,i)=>v<<(32*i)}.sum
      }
      def descriptor(binding:Int,contents:BigInt,fault:Boolean=false):Unit={
        until("descriptor offer")(d.io.descriptorRequest.valid.peek().litToBoolean)
        d.io.descriptorRequest.bits.address.expect((0x1000+binding*64).U)
        val held=d.io.descriptorRequest.bits.peek().litValue; d.clock.step(2)
        assert(d.io.descriptorRequest.bits.peek().litValue==held)
        d.io.descriptorRequest.ready.poke(true.B); d.clock.step(); d.io.descriptorRequest.ready.poke(false.B)
        d.io.descriptorResponse.bits.readData.poke(contents.U); d.io.descriptorResponse.bits.fault.poke(fault.B)
        d.io.descriptorResponse.valid.poke(true.B); until("descriptor response")(d.io.descriptorResponse.ready.peek().litToBoolean)
        d.clock.step(); d.io.descriptorResponse.valid.poke(false.B); d.io.descriptorResponse.bits.fault.poke(false.B)
      }
      def result(status:Int,tag:Int,value:Option[BigInt]=None):Unit={
        until("result")(d.io.out.valid.peek().litToBoolean); d.io.out.bits.status.expect(status.U); d.io.out.bits.tag.expect(tag.U)
        value.foreach(v=>d.io.out.bits.value.expect(v.U)); val held=d.io.out.bits.peek().litValue
        d.clock.step(2); assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      def atomic(address:BigInt,op:Int,old:BigInt,cancel:Boolean=false,fault:Boolean=false):Unit={
        until("atomic offer")(d.io.atomicRequest.valid.peek().litToBoolean)
        d.io.atomicRequest.bits.address.expect(address.U); d.io.atomicRequest.bits.operation.expect(op.U)
        d.io.atomicRequest.bits.operand.expect("h12345678".U); d.io.atomicRequest.bits.compareValue.expect("h87654321".U)
        d.io.atomicRequest.bits.warpId.expect(1.U)
        val held=d.io.atomicRequest.bits.peek().litValue
        if(cancel) d.io.cancel.poke(true.B)
        d.clock.step(2); assert(d.io.atomicRequest.bits.peek().litValue==held)
        d.io.atomicRequest.ready.poke(true.B); d.clock.step(); d.io.atomicRequest.ready.poke(false.B)
        d.io.atomicResponse.bits.warpId.poke(1.U); d.io.atomicResponse.bits.oldValue.poke(old.U)
        d.io.atomicResponse.bits.fault.poke(fault.B); d.io.atomicResponse.bits.pageFault.poke(fault.B)
        d.io.atomicResponse.valid.poke(true.B); until("atomic response")(d.io.atomicResponse.ready.peek().litToBoolean)
        d.clock.step(); d.io.atomicResponse.valid.poke(false.B); d.io.atomicResponse.bits.fault.poke(false.B)
        d.io.atomicResponse.bits.pageFault.poke(false.B); d.io.cancel.poke(false.B)
      }
      for(binding<-0 until 4;op<-0 until 10){
        val tag=binding*10+op; submit(binding,60,op,tag); descriptor(binding,record(binding))
        atomic(0x203c,op,tag); result(StorageAtomicStatus.ok,tag,Some(tag))
      }
      submit(4,0,0,40); result(StorageAtomicStatus.invalid,40); d.io.descriptorRequest.valid.expect(false.B)
      submit(0,0,10,41); result(StorageAtomicStatus.invalid,41); d.io.descriptorRequest.valid.expect(false.B)
      submit(0,1,1,42); descriptor(0,record(0)); result(StorageAtomicStatus.bounds,42); d.io.atomicRequest.valid.expect(false.B)
      submit(0,64,1,43); descriptor(0,record(0)); result(StorageAtomicStatus.bounds,43)
      submit(0,0,1,44); descriptor(0,record(0,write=0)); result(StorageAtomicStatus.invalid,44)
      submit(0,0,1,45); descriptor(0,record(0,read=0)); result(StorageAtomicStatus.invalid,45)
      submit(0,0,1,46); descriptor(0,record(0),fault=true); result(StorageAtomicStatus.memoryFault,46)
      submit(0,0,1,47); descriptor(0,record(0)); atomic(0x2000,1,0,fault=true)
      until("atomic fault")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.faultAddress.expect(0x2000.U); d.io.out.bits.faultAddressValid.expect(true.B); d.io.out.bits.pageFault.expect(true.B)
      result(StorageAtomicStatus.memoryFault,47)
      submit(0,0,1,48); descriptor(0,record(0)); atomic(0x2000,1,9,cancel=true); result(StorageAtomicStatus.cancelled,48)
      submit(0,0,1,49); descriptor(0,record(0)); atomic(0x2000,1,10); result(StorageAtomicStatus.ok,49,Some(10))
      submit(0,252,9,50); descriptor(0,record(0,base=BigInt("ffffff00",16),limit=BigInt(1)<<32,
        allocBase=BigInt("ffffff00",16),allocLimit=BigInt(1)<<32))
      atomic(BigInt("fffffffc",16),9,11); result(StorageAtomicStatus.ok,50,Some(11))
      submit(0,256,1,51); descriptor(0,record(0,base=BigInt("ffffff00",16),limit=BigInt(1)<<32,
        allocBase=BigInt("ffffff00",16),allocLimit=BigInt(1)<<32)); result(StorageAtomicStatus.bounds,51)
    }
  }
}
