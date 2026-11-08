package opengpu.graphics

import chisel3._
import chisel3.util._

/** Combine raster coverage with a shader-written gl_SampleMask value.
  *
  * `gl_SampleMaskIn` is the raster coverage for the invocation; `gl_SampleMask`
  * can only clear covered samples. Bits at or above the sample count are
  * ignored, a discarded invocation produces no samples, and an unwritten mask
  * leaves the coverage unchanged. Results are held until accepted. Transport of
  * the mask word through a shader output, sample variables and API exposure
  * remain outside this unit.
  */
class FragmentSampleMaskRequest extends Bundle {
  val tag=UInt(8.W);val coverage=UInt(4.W);val shaderMask=UInt(32.W)
  val samples=UInt(3.W);val written=Bool();val discard=Bool()
}
class FragmentSampleMaskResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val effective=UInt(4.W)
  val inputMask=UInt(4.W);val written=Bool()
}
object FragmentSampleMaskStatus {val ok=0;val invalid=1}

class FragmentSampleMaskUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentSampleMaskRequest));val out=Decoupled(new FragmentSampleMaskResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new FragmentSampleMaskResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val q=io.in.bits
  private val lowBits=MuxLookup(q.samples,0.U(4.W))(Seq(1.U->"b0001".U,2.U->"b0011".U,4.U->"b1111".U))
  private val validSamples=q.samples===1.U || q.samples===2.U || q.samples===4.U
  private val coverageCanonical=(q.coverage>>q.samples)===0.U
  private val effective=Mux(q.discard,0.U(4.W),Mux(q.written,q.coverage&q.shaderMask(3,0)&lowBits,q.coverage))
  when(io.in.fire){
    result:=0.U.asTypeOf(result);result.tag:=q.tag;result.inputMask:=Mux(validSamples,q.coverage&lowBits,q.coverage)
    result.written:=q.written
    when(!validSamples || !coverageCanonical){result.status:=FragmentSampleMaskStatus.invalid.U;result.effective:=0.U}
      .otherwise{result.status:=FragmentSampleMaskStatus.ok.U;result.effective:=effective}
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
