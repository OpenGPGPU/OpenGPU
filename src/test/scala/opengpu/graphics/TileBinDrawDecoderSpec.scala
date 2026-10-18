package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinDrawDecoderSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private val cfg=GraphicsConfig(screenWidth=32,screenHeight=32)
  it should "decode full precision geometry, alpha, unsigned UV and immutable draw bindings" in {
    simulate(new TileBinDrawDecoder(cfg)) { d =>
      d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val draw=Draw(0xa1b2c37fL,-2147483648L,Vector((0x80000001L,0xffffffffL),
        (123L,456L),(0xffffffffL,0x80000000L)),
        Vector((-2147483648L,2147483647L,-1L,65536L),(65536L,0L,1L,131072L),
          (0L,-65536L,2147483647L,65536L)),Some((3,5,17,23)))
      val s=state.updated(1,flags|(2L<<8)|(1L<<10)|(1L<<11)|(7L<<12)|(37L<<19)|(9L<<25))
        .updated(2,0x0000051dL).updated(6,0x80000100L).updated(7,0xf0000000L)
        .updated(8,0x10000L).updated(9,0xf1234000L).updated(10,123|(456L<<16)).updated(11,4L)
      pokeResolved(d.io.in.bits,primitive(draw),s,tile=3)
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.out.valid.expect(true.B); d.io.out.bits.validEncoding.expect(true.B)
      val b=d.io.out.bits; b.source.tile.expect(3.U); b.source.ordinal.expect(5.U)
      b.source.reference.sequence.expect(19.U); b.source.reference.token.expect(29.U)
      for (v <- 0 until 3) {
        val c=draw.clip(v)
        b.draw.clip(v).x.expect(c._1.S); b.draw.clip(v).y.expect(c._2.S)
        b.draw.clip(v).z.expect(c._3.S); b.draw.clip(v).w.expect(c._4.S)
        b.draw.color(v).r.expect(0xa1.U); b.draw.color(v).g.expect(0xb2.U)
        b.draw.color(v).b.expect(0xc3.U); b.draw.alpha(v).expect(0x7f.U)
        b.draw.depth(v).expect((-2147483648L).S)
        b.draw.uv(v).u.expect(draw.uv(v)._1.U); b.draw.uv(v).v.expect(draw.uv(v)._2.U)
      }
      b.draw.stateOverride.expect(true.B); b.draw.scissorEnable.expect(true.B)
      b.draw.scissorMinX.expect(3.U); b.draw.scissorMinY.expect(5.U)
      b.draw.scissorMaxX.expect(17.U); b.draw.scissorMaxY.expect(23.U)
      b.draw.depthTestEnable.expect(true.B); b.draw.depthWriteEnable.expect(true.B)
      b.draw.blendEnable.expect(true.B); b.draw.blendCfgEnable.expect(true.B)
      b.draw.blendSrcFactor.expect(4.U); b.draw.blendDstFactor.expect(5.U)
      b.draw.stencilFunc.expect(6.U); b.draw.stencilZFailOp.expect(5.U)
      b.draw.stencilZPassOp.expect(3.U); b.draw.stencilRef.expect(3.U)
      b.draw.stencilReadMask.expect(255.U); b.draw.stencilWriteMask.expect(15.U)
      b.draw.texEnable.expect(true.B); b.draw.texWrapClamp.expect(true.B)
      b.draw.texLodBias.expect((-3).S); b.draw.texMinLevel.expect(5.U)
      b.draw.texMaxLevel.expect(7.U); b.draw.cullMode.expect(2.U)
      b.draw.fragInputSkip.expect(37.U); b.draw.fragOutputSkip.expect(9.U)
      b.draw.shaderPc.expect(0x80000100L.U); b.draw.shaderKernarg.expect(0xf0000000L.U)
      b.draw.kernargBankStride.expect(0x10000.U)
      b.texture.base.expect(0xf1234000L.U); b.texture.texWidth.expect(123.U)
      b.texture.texHeight.expect(456.U); b.texture.format.expect(4.U)
      val held=b.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 8) {
        d.io.in.ready.expect(false.B); d.clock.step(); d.io.out.valid.expect(true.B)
        assert(b.peek().litValue==held)
      }
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.busy.expect(false.B)
      d.io.cancel.poke(false.B); d.io.in.ready.expect(true.B)
    }
  }

  it should "reject unknown versions, reserved bits and unsupported state without substitute draws" in {
    simulate(new TileBinDrawDecoder(cfg)) { d =>
      d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val p=primitive(draws.head); val s=state
      val bad=Seq((p.updated(0,p(0)+1),s),(p.updated(1,2L),s),
        (p,s.updated(0,s(0)+1)),(p,s.updated(1,s(1)& ~1L)),
        (p,s.updated(1,s(1)|(1L<<18))),(p,s.updated(1,s(1)|(3L<<8))),
        (p,s.updated(2,32L)),(p,s.updated(3,0xb1L)),(p,s.updated(3,5L<<12)),
        (p,s.updated(4,4096L)),(p,s.updated(5,1L<<24)),
        (p,s.updated(10,1L<<14)),(p,s.updated(11,5L)),
        (p,s.updated(1,s(1)|(1L<<10)).updated(10,0L))) ++
        (28 until 32).map(i=>(p.updated(i,1L),s)) ++
        (12 until 16).map(i=>(p,s.updated(i,1L)))
      for (((pv,sv),n) <- (bad:+((p,s))).zipWithIndex) {
        pokeResolved(d.io.in.bits,pv,sv)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
        d.io.in.valid.poke(false.B); d.io.out.valid.expect(true.B)
        d.io.out.bits.validEncoding.expect((n==bad.size).B)
        d.io.out.bits.source.reference.token.expect(29.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }
}
