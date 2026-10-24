package opengpu.graphics

import chisel3._
import chisel3.util._

/** Resident-word read/math/write service. The surrounding packet window owns
  * alias ordering. Its terminal is produced only after every read, arithmetic
  * operation and offered write/ACK has drained. Parent clear/load/valid/dirty
  * policy belongs outside this worker; it never allocates a second tile array.
  */
class TaggedTypedSampleWorker(materializeDepth: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val begin=Input(Bool());val cancel=Input(Bool())
    val in=Flipped(Decoupled(new TypedSamplePacketWork(8)))
    val out=Decoupled(new TypedSamplePacketDone(8))
    val request=Vec(8,Decoupled(new TypedTileWordRequest))
    val response=Vec(8,Flipped(Decoupled(new TypedTileWordResponse)))
    val virtualDepth=if(materializeDepth)Some(Input(Vec(2,Bool())))else None
    val fault=Output(Bool());val drained=Output(Bool())
  })
  private val bound=RegInit(false.B);private val aborted=RegInit(false.B);private val failed=RegInit(false.B)
  private val stop=aborted || io.cancel || failed
  private val valid=RegInit(0.U(128.W));private val epoch=Reg(Vec(128,Bool()))
  private val addresses=Reg(Vec(128,Vec(4,UInt(32.W))))
  private val results=Reg(Vec(128,new TypedSampleTerminal))
  private val faults=RegInit(VecInit(Seq.fill(128)(false.B)))
  private val faultOffsets=Reg(Vec(128,UInt(17.W)))
  private val logicalReads=Reg(Vec(128,UInt(3.W)))
  private val writeIssued=Reg(Vec(128,UInt(4.W)));private val writeDone=Reg(Vec(128,UInt(4.W)))
  private val writeGood=Reg(Vec(128,UInt(4.W)))
  private val requests=Seq.fill(8)(Module(new Queue(new TypedTileWordRequest,2,pipe=true)))
  private val finished=Module(new Queue(UInt(8.W),128))
  private val terminal=Module(new Queue(new TypedSamplePacketDone(8),2,pipe=true))
  private val math=Module(new StreamingTypedSampleMath)
  math.io.begin:=io.begin;math.io.cancel:=stop && !io.begin
  for(p<-0 until 8)io.request(p)<>requests(p).io.deq
  io.out<>terminal.io.deq;io.fault:=failed

  // Only read latency needs the large source-color/state payload. Keep eight
  // such buffers; the 128 lifetime contexts hold addresses and terminal state.
  private val readHead=RegInit(0.U(3.W));private val readTail=RegInit(0.U(3.W))
  private val readCount=RegInit(0.U(4.W));private val readValid=RegInit(0.U(8.W))
  private val readToken=Reg(Vec(8,UInt(8.W)));private val readPayload=Reg(Vec(8,new TypedSampleMathInput))
  private val readWords=Seq.fill(4)(Reg(Vec(8,UInt(64.W))))
  private val readIssued=Reg(Vec(8,UInt(4.W)));private val readDone=Reg(Vec(8,UInt(4.W)))
  private val readGood=Reg(Vec(8,UInt(4.W)))
  private val readVirtual=if(materializeDepth)Some(Reg(Vec(8,UInt(2.W))))else None
  private val initializeDepth=if(materializeDepth)Some(Reg(Vec(128,UInt(2.W))))else None
  private val token=io.in.bits.token;private val slot=token(6,0);private val packet=io.in.bits.packet
  private val needs=Wire(Vec(4,Bool()))
  for(pair<-0 until 2){
    needs(pair):= !stop && !packet.cancelled && packet.colorActive(2*pair+1,2*pair).orR
    needs(pair+2):= !stop && !packet.cancelled && packet.active(2*pair+1,2*pair).orR &&
      (packet.tests.depthEnable || packet.tests.stencilEnable)
  }
  private val readReady=VecInit((0 until 4).map(p=> !needs(p) || requests(p).io.enq.ready)).asUInt.andR
  io.in.ready:=bound && !io.begin && !valid(slot) && (readCount<8.U || releaseRead) && readReady
  private val admitted=io.in.fire
  for(p<-0 until 4){
    requests(p).io.enq.valid:=admitted && needs(p)
    requests(p).io.enq.bits:=0.U.asTypeOf(new TypedTileWordRequest)
    requests(p).io.enq.bits.address:=packet.words(p)
    requests(p).io.enq.bits.tag:=Cat(0.U(3.W),token,readTail,p.U(2.W))
  }
  when(admitted){
    epoch(slot):=token(7);addresses(slot):=packet.words;faults(slot):=false.B;logicalReads(slot):=0.U
    readToken(readTail):=token;readIssued(readTail):=0.U;readDone(readTail):= ~needs.asUInt;readGood(readTail):=0.U
    val saved=readPayload(readTail)
    saved.token:=token;saved.cancelled:=packet.cancelled || stop
    saved.active:=packet.active;saved.colorActive:=packet.colorActive;saved.channelMask:=packet.channelMask
    saved.colors:=packet.colors;saved.depths:=packet.depths;saved.frontFacing:=packet.frontFacing
    saved.tests:=packet.tests;saved.blend:=packet.blend;saved.oldColors:=VecInit(Seq.fill(2)(0.U(64.W)))
    saved.oldDepthStencil:=VecInit(Seq.fill(2)(0.U(64.W)))
    readVirtual.foreach(_(readTail):=0.U)
    for(p<-0 until 4)readWords(p)(readTail):=0.U
    readTail:=readTail+1.U
  }
  math.io.in.valid:=readCount=/=0.U && readValid(readHead) && readDone(readHead).andR
  math.io.in.bits:=readPayload(readHead)
  private val mathToken=readToken(readHead);private val mathSlot=mathToken(6,0)
  math.io.in.bits.cancelled:=readPayload(readHead).cancelled || stop || faults(mathSlot)
  for(pair<-0 until 2){math.io.in.bits.oldColors(pair):=readWords(pair)(readHead)
    math.io.in.bits.oldDepthStencil(pair):=readWords(pair+2)(readHead)}
  private def releaseRead: Bool=math.io.in.fire
  when(releaseRead){
    assert(valid(mathSlot) && epoch(mathSlot)===mathToken(7),"sample read owner changed before math admission")
    logicalReads(mathSlot):=PopCount(readPayload(readHead).active &
      Cat(Fill(2,readGood(readHead)(3)),Fill(2,readGood(readHead)(2))))
    initializeDepth.foreach(_(mathSlot):=readVirtual.get(readHead))
    readHead:=readHead+1.U
  }
  readValid:=(readValid& ~Mux(releaseRead,UIntToOH(readHead,8),0.U(8.W)))|
    Mux(admitted,UIntToOH(readTail,8),0.U(8.W))
  switch(Cat(admitted,releaseRead)){
    is("b10".U){readCount:=readCount+1.U};is("b01".U){readCount:=readCount-1.U}
  }

  private val readResponses=Wire(Vec(4,Bool()));private val writeResponses=Wire(Vec(4,Bool()))
  for(p<-0 until 4){
    val reply=io.response(p);val tag=reply.bits.tag;val r=tag(4,2);val t=tag(12,5);val s=t(6,0)
    val owned=readValid(r) && readToken(r)===t && valid(s) && epoch(s)===t(7) &&
      readIssued(r)(p) && !readDone(r)(p) && tag(1,0)===p.U && !tag(15,13).orR
    reply.ready:=bound && !io.begin && owned;readResponses(p):=reply.fire
    when(reply.valid && bound && !io.begin){assert(owned,"sample read reply has no issued generation")}
    when(reply.fire){readWords(p)(r):=reply.bits.data}
  }
  for(r<-0 until 8){
    val issued=VecInit((0 until 4).map(p=>io.request(p).fire && io.request(p).bits.tag(4,2)===r.U)).asUInt
    val completed=VecInit((0 until 4).map(p=>readResponses(p) && io.response(p).bits.tag(4,2)===r.U)).asUInt
    val good=VecInit((0 until 4).map(p=>readResponses(p) && !io.response(p).bits.fault && io.response(p).bits.tag(4,2)===r.U)).asUInt
    when(readValid(r) && !(admitted && readTail===r.U)){
      readIssued(r):=readIssued(r)|issued;readDone(r):=readDone(r)|completed;readGood(r):=readGood(r)|good
      readVirtual.foreach { bits=>
        val virtual=VecInit((0 until 2).map(p=>good(p+2) && io.virtualDepth.get(p))).asUInt
        bits(r):=bits(r)|virtual
      }
    }
  }

  private val plan=math.io.out.bits;private val planSlot=plan.token(6,0)
  // A virtual DS word becomes resident in the same full-word write as its
  // updates. Read-only tests still initialize it, without logical write effects.
  private val dsWrites=plan.depthStencilWrite | initializeDepth.map(_(planSlot)).getOrElse(0.U(2.W))
  private val writeNeeds=Mux(stop || faults(planSlot) || plan.result.status=/=0.U,0.U(4.W),Cat(dsWrites,plan.colorWrite))
  private val writeReady=VecInit((0 until 4).map(p=> !writeNeeds(p) || requests(p+4).io.enq.ready)).asUInt.andR
  math.io.out.ready:=finished.io.enq.ready && writeReady
  finished.io.enq.valid:=math.io.out.fire;finished.io.enq.bits:=plan.token
  for(p<-0 until 4){
    val request=requests(p+4).io.enq
    request.valid:=math.io.out.fire && writeNeeds(p)
    request.bits.address:=addresses(planSlot)(p);request.bits.write:=true.B
    request.bits.data:=(if(p<2)plan.colors(p)else plan.depthStencil(p-2))
    request.bits.mask:= ~0.U(64.W);request.bits.tag:=Cat(0.U(6.W),plan.token,p.U(2.W))
    val reply=io.response(p+4);val tag=reply.bits.tag;val t=tag(9,2);val s=t(6,0)
    val owned=valid(s) && epoch(s)===t(7) && writeIssued(s)(p) && !writeDone(s)(p) && tag(1,0)===p.U && !tag(15,10).orR
    reply.ready:=bound && !io.begin && owned;writeResponses(p):=reply.fire
    when(reply.valid && bound && !io.begin){assert(owned,"sample write reply has no issued generation")}
  }
  when(math.io.out.fire){
    assert(valid(planSlot) && epoch(planSlot)===plan.token(7),"sample writeback lost lifetime ownership")
    results(planSlot):=plan.result;writeIssued(planSlot):=0.U;writeDone(planSlot):= ~writeNeeds;writeGood(planSlot):=0.U
  }
  // Combine simultaneous acknowledgements before updating any context bitset.
  for(s<-0 until 128){
    val issued=VecInit((0 until 4).map(p=>io.request(p+4).fire && io.request(p+4).bits.tag(8,2)===s.U)).asUInt
    val completed=VecInit((0 until 4).map(p=>writeResponses(p) && io.response(p+4).bits.tag(8,2)===s.U)).asUInt
    val good=VecInit((0 until 4).map(p=>writeResponses(p) && !io.response(p+4).bits.fault && io.response(p+4).bits.tag(8,2)===s.U)).asUInt
    when(valid(s) && !(math.io.out.fire && planSlot===s.U)){
      writeIssued(s):=writeIssued(s)|issued;writeDone(s):=writeDone(s)|completed;writeGood(s):=writeGood(s)|good
    }
    val bad=VecInit((0 until 8).map(p=>io.response(p).fire && io.response(p).bits.fault &&
      (if(p<4)io.response(p).bits.tag(11,5)===s.U else io.response(p).bits.tag(8,2)===s.U))).asUInt
    when(bad.orR && !faults(s)){
      faults(s):=true.B;faultOffsets(s):=(addresses(s)(PriorityEncoder(bad)(1,0))<<3)(16,0)
    }
  }
  private val anyFault=VecInit(io.response.map(r=>r.fire && r.bits.fault)).asUInt.orR
  when(anyFault){failed:=true.B}
  when(io.cancel){aborted:=true.B}

  private val doneToken=finished.io.deq.bits;private val doneSlot=doneToken(6,0)
  private val done=finished.io.deq.valid && writeDone(doneSlot).andR
  terminal.io.enq.valid:=done;finished.io.deq.ready:=done && terminal.io.enq.ready
  terminal.io.enq.bits.token:=doneToken;terminal.io.enq.bits.result:=results(doneSlot)
  private val result=terminal.io.enq.bits.result
  result.status:=Mux(faults(doneSlot),3.U,Mux(stop && results(doneSlot).status===0.U,4.U,results(doneSlot).status))
  result.faultOffset:=Mux(faults(doneSlot),faultOffsets(doneSlot),0.U)
  result.dsReads:=logicalReads(doneSlot)
  result.dsFills:=initializeDepth.map(bits=>PopCount(bits(doneSlot)&writeGood(doneSlot)(3,2))).getOrElse(0.U)
  result.colorWritten:=results(doneSlot).colorWritten & Cat(Fill(2,writeGood(doneSlot)(1)),Fill(2,writeGood(doneSlot)(0)))
  result.depthWritten:=results(doneSlot).depthWritten & Cat(Fill(2,writeGood(doneSlot)(3)),Fill(2,writeGood(doneSlot)(2)))
  result.stencilWritten:=results(doneSlot).stencilWritten & Cat(Fill(2,writeGood(doneSlot)(3)),Fill(2,writeGood(doneSlot)(2)))
  when(terminal.io.enq.fire){assert(valid(doneSlot) && epoch(doneSlot)===doneToken(7),"sample terminal lost its generation")}
  valid:=(valid& ~Mux(terminal.io.enq.fire,UIntToOH(doneSlot,128),0.U(128.W)))|
    Mux(admitted,UIntToOH(slot,128),0.U(128.W))
  io.drained:= !valid.orR && !readValid.orR && readCount===0.U && math.io.drained && !finished.io.deq.valid && !terminal.io.deq.valid &&
    !VecInit(requests.map(_.io.deq.valid)).asUInt.orR
  when(io.begin){assert(io.drained && !io.cancel,"sample worker begin requires complete drain");bound:=true.B;aborted:=false.B;failed:=false.B}
}
