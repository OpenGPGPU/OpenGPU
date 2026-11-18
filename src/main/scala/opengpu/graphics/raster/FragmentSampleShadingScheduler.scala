package opengpu.graphics

import chisel3._
import chisel3.util._

/** Schedule fragment shader invocations for one covered pixel.
  *
  * With sample shading enabled and a nonzero minimum sample shading value the
  * owner emits one invocation per covered sample in ascending sample order,
  * carrying the sample id and its programmed position. Otherwise it emits one
  * invocation at the pixel center with the complete coverage mask. Zero
  * coverage and out-of-range sample modes are rejected before any invocation.
  *
  * The 32-bit minimum value is consumed only as zero versus nonzero; ES 3.2's
  * minimum specifies a lower bound on unique input sets; this scheduler
  * conservatively chooses every covered sample for a nonzero value.
  * Shader execution, interpolation at the emitted positions and per-sample
  * output merging remain owners outside this scheduler.
  */
class SampleShadingRequest(g: GraphicsConfig=GraphicsConfig()) extends Bundle {
  val tag=UInt(32.W);val x=SInt(32.W);val y=SInt(32.W)
  val coverage=UInt(g.maxSampleCount.W);val sampleMode=UInt(2.W)
  val enable=Bool();val minValue=UInt(32.W)
}
class SampleShadingInvocation(g: GraphicsConfig=GraphicsConfig()) extends Bundle {
  val tag=UInt(32.W);val x=SInt(32.W);val y=SInt(32.W)
  val sampleId=UInt(2.W);val positionX=SInt(4.W);val positionY=SInt(4.W)
  val coverageMask=UInt(g.maxSampleCount.W);val invocation=UInt(3.W);val perSample=Bool()
}
class SampleShadingResult extends Bundle {
  val tag=UInt(32.W);val status=UInt(3.W);val invocations=UInt(3.W);val perSample=Bool()
}
object SampleShadingStatus {val ok=0;val invalid=1;val cancelled=4}

class FragmentSampleShadingScheduler(g: GraphicsConfig=GraphicsConfig()) extends Module {
  require(Set(1,2,4)(g.maxSampleCount))
  private val maxMode=log2Ceil(g.maxSampleCount)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new SampleShadingRequest(g)))
    val out=Decoupled(new SampleShadingInvocation(g))
    val completion=Decoupled(new SampleShadingResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,run,finish,complete=Value}
  private val phase=RegInit(State.idle)
  private val request=Reg(new SampleShadingRequest(g))
  private val result=Reg(new SampleShadingResult)
  private val status=RegInit(0.U(3.W));private val emitted=RegInit(0.U(3.W))
  private val sample=RegInit(0.U(3.W));private val perSample=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val samples=1.U(3.W)<<request.sampleMode
  private val sx=MuxLookup(request.sampleMode,0.S(4.W))(Seq(
    1.U->Mux(sample===0.U,(-1).S(4.W),1.S(4.W)),
    2.U->Mux(sample(0),1.S(4.W),(-1).S(4.W))))
  private val sy=MuxLookup(request.sampleMode,0.S(4.W))(Seq(
    1.U->Mux(sample===0.U,(-1).S(4.W),1.S(4.W)),
    2.U->Mux(sample(1),1.S(4.W),(-1).S(4.W))))
  private val covered=(request.coverage>>sample)(0)
  private val emitIndex=emitted
  io.in.ready:=phase===State.idle && !io.cancel
  io.out.valid:=phase===State.run && Mux(perSample,covered,true.B) && !stop
  io.out.bits:=0.U.asTypeOf(io.out.bits)
  io.out.bits.tag:=request.tag;io.out.bits.x:=request.x;io.out.bits.y:=request.y
  io.out.bits.sampleId:=Mux(perSample,sample,0.U)
  io.out.bits.positionX:=Mux(perSample,sx,0.S)
  io.out.bits.positionY:=Mux(perSample,sy,0.S)
  io.out.bits.coverageMask:=Mux(perSample,1.U<<sample,request.coverage)
  io.out.bits.invocation:=emitIndex;io.out.bits.perSample:=perSample
  io.busy:=phase=/=State.idle
  when(io.in.fire){
    request:=io.in.bits;status:=0.U;emitted:=0.U;sample:=0.U
    perSample:=io.in.bits.enable && io.in.bits.minValue=/=0.U;phase:=State.check
  }
  switch(phase){
    is(State.check){
      val highCoverage=(request.coverage>>samples).orR
      when(stop){phase:=State.finish}
        .elsewhen(request.sampleMode>maxMode.U || !request.coverage.orR || highCoverage){
          status:=SampleShadingStatus.invalid.U;phase:=State.finish}
        .otherwise{phase:=State.run}
    }
    is(State.run){
      when(stop){phase:=State.finish}
        .elsewhen(perSample){
          when(sample>=samples){phase:=State.finish}
            .elsewhen(!covered){sample:=sample+1.U}
            .elsewhen(io.out.fire){emitted:=emitted+1.U;sample:=sample+1.U;phase:=State.run}
        }.otherwise{
          when(io.out.fire){emitted:=1.U;phase:=State.finish}
        }
    }
    is(State.finish){
      result:=0.U.asTypeOf(result);result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,SampleShadingStatus.cancelled.U,status)
      result.invocations:=emitted;result.perSample:=perSample
      phase:=State.complete
    }
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(active && io.cancel && status===0.U){status:=SampleShadingStatus.cancelled.U}
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
}
