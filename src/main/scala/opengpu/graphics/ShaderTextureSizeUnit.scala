package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class ShaderTextureSizeRequest extends Bundle {
  val resources=new ShaderTextureResources;val identity=new GraphicsWorkIdentity
  val unit=UInt(32.W);val lod=UInt(32.W);val component=UInt(2.W);val tag=UInt(32.W)
}
/** Resource-bound textureSize. Shader LOD is relative to GXI1 baseLevel.
  * Reads root, binding and selected image/level metadata only: sampler filtering,
  * coordinate tuples and texel data do not participate. The parent pins resource
  * publication and VM through retirement. ID0 owns root/binding; IDs4..7 own
  * image admission, with no alias or extra physical-credit requirement.
  */
class ShaderTextureSizeUnit(config: GpuConfig=GpuConfig(),stage: Int=0) extends Module {
  require(stage>=0 && stage<=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ShaderTextureSizeRequest));val out=Decoupled(new ShaderTextureFetchResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,readRoot,verifyRoot,readBinding,verifyBinding,offerImage,waitImage,checkTarget,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new ShaderTextureSizeRequest)
  private val root=Reg(new ShaderTextureRootRecord(if(stage==2) 2 else 1));private val binding=Reg(new ShaderTextureBindingRecord(if(stage==2) 2 else 1))
  private val image=Reg(new TextureImageBindingResult);private val result=Reg(new ShaderTextureFetchResult)
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(4.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val readBase=Reg(UInt(32.W));private val lineCount=Reg(UInt(2.W));private val issued=RegInit(0.U(2.W));private val readLine=Reg(UInt(1.W));private val malformed=RegInit(false.B)
  private val offered=RegInit(false.B);private val pending=RegInit(false.B);private val memory=Reg(new ComputeMemoryRequest(config,64,16))
  private val imageStarted=RegInit(false.B);private val noFill=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val reading=phase===State.readRoot || phase===State.readBinding
  private val readSource=Mux(phase===State.readRoot,1.U,2.U)
  private val admission=Module(new TextureImageBindingUnit(config,relativeLevel=true))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  admission.io.in.valid:=phase===State.offerImage;admission.io.in.bits:=0.U.asTypeOf(admission.io.in.bits)
  admission.io.in.bits.table.version:=1.U;admission.io.in.bits.table.identity:=request.identity
  admission.io.in.bits.table.base:=root.imagesBase;admission.io.in.bits.table.limit:=root.imagesLimit
  admission.io.in.bits.handle:=binding.imageHandle;admission.io.in.bits.level:=request.lod;admission.io.in.bits.tag:=request.tag
  admission.io.out.ready:=phase===State.waitImage
  admission.io.cancel:=imageStarted && (io.cancel || status===TextureImageResourceStatus.cancelled.U)
  admission.io.invalidate:=io.invalidate || stop || noFill
  private val arb=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
  arb.io.in(0).valid:=offered;arb.io.in(0).bits:=memory
  arb.io.in(1).valid:=admission.io.memoryRequest.valid;arb.io.in(1).bits:=admission.io.memoryRequest.bits
  arb.io.in(1).bits.transactionId:=Cat(1.U(2.W),admission.io.memoryRequest.bits.transactionId)
  admission.io.memoryRequest.ready:=arb.io.in(1).ready;io.memoryRequest<>arb.io.out
  private val tag=io.memoryResponse.bits.transactionId;private val ownReply=tag===0.U;private val imageReply=tag>=4.U && tag<=7.U
  admission.io.memoryResponse.valid:=io.memoryResponse.valid && imageReply;admission.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(ownReply,pending,imageReply && admission.io.memoryResponse.ready)
  private val ownerFire=io.memoryResponse.fire && ownReply
  when(arb.io.in(0).fire){offered:=false.B;pending:=true.B}
  private val rootFields=ShaderTextureRootLayout.fields(root);private val bindingFields=ShaderTextureBindingLayout.fields(binding)
  NativeTypedGraphicsLayout.check(root,rootFields,32);NativeTypedGraphicsLayout.check(binding,bindingFields,16)
  private val receive=ownerFire && !io.memoryResponse.bits.fault && !stop
  private val badRoot=NativeTypedGraphicsLayout.receive(rootFields,32,ShaderTextureRootLayout.magic,readLine,io.memoryResponse.bits.readData,receive && phase===State.readRoot)
  private val badBinding=NativeTypedGraphicsLayout.receive(bindingFields,16,ShaderTextureBindingLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readBinding)
  private def fail(code: UInt,where: UInt,addressValid: Bool=false.B,address: UInt=0.U): Unit={
    when(status===0.U && !io.cancel){status:=code;source:=where;faultValid:=addressValid;faultAddress:=address}
  }
  when(ownerFire){pending:=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,readSource,true.B,memory.address)}
    when(receive){malformed:=malformed || Mux(phase===State.readRoot,badRoot,badBinding)}
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
      assert(!offered && !pending && !admission.io.busy,"texture size abandoned metadata ownership")
      request:=io.in.bits;root:=0.U.asTypeOf(root);binding:=0.U.asTypeOf(binding);image:=0.U.asTypeOf(image)
      status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;imageStarted:=false.B;noFill:=io.invalidate;phase:=State.check
    }}
    is(State.check){
      when(stop){phase:=State.finish}.elsewhen(request.resources.version=/=1.U || !ShaderTextureResources.legal(request.resources) || request.component>=3.U){
        fail(1.U,1.U);phase:=State.finish
      }.otherwise{beginRead(request.resources.address,2,State.readRoot)}
    }
    is(State.readRoot){when((stop || issued===lineCount) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyRoot)}}
    is(State.verifyRoot){
      val end=root.bindingsBase.pad(64)+(root.bindingCount.pad(64)<<6)
      when(stop){phase:=State.finish}.elsewhen(malformed || root.identity.asUInt=/=request.identity.asUInt || root.stage=/=stage.U){fail(7.U,1.U);phase:=State.finish}
        .elsewhen(root.bindingCount===0.U || root.bindingCount>ShaderTextureResources.maxBindings.U || request.unit>=root.bindingCount ||
          !tableLegal(root.bindingsBase,root.bindingsLimit,64) || end>root.bindingsLimit ||
          !tableLegal(root.imagesBase,root.imagesLimit,128)){
          fail(1.U,1.U);phase:=State.finish
        }.otherwise{beginRead((root.bindingsBase.pad(64)+(request.unit.pad(64)<<6))(31,0),1,State.readBinding)}
    }
    is(State.readBinding){when((stop || issued===lineCount) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyBinding)}}
    is(State.verifyBinding){
      when(stop){phase:=State.finish}.elsewhen(malformed || binding.identity.asUInt=/=request.identity.asUInt || binding.stage=/=stage.U || binding.unit=/=request.unit){fail(7.U,2.U);phase:=State.finish}
        .elsewhen(binding.expectedType>2.U || binding.target>3.U){fail(1.U,2.U);phase:=State.finish}
        .otherwise{phase:=State.offerImage}
    }
    is(State.offerImage){when(admission.io.in.fire){imageStarted:=true.B;phase:=State.waitImage}}
    is(State.waitImage){when(admission.io.out.fire){
      image:=admission.io.out.bits;imageStarted:=false.B;val code=admission.io.out.bits.status
      when((code=/=0.U && code=/=4.U) || !stop){status:=code
        when(code=/=0.U){source:=4.U;faultValid:=admission.io.out.bits.faultAddressValid;faultAddress:=admission.io.out.bits.faultAddress}}
      phase:=Mux(stop || code=/=0.U,State.finish,State.checkTarget)
    }}
    is(State.checkTarget){
      when(!stop && (image.view.target=/=binding.target || TextureImageFormat.kind(image.format)=/=binding.expectedType)){fail(6.U,7.U)}
      phase:=State.finish
    }
    is(State.finish){
      assert(!offered && !pending && !admission.io.busy,"texture size completion escaped metadata drain")
      result:=0.U.asTypeOf(result);result.identity:=request.identity;result.unit:=request.unit;result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultSource:=source;result.faultAddressValid:=faultValid
      result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.value:=MuxLookup(request.component,0.U)(Seq(0.U->image.view.texWidth,1.U->image.view.texHeight,
        2.U->Mux(image.view.target===1.U,image.view.layers,Mux(image.view.target===3.U,image.view.texDepth,0.U))))}
      phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
}
