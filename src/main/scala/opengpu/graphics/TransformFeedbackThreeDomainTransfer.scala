package opengpu.graphics

import chisel3._
import chisel3.util._

/** Serialize control commands and move all live feedback objects between three
  * drained pipeline owners. The caller must prevent either renderer from
  * starting while a switch is offered or in progress. Each owner must implement
  * the same bounded TransformFeedbackExport control contract.
  *
  * A failed internal transfer is fail-closed: neither owner may run until a
  * global reset clears both tables. The completion status identifies the failed
  * command. An accepted external command or internal offer is never withdrawn.
  */
class TransformFeedbackThreeDomainTransfer(contexts:Int=8) extends Module {
  require(contexts>=1 && contexts<=16)
  private val indexBits=math.max(1,log2Ceil(contexts))
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new TransformFeedbackCommand))
    val commandReply=Decoupled(new TransformFeedbackCommandReply)
    val switch=Flipped(Decoupled(UInt(2.W)))
    /** 0 success, 1..6 owner error, 4 reset. */
    val switchReply=Decoupled(UInt(3.W))
    val ownerCommand=Vec(3,Decoupled(new TransformFeedbackCommand))
    val ownerReply=Flipped(Vec(3,Decoupled(new TransformFeedbackCommandReply)))
    /** True only after the enclosing renderer and its memory traffic drain. */
    val ownerIdle=Input(Vec(3,Bool()))
    val ownerCancel=Output(Vec(3,Bool()))
    val selected=Output(UInt(2.W));val quiesce=Output(Bool());val poisoned=Output(Bool());val busy=Output(Bool())
    val cancel=Input(Bool())
  })
  private object Phase extends ChiselEnum {val idle,externalOffer,externalReply,scan,pauseOffer,pauseReply,
    evictOffer,evictReply,restoreOffer,restoreReply,resumeOffer,resumeReply,switchResult=Value}
  private val phase=RegInit(Phase.idle)
  private val selected=RegInit(0.U(2.W));private val target=Reg(UInt(2.W));private val index=RegInit(0.U(indexBits.W))
  private val states=Reg(Vec(contexts,new TransformFeedbackState));private val live=RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val command=Reg(new TransformFeedbackCommand);private val transfer=Reg(new TransformFeedbackState)
  private val wasPaused=Reg(Bool());private val switchStatus=RegInit(0.U(3.W));private val poisoned=RegInit(false.B)
  private val resetPending=RegInit(false.B);private val resetArmed=RegInit(false.B);private val resetSeen=io.cancel || resetPending
  private val offer=phase===Phase.externalOffer || phase===Phase.pauseOffer || phase===Phase.evictOffer ||
    phase===Phase.restoreOffer || phase===Phase.resumeOffer
  private val coreReset=resetSeen && !offer
  io.ownerCancel.foreach(_:=coreReset)
  when(io.cancel){resetPending:=true.B}
  when(coreReset){resetArmed:=true.B}
  // An idle owner with live objects does not assert busy until the cycle after
  // it observes reset. Wait at least one edge before treating it as drained.
  private val resetDrained=coreReset && resetArmed && io.ownerIdle.asUInt.andR && !io.ownerReply.map(_.valid).reduce(_ || _)
  when(resetDrained){resetPending:=false.B;resetArmed:=false.B;live.foreach(_:=false.B);poisoned:=false.B}

  io.selected:=selected;io.poisoned:=poisoned
  io.quiesce:=phase=/=Phase.idle || io.command.valid || io.switch.valid || resetSeen || poisoned
  io.busy:=phase=/=Phase.idle || resetSeen || poisoned
  io.command.ready:=phase===Phase.idle && !io.switch.valid && !resetSeen && !poisoned && io.ownerIdle.asUInt.andR
  io.switch.ready:=phase===Phase.idle && !resetSeen && !poisoned && io.ownerIdle.asUInt.andR && io.switch.bits<3.U
  io.commandReply.valid:=false.B;io.commandReply.bits:=0.U.asTypeOf(io.commandReply.bits)
  io.switchReply.valid:=phase===Phase.switchResult;io.switchReply.bits:=switchStatus
  when(io.switchReply.fire){phase:=Phase.idle}
  for(i<-0 until 3){io.ownerCommand(i).valid:=false.B;io.ownerCommand(i).bits:=0.U.asTypeOf(io.ownerCommand(i).bits);io.ownerReply(i).ready:=false.B}
  private val commandOwner=Mux(phase===Phase.restoreOffer || phase===Phase.restoreReply || phase===Phase.resumeOffer || phase===Phase.resumeReply,target,selected)
  private val reply=io.ownerReply(commandOwner)
  private val action=MuxLookup(phase.asUInt,TransformFeedbackAction.pause.U)(Seq(
    Phase.pauseOffer.asUInt->TransformFeedbackAction.pause.U,Phase.evictOffer.asUInt->TransformFeedbackAction.evict.U,
    Phase.restoreOffer.asUInt->TransformFeedbackAction.restore.U,Phase.resumeOffer.asUInt->TransformFeedbackAction.resume.U))
  private val outbound=Wire(new TransformFeedbackCommand);outbound:=0.U.asTypeOf(outbound)
  outbound.action:=action;outbound.state:=Mux(phase===Phase.restoreOffer || phase===Phase.resumeOffer,transfer,states(index))
  when(phase===Phase.externalOffer){outbound:=command}
  when(offer){io.ownerCommand(commandOwner).valid:=true.B;io.ownerCommand(commandOwner).bits:=outbound}

  when(io.command.fire){command:=io.command.bits;phase:=Phase.externalOffer}
  when(io.switch.fire){target:=io.switch.bits;index:=0.U;switchStatus:=0.U
    when(io.switch.bits===selected){phase:=Phase.switchResult}.otherwise{phase:=Phase.scan}}
  when(phase===Phase.externalOffer && io.ownerCommand(commandOwner).fire){phase:=Phase.externalReply}
  when(phase===Phase.externalReply){
    io.commandReply.valid:=reply.valid;io.commandReply.bits:=reply.bits;reply.ready:=io.commandReply.ready
    when(reply.fire){
      val r=reply.bits;val key=r.state.descriptor
      val hits=VecInit((0 until contexts).map(i=>live(i) && states(i).root===r.state.root &&
        states(i).descriptor.context===key.context && states(i).descriptor.cookie===key.cookie))
      val free=VecInit(live.map(x=> !x))
      val hitSlot=if(contexts==1)0.U(indexBits.W)else PriorityEncoder(hits.asUInt)
      val creates=command.action===TransformFeedbackAction.begin.U || command.action===TransformFeedbackAction.restore.U
      val removes=command.action===TransformFeedbackAction.end.U || command.action===TransformFeedbackAction.evict.U || command.action===TransformFeedbackAction.invalidate.U
      when(r.applied && !resetSeen){
        assert(r.state.root===command.state.root && key.context===command.state.descriptor.context &&
          key.cookie===command.state.descriptor.cookie && key.program===command.state.descriptor.program,
          "feedback command reply changed object identity")
        when(creates){assert(!hits.asUInt.orR && free.asUInt.orR,"feedback owner directory overflow or duplicate")
          val slot=if(contexts==1)0.U(indexBits.W)else PriorityEncoder(free.asUInt);live(slot):=true.B;states(slot):=r.state}
          .elsewhen(removes){assert(hits.asUInt.orR,"feedback owner directory lost removed object");live(hitSlot):=false.B}
          .otherwise{assert(hits.asUInt.orR,"feedback owner directory lost updated object");states(hitSlot):=r.state}
      }
      phase:=Phase.idle
    }
  }
  private def next():Unit={when(index===(contexts-1).U){selected:=target;switchStatus:=0.U;phase:=Phase.switchResult}
    .otherwise{index:=index+1.U;phase:=Phase.scan}}
  when(phase===Phase.scan){
    when(resetSeen){switchStatus:=4.U;phase:=Phase.switchResult}
      .elsewhen(!live(index)){next()}
      .otherwise{wasPaused:=states(index).paused;phase:=Mux(states(index).paused,Phase.evictOffer,Phase.pauseOffer)}
  }
  when(phase===Phase.pauseOffer && io.ownerCommand(commandOwner).fire){phase:=Phase.pauseReply}
  when(phase===Phase.evictOffer && io.ownerCommand(commandOwner).fire){phase:=Phase.evictReply}
  when(phase===Phase.restoreOffer && io.ownerCommand(commandOwner).fire){phase:=Phase.restoreReply}
  when(phase===Phase.resumeOffer && io.ownerCommand(commandOwner).fire){phase:=Phase.resumeReply}
  private val internalReply=phase===Phase.pauseReply || phase===Phase.evictReply || phase===Phase.restoreReply || phase===Phase.resumeReply
  private val replyScope=reply.bits.state.descriptor
  private val matchingScope=reply.bits.state.root===states(index).root && replyScope.context===states(index).descriptor.context &&
    replyScope.cookie===states(index).descriptor.cookie && replyScope.program===states(index).descriptor.program
  when(internalReply){reply.ready:=true.B
    when(reply.fire){
      when(resetSeen){switchStatus:=4.U;phase:=Phase.switchResult}
        .elsewhen(reply.bits.status=/=0.U || !reply.bits.applied || !matchingScope){
          switchStatus:=Mux(reply.bits.status=/=0.U,reply.bits.status,Mux(!matchingScope,2.U,5.U));poisoned:=true.B;phase:=Phase.switchResult}
        .elsewhen(phase===Phase.pauseReply){phase:=Phase.evictOffer}
        .elsewhen(phase===Phase.evictReply){transfer:=reply.bits.state;phase:=Phase.restoreOffer}
        .elsewhen(phase===Phase.restoreReply){when(wasPaused){states(index):=reply.bits.state;next()}.otherwise{phase:=Phase.resumeOffer}}
        .otherwise{states(index):=reply.bits.state;next()}
    }
  }
  when(resetDrained && phase===Phase.idle){selected:=0.U}
}
