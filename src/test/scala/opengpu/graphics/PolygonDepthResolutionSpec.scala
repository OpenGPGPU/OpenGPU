package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class PolygonDepthAddEncodeHarness extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Bundle {val z=UInt(32.W);val r=UInt(32.W);val format=UInt(3.W)}))
    val out=Decoupled(new Bundle {val raw=UInt(32.W);val encoded=UInt(32.W)})
  })
  val service=Module(new ClipFloatArithmetic());val format=Reg(UInt(3.W))
  service.io.in.valid:=io.in.valid;io.in.ready:=service.io.in.ready
  service.io.in.bits.a:=ClipFloat.fromFp32(io.in.bits.z);service.io.in.bits.b:=ClipFloat.fromFp32(io.in.bits.r)
  service.io.in.bits.operation:=ClipFloatOperation.add.U;service.io.in.bits.tag:=0.U;service.io.cancel:=false.B
  when(io.in.fire){format:=io.in.bits.format}
  io.out.valid:=service.io.out.valid;service.io.out.ready:=io.out.ready
  io.out.bits.raw:=service.io.out.bits.fp32;io.out.bits.encoded:=TypedDepthFormat.encode(service.io.out.bits.fp32,format)
}
class PolygonDepthResolutionSpec extends AnyFlatSpec {
  import Fp32ViewportReference.fp
  private def bits(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v).toLong&0xffffffffL)
  it should "select fixed format constants and distinguish absent from malformed depth bindings" in {
    simulate(new PolygonDepthResolution){d=>
      for((format,value)<-Seq((1,BigInt("37804001",16)),(2,BigInt("33c00000",16)),(4,BigInt("33c00000",16)))){
        d.io.format.poke(format.U);d.io.depth.zip(Vector(0.0,0.5,1.0).map(fp)).foreach{case(a,b)=>a.poke(b.U)}
        d.clock.step();d.io.out.valid.expect(true.B);d.io.out.available.expect(true.B);d.io.out.value.expect(value.U)
      }
      for(format<-Seq(0,6,7)){
        d.io.format.poke(format.U);d.io.depth.foreach(_.poke("h7fc00000".U));d.clock.step()
        d.io.out.valid.expect((format!=7).B);d.io.out.available.expect(false.B);d.io.out.value.expect(0.U)
      }
      info("POLYGON_RESOLUTION fixedD16D24=true stencilPairsMatch=true absentDepthUnavailable=true reservedFormatRejected=true")
    }
  }
  it should "derive floating resolution across every depth exponent including gradual subnormal spacing" in {
    simulate(new PolygonDepthResolution){d=>var cases=0
      for(exponent<-0 to 127;fraction<-Seq(0,1,0x3fffff,0x7fffff) if exponent<127 || fraction==0;
          location<-0 until 3;format<-Seq(3,5)){
        val maximum=BigInt(exponent<<23)|BigInt(fraction)
        val values=Vector.fill(3)(BigInt(0)).updated(location,maximum)
        d.io.depth.zip(values).foreach{case(a,b)=>a.poke(b.U)};d.io.format.poke(format.U);d.clock.step()
        val expected=bits(java.lang.Math.ulp(java.lang.Float.intBitsToFloat(maximum.toInt)))
        d.io.out.valid.expect(true.B);d.io.out.available.expect(true.B);d.io.out.value.expect(expected.U);cases+=1
      }
      d.io.depth.foreach(_.poke("h80000000".U));d.clock.step();d.io.out.value.expect(1.U)
      info(s"POLYGON_RESOLUTION_FLOAT cases=$cases exponents=128 allVertexLocations=true mantissaBoundaries=true subnormalFloor=true negativeZero=true depthStencilMatches=true")
    }
  }
  it should "reject invalid active vertex depths before supplying a usable resolution" in {
    simulate(new PolygonDepthResolution){d=>var cases=0
      for(format<-1 to 5;location<-0 until 3;bad<-Seq[BigInt](BigInt("7fc00000",16),BigInt("7f800000",16),BigInt("ff800000",16),BigInt("80000001",16),fp(-1),BigInt("3f800001",16))){
        d.io.depth.foreach(_.poke(fp(0.5).U));d.io.depth(location).poke(bad.U);d.io.format.poke(format.U);d.clock.step()
        d.io.out.valid.expect(false.B);d.io.out.available.expect(false.B);d.io.out.value.expect(0.U);cases+=1
      }
      info(s"POLYGON_RESOLUTION_INVALID cases=$cases NaNInfinityNegativeOverOne=true allVertexLocations=true noUsableResolution=true")
    }
  }
  it should "preserve a resolvable offset at the actual scalar-add and fixed-encode tie boundaries" in {
    simulate(new PolygonDepthAddEncodeHarness){d=>PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def run(format: Int,r: BigInt,expected: BigInt): Unit={
        d.io.in.bits.z.poke(fp(0.5).U);d.io.in.bits.r.poke(r.U);d.io.in.bits.format.poke(format.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.in.bits.format.poke(0.U);var n=0
        while(!d.io.out.valid.peek().litToBoolean && n<100){d.clock.step();n+=1};assert(n<100)
        d.io.out.bits.encoded.expect(expected.U);val held=d.io.out.bits.peek().litValue
        d.clock.step(4);assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      for(format<-Seq(1,2,4)){
        val initial=if(format==1)BigInt(32768)else BigInt(8388608)
        val r=if(format==1)BigInt("37804001",16)else BigInt("33c00000",16)
        run(format,r-1,initial);run(format,r,initial+1)
      }
      info("POLYGON_RESOLUTION_ENCODE tieCases=6 predecessorLosesOffset=true selectedConstantDistinct=true actualClipScalar=true actualFixedEncode=true immutableReply=true")
    }
  }
}
