package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.SimtPath
import opengpu.core.trap.{CoreTrapEvent,TrapCause}

/** Retain a CU's blocked warp paths while the patch context resolves barriers.
  * The owner supplies captured warp bases and forwards terminal context errors.
  */
class TessellationCuBarrier(config:GpuConfig) extends Module {
  require(config.warps>=2 && isPow2(config.warps) && config.lanes<=32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new SimtPath(config)));val bases=Input(Vec(config.warps,UInt(5.W)))
    val park=Decoupled(new TessellationWarpPark(config.warps,config.lanes))
    val resume=Flipped(Decoupled(new TessellationWarpResume(config.warps)))
    val out=Decoupled(new SimtPath(config));val trap=Decoupled(new CoreTrapEvent(config))
    val cancel=Input(Bool());val terminal=Input(Bool());val kernelStart=Input(Bool());val memoryIdle=Input(Bool())
    val busy=Output(Bool());val waiting=Output(UInt(config.warps.W))
  })
  private val owned=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val sent=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val done=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val status=Reg(Vec(config.warps,UInt(2.W)));private val paths=Reg(Vec(config.warps,new SimtPath(config)))
  private val bases=Reg(Vec(config.warps,UInt(5.W)))
  private val terminal=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val offered=RegInit(false.B);private val offerWarp=Reg(UInt(config.warpIdWidth.W))
  private val success=Module(new Queue(new SimtPath(config),1));private val failure=Module(new Queue(new CoreTrapEvent(config),1))
  io.out<>success.io.deq;io.trap<>failure.io.deq
  io.busy:=owned.asUInt.orR || offered || success.io.deq.valid || failure.io.deq.valid
  io.waiting:=owned.asUInt | Mux(success.io.deq.valid,UIntToOH(success.io.deq.bits.warpId,config.warps),0.U) |
    Mux(failure.io.deq.valid,UIntToOH(failure.io.deq.bits.warpId,config.warps),0.U)
  when(io.kernelStart){assert(!io.busy,"new patch kernel before barrier drain");terminal:=false.B;cancelled:=false.B}
  when(io.cancel){cancelled:=true.B};when(io.terminal){terminal:=true.B}
  private val inWarp=io.in.bits.warpId
  io.in.ready:= !io.kernelStart && !owned(inWarp)
  when(io.in.fire){owned(inWarp):=true.B;sent(inWarp):=false.B;paths(inWarp):=io.in.bits;bases(inWarp):=io.bases(inWarp)
    done(inWarp):=terminal || io.terminal;status(inWarp):=2.U}
  private val candidates=VecInit((0 until config.warps).map(i=>owned(i) && !sent(i) && !done(i)))
  when(!offered && !terminal && !io.terminal && io.memoryIdle && candidates.asUInt.orR){offered:=true.B;offerWarp:=PriorityEncoder(candidates.asUInt)}
  io.park.valid:=offered;io.park.bits.warp:=offerWarp;io.park.bits.base:=bases(offerWarp)
  io.park.bits.lanes:=paths(offerWarp).activeMask;io.park.bits.barrierId:=paths(offerWarp).pc
  when(io.park.fire){sent(offerWarp):=true.B;offered:=false.B}
  when(io.terminal){
    when(offered && !io.park.fire){offered:=false.B}
    for(i<-0 until config.warps){when(owned(i) && !sent(i) && !(io.park.fire && offerWarp===i.U)){
      done(i):=true.B;status(i):=2.U}}
  }
  private val rw=io.resume.bits.warp
  io.resume.ready:=owned(rw) && sent(rw) && !done(rw)
  when(io.resume.fire){done(rw):=true.B
    status(rw):=Mux(cancelled || io.cancel || terminal || io.terminal,2.U,
      Mux(io.resume.bits.barrierId=/=paths(rw).pc,1.U,io.resume.bits.status))}
  private val completed=VecInit((0 until config.warps).map(i=>owned(i) && done(i)))
  private val w=PriorityEncoder(completed.asUInt);private val p=paths(w)
  success.io.enq.valid:=completed.asUInt.orR && status(w)===0.U;success.io.enq.bits:=p
  failure.io.enq.valid:=completed.asUInt.orR && status(w)=/=0.U
  failure.io.enq.bits:=0.U.asTypeOf(failure.io.enq.bits)
  failure.io.enq.bits.warpId:=w;failure.io.enq.bits.pc:=p.pc-4.U;failure.io.enq.bits.activeMask:=p.activeMask
  failure.io.enq.bits.cause:=Mux(status(w)===2.U,TrapCause.shaderInterrupted.U,TrapCause.illegalInstruction.U)
  failure.io.enq.bits.tval:=Mux(status(w)===2.U,p.pc-4.U,"h0000000b".U)
  failure.io.enq.bits.laneFaultMask:=p.activeMask
  when(success.io.enq.fire || failure.io.enq.fire){owned(w):=false.B;sent(w):=false.B;done(w):=false.B}
}
