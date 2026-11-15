package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** The same production mux and workers instantiated by GpuComputeUnit. This
  * fixture does not model fetch/launch/core retirement or claim full-CU proof.
  */
private class TessellationInfoMuxFixture extends Module {
  private val p=GpuConfig(lanes=16,warps=8)
  val io=IO(new Bundle {
    val start=Flipped(Valid(new TessellationShaderTaskBinding))
    val warpStart=Flipped(Valid(UInt(p.warpIdWidth.W)))
    val end=Input(Bool());val cancel=Input(Bool());val geometry=Input(Bool())
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(p)))
    val out=Decoupled(new ScalarCommitRequest(p))
    val external=Output(Bool());val enabled=Output(Bool());val busy=Output(Bool())
  })
  private val info=Module(new TessellationScalarInfoWorker(p))
  private val mux=Module(new TessellationScalarInfoMux(p))
  private val geometry=Module(new GeometryScalarWorker(p))
  info.io.start<>io.start;info.io.warpStart<>io.warpStart;info.io.end:=io.end;info.io.cancel:=io.cancel
  mux.io.bindingActive:=info.io.active;mux.io.stage:=info.io.stage;mux.io.geometryOwned:=geometry.io.active
  mux.io.in<>io.in;info.io.in<>mux.io.info;mux.io.infoCommit<>info.io.commit;io.out<>mux.io.out
  geometry.io.bind.valid:=io.start.valid && io.geometry
  geometry.io.bind.bits:=0.U.asTypeOf(geometry.io.bind.bits)
  geometry.io.bind.bits.warpId:=0.U
  geometry.io.bind.bits.invocation.invocationId:=11.U
  geometry.io.bind.bits.invocation.primitiveId:=22.U
  geometry.io.bind.bits.invocation.inputVertices:=1.U
  geometry.io.bind.bits.invocation.inputComponents:=3.U
  geometry.io.bind.bits.invocation.outputComponents:=4.U
  geometry.io.bind.bits.invocation.taskId:=55.U
  geometry.io.release.valid:=io.end && geometry.io.active;geometry.io.release.bits:=true.B
  geometry.io.cancel:=io.cancel;geometry.io.event.ready:=false.B
  private val legacyGeometry=geometry.io.active && GeometryScalarOpcode.legal(mux.io.legacy.bits.decode.instruction)
  geometry.io.in.valid:=mux.io.legacy.valid && legacyGeometry;geometry.io.in.bits:=mux.io.legacy.bits
  mux.io.legacy.ready:=legacyGeometry && geometry.io.in.ready
  mux.io.legacyCommit<>geometry.io.commit
  io.external:=mux.io.legacy.valid && !legacyGeometry
  io.enabled:=info.io.enabled;io.busy:=info.io.busy || geometry.io.busy
}

class TessellationInfoMuxSpec extends AnyFlatSpec {
  it should "retire unsupported TES selectors without an external texture owner and preserve actual old geometry meanings" in {
    simulate(new TessellationInfoMuxFixture) { d=>
      d.io.start.valid.poke(false.B);d.io.start.bits.poke(0.U.asTypeOf(d.io.start.bits))
      d.io.warpStart.valid.poke(false.B);d.io.warpStart.bits.poke(0.U)
      d.io.end.poke(false.B);d.io.cancel.poke(false.B);d.io.geometry.poke(false.B)
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def start(stage:Int,geometry:Boolean):Unit={
        d.io.geometry.poke(geometry.B);d.io.start.bits.version.poke(1.U);d.io.start.bits.stage.poke(stage.U)
        d.io.start.bits.taskId.poke(91.U);d.io.start.bits.inputPatchPrimitiveId.poke(0x87654321L.U)
        d.io.start.valid.poke(true.B);d.clock.step();d.io.start.valid.poke(false.B)
        d.io.warpStart.valid.poke(true.B);d.clock.step();d.io.warpStart.valid.poke(false.B)
      }
      def read(selector:Int,success:Boolean,value:BigInt=0):Unit={
        d.io.in.bits.decode.instruction.poke(((BigInt(37)<<25)|(6<<15)|(5<<7)|0x0b).U)
        d.io.in.bits.decode.warpId.poke(0.U);d.io.in.bits.decode.activeMask.poke(0xffff.U)
        d.io.in.bits.decode.pc.poke(0x1000.U);d.io.in.bits.rs1Data.poke(selector.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.io.external.expect(false.B)
        d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.writeRd.expect(success.B);d.io.out.bits.data.expect(value.U)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.clock.step()}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      def end():Unit={d.io.busy.expect(false.B);d.io.end.poke(true.B);d.clock.step();d.io.end.poke(false.B)}
      start(5,false);read(1,false);read(6,true,BigInt("87654321",16));read(7,false);end()
      start(5,true)
      for((selector,value)<-Seq(0->11,1->22,2->1,3->3,4->4,5->55))read(selector,true,value)
      read(6,true,BigInt("87654321",16));read(7,false);end()
      start(4,false);for(selector<-0 to 5)read(selector,false)
      read(6,true,BigInt("87654321",16));end()
    }
  }
}
