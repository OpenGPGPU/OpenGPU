package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderPayloadAtomicGuardSpec extends AnyFlatSpec {
  private case class Case(name: String,address: Int=0x9000,enabled: Boolean=true,
    base: Int=0x8000,reject: Boolean=false,cancel: Int= -1,memoryFault: Boolean=false,lateCancel: Boolean=false)
  private val cases=Seq(Case("local-start",address=0x8000,reject=true),
    Case("local-last",address=0x811c,reject=true),Case("uniform-boundary",address=0x8120),
    Case("before-prefix",address=0x7ffc),Case("straddling-prefix",address=0x7fff,reject=true),
    Case("disabled",address=0x8000,enabled=false),Case("malformed-binding",base=0x8004,reject=true),
    Case("cancel-at-accept",cancel=0,reject=true),Case("cancel-classify",cancel=1,reject=true),
    Case("cancel-offered",cancel=3),Case("memory-fault",memoryFault=true),Case("held-reply",lateCancel=true))
  for(c <- cases)it should s"handle ${c.name} without global aliasing or lost atomic ownership" in {
    val config=GpuConfig(lanes=4,warps=2);val p=ShaderPayloadConfig(owners=2,ports=2)
    simulate(new ShaderPayloadAtomicGuard(config,p)) { d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      d.io.task.valid.poke(c.enabled.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.task.bits.base.poke(c.base.U);d.io.task.bits.bytes.poke(288.U)
      d.io.cancel.poke(false.B);d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.in.bits.warpId.poke(1.U);d.io.in.bits.address.poke(c.address.U)
      d.io.in.bits.operand.poke(7.U);d.io.in.bits.operation.poke(AtomicMemoryOp.add)
      d.io.cancel.poke((c.cancel==0).B);d.io.in.valid.poke(true.B)
      d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.task.bits.base.poke(0x9000.U);d.io.task.valid.poke(false.B)
      var cycle=1;var sent=false;var returned=false;var due=10000;var first= -1;var consumed=false
      var heldRequest: Option[BigInt]=None;var heldReply: Option[BigInt]=None
      while(!consumed && cycle<100) {
        d.io.cancel.poke((cycle==c.cancel || (c.lateCancel && first>=0)).B)
        d.io.memoryRequest.ready.poke((cycle>=6).B)
        d.io.memoryResponse.valid.poke((sent && !returned && cycle>=due).B)
        d.io.memoryResponse.bits.warpId.poke(1.U);d.io.memoryResponse.bits.oldValue.poke(42.U)
        d.io.memoryResponse.bits.fault.poke(c.memoryFault.B)
        val req=d.io.memoryRequest
        heldRequest.foreach(v=>{req.valid.expect(true.B);assert(req.bits.peek().litValue==v)})
        if(req.valid.peek().litToBoolean) {
          assert(!c.reject && !sent)
          req.bits.warpId.expect(1.U);req.bits.address.expect(c.address.U)
          req.bits.operand.expect(7.U);req.bits.operation.expect(AtomicMemoryOp.add)
          if(req.ready.peek().litToBoolean){sent=true;due=cycle+4;heldRequest=None}
          else heldRequest=Some(req.bits.peek().litValue)
        }
        if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)returned=true
        val out=d.io.out
        heldReply.foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v)})
        if(out.valid.peek().litToBoolean) {
          if(first<0)first=cycle
          val fault=c.reject || c.memoryFault || (c.cancel>=0 && c.cancel<first)
          out.bits.warpId.expect(1.U);out.bits.fault.expect(fault.B);out.bits.oldValue.expect((if(fault)0 else 42).U)
          assert(c.reject || returned)
          if(cycle-first>=5){out.ready.poke(true.B);consumed=true}
          else heldReply=Some(out.bits.peek().litValue)
        }
        d.io.drained.expect(false.B);d.clock.step();cycle+=1
      }
      assert(consumed && sent== !c.reject);d.io.drained.expect(true.B)
      info(s"PAYLOAD_ATOMIC case=${c.name} forwarded=$sent heldDrain=true")
    }
  }
}
