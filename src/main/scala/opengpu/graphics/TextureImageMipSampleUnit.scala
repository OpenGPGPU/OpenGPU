package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageMipSampleRequest extends Bundle {
  val sample=new TextureImageLevelSampleRequest
  /** Effective explicit or derivative-based lambda, before sampler clamps. */
  val lod=UInt(32.W)
}
class TextureImageMipSampleResult extends Bundle {
  val sample=new TextureImageLevelSampleResult
  val first=UInt(4.W);val second=UInt(4.W);val weight=UInt(32.W);val linear=Bool();val minifying=Bool()
  val uniqueTaps=UInt(5.W);val dataReadLines=UInt(6.W)
}
/** Select min/mag and one/two mip levels from an admitted immutable level
  * table. Reuse one selected-level owner and its FMA for mip interpolation.
  * Cube projection, layer rounding and lambda derivation belong to the parent.
  */
class TextureImageMipSampleUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageMipSampleRequest));val out=Decoupled(new TextureImageMipSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,clampLower,clampUpper,splitLod,fractionLod,selectLod,checkLevels,prepare,offer,waitSample,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageMipSampleRequest)
  private val selection=Reg(new TextureLodSelection);private val selected=Reg(new TextureImageLevelSampleRequest)
  private val second=RegInit(false.B);private val firstColor=Reg(Vec(4,UInt(32.W)))
  private val childResult=Reg(new TextureImageLevelSampleResult);private val result=Reg(new TextureImageMipSampleResult)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val taps=Reg(UInt(5.W));private val lines=Reg(UInt(6.W));private val started=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val image=request.sample.image;private val state=request.sample.sampler.state
  private val lower=Reg(UInt(32.W));private val clamped=Reg(UInt(32.W));private val parts=Reg(new TextureLodParts);private val fraction=Reg(UInt(32.W))
  private val candidate=TextureSampleSelection.select(clamped,state,image.baseLevel,image.lastLevel,parts.floor,fraction,true.B)
  private val useSecond=selection.first=/=selection.second && selection.weight(30,0).orR
  private val child=Module(new TextureImageLevelSampleUnit(config,p))
  child.io.in.valid:=phase===State.offer;child.io.in.bits:=selected;child.io.out.ready:=phase===State.waitSample
  child.io.cancel:=started && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  child.io.invalidate:=io.invalidate || stop || noFill
  io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U;source:=0.U;faultValid:=false.B}
  when(active && io.invalidate){noFill:=true.B}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!child.io.busy,"mip admission abandoned selected-level ownership")
      request:=io.in.bits;status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U
      second:=false.B;firstColor.foreach(_:=0.U);childResult:=0.U.asTypeOf(childResult);selection:=0.U.asTypeOf(selection)
      taps:=0.U;lines:=0.U;started:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      val admitted=image.validBinding && image.status===0.U && request.sample.sampler.validSampler && request.sample.sampler.status===0.U &&
        image.level===image.baseLevel && image.baseLevel<=image.lastLevel && image.selected.level===image.baseLevel &&
        (!TextureSamplerState.requiresMips(state) || image.mipChainChecked)
      when(stop){phase:=State.finish}.elsewhen(!admitted){status:=TextureImageResourceStatus.view.U;source:=3.U;phase:=State.finish}
        .elsewhen(Fp32ViewportState.nan(request.lod)){status:=TextureImageResourceStatus.coordinate.U;source:=4.U;phase:=State.finish}
        .otherwise{phase:=State.clampLower}
    }
    is(State.clampLower){lower:=TextureSampleSelection.lower(request.lod,state);phase:=Mux(stop,State.finish,State.clampUpper)}
    is(State.clampUpper){clamped:=TextureSampleSelection.upper(lower,state);phase:=Mux(stop,State.finish,State.splitLod)}
    is(State.splitLod){parts:=TextureSampleSelection.parts(clamped);phase:=Mux(stop,State.finish,State.fractionLod)}
    is(State.fractionLod){fraction:=TextureSampleSelection.fraction(parts);phase:=Mux(stop,State.finish,State.selectLod)}
    is(State.selectLod){selection:=candidate;phase:=Mux(stop,State.finish,State.checkLevels)}
    is(State.checkLevels){
      when(stop){phase:=State.finish}
        .elsewhen(!selection.valid || !image.levelMask(selection.first) ||
          (selection.first=/=selection.second && selection.weight(30,0).orR && !image.levelMask(selection.second))){
          status:=TextureImageFetchStatus.format.U;source:=3.U;phase:=State.finish
        }.otherwise{phase:=State.prepare}
    }
    is(State.prepare){
      val level=Mux(second,selection.second,selection.first)
      val handle=image.selected.handle+&(level-image.baseLevel)
      when(stop){phase:=State.finish}.elsewhen(handle(32)){
        status:=TextureImageResourceStatus.bounds.U;source:=3.U;phase:=State.finish
      }.otherwise{
        selected:=request.sample;selected.image.level:=level;selected.image.view:=image.levels(level)
        selected.image.selected.level:=level;selected.image.selected.handle:=handle(31,0)
        // The child needs one selected view; the parent retains the whole table.
        selected.image.levelMask:=0.U;selected.image.levels:=0.U.asTypeOf(selected.image.levels)
        selected.linear:=selection.linear;selected.blendPrevious:=second;selected.previous:=firstColor;selected.mipWeight:=selection.weight
        selected.applySwizzle:=request.sample.applySwizzle && (second || !useSecond);phase:=State.offer
      }
    }
    is(State.offer){when(child.io.in.fire){started:=true.B;phase:=State.waitSample}}
    is(State.waitSample){when(child.io.out.fire){
      started:=false.B;childResult:=child.io.out.bits;val code=child.io.out.bits.status
      taps:=taps+child.io.out.bits.uniqueTaps;lines:=lines+child.io.out.bits.dataReadLines
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code
        when(code=/=0.U){source:=child.io.out.bits.faultSource;faultValid:=child.io.out.bits.faultAddressValid;faultAddress:=child.io.out.bits.faultAddress}}
      when(!stop && code===0.U && !second && useSecond){firstColor:=child.io.out.bits.rgba;second:=true.B;phase:=State.prepare}
        .otherwise{phase:=State.finish}
    }}
    is(State.finish){
      assert(!child.io.busy && !io.memoryRequest.valid,"mip completion escaped selected-level drain")
      result:=0.U.asTypeOf(result);result.sample:=childResult;result.sample.tag:=request.sample.tag
      result.sample.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.sample.faultSource:=source;result.sample.faultAddressValid:=faultValid;result.sample.faultAddress:=Mux(faultValid,faultAddress,0.U)
      result.first:=selection.first;result.second:=selection.second;result.weight:=selection.weight
      result.linear:=selection.linear;result.minifying:=selection.minifying;result.uniqueTaps:=taps;result.dataReadLines:=lines
      when(stop){result.sample.rgba.foreach(_:=0.U);result.sample.colorType:=0.U};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!child.io.busy && !io.memoryRequest.valid,"held mip result must retain drained children")}
}
