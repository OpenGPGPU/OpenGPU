package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

object ShaderTextureLodMode {val automatic=0;val explicit=1;val gradient=2}
/** Captured shader operands, supplied on chip by the enclosing execution owner.
  * This is an internal port, not a new GPU-memory packet or native encoding.
  */
class ShaderTextureSampleParameters extends Bundle {
  val mode=UInt(2.W);val coordinates=Vec(3,UInt(32.W));val layer=UInt(32.W)
  val reference=UInt(32.W);val shadow=Bool();val lod=UInt(32.W);val bias=UInt(32.W)
  val offsets=Vec(3,SInt(5.W));val gradients=Vec(2,Vec(3,UInt(32.W)))
  val quadCoordinates=Vec(4,Vec(3,UInt(32.W)));val quadLane=UInt(2.W);val quadValidMask=UInt(4.W)
}
class ShaderTextureSampleRequest extends Bundle {
  val resources=new ShaderTextureResources;val identity=new GraphicsWorkIdentity
  val unit=UInt(32.W);val component=UInt(2.W);val tag=UInt(32.W)
  val parameters=new ShaderTextureSampleParameters
}
class ShaderTextureSampleResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val unit=UInt(32.W);val tag=UInt(32.W)
  val value=UInt(32.W);val colorType=UInt(2.W);val status=UInt(3.W)
  /** 1=root, 2=binding, 3=parameters, 4=image sampling pipeline.
    * The nested sample retains its more detailed internal fault source.
    */
  val faultSource=UInt(4.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
  val sample=new TextureImageMipSampleResult
}
/** Resolve GXR1/GXB1 per-stage bindings into floating sampling. Logical ID15
  * owns root/binding reads; the child retains IDs0..4 and8..11. Parent pins
  * descriptor publication and translation through held completion.
  */
