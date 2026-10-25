package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class PreparedTypedColorEncoderSpec extends AnyFlatSpec {
  import TypedColorReference._
  it should "encode all36 internal formats with exact component masks, saturation and held replies" in {
    assert(names.size==36 && names==TypedColorFormat.formats.map(_.name))
    simulate(new PreparedTypedColorEncoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var cases=0
      def check(name: String,v: Seq[BigInt],mask: Int=15): Unit={
        val f=format(name);d.io.in.bits.format.poke(TypedColorFormat.id(name).U);d.io.in.bits.colorType.poke(f.kind.U)
        d.io.in.bits.mask.poke(mask.U);d.io.in.bits.tag.poke((cases&255).U);d.io.in.bits.rgba.zip(v).foreach{case(a,b)=>a.poke(b.U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<40){d.clock.step();n+=1};assert(d.io.out.valid.peek().litToBoolean)
        if(name!="SRGB8_ALPHA8")assert(n<=7,"four components must issue without numeric or packing bubbles")
        val(vv,mm)=encode(name,v,mask);d.io.out.bits.value.expect(vv.U);d.io.out.bits.mask.expect(mm.U);d.io.out.bits.bytes.expect(f.bytes.U)
        d.io.out.bits.status.expect(0.U);d.io.out.bits.tag.expect((cases&255).U)
        val held=d.io.out.bits.peek().litValue;for(_<-0 until (cases%3)){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);cases+=1
      }
      val random=new Random(0x657843)
      val directed=Vector(0L,0x80000000L,0x3f000000L,0x3f800000L,0xbf800000L,0x7f800000L,0xff800000L,0x7fc01234L,0xffffffffL,0x3effffffL,0x3f000001L,0x477ff000L)
      for(name<-names){
        for(i<-directed.indices)check(name,Vector.tabulate(4)(c=>BigInt(directed((i+c)%directed.size))),i%16)
        for(_<-0 until 40)check(name,Vector.fill(4)(BigInt(random.nextLong()&0xffffffffL)),random.nextInt(16))
      }
      // Exercise the bounded UNORM carry at the upper endpoint, including
      // values on both sides of saturation and tiny/negative/NaN inputs.
      for(name<-Seq("RGB10_A2","RGBA8","RGB565","SRGB8_ALPHA8");
          raw<-Seq(0x3f7ffffdL,0x3f7ffffeL,0x3f7fffffL,0x3f800000L,0x3f800001L,
            1L,0x007fffffL,0x80000001L,0x7fc00001L))
        check(name,Vector.fill(4)(BigInt(raw)))
      // Three neighboring FP32 values at every UNORM quantization boundary.
      // The rational reference remains independent of the pipelined product.
      for(name<-Seq("RGB5_A1","RGB10_A2","RGBA4","RGB565","RGBA8")){
        val widths=TypedColorFormat.formats.find(_.name==name).get.widths.distinct.filter(_>0)
        for(bits<-widths;q<-0 until ((1<<bits)-1)){
          val boundary=((q+0.5)/((1<<bits)-1)).toFloat
          val raw=java.lang.Float.floatToRawIntBits(boundary)&0xffffffffL
          for(delta<- -1 to 1)check(name,Vector.fill(4)(BigInt(raw+delta)))
        }
      }
      // Forward transfer is independent of the hardware's inverse-threshold binary search.
      for(i<-0 until 255){val y=(i+0.5)/255;val x=if(y<=0.04045)y/12.92 else math.pow((y+0.055)/1.055,2.4)
        val near=fp(x);for(delta<- -1 to 1)check("SRGB8_ALPHA8",Vector.fill(4)(near+delta))}
      for((id,kind)<-Seq((63,0),(0,1),(3,2))){
        d.io.in.bits.format.poke(id.U);d.io.in.bits.colorType.poke(kind.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.status.expect(1.U);d.io.out.bits.mask.expect(0.U)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info(s"TYPED_COLOR formats=36 requiredBaseFormats=28 encodedCases=$cases sRGBTransitions=765 exactUNORM=true integerSaturation=true componentMasks=true heldReplies=true invalidTypes=3")
    }
  }
}
