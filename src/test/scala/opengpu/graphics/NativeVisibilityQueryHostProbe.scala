package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import opengpu.system.{GpuHostSystemAxi, GpuHostTestSupport}
import scala.collection.mutable

/** Drive GQV1 entirely through production AXI MMIO and record memory. The
  * dedicated record is an uncached Sv32 allocation so raw backing inspection
  * observes acknowledged stores without assuming CPU cache coherence.
  */
private[graphics] class NativeVisibilityQueryHostProbe(d: GpuHostSystemAxi,
    memory: mutable.Map[BigInt, Int], read: BigInt => BigInt,
    write: (BigInt, BigInt, BigInt) => Unit) extends GpuHostTestSupport {
  private val address = BigInt(0x30000000); private val physical = BigInt(0x18000000)
  private val context = BigInt(0x71000007) // Intentionally differs from descriptor identity7.
  private var cookie = 0; private var conservative = false; private var root = BigInt(0)
  private var prepared = false; private var running = false; private var frameStatus = 0
  private var frameValue = true
  private var checkedFrames = 0; private var commands = 0; private var abortedScopes = 0
  private var recordResetCuts = 0; private var emptyScopes = 0
  def active: Boolean = running
  private def word(a: BigInt): BigInt = (0 until 4).foldLeft(BigInt(0)) { (v, b) =>
    v | (BigInt(memory.getOrElse(a + b, 0)) << (8 * b))
  }
  private def put(a: BigInt, v: BigInt): Unit =
    for (b <- 0 until 4) memory(a + b) = ((v >> (8 * b)) & 255).toInt
  private def installMapping(): Unit = {
    root = axiRead(d, GpuCommandMmioRegs.VECTOR_SATP)
    assert(root == BigInt(0x80000030L))
    // A dedicated level1 superpage, RWAD and RSW cache-policy2 (uncached).
    put(0x30000 + (address >> 22) * 4, ((physical >> 12) << 10) | 0x2cf)
  }
  private def rawPop(): Unit = {
    axiWrite(d, GpuCommandMmioRegs.COMPLETION_POP, 1); axiWrite(d, RenderHostRegs.IRQ, 3)
  }
  private def stage(action: Int): Unit = {
    commands += 1
    axiWrite(d, GpuCommandMmioRegs.COMMAND_ID, 200 + action)
    axiWrite(d, GpuCommandMmioRegs.OPCODE, 8 + action)
    axiWrite(d, GpuCommandMmioRegs.SOURCE, address.toInt)
    axiWrite(d, GpuCommandMmioRegs.DESTINATION, (address + 64).toInt)
    axiWrite(d, GpuCommandMmioRegs.BYTES, 64)
    axiWrite(d, GpuCommandMmioRegs.PATTERN, if (conservative) 2 else 0)
    axiWrite(d, GpuCommandMmioRegs.WIDTH, context.toInt); axiWrite(d, GpuCommandMmioRegs.HEIGHT, cookie)
    axiWrite(d, GpuCommandMmioRegs.FLAGS, 0); axiWrite(d, GpuCommandMmioRegs.SUBMIT, 1)
  }
  private def query(action: Int, status: Int, available: Boolean, value: Boolean): Unit = {
    stage(action)
    axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0, expectedResponse = 2)
    serviceMemoryMaster(d, read, writeAckDelay = 7, readResponseDelay = 3,
      maxCycles = 100000)(write) { d.io.m_irq.peek().litToBoolean }
    val meta = axiRead(d, GpuCommandMmioRegs.COMPLETION)
    val opcode = ((meta >> 8) & 7) | (((meta >> 16) & 1) << 3)
    assert((meta & 255) == 200 + action && opcode == 8 + action &&
      ((meta >> 11) & 15) == status && meta.testBit(15) == (status == 0),
      s"query action=$action completion=${meta.toString(16)} expectedStatus=$status cookie=$cookie")
    val data = axiRead(d, GpuCommandMmioRegs.COMPLETION_BYTES_LO)
    assert(data == (if (value) 1 else 0) + (if (available) 2 else 0),
      s"query action=$action data=$data expectedAvailable=$available expectedValue=$value")
    assert(axiRead(d, GpuCommandMmioRegs.COMPLETION_BYTES_HI) == 0)
    for (i <- 0 until 12) assert(axiRead(d, 0x180 + i * 4) == 0, "query inherited render details")
    axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0, expectedResponse = 2)
    assert(axiRead(d, GpuCommandMmioRegs.VECTOR_SATP) == root)
    rawPop()
  }
  private def verifyRecord(available: Boolean, value: Boolean, status: Int): Unit = {
    val expected = Seq(VisibilityQueryRecord.magic, BigInt(64), context, BigInt(cookie),
      BigInt((status << 2) | (if (conservative) 2 else 0) | (if (available) 1 else 0)),
      BigInt(if (value) 1 else 0), root) ++ Seq.fill(9)(BigInt(0))
    assert((0 until 16).map(i => word(physical + i * 4)) == expected,
      s"query GPU record disagrees with MMIO cookie=$cookie status=$status")
  }
  private def begin(): Unit = {
    cookie += 1; conservative = cookie % 2 == 0
    query(0, 0, available = false, value = false)
    verifyRecord(available = false, value = false, status = 0); running = true
  }
  private def finish(status: Int, value: Boolean): Unit = {
    query(1, status, available = true, value = value)
    verifyRecord(available = true, value = value, status = status)
    running = false
    query(2, 0, available = true, value = false)
    query(3, status, available = true, value = value)
  }
  private def resetDuringRecordStore(): Unit = {
    cookie += 1; conservative = false; stage(0)
    var requested = false
    serviceMemoryMaster(d, read, writeAckDelay = 41, readResponseDelay = 3, maxCycles = 100000,
      onWriteAccepted = a => if (!requested && a == physical) {
        requested = true; axiWrite(d, GpuCommandMmioRegs.RESET, 1)
        axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0, expectedResponse = 2)
      })(write) { requested && d.io.m_irq.peek().litToBoolean }
    assert(requested, "query reset did not cut an accepted record store before B")
    assert((axiRead(d, GpuCommandMmioRegs.STATUS) & 10) == 0,
      "query reset retained an aborted completion or active reset")
    assert(axiRead(d, GpuCommandMmioRegs.VECTOR_SATP) == root)
    axiWrite(d, RenderHostRegs.IRQ, 3); axiWrite(d, GpuCommandMmioRegs.TLB_FLUSH, 1)
    recordResetCuts += 1
  }
  def beforeFrame(): Unit = {
    assert(!running); installMapping()
    if (!prepared) {
      for ((expected, i) <- NativeVisibilityQueryCapabilities.words.zipWithIndex)
        assert(axiRead(d, NativeVisibilityQueryCapabilities.base + i * 4) == expected)
      axiWrite(d, NativeVisibilityQueryCapabilities.base, 0, expectedResponse = 2)
      resetDuringRecordStore()
      begin(); finish(0, value = false); emptyScopes += 1; prepared = true
    }
    begin(); frameStatus = 0
  }
  def completed(status: Int,expectedValue: Boolean=true): Unit = { assert(running); frameStatus = status; frameValue=expectedValue }
  def afterFrame(): Unit = if (running) {
    val status = if (frameStatus == 0) 0 else if (frameStatus == 4) 4 else 3
    finish(status, value = status == 0 && frameValue); checkedFrames += 1
  }
  def aborted(): Unit = { assert(running); running = false; abortedScopes += 1 }
  def report(kind: String, local: Boolean, expectedFrames: Int, expectedAborts: Int = 0): Unit = {
    assert(!running && checkedFrames == expectedFrames && abortedScopes == expectedAborts)
    assert(recordResetCuts == 1 && emptyScopes == 1)
    println(s"NATIVE_VISIBILITY_${kind}_AXI local=$local CUs=4 frames=$checkedFrames abortedScopes=$abortedScopes queryCommands=$commands recordWriteResetCuts=$recordResetCuts emptyScopes=$emptyScopes opaqueContext=true cookiesAndRoot=true exactGpuRecords=true bothTargets=true")
  }
}
