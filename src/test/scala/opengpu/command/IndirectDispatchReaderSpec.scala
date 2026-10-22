package opengpu.command

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IndirectDispatchReaderSpec extends AnyFlatSpec {
  private def initialize(d: IndirectDispatchReader): Unit = {
    d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def accept(d: IndirectDispatchReader, base: Long, bytes: Long, offset: Long): Unit = {
    d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B)
    d.io.in.bits.allocationBase.poke(base.U); d.io.in.bits.allocationBytes.poke(bytes.U)
    d.io.in.bits.offset.poke(offset.U); d.clock.step(); d.io.in.valid.poke(false.B)
    d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
  }
  private def until(d: IndirectDispatchReader)(condition: => Boolean): Unit = {
    var n=0; while(!condition && n<100) { d.clock.step(); n+=1 }; assert(condition)
  }

  it should "check full views and retain exact uint32 arguments across line boundaries and held replies" in {
    simulate(new IndirectDispatchReader()) { d =>
      initialize(d)
      var checked=0; var reads=0
      val cases=(0 until 64 by 4).map(offset=>(0x1000L,4096L,offset.toLong)) :+
        (0xfffffff4L,12L,0L)
      for(((base,bytes,offset),tag)<-cases.zipWithIndex) {
        val counts=Vector(BigInt(tag+1),if(tag%3==0)BigInt(0)else BigInt("ffffffff",16),BigInt(0x12340000L+tag))
        val start=base+offset
        accept(d,base,bytes,offset)
        var localReads=0
        while(!d.io.out.valid.peek().litToBoolean) {
          until(d)(d.io.memoryRequest.valid.peek().litToBoolean || d.io.out.valid.peek().litToBoolean)
          if(d.io.memoryRequest.valid.peek().litToBoolean) {
            val address=d.io.memoryRequest.bits.address.peek().litValue.toLong
            assert(address==(start& ~63L)+64L*localReads)
            val held=d.io.memoryRequest.bits.peek().litValue
            for(_<-0 until 5) { d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.clock.step() }
            d.io.memoryRequest.bits.isWrite.expect(false.B); d.io.memoryRequest.bits.transactionId.expect(0.U)
            d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
            for(_<-0 until 7) { d.io.busy.expect(true.B);d.io.out.valid.expect(false.B);d.clock.step() }
            val line=(0 until 16).map { word =>
              val i=((address+word*4-start)/4).toInt
              val value=if(address+word*4>=start && i>=0 && i<3)counts(i)else BigInt("deadbeef",16)
              value<<(word*32)
            }.sum
            d.io.memoryResponse.bits.readData.poke(line.U)
            d.io.memoryResponse.bits.fault.poke(false.B);d.io.memoryResponse.valid.poke(true.B)
            d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
            localReads+=1;reads+=1
          }
        }
        assert(localReads==(if((start&63)>52)2 else 1))
        d.io.out.bits.success.expect(true.B);d.io.out.bits.status.expect(0.U)
        d.io.out.bits.empty.expect(counts.contains(BigInt(0)).B)
        for(k<-0 until 3)d.io.out.bits.groups(k).expect(counts(k).U)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 6){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B);d.clock.step()}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        d.io.busy.expect(false.B);checked+=1
      }
      val invalid=Vector((0x1000L,12L,1L),(0x1001L,16L,0L),(0x1000L,11L,0L),
        (0x1000L,12L,4L),(0xfffffff8L,32L,0L),(0L,0xffffffffL,0xfffffff8L))
      for((base,bytes,offset)<-invalid) {
        accept(d,base,bytes,offset)
        until(d)(d.io.out.valid.peek().litToBoolean)
        d.io.memoryRequest.valid.expect(false.B);d.io.out.bits.success.expect(false.B)
        d.io.out.bits.status.expect(GpuCommandResultStatus.indirectBounds)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      for(faultLine<-0 until 2) {
        accept(d,0x1000,128,60)
        for(line<-0 to faultLine) {
          until(d)(d.io.memoryRequest.valid.peek().litToBoolean)
          d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
          d.io.memoryResponse.bits.readData.poke(0.U);d.io.memoryResponse.bits.fault.poke((line==faultLine).B)
          d.io.memoryResponse.valid.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
        }
        d.io.out.valid.expect(true.B);d.io.out.bits.success.expect(false.B)
        d.io.out.bits.status.expect(GpuCommandResultStatus.indirectReadFailed)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info(s"INDIRECT_ARGUMENT_READER validViews=$checked physicalReads=$reads invalidViews=${invalid.size} faultLines=2 exactUint32=true legal4GiBEnd=true noWrappedRead=true stableHeldOwner=true")
    }
  }

  it should "drain offered and accepted reads through cancel without launching a second line or reusing the owner early" in {
    simulate(new IndirectDispatchReader()) { d =>
      initialize(d)
      for(offered<-Seq(false,true)) {
        accept(d,0x1000,128,60)
        until(d)(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.memoryRequest.bits.peek().litValue
        if(!offered){d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)}
        d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
        if(offered){
          for(_<-0 until 7){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.in.ready.expect(false.B);d.clock.step()}
          d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
        }
        for(_<-0 until 19){d.io.busy.expect(true.B);d.io.in.ready.expect(false.B);d.io.out.valid.expect(false.B);d.clock.step()}
        d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.fault.poke(true.B)
        d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
        d.io.busy.expect(false.B);d.io.memoryRequest.valid.expect(false.B);d.io.out.valid.expect(false.B)
        for(_<-0 until 5){d.io.memoryRequest.valid.expect(false.B);d.clock.step()}
      }
      accept(d,0x2000,12,0)
      until(d)(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.fault.poke(false.B)
      d.io.memoryResponse.bits.readData.poke((BigInt(3)|(BigInt(4)<<32)|(BigInt(5)<<64)).U)
      d.io.memoryResponse.valid.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      d.io.out.valid.expect(true.B);d.io.out.bits.success.expect(true.B)
      for(k<-0 until 3)d.io.out.bits.groups(k).expect((k+3).U)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.busy.expect(false.B)
      info("INDIRECT_ARGUMENT_CANCEL offeredHeld=true acceptedReplyHeld=true noSecondRead=true noEarlyReuse=true realFaultReplyDrained=true healthyReuse=true")
    }
  }
}
