package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageLevelSampleRequest extends Bundle {
  val image=new TextureImageBindingResult;val sampler=new TextureSamplerBindingResult
  val coordinates=Vec(3,UInt(32.W));val offsets=Vec(3,SInt(5.W));val layer=UInt(32.W);val face=UInt(32.W)
  val linear=Bool();val shadow=Bool();val reference=UInt(32.W)
  /** False defers swizzle until a parent finishes mip interpolation. */
  val applySwizzle=Bool();val tag=UInt(32.W)
  /** A mip parent may supply its first level. Reuse this owner's filter after
    * sampling the second level, then perform the final swizzle once.
    */
  val blendPrevious=Bool();val previous=Vec(4,UInt(32.W));val mipWeight=UInt(32.W)
  /** Gather selects one component from each of the four 2x2 footprint taps;
    * each tap carries its own texel offset. */
  /** 0 textureGather,1 textureGatherOffset,2 textureGatherOffsets. */
  val gatherMode=UInt(2.W)
  val gather=Bool();val gatherComponent=UInt(2.W);val gatherOffsets=Vec(4,Vec(3,SInt(5.W)))
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
class TextureImageLevelSampleUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig(),groupedTexture: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val laneTemplate=if(groupedTexture)Some(Valid(new NativeRgba8TextureLaneTemplate))else None
    val in=Flipped(Decoupled(new TextureImageLevelSampleRequest));val out=Decoupled(new TextureImageLevelSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,axes,plan,offerFetch,waitFetch,nextTap,offerFilter,waitFilter,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageLevelSampleRequest)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val result=Reg(new TextureImageLevelSampleResult);private val noFill=RegInit(false.B)
  private val axisIssued=Reg(UInt(4.W));private val axisReceived=Reg(UInt(4.W));private val axes=Reg(Vec(8,new TextureFloatAxisResult))
  private val axisTarget=Mux(request.gather,8.U,3.U)
  private val tap=Reg(UInt(3.W));private val validTap=RegInit(VecInit(Seq.fill(8)(false.B)))
  private val locations=Reg(Vec(8,new TextureSampleTapLocation));private val colors=Reg(Vec(8,Vec(4,UInt(32.W))))
  private val planned=Reg(new TextureImageTypedFetchRequest);private val plannedLocation=Reg(new TextureSampleTapLocation)
  private val filtered=Reg(Vec(4,UInt(32.W)));private val uniqueTaps=Reg(UInt(4.W));private val readLines=Reg(UInt(5.W))
  private val blending=RegInit(false.B)
  private val groupEnabled=if(groupedTexture)Some(RegInit(false.B))else None
  private val groupStarted=if(groupedTexture)Some(RegInit(false.B))else None
  private val fetchStarted=RegInit(false.B);private val filterStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val view=request.image.view;private val volume=view.target===TextureImageTarget.image3D.U
  private val cube=view.target===TextureImageTarget.cube.U || view.target===TextureImageTarget.cubeArray.U
  private val array=view.target===TextureImageTarget.array2D.U || view.target===TextureImageTarget.cubeArray.U
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
  filter.io.in.bits.weights:=VecInit(axes.take(3).map(_.weight));filter.io.out.ready:=phase===State.waitFilter
  when(blending){filter.io.in.bits.values:=0.U.asTypeOf(filter.io.in.bits.values)
    filter.io.in.bits.values(0):=request.previous;filter.io.in.bits.values(1):=filtered
    filter.io.in.bits.axes:=1.U;filter.io.in.bits.weights:=VecInit(Seq(request.mipWeight,0.U(32.W),0.U(32.W)))}
  filter.io.cancel:=filterStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  private val gatherWhich=axisIssued(0);private val gatherTap=axisIssued(2,1)
  coordinate.io.in.valid:=phase===State.axes && axisIssued<axisTarget
  coordinate.io.in.bits.coordinate:=Mux(request.gather,
    Mux(gatherWhich===1.U,request.coordinates(1),request.coordinates(0)),
    Mux(axisIssued===2.U && !volume,0.U,MuxLookup(axisIssued,0.U)(
      Seq(0.U->request.coordinates(0),1.U->request.coordinates(1),2.U->request.coordinates(2)))))
  coordinate.io.in.bits.extent:=Mux(request.gather,Mux(gatherWhich===1.U,view.texHeight,view.texWidth),
    MuxLookup(axisIssued,1.U)(Seq(0.U->view.texWidth,1.U->view.texHeight,2.U->Mux(volume,view.texDepth,1.U))))
  coordinate.io.in.bits.wrap:=Mux(request.gather,Mux(gatherWhich===1.U,request.sampler.state.wrapT,request.sampler.state.wrapS),
    Mux(cube,Mux(request.linear,TextureFloatAxisWrap.cubeBorder.U,TextureFloatAxisWrap.clamp.U),
      MuxLookup(axisIssued,request.sampler.state.wrapR)(Seq(0.U->request.sampler.state.wrapS,1.U->request.sampler.state.wrapT))))
  private val gatherOffsets=VecInit((0 until 4).map(i=>
    Mux(gatherWhich===1.U,request.gatherOffsets(i)(1),request.gatherOffsets(i)(0))))
  private val gatherOffset=MuxLookup(request.gatherMode,0.S(5.W))(Seq(
    1.U->Mux(gatherWhich===1.U,request.gatherOffsets(0)(1),request.gatherOffsets(0)(0)),
    2.U->gatherOffsets(gatherTap)))
  coordinate.io.in.bits.offset:=Mux(request.gather,gatherOffset,
    Mux(axisIssued===2.U && !volume,0.S,MuxLookup(axisIssued,0.S)(
      Seq(0.U->request.offsets(0),1.U->request.offsets(1),2.U->request.offsets(2)))))
  coordinate.io.in.bits.linear:=Mux(request.gather,true.B,request.linear && (axisIssued=/=2.U || volume))
  coordinate.io.in.bits.tag:=axisIssued
  coordinate.io.out.ready:=phase===State.axes
  if(groupedTexture){
    val client=io.grouped.get
    val previousInvalidate=RegNext(io.invalidate,false.B)
    coordinate.io.in.valid:=phase===State.axes && axisIssued<axisTarget && !groupEnabled.get
    client.request.valid:=phase===State.axes && groupEnabled.get
    client.request.bits:=TextureRgba8GroupRequest.capture(request)
    client.response.ready:=phase===State.plan && groupEnabled.get
    client.cancelGroup.valid:=groupStarted.get && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
    client.cancelGroup.bits.identity:=request.image.identity;client.cancelGroup.bits.tag:=request.tag
    client.invalidate:=(io.invalidate && !previousInvalidate) ||
      (client.response.fire && (noFill || io.invalidate))
    val template=io.laneTemplate.get
    template.valid:=client.request.fire && view.target===TextureImageTarget.image2D.U &&
      !request.shadow && !request.blendPrevious && request.applySwizzle &&
      request.sampler.state.minFilter<=1.U && request.sampler.state.minFilter===request.sampler.state.magFilter &&
      view.base(1,0)===0.U && view.rowPitch(1,0)===0.U
    template.bits.group:=client.request.bits;template.bits.swizzle:=request.image.swizzle
    template.bits.lodIndependent:=request.image.baseLevel===request.image.lastLevel
    when(client.request.fire){groupStarted.get:=true.B;phase:=State.plan}
  }
  when(coordinate.io.in.fire){axisIssued:=axisIssued+1.U}
  when(active && io.invalidate){noFill:=true.B}
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U;source:=0.U;faultValid:=false.B}
  private def fail(code: UInt,origin: Int): Unit={status:=code;source:=origin.U;phase:=State.finish}
  private val firsts=VecInit(axes.take(3).map(_.first));private val seconds=VecInit(axes.take(3).map(_.second))
  private val xyz=VecInit((0 until 3).map(i=>Mux(tap(i),seconds(i),firsts(i))))
  private val gatherFirstX=axes((tap<<1)(2,0)).first
  private val gatherFirstY=axes(((tap<<1)+1.U)(2,0)).first
  private val gatherX=Mux(request.gatherMode===2.U,gatherFirstX,Mux(tap(0),axes((tap<<1)(2,0)).second,gatherFirstX))
  private val gatherY=Mux(request.gatherMode===2.U,gatherFirstY,Mux(tap(1),axes(((tap<<1)+1.U)(2,0)).second,gatherFirstY))
  private val gatherOutsideX=request.sampler.state.wrapS===TextureSamplerState.clampToBorder.U &&
    (gatherX<0.S || gatherX>=view.texWidth.pad(16).asSInt)
  private val gatherOutsideY=request.sampler.state.wrapT===TextureSamplerState.clampToBorder.U &&
    (gatherY<0.S || gatherY>=view.texHeight.pad(16).asSInt)
  private val axisExtent=VecInit(view.texWidth,view.texHeight,Mux(volume,view.texDepth,1.U))
  private val axisBorder=VecInit(request.sampler.state.wrapS===TextureSamplerState.clampToBorder.U,
    request.sampler.state.wrapT===TextureSamplerState.clampToBorder.U,
    request.sampler.state.wrapR===TextureSamplerState.clampToBorder.U)
  private val axisOutside=(0 until 3).map{i=>
    val used=if(i==2)volume else true.B
    used && axisBorder(i) && (xyz(i)<0.S || xyz(i)>=axisExtent(i).pad(16).asSInt)
  }
  // Seamless cube edges remap to a real face tap, so only non-cube sampling
  // substitutes the border texel.
  private val borderTap= !cube && axisOutside.reduce(_ || _)
  private def clampUnit(value: UInt): UInt=
    Mux(Fp32ViewportState.nan(value) || (value(31) && value(30,0).orR),0.U,
      Mux(value>"h3f800000".U,"h3f800000".U,value))
  private def clampSigned(value: UInt): UInt=
    Mux(Fp32ViewportState.nan(value),0.U,
      Mux(value(31),Mux(value>"hbf800000".U,"hbf800000".U,value),Mux(value>"h3f800000".U,"h3f800000".U,value)))
  /** Exact binary32 of a signed-normalized byte, matching texel decode: the
    * 7-bit pattern repeats and normalizes with one guard bit.
    */
  private def snormDecode(raw: UInt): UInt={
    val magnitude=Mux(raw(7),(0.U(8.W)-raw(7,0))(7,0),raw(7,0))
    val m=Mux(magnitude>127.U,127.U,magnitude)
    val repeated=Fill(12,m(6,0))(83,4)
    val leading=PriorityEncoder(Reverse(repeated))
    val shifted=(repeated<<leading)(79,0)
    val mantissa=shifted(79,56);val guard=shifted(55);val rounded=mantissa+&guard
    Mux(m.orR,Cat(raw(7),((126.U-leading)+&rounded(24))(7,0),rounded(22,0)),0.U)
  }
  /** Nearest sRGB byte via the eight-step threshold search used for stores;
    * sRGB formats encode RGB and keep alpha linear.
    */
  private def srgbEncode(raw: UInt): UInt={
    val normalized=Mux(raw(31) || Fp32ViewportState.nan(raw),0.U,Mux(raw>"h3f800000".U,"h3f800000".U,raw))
    var prefix=0.U(8.W)
    for(digit<-7 to 0 by -1){
      val candidate=prefix|(1.U(8.W)<<digit.U)
      val threshold=Srgb8Thresholds.values((candidate-1.U)(7,0))
      prefix=Mux(normalized>=threshold,candidate,prefix)
    }
    prefix
  }
  private def srgbValue(f: UInt,c: Int): UInt=
    Mux(c.U<3.U,Srgb8Decode.values(srgbEncode(f)),clampUnit(f))
  private def borderFloatValue(c: Int,safeBits: UInt): UInt={
    val f=request.sampler.borderFloat(c)
    val encoding=TextureImageFormat.encoding(request.image.format)
    MuxLookup(encoding,f)(Seq(
      // Widths above ten occur only in depth encodings, whose border color is
      // clamped rather than quantized.
      0.U->Mux(safeBits<=10.U,TypedColorDecode.unorm(TypedColorFormat.unorm(f,safeBits),safeBits),clampUnit(f)),
      3.U->TypedColorDecode.half(TypedColorFormat.half(f)(15,0)),
      5.U->srgbValue(f,c),6.U->snormDecode(ShaderImageFormat.snorm8(f)),
      9.U->clampUnit(f),
      11.U->TypedColorDecode.unorm(TypedColorFormat.unorm(f,8.U),8.U),
      12.U->srgbValue(f,c),13.U->clampSigned(f),14.U->clampSigned(f)))
  }
  private def borderComponent(c: Int): UInt={
    val bits=TextureImageFormat.componentWidth(request.image.format,c.U)
    val safeBits=Mux(bits===0.U,1.U,bits)
    val kind=TextureImageFormat.kind(request.image.format)
    val converted=Mux(kind===0.U,borderFloatValue(c,safeBits),
      TypedColorFormat.integer(request.sampler.borderInt(c),safeBits,kind===2.U))
    // Absent components keep the GL default (0,0,0,1), as texel decode does.
    Mux(bits===0.U,Mux(c.U===3.U,Mux(kind===0.U,"h3f800000".U,1.U),0.U),converted)
  }
  private val borderColor=VecInit((0 until 4).map(borderComponent))
  private val borderFixed=TextureImageFormat.encoding(request.image.format)===9.U
  private val borderReference=Mux(borderFixed,
    Mux(Fp32ViewportState.nan(request.reference),request.reference,Fp32ViewportState.clampDepth(request.reference)),
    request.reference)
  private val borderCompared=TextureDepthComparison(borderReference,borderColor(0),request.sampler.state.compareFunction)
  private val borderShadowColor=VecInit(Seq(Mux(borderCompared,"h3f800000".U,0.U(32.W)),0.U(32.W),0.U(32.W),"h3f800000".U(32.W)))
  private val cubeTap=TextureCubeTap(request.face(2,0),xyz(0),xyz(1),view.texWidth)
  private val location=Wire(new TextureSampleTapLocation)
  location.x:=Mux(request.gather,gatherX.asUInt,Mux(cube,cubeTap.x,xyz(0).asUInt))
  location.y:=Mux(request.gather,gatherY.asUInt,Mux(cube,cubeTap.y,xyz(1).asUInt))
  location.z:=Mux(request.gather,0.U,Mux(volume,xyz(2).asUInt,0.U))
  location.face:=Mux(request.gather,0.U,Mux(cube,cubeTap.face,0.U))
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
      blending:=false.B
      groupEnabled.foreach(_:=false.B);groupStarted.foreach(_:=false.B)
    }}
    is(State.check){
      val admitted=request.image.validBinding && request.image.status===0.U && request.sampler.validSampler && request.sampler.status===0.U &&
        request.image.identity.asUInt===request.sampler.identity.asUInt && request.image.identity.asUInt===request.image.selected.table.identity.asUInt &&
        request.image.level===request.image.selected.level && view.level===request.image.selected.level
      val compatible=TextureSamplerState.legal(request.sampler.state) && TextureSamplerState.compatibleSampling(request.sampler.state,request.image.format,
        TextureImageFormat.kind(request.image.format),request.shadow) && TextureImageSwizzle.legal(request.image.swizzle) &&
        TextureImageFormat.targetLegal(request.image.format,view.target) && view.block4x4===TextureImageFormat.block4x4(request.image.format) &&
        view.blockBytes===TextureImageFormat.bytes(request.image.format) && ((!request.linear && !request.blendPrevious) || request.shadow || TextureImageFormat.filterable(request.image.format)) &&
        (!request.blendPrevious || (!request.mipWeight(31) && request.mipWeight>0.U && request.mipWeight<"h3f800000".U))
      val offsetsLegal=request.offsets.map(o=>o>=(-8).S && o<=7.S).reduce(_ && _) &&
        (!cube || request.offsets.map(_===0.S).reduce(_ && _)) && (volume || request.offsets(2)===0.S)
      val selected= Mux(array,request.layer<view.layers,request.layer===0.U) && Mux(cube,request.face<6.U,request.face===0.U)
      val gatherLegal= !request.gather || (!cube && !volume && !request.blendPrevious &&
        view.target<=TextureImageTarget.array2D.U)
      val gatherOffsetsLegal= !request.gather || (0 until 4).flatMap(i=>(0 until 3).map(j=>request.gatherOffsets(i)(j)))
        .map(o=>o>=(-8).S && o<=7.S).reduce(_ && _)
      def offsetsZero(rows: Seq[Seq[SInt]]): Bool=rows.flatten.map(_===0.S).reduce(_ && _)
      val gatherModeLegal= !request.gather || (request.gatherMode<=2.U &&
        (0 until 4).map(i=>request.gatherOffsets(i)(2)===0.S).reduce(_ && _) &&
        Mux(request.gatherMode===0.U,offsetsZero((0 until 4).map(i=>request.gatherOffsets(i))),
          Mux(request.gatherMode===1.U,offsetsZero((1 until 4).map(i=>request.gatherOffsets(i))),true.B)))
      when(stop){phase:=State.finish}.elsewhen(!admitted){fail(TextureImageResourceStatus.view.U,3)}
        .elsewhen(!gatherLegal){fail(TextureImageResourceStatus.coordinate.U,4)}
        .elsewhen(!gatherOffsetsLegal){fail(TextureImageResourceStatus.coordinate.U,4)}
        .elsewhen(!gatherModeLegal){fail(TextureImageResourceStatus.coordinate.U,4)}
        .elsewhen(view.target===TextureImageTarget.image2DMS.U || view.target===TextureImageTarget.array2DMS.U){
          fail(TextureImageResourceStatus.coordinate.U,4)}
        .elsewhen(!compatible){fail(TextureImageFetchStatus.format.U,3)}
        .elsewhen(!offsetsLegal){fail(TextureImageResourceStatus.view.U,3)}
        .elsewhen(!selected){fail(TextureImageResourceStatus.coordinate.U,4)}.otherwise{
          phase:=State.axes
          groupEnabled.foreach(_:=request.image.format===TextureImageFormat.id("RGBA8").U &&
            !request.shadow && view.target<=TextureImageTarget.cube.U && !noFill && !io.invalidate &&
            request.sampler.state.wrapS=/=TextureSamplerState.clampToBorder.U &&
            request.sampler.state.wrapT=/=TextureSamplerState.clampToBorder.U && !request.gather)
        }
    }
    is(State.axes){when(coordinate.io.out.fire){
      assert(coordinate.io.out.bits.tag<axisTarget,"sample received an unowned axis tag")
      axes(coordinate.io.out.bits.tag(2,0)):=coordinate.io.out.bits;axisReceived:=axisReceived+1.U
      val code=coordinate.io.out.bits.status
      when(code=/=0.U && status===0.U && !io.cancel){status:=code;source:=4.U}
      when(axisReceived+1.U===axisTarget){assert(axisIssued===axisTarget)
        phase:=Mux(stop || code=/=0.U,State.finish,State.plan)}
    }}
    is(State.plan){
      when(stop){phase:=State.finish}
        .elsewhen(request.gather && (gatherOutsideX || gatherOutsideY)){
          colors(tap):=Mux(request.shadow,borderShadowColor,borderColor)
          locations(tap):=location;validTap(tap):=true.B;phase:=State.nextTap
        }
        .elsewhen(duplicate.orR){colors(tap):=colors(PriorityEncoder(duplicate));locations(tap):=location;validTap(tap):=true.B;phase:=State.nextTap}
        .elsewhen(request.gather){
          planned:=0.U.asTypeOf(planned);planned.resource.lookup:=request.image.selected;planned.resource.tag:=request.tag
          planned.resource.x:=location.x;planned.resource.y:=location.y;planned.resource.z:=0.U
          planned.resource.layer:=request.layer;planned.resource.face:=0.U;planned.resource.sample:=0.U;planned.format:=request.image.format
          plannedLocation:=location;phase:=State.offerFetch
        }
        .elsewhen(request.linear && !liveWeight){phase:=State.nextTap}
        .elsewhen(borderTap){
          // Clamp-to-border returns the sampler's border color converted to
          // the texture's component type; the default is transparent black.
          // No memory transaction is issued for the outside tap.
          colors(tap):=Mux(request.shadow,borderShadowColor,borderColor)
          locations(tap):=location;validTap(tap):=true.B;phase:=State.nextTap
        }
        .otherwise{
          planned:=0.U.asTypeOf(planned);planned.resource.lookup:=request.image.selected;planned.resource.tag:=request.tag
          planned.resource.x:=location.x;planned.resource.y:=location.y;planned.resource.z:=location.z
          planned.resource.layer:=request.layer;planned.resource.face:=location.face;planned.resource.sample:=0.U;planned.format:=request.image.format
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

      val last=Mux(request.gather,3.U,Mux(request.linear,Mux(volume,7.U,3.U),0.U))
      when(stop){phase:=State.finish}.elsewhen(tap===last){phase:=State.offerFilter}.otherwise{tap:=tap+1.U;phase:=State.plan}
    }
    is(State.offerFilter){when(filter.io.in.fire){filterStarted:=true.B;phase:=State.waitFilter}}
    is(State.waitFilter){when(filter.io.out.fire){
      filterStarted:=false.B;assert(filter.io.out.bits.tag===request.tag);val code=filter.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code;when(code=/=0.U){source:=3.U}}
      when(!stop && code===0.U){filtered:=filter.io.out.bits.rgba}
      when(!stop && code===0.U && request.blendPrevious && !blending){blending:=true.B;phase:=State.offerFilter}.otherwise{phase:=State.finish}
    }}
    is(State.finish){
      assert(!fetch.io.busy && !filter.io.busy && !coordinate.io.out.valid,"sample completion escaped coordinate/fetch/filter drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.uniqueTaps:=uniqueTaps;result.dataReadLines:=readLines
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.faultSource:=source;result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){val kind=Mux(request.shadow,0.U,TextureImageFormat.kind(request.image.format));result.colorType:=kind
        // textureGather/GatherOffset order i0j1, i1j1, i1j0, i0j0.
        // textureGatherOffsets returns i0j0 of each offset footprint in
        // offsets order.
        val gatherComponent=Mux(request.shadow,0.U,request.gatherComponent)
        val order=Mux(request.gatherMode===2.U,VecInit(0.U,1.U,2.U,3.U),VecInit(2.U,3.U,1.U,0.U))
        val gathered=VecInit(order.map(i=>colors(i)(gatherComponent)))
        result.rgba:=Mux(request.gather,gathered,
          Mux(request.applySwizzle,TextureImageSwizzle(filtered,kind,request.image.swizzle),filtered))}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  if(groupedTexture){
    // Reuse the old axes/plan states. The default profile retains exactly its
    // original state encoding and logic. A held shared offer must be accepted
    // and drained even after local cancellation.
    when(phase===State.plan && groupEnabled.get){
      phase:=State.plan
      when(io.grouped.get.response.fire){
        val sampled=io.grouped.get.response.bits;val code=sampled.status
        assert(groupStarted.get && sampled.tag===request.tag && sampled.identity.asUInt===request.image.identity.asUInt)
        groupStarted.get:=false.B
        readLines:=readLines+sampled.dataReadLines;uniqueTaps:=sampled.uniqueTaps
        when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){
          status:=code
          when(code=/=0.U){source:=sampled.faultSource;faultValid:=sampled.faultAddressValid;faultAddress:=sampled.faultAddress}
        }
        when(!stop && code===0.U && sampled.fallback){
          groupEnabled.get:=false.B;axisIssued:=0.U;axisReceived:=0.U;phase:=State.axes
        }.elsewhen(!stop && code===0.U){
          filtered:=sampled.rgba
          when(request.blendPrevious){blending:=true.B;phase:=State.offerFilter}.otherwise{phase:=State.finish}
        }.otherwise{phase:=State.finish}
      }
    }
    when(phase===State.finish || io.out.valid){assert(!groupStarted.get,"native sample retired before shared group drain")}
  }
  when(io.out.valid){assert(!fetch.io.busy && !filter.io.busy && !coordinate.io.out.valid && !io.memoryRequest.valid,"held sample must retain drained children")}
}
