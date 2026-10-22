package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Encodings for raw uniform words: rs1/vs1 is the logical block,
  * rs2/vs2 the byte offset. Vector vm has its ordinary RVV meaning.
  * Decoder admission requires an accepted task's uniform-provider permission.
  */
object ShaderUniformOpcode {
  def scalar(i: UInt): Bool=i(6,0)==="h0b".U && i(31,25)===7.U && i(14,12)===0.U
  def vector(i: UInt): Bool=i(6,0)==="h2b".U && i(31,26)===7.U && i(14,12)===0.U
}

/** One owned uniform instruction and ordinary scalar/vector retirement.
  * Equal active-lane block/offset pairs share one checked raw-word load.
  * Inactive lanes retain oldVd; any failure suppresses the whole writeback.
  * This adapter has no persistent descriptor or data cache.
  */
class ShaderUniformExecuteUnit(config: GpuConfig=GpuConfig(),stage: Int=0) extends Module {
  require(stage==0 || stage==1)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val vectorIn=Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit=Decoupled(new ScalarCommitRequest(config))
    val vectorCommit=Decoupled(new VectorCommitRequest(config))
    val resources=Input(new ShaderUniformResources);val identity=Input(new GraphicsWorkIdentity)
    val privateBase=Input(UInt(32.W));val privateLimit=Input(UInt(33.W))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val busy=Output(Bool());val status=Output(UInt(3.W))
    val faultSource=Output(UInt(2.W));val faultAddressValid=Output(Bool());val faultAddress=Output(UInt(32.W))
  })
  private object State extends ChiselEnum {val idle,next,offer,waitResult,commit=Value}
  private val phase=RegInit(State.idle);private val vector=RegInit(false.B);private val preferVector=RegInit(true.B)
  private val request=Reg(new ShaderUniformLoadRequest)
  private val warp=Reg(UInt(config.warpIdWidth.W));private val rd=Reg(UInt(5.W));private val pc=Reg(UInt(32.W))
  private val active=Reg(UInt(config.lanes.W));private val remaining=Reg(UInt(config.lanes.W))
  private val lane=Reg(UInt(math.max(1,log2Ceil(config.lanes)).W))
  private val blocks=Reg(Vec(config.lanes,UInt(32.W)));private val offsets=Reg(Vec(config.lanes,UInt(32.W)))
  private val data=Reg(Vec(config.lanes,UInt(32.W)));private val scalarData=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val source=RegInit(0.U(2.W))
  private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W));private val started=RegInit(false.B)
  private val load=Module(new ShaderUniformLoadUnit(config,stage))
  io.memoryRequest<>load.io.memoryRequest;load.io.memoryResponse<>io.memoryResponse
  private val chooseVector=io.vectorIn.valid && (!io.in.valid || preferVector)
  io.in.ready:=phase===State.idle && !io.cancel && !chooseVector
  io.vectorIn.ready:=phase===State.idle && !io.cancel && chooseVector
  io.busy:=phase=/=State.idle;io.status:=status;io.faultSource:=source
  io.faultAddressValid:=faultValid;io.faultAddress:=Mux(faultValid,faultAddress,0.U)
  load.io.in.valid:=phase===State.offer;load.io.in.bits:=request
  load.io.in.bits.block:=blocks(lane);load.io.in.bits.offset:=offsets(lane);load.io.in.bits.tag:=lane
  load.io.out.ready:=phase===State.waitResult
  load.io.cancel:=started && (io.cancel || status===ShaderUniformStatus.cancelled.U)
  private val matches=VecInit((0 until config.lanes).map(i=>remaining(i) && blocks(i)===blocks(lane) && offsets(i)===offsets(lane))).asUInt
  when(phase=/=State.idle && phase=/=State.commit && io.cancel && status===0.U){status:=ShaderUniformStatus.cancelled.U}
  io.commit.valid:=phase===State.commit && !vector;io.commit.bits:=0.U.asTypeOf(io.commit.bits)
  io.commit.bits.warpId:=warp;io.commit.bits.nextPc:=pc+4.U;io.commit.bits.activeMask:=active
  io.commit.bits.rd:=rd;io.commit.bits.writeRd:=status===0.U;io.commit.bits.data:=Mux(status===0.U,scalarData,0.U)
  io.vectorCommit.valid:=phase===State.commit && vector;io.vectorCommit.bits:=0.U.asTypeOf(io.vectorCommit.bits)
  io.vectorCommit.bits.writeback.warpId:=warp;io.vectorCommit.bits.writeback.vd:=rd
  io.vectorCommit.bits.writeback.data:=Mux(status===0.U,data,0.U.asTypeOf(data))
  io.vectorCommit.bits.writesVd:=status===0.U;io.vectorCommit.bits.pc:=pc;io.vectorCommit.bits.warpActiveMask:=active
  switch(phase){
    is(State.idle){when(io.in.fire || io.vectorIn.fire){
      assert(!load.io.busy,"uniform instruction abandoned a load owner")
      request:=0.U.asTypeOf(request);request.resources:=io.resources;request.identity:=io.identity
      request.privateBase:=io.privateBase;request.privateLimit:=io.privateLimit
      status:=0.U;source:=0.U;faultValid:=false.B;faultAddress:=0.U;scalarData:=0.U;started:=false.B;phase:=State.next
      when(io.vectorIn.fire){val d=io.vectorIn.bits.issued.decode
        assert(ShaderUniformOpcode.vector(d.instruction))
        vector:=true.B;warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask
        remaining:=io.vectorIn.bits.executionMask;blocks:=io.vectorIn.bits.issued.vs1Data;offsets:=io.vectorIn.bits.issued.vs2Data
        data:=io.vectorIn.bits.issued.oldVdData;preferVector:=false.B
      }.otherwise{val d=io.in.bits.decode
        assert(ShaderUniformOpcode.scalar(d.instruction))
        vector:=false.B;warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask
        remaining:=1.U;blocks:=0.U.asTypeOf(blocks);offsets:=0.U.asTypeOf(offsets);data:=0.U.asTypeOf(data)
        blocks(0):=io.in.bits.rs1Data;offsets(0):=io.in.bits.rs2Data;preferVector:=true.B
      }
    }}
    is(State.next){when(status=/=0.U || io.cancel || !remaining.orR){phase:=State.commit}
      .otherwise{lane:=PriorityEncoder(remaining);phase:=State.offer}}
    // An offered child request must first be accepted, then canceled/drained.
    is(State.offer){when(load.io.in.fire){started:=true.B;phase:=State.waitResult}}
    is(State.waitResult){when(load.io.out.fire){
      started:=false.B;val result=load.io.out.bits;val code=result.status
      assert(result.tag===lane && result.block===blocks(lane) && result.identity.asUInt===request.identity.asUInt)
      when(code=/=0.U && code=/=ShaderUniformStatus.cancelled.U){
        status:=code;source:=result.faultSource;faultValid:=result.faultAddressValid;faultAddress:=result.faultAddress
      }.elsewhen(status===0.U && !io.cancel){status:=code}
      when(code===0.U && status===0.U && !io.cancel){
        when(vector){for(i<-0 until config.lanes){when(matches(i)){data(i):=result.value}}}
          .otherwise{scalarData:=result.value}
        remaining:=remaining & ~matches;phase:=State.next
      }.otherwise{phase:=State.commit}
    }}
    is(State.commit){when(io.commit.fire || io.vectorCommit.fire){
      assert(!load.io.busy && !io.memoryRequest.valid,"uniform writeback escaped memory drain")
      phase:=State.idle
    }}
  }
}
