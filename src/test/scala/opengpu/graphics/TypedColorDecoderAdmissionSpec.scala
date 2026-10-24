package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedColorDecoderAdmissionSpec extends AnyFlatSpec {
  it should "capture every typed format and reject invalid IDs independently of changing live inputs" in {
    simulate(new TypedColorDecoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(id<-0 until 64){
        val packed=BigInt("81aabbccddeeff001122334455667788",16)
        d.io.in.ready.expect(true.B);d.io.in.bits.format.poke(id.U)
        d.io.in.bits.value.poke(packed.U);d.io.in.bits.tag.poke(id.U);d.io.in.valid.poke(true.B)
        d.clock.step();d.io.in.valid.poke(false.B)
        d.io.in.bits.format.poke(((id+1)%64).U);d.io.in.bits.value.poke(0.U);d.io.in.bits.tag.poke(255.U)
        var clocks=0
        while(!d.io.out.valid.peek().litToBoolean && clocks<8){d.clock.step();clocks+=1}
        assert(clocks<8);val valid=id<TypedColorFormat.formats.size
        d.io.out.bits.tag.expect(id.U);d.io.out.bits.status.expect((if(valid)0 else 1).U)
        val values=if(valid)TypedBlendReference.decode(TypedColorFormat.formats(id).name,packed)else Vector.fill(4)(BigInt(0))
        d.io.out.bits.rgba.zip(values).foreach{case(a,b)=>a.expect(b.U)}
        val held=d.io.out.bits.peek().litValue;d.clock.step(3)
        assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info("TYPED_DECODE_ADMISSION formats=36 invalidIds=28 poisonedLiveInputs=true heldReplies=true")
    }
  }
}
