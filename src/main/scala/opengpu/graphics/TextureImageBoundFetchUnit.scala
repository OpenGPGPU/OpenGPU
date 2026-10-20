package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageBoundFetchRequest extends Bundle {
  val image=new TextureImageBindingRequest
  val x=UInt(32.W);val y=UInt(32.W);val z=UInt(32.W);val layer=UInt(32.W);val face=UInt(3.W)
}
class TextureImageBoundFetchResult extends Bundle {
  val image=new TextureImageBindingResult;val sample=new TextureImageTypedFetchResult
}
/** Image format comes exclusively from GXI1, never from the texel caller.
  * The binding validates the required level set before texel reads. The
  * selected GXL1 lookup is then passed to the qualified typed fetch owner.
  * Publication/VM lifetime spans both children and the held final result.
  */
class TextureImageBoundFetchUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageBoundFetchRequest));val out=Decoupled(new TextureImageBoundFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offerBinding,waitBinding,offerFetch,waitFetch,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageBoundFetchRequest)
  private val binding=Reg(new TextureImageBindingResult);private val sample=Reg(new TextureImageTypedFetchResult)
  private val result=Reg(new TextureImageBoundFetchResult);private val status=RegInit(0.U(3.W))
  private val started=RegInit(false.B);private val fetchStarted=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val image=Module(new TextureImageBindingUnit(config,p));private val fetch=Module(new TextureImageTypedFetchUnit(config,p))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  image.io.in.valid:=phase===State.offerBinding;image.io.in.bits:=request.image;image.io.out.ready:=phase===State.waitBinding
  image.io.cancel:=started && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  image.io.invalidate:=io.invalidate || stop || noFill
  fetch.io.in.valid:=phase===State.offerFetch;fetch.io.out.ready:=phase===State.waitFetch
  fetch.io.in.bits.resource.lookup:=binding.selected;fetch.io.in.bits.resource.tag:=request.image.tag
  fetch.io.in.bits.resource.x:=request.x;fetch.io.in.bits.resource.y:=request.y;fetch.io.in.bits.resource.z:=request.z
  fetch.io.in.bits.resource.layer:=request.layer;fetch.io.in.bits.resource.face:=request.face;fetch.io.in.bits.format:=binding.format
  fetch.io.cancel:=fetchStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  fetch.io.invalidate:=io.invalidate || stop || noFill
  when(active && io.invalidate){noFill:=true.B}
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  arb.io.in(0).valid:=image.io.memoryRequest.valid;arb.io.in(0).bits:=image.io.memoryRequest.bits
  arb.io.in(0).bits.transactionId:=Cat(0.U(1.W),image.io.memoryRequest.bits.transactionId);image.io.memoryRequest.ready:=arb.io.in(0).ready
  arb.io.in(1).valid:=fetch.io.memoryRequest.valid;arb.io.in(1).bits:=fetch.io.memoryRequest.bits
  arb.io.in(1).bits.transactionId:=Cat(1.U(1.W),fetch.io.memoryRequest.bits.transactionId);fetch.io.memoryRequest.ready:=arb.io.in(1).ready
  io.memoryRequest<>arb.io.out
  private val replyFetch=io.memoryResponse.bits.transactionId(2)
  image.io.memoryResponse.valid:=io.memoryResponse.valid && !replyFetch;image.io.memoryResponse.bits:=io.memoryResponse.bits
  fetch.io.memoryResponse.valid:=io.memoryResponse.valid && replyFetch;fetch.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(replyFetch,fetch.io.memoryResponse.ready,image.io.memoryResponse.ready)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!image.io.busy && !fetch.io.busy,"bound fetch abandoned image/texel ownership")
      request:=io.in.bits;binding:=0.U.asTypeOf(binding);sample:=0.U.asTypeOf(sample);status:=0.U
      started:=false.B;fetchStarted:=false.B;noFill:=io.invalidate;phase:=State.offerBinding
    }}
    is(State.offerBinding){when(image.io.in.fire){started:=true.B;phase:=State.waitBinding}}
    is(State.waitBinding){when(image.io.out.fire){
      binding:=image.io.out.bits;started:=false.B;val code=image.io.out.bits.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code}
      phase:=Mux(stop || code=/=0.U,State.finish,State.offerFetch)
    }}
    is(State.offerFetch){when(fetch.io.in.fire){fetchStarted:=true.B;phase:=State.waitFetch}}
    is(State.waitFetch){when(fetch.io.out.fire){
      sample:=fetch.io.out.bits;fetchStarted:=false.B;val code=fetch.io.out.bits.sample.status
      when((code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U) || !stop){status:=code
        when(code=/=0.U && code=/=TextureImageResourceStatus.cancelled.U){
          binding.faultAddressValid:=fetch.io.out.bits.sample.faultAddressValid;binding.faultAddress:=fetch.io.out.bits.sample.faultAddress
          binding.faultStage:=Mux(code===TextureImageResourceStatus.coordinate.U,TextureImageBindingFaultStage.coordinate.U,
            MuxLookup(fetch.io.out.bits.sample.faultSource,TextureImageBindingFaultStage.layout.U)(Seq(
              TextureImageFetchFaultSource.descriptor.U->TextureImageBindingFaultStage.level.U,
              TextureImageFetchFaultSource.data.U->TextureImageBindingFaultStage.data.U)))
        }
      }
      phase:=State.finish
    }}
    is(State.finish){
      assert(!image.io.busy && !fetch.io.busy,"bound fetch completion escaped child drain")
      result:=0.U.asTypeOf(result);result.image:=binding;result.sample:=sample
      result.sample.rgba:=TextureImageSwizzle(sample.rgba,sample.colorType,binding.swizzle)
      result.image.identity:=request.image.table.identity;result.image.handle:=request.image.handle
      result.image.level:=binding.level;result.image.tag:=request.image.tag
      val finalStatus=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      result.image.status:=finalStatus;result.sample.sample.status:=finalStatus
      result.sample.sample.identity:=request.image.table.identity;result.sample.sample.tag:=request.image.tag
      result.sample.sample.level:=binding.level(3,0)
      when(stop){result.image.validBinding:=false.B;result.image.format:=0.U;result.image.baseLevel:=0.U;result.image.lastLevel:=0.U
        result.image.swizzle:=0.U.asTypeOf(result.image.swizzle)
        result.image.mipChainChecked:=false.B;result.image.selected:=0.U.asTypeOf(result.image.selected);result.image.view:=0.U.asTypeOf(result.image.view)
        result.sample.sample.viewValid:=false.B;result.sample.sample.view:=0.U.asTypeOf(result.sample.sample.view)
        result.sample.sample.address:=0.U;result.sample.sample.bytes:=0.U;result.sample.sample.raw:=0.U
        result.sample.sample.blockX:=0.U;result.sample.sample.blockY:=0.U;result.sample.rgba.foreach(_:=0.U);result.sample.colorType:=0.U
      }
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!image.io.busy && !fetch.io.busy && !io.memoryRequest.valid,"held bound result must retain drained children")}
}
