package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

object PrimitiveQueryAction {
  val begin=0.U(2.W);val end=1.U(2.W);val poll=2.U(2.W);val result=3.U(2.W)
}
class PrimitiveQueryCommand extends Bundle {
  val action=UInt(2.W);val address=UInt(32.W);val limit=UInt(33.W)
  val context=UInt(32.W);val cookie=UInt(32.W);val tag=UInt(32.W)
}
class PrimitiveQueryReply extends Bundle {
  val tag=UInt(32.W);val address=UInt(32.W);val context=UInt(32.W);val cookie=UInt(32.W)
  val status=UInt(3.W);val available=Bool();val count=UInt(64.W);val value=UInt(32.W)
}
/** Trusted hardware events only: a primitive contributes after all its feedback
  * stores succeed. The native source will supply one; batched sources may
  * supply their acknowledged count. Clipped/tile references are not events.
  */
class PrimitiveQueryWritten extends Bundle {
  val context=UInt(32.W);val root=UInt(32.W);val count=UInt(64.W)
}
object PrimitiveQueryRecord {
  val magic=BigInt("47504f01",16) // GPO1
  val bytes=64;val target=0x8c88 // TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN
  def apiValue(count: UInt): UInt=Mux(count(63,32).orR,"hffffffff".U(32.W),count(31,0))
}

/** Bounded active counters with GPU-memory completed-query lifetime.
  *
  * Begin/end are render boundaries; the caller drains acknowledged producer
  * events and fences following work until the reply. Other active contexts
  * accumulate independently of record-memory or public-reply backpressure.
  * Completed unread records consume no active slot. Root/record/cookie leases
  * belong to the caller; reset requires fresh cookies before reuse.
  *
  * Counters saturate64. GetQueryObjectuiv's32-bit view clamps rather than
  * truncating. Faulted records may retain their committed count for diagnostics;
  * public count/value are zero on a failed query result. No native opcode,
  * translation,CPU coherence or API-object-name mapping is supplied here.
  */
