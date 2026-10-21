package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

object ShaderExecutionFault {
  val none=0;val instructionLimit=1;val interrupted=2
}
class ShaderExecutionControl extends Bundle {
  val instructionLimit=UInt(32.W)
  val interrupt=Bool()
}
class WarpInstructionBudget(config: GpuConfig) extends Bundle {
  val warpId=UInt(config.warpIdWidth.W);val instructions=UInt(32.W)
}

/** Per-warp admitted-fetch limits and cooperative interruption. A request is
  * decided and registered before any physical offer. Interrupting cannot
  * withdraw an already owned fetch; its real reply drains unchanged. A stopped
  * warp receives a typed execution fault at its next fetch boundary, where the
  * frontend guarantees that its older instruction has completed. Zero disables
  * the quota, not interruption. The caller snapshots limits for each kernel.
  */
class WarpInstructionGuard(config: GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val request=Flipped(Decoupled(new InstructionFetchRequest(config)))
    val response=Decoupled(new InstructionFetchResponse(config))
    val physicalRequest=Decoupled(new InstructionFetchRequest(config))
    val physicalResponse=Flipped(Decoupled(new InstructionFetchResponse(config)))
    val launch=Flipped(Valid(new WarpInstructionBudget(config)))
    val finish=Flipped(Valid(UInt(config.warpIdWidth.W)))
    val kernelStart=Input(Bool());val interrupt=Input(Bool())
    val busy=Output(Bool())
  })
  private val live=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val bounded=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val remaining=Reg(Vec(config.warps,UInt(32.W)))
  private val pending=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val physicalPending=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val interrupted=RegInit(false.B)
  private class Decision extends Bundle {
    val request=new InstructionFetchRequest(config);val fault=UInt(2.W)
  }
  private val requests=Module(new Queue(new Decision,1))
  private val responseArbiter=Module(new RRArbiter(new InstructionFetchResponse(config),2))
  private val responses=Module(new Queue(new InstructionFetchResponse(config),2))
  io.response<>responses.io.deq;responses.io.enq<>responseArbiter.io.out
  private def selected[T<:Data](v: Vec[T],warp: UInt): T=if(config.warps==1)v(0)else v(warp)
  private val inputWarp=io.request.bits.warpId
  private val newLaunch=io.launch.valid && io.launch.bits.warpId===inputWarp
  private val quota=Mux(newLaunch,io.launch.bits.instructions,selected(remaining,inputWarp))
  private val limited=Mux(newLaunch,io.launch.bits.instructions=/=0.U,selected(bounded,inputWarp))
  private val stopped=interrupted || io.interrupt
  requests.io.enq.valid:=io.request.valid;io.request.ready:=requests.io.enq.ready
  requests.io.enq.bits.request:=io.request.bits
  requests.io.enq.bits.fault:=Mux(stopped,ShaderExecutionFault.interrupted.U,
    Mux(limited && quota===0.U,ShaderExecutionFault.instructionLimit.U,ShaderExecutionFault.none.U))
  private val offered=requests.io.deq
  private val synthetic=offered.bits.fault=/=0.U
  io.physicalRequest.valid:=offered.valid && !synthetic
  io.physicalRequest.bits:=offered.bits.request
  responseArbiter.io.in(0)<>io.physicalResponse
  responseArbiter.io.in(1).valid:=offered.valid && synthetic
  responseArbiter.io.in(1).bits:=0.U.asTypeOf(responseArbiter.io.in(1).bits)
  responseArbiter.io.in(1).bits.warpId:=offered.bits.request.warpId
  responseArbiter.io.in(1).bits.accessFault:=true.B
  responseArbiter.io.in(1).bits.executionFault:=offered.bits.fault
  offered.ready:=Mux(synthetic,responseArbiter.io.in(1).ready,io.physicalRequest.ready)
  when(io.launch.valid){
    assert(io.launch.bits.warpId<config.warps.U && !selected(live,io.launch.bits.warpId),"guard launch must allocate a free warp")
    selected(live,io.launch.bits.warpId):=true.B
    selected(bounded,io.launch.bits.warpId):=io.launch.bits.instructions=/=0.U
    selected(remaining,io.launch.bits.warpId):=io.launch.bits.instructions
  }
  when(io.request.fire){
    assert(inputWarp<config.warps.U && (selected(live,inputWarp) || newLaunch),"fetch must name a live warp")
    assert(!selected(pending,inputWarp),"one unresolved fetch per warp")
    selected(pending,inputWarp):=true.B
    when(requests.io.enq.bits.fault===0.U && limited){selected(remaining,inputWarp):=quota-1.U}
  }
  when(io.physicalRequest.fire){
    assert(!selected(physicalPending,io.physicalRequest.bits.warpId))
    selected(physicalPending,io.physicalRequest.bits.warpId):=true.B
  }
  when(io.physicalResponse.fire){
    val warp=io.physicalResponse.bits.warpId
    assert(warp<config.warps.U && (selected(physicalPending,warp) ||
      (io.physicalRequest.fire && io.physicalRequest.bits.warpId===warp)),"unowned physical instruction reply")
    assert(io.physicalResponse.bits.executionFault===0.U,"memory cannot inject an execution-stop cause")
    selected(physicalPending,warp):=false.B
  }
  when(io.response.fire){
    assert(io.response.bits.warpId<config.warps.U && selected(pending,io.response.bits.warpId))
    selected(pending,io.response.bits.warpId):=false.B
  }
  when(io.finish.valid){
    assert(io.finish.bits<config.warps.U && selected(live,io.finish.bits) && !selected(pending,io.finish.bits),
      "warp finish must follow its fetch reply")
    selected(live,io.finish.bits):=false.B
  }
  when(io.interrupt){interrupted:=true.B}
  io.busy:=pending.asUInt.orR || offered.valid || responses.io.deq.valid
  when(io.kernelStart){
    assert(!io.busy && !live.asUInt.orR && !io.request.valid,"new kernel cannot replace owned fetches")
    interrupted:=io.interrupt
  }
}
