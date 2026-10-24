package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

/** Capacity bounds reserved addressable bytes, independent of live bytes.
  * Small configurations still occupy the characterized 256-row macro minimum.
  * The default fixed SoA payload is nine arrays for a 128-lane batch. Fourteen
  * complete reservations fit in 64 KiB; a fifteenth must wait or spill.
  */
case class ShaderPayloadConfig(
  capacityBytes: Int=64*1024,maxPayloadBytes: Int=9*128*4,
  owners: Int=8,ports: Int=12,responseDepth: Int=2,generationBits: Int=16
) {
  require(capacityBytes>=64 && isPow2(capacityBytes) && maxPayloadBytes>0 && maxPayloadBytes<=capacityBytes)
  require(owners>0 && ports>0 && responseDepth>0 && generationBits>0)
  val slotBytes: Int=((maxPayloadBytes+63)/64)*64
  val slots: Int=capacityBytes/slotBytes
  require(slots>0)
  val slotLines: Int=slotBytes/64
  val rows: Int=slots*slotLines
  val arrayRows: Int=1<<log2Ceil(math.max(2,rows))
  val segmentRows: Int=math.min(256,arrayRows)
  val segments: Int=arrayRows/segmentRows
  val handleWidth: Int=math.max(1,log2Ceil(slots))
  val ownerWidth: Int=math.max(1,log2Ceil(owners))
  val reservedBytes: Int=slots*slotBytes
  val physicalBytes: Int=segments*256*64
  val countWidth: Int=math.max(1,log2Ceil(ports*responseDepth+2))
}

object ShaderPayloadControl {
  val allocate=0
  val cancel=1
  val release=2
}

class ShaderPayloadLease(p: ShaderPayloadConfig) extends Bundle {
  val handle=UInt(p.handleWidth.W)
  val generation=UInt(p.generationBits.W)
  val context=UInt(32.W)
}
class ShaderPayloadPin(p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  val owner=UInt(p.ownerWidth.W)
}

class ShaderPayloadControlRequest(p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  val operation=UInt(2.W)
  val tag=UInt(8.W)
  val bytes=UInt(32.W)
  /** A full pool can return a fallback decision instead of holding admission. */
  val allowSpill=Bool()
}

class ShaderPayloadControlResponse(p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  val operation=UInt(2.W)
  val tag=UInt(8.W)
  val bytes=UInt(32.W)
  val resident=Bool()
  val spill=Bool()
  val fault=Bool()
}

class ShaderPayloadRequest(p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  /** Supplied by the trusted task binding, never by a shader address. */
  val owner=UInt(p.ownerWidth.W)
  val tag=UInt(8.W)
  val offset=UInt(32.W)
  val write=Bool()
  val data=UInt(512.W)
  /** Requested bytes for reads and writes, including partial tail lines. */
  val mask=UInt(64.W)
}

class ShaderPayloadResponse(p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  val tag=UInt(8.W)
  val data=UInt(512.W)
  val fault=Bool()
}

private class QueuedShaderPayloadResponse(p: ShaderPayloadConfig) extends Bundle {
  val response=new ShaderPayloadResponse(p)
  val charged=Bool()
  val slot=UInt(p.handleWidth.W)
}

private class ScheduledShaderPayloadOperation(p: ShaderPayloadConfig) extends Bundle {
  val request=new ShaderPayloadRequest(p)
  val initialize=Bool()
  val leaseMatches=Bool()
  val fault=Bool()
}

/** Shared banked payload storage with explicit reservation and drain ownership.
  *
  * Sixteen single-port word banks deliver one 64-byte line. Each bank is split
  * into characterized <=256-row arrays. Masked writes read/modify/write under
  * the same global operation owner; no uncharacterized byte-write SRAM port is
  * assumed. A two-entry ordered admission queue overlaps accepted lines with
  * the SRAM service. Queued operations hand off at completion, sustaining
  * one read/modify/write every two clocks. Reply credits cover queued, active
  * and held operations; physical line service remains serialized.
  *
  * Allocation reserves the complete input/output slot. Background zeroing
  * covers its live bytes, including the complete final line. It is
  * fairly arbitrated with live traffic, and matching accesses wait for it.
  * Cancellation rejects new accesses and poisons unfinished operations, while
  * retaining the lease until every held reply and initializer has drained.
  * Oversized/full-pool spill responses reserve no SRAM; the caller owns the
  * separate memory-backed fallback protocol.
  */
