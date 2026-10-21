package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

object TransformFeedbackAction {
  val begin=0; val end=1; val pause=2; val resume=3; val restore=4; val evict=5; val invalidate=6
}
class TransformFeedbackBuffer extends Bundle {
  val base=UInt(32.W); val limit=UInt(33.W)
}
/** Source words are position[0..3], all raw varying words, then point size.
  * Interleaved mode uses selectors[0..components(0)); separate mode uses four
  * selectors per binding. Values already have their shader's32-bit highp ABI.
  */
class TransformFeedbackDescriptor extends Bundle {
  val context=UInt(32.W); val cookie=UInt(32.W); val program=UInt(32.W)
  val kind=UInt(2.W); val interleaved=Bool(); val buffers=UInt(3.W)
  val components=Vec(4,UInt(7.W)); val selectors=Vec(64,UInt(7.W))
  val views=Vec(4,new TransformFeedbackBuffer)
}
/** Explicit checkpoint for paused-object/context virtualization. The caller
  * retains allocation/program leases and this state until restore or deletion.
  */
class TransformFeedbackState extends Bundle {
  val descriptor=new TransformFeedbackDescriptor; val root=UInt(32.W)
  val offsets=Vec(4,UInt(33.W)); val primitives=UInt(64.W); val vertices=UInt(64.W)
  val paused=Bool(); val status=UInt(3.W)
}
class TransformFeedbackCommand extends Bundle {
  val action=UInt(3.W); val state=new TransformFeedbackState
}
class TransformFeedbackCommandReply extends Bundle {
  val state=new TransformFeedbackState; val status=UInt(3.W); val active=Bool(); val applied=Bool()
}
class TransformFeedbackPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new AssembledPrimitive(p)
  val context=UInt(32.W); val root=UInt(32.W); val program=UInt(32.W)
  val rasterDiscard=Bool()
  val mode=UInt(2.W);val cookie=UInt(32.W)
}
class TransformFeedbackPrimitiveResult extends Bundle {
  val identity=new GraphicsWorkIdentity; val sequence=UInt(64.W)
  val context=UInt(32.W); val root=UInt(32.W); val cookie=UInt(32.W)
  val status=UInt(3.W); val captured=Bool(); val bytes=UInt(32.W)
}

/** Bounded export before flat shading/clipping. Full-primitive bounds are
  * checked before any store. Dense words are coalesced into masked64-byte
  * writes; counters/cursors commit only after the final acknowledgement.
  * Complete source primitives are private until stores and public outputs
  * drain. No shader ISA change or extra vertex-output staging is required.
  *
  * Status0=success,1=encoding/bounds,2=identity/program/mode,3=memory fault,
  * 4=cancellation,5=object state,6=capacity. Partial faulted stores are not a
  * completed primitive and latch the object fault until end/eviction. CPU
  * coherence, translation and native descriptor/command transport are enclosing
  * contracts; memorySatp pins this primitive's root through its public result.
  */
