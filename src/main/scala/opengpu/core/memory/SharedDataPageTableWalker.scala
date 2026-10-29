package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class OwnedDataPageWalkRequest(config: GpuConfig) extends Bundle {
  val rootPpn=UInt(20.W)
  val request=new VectorPageWalkRequest(config)
}

/** One walker for ordinary data translations and global AMOs. Capture the
  * selected root/request before presentation and retain reply ownership until
  * the client accepts it. A peer may queue behind a stalled owned response.
  */
class SharedDataPageTableWalker(config: GpuConfig=GpuConfig(),ports: Int=2) extends Module {
  require(ports>0)
  val io=IO(new Bundle {
    val request=Vec(ports,Flipped(Decoupled(new OwnedDataPageWalkRequest(config))))
    val response=Vec(ports,Decoupled(new VectorPageWalkResponse(config)))
    val memoryRequest=Decoupled(new PageTableMemoryRequest(config))
    val memoryResponse=Flipped(Decoupled(new PageTableMemoryResponse))
  })
  private val ownerWidth=math.max(1,log2Ceil(ports))
  private val arbiter=Module(new RRArbiter(new OwnedDataPageWalkRequest(config),ports))
  private val pending=RegInit(false.B)
  private val captured=Reg(new OwnedDataPageWalkRequest(config))
  private val pendingOwner=Reg(UInt(ownerWidth.W))
  private val busy=RegInit(false.B)
  private val owner=Reg(UInt(ownerWidth.W))
  private val walker=Module(new Sv32PageTableWalker(config))
  for(p<-0 until ports)arbiter.io.in(p)<>io.request(p)
  arbiter.io.out.ready:= !pending
  when(arbiter.io.out.fire){
    captured:=arbiter.io.out.bits;pendingOwner:=arbiter.io.chosen;pending:=true.B
  }
  walker.io.request.valid:=pending && !busy
  walker.io.request.bits:=captured.request
  walker.io.rootPpn:=captured.rootPpn
  when(walker.io.request.fire){pending:=false.B;busy:=true.B;owner:=pendingOwner}
  for(p<-0 until ports){
    io.response(p).valid:=walker.io.response.valid && busy && owner===p.U
    io.response(p).bits:=walker.io.response.bits
  }
  walker.io.response.ready:=busy && VecInit(io.response.map(_.ready))(owner)
  when(walker.io.response.fire){busy:=false.B}
  io.memoryRequest<>walker.io.memoryRequest
  walker.io.memoryResponse<>io.memoryResponse
}
