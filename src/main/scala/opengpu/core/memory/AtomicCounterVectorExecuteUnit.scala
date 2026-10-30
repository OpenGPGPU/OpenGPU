package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}

/** Native CUSTOM-1 operations, with a per-lane counter index in vs1. These are
  * not a GLES instruction encoding and must remain gated until task ownership,
  * descriptor/atomic translation and fault retirement are connected.
  */
object AtomicCounterVectorOpcode {
  private def function(i:UInt,n:Int):Bool = i(6,0)==="h2b".U && i(31,26)===n.U && i(24,20)===0.U && i(14,12)===0.U
  def read(i:UInt):Bool=function(i,17)
  def increment(i:UInt):Bool=function(i,18)
  def decrement(i:UInt):Bool=function(i,19)
  def legal(i:UInt):Bool=read(i)||increment(i)||decrement(i)
}

class AtomicCounterVectorRequest(config:GpuConfig) extends VectorTextureRequest(config) {
  val sideEffectMask=UInt(config.lanes.W)
}

/** One lane is acknowledged at a time. A later fault does not undo prior
  * atomic side effects, and a faulting vector publishes no destination data.
  */
class AtomicCounterVectorExecuteUnit(config:GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AtomicCounterVectorRequest(config)))
    val commit=Decoupled(new VectorCommitRequest(config))
    val request=Decoupled(new AtomicCounterInstructionRequest(config))
    val response=Flipped(Decoupled(new AtomicCounterResult))
    val enabled=Input(Bool());val kernelStart=Input(Bool());val cancel=Input(Bool())
    val busy=Output(Bool());val status=Output(UInt(3.W))
    val faultAddressValid=Output(Bool());val faultAddress=Output(UInt(32.W));val faultMask=Output(UInt(config.lanes.W));val pageFault=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,next,offer,waitResult,commit=Value}
  private val state=RegInit(State.idle)
  private val instruction=Reg(UInt(32.W));private val warp=Reg(UInt(config.warpIdWidth.W));private val pc=Reg(UInt(32.W))
  private val active=Reg(UInt(config.lanes.W));private val remaining=Reg(UInt(config.lanes.W))
  private val lane=Reg(UInt(math.max(1,log2Ceil(config.lanes)).W))
  private val indices=Reg(Vec(config.lanes,UInt(32.W)));private val data=Reg(Vec(config.lanes,UInt(32.W)))
  private val status=RegInit(0.U(3.W));private val faultValid=RegInit(false.B);private val faultAddress=RegInit(0.U(32.W))
  private val pageFault=RegInit(false.B);private val faultMask=RegInit(0.U(config.lanes.W));private val owned=RegInit(false.B)
  private val read=AtomicCounterVectorOpcode.read(instruction)
  private val increment=AtomicCounterVectorOpcode.increment(instruction)
  private val stopped=status=/=0.U || io.cancel
  io.in.ready:=state===State.idle && !io.kernelStart
  io.busy:=state=/=State.idle;io.status:=status;io.faultAddressValid:=faultValid
  io.faultAddress:=Mux(faultValid,faultAddress,0.U);io.faultMask:=faultMask;io.pageFault:=pageFault
  io.request.valid:=state===State.offer
  io.request.bits.counter:=indices(lane);io.request.bits.warpId:=warp;io.request.bits.tag:=lane
  io.request.bits.operation:=Mux(read,AtomicCounterOperation.read.U,
    Mux(increment,AtomicCounterOperation.increment.U,AtomicCounterOperation.decrement.U))
  io.response.ready:=state===State.waitResult
  io.commit.valid:=state===State.commit;io.commit.bits:=0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId:=warp;io.commit.bits.writeback.vd:=instruction(11,7)
  io.commit.bits.writeback.data:=Mux(status===0.U,data,0.U.asTypeOf(data))
  io.commit.bits.writesVd:=status===0.U;io.commit.bits.pc:=pc;io.commit.bits.warpActiveMask:=active
  when(io.kernelStart){assert(state===State.idle,"counter launch changed before instruction drain")}
  when(state=/=State.idle && state=/=State.commit && io.cancel && status===0.U){status:=AtomicCounterStatus.cancelled.U}
  switch(state) {
    is(State.idle){when(io.in.fire){val q=io.in.bits;val d=q.issued.decode
      assert(d.warpId<config.warps.U);instruction:=d.instruction;warp:=d.warpId;pc:=d.pc;active:=d.activeMask
      val execution=q.executionMask & d.activeMask
      remaining:=Mux(AtomicCounterVectorOpcode.read(d.instruction),execution,execution & q.sideEffectMask)
      faultMask:=execution
      indices:=q.issued.vs1Data;data:=q.issued.oldVdData
      status:=Mux(!io.enabled,AtomicCounterStatus.invalid.U,Mux(io.cancel,AtomicCounterStatus.cancelled.U,0.U))
      faultValid:=false.B;faultAddress:=0.U;pageFault:=false.B;owned:=false.B;state:=State.check
    }}
    is(State.check){when(stopped){state:=State.commit}
      .elsewhen(!AtomicCounterVectorOpcode.legal(instruction)){status:=AtomicCounterStatus.invalid.U;state:=State.commit}
      .otherwise{state:=State.next}}
    is(State.next){when(stopped || !remaining.orR){state:=State.commit}
      .otherwise{lane:=PriorityEncoder(remaining);state:=State.offer}}
    is(State.offer){when(io.request.fire){owned:=true.B;state:=State.waitResult}}
    is(State.waitResult){when(io.response.fire){val r=io.response.bits;assert(owned && r.tag===lane)
      owned:=false.B
      when(r.status=/=0.U && r.status=/=AtomicCounterStatus.cancelled.U){
        status:=r.status;faultValid:=r.faultAddressValid;faultAddress:=r.faultAddress;faultMask:=UIntToOH(lane,config.lanes);pageFault:=r.pageFault
      }.elsewhen(status===0.U && !io.cancel){status:=r.status}
      when(r.status===0.U && !stopped){data(lane):=r.value;remaining:=remaining & ~UIntToOH(lane,config.lanes);state:=State.next}
        .otherwise{state:=State.commit}
    }}
    is(State.commit){assert(!owned,"counter instruction escaped an accepted request");when(io.commit.fire){state:=State.idle}}
  }
}
