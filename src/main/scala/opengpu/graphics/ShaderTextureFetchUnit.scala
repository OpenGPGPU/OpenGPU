package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class ShaderTextureFetchRequest(capturedCoordinates: Boolean=false) extends Bundle {
  val resources=new ShaderTextureResources;val identity=new GraphicsWorkIdentity;val stage=UInt(1.W)
  val unit=UInt(32.W);val packet=UInt(32.W);val component=UInt(2.W);val tag=UInt(32.W)
  val coordinates=if(capturedCoordinates)Some(new ShaderTextureCoordinateRecord)else None
  val offsets=if(capturedCoordinates)Some(Vec(3,SInt(5.W)))else None
}
class ShaderTextureFetchResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val unit=UInt(32.W);val tag=UInt(32.W)
  val status=UInt(3.W);val value=UInt(32.W);val colorType=UInt(2.W)
  val faultSource=UInt(4.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}
/** Root/binding/packet reads use ID0, selected typed fetch IDs4..7, and image/
  * sampler admission IDs8..12. Reserved IDs never alias an owned transaction.
  * Parent pins root,descriptor publication and VM through held completion.
  */
class ShaderTextureFetchUnit(config: GpuConfig=GpuConfig(),capturedCoordinates: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ShaderTextureFetchRequest(capturedCoordinates)));val out=Decoupled(new ShaderTextureFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,readRoot,verifyRoot,readBinding,verifyBinding,readPacket,verifyPacket,offerAdmission,waitAdmission,checkTarget,offerFetch,waitFetch,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new ShaderTextureFetchRequest(capturedCoordinates))
  private val root=Reg(new ShaderTextureRootRecord);private val binding=Reg(new ShaderTextureBindingRecord);private val packet=Reg(new ShaderTextureCoordinateRecord)
  private val admitted=Reg(new TextureImageSamplerBindingResult);private val fetched=Reg(new TextureImageTypedFetchResult);private val result=Reg(new ShaderTextureFetchResult)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(4.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val readBase=Reg(UInt(32.W));private val lineCount=Reg(UInt(2.W));private val issued=RegInit(0.U(2.W));private val readLine=Reg(UInt(1.W));private val malformed=RegInit(false.B)
  private val offered=RegInit(false.B);private val pending=RegInit(false.B);private val memory=Reg(new ComputeMemoryRequest(config,64,16))
  private val admissionStarted=RegInit(false.B);private val fetchStarted=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val reading=phase===State.readRoot || phase===State.readBinding || phase===State.readPacket
  private val readSource=Mux(phase===State.readRoot,1.U,Mux(phase===State.readBinding,2.U,3.U))
  private val admission=Module(new TextureImageSamplerBindingUnit(config,relativeLevel=capturedCoordinates));private val fetch=Module(new TextureImageTypedFetchUnit(config))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  admission.io.in.valid:=phase===State.offerAdmission;admission.io.in.bits:=0.U.asTypeOf(admission.io.in.bits)
  admission.io.in.bits.images.version:=1.U;admission.io.in.bits.images.identity:=request.identity
  admission.io.in.bits.images.base:=root.imagesBase;admission.io.in.bits.images.limit:=root.imagesLimit
  admission.io.in.bits.samplers.version:=1.U;admission.io.in.bits.samplers.identity:=request.identity
  admission.io.in.bits.samplers.base:=root.samplersBase;admission.io.in.bits.samplers.limit:=root.samplersLimit
  admission.io.in.bits.imageHandle:=binding.imageHandle;admission.io.in.bits.samplerHandle:=binding.samplerHandle
  admission.io.in.bits.expectedType:=binding.expectedType;admission.io.in.bits.level:=packet.level;admission.io.in.bits.tag:=request.tag
  admission.io.out.ready:=phase===State.waitAdmission
  admission.io.cancel:=admissionStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  admission.io.invalidate:=io.invalidate || stop || noFill
  fetch.io.in.valid:=phase===State.offerFetch;fetch.io.in.bits:=0.U.asTypeOf(fetch.io.in.bits)
  fetch.io.in.bits.resource.lookup:=admitted.image.selected;fetch.io.in.bits.resource.tag:=request.tag
  fetch.io.in.bits.resource.x:=packet.x;fetch.io.in.bits.resource.y:=packet.y;fetch.io.in.bits.resource.z:=packet.z
  fetch.io.in.bits.resource.layer:=packet.layer;fetch.io.in.bits.resource.face:=packet.face(2,0);fetch.io.in.bits.format:=admitted.image.format
  fetch.io.out.ready:=phase===State.waitFetch;fetch.io.cancel:=fetchStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  fetch.io.invalidate:=io.invalidate || stop || noFill
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),3))
  arb.io.in(0).valid:=offered;arb.io.in(0).bits:=memory
  arb.io.in(1).valid:=admission.io.memoryRequest.valid;arb.io.in(1).bits:=admission.io.memoryRequest.bits
  arb.io.in(1).bits.transactionId:=Cat(1.U(1.W),admission.io.memoryRequest.bits.transactionId);admission.io.memoryRequest.ready:=arb.io.in(1).ready
  arb.io.in(2).valid:=fetch.io.memoryRequest.valid;arb.io.in(2).bits:=fetch.io.memoryRequest.bits
  arb.io.in(2).bits.transactionId:=Cat(1.U(2.W),fetch.io.memoryRequest.bits.transactionId);fetch.io.memoryRequest.ready:=arb.io.in(2).ready
  io.memoryRequest<>arb.io.out
  private val tag=io.memoryResponse.bits.transactionId
  private val ownReply=tag===0.U;private val admissionReply=tag>=8.U && tag<=12.U;private val fetchReply=tag>=4.U && tag<=7.U
  admission.io.memoryResponse.valid:=io.memoryResponse.valid && admissionReply;admission.io.memoryResponse.bits:=io.memoryResponse.bits
  fetch.io.memoryResponse.valid:=io.memoryResponse.valid && fetchReply;fetch.io.memoryResponse.bits:=io.memoryResponse.bits
  private val ownerFire=io.memoryResponse.fire && ownReply
  io.memoryResponse.ready:=Mux(ownReply,pending,Mux(admissionReply,admission.io.memoryResponse.ready,fetchReply && fetch.io.memoryResponse.ready))
  when(arb.io.in(0).fire){offered:=false.B;pending:=true.B}
  private val rootFields=ShaderTextureRootLayout.fields(root);private val bindingFields=ShaderTextureBindingLayout.fields(binding);private val packetFields=ShaderTextureCoordinateLayout.fields(packet)
  NativeTypedGraphicsLayout.check(root,rootFields,32);NativeTypedGraphicsLayout.check(binding,bindingFields,16);NativeTypedGraphicsLayout.check(packet,packetFields,16)
  private val receive=ownerFire && !io.memoryResponse.bits.fault && !stop
  private val badRoot=NativeTypedGraphicsLayout.receive(rootFields,32,ShaderTextureRootLayout.magic,readLine,io.memoryResponse.bits.readData,receive && phase===State.readRoot)
  private val badBinding=NativeTypedGraphicsLayout.receive(bindingFields,16,ShaderTextureBindingLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readBinding)
  private val badPacket=NativeTypedGraphicsLayout.receive(packetFields,16,ShaderTextureCoordinateLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readPacket)
  private def fail(code: UInt,where: UInt,addressValid: Bool=false.B,address: UInt=0.U): Unit={
    when(status===0.U && !io.cancel){status:=code;source:=where;faultValid:=addressValid;faultAddress:=address}
  }
  when(ownerFire){pending:=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,readSource,true.B,memory.address)}
    when(receive){malformed:=malformed || Mux(phase===State.readRoot,badRoot,Mux(phase===State.readBinding,badBinding,badPacket))}
  }
  when(reading && !stop && !(ownerFire && io.memoryResponse.bits.fault) && !offered && !pending && issued<lineCount){
    memory:=0.U.asTypeOf(memory);memory.address:=(readBase+(issued<<6))(31,0);memory.sizeLog2:=6.U
    memory.byteMask:=Fill(64,1.U(1.W));memory.cachePolicy:=CachePolicy.uncached
    readLine:=issued(0);issued:=issued+1.U;offered:=true.B
  }
  when(active && io.cancel && status===0.U){status:=4.U;source:=0.U}
  when(active && io.invalidate){noFill:=true.B}
  private def beginRead(base: UInt,lines: Int,next: State.Type): Unit={readBase:=base;lineCount:=lines.U;issued:=0.U;malformed:=false.B;phase:=next}
  private def tableLegal(base: UInt,limit: UInt,stride: Int): Bool=base(5,0)===0.U && limit>base && limit<=(BigInt(1)<<32).U &&
    (limit-base)(log2Ceil(stride)-1,0)===0.U
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!offered && !pending && !admission.io.busy && !fetch.io.busy,"typed shader fetch abandoned ownership")
      request:=io.in.bits;root:=0.U.asTypeOf(root);binding:=0.U.asTypeOf(binding);packet:=0.U.asTypeOf(packet)
      if(capturedCoordinates){packet:=io.in.bits.coordinates.get}
      admitted:=0.U.asTypeOf(admitted);fetched:=0.U.asTypeOf(fetched);status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U
      admissionStarted:=false.B;fetchStarted:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      when(stop){phase:=State.finish}.elsewhen(request.resources.version=/=1.U || !ShaderTextureResources.legal(request.resources)){
        fail(1.U,1.U);phase:=State.finish
      }.elsewhen((!capturedCoordinates).B && (request.packet(5,0)=/=0.U || request.packet.pad(33)+64.U>(BigInt(1)<<32).U)){fail(1.U,3.U);phase:=State.finish}
        .otherwise{beginRead(request.resources.address,2,State.readRoot)}
    }
    is(State.readRoot){when((stop || issued===lineCount) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyRoot)}}
    is(State.verifyRoot){
      val end=root.bindingsBase.pad(64)+(root.bindingCount.pad(64)<<6)
      when(stop){phase:=State.finish}.elsewhen(malformed || root.identity.asUInt=/=request.identity.asUInt || root.stage=/=request.stage){fail(7.U,1.U);phase:=State.finish}
        .elsewhen(root.bindingCount===0.U || root.bindingCount>32.U || request.unit>=root.bindingCount ||
          !tableLegal(root.bindingsBase,root.bindingsLimit,64) || end>root.bindingsLimit ||
          !tableLegal(root.imagesBase,root.imagesLimit,128) || !tableLegal(root.samplersBase,root.samplersLimit,64)){
          fail(1.U,1.U);phase:=State.finish
        }.otherwise{beginRead((root.bindingsBase.pad(64)+(request.unit.pad(64)<<6))(31,0),1,State.readBinding)}
    }
    is(State.readBinding){when((stop || issued===lineCount) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyBinding)}}
    is(State.verifyBinding){
      when(stop){phase:=State.finish}.elsewhen(malformed || binding.identity.asUInt=/=request.identity.asUInt || binding.stage=/=request.stage || binding.unit=/=request.unit){fail(7.U,2.U);phase:=State.finish}
        .elsewhen(binding.expectedType>2.U || binding.target>3.U){fail(1.U,2.U);phase:=State.finish}
        .otherwise{if(capturedCoordinates){phase:=State.verifyPacket}else{beginRead(request.packet,1,State.readPacket)}}
    }
    is(State.readPacket){when((stop || issued===lineCount) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyPacket)}}
    is(State.verifyPacket){
      when(stop){phase:=State.finish}.elsewhen(malformed || packet.identity.asUInt=/=request.identity.asUInt){fail(7.U,3.U);phase:=State.finish}
        .elsewhen(packet.face>7.U){fail(5.U,3.U);phase:=State.finish}.otherwise{phase:=State.offerAdmission}
    }
    is(State.offerAdmission){when(admission.io.in.fire){admissionStarted:=true.B;phase:=State.waitAdmission}}
    is(State.waitAdmission){when(admission.io.out.fire){
      admitted:=admission.io.out.bits;admissionStarted:=false.B;val code=admission.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=MuxLookup(admission.io.out.bits.faultSource,7.U)(Seq(1.U->4.U,2.U->5.U,3.U->7.U))
          faultValid:=admission.io.out.bits.faultAddressValid;faultAddress:=admission.io.out.bits.faultAddress}}
      phase:=Mux(stop || code=/=0.U,State.finish,State.checkTarget)
    }}
    is(State.checkTarget){
      if(capturedCoordinates){
        val offsets=request.offsets.get
        val sums=Seq(packet.x,packet.y,packet.z).zip(offsets).map{case(v,o)=>v.asSInt.pad(34)+&o.pad(34)}
        val badOffset=offsets.map(o=>o<(-8).S || o>7.S).reduce(_ || _) ||
          (binding.target===2.U && offsets.map(_=/=0.S).reduce(_ || _)) ||
          (binding.target=/=3.U && offsets(2)=/=0.S)
        val badCoordinate=sums.map(v=>v<0.S || v>=(BigInt(1)<<32).S(35.W)).reduce(_ || _)
        when(stop){phase:=State.finish}
          .elsewhen(admitted.image.view.target=/=binding.target){fail(6.U,7.U);phase:=State.finish}
          .elsewhen(badOffset || badCoordinate){fail(5.U,3.U);phase:=State.finish}
          .otherwise{packet.x:=sums(0).asUInt;packet.y:=sums(1).asUInt;packet.z:=sums(2).asUInt;phase:=State.offerFetch}
      }else{
        when(!stop && admitted.image.view.target=/=binding.target){fail(6.U,7.U);phase:=State.finish}
          .otherwise{phase:=Mux(stop,State.finish,State.offerFetch)}
      }
    }
    is(State.offerFetch){when(fetch.io.in.fire){fetchStarted:=true.B;phase:=State.waitFetch}}
    is(State.waitFetch){when(fetch.io.out.fire){
      fetched:=fetch.io.out.bits;fetchStarted:=false.B;val code=fetch.io.out.bits.sample.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=6.U;faultValid:=fetch.io.out.bits.sample.faultAddressValid;faultAddress:=fetch.io.out.bits.sample.faultAddress}}
      phase:=State.finish
    }}
    is(State.finish){
      assert(!offered && !pending && !admission.io.busy && !fetch.io.busy,"shader fetch completion escaped drain")
      result:=0.U.asTypeOf(result);result.identity:=request.identity;result.unit:=request.unit;result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultSource:=source;result.faultAddressValid:=faultValid
      result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.value:=TextureImageSwizzle(fetched.rgba,fetched.colorType,admitted.image.swizzle)(request.component);result.colorType:=fetched.colorType}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
}
