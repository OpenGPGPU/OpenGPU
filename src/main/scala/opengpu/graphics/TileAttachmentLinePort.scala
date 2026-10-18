package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig,MobileGpuConfig}

class TileAttachmentLineEvents extends Bundle {
  /** Requested attachment words returned from a line or the local line cache. */
  val loadBytes=UInt(3.W)
  /** Actual issued write-mask bytes, including faulted issued requests. */
  val storeBytes=UInt(7.W)
  val resolveBytes=UInt(7.W)
  val readLine=Bool()
  val writeLine=Bool()
  val readHit=Bool()
}
class TileAttachmentLinePortIO(config: GpuConfig,transactions: Int)
  extends OmWordToLineIO(config,64,transactions) {
  val begin=Input(Bool())
  val abort=Input(Bool())
  val resolve=Input(Bool())
  val responseFault=Output(Bool())
  /** Actual failed write-line acknowledgement, independent of word replies. */
  val writeFault=Output(Bool())
  val drained=Output(Bool())
  val events=Output(new TileAttachmentLineEvents)
}

/** Attachment-only gather/scatter adapter. At most two write lines gather
  * disjoint words. An independent read transaction and a four-line cache
  * serve lazy loads. One reserved word credit belongs to reads. Every input
  * word retains its address and exactly one acknowledgement, including when
  * a masked line response expands into several write acknowledgements.
  *
  * A full credit window, read hazard, buffer pressure or bounded idle timer
  * seals partial writes. The timer prevents deadlock when the upstream writer
  * waits for acknowledgements before it can supply another word. Once a line
  * is offered, its payload is immutable. Abort drops only unoffered work and
  * synthesizes drain replies; offered/issued lines retain ownership to ack.
  * Faulted reads never fill the cache. Begin requires complete drain and
  * invalidates all cached data, so another tile/VM cannot inherit a VA hit.
  *
  * Optional bufferedWriteAcks acknowledges captured write words, allowing a
  * line to collect sixteen words through a smaller private reply window.
  * Its enclosing pass must retain ownership until drained and consume the
  * separate writeFault pulse. Reads still wait for actual conflicting writes;
  * repeated unoffered writes may combine to their final attachment value
  * and resolve classification.
  */
