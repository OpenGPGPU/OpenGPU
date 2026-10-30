package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuCoreCounterAtomicSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  for((translated,denied)<-Seq((false,false),(true,false),(true,true))) {
    it should s"route a counter atomic through the core translation owner translated=$translated denied=$denied" in {
      simulate(new GpuCore(GpuConfig(lanes=4,warps=2),vectorCacheSets=4,vectorCacheWays=1,
        atomicCounterAvailable=true)){d=>
        zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
        val satp=if(translated)BigInt("80000090",16)else BigInt(0)
        d.io.counterAtomicSatp.get.poke(satp.U);d.io.vectorSatp.poke(0.U)
        val req=d.io.counterAtomicRequest.get;req.bits.address.poke(0x201c.U);req.bits.warpId.poke(1.U)
        req.bits.operation.poke(AtomicMemoryOp.add);req.bits.operand.poke(1.U);req.valid.poke(true.B)
        req.ready.expect(true.B);d.clock.step();req.valid.poke(false.B);d.io.counterAtomicSatp.get.poke(0.U)
        def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,s"$label stalled")}
        if(translated){
          until("page walk")(d.io.vectorPageTableRequest.valid.peek().litToBoolean)
          d.io.vectorPageTableRequest.bits.address.expect(0x90000.U)
          val held=d.io.vectorPageTableRequest.bits.peek().litValue;d.clock.step(2)
          assert(d.io.vectorPageTableRequest.bits.peek().litValue==held)
          d.io.vectorPageTableRequest.ready.poke(true.B);d.clock.step();d.io.vectorPageTableRequest.ready.poke(false.B)
          d.io.vectorPageTableResponse.bits.pte.poke(((BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc7)).U)
          d.io.vectorPageTableResponse.valid.poke(true.B);until("page response")(d.io.vectorPageTableResponse.ready.peek().litToBoolean)
          d.clock.step();d.io.vectorPageTableResponse.valid.poke(false.B)
        }
        if(!denied){
          until("physical atomic")(d.io.globalAtomicRequest.valid.peek().litToBoolean)
          d.io.globalAtomicRequest.bits.address.expect((if(translated)0x40201c else 0x201c).U)
          d.io.globalAtomicRequest.bits.operand.expect(1.U);d.io.globalAtomicRequest.bits.warpId.expect(1.U)
          val held=d.io.globalAtomicRequest.bits.peek().litValue;d.clock.step(2);assert(d.io.globalAtomicRequest.bits.peek().litValue==held)
          d.io.globalAtomicRequest.ready.poke(true.B);d.clock.step();d.io.globalAtomicRequest.ready.poke(false.B)
          d.io.globalAtomicResponse.bits.warpId.poke(1.U);d.io.globalAtomicResponse.bits.oldValue.poke(41.U)
          d.io.globalAtomicResponse.valid.poke(true.B);until("atomic response")(d.io.globalAtomicResponse.ready.peek().litToBoolean)
          d.clock.step();d.io.globalAtomicResponse.valid.poke(false.B)
        }
        until("counter result")(d.io.counterAtomicResponse.get.valid.peek().litToBoolean)
        d.io.globalAtomicRequest.valid.expect(false.B)
        val out=d.io.counterAtomicResponse.get;out.bits.fault.expect(denied.B);out.bits.pageFault.expect(denied.B)
        if(!denied)out.bits.oldValue.expect(41.U)
        val held=out.bits.peek().litValue;d.clock.step(3);assert(out.bits.peek().litValue==held)
        out.ready.poke(true.B);d.clock.step();out.ready.poke(false.B)
      }
    }
  }
}
