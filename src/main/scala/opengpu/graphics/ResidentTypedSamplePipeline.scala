package opengpu.graphics

import chisel3._
import chisel3.util._

/** A stable parent-bound clear/valid query. Availability and data must remain
  * valid through drain of the bound tile, including cancellation. The parent
  * marks a word resident only when its full physical write is accepted.
  */
class TypedSampleVirtualWordQuery extends Bundle {
  val valid=Output(Bool());val address=Output(UInt(32.W))
  val available=Input(Bool());val data=Input(UInt(64.W))
}
private class VirtualTypedSampleWordReply extends TypedTileWordResponse {val virtualWord=Bool()}

/** A composed resident RGBA8+D24S8 sample pipeline with one canonical tile SRAM.
  * Native descriptors/quads must pass the caller's ordinary validation. Other
  * formats use the caller's generic owner. Maintenance can initialize/read the
  * same array only after the native frontend, workers and memory fully drain.
  * Clear/load/refill/dirty/export policy and native-quad completion aggregation
  * remain the enclosing renderer's responsibility.
  */
class ResidentTypedSamplePipeline(useBlackBoxes: Boolean=false,virtualWords: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val begin=Input(Bool());val cancel=Input(Bool());val maintenance=Input(Bool())
    val nativeMode=Input(Bool());val bind=Flipped(Decoupled(new TypedTilePixelDescriptor))
    val nativeIn=Flipped(Decoupled(new TaggedTypedPixelQuad))
    val in=Flipped(Decoupled(new TypedSamplePacket));val out=Decoupled(new TypedSamplePacketRetired)
    val memoryIn=Vec(8,Flipped(Decoupled(new TypedTileWordRequest)))
    val memoryOut=Vec(8,Decoupled(new TypedTileWordResponse))
    val virtual=if(virtualWords)Some(Vec(4,new TypedSampleVirtualWordQuery))else None
    val materialized=if(virtualWords)Some(Output(Vec(4,Valid(UInt(32.W)))))else None
    val drained=Output(Bool());val fault=Output(Bool());val supported=Output(Bool())
  })
  // A mode change already requires complete ownership drain. Capture it here
  // rather than placing an external mode input on every math/memory ready path.
  private val maintenanceMode=RegNext(io.maintenance,false.B)
  // Register external addresses before bank selection and cross-port arbitration.
  // A mode transition closes admission; queued offers remain owned until drain.
  private val maintenanceRequests=Seq.fill(8)(Module(new Queue(new TypedTileWordRequest,2,pipe=true)))
  private val maintenanceDrained=maintenanceRequests.map(q=> !q.io.deq.valid).reduce(_ && _)
  protected val memory=Module(new BankedTypedTileMemory(ports=8,banks=16,useBlackBoxes=useBlackBoxes,xorBanks=true,samplePairBanks=true))
  protected val window=Module(new TypedSamplePacketWindow)
  protected val worker=Module(new TaggedTypedSampleWorker(materializeDepth=virtualWords))
  private val expander=Module(new TypedSamplePacketExpander)
  private val virtualReplies=if(virtualWords)Some(Seq.fill(4)(Module(new Queue(new VirtualTypedSampleWordReply,2,pipe=true))))else None
  private val virtualDrained=virtualReplies.map(_.map(q=> !q.io.deq.valid).reduce(_ && _)).getOrElse(true.B)
  private val coreDrained=window.io.drained && worker.io.drained && memory.io.drained && virtualDrained && maintenanceDrained
  private val bindAllowed=io.nativeMode && coreDrained && expander.io.drained && !maintenanceMode && !io.cancel
  private val resetCohort=Mux(io.nativeMode,io.bind.valid && bindAllowed,io.begin)
  expander.io.cancel:=io.cancel || (worker.io.fault && !resetCohort)
  expander.io.bind.valid:=io.bind.valid && bindAllowed;expander.io.bind.bits:=io.bind.bits
  io.bind.ready:=bindAllowed && expander.io.bind.ready;io.supported:=expander.io.supported
  expander.io.in.valid:=io.nativeMode && !maintenanceMode && io.nativeIn.valid
  expander.io.in.bits:=io.nativeIn.bits;io.nativeIn.ready:=io.nativeMode && !maintenanceMode && expander.io.in.ready
  private val begin=Mux(io.nativeMode,expander.io.bind.fire,io.begin)
  window.io.begin:=begin;window.io.cancel:=io.cancel || (worker.io.fault && !begin)
  worker.io.begin:=begin;worker.io.cancel:=window.io.cancelSource && !begin
  window.io.in.valid:=Mux(io.nativeMode,expander.io.out.valid,io.in.valid) && !maintenanceMode
  window.io.in.bits:=Mux(io.nativeMode,expander.io.out.bits,io.in.bits)
  expander.io.out.ready:=io.nativeMode && !maintenanceMode && window.io.in.ready
  io.in.ready:= !io.nativeMode && window.io.in.ready && !maintenanceMode
  worker.io.in<>window.io.work;window.io.complete<>worker.io.out;io.out<>window.io.out
  io.fault:=worker.io.fault;io.drained:=coreDrained && expander.io.drained
  for(p<-0 until 8){
    val maintenance=maintenanceRequests(p)
    maintenance.io.enq.valid:=maintenanceMode && io.maintenance && io.memoryIn(p).valid
    maintenance.io.enq.bits:=io.memoryIn(p).bits
    io.memoryIn(p).ready:=maintenanceMode && io.maintenance && maintenance.io.enq.ready
    maintenance.io.deq.ready:=maintenanceMode && memory.io.request(p).ready
    memory.io.request(p).valid:=Mux(maintenanceMode,maintenance.io.deq.valid,worker.io.request(p).valid)
    memory.io.request(p).bits:=Mux(maintenanceMode,maintenance.io.deq.bits,worker.io.request(p).bits)
    worker.io.request(p).ready:= !maintenanceMode && memory.io.request(p).ready
    io.memoryOut(p).valid:=maintenanceMode && memory.io.response(p).valid;io.memoryOut(p).bits:=memory.io.response(p).bits
    worker.io.response(p).valid:= !maintenanceMode && memory.io.response(p).valid;worker.io.response(p).bits:=memory.io.response(p).bits
    memory.io.response(p).ready:=Mux(maintenanceMode,io.memoryOut(p).ready,worker.io.response(p).ready)
    if(virtualWords && p<4){
      val query=io.virtual.get(p);val pending=virtualReplies.get(p)
      query.valid:= !maintenanceMode && worker.io.request(p).valid
      query.address:=worker.io.request(p).bits.address
      val virtual= !maintenanceMode && query.available
      memory.io.request(p).valid:=Mux(maintenanceMode,maintenance.io.deq.valid,worker.io.request(p).valid && !virtual)
      worker.io.request(p).ready:= !maintenanceMode && Mux(virtual,pending.io.enq.ready,memory.io.request(p).ready)
      pending.io.enq.valid:=query.valid && virtual
      pending.io.enq.bits.data:=query.data;pending.io.enq.bits.tag:=worker.io.request(p).bits.tag
      pending.io.enq.bits.fault:=false.B;pending.io.enq.bits.virtualWord:=true.B
      val replies=Module(new RRArbiter(new VirtualTypedSampleWordReply,2))
      replies.io.in(0).valid:= !maintenanceMode && memory.io.response(p).valid
      replies.io.in(0).bits.data:=memory.io.response(p).bits.data
      replies.io.in(0).bits.tag:=memory.io.response(p).bits.tag
      replies.io.in(0).bits.fault:=memory.io.response(p).bits.fault
      replies.io.in(0).bits.virtualWord:=false.B
      replies.io.in(1)<>pending.io.deq
      worker.io.response(p).valid:=replies.io.out.valid
      worker.io.response(p).bits.data:=replies.io.out.bits.data
      worker.io.response(p).bits.tag:=replies.io.out.bits.tag
      worker.io.response(p).bits.fault:=replies.io.out.bits.fault
      replies.io.out.ready:=worker.io.response(p).ready
      memory.io.response(p).ready:=Mux(maintenanceMode,io.memoryOut(p).ready,replies.io.in(0).ready)
      if(p>=2)worker.io.virtualDepth.get(p-2):=replies.io.out.bits.virtualWord
      when(query.valid && query.available){assert(!worker.io.request(p).bits.write,"virtual old-word port received a write")}
    }
  }
  io.materialized.foreach { writes=>for(p<-0 until 4){
    val request=memory.io.request(p+4)
    writes(p).valid:= !maintenanceMode && request.fire
    writes(p).bits:=request.bits.address
    when(writes(p).valid){assert(request.bits.write && request.bits.mask.andR,"resident materialization requires a complete word")}
  }}
  when(io.maintenance=/=maintenanceMode){assert(io.drained,"maintenance mode changed before complete memory drain")}
  when(io.maintenance){assert(window.io.drained && worker.io.drained && expander.io.drained && virtualDrained,"maintenance crossed a live sample owner")}
  when(begin){assert(io.drained && !io.maintenance,"resident sample begin requires complete memory drain")}
}
