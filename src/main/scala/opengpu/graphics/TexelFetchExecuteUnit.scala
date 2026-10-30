package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Ordinary one-register scalar/vector retirement for typed component fetch.
  * Inactive vector lanes retain oldVd. Any active-lane failure suppresses the
  * entire destination write. The first fault remains owned through completion.
  */
class TexelFetchExecuteUnit(config: GpuConfig=GpuConfig(),stage: Int=0) extends Module {
  require(stage>=0 && stage<=2)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ScalarIssuedInstruction(config)));val vectorIn=Flipped(Decoupled(new VectorTextureRequest(config)))
    val commit=Decoupled(new ScalarCommitRequest(config));val vectorCommit=Decoupled(new VectorCommitRequest(config))
    val resources=Input(new ShaderTextureResources);val identity=Input(new GraphicsWorkIdentity)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val cancel=Input(Bool());val invalidate=Input(Bool());val busy=Output(Bool())
    val status=Output(UInt(3.W));val faultAddressValid=Output(Bool());val faultAddress=Output(UInt(32.W))
  })
  private object State extends ChiselEnum {val idle,nextLane,offer,waitResult,commit=Value}
  private val phase=RegInit(State.idle);private val vector=RegInit(false.B);private val preferVector=RegInit(true.B)
  private val resources=Reg(new ShaderTextureResources);private val identity=Reg(new GraphicsWorkIdentity)
  private val warp=Reg(UInt(config.warpIdWidth.W));private val rd=Reg(UInt(5.W));private val pc=Reg(UInt(32.W));private val active=Reg(UInt(config.lanes.W))
  private val mask=Reg(UInt(config.lanes.W));private val component=Reg(UInt(2.W));private val lane=RegInit(0.U(math.max(1,log2Ceil(config.lanes)).W))
  private val packets=Reg(Vec(config.lanes,UInt(32.W)));private val units=Reg(Vec(config.lanes,UInt(32.W)));private val data=Reg(Vec(config.lanes,UInt(32.W)))
  private val scalarData=Reg(UInt(32.W));private val status=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=Reg(UInt(32.W))
  private val started=RegInit(false.B);private val noFill=RegInit(false.B)
  private val selectVector=io.vectorIn.valid && (!io.in.valid || preferVector)
  io.in.ready:=phase===State.idle && !io.cancel && !selectVector;io.vectorIn.ready:=phase===State.idle && !io.cancel && selectVector
  io.busy:=phase=/=State.idle;io.status:=status;io.faultAddressValid:=faultValid;io.faultAddress:=faultAddress
  private val fetch=Module(new ShaderTextureFetchUnit(config,stageWidth=if(stage==2) 2 else 1));io.memoryRequest<>fetch.io.memoryRequest;fetch.io.memoryResponse<>io.memoryResponse
  fetch.io.in.valid:=phase===State.offer;fetch.io.in.bits.resources:=resources;fetch.io.in.bits.identity:=identity;fetch.io.in.bits.stage:=stage.U
  fetch.io.in.bits.unit:=units(lane);fetch.io.in.bits.packet:=packets(lane);fetch.io.in.bits.component:=component;fetch.io.in.bits.tag:=lane
  fetch.io.out.ready:=phase===State.waitResult;fetch.io.cancel:=started && (io.cancel || status===4.U)
  fetch.io.invalidate:=io.invalidate || noFill || status=/=0.U
  when(phase=/=State.idle && phase=/=State.commit && io.cancel && status===0.U){status:=4.U}
  when(phase=/=State.idle && io.invalidate){noFill:=true.B}
  io.commit.valid:=phase===State.commit && !vector;io.commit.bits:=0.U.asTypeOf(io.commit.bits)
  io.commit.bits.warpId:=warp;io.commit.bits.nextPc:=pc+4.U;io.commit.bits.activeMask:=active
  io.commit.bits.rd:=rd;io.commit.bits.writeRd:=status===0.U;io.commit.bits.data:=Mux(status===0.U,scalarData,0.U)
  io.vectorCommit.valid:=phase===State.commit && vector;io.vectorCommit.bits:=0.U.asTypeOf(io.vectorCommit.bits)
  io.vectorCommit.bits.writeback.warpId:=warp;io.vectorCommit.bits.writeback.vd:=rd
  io.vectorCommit.bits.writeback.data:=Mux(status===0.U,data,0.U.asTypeOf(data))
  io.vectorCommit.bits.writesVd:=status===0.U;io.vectorCommit.bits.pc:=pc;io.vectorCommit.bits.warpActiveMask:=active
  private def next(): Unit={when(!vector || lane===(config.lanes-1).U){phase:=State.commit}.otherwise{lane:=lane+1.U;phase:=State.nextLane}}
  switch(phase){
    is(State.idle){when(io.in.fire || io.vectorIn.fire){
      assert(!fetch.io.busy,"typed execution abandoned a texel owner")
      resources:=io.resources;identity:=io.identity;status:=0.U;faultValid:=false.B;faultAddress:=0.U
      started:=false.B;noFill:=io.invalidate;lane:=0.U;phase:=State.nextLane;scalarData:=0.U
      when(io.vectorIn.fire){val d=io.vectorIn.bits.issued.decode
        assert(ShaderTextureFetchOpcode.vector(d.instruction))
        vector:=true.B;warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask
        mask:=io.vectorIn.bits.executionMask;component:=d.instruction(13,12)
        packets:=io.vectorIn.bits.issued.vs1Data;units:=io.vectorIn.bits.issued.vs2Data;data:=io.vectorIn.bits.issued.oldVdData;preferVector:=false.B
      }.otherwise{val d=io.in.bits.decode
        assert(ShaderTextureFetchOpcode.scalar(d.instruction))
        vector:=false.B;warp:=d.warpId;rd:=d.instruction(11,7);pc:=d.pc;active:=d.activeMask
        mask:=1.U;component:=d.instruction(13,12);packets:=0.U.asTypeOf(packets);units:=0.U.asTypeOf(units);data:=0.U.asTypeOf(data)
        packets(0):=io.in.bits.rs1Data;units(0):=io.in.bits.rs2Data;preferVector:=true.B
      }
    }}
    is(State.nextLane){when(status=/=0.U || io.cancel){phase:=State.commit}
      .elsewhen(mask(lane)){phase:=State.offer}.otherwise{next()}}
    is(State.offer){when(fetch.io.in.fire){started:=true.B;phase:=State.waitResult}}
    is(State.waitResult){when(fetch.io.out.fire){
      started:=false.B;val code=fetch.io.out.bits.status
      assert(fetch.io.out.bits.tag===lane && fetch.io.out.bits.unit===units(lane))
      when((code=/=0.U && code=/=4.U) || (status===0.U && !io.cancel)){status:=code
        when(code=/=0.U){faultValid:=fetch.io.out.bits.faultAddressValid;faultAddress:=fetch.io.out.bits.faultAddress}}
      when(code===0.U && status===0.U && !io.cancel){when(vector){data(lane):=fetch.io.out.bits.value}.otherwise{scalarData:=fetch.io.out.bits.value}}
      when(code=/=0.U || status=/=0.U || io.cancel){phase:=State.commit}.otherwise{next()}
    }}
    is(State.commit){when(io.commit.fire || io.vectorCommit.fire){assert(!fetch.io.busy,"typed retirement escaped fetch drain");phase:=State.idle}}
  }
}
