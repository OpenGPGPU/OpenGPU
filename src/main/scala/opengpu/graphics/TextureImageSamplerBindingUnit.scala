package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageSamplerBindingRequest extends Bundle {
  val images=new TextureImageLevelTable;val samplers=new TextureImageLevelTable
  val imageHandle=UInt(32.W);val samplerHandle=UInt(32.W);val level=UInt(32.W);val tag=UInt(32.W)
  val expectedType=UInt(2.W)
  val selectBaseLevel=Bool()
}
object TextureImageSamplerFaultSource {val none=0;val sampler=1;val image=2;val compatibility=3}
class TextureImageSamplerBindingResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val imageHandle=UInt(32.W);val samplerHandle=UInt(32.W);val level=UInt(32.W);val tag=UInt(32.W)
  val status=UInt(3.W);val faultSource=UInt(2.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
  val validBinding=Bool();val sampler=new TextureSamplerBindingResult;val image=new TextureImageBindingResult
}
/** Compose independent GXS1/GXI1 admissions. The sampler alone determines
  * mip-chain completeness; callers cannot weaken it. Compatibility is checked
  * before publication. This owner issues descriptor reads only, never texels.
  * Image tags0..3 and sampler tag4 are disjoint through the shared VM port.
  */
class TextureImageSamplerBindingUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageSamplerBindingRequest));val out=Decoupled(new TextureImageSamplerBindingResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,offerSampler,waitSampler,offerImage,waitImage,offerBase,waitBase,compatibility,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageSamplerBindingRequest)
  private val sampler=Module(new TextureSamplerBindingUnit(config));private val image=Module(new TextureImageBindingUnit(config,p))
  private val samplerResult=Reg(new TextureSamplerBindingResult);private val imageResult=Reg(new TextureImageBindingResult)
  private val result=Reg(new TextureImageSamplerBindingResult);private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(2.W))
  private val samplerStarted=RegInit(false.B);private val imageStarted=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  sampler.io.in.valid:=phase===State.offerSampler;sampler.io.in.bits.table:=request.samplers
  sampler.io.in.bits.handle:=request.samplerHandle;sampler.io.in.bits.tag:=request.tag;sampler.io.out.ready:=phase===State.waitSampler
  image.io.in.valid:=phase===State.offerImage || phase===State.offerBase;image.io.in.bits.table:=request.images
  image.io.in.bits.handle:=request.imageHandle;image.io.in.bits.level:=Mux(phase===State.offerBase,imageResult.baseLevel,request.level);image.io.in.bits.tag:=request.tag
  image.io.in.bits.requireMipChain:=TextureSamplerState.requiresMips(samplerResult.state);image.io.out.ready:=phase===State.waitImage || phase===State.waitBase
  image.io.in.bits.selectBaseLevel:=request.selectBaseLevel && phase===State.offerImage
  sampler.io.cancel:=samplerStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  image.io.cancel:=imageStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  sampler.io.invalidate:=io.invalidate || stop || noFill;image.io.invalidate:=io.invalidate || stop || noFill
  when(active && io.invalidate){noFill:=true.B}
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  arb.io.in(0).valid:=image.io.memoryRequest.valid;arb.io.in(0).bits:=image.io.memoryRequest.bits
  arb.io.in(0).bits.transactionId:=image.io.memoryRequest.bits.transactionId.pad(3);image.io.memoryRequest.ready:=arb.io.in(0).ready
  arb.io.in(1).valid:=sampler.io.memoryRequest.valid;arb.io.in(1).bits:=sampler.io.memoryRequest.bits
  arb.io.in(1).bits.transactionId:=4.U;sampler.io.memoryRequest.ready:=arb.io.in(1).ready;io.memoryRequest<>arb.io.out
  private val samplerReply=io.memoryResponse.bits.transactionId===4.U
  private val imageReply= !io.memoryResponse.bits.transactionId(2)
  sampler.io.memoryResponse.valid:=io.memoryResponse.valid && samplerReply;sampler.io.memoryResponse.bits:=io.memoryResponse.bits
  sampler.io.memoryResponse.bits.transactionId:=0.U
  image.io.memoryResponse.valid:=io.memoryResponse.valid && imageReply;image.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(samplerReply,sampler.io.memoryResponse.ready,imageReply && image.io.memoryResponse.ready)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!image.io.busy && !sampler.io.busy,"image/sampler binding abandoned child ownership")
      request:=io.in.bits;samplerResult:=0.U.asTypeOf(samplerResult);imageResult:=0.U.asTypeOf(imageResult)
      status:=0.U;source:=0.U;samplerStarted:=false.B;imageStarted:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      when(stop){phase:=State.finish}
        .elsewhen(request.images.identity.asUInt=/=request.samplers.identity.asUInt || request.expectedType>2.U || (request.selectBaseLevel && request.level=/=0.U)){
          status:=TextureImageResourceStatus.view.U;source:=TextureImageSamplerFaultSource.compatibility.U;phase:=State.finish
        }.otherwise{phase:=State.offerSampler}
    }
    is(State.offerSampler){when(sampler.io.in.fire){samplerStarted:=true.B;phase:=State.waitSampler}}
    is(State.waitSampler){when(sampler.io.out.fire){
      samplerStarted:=false.B;samplerResult:=sampler.io.out.bits;val code=sampler.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code}
      when(code=/=0.U){source:=TextureImageSamplerFaultSource.sampler.U}
      phase:=Mux(stop || code=/=0.U,State.finish,State.offerImage)
    }}
    is(State.offerImage){when(image.io.in.fire){imageStarted:=true.B;phase:=State.waitImage}}
    is(State.waitImage){when(image.io.out.fire){
      imageStarted:=false.B;imageResult:=image.io.out.bits;val code=image.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code}
      when(code=/=0.U){source:=TextureImageSamplerFaultSource.image.U}
      phase:=Mux(stop || code=/=0.U,State.finish,
        Mux(!TextureSamplerState.requiresMips(samplerResult.state) && image.io.out.bits.level=/=image.io.out.bits.baseLevel,
          State.offerBase,State.compatibility))
    }}
    // Nonmip completeness still requires the base image, even when an explicit
    // selected-level consumer asks for another level. Retain its selected view.
    is(State.offerBase){when(image.io.in.fire){imageStarted:=true.B;phase:=State.waitBase}}
    is(State.waitBase){when(image.io.out.fire){
      imageStarted:=false.B;val code=image.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code}
      when(code=/=0.U){source:=TextureImageSamplerFaultSource.image.U;imageResult:=image.io.out.bits}
      phase:=Mux(stop || code=/=0.U,State.finish,State.compatibility)
    }}
    is(State.compatibility){
      when(!stop && !TextureSamplerState.compatible(samplerResult.state,imageResult.format,request.expectedType)){
        status:=TextureImageFetchStatus.format.U;source:=TextureImageSamplerFaultSource.compatibility.U
      };phase:=State.finish
    }
    is(State.finish){
      assert(!image.io.busy && !sampler.io.busy,"image/sampler completion escaped child drain")
      result:=0.U.asTypeOf(result);result.identity:=request.images.identity;result.imageHandle:=request.imageHandle
      result.samplerHandle:=request.samplerHandle;result.level:=request.level;result.tag:=request.tag;result.faultSource:=source
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.faultAddressValid:=Mux(source===TextureImageSamplerFaultSource.sampler.U,samplerResult.faultAddressValid,
        source===TextureImageSamplerFaultSource.image.U && imageResult.faultAddressValid)
      result.faultAddress:=Mux(source===TextureImageSamplerFaultSource.sampler.U,samplerResult.faultAddress,
        Mux(source===TextureImageSamplerFaultSource.image.U,imageResult.faultAddress,0.U))
      when(!stop){result.validBinding:=true.B;result.level:=imageResult.level;result.sampler:=samplerResult;result.image:=imageResult}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!image.io.busy && !sampler.io.busy && !io.memoryRequest.valid,"held image/sampler result retains drained children")}
}