class TransformFeedbackExport(config: GpuConfig=GpuConfig(),
    p: VertexShaderStageConfig=VertexShaderStageConfig(), contexts: Int=8) extends Module {
  require(config.xLen==32 && contexts>=1 && contexts<=16)
  private val slotBits=math.max(1,log2Ceil(contexts))
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new TransformFeedbackCommand))
    val commandReply=Decoupled(new TransformFeedbackCommandReply)
    val in=Flipped(Decoupled(new TransformFeedbackPrimitive(p)))
    val out=Decoupled(new AssembledPrimitive(p))
    val result=Decoupled(new TransformFeedbackPrimitiveResult)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val memorySatp=Output(UInt(32.W)); val cancel=Input(Bool())
    /** Abort only the current primitive/control; retain all object leases. */
    val abort=Input(Bool())
    val busy=Output(Bool()); val activeObjects=Output(UInt(log2Ceil(contexts+1).W))
  })
  private val states=Reg(Vec(contexts,new TransformFeedbackState))
  private val live=RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private object Phase extends ChiselEnum { val idle,commandNormalize,commandValidate,commandCommit,limits,validate,check,pack,issue,waitMemory,finish,drain=Value }
  private val phase=RegInit(Phase.idle)
  private val packet=Reg(new TransformFeedbackPrimitive(p)); private val slot=Reg(UInt(slotBits.W))
  // Snapshot one object's immutable metadata before planning. Do not put the
  // object-table mux, varying-limit addition and64 selector comparisons on
  // the same fault/status-register path.
  private val work=Reg(new TransformFeedbackState)
  private val sourceLimit=Reg(UInt(8.W));private val sourceGroups=Reg(Vec(8,Bool()))
  private val shapeValid=Reg(Bool());private val programValid=Reg(Bool())
  private val endAddresses=Reg(Vec(4,UInt(36.W)));private val boundsValid=Reg(Vec(4,Bool()))
  private val selected=Reg(Bool()); private val root=RegInit(0.U(32.W))
  private val ends=Reg(Vec(4,UInt(33.W))); private val byteCount=Reg(UInt(32.W))
  private val vertex=Reg(UInt(2.W)); private val buffer=Reg(UInt(2.W)); private val component=Reg(UInt(6.W))
  private val line=Reg(UInt(32.W)); private val mask=RegInit(0.U(64.W)); private val data=Reg(UInt(512.W))
  private val finalLine=RegInit(false.B); private val interrupted=RegInit(false.B)
  private val resetPending=RegInit(false.B)
  private val result=Reg(new TransformFeedbackPrimitiveResult)
  private val output=Reg(new AssembledPrimitive(p))
  private val outValid=RegInit(false.B); private val resultValid=RegInit(false.B)
  private val commandReplies=Module(new Queue(new TransformFeedbackCommandReply,1))
  io.commandReply<>commandReplies.io.deq
  commandReplies.io.enq.valid:=false.B;commandReplies.io.enq.bits:=0.U.asTypeOf(commandReplies.io.enq.bits)
  io.out.valid:=outValid;io.out.bits:=output;when(io.out.fire){outValid:=false.B}
  io.result.valid:=resultValid;io.result.bits:=result;when(io.result.fire){resultValid:=false.B}
  io.memorySatp:=root;io.activeObjects:=PopCount(live)
  io.busy:=phase=/=Phase.idle || commandReplies.io.deq.valid || resetPending
  private val idle=phase===Phase.idle && !resetPending && !io.cancel && !io.abort
  io.command.ready:=idle && commandReplies.io.enq.ready
  io.in.ready:=idle && !io.command.valid && !commandReplies.io.deq.valid

  private val control=Reg(new TransformFeedbackCommand)
  private val commandLegal=Reg(Bool())
  private val c=control;private val wanted=c.state.descriptor
  private val objectHits=VecInit((0 until contexts).map(i=>live(i) &&
    states(i).root===c.state.root && states(i).descriptor.context===wanted.context && states(i).descriptor.cookie===wanted.cookie))
  private val runningContext=VecInit((0 until contexts).map(i=>live(i) && !states(i).paused &&
    states(i).root===c.state.root && states(i).descriptor.context===wanted.context))
  private val hasObject=objectHits.asUInt.orR
  private val objectSlot=if(contexts==1)0.U(slotBits.W)else PriorityEncoder(objectHits.asUInt)
  private val free=VecInit(live.map(x=> !x));private val freeSlot=if(contexts==1)0.U(slotBits.W)else PriorityEncoder(free.asUInt)
  private def legalState(s: TransformFeedbackState): Bool={
    val d=s.descriptor
    val shape=d.kind<=2.U && d.buffers>=1.U && d.buffers<=4.U &&
      (!d.interleaved || d.buffers===1.U)
    val views=(0 until 4).map { i=>
      val used=i.U<d.buffers;val n=d.components(i);val view=d.views(i)
      val layout=Mux(used,n>0.U && n<=Mux(d.interleaved,64.U,4.U),n===0.U)
      val bounds= !view.base(1,0).orR && !view.limit(1,0).orR && view.limit<=(BigInt(1)<<32).U &&
        view.base.pad(33)<=view.limit && !s.offsets(i)(1,0).orR &&
        view.base.pad(34)+s.offsets(i).pad(34)<=view.limit.pad(34)
      layout && (!used || bounds)
    }.reduce(_ && _)
    val indices=(0 until 64).map { j=>
      val used=Mux(d.interleaved,j.U<d.components(0),
        (j/4).U<d.buffers && (j%4).U<d.components(j/4 min 3) && (j<16).B)
      !used || d.selectors(j)<(5+4*p.varyings).U
    }.reduce(_ && _)
    shape && views && indices && s.status<=4.U
  }
  when(io.command.fire){
    control:=io.command.bits;interrupted:=false.B;phase:=Phase.commandNormalize
  }
  when(phase===Phase.commandNormalize){
    // Normalize begin state before wide bounds validation. Command action
    // must not feed cursor arithmetic and the object-table write enable in
    // one cycle; the complete accepted command is a private snapshot.
    when(c.action===TransformFeedbackAction.begin.U){
      control.state.offsets.foreach(_:=0.U);control.state.primitives:=0.U
      control.state.vertices:=0.U;control.state.paused:=false.B;control.state.status:=0.U
    }
    phase:=Phase.commandValidate
  }
  when(phase===Phase.commandValidate){commandLegal:=legalState(c.state);phase:=Phase.commandCommit}
  when(phase===Phase.commandCommit && commandReplies.io.enq.ready){
    val reply=commandReplies.io.enq.bits;commandReplies.io.enq.valid:=true.B
    reply.state:=c.state;reply.status:=0.U;reply.active:=false.B;reply.applied:=false.B
    when(hasObject){reply.state:=states(objectSlot);reply.active:=true.B}
    val creates=c.action===TransformFeedbackAction.begin.U || c.action===TransformFeedbackAction.restore.U
    val source=Wire(new TransformFeedbackState);source:=c.state
    when(io.cancel || io.abort || interrupted){
      val oldFault=states(objectSlot).status
      reply.status:=Mux(hasObject && oldFault=/=0.U && oldFault=/=4.U,oldFault,4.U)
      reply.active:=hasObject && !io.cancel && !resetPending
      when(hasObject){reply.state:=states(objectSlot)}
    }.otherwise{
    when(creates){
      when(!commandLegal){reply.status:=1.U}
        .elsewhen(hasObject || (!source.paused && runningContext.asUInt.orR)){reply.status:=5.U
          when(hasObject){reply.state:=states(objectSlot);reply.active:=true.B}}
        .elsewhen(!free.asUInt.orR){reply.status:=6.U}
        .otherwise{states(freeSlot):=source;live(freeSlot):=true.B;reply.state:=source;reply.active:=true.B;reply.applied:=true.B}
    }.elsewhen(c.action>TransformFeedbackAction.invalidate.U){reply.status:=1.U}
      .elsewhen(!hasObject){reply.status:=5.U}
      .otherwise{
        val old=states(objectSlot);reply.state:=old;reply.active:=true.B
        when(old.descriptor.program=/=wanted.program){reply.status:=2.U}
          .elsewhen(c.action===TransformFeedbackAction.end.U){
            reply.status:=old.status;reply.active:=false.B;reply.state.paused:=false.B;live(objectSlot):=false.B;reply.applied:=true.B
          }.elsewhen(c.action===TransformFeedbackAction.pause.U){
            when(old.paused){reply.status:=5.U}.otherwise{states(objectSlot).paused:=true.B;reply.state.paused:=true.B;reply.applied:=true.B}
          }.elsewhen(c.action===TransformFeedbackAction.resume.U){
            when(!old.paused || runningContext.asUInt.orR){reply.status:=5.U}
              .otherwise{states(objectSlot).paused:=false.B;reply.state.paused:=false.B;reply.applied:=true.B}
          }.elsewhen(c.action===TransformFeedbackAction.evict.U){
            when(!old.paused){reply.status:=5.U}.otherwise{reply.active:=false.B;live(objectSlot):=false.B;reply.applied:=true.B}
          }.elsewhen(c.action===TransformFeedbackAction.invalidate.U){
            // Internal recovery after a failed native checkpoint publication.
            // Other running/paused objects,contexts and roots stay intact.
            reply.active:=false.B;reply.applied:=true.B;live(objectSlot):=false.B
          }
      }
    }
    when(commandReplies.io.enq.fire){phase:=Phase.idle}
  }
  private val hits=VecInit((0 until contexts).map(i=>live(i) && !states(i).paused &&
    states(i).descriptor.context===io.in.bits.context && states(i).root===io.in.bits.root))
  private val inputSlot=if(contexts==1)0.U(slotBits.W)else PriorityEncoder(hits.asUInt)
  when(io.in.fire){
    work:=states(inputSlot)
    packet:=io.in.bits;root:=io.in.bits.root;selected:=hits.asUInt.orR
    slot:=inputSlot
    result:=0.U.asTypeOf(result);result.identity:=io.in.bits.primitive.identity
    result.sequence:=io.in.bits.primitive.sequence;result.context:=io.in.bits.context;result.root:=io.in.bits.root
    result.cookie:=Mux(io.in.bits.mode===1.U,io.in.bits.cookie,0.U)
    interrupted:=false.B;mask:=0.U;data:=0.U;finalLine:=false.B
    phase:=Phase.limits
  }
  private val current=work;private val descriptor=current.descriptor
  private val prim=packet.primitive
  private val ownsCapture=selected && (packet.mode===0.U || (packet.mode===1.U &&
    packet.cookie===descriptor.cookie && packet.program===descriptor.program && prim.kind===descriptor.kind))
  private val wordCounts=descriptor.components
  private val bytesPerVertex=VecInit(wordCounts.map(n=>(n<<2).pad(33)))
  private val objectEnds=VecInit((0 until 4).map(i=>descriptor.views(i).base.pad(36)+
    current.offsets(i).pad(36)+(bytesPerVertex(i)*prim.vertexCount).pad(36)))

  private val shape=prim.kind<=2.U && prim.vertexCount===prim.kind+&1.U &&
    prim.provokingVertex<prim.vertexCount && prim.varyingSlots<=p.varyings.U
  private val sourceChecks=(0 until 64).map { j=>
    val used=Mux(descriptor.interleaved,j.U<wordCounts(0),
      (j/4).U<descriptor.buffers && (j%4).U<wordCounts(j/4 min 3) && (j<16).B)
    val index=descriptor.selectors(j)
    !used || index.pad(8)<sourceLimit || index===(4+4*p.varyings).U
  }
  when(phase===Phase.limits){
    // Bind reply identity before cancellation can bypass the later checks.
    when(selected && packet.mode=/=1.U){result.cookie:=descriptor.cookie}
    sourceLimit:=(prim.varyingSlots.pad(8)<<2)+4.U
    shapeValid:=shape;endAddresses:=objectEnds
    byteCount:=(wordCounts.map(_.pad(10)).reduce(_+&_)*prim.vertexCount)<<2
    phase:=Phase.validate
  }
  when(phase===Phase.validate){
    for(g<-0 until 8)sourceGroups(g):=sourceChecks.slice(g*8,g*8+8).reduce(_ && _)
    for(i<-0 until 4){
      boundsValid(i):=i.U>=descriptor.buffers || endAddresses(i)<=descriptor.views(i).limit.pad(36)
      ends(i):=endAddresses(i)-descriptor.views(i).base.pad(36)
    }
    programValid:=packet.program===descriptor.program && prim.kind===descriptor.kind
    phase:=Phase.check
  }
  when(phase===Phase.check){
    when(!shapeValid || packet.mode===3.U){result.status:=1.U;phase:=Phase.finish}
      .elsewhen(packet.mode===1.U && !selected){result.status:=5.U;phase:=Phase.finish}
      .elsewhen(packet.mode===1.U && (packet.cookie=/=descriptor.cookie || packet.program=/=descriptor.program || prim.kind=/=descriptor.kind)){result.status:=2.U;phase:=Phase.finish}
      .elsewhen(packet.mode===2.U && selected){result.status:=5.U;phase:=Phase.finish}
      .elsewhen(!selected){phase:=Phase.finish}
      .otherwise{
        val failure=Mux(current.status=/=0.U,current.status,Mux(!boundsValid.asUInt.andR,1.U,
          Mux(!programValid || !sourceGroups.asUInt.andR,2.U,0.U)))
        when(failure=/=0.U){result.status:=failure;states(slot).status:=failure;phase:=Phase.finish}
          .otherwise{
            vertex:=0.U;buffer:=0.U;component:=0.U;phase:=Phase.pack
          }
      }
  }
  private val raw=VecInit(prim.vertices(vertex).position.toSeq ++
    prim.vertices(vertex).varyings.flatMap(_.toSeq) ++ Seq(prim.vertices(vertex).pointSize))
  private val sourceIndex=Mux(descriptor.interleaved,component.pad(7),(buffer<<2)+component)
  private val value=raw(descriptor.selectors(sourceIndex(5,0))(log2Ceil(5+4*p.varyings)-1,0))
  private val addressWide=descriptor.views(buffer).base.pad(36)+current.offsets(buffer).pad(36)+
    (bytesPerVertex(buffer)*vertex).pad(36)+(component<<2).pad(36)
  private val address=addressWide(31,0);private val lineAddress=address & "hffffffc0".U
  private val wordMask=(15.U(64.W)<<address(5,0))(63,0)
  private val wordData=(value.pad(512)<<(address(5,0)<<3))(511,0)
  private val lastComponent=component.pad(7)+1.U===wordCounts(buffer)
  private val lastBuffer=buffer.pad(3)+1.U===descriptor.buffers
  private val lastVertex=vertex.pad(3)+1.U===prim.vertexCount
  when(phase===Phase.pack){
    when(mask.orR && line=/=lineAddress){phase:=Phase.issue}
      .otherwise{
        when(!mask.orR){line:=lineAddress;data:=wordData;mask:=wordMask}
          .otherwise{data:=data | wordData;mask:=mask | wordMask}
        when(lastComponent){component:=0.U
          when(lastBuffer){buffer:=0.U;vertex:=vertex+1.U}
            .otherwise{buffer:=buffer+1.U}
        }.otherwise{component:=component+1.U}
        when(lastComponent && lastBuffer && lastVertex){finalLine:=true.B;phase:=Phase.issue}
      }
  }
  io.memoryRequest.valid:=phase===Phase.issue
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=line;io.memoryRequest.bits.isWrite:=true.B
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.byteMask:=mask;io.memoryRequest.bits.writeData:=data
  io.memoryRequest.bits.transactionId:=0.U
  when(io.memoryRequest.fire){phase:=Phase.waitMemory}
  io.memoryResponse.ready:=phase===Phase.waitMemory || (phase===Phase.issue && io.memoryRequest.fire)
  when(io.memoryResponse.valid){assert(io.memoryResponse.ready && io.memoryResponse.bits.transactionId===0.U)}
  private def saturatingAdd(a: UInt,b: UInt): UInt={val sum=a+&b;Mux(sum(64),(BigInt(1)<<64).U-1.U,sum(63,0))}
  when(io.memoryResponse.fire){
    when(io.memoryResponse.bits.fault){result.status:=3.U;states(slot).status:=3.U;phase:=Phase.finish}
      .elsewhen(io.cancel || io.abort || interrupted){result.status:=4.U;phase:=Phase.finish}
      .elsewhen(finalLine){
        states(slot).offsets:=ends
        states(slot).primitives:=saturatingAdd(current.primitives,1.U)
        states(slot).vertices:=saturatingAdd(current.vertices,prim.vertexCount)
        result.captured:=true.B;result.bytes:=byteCount;phase:=Phase.finish
      }.otherwise{mask:=0.U;data:=0.U;phase:=Phase.pack}
  }
  when(phase===Phase.finish){
    output:=prim
    for(v<-0 until 3;n<-0 until p.varyings;c<-0 until 4){
      output.vertices(v).varyings(n)(c):=Mux(v.U<prim.vertexCount && prim.flatMask(n*4+c),
        prim.vertices(prim.provokingVertex).varyings(n)(c),prim.vertices(v).varyings(n)(c))
    }
    outValid:=result.status===0.U && !packet.rasterDiscard
    resultValid:=true.B;phase:=Phase.drain
  }
  when(phase===Phase.drain && !outValid && !resultValid){phase:=Phase.idle}
  when(io.cancel){
    when(live.asUInt.orR || phase=/=Phase.idle){resetPending:=true.B}
  }
  when(io.cancel || io.abort){
    when(phase=/=Phase.idle){interrupted:=true.B}
    // Offered memory/output/result payloads remain irrevocable. A private pack
    // or preflight can stop immediately; an owned store retains its reply.
    when(phase===Phase.limits || phase===Phase.validate || phase===Phase.check || phase===Phase.pack){
      result.status:=Mux(ownsCapture && current.status=/=0.U && current.status=/=4.U,current.status,4.U);phase:=Phase.finish}
    val working=phase===Phase.limits || phase===Phase.validate || phase===Phase.check ||
      phase===Phase.pack || phase===Phase.issue || phase===Phase.waitMemory
    // A draw abort may leave partial stores. Keep that object's cursor/counts
    // uncommitted and latch cancellation, while retaining peer leases. An
    // owned memory fault on this edge must remain primary over cancellation.
    when(io.abort && working && ownsCapture && states(slot).status===0.U &&
      !(io.memoryResponse.fire && io.memoryResponse.bits.fault)){
      states(slot).status:=4.U
    }
  }
  when(resetPending && phase===Phase.idle && !commandReplies.io.deq.valid){
    live.foreach(_:=false.B);resetPending:=false.B
  }
  for(i<-0 until contexts;j<-0 until i){
    assert(!(live(i) && live(j) && states(i).root===states(j).root &&
      states(i).descriptor.context===states(j).descriptor.context &&
      (states(i).descriptor.cookie===states(j).descriptor.cookie || (!states(i).paused && !states(j).paused))),
      "duplicate transform-feedback object or simultaneously running context")
  }
}
