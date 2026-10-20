package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32FmaLane,Fp32Operation,Fp32Request}

class TextureLodBiasRequest extends Bundle {val lod=UInt(32.W);val bias=UInt(32.W);val tag=UInt(32.W)}
class TextureLodBiasResult extends Bundle {val lod=UInt(32.W);val status=UInt(3.W);val tag=UInt(32.W)}
/** Implicit shader bias is clamped to the resolved implementation limit +/-16,
  * then added with one RNE FP32 operation before sampler min/max clamps. Zero
  * gradient lambda (-infinity) remains infinite with every permitted bias.
  * NaNs are rejected; finite/infinite bias inputs otherwise clamp normally.
  */
class TextureLodBiasUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureLodBiasRequest));val out=Decoupled(new TextureLodBiasResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,prepare,offer,waitResult,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureLodBiasRequest)
  private val result=Reg(new TextureLodBiasResult);private val arithmetic=Reg(new Fp32Request(8));private val status=RegInit(0.U(3.W));private val lod=Reg(UInt(32.W))
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val fma=Module(new Fp32FmaLane(8));fma.io.flush:=false.B
  fma.io.in.valid:=phase===State.offer;fma.io.in.bits:=arithmetic;fma.io.out.ready:=phase===State.waitResult
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U}
  switch(phase){
    is(State.idle){when(io.in.fire){assert(!fma.io.busy,"LOD bias admission abandoned arithmetic")
      request:=io.in.bits;status:=0.U;lod:=0.U;phase:=State.check}}
    is(State.check){when(stop){phase:=State.finish}
      .elsewhen(Fp32ViewportState.nan(request.lod) || Fp32ViewportState.nan(request.bias)){status:=5.U;phase:=State.finish}
      .otherwise{phase:=State.prepare}}
    is(State.prepare){
      val below=Mux(PrimitiveClipStage.less(request.bias,"hc1800000".U),"hc1800000".U,request.bias)
      val clamped=Mux(PrimitiveClipStage.less("h41800000".U,below),"h41800000".U,below)
      arithmetic:=0.U.asTypeOf(arithmetic);arithmetic.operation:=Fp32Operation.add;arithmetic.operandB:=request.lod;arithmetic.operandC:=clamped
      arithmetic.tag:=0.U;phase:=Mux(stop,State.finish,State.offer)
    }
    is(State.offer){when(fma.io.in.fire){phase:=State.waitResult}}
    is(State.waitResult){when(fma.io.out.fire){assert(fma.io.out.bits.tag===0.U);lod:=fma.io.out.bits.result
      phase:=State.finish}}
    is(State.finish){
      assert(!fma.io.busy,"LOD bias completion escaped accepted FMA drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(!stop){result.lod:=lod};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!fma.io.busy,"held LOD bias must retain drained arithmetic")}
}
