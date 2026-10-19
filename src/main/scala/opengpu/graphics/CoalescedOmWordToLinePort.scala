package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.ComputeMemoryRequest

/** Combines framebuffer word writes before the coherent line-memory port.
  * Each word retains its own reply credit until its acknowledgement is consumed.
  * A line acknowledgement completes all of its constituent words; buffering a
  * write alone never acknowledges it. Conflicting word addresses are held until
  * the preceding response is consumed, including responses stalled upstream.
  * Partial lines flush after a bounded interval, or when word credits run out.
  */
class CoalescedOmWordToLinePort(
  config: GpuConfig = GpuConfig(),
  maxOutstanding: Int = 16,
  uncached: Boolean = false,
  groups: Int = 4,
  flushCycles: Int = 16
) extends Module {
  require(maxOutstanding >= groups && groups >= 2)
  require(flushCycles >= 1)
  val io = IO(new OmWordToLineIO(config, 64, maxOutstanding))
  private val idWidth = math.max(1, log2Ceil(maxOutstanding))
  private val ageWidth = math.max(1, log2Ceil(flushCycles + 1))

  private val valid = RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val done = RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val addresses = Reg(Vec(maxOutstanding, UInt(32.W)))
  private val writes = Reg(Vec(maxOutstanding, Bool()))
  private val readData = Reg(Vec(maxOutstanding, UInt(32.W)))
  private val responseFault=Reg(Vec(maxOutstanding,Bool()))
  private val freeWords = ~valid.asUInt
  private val wordId = PriorityEncoder(freeWords)
  private val conflict = (0 until maxOutstanding).map(i =>
    valid(i) && addresses(i) === io.in.bits.addr).reduce(_ || _)
  private val canAccept = freeWords.orR && !conflict
  private val lineAddress = Cat(io.in.bits.addr(31, 6), 0.U(6.W))
  private val wordIndex = io.in.bits.addr(5, 2)
  private val inputMask = (15.U(64.W) << (wordIndex << 2))(63, 0)
  private val inputData = (io.in.bits.data.pad(512) << (wordIndex << 5))(511, 0)
  private val inputDataMask = ("hffffffff".U(512.W) << (wordIndex << 5))(511, 0)

  // Merge only reads whose physical line request is still in flight. No
  // completed line is cached: later CPU updates remain visible on this
  // uncached client. Writes exclude their word from older read snapshots.
  private val readValid = RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val readSent = RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val readExcludedWords = Reg(Vec(groups, UInt(16.W)))
  private val readAddress = Reg(Vec(groups, UInt(32.W)))
  private val readMembers = Reg(Vec(groups, UInt(maxOutstanding.W)))
  private val readTag = Reg(Vec(groups, UInt(idWidth.W)))
  private val readMatches = VecInit((0 until groups).map(i =>
    readValid(i) && !readExcludedWords(i)(wordIndex) && readAddress(i) === lineAddress &&
      !(io.memoryResponse.valid && io.memoryResponse.bits.transactionId === readTag(i)))).asUInt
  private val freeReadGroups = ~readValid.asUInt
  private val readGroup = Mux(readMatches.orR, PriorityEncoder(readMatches), PriorityEncoder(freeReadGroups))
  private val pendingWriteWords = (0 until maxOutstanding).map(i =>
    Mux(valid(i) && writes(i) && addresses(i)(31, 6) === io.in.bits.addr(31, 6),
      UIntToOH(addresses(i)(5, 2), 16), 0.U(16.W))).reduce(_ | _)

  private val groupValid = RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val groupIssued = RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val groupSent = RegInit(VecInit(Seq.fill(groups)(false.B)))
  private val groupAddress = Reg(Vec(groups, UInt(32.W)))
  private val groupData = Reg(Vec(groups, UInt(512.W)))
  private val groupMask = Reg(Vec(groups, UInt(64.W)))
  private val groupMembers = Reg(Vec(groups, UInt(maxOutstanding.W)))
  private val groupTag = Reg(Vec(groups, UInt(idWidth.W)))
  private val groupAge = Reg(Vec(groups, UInt(ageWidth.W)))
  private val sealedGroup = VecInit((0 until groups).map(i =>
    groupValid(i) && !groupIssued(i) &&
      (groupAge(i) >= flushCycles.U || groupMask(i).andR || !freeWords.orR)))
  private val matching = VecInit((0 until groups).map(i =>
    groupValid(i) && !groupIssued(i) && !sealedGroup(i) &&
      groupAddress(i) === lineAddress)).asUInt
  private val freeGroups = ~groupValid.asUInt
  private val hasGroup = matching.orR || freeGroups.orR
  private val selectedGroup = Mux(matching.orR, PriorityEncoder(matching), PriorityEncoder(freeGroups))

  // Snapshot complete requests into a queue before exposing them downstream.
  // Once a group is eligible, it no longer accepts merges, even under stalls.
  private val requestArb = Module(new RRArbiter(
    new ComputeMemoryRequest(config, 64, maxOutstanding), groups + 1))
  private val requestQueue = Module(new Queue(
    new ComputeMemoryRequest(config, 64, maxOutstanding), 2, pipe = true))
  requestQueue.io.enq <> requestArb.io.out
  io.memoryRequest <> requestQueue.io.deq
  for (i <- 0 to groups) {
    requestArb.io.in(i).bits := 0.U.asTypeOf(requestArb.io.in(i).bits)
    requestArb.io.in(i).bits.sizeLog2 := 6.U
    requestArb.io.in(i).bits.cachePolicy := Mux(uncached.B, CachePolicy.uncached, CachePolicy.cached)
  }
  requestArb.io.in(0).valid := io.in.valid && !io.in.bits.write && canAccept &&
    !readMatches.orR && freeReadGroups.orR
  requestArb.io.in(0).bits.address := lineAddress
  requestArb.io.in(0).bits.transactionId := wordId
  io.in.ready := canAccept && Mux(io.in.bits.write, hasGroup,
    readMatches.orR || (freeReadGroups.orR && requestArb.io.in(0).ready))
  for (i <- 0 until groups) {
    requestArb.io.in(i + 1).valid := sealedGroup(i)
    requestArb.io.in(i + 1).bits.address := groupAddress(i)
    requestArb.io.in(i + 1).bits.writeData := groupData(i)
    requestArb.io.in(i + 1).bits.byteMask := groupMask(i)
    requestArb.io.in(i + 1).bits.isWrite := true.B
    requestArb.io.in(i + 1).bits.transactionId := groupTag(i)
    when(groupValid(i) && !groupIssued(i) && groupAge(i) < flushCycles.U) {
      groupAge(i) := groupAge(i) + 1.U
    }
    when(requestArb.io.in(i + 1).fire) { groupIssued(i) := true.B }
    when(io.memoryRequest.fire && io.memoryRequest.bits.isWrite &&
        io.memoryRequest.bits.transactionId === groupTag(i) && groupValid(i)) {
      groupSent(i) := true.B
    }
    when(io.in.fire && io.in.bits.write && selectedGroup === i.U) {
      groupValid(i) := true.B
      when(matching.orR) {
        groupData(i) := (groupData(i) & ~inputDataMask) | inputData
        groupMask(i) := groupMask(i) | inputMask
        groupMembers(i) := groupMembers(i) | UIntToOH(wordId, maxOutstanding)
      }.otherwise {
        groupIssued(i) := false.B
        groupSent(i) := false.B
        groupAddress(i) := lineAddress
        groupData(i) := inputData
        groupMask(i) := inputMask
        groupMembers(i) := UIntToOH(wordId, maxOutstanding)
        groupTag(i) := wordId
        groupAge(i) := 0.U
      }
    }
  }
  when(io.in.fire) {
    valid(wordId) := true.B
    done(wordId) := false.B
    addresses(wordId) := io.in.bits.addr
    writes(wordId) := io.in.bits.write
  }
  for (i <- 0 until groups) {
    when(io.in.fire && !io.in.bits.write && readGroup === i.U) {
      when(readMatches.orR) {
        readMembers(i) := readMembers(i) | UIntToOH(wordId, maxOutstanding)
      }.otherwise {
        readValid(i) := true.B
        readSent(i) := false.B
        readAddress(i) := lineAddress
        readTag(i) := wordId
        readMembers(i) := UIntToOH(wordId, maxOutstanding)
        // A read started while a prior write is pending must not admit a
        // later reader after that write has completed, using an old snapshot.
        readExcludedWords(i) := pendingWriteWords
      }
    }
    when(io.in.fire && io.in.bits.write && readValid(i) && readAddress(i) === lineAddress) {
      readExcludedWords(i) := readExcludedWords(i) | UIntToOH(wordIndex, 16)
    }
    when(io.memoryRequest.fire && !io.memoryRequest.bits.isWrite &&
        readValid(i) && readTag(i) === io.memoryRequest.bits.transactionId) {
      readSent(i) := true.B
    }
  }

  private val responseId = io.memoryResponse.bits.transactionId
  private val readResponses = VecInit((0 until groups).map(i =>
    readValid(i) && readSent(i) && readTag(i) === responseId))
  private val writeResponses = VecInit((0 until groups).map(i =>
    groupValid(i) && groupIssued(i) && groupSent(i) && groupTag(i) === responseId))
  io.memoryResponse.ready := readResponses.asUInt.orR || writeResponses.asUInt.orR
  when(io.memoryResponse.valid) {
    assert(PopCount(writeResponses) + PopCount(readResponses) === 1.U,
      "coalesced response must identify exactly one outstanding request")
  }
  private val responseWords = io.memoryResponse.bits.readData.asTypeOf(Vec(16, UInt(32.W)))
  for (i <- 0 until groups) {
    when(io.memoryResponse.fire && readResponses(i)) {
      readValid(i) := false.B
      for (j <- 0 until maxOutstanding) {
        when(readMembers(i)(j)) {
          readData(j) := responseWords(addresses(j)(5, 2))
          done(j) := true.B
          responseFault(j):=io.memoryResponse.bits.fault
        }
      }
    }
    when(io.memoryResponse.fire && writeResponses(i)) {
      groupValid(i) := false.B
      for (j <- 0 until maxOutstanding) {
        when(groupMembers(i)(j)) { done(j) := true.B;responseFault(j):=io.memoryResponse.bits.fault }
      }
    }
  }

  private class WordReply extends Bundle {
    val id = UInt(idWidth.W)
    val response = new OmMemoryResponse
    val fault=Bool()
  }
  private val replies = Module(new RRArbiter(new WordReply, maxOutstanding))
  private val replyQueue = Module(new Queue(new WordReply, 2, pipe = true))
  replyQueue.io.enq <> replies.io.out
  io.out.valid := replyQueue.io.deq.valid
  io.out.bits := replyQueue.io.deq.bits.response
  io.responseFault:=io.out.valid && replyQueue.io.deq.bits.fault
  replyQueue.io.deq.ready := io.out.ready
  for (i <- 0 until maxOutstanding) {
    replies.io.in(i).valid := valid(i) && done(i)
    replies.io.in(i).bits.id := i.U
    replies.io.in(i).bits.fault:=responseFault(i)
    replies.io.in(i).bits.response.addr := addresses(i)
    replies.io.in(i).bits.response.write := writes(i)
    replies.io.in(i).bits.response.data := Mux(writes(i), 0.U, readData(i))
    when(replies.io.in(i).fire) { done(i) := false.B }
  }
  when(io.out.fire) { valid(replyQueue.io.deq.bits.id) := false.B }
}