class TileAttachmentLinePort(config: GpuConfig=GpuConfig(),transactions: Int=9,
  idleCycles: Int=32,cacheLines: Int=MobileGpuConfig.AttachmentReadCacheLines,
  bufferedWriteAcks: Boolean=false) extends Module {
  require(cacheLines>=1 && cacheLines<=4 && (cacheLines & (cacheLines-1))==0)
  require(config.xLen==32)
  require(transactions>=2 && transactions<=17)
  require(idleCycles>=2 && idleCycles<=256)
  private val maxWrites=transactions-1
  private val groups=math.min(2,maxWrites)
  private val groupBits=log2Ceil(groups).max(1)
  private val slotBits=log2Ceil(transactions).max(1)
  private val readTag=transactions-1
  val io=IO(new TileAttachmentLinePortIO(config,transactions))
  private def at[T <: Data](v: Vec[T],index: UInt): T=if (groups==1) v(0) else v(index(groupBits-1,0))
  private val live=RegInit(0.U(transactions.W))
  private val complete=RegInit(0.U(transactions.W))
  private val addresses=Reg(Vec(transactions,UInt(32.W)))
  private val writes=Reg(Vec(transactions,Bool()))
  private val data=Reg(Vec(transactions,UInt(32.W)))
  private val faults=Reg(Vec(transactions,Bool()))
  private val sourced=Reg(Vec(transactions,Bool()))
  private val groupLive=RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val sealedLine=RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val issued=RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val lines=Reg(Vec(groups,UInt(26.W)))
  private val words=Reg(Vec(groups,Vec(16,UInt(32.W))))
  private val masks=RegInit(VecInit(Seq.fill(groups)(0.U(16.W))))
  private val resolves=RegInit(VecInit(Seq.fill(groups)(0.U(16.W))))
  private val members=RegInit(VecInit(Seq.fill(groups)(0.U(transactions.W))))
  private val age=Reg(Vec(groups,UInt(log2Ceil(idleCycles).max(1).W)))
  private val readLive=RegInit(false.B)
  private val readIssued=RegInit(false.B)
  private val readLine=Reg(UInt(26.W))
  private val readSlot=Reg(UInt(slotBits.W))
  private val cacheBits=log2Ceil(cacheLines).max(1)
  private val cacheValid=RegInit(VecInit(Seq.fill(cacheLines)(false.B)))
  private val cacheLine=Reg(Vec(cacheLines,UInt(26.W)))
  private val cacheData=Reg(Vec(cacheLines,UInt(512.W)))
  private val replacement=RegInit(0.U(cacheBits.W))
  private val hitPending=RegInit(false.B)
  private val hitWay=Reg(UInt(cacheBits.W))
  private val hitSlot=Reg(UInt(slotBits.W))
  private val hitWord=Reg(UInt(4.W))
  private def cacheAt[T <: Data](v: Vec[T],index: UInt): T=if (cacheLines==1) v(0) else v(index)
  private val aborting=RegInit(false.B)
  private val faulted=RegInit(false.B)
  private val offered=RegInit(false.B)
  private val offeredRead=Reg(Bool())
  private val offeredGroup=Reg(UInt(groupBits.W))
  private val replyValid=RegInit(false.B)
  private val replySlot=Reg(UInt(slotBits.W))

  private val responseId=io.memoryResponse.bits.transactionId
  private val writeResponse=responseId<groups.U && at(groupLive,responseId) && at(issued,responseId)
  private val readResponse=responseId===readTag.U && readLive && readIssued
  io.memoryResponse.ready:=writeResponse || readResponse
  private val memoryFault=io.memoryResponse.fire && io.memoryResponse.bits.fault
  io.writeFault:=memoryFault && writeResponse
  private val stop=aborting || io.abort || memoryFault
  private val inputLine=io.in.bits.addr(31,6)
  private val inputWord=io.in.bits.addr(5,2)
  private val wordMask=UIntToOH(inputWord,16)
  private val free= ~live
  private val allocated=PriorityEncoder(free)
  private val allocatedMask=UIntToOH(allocated,transactions)
  private val duplicate=(0 until transactions).map(i=>live(i) && addresses(i)===io.in.bits.addr).reduce(_ || _)
  private val writeCount=PopCount(VecInit((0 until transactions).map(i=>live(i) && writes(i))))
  private val haveRead=(0 until transactions).map(i=>live(i) && !writes(i)).reduce(_ || _)
  private val gatheringWords=PopCount((0 until groups).map(i=>
    Mux(groupLive(i) && !sealedLine(i) && !issued(i),members(i),0.U)).reduce(_ | _))
  private val sameLine=VecInit((0 until groups).map(i=>groupLive(i) && lines(i)===inputLine))
  private val merge=VecInit((0 until groups).map(i=>sameLine(i) && !sealedLine(i) && !issued(i)))
  private val groupFree=VecInit(groupLive.map(!_))
  private val mergeSlot=if (groups==1) 0.U(groupBits.W) else PriorityEncoder(merge)
  private val freeGroup=if (groups==1) 0.U(groupBits.W) else PriorityEncoder(groupFree)
  private val chosenGroup=Mux(merge.asUInt.orR,mergeSlot,freeGroup)
  private val canWrite=writeCount<maxWrites.U && !(readLive && readLine===inputLine) &&
    (merge.asUInt.orR || (!sameLine.asUInt.orR && groupFree.asUInt.orR))
  private val hits=VecInit((0 until cacheLines).map(i=>cacheValid(i) && cacheLine(i)===inputLine))
  private val hit=hits.asUInt.orR && !sameLine.asUInt.orR
  private val hitIndex=if (cacheLines==1) 0.U(cacheBits.W) else PriorityEncoder(hits)
  private val cacheFree=VecInit(cacheValid.map(!_))
  private val fillWay=if (cacheLines==1) 0.U(cacheBits.W) else Mux(cacheFree.asUInt.orR,PriorityEncoder(cacheFree),replacement)
  private val canRead= !haveRead && !sameLine.asUInt.orR && (hit || !readLive)
  io.in.ready:= !io.begin && free.orR && !duplicate && (stop || Mux(io.in.bits.write,canWrite,canRead))
  private val inputFire=io.in.fire
  private val writeFire=inputFire && io.in.bits.write && !stop

  io.memoryRequest.valid:=offered
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=Cat(Mux(offeredRead,readLine,at(lines,offeredGroup)),0.U(6.W))
  io.memoryRequest.bits.isWrite:= !offeredRead
  io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryRequest.bits.transactionId:=Mux(offeredRead,readTag.U,offeredGroup)
  io.memoryRequest.bits.writeData:=Mux(offeredRead,0.U,
    VecInit((0 until 16).map(i=>Mux(at(masks,offeredGroup)(i),at(words,offeredGroup)(i),0.U(32.W)))).asUInt)
  io.memoryRequest.bits.byteMask:=Mux(offeredRead,0.U,
    VecInit((0 until 16).map(i=>Mux(at(masks,offeredGroup)(i),15.U(4.W),0.U(4.W)))).asUInt)
  when(io.memoryRequest.fire) {
    offered:=false.B
    when(offeredRead) { readIssued:=true.B }
      .otherwise { at(issued,offeredGroup):=true.B }
  }
  private val waiting=VecInit((0 until groups).map(i=>groupLive(i) && sealedLine(i) && !issued(i)))
  // A registered offer removes response/input ready from the external payload.
  when(!offered && !stop) {
    when(readLive && !readIssued) { offered:=true.B; offeredRead:=true.B }
      .elsewhen(waiting.asUInt.orR) {
        offered:=true.B; offeredRead:=false.B
        offeredGroup:=(if (groups==1) 0.U else PriorityEncoder(waiting))
      }
  }

  private val completedRead=VecInit((0 until transactions).map(i=>complete(i) && !writes(i))).asUInt
  private val remainingComplete=complete & ~Mux(replyValid,UIntToOH(replySlot,transactions),0.U)
  private val nextReply=Mux((completedRead & remainingComplete).orR,
    PriorityEncoder(completedRead & remainingComplete),PriorityEncoder(remainingComplete))
  io.out.valid:=replyValid
  io.out.bits.addr:=addresses(replySlot); io.out.bits.write:=writes(replySlot); io.out.bits.data:=data(replySlot)
  io.responseFault:=faults(replySlot)
  when(!replyValid || io.out.fire) {
    replyValid:=remainingComplete.orR
    when(remainingComplete.orR) { replySlot:=nextReply }
  }
  private val retired=Mux(io.out.fire,UIntToOH(replySlot,transactions),0.U)
  private val completing=WireDefault(0.U(transactions.W))
  // Disjoint slot masks from input, line responses and abort drain can coincide.
  private val completions=collection.mutable.ArrayBuffer.empty[UInt]
  when(inputFire) {
    assert(io.in.bits.addr(1,0)===0.U)
    addresses(allocated):=io.in.bits.addr; writes(allocated):=io.in.bits.write
    faults(allocated):=Mux(stop,faulted || memoryFault,false.B)
    sourced(allocated):=false.B; data(allocated):=0.U
    when(stop) {
      // This irrevocable upstream word had not become an external request.
    }.elsewhen(io.in.bits.write) {
      for (i <- 0 until cacheLines) when(cacheLine(i)===inputLine) { cacheValid(i):=false.B }
      at(groupLive,chosenGroup):=true.B
      when(!merge.asUInt.orR) {
        at(lines,chosenGroup):=inputLine; at(masks,chosenGroup):=wordMask
        at(resolves,chosenGroup):=Mux(io.resolve,wordMask,0.U)
        at(members,chosenGroup):=allocatedMask
        at(sealedLine,chosenGroup):=false.B; at(issued,chosenGroup):=false.B
      }.otherwise {
        at(masks,chosenGroup):=at(masks,chosenGroup) | wordMask
        // Capture acknowledgements permit this word to be overwritten before
        // issue. Its final value and classification must stay together; other
        // words in the same line retain their resolve bits.
        val retained=if(bufferedWriteAcks) at(resolves,chosenGroup) & ~wordMask else at(resolves,chosenGroup)
        at(resolves,chosenGroup):=retained | Mux(io.resolve,wordMask,0.U)
        at(members,chosenGroup):=at(members,chosenGroup) | allocatedMask
      }
      at(words,chosenGroup)(inputWord):=io.in.bits.data
      at(age,chosenGroup):=0.U
    }.elsewhen(hit) {
      // Separate tag validation from the cache-word mux and reply registers.
      hitPending:=true.B; hitWay:=hitIndex; hitSlot:=allocated; hitWord:=inputWord
    }.otherwise { readLive:=true.B; readIssued:=false.B; readLine:=inputLine; readSlot:=allocated }
  }
  completions+=Mux(inputFire && stop,allocatedMask,0.U)
  if(bufferedWriteAcks) {
    // Capture transfers ownership to the bounded line buffers. An enclosing
    // pass owner must wait for drained and consume writeFault even after all
    // private word replies have retired. A later B cannot address these slots:
    // they may already belong to newer words.
    completions+=Mux(writeFire,allocatedMask,0.U)
  }
  completions+=Mux(hitPending,UIntToOH(hitSlot,transactions),0.U)
  when(hitPending) {
    data(hitSlot):=Mux(stop,0.U,cacheAt(cacheData,hitWay).asTypeOf(Vec(16,UInt(32.W)))(hitWord))
    faults(hitSlot):=faulted || memoryFault
    sourced(hitSlot):= !stop; hitPending:=false.B
  }
  for (g <- 0 until groups) {
    val merging=writeFire && chosenGroup===g.U
    val pressure=io.in.valid && ((!io.in.bits.write && sameLine(g)) ||
      (io.in.bits.write && !groupFree.asUInt.orR && !merge.asUInt.orR))
    when(groupLive(g) && !sealedLine(g) && !issued(g) && !merging) {
      val fullCredits=if(bufferedWriteAcks) false.B else gatheringWords===maxWrites.U
      when(age(g)===(idleCycles-1).U || fullCredits || masks(g).andR || pressure) {
        sealedLine(g):=true.B
      }.otherwise { age(g):=age(g)+1.U }
    }
    val dropped=stop && groupLive(g) && !issued(g) && !(offered && !offeredRead && offeredGroup===g.U)
    val responded=io.memoryResponse.fire && writeResponse && responseId===g.U
    val completed=dropped || responded
    if(!bufferedWriteAcks) { completions+=Mux(completed,members(g),0.U) }
    when(completed) {
      if(!bufferedWriteAcks) {
        for (i <- 0 until transactions) when(members(g)(i)) {
          data(i):=0.U; faults(i):=Mux(responded,io.memoryResponse.bits.fault,faulted || memoryFault)
        }
      }
      groupLive(g):=false.B; sealedLine(g):=false.B; issued(g):=false.B
    }
  }
  private val droppedRead=stop && readLive && !readIssued && !(offered && offeredRead)
  private val respondedRead=io.memoryResponse.fire && readResponse
  completions+=Mux(droppedRead || respondedRead,UIntToOH(readSlot,transactions),0.U)
  when(droppedRead || respondedRead) {
    readLive:=false.B; readIssued:=false.B
    faults(readSlot):=Mux(respondedRead,io.memoryResponse.bits.fault,faulted || memoryFault)
    data(readSlot):=Mux(respondedRead,
      io.memoryResponse.bits.readData.asTypeOf(Vec(16,UInt(32.W)))(addresses(readSlot)(5,2)),0.U)
    sourced(readSlot):=respondedRead
    when(respondedRead && !io.memoryResponse.bits.fault && !stop) {
      cacheAt(cacheValid,fillWay):=true.B; cacheAt(cacheLine,fillWay):=readLine
      cacheAt(cacheData,fillWay):=io.memoryResponse.bits.readData
      replacement:=(if (cacheLines==1) 0.U else (fillWay+1.U)(cacheBits-1,0))
    }
  }
  completing:=completions.reduce(_ | _)
  live:=(live | Mux(inputFire,allocatedMask,0.U)) & ~retired
  complete:=(complete | completing) & ~retired
  when(stop) { aborting:=true.B; cacheValid.foreach(_:=false.B) }
  when(memoryFault) { faulted:=true.B }
  io.drained:= !live.orR && !groupLive.asUInt.orR && !readLive && !offered && !replyValid && !hitPending
  when(io.begin) {
    assert(io.drained,"attachment line/cache ownership must drain before rebinding")
    aborting:=false.B; faulted:=false.B; cacheValid.foreach(_:=false.B); replacement:=0.U
  }
  io.events.loadBytes:=Mux(io.out.fire && !io.out.bits.write && sourced(replySlot),4.U,0.U)
  io.events.storeBytes:=Mux(io.memoryRequest.fire && !offeredRead,PopCount(at(masks,offeredGroup))<<2,0.U)
  io.events.resolveBytes:=Mux(io.memoryRequest.fire && !offeredRead,
    PopCount(at(masks,offeredGroup) & at(resolves,offeredGroup))<<2,0.U)
  io.events.readLine:=io.memoryRequest.fire && offeredRead
  io.events.writeLine:=io.memoryRequest.fire && !offeredRead
  io.events.readHit:=inputFire && !io.in.bits.write && hit && !stop
}