class NativePrimitiveQueryEngine(config: GpuConfig=GpuConfig(),contexts: Int=8,
    producers: Int=1) extends Module {
  require(config.xLen==32 && contexts>=1 && contexts<=16 && producers>=1 && producers<=4)
  private val indexBits=math.max(1,log2Ceil(contexts))
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new PrimitiveQueryCommand))
    val reply=Decoupled(new PrimitiveQueryReply)
    val written=Input(Vec(producers,Valid(new PrimitiveQueryWritten)))
    val jobFault=Flipped(Valid(new VisibilityQueryJobFault))
    val satp=Input(UInt(32.W));val memorySatp=Output(UInt(32.W))
    val renderIdle=Input(Bool());val cancel=Input(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val busy=Output(Bool());val activeContexts=Output(UInt(log2Ceil(contexts+1).W))
  })
  private class Active extends Bundle {
    val context=UInt(32.W);val root=UInt(32.W);val address=UInt(32.W);val cookie=UInt(32.W)
    val count=UInt(64.W);val fault=UInt(3.W);val closing=Bool()
  }
  private val active=Reg(Vec(contexts,new Active))
  private val live=RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private object Phase extends ChiselEnum {val idle,offer,waitMemory,publish=Value}
  private val phase=RegInit(Phase.idle);private val command=Reg(new PrimitiveQueryCommand)
  private val root=RegInit(0.U(32.W));private val slot=Reg(UInt(indexBits.W))
  private val writing=Reg(Bool());private val payload=Reg(UInt(512.W))
  private val response=Reg(new PrimitiveQueryReply)
  private val resetPending=RegInit(false.B);private val interrupted=RegInit(false.B)
  private val replies=Module(new Queue(new PrimitiveQueryReply,1))
  io.reply<>replies.io.deq
  replies.io.enq.valid:=phase===Phase.publish;replies.io.enq.bits:=response
  replies.io.enq.bits.value:=PrimitiveQueryRecord.apiValue(response.count)
  when(replies.io.enq.fire){phase:=Phase.idle}
  io.memorySatp:=root;io.activeContexts:=PopCount(live)
  io.busy:=phase=/=Phase.idle || replies.io.deq.valid || resetPending

  private val nextCount=Wire(Vec(contexts,UInt(64.W)))
  private val nextFault=Wire(Vec(contexts,UInt(3.W)))
  for(i<-0 until contexts){
    val a=active(i)
    val hits=io.written.map(e=>e.valid && live(i) && !a.closing &&
      e.bits.context===a.context && e.bits.root===a.root)
    val delta=hits.zip(io.written).map{case(hit,e)=>Mux(hit,e.bits.count,0.U(64.W))}.reduce(_+&_)
    val sum=a.count+&delta
    nextCount(i):=Mux(sum(sum.getWidth-1,64).orR,((BigInt(1)<<64)-1).U,sum(63,0))
    val failed=io.jobFault.valid && live(i) && !a.closing &&
      io.jobFault.bits.context===a.context && io.jobFault.bits.root===a.root
    nextFault(i):=a.fault
    when((io.cancel || (failed && io.jobFault.bits.status===4.U)) && a.fault===0.U){nextFault(i):=4.U}
    when(failed && io.jobFault.bits.status=/=0.U && io.jobFault.bits.status=/=4.U &&
      (a.fault===0.U || a.fault===4.U)){nextFault(i):=3.U}
    when(live(i) && !a.closing){a.count:=nextCount(i);a.fault:=nextFault(i)}
    for(j<-0 until i)assert(!(live(i) && live(j) && a.root===active(j).root &&
      (a.context===active(j).context || a.address===active(j).address)),"duplicate active primitive query")
  }
  private val selected=VecInit((0 until contexts).map(i=>live(i) &&
    active(i).context===io.command.bits.context && active(i).root===io.satp))
  private val aliases=VecInit((0 until contexts).map(i=>live(i) &&
    active(i).address===io.command.bits.address && active(i).root===io.satp))
  private val hasActive=selected.asUInt.orR
  private val selectedSlot=if(contexts==1)0.U(indexBits.W)else PriorityEncoder(selected.asUInt)
  private val free=VecInit(live.map(x=> !x))
  private val freeSlot=if(contexts==1)0.U(indexBits.W)else PriorityEncoder(free.asUInt)
  private val boundary=io.command.bits.action===PrimitiveQueryAction.begin || io.command.bits.action===PrimitiveQueryAction.end
  io.command.ready:=phase===Phase.idle && replies.io.enq.ready && !io.cancel &&
    !resetPending && (!boundary || io.renderIdle)
  private def record(available: Bool,count: UInt,fault: UInt): UInt={
    val w=Wire(Vec(16,UInt(32.W)));w.foreach(_:=0.U)
    w(0):=PrimitiveQueryRecord.magic.U;w(1):=64.U
    w(2):=io.command.bits.context;w(3):=io.command.bits.cookie
    w(4):=(fault.pad(32)<<2)|available.asUInt
    w(5):=count(31,0);w(6):=count(63,32);w(7):=io.satp;w(8):=PrimitiveQueryRecord.target.U
    w.asUInt
  }
  when(io.command.fire){
    val q=io.command.bits;command:=q;root:=io.satp;interrupted:=false.B
    response:=0.U.asTypeOf(response)
    response.tag:=q.tag;response.address:=q.address;response.context:=q.context;response.cookie:=q.cookie
    phase:=Phase.publish
    val bad=q.address(5,0).orR || q.limit>(BigInt(1)<<32).U ||
      q.address.pad(34)+64.U>q.limit.pad(34)
    when(bad){response.status:=1.U}
      .elsewhen(q.action===PrimitiveQueryAction.begin){
        when(hasActive || aliases.asUInt.orR){response.status:=5.U}
          .elsewhen(!free.asUInt.orR){response.status:=6.U}
          .otherwise{slot:=freeSlot;writing:=true.B;payload:=record(false.B,0.U(64.W),0.U);phase:=Phase.offer}
      }.elsewhen(q.action===PrimitiveQueryAction.end){
        val a=active(selectedSlot)
        when(!hasActive || a.closing || a.address=/=q.address || a.cookie=/=q.cookie){response.status:=5.U}
          .otherwise{
            slot:=selectedSlot;writing:=true.B;payload:=record(true.B,nextCount(selectedSlot),nextFault(selectedSlot))
            response.status:=nextFault(selectedSlot);response.available:=true.B
            response.count:=Mux(nextFault(selectedSlot)===0.U,nextCount(selectedSlot),0.U)
            a.closing:=true.B;phase:=Phase.offer
          }
      }.otherwise{
        when(aliases.asUInt.orR){response.status:=5.U}.otherwise{writing:=false.B;phase:=Phase.offer}
      }
  }
  io.memoryRequest.valid:=phase===Phase.offer
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=command.address;io.memoryRequest.bits.isWrite:=writing
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.byteMask:=((BigInt(1)<<64)-1).U
  io.memoryRequest.bits.writeData:=payload;io.memoryRequest.bits.transactionId:=0.U
  when(io.memoryRequest.fire){phase:=Phase.waitMemory}
  io.memoryResponse.ready:=phase===Phase.waitMemory || io.memoryRequest.fire
  when(io.memoryResponse.valid){assert(io.memoryResponse.ready && io.memoryResponse.bits.transactionId===0.U,
    "primitive query received an unowned memory reply")}
  when(io.memoryResponse.fire){
    val failed=io.memoryResponse.bits.fault;val stopped=io.cancel || interrupted
    when(failed){response.status:=3.U;response.available:=false.B;response.count:=0.U}
      .elsewhen(stopped){
        when(response.status===0.U){response.status:=4.U}
        response.available:=false.B;response.count:=0.U
      }
    when(writing){
      when(command.action===PrimitiveQueryAction.begin && !failed && !stopped){
        assert(!live(slot));live(slot):=true.B
        active(slot).context:=command.context;active(slot).root:=root;active(slot).address:=command.address
        active(slot).cookie:=command.cookie;active(slot).count:=0.U;active(slot).fault:=0.U;active(slot).closing:=false.B
      }
      when(command.action===PrimitiveQueryAction.end){live(slot):=false.B}
    }.elsewhen(!failed && !stopped){
      val w=io.memoryResponse.bits.readData.asTypeOf(Vec(16,UInt(32.W)))
      val available=w(4)(0);val fault=w(4)(4,2);val count=Cat(w(6),w(5))
      val bad=w(0)=/=PrimitiveQueryRecord.magic.U || w(1)=/=64.U || w(4)(31,5).orR || w(4)(1) ||
        fault>4.U || (!available && (count.orR || fault.orR)) || w(8)=/=PrimitiveQueryRecord.target.U ||
        w.slice(9,16).map(_.orR).reduce(_ || _)
      val wrong=w(2)=/=command.context || w(3)=/=command.cookie || w(7)=/=root
      response.status:=Mux(bad,1.U,Mux(wrong,2.U,
        Mux(command.action===PrimitiveQueryAction.result && available,fault,0.U)))
      response.available:=available && !bad && !wrong
      response.count:=Mux(command.action===PrimitiveQueryAction.result && available && !bad && !wrong && fault===0.U,count,0.U)
    }
    phase:=Phase.publish
  }
  when(io.cancel){
    when(live.asUInt.orR || phase=/=Phase.idle){resetPending:=true.B}
    when(phase=/=Phase.idle){interrupted:=true.B}
  }
  when(resetPending && io.renderIdle && phase===Phase.idle){live.foreach(_:=false.B);resetPending:=false.B}
}
