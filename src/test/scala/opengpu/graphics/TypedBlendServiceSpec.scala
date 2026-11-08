package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedBlendServiceSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  behavior of "TypedBlendService"
  private class Driver(val d: TypedBlendService) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def submit(source: Seq[Float],destination: Seq[Float],ordinary: BigInt,advanced: Boolean,equation: Int,
      state: TypedBlendState=>Unit=(_: TypedBlendState)=>(),tag: Int=1): Unit={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U)
      for(c<-0 until 4){q.source(c).poke(bits(source(c)).U);q.destination(c).poke(bits(destination(c)).U)}
      q.ordinaryDestination.poke(ordinary.U);q.format.poke(TextureImageFormat.id("RGBA8").U)
      q.state.version.poke(1.U);state(q.state)
      q.advanced.poke(advanced.B);q.advancedEquation.poke(equation.U)
      d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
    }
    def expectResult(tag: Int,status: Int,values: Seq[BigInt]): Unit={
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<8000){d.clock.step();n+=1}
      assert(n<8000);d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect(status.U)
      d.io.out.bits.rgba.zip(values).foreach{case(a,b)=>a.expect(b.U)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
    }
  }
  it should "route ordinary, separable and HSL requests and gate the blend barrier" in {
    simulate(new TypedBlendService){d=>val t=new Driver(d)
      // Ordinary core blend: RGBA8 destination (1,0,0,1), factors ONE/ONE, add.
      t.submit(Seq(0.5f,0.25f,0.75f,1.0f),Seq(0f,0f,0f,0f),
        0xff0000ffL,advanced=false,equation=0,state=s=>{s.enable.poke(true.B);s.srcRGB.poke(1.U);s.dstRGB.poke(1.U)
          s.srcAlpha.poke(1.U);s.dstAlpha.poke(1.U);s.equationRGB.poke(0.U);s.equationAlpha.poke(0.U)})
      d.io.barrierReady.expect(false.B)
      t.expectResult(1,0,Seq(bits(1f),bits(0.25f),bits(0.75f),bits(1f)))
      d.io.barrierReady.expect(true.B)
      // Separable multiply with premultiplied opaque colors.
      t.submit(Seq(0.5f,0.25f,0.75f,1f),Seq(0.5f,0.5f,0.5f,1f),0,advanced=true,equation=0,tag=2)
      t.expectResult(2,0,Seq(bits(0.25f),bits(0.125f),bits(0.375f),bits(1f)))
      // HSL hue with identical colors leaves the color unchanged.
      t.submit(Seq(0.5f,0.25f,0.75f,1f),Seq(0.5f,0.25f,0.75f,1f),0,advanced=true,equation=11,tag=3)
      t.expectResult(3,0,Seq(bits(0.5f),bits(0.25f),bits(0.75f),bits(1f)))
      // Unsupported advanced equation is rejected before any child work.
      t.submit(Seq(0.5f,0.25f,0.75f,1f),Seq(0.5f,0.5f,0.5f,1f),0,advanced=true,equation=15,tag=4)
      t.expectResult(4,1,Seq(BigInt(0),BigInt(0),BigInt(0),BigInt(0)))
      d.io.barrierReady.expect(true.B)
      info("BLEND_SERVICE ordinary=true separable=true hsl=true unsupportedRejected=true barrierGated=true")
    }
  }
  it should "hold results and keep the barrier closed until retirement" in {
    simulate(new TypedBlendService){d=>val t=new Driver(d)
      t.submit(Seq(0.5f,0.25f,0.75f,1f),Seq(0.5f,0.5f,0.5f,1f),0,advanced=true,equation=1,tag=5)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<8000){d.clock.step();n+=1}
      assert(n<8000)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 5){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.barrierReady.expect(false.B);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      d.io.barrierReady.expect(true.B)
      info("BLEND_SERVICE_HOLD immutable=true barrierClosesUntilRetire=true singleOutstanding=true")
    }
  }
}
