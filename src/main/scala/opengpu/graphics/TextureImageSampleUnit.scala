package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class TextureImageSampleRequest extends Bundle {
  val preparation=new TextureImageSamplePreparationRequest
  /** Effective explicit lambda. Derivative and shader-bias owners resolve
    * their lambda before using this endpoint; sampler clamps apply here.
    */
  val lod=UInt(32.W)
}
/** Complete descriptor-to-texel owner for explicit effective LOD. Admission,
  * cube projection, array selection, spatial filtering, depth comparison,
  * mip interpolation and final swizzle share one immutable request lifetime.
  * This internal endpoint does not add or advertise a native shader opcode.
  */
class TextureImageSampleUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureImageSampleRequest));val out=Decoupled(new TextureImageMipSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offerPreparation,waitPreparation,offerSample,waitSample,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageSampleRequest)
  private val prepared=Reg(new TextureImageMipSampleRequest);private val sampled=Reg(new TextureImageMipSampleResult)
  private val result=Reg(new TextureImageMipSampleResult);private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W))
  private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W));private val noFill=RegInit(false.B)
  private val preparationStarted=RegInit(false.B);private val sampleStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val preparation=Module(new TextureImageSamplePreparationUnit(config,p));private val sample=Module(new TextureImageMipSampleUnit(config,p))
  preparation.io.in.valid:=phase===State.offerPreparation;preparation.io.in.bits:=request.preparation
  preparation.io.out.ready:=phase===State.waitPreparation;preparation.io.cancel:=preparationStarted && (io.cancel || status===4.U)
  preparation.io.invalidate:=io.invalidate || noFill || stop
  sample.io.in.valid:=phase===State.offerSample;sample.io.in.bits:=prepared;sample.io.out.ready:=phase===State.waitSample
  sample.io.cancel:=sampleStarted && (io.cancel || status===4.U);sample.io.invalidate:=io.invalidate || noFill || stop
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
  arb.io.in(0).valid:=preparation.io.memoryRequest.valid;arb.io.in(0).bits:=preparation.io.memoryRequest.bits
  arb.io.in(0).bits.transactionId:=preparation.io.memoryRequest.bits.transactionId.pad(4)
  preparation.io.memoryRequest.ready:=arb.io.in(0).ready
  arb.io.in(1).valid:=sample.io.memoryRequest.valid;arb.io.in(1).bits:=sample.io.memoryRequest.bits
  arb.io.in(1).bits.transactionId:=Cat(2.U(2.W),sample.io.memoryRequest.bits.transactionId)
  sample.io.memoryRequest.ready:=arb.io.in(1).ready;io.memoryRequest<>arb.io.out
  private val id=io.memoryResponse.bits.transactionId;private val preparationReply=id<5.U;private val sampleReply=id>=8.U && id<12.U
  preparation.io.memoryResponse.valid:=io.memoryResponse.valid && preparationReply;preparation.io.memoryResponse.bits:=io.memoryResponse.bits
  sample.io.memoryResponse.valid:=io.memoryResponse.valid && sampleReply;sample.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(preparationReply,preparation.io.memoryResponse.ready,Mux(sampleReply,sample.io.memoryResponse.ready,false.B))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U;source:=0.U;faultValid:=false.B}
  when(active && io.invalidate){noFill:=true.B}
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!preparation.io.busy && !sample.io.busy,"image sampling abandoned child ownership")
      request:=io.in.bits;status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;noFill:=io.invalidate
      preparationStarted:=false.B;sampleStarted:=false.B;sampled:=0.U.asTypeOf(sampled);phase:=State.offerPreparation
    }}
    is(State.offerPreparation){when(preparation.io.in.fire){preparationStarted:=true.B;phase:=State.waitPreparation}}
    is(State.waitPreparation){when(preparation.io.out.fire){
      preparationStarted:=false.B;val code=preparation.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=preparation.io.out.bits.faultSource;faultValid:=preparation.io.out.bits.faultAddressValid;faultAddress:=preparation.io.out.bits.faultAddress}}
      prepared:=preparation.io.out.bits.prepared;prepared.lod:=request.lod
      phase:=Mux(stop || code=/=0.U,State.finish,State.offerSample)
    }}
    is(State.offerSample){when(sample.io.in.fire){sampleStarted:=true.B;phase:=State.waitSample}}
    is(State.waitSample){when(sample.io.out.fire){
      sampleStarted:=false.B;sampled:=sample.io.out.bits;val code=sample.io.out.bits.sample.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=sample.io.out.bits.sample.faultSource;faultValid:=sample.io.out.bits.sample.faultAddressValid;faultAddress:=sample.io.out.bits.sample.faultAddress}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!preparation.io.busy && !sample.io.busy && !io.memoryRequest.valid,"image sampling completion escaped child drain")
      result:=sampled;result.sample.tag:=request.preparation.tag;result.sample.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.sample.faultSource:=source;result.sample.faultAddressValid:=faultValid;result.sample.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(stop){result.sample.rgba.foreach(_:=0.U);result.sample.colorType:=0.U};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!preparation.io.busy && !sample.io.busy && !io.memoryRequest.valid,"held image sample owns drained children")}
}
