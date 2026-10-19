package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderPayloadMemoryRouterSpec extends AnyFlatSpec {
  private val gpu=GpuConfig(lanes=4,warps=2)
  private val p=ShaderPayloadConfig(owners=2,ports=2)
  private val full=(BigInt(1)<<64)-1
  private val local=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(0x80+b)<<(8*b)))
  private val memory=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(0x20+b)<<(8*b)))
  private case class Case(name: String,address: Long=0x8100L,base: Long=0x8000L,bytes: Int=288,
    enabled: Boolean=true,mask: BigInt=full,write: Boolean=false,localFault: Boolean=false,
    memoryFault: Boolean=false,pageFault: Boolean=false,cancel: Int= -1,lateCancel: Boolean=false,
    memoryFirst: Boolean=false,malformed: Boolean=false)
  private val cases=Seq(
    Case("mixed-tail"),Case("mixed-tail-memory-first",memoryFirst=true),
    Case("disabled",enabled=false),Case("all-local",address=0x8000),Case("all-memory",address=0x9000),
    Case("selected-bytes",mask=(BigInt(1)<<3)|(BigInt(1)<<39)),
    Case("masked-store",write=true,mask=BigInt("a5a55a5af00f0ff0",16)),
    Case("empty-mask",mask=0),Case("local-fault",localFault=true),
    Case("memory-fault",memoryFault=true),Case("page-fault",pageFault=true,memoryFirst=true),
    Case("cancel-at-accept",cancel=0),Case("cancel-classify",cancel=1),Case("cancel-offered",cancel=4),
    Case("cancel-returned",cancel=13),Case("cancel-held-output",lateCancel=true),
    Case("invalid-extent",bytes=0,malformed=true),Case("invalid-base",base=0x8004,malformed=true),
    Case("invalid-line",address=0x8104,malformed=true),
    Case("overflow",base=0xffffffc0L,address=0xffffffc0L,bytes=65,malformed=true),
    Case("last-address-line",base=0xffffffc0L,address=0xffffffc0L,bytes=64))

  for(c <- cases)it should s"route ${c.name} with stable task ownership and independent fragment drain" in {
    simulate(new ShaderPayloadMemoryRouter(gpu,p)) { d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.payload.request.ready.poke(false.B);d.io.payload.cancel.poke(false.B)
      d.io.payload.response.valid.poke(false.B);d.io.payload.response.bits.poke(0.U.asTypeOf(d.io.payload.response.bits))
      d.io.payload.task.valid.poke(c.enabled.B)
      val b=d.io.payload.task.bits
      b.handle.poke(3.U);b.generation.poke(17.U);b.context.poke(23.U);b.owner.poke(1.U)
      b.base.poke(c.base.U);b.bytes.poke(c.bytes.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val in=d.io.in.bits
      in.warpId.poke(1.U);in.lineAddress.poke(c.address.U);in.isStore.poke(c.write.B)
      in.writeData.poke((local^memory).U);in.byteMask.poke(c.mask.U);in.cachePolicy.poke(0.U)
      d.io.payload.cancel.poke((c.cancel==0).B)
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      // A later task must not change this request's address range or lease.
      b.handle.poke(2.U);b.generation.poke(99.U);b.context.poke(77.U);b.owner.poke(0.U)
      b.base.poke(0x10000.U);b.bytes.poke(64.U)
      val suppressed=c.malformed || c.cancel==0 || c.cancel==1
      val localMask=if(suppressed || !c.enabled)BigInt(0)else (0 until 64).foldLeft(BigInt(0)) { (m,i)=>
        if(c.mask.testBit(i) && c.address+i>=c.base && c.address+i<c.base+c.bytes)m.setBit(i)else m}
      val memoryMask=if(suppressed)BigInt(0)else c.mask & (full^localMask)
      var localSent=false;var memorySent=false;var localReturned=false;var memoryReturned=false
      var localDue=10000;var memoryDue=10000;var localBytes=0;var memoryBytes=0;var split=0
      var heldLocal: Option[BigInt]=None;var heldMemory: Option[BigInt]=None
      var heldOutput: Option[BigInt]=None;var firstOutput= -1;var cycle=1
      var sawOutput=false;var consumed=false
      while(!consumed && cycle<150) {
        d.io.payload.cancel.poke((cycle==c.cancel || (c.lateCancel && firstOutput>=0)).B)
        d.io.payload.request.ready.poke((cycle>=6 && cycle%3!=1).B)
        d.io.memoryRequest.ready.poke((cycle>=4 && cycle%4!=1).B)
        d.io.payload.response.valid.poke((localSent && !localReturned && cycle>=localDue).B)
        d.io.payload.response.bits.handle.poke(3.U);d.io.payload.response.bits.generation.poke(17.U)
        d.io.payload.response.bits.context.poke(23.U);d.io.payload.response.bits.tag.poke(0.U)
        d.io.payload.response.bits.data.poke(local.U);d.io.payload.response.bits.fault.poke(c.localFault.B)
        d.io.memoryResponse.valid.poke((memorySent && !memoryReturned && cycle>=memoryDue).B)
        d.io.memoryResponse.bits.readData.poke(memory.U);d.io.memoryResponse.bits.fault.poke(c.memoryFault.B)
        d.io.memoryResponse.bits.pageFault.poke(c.pageFault.B)
        val l=d.io.payload.request;val m=d.io.memoryRequest
        heldLocal.foreach(v=>{l.valid.expect(true.B);assert(l.bits.peek().litValue==v,"held local request changed")})
        heldMemory.foreach(v=>{m.valid.expect(true.B);assert(m.bits.peek().litValue==v,"held VM request changed")})
        if(l.valid.peek().litToBoolean) {
          assert(localMask!=0 && !localSent)
          l.bits.handle.expect(3.U);l.bits.generation.expect(17.U);l.bits.context.expect(23.U);l.bits.owner.expect(1.U)
          l.bits.offset.expect((c.address-c.base).U);l.bits.mask.expect(localMask.U)
          l.bits.data.expect((local^memory).U);l.bits.write.expect(c.write.B)
          if(l.ready.peek().litToBoolean){localSent=true;localDue=cycle+(if(c.memoryFirst)8 else 2);heldLocal=None}
          else heldLocal=Some(l.bits.peek().litValue)
        }
        if(m.valid.peek().litToBoolean) {
          assert(memoryMask!=0 && !memorySent)
          m.bits.lineAddress.expect(c.address.U);m.bits.byteMask.expect(memoryMask.U)
          m.bits.warpId.expect(1.U);m.bits.writeData.expect((local^memory).U);m.bits.isStore.expect(c.write.B)
          if(m.ready.peek().litToBoolean){memorySent=true;memoryDue=cycle+(if(c.memoryFirst)2 else 10);heldMemory=None}
          else heldMemory=Some(m.bits.peek().litValue)
        }
        if(d.io.payload.response.valid.peek().litToBoolean && d.io.payload.response.ready.peek().litToBoolean)localReturned=true
        if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)memoryReturned=true
        localBytes+=d.io.localBytesAccepted.peek().litValue.toInt
        memoryBytes+=d.io.memoryBytesAccepted.peek().litValue.toInt
        if(d.io.splitLine.peek().litToBoolean)split+=1
        val output=d.io.out
        heldOutput.foreach(v=>{output.valid.expect(true.B);assert(output.bits.peek().litValue==v,"held CU reply changed")})
        if(output.valid.peek().litToBoolean) {
          sawOutput=true
          assert((localMask==0 || localReturned) && (memoryMask==0 || memoryReturned),"CU reply escaped before both fragments drained")
          if(firstOutput<0)firstOutput=cycle
          val fault=c.malformed || (localMask!=0 && c.localFault) ||
            (memoryMask!=0 && (c.memoryFault || c.pageFault)) || (c.cancel>=0 && c.cancel<firstOutput)
          val expected=if(fault || c.write)BigInt(0)else (0 until 64).foldLeft(BigInt(0)) { (v,i)=>
            v|(if(!c.mask.testBit(i))BigInt(0)else ((if(localMask.testBit(i))local else memory)>>(8*i)&255)<<(8*i))}
          output.bits.fault.expect(fault.B);output.bits.pageFault.expect((memoryMask!=0 && c.pageFault).B)
          output.bits.readData.expect(expected.U)
          if(cycle-firstOutput>=6){output.ready.poke(true.B);consumed=true}
          else heldOutput=Some(output.bits.peek().litValue)
        }
        if(!consumed)d.io.payload.drained.expect(false.B)
        d.clock.step();cycle+=1
      }
      assert(sawOutput && consumed)
      d.io.payload.drained.expect(true.B)
      assert(localBytes==localMask.bitCount && memoryBytes==memoryMask.bitCount)
      assert(split==(if(localMask!=0 && memoryMask!=0)1 else 0))
      info(s"PAYLOAD_ROUTE case=${c.name} localBytes=$localBytes memoryBytes=$memoryBytes cycles=$cycle")
    }
  }
}
