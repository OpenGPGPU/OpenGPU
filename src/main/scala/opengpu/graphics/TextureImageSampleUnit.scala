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
  val lod=UInt(32.W);val offsets=Vec(3,SInt(5.W))
  /** Gradient mode resolves lambda from admitted extents and original cube
    * directions. Its unused explicit lambda must have canonical zero bits.
    */
  val gradient=Bool();val gradients=Vec(2,Vec(3,UInt(32.W)))
  /** Fragment implicit LOD uses a complete TL/TR/BL/BR helper snapshot.
    * The selected coordinate must match preparation. Bias is applied only
    * in this mode, after gradient lambda and before sampler clamps.
    */
  val implicitLod=Bool();val quadCoordinates=Vec(4,Vec(3,UInt(32.W)));val quadLane=UInt(2.W);val quadValidMask=UInt(4.W);val bias=UInt(32.W)
}
/** Descriptor-to-texel owner for explicit effective LOD or explicit gradients. Admission,
  * cube projection, array selection, spatial filtering, depth comparison,
  * mip interpolation and final swizzle share one immutable request lifetime.
  * This internal endpoint does not add or advertise a native shader opcode.
  */
class TextureImageSampleUnit(config: GpuConfig=GpuConfig(),p: TextureImageAddressConfig=TextureImageAddressConfig(),groupedTexture: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val in=Flipped(Decoupled(new TextureImageSampleRequest));val out=Decoupled(new TextureImageMipSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offerPreparation,waitPreparation,offerQuad,waitQuad,offerGradient,waitGradient,offerBias,waitBias,offerSample,waitSample,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureImageSampleRequest)
  private val prepared=Reg(new TextureImageMipSampleRequest);private val sampled=Reg(new TextureImageMipSampleResult)
  private val gradientRequest=Reg(new TextureImageGradientLodRequest);private val gradientStarted=RegInit(false.B)
  private val quadStarted=RegInit(false.B);private val biasStarted=RegInit(false.B)
  private val result=Reg(new TextureImageMipSampleResult);private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(3.W))
  private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W));private val noFill=RegInit(false.B)
  private val preparationStarted=RegInit(false.B);private val sampleStarted=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val preparation=Module(new TextureImageSamplePreparationUnit(config,p));private val sample=Module(new TextureImageMipSampleUnit(config,p,groupedTexture))
  if(groupedTexture){io.grouped.get <> sample.io.grouped.get}
  private val gradient=Module(new TextureImageGradientLodUnit)
  private val quad=Module(new TextureQuadGradientUnit);private val bias=Module(new TextureLodBiasUnit)
  quad.io.in.valid:=phase===State.offerQuad;quad.io.in.bits.coordinates:=request.quadCoordinates
  quad.io.in.bits.validMask:=request.quadValidMask;quad.io.in.bits.lane:=request.quadLane
  quad.io.in.bits.dimensions:=Mux(request.preparation.target===2.U || request.preparation.target===3.U,3.U,2.U);quad.io.in.bits.tag:=request.preparation.tag
  quad.io.out.ready:=phase===State.waitQuad;quad.io.cancel:=quadStarted && (io.cancel || status===4.U)
  bias.io.in.valid:=phase===State.offerBias;bias.io.in.bits.lod:=prepared.lod;bias.io.in.bits.bias:=request.bias;bias.io.in.bits.tag:=request.preparation.tag
  bias.io.out.ready:=phase===State.waitBias;bias.io.cancel:=biasStarted && (io.cancel || status===4.U)
  gradient.io.in.valid:=phase===State.offerGradient;gradient.io.in.bits:=gradientRequest;gradient.io.out.ready:=phase===State.waitGradient
  gradient.io.cancel:=gradientStarted && (io.cancel || status===4.U)
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
      assert(!preparation.io.busy && !sample.io.busy && !gradient.io.busy && !quad.io.busy && !bias.io.busy,"image sampling abandoned child ownership")
      request:=io.in.bits;status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;noFill:=io.invalidate
      preparationStarted:=false.B;sampleStarted:=false.B;gradientStarted:=false.B;quadStarted:=false.B;biasStarted:=false.B
      sampled:=0.U.asTypeOf(sampled);phase:=State.offerPreparation
      val q=io.in.bits;val third=q.preparation.target===2.U || q.preparation.target===3.U
      val mismatched=q.preparation.coordinates.zipWithIndex.map{case(v,a)=>(if(a==2)third else true.B) && v=/=q.quadCoordinates(q.quadLane)(a)}.reduce(_ || _)
      val badMode=(q.gradient && q.implicitLod) || ((q.gradient || q.implicitLod) && q.lod=/=0.U) ||
        (!q.implicitLod && q.bias=/=0.U) || (q.implicitLod && (q.quadValidMask=/=15.U || mismatched))
      val badOffset=q.offsets.map(o=>o<(-8).S || o>7.S).reduce(_ || _) ||
        (q.preparation.target===TextureImageTarget.cube.U && q.offsets.map(_=/=0.S).reduce(_ || _)) ||
        (q.preparation.target=/=TextureImageTarget.image3D.U && q.offsets(2)=/=0.S)
      when(badMode || badOffset){status:=1.U;source:=3.U;phase:=State.finish}
    }}
    is(State.offerPreparation){when(preparation.io.in.fire){preparationStarted:=true.B;phase:=State.waitPreparation}}
    is(State.waitPreparation){when(preparation.io.out.fire){
      preparationStarted:=false.B;val code=preparation.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=preparation.io.out.bits.faultSource;faultValid:=preparation.io.out.bits.faultAddressValid;faultAddress:=preparation.io.out.bits.faultAddress}}
      prepared:=preparation.io.out.bits.prepared;prepared.lod:=request.lod;prepared.sample.offsets:=request.offsets
      gradientRequest.prepared:=preparation.io.out.bits;gradientRequest.vector:=request.preparation.coordinates;gradientRequest.gradients:=request.gradients
      gradientRequest.extended:=false.B;gradientRequest.wideGradients:=0.U.asTypeOf(gradientRequest.wideGradients)
      phase:=Mux(stop || code=/=0.U,State.finish,Mux(request.implicitLod,State.offerQuad,Mux(request.gradient,State.offerGradient,State.offerSample)))
    }}
    is(State.offerQuad){when(quad.io.in.fire){quadStarted:=true.B;phase:=State.waitQuad}}
    is(State.waitQuad){when(quad.io.out.fire){
      quadStarted:=false.B;val r=quad.io.out.bits;assert(r.tag===request.preparation.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status;when(r.status=/=0.U){source:=Mux(r.status===5.U,4.U,3.U)}}
      gradientRequest.extended:=true.B;gradientRequest.wideGradients:=r.gradients
      phase:=Mux(stop || r.status=/=0.U,State.finish,State.offerGradient)
    }}
    is(State.offerGradient){when(gradient.io.in.fire){gradientStarted:=true.B;phase:=State.waitGradient}}
    is(State.waitGradient){when(gradient.io.out.fire){
      gradientStarted:=false.B;val r=gradient.io.out.bits
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status
        when(r.status=/=0.U){source:=r.faultSource;faultValid:=r.faultAddressValid;faultAddress:=r.faultAddress}}
      prepared:=r.resolved;prepared.sample.offsets:=request.offsets;phase:=Mux(stop || r.status=/=0.U,State.finish,Mux(request.implicitLod,State.offerBias,State.offerSample))
    }}
    is(State.offerBias){when(bias.io.in.fire){biasStarted:=true.B;phase:=State.waitBias}}
    is(State.waitBias){when(bias.io.out.fire){
      biasStarted:=false.B;val r=bias.io.out.bits;assert(r.tag===request.preparation.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status;when(r.status=/=0.U){source:=4.U}}
      prepared.lod:=r.lod;phase:=Mux(stop || r.status=/=0.U,State.finish,State.offerSample)
    }}
    is(State.offerSample){when(sample.io.in.fire){sampleStarted:=true.B;phase:=State.waitSample}}
    is(State.waitSample){when(sample.io.out.fire){
      sampleStarted:=false.B;sampled:=sample.io.out.bits;val code=sample.io.out.bits.sample.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=sample.io.out.bits.sample.faultSource;faultValid:=sample.io.out.bits.sample.faultAddressValid;faultAddress:=sample.io.out.bits.sample.faultAddress}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!preparation.io.busy && !sample.io.busy && !gradient.io.busy && !quad.io.busy && !bias.io.busy && !io.memoryRequest.valid,"image sampling completion escaped child drain")
      result:=sampled;result.sample.tag:=request.preparation.tag;result.sample.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.sample.faultSource:=source;result.sample.faultAddressValid:=faultValid;result.sample.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(stop){result.sample.rgba.foreach(_:=0.U);result.sample.colorType:=0.U};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!preparation.io.busy && !sample.io.busy && !gradient.io.busy && !quad.io.busy && !bias.io.busy && !io.memoryRequest.valid,"held image sample owns drained children")}
}
