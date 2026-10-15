package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** Read-only line reuse within one explicitly bounded texture job.
  *
  * Words sharing a pending line join its fill. Other lines can miss in
  * parallel, and a line stays pinned until its accepted word replies have
  * been copied into the output queue. Invalidation revokes reuse, including
  * pending fills, but never stops reads needed to drain an admitted shader.
  * A new miss enters translation on the same edge as word acceptance.
  */
class BufferedTextureWordPort(
  config: GpuConfig = GpuConfig(),
  maxOutstanding: Int = 4,
  cacheLines: Int = 4,
  uncached: Boolean = false
) extends Module {
  require(maxOutstanding > 0)
  require(cacheLines > 0 && isPow2(cacheLines) && cacheLines <= maxOutstanding)
  private val lineIndexWidth = math.max(1, log2Ceil(cacheLines))
  private val wordIndexWidth = math.max(1, log2Ceil(maxOutstanding))

  val io = IO(new Bundle {
    val port = new OmWordToLineIO(config, 64, maxOutstanding)
    val invalidate = Input(Bool())
  })
  private val p = io.port
  private def at[T <: Data](values: Vec[T], index: UInt): T =
    if (values.length == 1) values(0) else values(index)
  private val lineTag = Reg(Vec(cacheLines, UInt(26.W)))
  private val lineData = Reg(Vec(cacheLines, UInt(512.W)))
  private val pending = RegInit(VecInit(Seq.fill(cacheLines)(false.B)))
  private val ready = RegInit(VecInit(Seq.fill(cacheLines)(false.B)))
  // A pending fill may serve its original waiters after invalidation, but it
  // must not serve a later job or a different translation of the same VA.
  private val reusable = RegInit(VecInit(Seq.fill(cacheLines)(false.B)))
  private val replacement = RegInit(0.U(lineIndexWidth.W))
  private val wordValid = RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val wordAddress = Reg(Vec(maxOutstanding, UInt(32.W)))
  private val wordLine = Reg(Vec(maxOutstanding, UInt(lineIndexWidth.W)))

  private val matches = VecInit((0 until cacheLines).map(i =>
    reusable(i) && !io.invalidate && lineTag(i) === p.in.bits.addr(31, 6)))
  private val matched = matches.asUInt.orR
  private val matchedLine = PriorityEncoder(matches)
  private val available = VecInit((0 until cacheLines).map(i =>
    !pending(i) && !VecInit((0 until maxOutstanding).map(w =>
      wordValid(w) && wordLine(w) === i.U)).asUInt.orR))
  private val empty = VecInit((0 until cacheLines).map(i =>
    available(i) && !reusable(i)))
  private val rotatedAvailable = VecInit((0 until cacheLines).map(i =>
    at(available, if (cacheLines == 1) 0.U else
      (replacement + i.U)(lineIndexWidth - 1, 0))))
  private val rotatedChoice = if (cacheLines == 1) 0.U else
    (replacement + PriorityEncoder(rotatedAvailable))(lineIndexWidth - 1, 0)
  private val victim = Mux(empty.asUInt.orR, PriorityEncoder(empty), rotatedChoice)
  private val selectedLine = Mux(matched, matchedLine, victim)
  private val freeWords = ~wordValid.asUInt
  private val hasWord = freeWords.orR
  private val allocatedWord = PriorityEncoder(freeWords)
  private val canMiss = available.asUInt.orR

  p.memoryRequest.valid := p.in.valid && hasWord && !matched && canMiss
  p.memoryRequest.bits := 0.U.asTypeOf(p.memoryRequest.bits)
  p.memoryRequest.bits.address := Cat(p.in.bits.addr(31, 6), 0.U(6.W))
  p.memoryRequest.bits.sizeLog2 := 6.U
  p.memoryRequest.bits.cachePolicy := Mux(uncached.B, CachePolicy.uncached,
    CachePolicy.cached)
  p.memoryRequest.bits.transactionId := victim
  p.in.ready := hasWord && (matched || (canMiss && p.memoryRequest.ready))

  when(p.in.fire) {
    assert(!p.in.bits.write, "texture line reuse is a read-only client")
    at(wordValid, allocatedWord) := true.B
    at(wordAddress, allocatedWord) := p.in.bits.addr
    at(wordLine, allocatedWord) := selectedLine
    when(!matched) {
      assert(p.memoryRequest.fire, "texture miss must enter translation with word acceptance")
      at(lineTag, victim) := p.in.bits.addr(31, 6)
      at(pending, victim) := true.B
      at(ready, victim) := false.B
      at(reusable, victim) := !io.invalidate
      replacement := (if (cacheLines == 1) 0.U else
        (victim + 1.U)(lineIndexWidth - 1, 0))
    }
  }

  private val responseId = p.memoryResponse.bits.transactionId
  private val responseInRange = responseId < cacheLines.U
  private val responseLine = responseId(lineIndexWidth - 1, 0)
  p.memoryResponse.ready := responseInRange && at(pending, responseLine)
  when(p.memoryResponse.valid) {
    assert(responseInRange && at(pending, responseLine),
      "texture fill response must identify a pending line")
  }
  when(p.memoryResponse.fire) {
    at(pending, responseLine) := false.B
    at(ready, responseLine) := true.B
    at(lineData, responseLine) := Mux(p.memoryResponse.bits.fault, 0.U,
      p.memoryResponse.bits.readData)
    when(p.memoryResponse.bits.fault) { at(reusable, responseLine) := false.B }
  }
  when(io.invalidate) { reusable.foreach(_ := false.B) }

  // Arbitrate only slot IDs. One shared line/word selection feeds the queue,
  // instead of replicating a 512-bit selector for every outstanding word.
  private val replies = Module(new RRArbiter(UInt(wordIndexWidth.W), maxOutstanding))
  for (w <- 0 until maxOutstanding) {
    replies.io.in(w).valid := wordValid(w) && at(ready, wordLine(w))
    replies.io.in(w).bits := w.U
  }
  // Refill a consumed reply on the same edge, sustaining one word per cycle.
  private val output = Module(new Queue(new OmMemoryResponse, 1, pipe = true))
  private val replyingWord = replies.io.out.bits
  output.io.enq.valid := replies.io.out.valid
  private val replyingAddress = at(wordAddress, replyingWord)
  output.io.enq.bits.addr := replyingAddress
  output.io.enq.bits.write := false.B
  output.io.enq.bits.data :=
    (at(lineData, at(wordLine, replyingWord)) >> (replyingAddress(5, 2) << 5))(31, 0)
  replies.io.out.ready := output.io.enq.ready
  when(replies.io.out.fire) { at(wordValid, replyingWord) := false.B }
  p.out <> output.io.deq
}
