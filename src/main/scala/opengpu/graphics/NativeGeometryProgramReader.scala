package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class NativeGeometryProgramRequest extends Bundle {
  val resources=new NativeGeometryResources;val identity=new GraphicsWorkIdentity
  val topology=UInt(4.W);val vertexVaryingSlots=UInt(5.W);val fragmentVaryingSlots=UInt(5.W)
  val tag=UInt(32.W)
}
class NativeGeometryProgramResult extends Bundle {
  val identity=new GraphicsWorkIdentity;val tag=UInt(32.W);val program=new NativeGeometryProgram
  val status=UInt(3.W);val readBytes=UInt(11.W);val faultAddressValid=Bool();val faultAddress=UInt(32.W)
}

/** A separately enabled static GNG1 snapshot. This never launches a shader and
  * does not prove instruction/data bytes or raw-caller immutability. The draw
  * owner must preflight every referenced GNG before the first VS and retain
  * publication/translation ownership through all child and memory drain.
  */
class NativeGeometryProgramReader(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    geometryAvailable: Boolean=false) extends Module {
  require(geometryAvailable,"native GS metadata reader requires its explicit provider")
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new NativeGeometryProgramRequest));val out=Decoupled(new NativeGeometryProgramResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,check,fetch,verify,finish,done=Value}
  private val phase=RegInit(Phase.idle);private val binding=Reg(new NativeGeometryProgramRequest)
  private val program=Reg(new NativeGeometryProgram);private val result=Reg(new NativeGeometryProgramResult)
  private val status=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val malformed=RegInit(false.B);private val nextLine=RegInit(0.U(5.W));private val reads=RegInit(0.U(11.W))
  private val offered=RegInit(false.B);private val request=Reg(new ComputeMemoryRequest(config,64,4))
  private val requestLine=Reg(UInt(4.W));private val live=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val lineForTag=Reg(Vec(4,UInt(4.W)))
  private val stop=status=/=0.U || io.cancel;private val drained= !offered && !live.asUInt.orR
  private val active=phase=/=Phase.idle && phase=/=Phase.done
  io.in.ready:=phase===Phase.idle && !io.cancel;io.busy:=phase=/=Phase.idle
  io.out.valid:=phase===Phase.done;io.out.bits:=result
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=request
  io.memoryResponse.ready:=phase===Phase.fetch && live(io.memoryResponse.bits.transactionId)
  when(io.memoryRequest.fire){
    offered:=false.B;live(request.transactionId):=true.B;lineForTag(request.transactionId):=requestLine
    nextLine:=nextLine+1.U;reads:=reads+64.U
  }
  private val fields=NativeGeometryProgramLayout.fields(program)
  NativeTypedGraphicsLayout.check(program,fields,NativeGeometryProgramLayout.words)
  private val replyLine=lineForTag(io.memoryResponse.bits.transactionId)
  private val writeReply=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val bad=NativeTypedGraphicsLayout.receive(fields,NativeGeometryProgramLayout.words,
    NativeGeometryProgramLayout.magic,replyLine,io.memoryResponse.bits.readData,writeReply)
  when(io.memoryResponse.fire){
    live(io.memoryResponse.bits.transactionId):=false.B
    when(io.memoryResponse.bits.fault && status===0.U && !io.cancel){
      status:=3.U;faultValid:=true.B;faultAddress:=binding.resources.base+(replyLine<<6)
    }
    when(writeReply && bad){malformed:=true.B}
  }
  when(active && io.cancel && status===0.U){status:=4.U;faultValid:=false.B;faultAddress:=0.U}
  when(phase===Phase.fetch && !stop && !offered && nextLine<16.U && !live.asUInt.andR){
    request:=0.U.asTypeOf(request);request.address:=binding.resources.base+(nextLine<<6)
    request.sizeLog2:=6.U;request.byteMask:=Fill(64,1.U(1.W));request.cachePolicy:=CachePolicy.uncached
    request.transactionId:=PriorityEncoder(~live.asUInt);requestLine:=nextLine(3,0);offered:=true.B
  }
  switch(phase){
    is(Phase.idle){when(io.in.fire){
      assert(drained,"GNG admission abandoned a memory credit")
      binding:=io.in.bits;program:=0.U.asTypeOf(program);status:=0.U;faultValid:=false.B;faultAddress:=0.U
      malformed:=false.B;nextLine:=0.U;reads:=0.U;phase:=Phase.check
    }}
    is(Phase.check){
      when(stop){phase:=Phase.finish}
        .elsewhen(!NativeGeometryResources.enabled(binding.resources)){status:=2.U;phase:=Phase.finish}
        .otherwise{phase:=Phase.fetch}
    }
    is(Phase.fetch){when(drained && (stop || nextLine===16.U)){phase:=Mux(stop,Phase.finish,Phase.verify)}}
    is(Phase.verify){
      when(!stop){
        when(program.identity.asUInt=/=binding.identity.asUInt){status:=7.U}
          .elsewhen(malformed || !NativeGeometryProgramLayout.legal(program,p) ||
            !NativeGeometryProgramLayout.topologyMatches(program,binding.topology) ||
            program.vertexVaryingSlots=/=binding.vertexVaryingSlots ||
            program.fragmentVaryingSlots=/=binding.fragmentVaryingSlots){status:=1.U}
      }
      phase:=Phase.finish
    }
    is(Phase.finish){
      assert(drained,"GNG completion escaped memory drain")
      result:=0.U.asTypeOf(result);result.identity:=binding.identity;result.tag:=binding.tag
      result.status:=Mux(io.cancel && status===0.U,4.U,status);result.readBytes:=reads
      result.faultAddressValid:=faultValid;result.faultAddress:=Mux(faultValid,faultAddress,0.U)
      when(!stop){result.program:=program};phase:=Phase.done
    }
    is(Phase.done){when(io.out.fire){phase:=Phase.idle}}
  }
}
