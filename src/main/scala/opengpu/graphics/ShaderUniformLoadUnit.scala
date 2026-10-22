package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.util.BlockPrefixAdder

class ShaderUniformLoadRequest extends Bundle {
  val resources=new ShaderUniformResources;val identity=new GraphicsWorkIdentity
  val block=UInt(32.W);val offset=UInt(32.W);val tag=UInt(32.W)
  /** Empty is canonically (0,0); otherwise forbid visible resource overlap
    * with this task's private input/output prefix. Padding line reads may
    * still cover adjacent bytes, as with an unaligned default-uniform tail.
    */
  val privateBase=UInt(32.W);val privateLimit=UInt(33.W)
}
class ShaderUniformLoadResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val block=UInt(32.W);val tag=UInt(32.W);val value=UInt(32.W)
  val status=UInt(3.W);val faultSource=UInt(2.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}
object ShaderUniformStatus {val ok=0;val invalid=1;val bounds=2;val memory=3;val cancelled=4;val identity=7}

/** Serial immutable uniform admission and raw component read. The parent pins
  * descriptors, data publication and VM context until the held result retires.
  * One physical read credit owns root, binding or data; every offered request
  * and accepted response drains on cancel. No descriptor/data cache is kept.
  */
class ShaderUniformLoadUnit(config: GpuConfig=GpuConfig(),stage: Int=0) extends Module {
  require(stage==0 || stage==1)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ShaderUniformLoadRequest));val out=Decoupled(new ShaderUniformLoadResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,readRoot,verifyRoot,readBinding,verifyBinding,address,checkAddress,readData,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new ShaderUniformLoadRequest)
  private val root=Reg(new ShaderUniformRootRecord);private val binding=Reg(new ShaderUniformBindingRecord)
  private val result=Reg(new ShaderUniformLoadResult);private val selectedBinding=Reg(UInt(5.W))
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(2.W));private val faultValid=RegInit(false.B)
  private val faultAddress=Reg(UInt(32.W));private val value=Reg(UInt(32.W));private val wideAddress=Reg(UInt(33.W))
  private val offered=RegInit(false.B);private val pending=RegInit(false.B);private val issued=RegInit(false.B)
  private val malformed=RegInit(false.B);private val readBase=Reg(UInt(32.W))
  private val memory=Reg(new ComputeMemoryRequest(config,64,1))
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val reading=phase===State.readRoot || phase===State.readBinding || phase===State.readData
  private val readSource=Mux(phase===State.readRoot,1.U,Mux(phase===State.readBinding,2.U,3.U))
  private val isDefault=request.block===ShaderUniformLimits.defaultBlock.U
  private val addressSum=Module(new BlockPrefixAdder(32))
  addressSum.io.a:=binding.base;addressSum.io.b:=request.offset;addressSum.io.carryIn:=false.B
  private val endSum=Module(new BlockPrefixAdder(33))
  endSum.io.a:=wideAddress;endSum.io.b:=4.U;endSum.io.carryIn:=false.B
  private val dataLine=Cat(wideAddress(31,6),0.U(6.W))
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete
  io.out.bits:=result;io.busy:=phase=/=State.idle
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=memory
  io.memoryResponse.ready:=pending && io.memoryResponse.bits.transactionId===0.U
  when(io.memoryRequest.fire){offered:=false.B;pending:=true.B}
  private val rootFields=ShaderUniformRootLayout.fields(root);private val bindingFields=ShaderUniformBindingLayout.fields(binding)
  NativeTypedGraphicsLayout.check(root,rootFields,16);NativeTypedGraphicsLayout.check(binding,bindingFields,16)
  private val receive=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val badRoot=NativeTypedGraphicsLayout.receive(rootFields,16,ShaderUniformRootLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readRoot)
  private val badBinding=NativeTypedGraphicsLayout.receive(bindingFields,16,ShaderUniformBindingLayout.magic,0.U,io.memoryResponse.bits.readData,receive && phase===State.readBinding)
  private def fail(code: UInt,where: UInt,valid: Bool=false.B,address: UInt=0.U): Unit={
    when(status===0.U && !io.cancel){status:=code;source:=where;faultValid:=valid;faultAddress:=address}
  }
  when(io.memoryResponse.fire){pending:=false.B
    when(io.memoryResponse.bits.fault){fail(ShaderUniformStatus.memory.U,readSource,true.B,Mux(phase===State.readData,wideAddress(31,0),memory.address))}
    when(receive){
      when(phase===State.readData){value:=(io.memoryResponse.bits.readData>>(wideAddress(5,0)<<3))(31,0)}
        .otherwise{malformed:=Mux(phase===State.readRoot,badRoot,badBinding)}
    }
  }
  when(reading && !stop && !issued && !offered && !pending){
    memory:=0.U.asTypeOf(memory);memory.address:=readBase;memory.sizeLog2:=6.U
    memory.byteMask:=Fill(64,1.U(1.W));memory.cachePolicy:=CachePolicy.uncached
    offered:=true.B;issued:=true.B
  }
  when(active && io.cancel && status===0.U){status:=ShaderUniformStatus.cancelled.U;source:=0.U}
  private def beginRead(base: UInt,next: State.Type): Unit={readBase:=base;issued:=false.B;malformed:=false.B;phase:=next}
  private def overlaps(base: UInt,end: UInt): Bool=request.privateLimit=/=0.U && base<request.privateLimit && end>request.privateBase
  private def lineLegal(base: UInt): Bool=base(5,0)===0.U && base.pad(33)+64.U<=(BigInt(1)<<32).U && !overlaps(base,base.pad(33)+64.U)
  private val mapping=VecInit((0 until 12).map(i=>if(i<6)root.mapLow(5*i+4,5*i)else root.mapHigh(5*(i-6)+4,5*(i-6))))
  private val mapsLegal=(0 until 12).map(i=>Mux(root.activeMask(i),mapping(i)<24.U,mapping(i)===0.U)).reduce(_ && _)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!offered && !pending,"uniform admission abandoned a memory credit")
      request:=io.in.bits;root:=0.U.asTypeOf(root);binding:=0.U.asTypeOf(binding)
      status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;value:=0.U;phase:=State.check
    }}
    is(State.check){
      val privateLegal=Mux(request.privateLimit===0.U,request.privateBase===0.U,
        request.privateLimit>request.privateBase && request.privateLimit<=(BigInt(1)<<32).U)
      when(stop){phase:=State.finish}
        .elsewhen(request.resources.version=/=1.U || !ShaderUniformResources.legal(request.resources) || !privateLegal ||
          !lineLegal(request.resources.address) || (!isDefault && request.block>=12.U)){fail(1.U,1.U);phase:=State.finish}
        .elsewhen(request.offset(1,0)=/=0.U){fail(2.U,3.U);phase:=State.finish}
        .otherwise{beginRead(request.resources.address,State.readRoot)}
    }
    is(State.readRoot){when((stop || issued) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyRoot)}}
    is(State.verifyRoot){
      val tableEnd=root.bindingsBase.pad(33)+1536.U
      val tableLegal=Mux(root.activeMask.orR,root.bindingsBase(5,0)===0.U && root.bindingsLimit<= (BigInt(1)<<32).U &&
        tableEnd<=root.bindingsLimit && (root.bindingsLimit-root.bindingsBase)(5,0)===0.U && !overlaps(root.bindingsBase,tableEnd),
        root.bindingsBase===0.U && root.bindingsLimit===0.U)
      val defaultLegal=Mux(root.defaultPresent,lineLegal(root.defaultView),root.defaultView===0.U)
      val block=request.block(3,0)
      when(stop){phase:=State.finish}
        .elsewhen(malformed || root.identity.asUInt=/=request.identity.asUInt || root.stage=/=stage.U){fail(7.U,1.U);phase:=State.finish}
        .elsewhen(!mapsLegal || !tableLegal || !defaultLegal || Mux(isDefault,!root.defaultPresent,!root.activeMask(block))){fail(1.U,1.U);phase:=State.finish}
        .otherwise{
          val selected=Mux(isDefault,31.U,mapping(block));selectedBinding:=selected
          beginRead(Mux(isDefault,root.defaultView,(root.bindingsBase.pad(33)+(selected<<6))(31,0)),State.readBinding)
        }
    }
    is(State.readBinding){when((stop || issued) && !offered && !pending){phase:=Mux(stop,State.finish,State.verifyBinding)}}
    is(State.verifyBinding){
      val aligned=Mux(isDefault,binding.base(1,0)===0.U,binding.base(7,0)===0.U)
      val maximum=Mux(isDefault,(ShaderUniformLimits.defaultComponents(stage)*4).U,ShaderUniformLimits.blockBytes.U)
      val legal=binding.readOnly && aligned && binding.limit>binding.base && binding.limit-binding.base<=maximum &&
        binding.allocationBase(5,0)===0.U && binding.allocationLimit(5,0)===0.U &&
        binding.allocationLimit<=(BigInt(1)<<32).U && binding.base>=binding.allocationBase && binding.limit<=binding.allocationLimit &&
        !overlaps(binding.base,binding.limit)
      when(stop){phase:=State.finish}
        .elsewhen(malformed || binding.identity.asUInt=/=request.identity.asUInt || binding.bindingIndex=/=selectedBinding || !binding.stageMask(stage)){
          fail(7.U,2.U);phase:=State.finish
        }.elsewhen(!legal){fail(1.U,2.U);phase:=State.finish}.otherwise{phase:=State.address}
    }
    is(State.address){wideAddress:=addressSum.io.sum;phase:=State.checkAddress}
    is(State.checkAddress){
      when(stop){phase:=State.finish}
        .elsewhen(wideAddress>=(BigInt(1)<<32).U || endSum.io.sum>binding.limit ||
          dataLine<binding.allocationBase || dataLine.pad(33)+64.U>binding.allocationLimit){fail(2.U,3.U);phase:=State.finish}
        .otherwise{beginRead(dataLine,State.readData)}
    }
    is(State.readData){when((stop || issued) && !offered && !pending){phase:=State.finish}}
    is(State.finish){
      assert(!offered && !pending,"uniform completion escaped memory drain")
      result:=0.U.asTypeOf(result);result.identity:=request.identity;result.block:=request.block;result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultSource:=source
      result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.value:=value};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
}
