package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorMemoryRetirementSpec extends AnyFlatSpec {
  private val config=GpuConfig(lanes=4,warps=2)
  private def zero(data: Data): Unit=data match {
    case b: Bool=>b.poke(false.B)
    case u: UInt=>u.poke(0.U)
    case s: SInt=>s.poke(0.S)
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.getElements.foreach(zero)
    case other=>throw new IllegalArgumentException(s"unsupported input type: $other")
  }
  private def initialize(d: VectorMemoryUnit): Unit={
    d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B);zero(d.io.memoryResponse.bits)
    d.io.out.ready.poke(false.B);d.io.invocations.foreach(zero)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def offer(d: VectorMemoryUnit,warp: Int,base: Int,pc: Int,vd: Int,
      mask: Int=15,element: Int=2,store: Boolean=false,old: Int=0x900): Unit={
    val q=d.io.in.bits;zero(q)
    q.warpId.poke(warp.U);q.pc.poke(pc.U);q.vd.poke(vd.U)
    q.warpActiveMask.poke(15.U);q.activeMask.poke(mask.U)
    q.baseAddress.poke(base.U);q.elementSize.poke(element.U);q.isStore.poke(store.B)
    for(lane<-0 until 4){q.storeData(lane).poke((0x40+lane).U);q.oldVd(lane).poke((old+lane).U)}
    d.io.in.valid.poke(true.B)
  }
  private def reply(d: VectorMemoryUnit,base: Int,fault: Int=0,page: Boolean=false): Unit={
    for(lane<-0 until 4)d.io.memoryResponse.bits.readData(lane).poke((base+lane*0x101).U)
    d.io.memoryResponse.bits.faultMask.poke(fault.U);d.io.memoryResponse.bits.pageFault.poke(page.B)
    d.io.memoryResponse.valid.poke(true.B)
  }

  for((faultMask,pageFault)<-Seq((2,false),(0,true),(2,true))) {
  it should s"capture replies and admit only healthy turnover with faultMask=$faultMask pageFault=$pageFault" in {
    simulate(new VectorMemoryUnit(config)){d=>
      initialize(d)
      offer(d,0,0x1000,0x100,5,mask=5)
      d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(true.B);d.clock.step()
      reply(d,0x100,fault=8);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
      d.io.memoryResponse.valid.poke(false.B)
      d.io.out.valid.expect(true.B);d.io.out.bits.faultMask.expect(0.U)
      d.io.out.bits.warpId.expect(0.U);d.io.out.bits.pc.expect(0x100.U)
      for(lane<-0 until 4)d.io.out.bits.data(lane).expect((if((lane&1)==0)0x100+lane*0x101 else 0x900+lane).U)
      val held=d.io.out.bits.peek().litValue
      offer(d,1,0x2000,0x200,7,mask=10,element=1,old=0xa00)
      for(_<-0 until 3){d.io.in.ready.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
        assert(d.io.out.bits.peek().litValue==held);d.clock.step()}
      d.io.out.ready.poke(true.B);d.io.in.ready.expect(true.B)
      d.io.memoryRequest.ready.poke(false.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.io.out.valid.expect(false.B);d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.warpId.expect(1.U);d.io.memoryRequest.bits.laneMask.expect(10.U)
      for(lane<-0 until 4)d.io.memoryRequest.bits.addresses(lane).expect((0x2000+lane*2).U)
      val request=d.io.memoryRequest.bits.peek().litValue
      for(_<-0 until 3){d.io.in.ready.expect(false.B);assert(d.io.memoryRequest.bits.peek().litValue==request);d.clock.step()}
      d.io.memoryRequest.ready.poke(true.B);d.clock.step()
      reply(d,0xab0000,fault=faultMask,page=pageFault);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
      d.io.memoryResponse.valid.poke(false.B);zero(d.io.memoryResponse.bits)
      d.io.out.valid.expect(true.B);d.io.out.bits.warpId.expect(1.U);d.io.out.bits.pc.expect(0x200.U)
      d.io.out.bits.vd.expect(7.U);d.io.out.bits.faultMask.expect(faultMask.U);d.io.out.bits.pageFault.expect(pageFault.B)
      for(lane<-0 until 4)d.io.out.bits.data(lane).expect((if((lane&1)==1)(0xab0000+lane*0x101)&0xffff else 0xa00+lane).U)
      val failed=d.io.out.bits.peek().litValue
      offer(d,0,0x3000,0x300,9)
      for(_<-0 until 3){d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==failed);d.clock.step()}
      d.io.out.ready.poke(true.B);d.io.in.ready.expect(false.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.out.valid.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
      d.io.in.ready.expect(true.B)
    }
  }
  }

  it should "retain the helper policy across a healthy turnover and retire a suppressed store without a request" in {
    simulate(new VectorMemoryUnit(config,helperInvocations=true)){d=>
      initialize(d)
      offer(d,0,0x1000,0x100,5);d.clock.step();d.io.in.valid.poke(false.B);d.clock.step()
      reply(d,0x20);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      d.io.out.valid.expect(true.B);val held=d.io.out.bits.peek().litValue
      val policy=d.io.invocations.get(1)
      policy.helpers.poke(15.U);policy.privateBase.poke(0x8000.U);policy.privateBytes.poke(16.U)
      offer(d,1,0x4000,0x200,7,store=true)
      for(_<-0 until 3){d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==held);d.clock.step()}
      d.io.out.ready.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);zero(d.io.invocations.get)
      d.io.memoryRequest.valid.expect(false.B);d.io.out.valid.expect(false.B);d.clock.step()
      d.io.out.valid.expect(true.B);d.io.out.bits.warpId.expect(1.U);d.io.out.bits.pc.expect(0x200.U)
      d.io.out.bits.isStore.expect(true.B);d.io.out.bits.writesVd.expect(false.B)
      d.io.out.bits.faultMask.expect(0.U);d.io.out.bits.pageFault.expect(false.B)
      val suppressed=d.io.out.bits.peek().litValue
      for(_<-0 until 4){d.io.memoryRequest.valid.expect(false.B);assert(d.io.out.bits.peek().litValue==suppressed);d.clock.step()}
      offer(d,0,0x5000,0x300,9,mask=10,element=0,old=0xb00)
      d.io.out.ready.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.io.memoryRequest.valid.expect(true.B);d.clock.step()
      reply(d,0xcc0000);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      d.io.out.valid.expect(true.B);d.io.out.bits.isStore.expect(false.B);d.io.out.bits.vd.expect(9.U)
      for(lane<-0 until 4)d.io.out.bits.data(lane).expect((if((lane&1)==1)lane else 0xb00+lane).U)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.valid.expect(false.B)
    }
  }

  it should "serve eight ordered word loads with two-clock replies within 33 clocks" in {
    simulate(new VectorMemoryUnit(config)){d=>
      initialize(d);d.io.out.ready.poke(true.B)
      var admitted=0;var issued=0;var completed=0;var cycle=0
      var pending=Option.empty[(Int,Int)]
      while(completed<8 && cycle<64){
        if(admitted<8)offer(d,admitted&1,0x1000+admitted*0x80,0x400+admitted*4,5+admitted%3)
        else d.io.in.valid.poke(false.B)
        val due=pending.filter(_._1<=cycle)
        d.io.memoryResponse.valid.poke(due.nonEmpty.B)
        due.foreach { case(_,id)=>
          for(lane<-0 until 4)d.io.memoryResponse.bits.readData(lane).poke((0x8000+id*16+lane).U)
        }
        val take=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean
        val receive=d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean
        if(d.io.memoryRequest.valid.peek().litToBoolean){
          assert(pending.isEmpty,"more than one external memory request became outstanding")
          d.io.memoryRequest.bits.warpId.expect((issued&1).U)
          for(lane<-0 until 4)d.io.memoryRequest.bits.addresses(lane).expect((0x1000+issued*0x80+lane*4).U)
          pending=Some((cycle+2,issued));issued+=1
        }
        if(receive)pending=None
        if(d.io.out.valid.peek().litToBoolean){
          d.io.out.bits.warpId.expect((completed&1).U);d.io.out.bits.pc.expect((0x400+completed*4).U)
          d.io.out.bits.vd.expect((5+completed%3).U);d.io.out.bits.faultMask.expect(0.U)
          for(lane<-0 until 4)d.io.out.bits.data(lane).expect((0x8000+completed*16+lane).U)
          completed+=1
        }
        d.clock.step();if(take)admitted+=1;cycle+=1
      }
      assert(admitted==8 && issued==8 && completed==8 && pending.isEmpty)
      assert(cycle<=33,s"eight loads took $cycle clocks")
      info(s"VECTOR_MEMORY_RETIREMENT requests=8 memoryReplyLatency=2 clocks=$cycle singleOutstanding=true exactOrderedResults=true")
    }
  }

  it should "retain all sixteen lanes and the eighth warp through helper stores and byte-load turnover" in {
    val native=GpuConfig(lanes=16,warps=8)
    simulate(new VectorMemoryUnit(native,helperInvocations=true)){d=>
      d.io.in.valid.poke(false.B);zero(d.io.in.bits);zero(d.io.invocations.get)
      d.io.memoryRequest.ready.poke(true.B);d.io.memoryResponse.valid.poke(false.B)
      zero(d.io.memoryResponse.bits);d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val policy=d.io.invocations.get(7)
      policy.helpers.poke(0xffff.U);policy.privateBase.poke(0x8000.U);policy.privateBytes.poke(32.U)
      val q=d.io.in.bits
      q.warpId.poke(7.U);q.pc.poke(0x4000.U);q.warpActiveMask.poke(0xffff.U)
      q.activeMask.poke(0xffff.U);q.vd.poke(31.U);q.baseAddress.poke(0x8000.U)
      q.elementSize.poke(2.U);q.isStore.poke(true.B)
      for(lane<-0 until 16)q.storeData(lane).poke((0x400+lane).U)
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()
      d.io.in.valid.poke(false.B);zero(d.io.invocations.get)
      d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.warpId.expect(7.U)
      d.io.memoryRequest.bits.laneMask.expect(0xff.U)
      for(lane<-0 until 16){
        d.io.memoryRequest.bits.addresses(lane).expect((0x8000+lane*4).U)
        d.io.memoryRequest.bits.writeData(lane).expect((0x400+lane).U)
      }
      d.clock.step();d.io.memoryResponse.bits.faultMask.poke(0xff00.U)
      d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
      d.io.memoryResponse.valid.poke(false.B);zero(d.io.memoryResponse.bits)
      d.io.out.valid.expect(true.B);d.io.out.bits.faultMask.expect(0.U)
      d.io.out.bits.warpId.expect(7.U);d.io.out.bits.writesVd.expect(false.B)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 3){assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B);d.clock.step()}
      zero(q);q.warpId.poke(7.U);q.pc.poke(0x5000.U);q.vd.poke(30.U)
      q.warpActiveMask.poke(0xffff.U);q.activeMask.poke(0xa55a.U);q.baseAddress.poke(0x9000.U)
      policy.helpers.poke(0xffff.U)
      for(lane<-0 until 16)q.oldVd(lane).poke((0x9000+lane).U)
      d.io.in.valid.poke(true.B);d.io.out.ready.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);zero(d.io.invocations.get)
      d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.laneMask.expect(0xa55a.U)
      for(lane<-0 until 16)d.io.memoryRequest.bits.addresses(lane).expect((0x9000+lane).U)
      d.clock.step()
      for(lane<-0 until 16)d.io.memoryResponse.bits.readData(lane).poke((0xcc0000+lane).U)
      d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.ready.expect(true.B);d.clock.step()
      d.io.memoryResponse.valid.poke(false.B);zero(d.io.memoryResponse.bits)
      d.io.out.valid.expect(true.B);d.io.out.bits.warpId.expect(7.U);d.io.out.bits.pc.expect(0x5000.U)
      d.io.out.bits.vd.expect(30.U);d.io.out.bits.writesVd.expect(true.B);d.io.out.bits.faultMask.expect(0.U)
      for(lane<-0 until 16)d.io.out.bits.data(lane).expect((if((0xa55a&(1<<lane))!=0)lane else 0x9000+lane).U)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.valid.expect(false.B)
      info("VECTOR_MEMORY_NATIVE lanes=16 warps=8 capturedHelperMask=true suppressedFaults=true byteLoads=true inactiveDestinations=true")
    }
  }
}
