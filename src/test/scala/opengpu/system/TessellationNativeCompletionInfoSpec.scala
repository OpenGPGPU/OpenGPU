package opengpu.system

import chisel3._
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TessellationNativeCompletionInfoHarness extends Module {
  val io=IO(new Bundle {
    val in=Input(new TessellationNativeJobCompletion(8,VertexShaderStageConfig()))
    val out=Output(Vec(12,UInt(32.W)))
  })
  io.out:=VecInit(TessellationNativeCompletionInfo.words(io.in))
}

class TessellationNativeCompletionInfoSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool=>b.poke(false.B)
    case s:SInt=>s.poke(0.S)
    case u:UInt=>u.poke(0.U)
    case _=>()
  }
  it should "preserve reader and nested fault ownership in the completion snapshot" in {
    simulate(new TessellationNativeCompletionInfoHarness){d=>
      zero(d.io.in)
      val c=d.io.in;c.status.poke(3.U);c.faultStage.poke(2.U);c.faultAddress.poke("h12345640".U);c.faultDraw.poke(7.U)
      c.identity.context.poke(11.U);c.identity.pass.poke(12.U);c.identity.generation.poke(13.U)
      c.frame.status.poke(6.U);c.frame.img.status.poke(3.U);c.frame.img.faultStage.poke(2.U)
      c.frame.img.faultTileValid.poke(true.B);c.frame.img.faultTile.poke(19.U)
      c.frame.input.faultAddress.poke("h44444440".U);c.frame.input.taskId.poke(23.U)
      d.io.out(0).expect("h474e5a01".U);d.io.out(1).expect((3+16+64+256).U)
      d.io.out(2).expect("h12345640".U);d.io.out(3).expect(7.U);d.io.out(4).expect(19.U)
      d.io.out(5).expect((6+3*8).U);d.io.out(6).expect(11.U);d.io.out(7).expect(12.U);d.io.out(8).expect(13.U)
      d.io.out(9).expect(0.U);d.io.out(10).expect(0.U);d.io.out(11).expect(0.U)
      c.frame.inputFaultValid.poke(true.B);d.io.out(1).expect((3+16+64+128+256).U)
      d.io.out(9).expect("h44444440".U);d.io.out(10).expect(23.U)
    }
  }
}
