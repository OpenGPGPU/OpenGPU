package opengpu.core.trap

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Finish-on-trap ownership for one CU's one-wide warp finish port. Capture
  * the arbitrated event before offering it publicly; a stalled trap remains
  * stable while peer warps cease. A simultaneous normal finish takes priority
  * and every acknowledged trap retains a private finish slot until that port
  * is free. The registered slot cuts public backpressure off the CU finish
  * path. A faulting warp finishes only after its public trap is accepted.
  */
class ShaderTrapRetirement(config: GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new CoreTrapEvent(config)))
    val out=Decoupled(new CoreTrapEvent(config))
    val normalFinish=Flipped(Valid(UInt(config.warpIdWidth.W)))
    val finish=Valid(UInt(config.warpIdWidth.W))
    val kernelStart=Input(Bool());val failed=Output(Bool())
  })
  private val offers=Module(new Queue(new CoreTrapEvent(config),1))
  offers.io.enq<>io.in
  private val pending=RegInit(false.B);private val warp=Reg(UInt(config.warpIdWidth.W))
  private val failed=RegInit(false.B);io.failed:=failed
  io.out.valid:=offers.io.deq.valid && !pending;io.out.bits:=offers.io.deq.bits
  offers.io.deq.ready:=io.out.ready && !pending
  io.finish:=io.normalFinish
  when(pending && !io.normalFinish.valid){io.finish.valid:=true.B;io.finish.bits:=warp;pending:=false.B}
  when(io.out.fire){
    failed:=true.B
    when(io.normalFinish.valid){assert(io.normalFinish.bits=/=io.out.bits.warpId,"one warp cannot cease and fault simultaneously")}
    pending:=true.B;warp:=io.out.bits.warpId
  }
  when(io.kernelStart){
    assert(!pending && !offers.io.deq.valid && !io.in.valid,"new kernel cannot replace an owned trap")
    failed:=false.B
  }
}
