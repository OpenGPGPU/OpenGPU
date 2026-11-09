package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class AstcSampleHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageLevelSampleRequest))
    val out=Decoupled(new TextureImageLevelSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
  })
  private val sample=Module(new TextureImageLevelSampleUnit(config))
  sample.io.in<>io.in;io.out<>sample.io.out
  sample.io.memoryRequest<>io.memoryRequest;io.memoryResponse<>sample.io.memoryResponse
  sample.io.cancel:=false.B;sample.io.invalidate:=false.B
}

class AstcSamplingSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private def bits(v: Float)=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def voidBlock(r: Int,g: Int,b: Int,a: Int): BigInt={
    var w=BigInt(0);w|=BigInt(r)<<64;w|=BigInt(g)<<80;w|=BigInt(b)<<96;w|=BigInt(a)<<112
    w|=((BigInt(1)<<52)-1)<<12;w|=BigInt(0xc00);w|0x1fc}
  private def srgbLinear(byte: Int): BigInt={
    val x=byte/255.0
    val l=if(x<=0.04045)x/12.92 else math.pow((x+0.055)/1.055,2.4)
    bits(l.toFloat)}
  private case class Image(format: Int,blockW: Int,blockH: Int,red: Int=0xffff) {
    val base=BigInt(0x100000);val row=32;val limit=base+128
    val block=voidBlock(red,0,0,0xffff)
  }
  private class Driver(val d: AstcSampleHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    memory.beforeRequest=r=>assert(!r.write && r.address%64==0 && r.mask==(BigInt(1)<<64)-1)
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<20000){tick();n+=1};assert(p,s"astc sample timeout")}
    def load(i: Image): Unit={
      for(b<-0 until 8){
        val at=i.base+16*b;val raw=i.block
        for(j<-0 until 16)memory.bytes(at+j)=((raw>>(8*j))&255).toInt
      }
    }
    def run(i: Image,linear: Boolean,tag: Int): (Int,Int,Vector[BigInt])={
      load(i);val q=d.io.in.bits;zero(q)
      q.image.validBinding.poke(true.B);q.image.format.poke(i.format.U)
      q.image.selected.table.version.poke(1.U);q.image.selected.table.base.poke(0x2000.U);q.image.selected.table.limit.poke(0x2080.U)
      val v=q.image.view;v.target.poke(TextureImageTarget.image2D.U)
      v.allocationBase.poke(i.base.U);v.allocationLimit.poke(i.limit.U);v.base.poke(i.base.U)
      v.texWidth.poke(8.U);v.texHeight.poke(8.U);v.texDepth.poke(1.U);v.layers.poke(1.U)
      v.rowPitch.poke(i.row.U);v.slicePitch.poke(0.U);v.layerPitch.poke(0.U)
      v.block4x4.poke(false.B);v.blockBytes.poke(16.U);v.blockWidth.poke(i.blockW.U);v.blockHeight.poke(i.blockH.U)
      q.image.swizzle.version.poke(1.U)
      q.image.swizzle.r.poke(0.U);q.image.swizzle.g.poke(1.U);q.image.swizzle.b.poke(2.U);q.image.swizzle.a.poke(3.U)
      q.sampler.validSampler.poke(true.B);val st=q.sampler.state
      st.minFilter.poke((if(linear)1 else 0).U);st.magFilter.poke((if(linear)1 else 0).U)
      st.wrapS.poke(1.U);st.wrapT.poke(1.U);st.wrapR.poke(1.U);st.minLod.poke(0.U);st.maxLod.poke(0.U)
      st.compareEnable.poke(false.B);st.compareFunction.poke(0.U)
      q.coordinates(0).poke(bits(0.3f).U);q.coordinates(1).poke(bits(0.6f).U);q.coordinates(2).poke(0.U)
      q.layer.poke(0.U);q.face.poke(0.U);q.linear.poke(linear.B);q.shadow.poke(false.B)
      q.reference.poke(0.U);q.applySwizzle.poke(true.B);q.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(q)
      until(d.io.out.valid.peek().litToBoolean)
      val o=d.io.out.bits
      val r=(o.status.peek().litValue.toInt,o.colorType.peek().litValue.toInt,
        (0 until 4).map(c=>o.rgba(c).peek().litValue).toVector)
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)
      r
    }
  }
  behavior of "ASTC normalized sampling"
  it should "sample linear and sRGB ASTC blocks with nearest and linear filters" in {
    simulate(new AstcSampleHarness){d=>val t=new Driver(d)
      for(linear<-Seq(false,true)){
        val (status,kind,rgba)=t.run(Image(65,4,4),linear,1)
        assert(status==0,s"linear=$linear status=$status");assert(kind==0)
        assert(rgba==Vector(bits(1f),BigInt(0),BigInt(0),bits(1f)),s"linear=$linear rgba=$rgba")
      }
      for(linear<-Seq(false,true)){
        val red=0x8080
        val (status,kind,rgba)=t.run(Image(66,4,4,red),linear,2)
        assert(status==0,s"sRGB linear=$linear status=$status");assert(kind==0)
        assert(rgba==Vector(srgbLinear(0x80),BigInt(0),BigInt(0),bits(1f)),s"sRGB linear=$linear rgba=$rgba")
      }
      val (status,_,rgba)=t.run(Image(92,12,12),false,3)
      assert(status==0 && rgba==Vector(bits(1f),BigInt(0),BigInt(0),bits(1f)),s"12x12 status=$status rgba=$rgba")
      info("ASTC_SAMPLING nearest=true linear=true srgb=true footprint12=true")
    }
  }
}