class ShaderTextureSampleUnit(config: GpuConfig=GpuConfig(),stage: Int=1,
    p: TextureImageAddressConfig=TextureImageAddressConfig(),groupedTexture: Boolean=false) extends Module {
  require(stage>=0 && stage<=2)
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val laneTemplate=if(groupedTexture)Some(Valid(new NativeRgba8TextureLaneTemplate))else None
    val in=Flipped(Decoupled(new ShaderTextureSampleRequest));val out=Decoupled(new ShaderTextureSampleResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,readRoot,verifyRoot,readBinding,verifyBinding,offerSample,waitSample,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new ShaderTextureSampleRequest)
  private val root=Reg(new ShaderTextureRootRecord(if(stage==2) 2 else 1));private val binding=Reg(new ShaderTextureBindingRecord(if(stage==2) 2 else 1))
  private val sampled=Reg(new TextureImageMipSampleResult);private val result=Reg(new ShaderTextureSampleResult)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(4.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val base=Reg(UInt(32.W));private val lines=Reg(UInt(2.W));private val issued=Reg(UInt(2.W));private val readLine=Reg(UInt(1.W))
  private val offered=RegInit(false.B);private val pending=RegInit(false.B);private val memory=Reg(new ComputeMemoryRequest(config,64,16))
  private val malformed=RegInit(false.B);private val started=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val reading=phase===State.readRoot || phase===State.readBinding
  private val sample=Module(new TextureImageSampleUnit(config,p,groupedTexture));private val q=request.parameters
  if(groupedTexture){io.grouped.get <> sample.io.grouped.get; io.laneTemplate.get := sample.io.laneTemplate.get}
  sample.io.in.valid:=phase===State.offerSample;sample.io.in.bits:=0.U.asTypeOf(sample.io.in.bits)
  private val s=sample.io.in.bits;private val prep=s.preparation
  prep.images.version:=1.U;prep.images.identity:=request.identity;prep.images.base:=root.imagesBase;prep.images.limit:=root.imagesLimit
  prep.samplers.version:=1.U;prep.samplers.identity:=request.identity;prep.samplers.base:=root.samplersBase;prep.samplers.limit:=root.samplersLimit
  prep.imageHandle:=binding.imageHandle;prep.samplerHandle:=binding.samplerHandle;prep.expectedType:=binding.expectedType;prep.target:=binding.target
  prep.coordinates:=q.coordinates;prep.layer:=q.layer;prep.reference:=q.reference;prep.shadow:=q.shadow;prep.applySwizzle:=true.B;prep.tag:=request.tag
  s.lod:=Mux(q.mode===ShaderTextureLodMode.explicit.U,q.lod,0.U)
  s.gradient:=q.mode===ShaderTextureLodMode.gradient.U;s.gradients:=q.gradients;s.offsets:=q.offsets
  s.implicitLod:=(stage==1).B && q.mode===ShaderTextureLodMode.automatic.U
  s.quadCoordinates:=q.quadCoordinates;s.quadLane:=q.quadLane;s.quadValidMask:=q.quadValidMask
  s.bias:=Mux(s.implicitLod,q.bias,0.U)
  sample.io.out.ready:=phase===State.waitSample;sample.io.cancel:=started && (io.cancel || status===4.U)
  sample.io.invalidate:=io.invalidate || stop || noFill
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
  arb.io.in(0).valid:=offered;arb.io.in(0).bits:=memory;arb.io.in(1)<>sample.io.memoryRequest;io.memoryRequest<>arb.io.out
  when(sample.io.memoryRequest.valid){assert(sample.io.memoryRequest.bits.transactionId=/=15.U,"sample used reserved native binding ID")}
  private val own=io.memoryResponse.bits.transactionId===15.U
  sample.io.memoryResponse.valid:=io.memoryResponse.valid && !own;sample.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(own,pending,sample.io.memoryResponse.ready)
  private val reply=io.memoryResponse.fire && own
  when(arb.io.in(0).fire){offered:=false.B;pending:=true.B}
  private val rootFields=ShaderTextureRootLayout.fields(root);private val bindingFields=ShaderTextureBindingLayout.fields(binding)
  NativeTypedGraphicsLayout.check(root,rootFields,32);NativeTypedGraphicsLayout.check(binding,bindingFields,16)
  private val receive=reply && !io.memoryResponse.bits.fault && !stop
  private val badRoot=NativeTypedGraphicsLayout.receive(rootFields,32,ShaderTextureRootLayout.magic,readLine,io.memoryResponse.bits.readData,receive && phase===State.readRoot)
  private val badBinding=NativeTypedGraphicsLayout.receive(bindingFields,16,ShaderTextureBindingLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readBinding)
  private def fail(code: UInt,where: UInt,addressValid: Bool=false.B,address: UInt=0.U): Unit={
    when(status===0.U && !io.cancel){status:=code;source:=where;faultValid:=addressValid;faultAddress:=address}
  }
  when(reply){pending:=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,Mux(phase===State.readRoot,1.U,2.U),true.B,memory.address)}
    when(receive){malformed:=malformed || Mux(phase===State.readRoot,badRoot,badBinding)}
  }
  when(reading && !stop && !(reply && io.memoryResponse.bits.fault) && !offered && !pending && issued<lines){
    memory:=0.U.asTypeOf(memory);memory.address:=(base+(issued<<6))(31,0);memory.sizeLog2:=6.U
    memory.byteMask:=Fill(64,1.U(1.W));memory.cachePolicy:=CachePolicy.uncached;memory.transactionId:=15.U
    readLine:=issued(0);issued:=issued+1.U;offered:=true.B
  }
  when(active && io.cancel && status===0.U){status:=4.U;source:=0.U;faultValid:=false.B}
  when(active && io.invalidate){noFill:=true.B}
  private def beginRead(address: UInt,count: Int,next: State.Type): Unit={base:=address;lines:=count.U;issued:=0.U;malformed:=false.B;phase:=next}
  private def legalTable(address: UInt,limit: UInt,stride: Int): Bool=address(5,0)===0.U && limit>address &&
    limit<=(BigInt(1)<<32).U && (limit-address)(log2Ceil(stride)-1,0)===0.U
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!offered && !pending && !sample.io.busy,"native floating sampling abandoned ownership")
      request:=io.in.bits;root:=0.U.asTypeOf(root);binding:=0.U.asTypeOf(binding);sampled:=0.U.asTypeOf(sampled)
      status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;started:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      val params=q.mode>ShaderTextureLodMode.gradient.U || q.offsets.map(o=>o<(-8).S || o>7.S).reduce(_ || _) ||
        ((if(stage!=1)true.B else q.mode=/=ShaderTextureLodMode.automatic.U) && q.bias=/=0.U) ||
        ((stage==1).B && q.mode===ShaderTextureLodMode.automatic.U && q.quadValidMask=/=15.U)
      when(stop){phase:=State.finish}.elsewhen(params){fail(1.U,3.U);phase:=State.finish}
        .elsewhen(request.resources.version=/=1.U || !ShaderTextureResources.legal(request.resources)){fail(1.U,1.U);phase:=State.finish}
        .otherwise{beginRead(request.resources.address,2,State.readRoot)}
    }
    is(State.readRoot){when((stop || issued===lines) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyRoot)}}
    is(State.verifyRoot){
      val end=root.bindingsBase.pad(64)+(root.bindingCount.pad(64)<<6)
      when(stop){phase:=State.finish}.elsewhen(malformed || root.identity.asUInt=/=request.identity.asUInt || root.stage=/=stage.U){fail(7.U,1.U);phase:=State.finish}
        .elsewhen(root.bindingCount===0.U || root.bindingCount>ShaderTextureResources.maxBindings.U || request.unit>=root.bindingCount ||
          !legalTable(root.bindingsBase,root.bindingsLimit,64) || end>root.bindingsLimit ||
          !legalTable(root.imagesBase,root.imagesLimit,128) || !legalTable(root.samplersBase,root.samplersLimit,64)){fail(1.U,1.U);phase:=State.finish}
        .otherwise{beginRead((root.bindingsBase.pad(64)+(request.unit.pad(64)<<6))(31,0),1,State.readBinding)}
    }
    is(State.readBinding){when((stop || issued===lines) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyBinding)}}
    is(State.verifyBinding){
      when(stop){phase:=State.finish}.elsewhen(malformed || binding.identity.asUInt=/=request.identity.asUInt || binding.stage=/=stage.U || binding.unit=/=request.unit){fail(7.U,2.U);phase:=State.finish}
        .elsewhen(binding.expectedType>2.U || binding.target>3.U){fail(1.U,2.U);phase:=State.finish}
        .otherwise{phase:=State.offerSample}
    }
    is(State.offerSample){when(sample.io.in.fire){started:=true.B;phase:=State.waitSample}}
    is(State.waitSample){when(sample.io.out.fire){
      sampled:=sample.io.out.bits;started:=false.B;val child=sample.io.out.bits.sample;val code=child.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=4.U;faultValid:=child.faultAddressValid;faultAddress:=child.faultAddress}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!offered && !pending && !sample.io.busy && !io.memoryRequest.valid,"native sample completion escaped drain")
      result:=0.U.asTypeOf(result);result.identity:=request.identity;result.unit:=request.unit;result.tag:=request.tag;result.sample:=sampled
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultSource:=source;result.faultAddressValid:=faultValid
      result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.value:=sampled.sample.rgba(request.component);result.colorType:=sampled.sample.colorType}
      result.sample.sample.tag:=request.tag;result.sample.sample.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(stop){result.sample.sample.rgba.foreach(_:=0.U);result.sample.sample.colorType:=0.U};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!offered && !pending && !sample.io.busy,"held native sample owns undrained work")}
}
