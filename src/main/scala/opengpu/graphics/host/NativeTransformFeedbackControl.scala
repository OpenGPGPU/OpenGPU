package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class NativeTransformFeedbackRequest extends Bundle {
  val address=UInt(32.W);val limit=UInt(33.W)
  val context=UInt(32.W);val cookie=UInt(32.W);val program=UInt(32.W);val tag=UInt(32.W)
}
class NativeTransformFeedbackReply extends Bundle {
  val address=UInt(32.W);val context=UInt(32.W);val cookie=UInt(32.W);val tag=UInt(32.W)
  val status=UInt(3.W);val active=Bool();val recordComplete=Bool();val applied=Bool()
}

/** GTF1 is a64-byte-aligned512-byte control/checkpoint record. Input operation
  * result words42/43 must be zero. Output line2 contains the completion marker
  * and is written last,after every other line acknowledgement. A failed
  * unified completion invalidates the response regardless of partial bytes.
  */
object NativeTransformFeedbackRecord {
  val magic=BigInt("47544601",16);val version=1;val bytes=512;val words=128
  val contextWord=4;val cookieWord=5;val programWord=6;val rootWord=41
  val operationStatusWord=42;val operationFlagsWord=43;val selectorsWord=48
  val reserved: Seq[Int]=Seq(14,15)++(44 until 48)++(112 until 128)
  def decode(w: Vec[UInt],root: UInt): TransformFeedbackState={
    val s=Wire(new TransformFeedbackState);s:=0.U.asTypeOf(s)
    val d=s.descriptor
    d.context:=w(4);d.cookie:=w(5);d.program:=w(6);d.kind:=w(7)(1,0)
    d.interleaved:=w(8)(0);s.paused:=w(8)(1);d.buffers:=w(9)(2,0)
    for(i<-0 until 4){
      d.components(i):=w(10+i)(6,0)
      d.views(i).base:=w(16+3*i);d.views(i).limit:=Cat(w(18+3*i)(0),w(17+3*i))
      s.offsets(i):=Cat(w(29+2*i)(0),w(28+2*i))
    }
    s.primitives:=Cat(w(37),w(36));s.vertices:=Cat(w(39),w(38))
    s.status:=w(40)(2,0);s.root:=root
    for(i<-0 until 64)d.selectors(i):=w(48+i)(6,0)
    s
  }
  def encoding(w: Vec[UInt]): Bool={
    val header=w(0)===magic.U && w(1)===bytes.U && w(2)===version.U && w(3)<=5.U &&
      w(7)<=2.U && !w(8)(31,2).orR && w(9)<=4.U && w(40)<=4.U && w(42)===0.U && w(43)===0.U
    header && reserved.map(i=>w(i)===0.U).reduce(_ && _) &&
      (0 until 4).map(i=>w(10+i)<=64.U && w(18+3*i)<=1.U && w(29+2*i)<=1.U).reduce(_ && _) &&
      (0 until 64).map(i=>w(48+i)<=127.U).reduce(_ && _)
  }
  def encode(s: TransformFeedbackState,action: UInt,status: UInt,active: Bool,applied: Bool): Vec[UInt]={
    val w=Wire(Vec(words,UInt(32.W)));w.foreach(_:=0.U)
    val d=s.descriptor
    w(0):=magic.U;w(1):=bytes.U;w(2):=version.U;w(3):=action
    w(4):=d.context;w(5):=d.cookie;w(6):=d.program;w(7):=d.kind
    w(8):=Cat(s.paused,d.interleaved);w(9):=d.buffers
    for(i<-0 until 4){
      w(10+i):=d.components(i);w(16+3*i):=d.views(i).base
      w(17+3*i):=d.views(i).limit(31,0);w(18+3*i):=d.views(i).limit(32)
      w(28+2*i):=s.offsets(i)(31,0);w(29+2*i):=s.offsets(i)(32)
    }
    w(36):=s.primitives(31,0);w(37):=s.primitives(63,32)
    w(38):=s.vertices(31,0);w(39):=s.vertices(63,32)
    w(40):=s.status;w(41):=s.root;w(42):=status
    w(43):=Cat(applied,true.B,active)
    for(i<-0 until 64)w(48+i):=d.selectors(i)
    w
  }
}

