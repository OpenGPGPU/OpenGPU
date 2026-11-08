package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcLdrBlockDecoderSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def fp(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def unorm16(n: Int): BigInt=fp(n.toFloat/65535.0f)
  private def block(r: Int,g: Int,b: Int,a: Int,hdr: Boolean=false,reserved: Boolean=true,pattern: Int=0x1fc): BigInt={
    var w=BigInt(0)
    w|=BigInt(r&0xffff)<<64;w|=BigInt(g&0xffff)<<80;w|=BigInt(b&0xffff)<<96;w|=BigInt(a&0xffff)<<112
    w|=((BigInt(1)<<52)-1)<<12 // every void-extent coordinate bit set: extent ignored
    w = w | (if(reserved)BigInt(0xc00)else BigInt(0x400))
    w = w | (if(hdr)BigInt(0x200)else BigInt(0))
    w|=BigInt(pattern&0x1ff)
    w
  }
  behavior of "AstcLdrBlockDecoder"
  it should "decode LDR void-extent constant-color blocks and flag illegal or unsupported blocks" in {
    simulate(new AstcLdrBlockDecoder){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(value: BigInt): (Int,Boolean,Seq[BigInt])={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.block.poke(value.U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<200){d.clock.step();n+=1}
        assert(n<200);d.io.out.bits.tag.expect(tag.U)
        val status=d.io.out.bits.status.peek().litValue.toInt;val void=d.io.out.bits.voidExtent.peek().litToBoolean
        val rgba=(0 until 4).map(i=>d.io.out.bits.rgba(i).peek().litValue).toVector
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 2){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        (status,void,rgba)
      }
      val zeroBlock=run(block(0,0,0,0))
      assert(zeroBlock==(0,true,Seq(BigInt(0),BigInt(0),BigInt(0),BigInt(0))))
      val red=run(block(0xffff,0,0,0xffff))
      assert(red==(0,true,Seq(fp(1f),BigInt(0),BigInt(0),fp(1f))))
      val half=run(block(0x8000,0x4000,0x2000,0xffff))
      assert(half==(0,true,Seq(unorm16(0x8000),unorm16(0x4000),unorm16(0x2000),fp(1f))))
      val white=run(block(0xffff,0xffff,0xffff,0xffff))
      assert(white==(0,true,Seq(fp(1f),fp(1f),fp(1f),fp(1f))))
      val error=Seq(fp(1f),BigInt(0),fp(1f),fp(1f))
      assert(run(block(1,2,3,4,hdr=true))==(2,false,error))
      assert(run(block(1,2,3,4,reserved=false))==(2,false,error))
      assert(run(block(1,2,3,4,pattern=0x000))==(1,false,error))
      info("ASTC_VOID_EXTENT ldr=true unorm16Exact=true hdrRejected=true reservedRejected=true nonVoidUnsupported=true")
    }
  }
}
