package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageLevelSampleRequest extends Bundle {
  val image=new TextureImageBindingResult;val sampler=new TextureSamplerBindingResult
  val coordinates=Vec(3,UInt(32.W));val layer=UInt(32.W);val face=UInt(32.W)
  val linear=Bool();val shadow=Bool();val reference=UInt(32.W)
  /** False defers swizzle until a parent finishes mip interpolation. */
  val applySwizzle=Bool();val tag=UInt(32.W)
}
class TextureImageLevelSampleResult extends Bundle {
  val rgba=Vec(4,UInt(32.W));val colorType=UInt(2.W);val tag=UInt(32.W)
  val status=UInt(3.W);val faultSource=UInt(3.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
  val uniqueTaps=UInt(4.W);val dataReadLines=UInt(5.W)
}
private class TextureSampleTapLocation extends Bundle {
  val x=UInt(14.W);val y=UInt(14.W);val z=UInt(14.W);val face=UInt(3.W)
}
object TextureDepthComparison {
  /** GXS1 order: never,less,equal,lequal,greater,notequal,gequal,always. */
  def apply(reference: UInt,depth: UInt,function: UInt): Bool={
    val ordered= !Fp32ViewportState.nan(reference) && !Fp32ViewportState.nan(depth)
    val equal=ordered && PrimitiveClipStage.equal(reference,depth)
    val less=ordered && PrimitiveClipStage.less(reference,depth)
    val greater=ordered && PrimitiveClipStage.less(depth,reference)
    MuxLookup(function,false.B)(Seq(0.U->false.B,1.U->less,2.U->equal,3.U->(less||equal),
      4.U->greater,5.U-> !equal,6.U->(greater||equal),7.U->true.B))
  }
}

/** Sample one admitted mip level using normalized FP32 coordinates. The parent
  * selects the mip/filter, rounds array layers and projects cube directions.
  * This owner captures GXI1/GXS1 state, maps seamless cube edge taps, fetches
  * only nonzero-weight unique texels, decodes before filtering, compares depth
  * before filtering, and optionally applies the final captured image swizzle.
  * Every accepted coordinate, data and arithmetic operation drains on cancel.
  */
class TextureImageLevelSampleUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageLevelSampleRequest));val out=Decoupled(new TextureImageLevelSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,axes,plan,offerFetch,waitFetch,nextTap,offerFilter,waitFilter,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageLevelSampleRequest)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val result=Reg(new TextureImageLevelSampleResult);private val noFill=RegInit(false.B)
  private val axisIssued=Reg(UInt(2.W));private val axisReceived=Reg(UInt(2.W));private val axes=Reg(Vec(3,new TextureFloatAxisResult))
  private val tap=Reg(UInt(3.W));private val validTap=RegInit(VecInit(Seq.fill(8)(false.B)))
  private val locations=Reg(Vec(8,new TextureSampleTapLocation));private val colors=Reg(Vec(8,Vec(4,UInt(32.W))))
  private val planned=Reg(new TextureImageTypedFetchRequest);private val plannedLocation=Reg(new TextureSampleTapLocation)
  private val filtered=Reg(Vec(4,UInt(32.W)));private val uniqueTaps=Reg(UInt(4.W));private val readLines=Reg(UInt(5.W))
  private val fetchStarted=RegInit(false.B);private val filterStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val view=request.image.view;private val volume=view.target===TextureImageTarget.image3D.U
  private val cube=view.target===TextureImageTarget.cube.U;private val array=view.target===TextureImageTarget.array2D.U
  private val coordinate=Module(new TextureFloatAxisPipeline)
  private val fetch=Module(new TextureImageTypedFetchUnit(config,p,capturedViewInput=true))
  private val filter=Module(new TextureLinearFilterUnit(3))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest<>fetch.io.memoryRequest;fetch.io.memoryResponse<>io.memoryResponse
  fetch.io.in.valid:=phase===State.offerFetch;fetch.io.in.bits:=planned;fetch.io.capturedView.get:=view
  fetch.io.out.ready:=phase===State.waitFetch;fetch.io.invalidate:=io.invalidate || stop || noFill
  fetch.io.cancel:=fetchStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  filter.io.in.valid:=phase===State.offerFilter;filter.io.in.bits.values:=colors
  filter.io.in.bits.axes:=Mux(request.linear,Mux(volume,3.U,2.U),0.U);filter.io.in.bits.tag:=request.tag
  filter.io.in.bits.weights:=VecInit(axes.map(_.weight));filter.io.out.ready:=phase===State.waitFilter
  filter.io.cancel:=filterStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  coordinate.io.in.valid:=phase===State.axes && axisIssued<3.U
  coordinate.io.in.bits.coordinate:=Mux(axisIssued===2.U && !volume,0.U,request.coordinates(axisIssued))
  coordinate.io.in.bits.extent:=MuxLookup(axisIssued,1.U)(Seq(0.U->view.texWidth,1.U->view.texHeight,2.U->Mux(volume,view.texDepth,1.U)))
  coordinate.io.in.bits.wrap:=Mux(cube,Mux(request.linear,TextureFloatAxisWrap.cubeBorder.U,TextureFloatAxisWrap.clamp.U),
    MuxLookup(axisIssued,request.sampler.state.wrapR)(Seq(0.U->request.sampler.state.wrapS,1.U->request.sampler.state.wrapT)))
  coordinate.io.in.bits.linear:=request.linear && (axisIssued=/=2.U || volume);coordinate.io.in.bits.tag:=axisIssued
  coordinate.io.out.ready:=phase===State.axes
  when(coordinate.io.in.fire){axisIssued:=axisIssued+1.U}
  when(active && io.invalidate){noFill:=true.B}
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U;source:=0.U;faultValid:=false.B}
  private def fail(code: UInt,origin: Int): Unit={status:=code;source:=origin.U;phase:=State.finish}
  private val firsts=VecInit(axes.map(_.first));private val seconds=VecInit(axes.map(_.second))
  private val xyz=VecInit((0 until 3).map(i=>Mux(tap(i),seconds(i),firsts(i))))
  private val cubeTap=TextureCubeTap(request.face(2,0),xyz(0),xyz(1),view.texWidth)
  private val location=Wire(new TextureSampleTapLocation)
  location.x:=Mux(cube,cubeTap.x,xyz(0).asUInt);location.y:=Mux(cube,cubeTap.y,xyz(1).asUInt)
  location.z:=Mux(volume,xyz(2).asUInt,0.U);location.face:=Mux(cube,cubeTap.face,0.U)
  private val liveWeight=(0 until 3).map{i=>
    val used=if(i==2)volume else true.B
    !used || Mux(axes(i).weight(30,0)===0.U,!tap(i),Mux(axes(i).weight==="h3f800000".U,tap(i),true.B))
  }.reduce(_ && _)
  private val duplicate=VecInit((0 until 8).map(i=>i.U<tap && validTap(i) && locations(i).asUInt===location.asUInt)).asUInt
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!fetch.io.busy && !filter.io.busy && !coordinate.io.out.valid,"sample admission abandoned child ownership")
      request:=io.in.bits;status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;noFill:=io.invalidate
      axisIssued:=0.U;axisReceived:=0.U;tap:=0.U;validTap.foreach(_:=false.B);colors.foreach(_.foreach(_:=0.U));filtered.foreach(_:=0.U)
      uniqueTaps:=0.U;readLines:=0.U;fetchStarted:=false.B;filterStarted:=false.B;phase:=State.check
    }}
    is(State.check){
      val admitted=request.image.validBinding && request.image.status===0.U && request.sampler.validSampler && request.sampler.status===0.U &&
        request.image.identity.asUInt===request.sampler.identity.asUInt && request.image.identity.asUInt===request.image.selected.table.identity.asUInt &&
        request.image.level===request.image.selected.level && view.level===request.image.selected.level
      val compatible=TextureSamplerState.legal(request.sampler.state) && TextureSamplerState.compatibleSampling(request.sampler.state,request.image.format,
        TextureImageFormat.kind(request.image.format),request.shadow) && TextureImageSwizzle.legal(request.image.swizzle) &&
        TextureImageFormat.targetLegal(request.image.format,view.target) && view.block4x4===TextureImageFormat.block4x4(request.image.format) &&
        view.blockBytes===TextureImageFormat.bytes(request.image.format) && (!request.linear || request.shadow || TextureImageFormat.filterable(request.image.format))
      val selected= Mux(array,request.layer<view.layers,request.layer===0.U) && Mux(cube,request.face<6.U,request.face===0.U)
      when(stop){phase:=State.finish}.elsewhen(!admitted){fail(TextureImageResourceStatus.view.U,3)}
        .elsewhen(!compatible){fail(TextureImageFetchStatus.format.U,3)}
        .elsewhen(!selected){fail(TextureImageResourceStatus.coordinate.U,4)}.otherwise{phase:=State.axes}
    }
    is(State.axes){when(coordinate.io.out.fire){
      assert(coordinate.io.out.bits.tag<3.U,"sample received an unowned axis tag")
      axes(coordinate.io.out.bits.tag(1,0)):=coordinate.io.out.bits;axisReceived:=axisReceived+1.U
      val code=coordinate.io.out.bits.status
      when(code=/=0.U && status===0.U && !io.cancel){status:=code;source:=4.U}
      when(axisReceived===2.U){assert(axisIssued===3.U);phase:=Mux(stop || code=/=0.U,State.finish,State.plan)}
    }}
    is(State.plan){
      when(stop){phase:=State.finish}.elsewhen(request.linear && !liveWeight){phase:=State.nextTap}
        .elsewhen(duplicate.orR){colors(tap):=colors(PriorityEncoder(duplicate));locations(tap):=location;validTap(tap):=true.B;phase:=State.nextTap}
        .otherwise{
          planned:=0.U.asTypeOf(planned);planned.resource.lookup:=request.image.selected;planned.resource.tag:=request.tag
          planned.resource.x:=location.x;planned.resource.y:=location.y;planned.resource.z:=location.z
          planned.resource.layer:=request.layer;planned.resource.face:=location.face;planned.format:=request.image.format
          plannedLocation:=location;phase:=State.offerFetch
        }
    }
    is(State.offerFetch){when(fetch.io.in.fire){fetchStarted:=true.B;uniqueTaps:=uniqueTaps+1.U;phase:=State.waitFetch}}
    is(State.waitFetch){when(fetch.io.out.fire){
      fetchStarted:=false.B;readLines:=readLines+fetch.io.out.bits.sample.dataReadLines;val sample=fetch.io.out.bits;val code=sample.sample.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){
        status:=code
        when(code=/=0.U){source:=sample.sample.faultSource;faultValid:=sample.sample.faultAddressValid;faultAddress:=sample.sample.faultAddress}
      }
      when(!stop && code===0.U){
        val fixed=TextureImageFormat.encoding(request.image.format)===9.U
        val clampedRef=Mux(Fp32ViewportState.nan(request.reference),request.reference,Fp32ViewportState.clampDepth(request.reference))
        val ref=Mux(fixed,clampedRef,request.reference)
        val depth=Mux(fixed,Fp32ViewportState.clampDepth(sample.rgba(0)),sample.rgba(0))
        val compared=TextureDepthComparison(ref,depth,request.sampler.state.compareFunction)
        colors(tap):=Mux(request.shadow,VecInit(Seq(Mux(compared,"h3f800000".U,0.U),0.U(32.W),0.U(32.W),"h3f800000".U(32.W))),sample.rgba)
        locations(tap):=plannedLocation;validTap(tap):=true.B
      }
      phase:=Mux(stop || code=/=0.U,State.finish,State.nextTap)
    }}
    is(State.nextTap){
      val last=Mux(request.linear,Mux(volume,7.U,3.U),0.U)
      when(stop){phase:=State.finish}.elsewhen(tap===last){phase:=State.offerFilter}.otherwise{tap:=tap+1.U;phase:=State.plan}
    }
    is(State.offerFilter){when(filter.io.in.fire){filterStarted:=true.B;phase:=State.waitFilter}}
    is(State.waitFilter){when(filter.io.out.fire){
      filterStarted:=false.B;assert(filter.io.out.bits.tag===request.tag);val code=filter.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code;when(code=/=0.U){source:=3.U}}
      when(!stop && code===0.U){filtered:=filter.io.out.bits.rgba};phase:=State.finish
    }}
    is(State.finish){
      assert(!fetch.io.busy && !filter.io.busy && !coordinate.io.out.valid,"sample completion escaped coordinate/fetch/filter drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.uniqueTaps:=uniqueTaps;result.dataReadLines:=readLines
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.faultSource:=source;result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){val kind=Mux(request.shadow,0.U,TextureImageFormat.kind(request.image.format));result.colorType:=kind
        result.rgba:=Mux(request.applySwizzle,TextureImageSwizzle(filtered,kind,request.image.swizzle),filtered)}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!fetch.io.busy && !filter.io.busy && !coordinate.io.out.valid && !io.memoryRequest.valid,"held sample must retain drained children")}
}
