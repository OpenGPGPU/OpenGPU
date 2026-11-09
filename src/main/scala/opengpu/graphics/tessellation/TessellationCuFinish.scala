package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class TessellationCuWarpFinish(config:GpuConfig) extends Bundle {
  val warp=UInt(config.warpIdWidth.W);val normal=Bool()
}

/** Delay physical warp retirement until all of its patch invocations finish.
  * Faulted warps retire without success events; the owner cancels the patch.
  */
class TessellationCuFinish(config:GpuConfig) extends Module {
  require(config.warps>=2 && isPow2(config.warps) && config.lanes<=32)
  val io=IO(new Bundle {
    val launch=Flipped(Valid(new Bundle {val warp=UInt(config.warpIdWidth.W);val mask=UInt(config.lanes.W);val base=UInt(5.W)}))
    val in=Flipped(Valid(new TessellationCuWarpFinish(config)))
    val finish=Decoupled(UInt(5.W));val retired=Valid(UInt(config.warpIdWidth.W))
    val terminal=Input(Bool());val kernelStart=Input(Bool());val busy=Output(Bool())
  })
  private val owned=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val done=RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val remaining=Reg(Vec(config.warps,UInt(config.lanes.W)))
  private val masks=Reg(Vec(config.warps,UInt(config.lanes.W)));private val bases=Reg(Vec(config.warps,UInt(5.W)))
  private val offered=RegInit(false.B);private val offerWarp=Reg(UInt(config.warpIdWidth.W));private val offerLane=Reg(UInt(math.max(1,log2Ceil(config.lanes)).W))
  private val terminal=RegInit(false.B)
  io.busy:=owned.asUInt.orR || offered
  when(io.kernelStart){assert(!io.busy,"new patch kernel before finish drain");terminal:=false.B}
  when(io.terminal){terminal:=true.B}
  private val launchMask=(io.launch.bits.mask.pad(64) << io.launch.bits.base)(63,0)
  when(io.launch.valid){assert(!owned(io.launch.bits.warp),"patch warp reused before finish drain")
    assert(io.launch.bits.mask.orR && !launchMask(63,32).orR,"patch warp mapping must fit the patch")
    masks(io.launch.bits.warp):=io.launch.bits.mask;bases(io.launch.bits.warp):=io.launch.bits.base}
  when(io.in.valid){val w=io.in.bits.warp;assert(!owned(w),"duplicate patch warp finish")
    owned(w):=true.B;remaining(w):=masks(w);done(w):= !io.in.bits.normal || terminal || io.terminal}
  private val eligible=VecInit((0 until config.warps).map(i=>owned(i) && !done(i) && remaining(i).orR))
  private val nextWarp=PriorityEncoder(eligible.asUInt)
  when(!offered && !terminal && !io.terminal && eligible.asUInt.orR){
    offered:=true.B;offerWarp:=nextWarp;offerLane:=PriorityEncoder(remaining(nextWarp))}
  io.finish.valid:=offered;io.finish.bits:=bases(offerWarp)+offerLane
  when(io.finish.fire){val next=remaining(offerWarp) & ~UIntToOH(offerLane,config.lanes)
    remaining(offerWarp):=next;offered:=false.B;when(!next.orR){done(offerWarp):=true.B}}
  when(io.terminal){
    when(offered && !io.finish.fire){offered:=false.B}
    for(i<-0 until config.warps)when(owned(i)){done(i):=true.B}
  }
  private val complete=VecInit((0 until config.warps).map(i=>owned(i) && done(i)))
  private val w=PriorityEncoder(complete.asUInt)
  io.retired.valid:=complete.asUInt.orR;io.retired.bits:=w
  when(io.retired.valid){owned(w):=false.B;done(w):=false.B}
}
