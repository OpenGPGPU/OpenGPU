package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Translate each global AMO before the shared L2 serialization point. A walk
  * requires both read and write permission; the PTW checks accessed/dirty bits.
  * No translation cache is retained across tasks or address-space changes.
  */
class GlobalAtomicTranslation(config: GpuConfig=GpuConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new SharedAtomicRequest(config)))
    val out=Decoupled(new SharedAtomicResponse(config))
    val satp=Input(UInt(32.W))
    val pageWalkRootPpn=Output(UInt(20.W))
    val pageWalkRequest=Decoupled(new VectorPageWalkRequest(config))
    val pageWalkResponse=Flipped(Decoupled(new VectorPageWalkResponse(config)))
    val physicalRequest=Decoupled(new SharedAtomicRequest(config))
    val physicalResponse=Flipped(Decoupled(new SharedAtomicResponse(config)))
    val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,walkRequest,walkResponse,request,response,reply=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new SharedAtomicRequest(config))
  private val rootPpn=Reg(UInt(20.W))
  private val result=Reg(new SharedAtomicResponse(config))

  io.in.ready:=state===State.idle
  when(io.in.fire){
    request:=io.in.bits;rootPpn:=io.satp(19,0)
    result:=0.U.asTypeOf(result);result.warpId:=io.in.bits.warpId
    when(io.satp(31) && io.satp(21,20).orR){
      result.fault:=true.B;result.pageFault:=true.B
      state:=State.reply
    }.otherwise{state:=Mux(io.satp(31),State.walkRequest,State.request)}
  }
  io.pageWalkRootPpn:=rootPpn
  io.pageWalkRequest.valid:=state===State.walkRequest
  io.pageWalkRequest.bits.virtualPageNumber:=request.address(31,12)
  io.pageWalkRequest.bits.isStore:=true.B
  io.pageWalkRequest.bits.isInstruction:=false.B
  when(io.pageWalkRequest.fire){state:=State.walkResponse}
  io.pageWalkResponse.ready:=state===State.walkResponse
  when(io.pageWalkResponse.fire){
    val failed=io.pageWalkResponse.bits.fault || io.pageWalkResponse.bits.accessFault ||
      !io.pageWalkResponse.bits.readable || !io.pageWalkResponse.bits.writable
    when(failed){
      result.fault:=true.B;result.pageFault:= !io.pageWalkResponse.bits.accessFault
      state:=State.reply
    }.otherwise{
      request.address:=Cat(io.pageWalkResponse.bits.physicalPageNumber,request.address(11,0))
      request.cachePolicy:=io.pageWalkResponse.bits.cachePolicy
      state:=State.request
    }
  }
  io.physicalRequest.valid:=state===State.request
  io.physicalRequest.bits:=request
  when(io.physicalRequest.fire){state:=State.response}
  io.physicalResponse.ready:=state===State.response
  when(io.physicalResponse.fire){
    assert(io.physicalResponse.bits.warpId===request.warpId,"global atomic reply lost its translated owner")
    result:=io.physicalResponse.bits
    state:=State.reply
  }
  io.out.valid:=state===State.reply;io.out.bits:=result
  when(io.out.fire){state:=State.idle}
  io.drained:=state===State.idle
}
