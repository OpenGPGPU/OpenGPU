package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

/** Stage-specific checked service for one control/evaluation patch owner.
  * The parent freezes scope/tag, forbids a later launch until busy clears and
  * supplies actual physical retirement before an orphaned writeback may drain.
  * The two logical credits remain disjoint through the enclosing image MMU.
  */
class TessellationUniformCuService(config:GpuConfig,idWidth:Int,cus:Int) extends Module {
  require(cus>0)
  val io=IO(new Bundle {
    val texture=Flipped(Vec(cus,new SharedCuTexturePort(config)))
    val scope=Input(new TessellationUniformTaskResources)
    val commandId=Input(UInt(idWidth.W));val control=Input(Bool())
    val owned=Input(UInt(cus.W));val taskIds=Input(Vec(cus,UInt(idWidth.W)))
    val active=Input(Bool());val retired=Input(Bool());val cancel=Input(Bool())
    val busy=Output(Bool());val fault=Output(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
  })
  private val engines=Seq(4,5).map(s=>Module(new ShaderUniformExecuteUnit(config,s,tessellationAvailable=true)))
  private val client=Reg(UInt(math.max(1,log2Ceil(cus)).W))
  private val selected=VecInit((0 until cus).map(i=>io.active && io.owned(i) && io.taskIds(i)===io.commandId))
  assert(PopCount(selected)<=1.U,"one tessellation invocation may own only one physical CU")
  private val matching=VecInit((0 until cus).map(i=>selected(i) && client===i.U))
  private val discard=io.retired && !matching.asUInt.orR
  private val stage= !io.control
  private val requests=Module(new Queue(new ComputeMemoryRequest(config,64,2),2,pipe=true))
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,2),2))
  private val pending=RegInit(0.U(2.W))
  for((u,s)<-engines.zipWithIndex){
    val enabled=stage===s.U && !engines(1-s).io.busy
    u.io.in.valid:=enabled && selected.asUInt.orR && Mux1H(selected,io.texture.map(_.scalar.valid))
    u.io.in.bits:=Mux1H(selected,io.texture.map(_.scalar.bits))
    u.io.vectorIn.valid:=enabled && selected.asUInt.orR && Mux1H(selected,io.texture.map(_.vector.valid))
    u.io.vectorIn.bits:=Mux1H(selected,io.texture.map(_.vector.bits))
    when(u.io.in.fire || u.io.vectorIn.fire){client:=PriorityEncoder(selected)}
    val scope=if(s==0)io.scope.control else io.scope.evaluation
    u.io.resources:=scope.resources;u.io.identity:=io.scope.identity
    u.io.privateBase:=scope.privateBase;u.io.privateLimit:=scope.privateLimit
    // A held CU offer transfers first; cancellation then drains the accepted
    // instruction, every offered memory request and its matching response.
    u.io.cancel:=u.io.busy && io.cancel
    u.io.commit.ready:=discard || Mux1H(matching,io.texture.map(_.scalarWriteback.ready))
    u.io.vectorCommit.ready:=discard || Mux1H(matching,io.texture.map(_.vectorWriteback.ready))
    arbiter.io.in(s).valid:=u.io.memoryRequest.valid;arbiter.io.in(s).bits:=u.io.memoryRequest.bits
    arbiter.io.in(s).bits.transactionId:=s.U;u.io.memoryRequest.ready:=arbiter.io.in(s).ready
    u.io.memoryResponse.valid:=io.memoryResponse.valid && io.memoryResponse.bits.transactionId===s.U
    u.io.memoryResponse.bits:=io.memoryResponse.bits;u.io.memoryResponse.bits.transactionId:=0.U
  }
  for(i<-0 until cus){val p=io.texture(i)
    p.scalar.ready:=selected(i) && Mux(io.control,engines(0).io.in.ready && !engines(1).io.busy,engines(1).io.in.ready && !engines(0).io.busy)
    p.vector.ready:=selected(i) && Mux(io.control,engines(0).io.vectorIn.ready && !engines(1).io.busy,engines(1).io.vectorIn.ready && !engines(0).io.busy)
    p.scalarWriteback.valid:=matching(i) && engines.map(_.io.commit.valid).reduce(_ || _)
    p.scalarWriteback.bits:=Mux(engines(0).io.commit.valid,engines(0).io.commit.bits,engines(1).io.commit.bits)
    p.vectorWriteback.valid:=matching(i) && engines.map(_.io.vectorCommit.valid).reduce(_ || _)
    p.vectorWriteback.bits:=Mux(engines(0).io.vectorCommit.valid,engines(0).io.vectorCommit.bits,engines(1).io.vectorCommit.bits)
  }
  requests.io.enq<>arbiter.io.out;io.memoryRequest<>requests.io.deq
  io.memoryResponse.ready:=(0 until 2).map(s=>io.memoryResponse.bits.transactionId===s.U && engines(s).io.memoryResponse.ready).reduce(_ || _)
  pending:=pending+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(pending.orR || io.memoryRequest.fire,"unowned tessellation uniform response")}
  assert(PopCount(VecInit(engines.map(_.io.busy)))<=1.U,"tessellation uniform stage changed before drain")
  io.busy:=engines.map(_.io.busy).reduce(_ || _) || requests.io.count.orR || pending.orR
  io.fault:=engines.map(u=>(u.io.commit.valid || u.io.vectorCommit.valid) && u.io.status=/=0.U && u.io.status=/=ShaderUniformStatus.cancelled.U).reduce(_ || _)
}