class BankedShaderPayloadStore(p: ShaderPayloadConfig=ShaderPayloadConfig(),useBlackBoxes: Boolean=false,
    taskPins: Int=0)
    extends Module {
  require(taskPins>=0)
  val io=IO(new Bundle {
    val control=Vec(p.owners,Flipped(Decoupled(new ShaderPayloadControlRequest(p))))
    val controlResponse=Vec(p.owners,Decoupled(new ShaderPayloadControlResponse(p)))
    val request=Vec(p.ports,Flipped(Decoupled(new ShaderPayloadRequest(p))))
    val response=Vec(p.ports,Decoupled(new ShaderPayloadResponse(p)))
    val pins=if(taskPins>0)Some(Input(Vec(taskPins,Valid(new ShaderPayloadPin(p)))))else None
    val reservedBytes=Output(UInt(32.W))
    val liveBytes=Output(UInt(32.W))
    val initializing=Output(Bool())
    val drained=Output(Bool())
    val readIssued=Output(Bool())
    val writeCommitted=Output(Bool())
  })
  private val active=RegInit(VecInit(Seq.fill(p.slots)(false.B)))
  private val canceled=RegInit(VecInit(Seq.fill(p.slots)(false.B)))
  private val initialized=RegInit(VecInit(Seq.fill(p.slots)(false.B)))
  private val owner=Reg(Vec(p.slots,UInt(p.ownerWidth.W)))
  private val context=Reg(Vec(p.slots,UInt(32.W)))
  private val generation=RegInit(VecInit(Seq.fill(p.slots)(0.U(p.generationBits.W))))
  private val bytes=Reg(Vec(p.slots,UInt(math.max(1,log2Ceil(p.maxPayloadBytes+1)).W)))
  private val inFlight=RegInit(VecInit(Seq.fill(p.slots)(0.U(p.countWidth.W))))
  private val initLineBits=math.max(1,log2Ceil(p.slotLines))
  private val initLine=Reg(Vec(p.slots,UInt(initLineBits.W)))
  protected val memories=Seq.fill(16)(Seq.fill(p.segments)(
    Module(new L2SramArray(p.segmentRows,32,useBlackBoxes))))
  private val replies=Seq.fill(p.ports)(Module(new Queue(new QueuedShaderPayloadResponse(p),p.responseDepth)))
  private val controlReplies=Seq.fill(p.owners)(Module(new Queue(new ShaderPayloadControlResponse(p),1)))
  private object State extends ChiselEnum {val idle,memory,complete=Value}
  private val state=RegInit(State.idle)
  private val operation=Reg(new ScheduledShaderPayloadOperation(p))
  private val operationPort=Reg(UInt(math.max(1,log2Ceil(p.ports+1)).W))
  private val operationSlot=Reg(UInt(p.handleWidth.W))
  private val heldSlot=if(p.slots==1)0.U else operationSlot
  private val operationFault=Reg(Bool())
  private val operationCharged=Reg(Bool())
  private val initializerQueued=RegInit(VecInit(Seq.fill(p.slots)(false.B)))
  private val portFlights=RegInit(VecInit(Seq.fill(p.ports)(0.U(math.max(1,log2Ceil(p.responseDepth+1)).W))))

  private def safeSlot(handle: UInt): UInt=if(p.slots==1)0.U else Mux(handle<p.slots.U,handle,0.U)
  private def matches(lease: ShaderPayloadLease,who: UInt): Bool=
    // Compare stored lease metadata in parallel; a late handle selects a
    // completed match instead of selecting 48 metadata bits before comparison.
    VecInit((0 until p.slots).map(i=>lease.handle===i.U && active(i) &&
      generation(i)===lease.generation && owner(i)===who && context(i)===lease.context)).asUInt.orR
  // Pin lifetime is a property of each stored lease. Compare it once per slot,
  // independently of incoming control metadata, then select the slot result.
  private val pinnedSlots=VecInit((0 until p.slots).map(s=>io.pins.map(pins=>
    VecInit(pins.map(pin=>active(s) && pin.valid && pin.bits.handle===s.U &&
      pin.bits.generation===generation(s) && pin.bits.context===context(s) &&
      pin.bits.owner===owner(s))).asUInt.orR).getOrElse(false.B)))
  private val controls=Module(new RRArbiter(new ShaderPayloadControlRequest(p),p.owners))
  private val arbitrateBeforePinVeto=taskPins>0
  private val controlLeaseMatches=Wire(Vec(p.owners,Bool()))
  private val controlMayMutate=Wire(Vec(p.owners,Bool()))
  private val controlAccepted=Wire(Bool())
  private val releaseFence=RegInit(VecInit(Seq.fill(p.slots)(false.B)))
  private val releaseIntent=Wire(Vec(p.owners,Bool()))
  private val free = ~active.asUInt
  private val freeSlot=if(p.slots==1)0.U(p.handleWidth.W)else PriorityEncoder(free)
  for(i <- 0 until p.owners) {
    val req=io.control(i)
    val slot=safeSlot(req.bits.handle)
    val validLease=matches(req.bits,i.U)
    controlLeaseMatches(i):=validLease
    val alloc=req.bits.operation===ShaderPayloadControl.allocate.U
    val release=req.bits.operation===ShaderPayloadControl.release.U
    val initializerBusy=initializerQueued(slot)
    val pinned=pinnedSlots(slot)
    val pinAdmissible=if(arbitrateBeforePinVeto)true.B else !pinned
    val oversized=req.bits.bytes>p.maxPayloadBytes.U || req.bits.bytes===0.U
    val eligible=controlReplies(i).io.enq.ready &&
      Mux(alloc,oversized || free.orR || req.bits.allowSpill,
        !release || !validLease || (releaseFence(slot) && inFlight(slot)===0.U && !initializerBusy && pinAdmissible))
    // Establish release backpressure a clock before release can commit.
    // Current pins still veto release immediately; their admission fence clears
    // on the next clock so a pinned shader can continue its drain traffic.
    releaseIntent(i):=req.valid && release && validLease &&
      controlReplies(i).io.enq.ready && !pinned
    controlMayMutate(i):=req.valid && controlReplies(i).io.enq.ready &&
      req.bits.operation===ShaderPayloadControl.cancel.U
    controls.io.in(i).valid:=req.valid && eligible
    controls.io.in(i).bits:=req.bits
    // Veto this input directly after grant selection. Pin reductions need
    // not return through the arbiter's shared ready and metadata paths.
    val pinVeto=if(arbitrateBeforePinVeto)release && validLease && pinned else false.B
    req.ready:=controls.io.in(i).ready && eligible && !pinVeto
    io.controlResponse(i)<>controlReplies(i).io.deq
    controlReplies(i).io.enq.valid:=(if(arbitrateBeforePinVeto)req.fire
      else controls.io.out.fire && controls.io.chosen===i.U)
    controlReplies(i).io.enq.bits:=0.U.asTypeOf(controlReplies(i).io.enq.bits)
  }
  for(s<-0 until p.slots)releaseFence(s):=VecInit((0 until p.owners).map(i=>
    releaseIntent(i) && io.control(i).bits.handle===s.U)).asUInt.orR
  private val command=controls.io.out.bits
  private val commandSlot=safeSlot(command.handle)
  private val commandMatches=if(arbitrateBeforePinVeto)
    Mux1H((0 until p.owners).map(i=>(controls.io.chosen===i.U)->controlLeaseMatches(i)))
  else matches(command,controls.io.chosen)
  private val commandAlloc=command.operation===ShaderPayloadControl.allocate.U
  private val commandCancel=command.operation===ShaderPayloadControl.cancel.U
  private val commandRelease=command.operation===ShaderPayloadControl.release.U
  // The arbiter selects candidates and rotates even if a late pin vetoes a
  // release. Only the external control handshake commits state or a reply.
  // Rotating a blocked candidate prevents it from monopolizing other owners.
  controls.io.out.ready:=true.B
  controlAccepted:=(if(arbitrateBeforePinVeto)VecInit(io.control.map(_.fire)).asUInt.orR
    else controls.io.out.fire)
  if(arbitrateBeforePinVeto){
    assert(PopCount(io.control.map(_.fire))<=1.U,"payload controls must accept at most one command")
    when(controlAccepted){assert(io.control(controls.io.chosen).fire,"accepted payload control must own the selected grant")}
  }
  private val allocationFits=command.bytes=/=0.U && command.bytes<=p.maxPayloadBytes.U
  private val allocate=controlAccepted && commandAlloc && allocationFits && free.orR
  private val cancel=controlAccepted && commandCancel && commandMatches
  private val release=controlAccepted && commandRelease && commandMatches
  // Decode each accepted command before arbitration's payload mux. Only a
  // handshaken, matching cancel/release may suppress a data operation.
  private def mutating(slot: UInt): Bool=VecInit((0 until p.owners).map{i=>
    val req=io.control(i)
    if(arbitrateBeforePinVeto)
      // Selected cancels always handshake: the late veto applies only to
      // releases. Release already waits for all data and initializer owners,
      // so it cannot poison an active operation. Avoid its pin-dependent
      // ready path when deciding whether an accepted cancel poisons data.
      // validOperation independently validates the held operation against
      // stored ownership. Match the cancel directly to that operation so its
      // generation/context need not traverse every slot and its reduction.
      controlMayMutate(i) && controls.io.in(i).ready &&
        req.bits.handle===slot &&
        req.bits.generation===operation.request.generation &&
        req.bits.context===operation.request.context && operation.request.owner===i.U
    else controls.io.in(i).fire && controlLeaseMatches(i) && req.bits.handle===slot &&
      (req.bits.operation===ShaderPayloadControl.cancel.U || req.bits.operation===ShaderPayloadControl.release.U)
  }).asUInt.orR
  if(arbitrateBeforePinVeto)for(i<-0 until p.owners){
    when(controls.io.in(i).valid && controls.io.chosen===i.U &&
      io.control(i).bits.operation===ShaderPayloadControl.cancel.U){
      assert(io.control(i).fire,"selected payload cancel must be accepted before poisoning data")
    }
  }
  for(i <- 0 until p.owners) {
    val response=controlReplies(i).io.enq.bits
    response.operation:=command.operation
    response.tag:=command.tag
    response.handle:=Mux(allocate,freeSlot,command.handle)
    response.generation:=Mux(allocate,generation(freeSlot)+1.U,command.generation)
    response.context:=command.context
    response.bytes:=Mux(commandAlloc,command.bytes,Mux(commandMatches,bytes(commandSlot),0.U))
    response.resident:=Mux(commandAlloc,allocate,commandMatches && !commandRelease)
    response.spill:=commandAlloc && command.bytes=/=0.U &&
      (!allocationFits || (!free.orR && command.allowSpill))
    response.fault:=Mux(commandAlloc,command.bytes===0.U,
      !commandMatches || !(commandCancel || commandRelease))
  }
  when(allocate) {
    active(freeSlot):=true.B
    canceled(freeSlot):=false.B
    initialized(freeSlot):=false.B
    generation(freeSlot):=generation(freeSlot)+1.U
    owner(freeSlot):=controls.io.chosen
    context(freeSlot):=command.context
    bytes(freeSlot):=command.bytes
    initLine(freeSlot):=0.U
    assert(inFlight(freeSlot)===0.U,"a payload reservation cannot recycle outstanding replies")
  }
  when(cancel) {canceled(commandSlot):=true.B}
  when(release) {
    active(commandSlot):=false.B
    initialized(commandSlot):=false.B
  }

  private def admissionBlocked(slot: UInt): Bool=releaseFence(slot) ||
    VecInit((0 until p.owners).map(i=>controlMayMutate(i) && io.control(i).bits.handle===slot)).asUInt.orR
  private def bounds(request: ShaderPayloadRequest,slot: UInt): Bool={
    // Each request is line aligned. Only the final line needs a tail mask.
    val limit=bytes(slot).pad(32);val line=request.offset(31,6);val lastLine=limit(31,6)
    val withinBytes= !request.mask.orR || line<lastLine ||
      (line===lastLine && !(request.mask>>limit(5,0)).orR)
    request.offset(5,0)===0.U && request.offset<p.slotBytes.U && withinBytes
  }
  private val scheduled=Module(new RRArbiter(new ScheduledShaderPayloadOperation(p),p.ports+1))
  for(i <- 0 until p.ports) {
    val req=io.request(i)
    val slot=safeSlot(req.bits.handle)
    val validLease=matches(req.bits,req.bits.owner)
    val waitingForInit=validLease && !canceled(slot) && !initialized(slot)
    val eligible=portFlights(i)<p.responseDepth.U && !waitingForInit && !admissionBlocked(slot)
    scheduled.io.in(i).valid:=req.valid && eligible
    scheduled.io.in(i).bits.request:=req.bits
    scheduled.io.in(i).bits.initialize:=false.B
    scheduled.io.in(i).bits.leaseMatches:=validLease
    scheduled.io.in(i).bits.fault:= !validLease || canceled(slot) || !bounds(req.bits,slot)
    req.ready:=scheduled.io.in(i).ready && eligible
    io.response(i).valid:=replies(i).io.deq.valid
    io.response(i).bits:=replies(i).io.deq.bits.response
    replies(i).io.deq.ready:=io.response(i).ready
  }
  private val initializers=Module(new RRArbiter(UInt(p.handleWidth.W),p.slots))
  for(i <- 0 until p.slots) {
    initializers.io.in(i).valid:=active(i) && !initialized(i) && !canceled(i) && !admissionBlocked(i.U) && !initializerQueued(i)
    initializers.io.in(i).bits:=i.U
  }
  private val initializingSlot=if(p.slots==1)0.U else initializers.io.out.bits
  private val init=scheduled.io.in(p.ports)
  init.valid:=initializers.io.out.valid
  init.bits:=0.U.asTypeOf(init.bits)
  init.bits.initialize:=true.B
  init.bits.leaseMatches:=true.B
  init.bits.fault:=false.B
  init.bits.request.handle:=initializingSlot
  init.bits.request.generation:=generation(initializingSlot)
  init.bits.request.context:=context(initializingSlot)
  init.bits.request.owner:=owner(initializingSlot)
  init.bits.request.offset:=initLine(initializingSlot)<<6
  init.bits.request.write:=true.B
  init.bits.request.mask:=Fill(64,1.U(1.W))
  initializers.io.out.ready:=init.ready
  private val pendingOperations=Module(new Queue(new Bundle {
    val operation=new ScheduledShaderPayloadOperation(p)
    val port=UInt(math.max(1,log2Ceil(p.ports+1)).W)
    val slot=UInt(p.handleWidth.W);val charged=Bool();val fault=Bool()
  },2,flow=true))
  scheduled.io.out.ready:=pendingOperations.io.enq.ready
  pendingOperations.io.enq.valid:=scheduled.io.out.valid
  private val offered=scheduled.io.out.bits.request
  private val offeredSlot=safeSlot(offered.handle)
  private val offeredMatches=scheduled.io.out.bits.leaseMatches
  pendingOperations.io.enq.bits.operation:=scheduled.io.out.bits
  pendingOperations.io.enq.bits.port:=scheduled.io.chosen
  pendingOperations.io.enq.bits.slot:=offeredSlot
  pendingOperations.io.enq.bits.charged:=offeredMatches && !scheduled.io.out.bits.initialize
  pendingOperations.io.enq.bits.fault:=scheduled.io.out.bits.fault
  when(scheduled.io.out.fire && scheduled.io.out.bits.initialize){initializerQueued(offeredSlot):=true.B}
  pendingOperations.io.deq.ready:=state===State.idle || state===State.complete
  when(pendingOperations.io.deq.fire){
    val next=pendingOperations.io.deq.bits
    operation:=next.operation;operationPort:=next.port;operationSlot:=next.slot
    operationCharged:=next.charged;operationFault:=next.fault;state:=State.memory
  }
  for(i<-0 until p.ports){
    val increment=io.request(i).fire;val decrement=io.response(i).fire
    when(increment=/=decrement){portFlights(i):=Mux(increment,portFlights(i)+1.U,portFlights(i)-1.U)}
    when(decrement){assert(portFlights(i)=/=0.U,"payload port reply ownership underflow")}
    assert(portFlights(i)<=p.responseDepth.U,"payload port overcommitted reply capacity")
  }
  private val validOperation=matches(operation.request,operation.request.owner) &&
    !canceled(heldSlot) && !mutating(heldSlot) && !operationFault
  if(arbitrateBeforePinVeto)when(release && state=/=State.idle && commandSlot===heldSlot){
    assert(!validOperation,"payload release must wait for its data and initializer operation")
  }
  private val row=heldSlot*p.slotLines.U+(operation.request.offset>>6)
  private val segment=if(p.segments==1)0.U else row>>log2Ceil(p.segmentRows)
  private val address=row(log2Ceil(p.segmentRows)-1,0)
  private val readWords=Wire(Vec(16,UInt(32.W)))
  for(bank <- 0 until 16) {
    readWords(bank):=Mux1H((0 until p.segments).map(s=>(segment===s.U)->memories(bank)(s).io.readData))
    val mask=operation.request.mask(4*bank+3,4*bank)
    val replacement=VecInit((0 until 4).map(b=>Mux(mask(b),
      operation.request.data(32*bank+8*b+7,32*bank+8*b),readWords(bank)(8*b+7,8*b)))).asUInt
    for(s <- 0 until p.segments) {
      val ram=memories(bank)(s).io
      val selected=segment===s.U
      ram.readEnable:=state===State.memory && !operation.initialize && validOperation && selected
      ram.readAddress:=address
      ram.writeEnable:=selected && validOperation &&
        ((state===State.memory && operation.initialize) ||
         (state===State.complete && !operation.initialize && operation.request.write && mask.orR))
      ram.writeAddress:=address
      ram.writeData:=Mux(operation.initialize,0.U,replacement)
    }
  }
  when(state===State.memory) {state:=State.complete}
  for(i <- 0 until p.ports) {
    val enq=replies(i).io.enq
    enq.valid:=state===State.complete && !operation.initialize && operationPort===i.U
    enq.bits:=0.U.asTypeOf(enq.bits)
    enq.bits.response.handle:=operation.request.handle
    enq.bits.response.generation:=operation.request.generation
    enq.bits.response.context:=operation.request.context
    enq.bits.response.tag:=operation.request.tag
    enq.bits.response.fault := !validOperation
    enq.bits.response.data:=Mux(validOperation && !operation.request.write,
      VecInit((0 until 64).map(b=>Mux(operation.request.mask(b),
        readWords.asUInt(8*b+7,8*b),0.U(8.W)))).asUInt,0.U)
    enq.bits.charged:=operationCharged
    enq.bits.slot:=operationSlot
    when(enq.valid) {assert(enq.ready,"accepted payload operation must retain reply capacity")}
  }
  when(state===State.complete) {
    state:=Mux(pendingOperations.io.deq.fire,State.memory,State.idle)
    when(operation.initialize){initializerQueued(heldSlot):=false.B}
    when(operation.initialize && validOperation) {
      // Every accepted allocation has nonzero live bytes. Its existing stored
      // length gives the inclusive final line without another admission register.
      val lastLine=(bytes(heldSlot)-1.U)>>6
      when(initLine(heldSlot)===lastLine) {
        initialized(heldSlot):=true.B
      }.otherwise {initLine(heldSlot):=initLine(heldSlot)+1.U}
    }
  }
  for(i <- 0 until p.slots) {
    val increment=scheduled.io.out.fire && !scheduled.io.out.bits.initialize && offeredMatches && offeredSlot===i.U
    val decrements=PopCount(replies.map(q=>q.io.deq.fire && q.io.deq.bits.charged && q.io.deq.bits.slot===i.U))
    when(increment || decrements=/=0.U) {
      assert((inFlight(i)+&increment.asUInt)>=decrements,"payload reply ownership underflow")
    }
    // Admission is late after arbitration. Form the retired count first and
    // inject its conditional increment after the parallel carry conditions.
    val retired=inFlight(i)-decrements
    inFlight(i):=VecInit((0 until p.countWidth).map(b=>retired(b) ^
      (increment && (if(b==0)true.B else retired(b-1,0).andR)))).asUInt
    when(!active(i)) {assert(inFlight(i)===0.U,"free payload slot retains data replies")}
  }
  io.reservedBytes:=PopCount(active)*p.slotBytes.U
  private val liveTerms=(0 until p.slots).map(i=>Mux(active(i),bytes(i),0.U))
  if(p.slots<=3)io.liveBytes:=liveTerms.reduce(_+& _)
  else {
    // Compress independent lease sizes without serial carry propagation
    // through every reservation. The width covers arbitrary stored values.
    val width=bytes.head.getWidth+log2Ceil(p.slots)
    def compress(terms: Seq[UInt]): Seq[UInt]=if(terms.size<=2)terms else{
      compress(terms.grouped(3).flatMap{
        case Seq(a,b,c)=>Seq(a^b^c,(((a&b)|(a&c)|(b&c))<<1)(width-1,0))
        case remainder=>remainder
      }.toSeq)
    }
    val reduced=compress(liveTerms.map(_.pad(width)))
    val finish=Module(new opengpu.util.BlockPrefixAdder(width))
    finish.io.a:=reduced.head;finish.io.b:=reduced(1);finish.io.carryIn:=false.B
    io.liveBytes:=finish.io.sum
  }
  io.initializing:=(0 until p.slots).map(i=>active(i) && !initialized(i) && !canceled(i)).reduce(_ || _)
  io.drained := !active.asUInt.orR && state===State.idle && pendingOperations.io.count===0.U &&
    !portFlights.map(_.orR).reduce(_ || _) &&
    !replies.map(_.io.deq.valid).reduce(_ || _) && !controlReplies.map(_.io.deq.valid).reduce(_ || _)
  io.readIssued:=state===State.memory && !operation.initialize && validOperation
  io.writeCommitted:=state===State.complete && !operation.initialize && operation.request.write &&
    validOperation && operation.request.mask.orR
}
