package opengpu.core.memory

import chisel3._
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderPayloadAccessArbiterSpec extends AnyFlatSpec {
  it should "retain a public offer, route reordered replies, and restore both original tags before reuse" in {
    val p=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=2)
    simulate(new ShaderPayloadAccessArbiter(p)){d=>
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      for(i<-0 until 2){d.io.requests(i).valid.poke(false.B);d.io.requests(i).bits.poke(0.U.asTypeOf(d.io.requests(i).bits));d.io.responses(i).ready.poke(false.B)}
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(round<-0 until 3){
        for(i<-0 until 2){val q=d.io.requests(i);q.bits.handle.poke(i.U);q.bits.generation.poke((17+round).U)
          q.bits.context.poke((23+i).U);q.bits.owner.poke(i.U);q.bits.tag.poke((37+164*i+round).U)
          q.bits.offset.poke((64*i).U);q.bits.mask.poke((BigInt(15)<<i).U);q.bits.data.poke((1234+i).U)
          q.valid.poke(true.B);q.ready.expect(true.B);d.clock.step();q.valid.poke(false.B)
          if(i==0){d.io.request.valid.expect(true.B);val held=d.io.request.bits.peek().litValue
            d.clock.step();assert(d.io.request.bits.peek().litValue==held)}
        }
        val first=d.io.request.bits.peek().litValue
        for(_<-0 until 4){d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==first);d.clock.step()}
        val seen=scala.collection.mutable.ArrayBuffer.empty[Int]
        d.io.request.ready.poke(true.B)
        for(_<-0 until 2){d.io.request.valid.expect(true.B);val id=d.io.request.bits.tag.peek().litValue.toInt
          seen+=id;d.io.request.bits.context.expect((23+id).U);d.clock.step()}
        d.io.request.ready.poke(false.B);assert(seen.toSet==Set(0,1));d.io.drained.expect(false.B)
        for(i<-seen.reverse){val r=d.io.response;r.bits.tag.poke(i.U);r.bits.handle.poke(i.U)
          r.bits.generation.poke((17+round).U);r.bits.context.poke((23+i).U);r.bits.data.poke((0x123400+i).U)
          r.valid.poke(true.B);d.io.responses(i).valid.expect(true.B);d.io.responses(1-i).valid.expect(false.B)
          d.io.responses(i).bits.tag.expect((37+164*i+round).U);d.io.responses(i).bits.data.expect((0x123400+i).U)
          val held=d.io.responses(i).bits.peek().litValue
          for(_<-0 until 3){r.ready.expect(false.B);d.clock.step();assert(d.io.responses(i).bits.peek().litValue==held)}
          d.io.responses(i).ready.poke(true.B);r.ready.expect(true.B);d.clock.step()
          d.io.responses(i).ready.poke(false.B);r.valid.poke(false.B)
        }
        d.io.drained.expect(true.B)
      }
    }
  }
}