/** One native control owns its immutable GPU record,VM root,core command and
  * public result. Rejected commands do not mutate existing objects. If a
  * command changes an object but checkpoint publication fails,invalidate only
  * that context/root/cookie before reporting the memory fault. Global reset
  * instead drains owned offers and clears all feedback objects via cancelCore.
  */
class NativeTransformFeedbackControl(config: GpuConfig=GpuConfig()) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new NativeTransformFeedbackRequest))
    val reply=Decoupled(new NativeTransformFeedbackReply)
    val control=Decoupled(new TransformFeedbackCommand)
    val controlReply=Flipped(Decoupled(new TransformFeedbackCommandReply))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val satp=Input(UInt(32.W));val memorySatp=Output(UInt(32.W))
    val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {
    val idle,readOffer,readReply,decode,controlOffer,controlReply,writeOffer,writeReply,
      invalidateOffer,invalidateReply,publish=Value
  }
  private val phase=RegInit(Phase.idle)
  private val request=Reg(new NativeTransformFeedbackRequest);private val root=RegInit(0.U(32.W))
  private val packet=Reg(Vec(NativeTransformFeedbackRecord.words,UInt(32.W)))
  private val state=Reg(new TransformFeedbackState);private val action=Reg(UInt(3.W))
  private val line=RegInit(0.U(3.W));private val changed=RegInit(false.B)
  private val interrupted=RegInit(false.B);private val response=Reg(new NativeTransformFeedbackReply)
  private val replies=Module(new Queue(new NativeTransformFeedbackReply,1))
  io.reply<>replies.io.deq;replies.io.enq.valid:=phase===Phase.publish;replies.io.enq.bits:=response
  when(replies.io.enq.fire){phase:=Phase.idle}
  io.busy:=phase=/=Phase.idle || replies.io.deq.valid
  io.command.ready:=phase===Phase.idle && !replies.io.deq.valid && !io.cancel
  io.memorySatp:=root
  private val stopped=io.cancel || interrupted
  when(io.cancel && io.busy){interrupted:=true.B}
  io.cancelCore:=(io.cancel || (interrupted && io.busy)) && !io.control.valid

  when(io.command.fire){
    request:=io.command.bits;root:=io.satp;interrupted:=false.B;changed:=false.B;line:=0.U
    response:=0.U.asTypeOf(response);response.address:=io.command.bits.address
    response.context:=io.command.bits.context;response.cookie:=io.command.bits.cookie;response.tag:=io.command.bits.tag
    val q=io.command.bits
    val valid= !q.address(5,0).orR && q.limit<=(BigInt(1)<<32).U &&
      q.address.pad(34)+NativeTransformFeedbackRecord.bytes.U<=q.limit.pad(34)
    phase:=Mux(valid,Phase.readOffer,Phase.publish)
    when(!valid){response.status:=1.U}
  }
  private val writing=phase===Phase.writeOffer
  // Line2 carries words32..47,including operation/status/root/counters. It is
  // the commit line; all other checkpoint bytes must be acknowledged first.
  private val writeLine=Mux(line===7.U,2.U,Mux(line>=2.U,line+1.U,line))
  private val physicalLine=Mux(writing,writeLine,line)
  io.memoryRequest.valid:=phase===Phase.readOffer || writing
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=request.address+(physicalLine<<6)
  io.memoryRequest.bits.isWrite:=writing;io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.byteMask:=((BigInt(1)<<64)-1).U
  io.memoryRequest.bits.writeData:=VecInit((0 until 16).map(i=>packet(Cat(physicalLine,i.U(4.W))))).asUInt
  io.memoryRequest.bits.transactionId:=0.U
  when(io.memoryRequest.fire){phase:=Mux(writing,Phase.writeReply,Phase.readReply)}
  io.memoryResponse.ready:=phase===Phase.readReply || phase===Phase.writeReply || io.memoryRequest.fire
  when(io.memoryResponse.valid){assert(io.memoryResponse.ready && io.memoryResponse.bits.transactionId===0.U,
    "native feedback control received an unowned memory response")}
  when(io.memoryResponse.fire){
    val write=phase===Phase.writeReply || writing
    when(io.memoryResponse.bits.fault){
      response.status:=3.U;response.recordComplete:=false.B;response.applied:=false.B
      when(write && changed){response.active:=false.B
        phase:=Mux(stopped,Phase.publish,Phase.invalidateOffer)
      }.otherwise{phase:=Phase.publish}
    }.elsewhen(stopped){
      when(response.status===0.U || response.status===4.U){response.status:=4.U}
      response.active:=false.B;response.applied:=false.B;response.recordComplete:=false.B;phase:=Phase.publish
    }.elsewhen(write){
      when(line===7.U){response.recordComplete:=true.B;response.applied:=changed;phase:=Phase.publish}
        .otherwise{line:=line+1.U;phase:=Phase.writeOffer}
    }.otherwise{
      val words=io.memoryResponse.bits.readData.asTypeOf(Vec(16,UInt(32.W)))
      for(i<-0 until 16)packet(Cat(line,i.U(4.W))):=words(i)
      when(line===7.U){phase:=Phase.decode}.otherwise{line:=line+1.U;phase:=Phase.readOffer}
    }
  }
  when(phase===Phase.decode){
    val valid=NativeTransformFeedbackRecord.encoding(packet)
    val identity=packet(4)===request.context && packet(5)===request.cookie &&
      packet(6)===request.program && packet(41)===root
    when(!valid){response.status:=1.U;phase:=Phase.publish}
      .elsewhen(!identity){response.status:=2.U;phase:=Phase.publish}
      .elsewhen(stopped){response.status:=4.U;phase:=Phase.publish}
      .otherwise{state:=NativeTransformFeedbackRecord.decode(packet,root);action:=packet(3);phase:=Phase.controlOffer}
  }
  private val invalidate=phase===Phase.invalidateOffer
  io.control.valid:=phase===Phase.controlOffer || invalidate
  io.control.bits.state:=state
  io.control.bits.action:=Mux(invalidate,TransformFeedbackAction.invalidate.U,action)
  when(io.control.fire){phase:=Mux(invalidate,Phase.invalidateReply,Phase.controlReply)}
  io.controlReply.ready:=phase===Phase.controlReply || phase===Phase.invalidateReply
  when(io.controlReply.valid){assert(io.controlReply.ready,"native feedback control received an unowned core reply")}
  when(io.controlReply.fire){
    val r=io.controlReply.bits
    assert(r.state.root===root && r.state.descriptor.context===request.context &&
      r.state.descriptor.cookie===request.cookie,"native feedback control lost its accepted scope")
    when(phase===Phase.invalidateReply){
      assert(r.status===0.U || r.status===5.U || (stopped && r.status===4.U),
        "scoped feedback invalidation failed after checkpoint publication fault")
      response.active:=false.B;response.applied:=false.B;phase:=Phase.publish
    }.otherwise{
      state:=r.state;changed:=r.applied;response.status:=r.status;response.active:=r.active
      when(stopped){
        when(r.status===0.U || r.status===4.U){response.status:=4.U}
        response.active:=false.B;phase:=Phase.publish
      }.otherwise{
        packet:=NativeTransformFeedbackRecord.encode(r.state,action,r.status,r.active,r.applied)
        line:=0.U;phase:=Phase.writeOffer
      }
    }
  }
}
