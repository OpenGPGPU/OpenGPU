package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Private shader adapters expose no service for the scalar-FP extension port. */
class KernelShaderAvailabilitySpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private val cease = BigInt("30500073", 16)
  private val scalarRead = (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 15) | (5 << 12) | (10 << 7) | 0x57
  private val scalarWrite = (BigInt(0x10) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 12) | (10 << 7) | 0x57
  private val cases = Seq(
    ("no-backend-scalar-add", BigInt("00000553", 16), false, true),
    ("no-backend-scalar-load", BigInt("0000a507", 16), false, true),
    ("no-backend-vector-FP-read", scalarRead, false, true),
    ("no-backend-vector-FP-write", scalarWrite, false, true),
    ("F-disabled-vector-FP-read", scalarRead, true, false),
    ("F-disabled-vector-FP-write", scalarWrite, true, false),
    ("enabled-scalar-vector-bridge", BigInt(0), true, true))
  private def run(kind: String, word: BigInt, backend: Boolean, enabled: Boolean, gpu: GpuConfig): Unit = {
    val capacity = gpu.lanes * gpu.warps
    simulate(new KernelShaderStage(gpu, enableScalarFpu = backend)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B); d.io.l1InvalidateDone.ready.poke(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(at: BigInt, value: BigInt): Unit = for (i <- 0 until 4) memory(at + i) = ((value >> (8 * i)) & 255).toInt
      def line(at: BigInt): BigInt = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(memory.getOrElse(at + i, 0)) << (8 * i)))
      val vectorSetup = (BigInt("c1027057", 16) & ~(BigInt(31) << 15)) | (BigInt(gpu.lanes) << 15)
      val moveOne = (BigInt(0x17) << 26) | (BigInt(1) << 25) | (1 << 15) | (3 << 12) | (10 << 7) | 0x57
      val convert = (BigInt(0x12) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (3 << 15) | (1 << 12) | (10 << 7) | 0x57
      val addVector = (BigInt(1) << 25) | (BigInt(10) << 20) | (10 << 15) | (1 << 12) | (10 << 7) | 0x57
      // Every warp writes its own lanes at kernarg + 1024 + 4 * localLinearBase.
      val store = Seq(BigInt("40008293", 16), BigInt("00241313", 16), BigInt("006282b3", 16),
        (BigInt(1) << 25) | (5 << 15) | (6 << 12) | (10 << 7) | 0x27, cease)
      val healthy = Seq(vectorSetup, moveOne, convert, addVector) ++ store
      val supported = kind == "enabled-scalar-vector-bridge"
      val bridge = Seq(BigInt("0000a087", 16), vectorSetup, moveOne, convert, scalarRead) ++ store
      // Warp IDs name reusable physical slots. Attribute each logical warp by
      // a distinct fault PC selected from its trusted local-linear base.
      val faultPCs = (0 until gpu.warps).map(i => BigInt(0x1100 + i * gpu.lanes * 4)).toSet
      val first = if (supported) bridge else Seq(BigInt("00241293", 16),
        BigInt("00001337", 16), BigInt("10030313", 16), BigInt("006282b3", 16), BigInt("00028067", 16))
      first.zipWithIndex.foreach { case (v, i) => put(0x1000 + 4 * i, v) }
      if (!supported) faultPCs.foreach { pc => put(pc, word); put(pc + 4, cease) }
      healthy.zipWithIndex.foreach { case (v, i) => put(0x2000 + 4 * i, v) }
      put(0x8000, BigInt("3f800000", 16))
      for (at <- 0x83f0 until 0x8400 + capacity * 4 + 16) memory(BigInt(at)) = 0xa5
      case class Reply(id: BigInt, data: BigInt, due: Int)
      val pending = mutable.Queue.empty[Reply]
      val trapped = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      val written = mutable.Set.empty[BigInt]
      var cycle = 0
      var firstPhase = true
      var scalarDataReads = 0
      def tick(): Unit = {
        val reply = pending.headOption.filter(_.due <= cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) }
        if (reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
        if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val q = d.io.memoryRequest.bits; val at = q.address.peek().litValue
          assert((at & 63) == 0)
          if (q.isWrite.peek().litToBoolean) {
            assert(!firstPhase || supported, "unavailable operation must not write memory")
            val mask = q.byteMask.peek().litValue; val data = q.writeData.peek().litValue
            for (i <- 0 until 64 if mask.testBit(i)) {
              assert(at + i >= 0x8400 && at + i < 0x8400 + capacity * 4, s"guard write at ${at + i}")
              memory(at + i) = ((data >> (8 * i)) & 255).toInt; written += at + i
            }
          } else {
            assert((Set[BigInt](0x1000, 0x2000, 0x8000) ++ faultPCs.map(_ & ~BigInt(63)))(at), s"unexpected read $at")
            if (at == 0x8000) { assert(supported && firstPhase); scalarDataReads += 1 }
          }
          pending.enqueue(Reply(q.transactionId.peek().litValue, line(at), cycle + 5))
        }
        if (d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) {
          assert(firstPhase && !supported)
          val t = d.io.trap.bits
          val pc = t.pc.peek().litValue
          assert(faultPCs(pc) && !trapped.exists(_._2 == pc), s"logical warp fault repeated or unexpected: $pc; previous=$trapped")
          t.cause.expect(2.U); t.tval.expect(word.U)
          t.activeMask.expect(((BigInt(1) << gpu.lanes) - 1).U)
          t.laneFaultMask.expect(((BigInt(1) << gpu.lanes) - 1).U)
          val warp = t.warpId.peek().litValue; assert(warp < gpu.warps); trapped += ((warp, pc))
        }
        d.clock.step(); cycle += 1
      }
      def until(condition: => Boolean): Unit = {
        var n = 0; while (!condition && n < 4000) { tick(); n += 1 }
        assert(condition, s"private shader $kind failed to progress: cycle=$cycle trap=${d.io.trap.valid.peek().litToBoolean} completion=${d.io.completion.valid.peek().litToBoolean}")
      }
      def launch(pc: Int): Unit = {
        d.io.launch.kernelPc.poke(pc.U); d.io.launch.kernargAddress.poke(0x8000.U)
        d.io.launch.gridX.poke(1.U); d.io.launch.gridY.poke(1.U); d.io.launch.gridZ.poke(1.U)
        d.io.launch.localX.poke(capacity.U); d.io.launch.localY.poke(1.U); d.io.launch.localZ.poke(1.U)
        d.io.launch.valid.poke(true.B); until(d.io.launch.ready.peek().litToBoolean); tick(); d.io.launch.valid.poke(false.B)
      }
      def completion(success: Boolean): Unit = {
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(success.B)
        val held = d.io.completion.bits.peek().litValue
        for (_ <- 0 until 9) { assert(d.io.completion.bits.peek().litValue == held); d.io.completion.valid.expect(true.B); tick() }
        assert(pending.isEmpty)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      launch(0x1000)
      if (!supported) {
        until(d.io.trap.valid.peek().litToBoolean)
        val held = d.io.trap.bits.peek().litValue
        for (_ <- 0 until 9) { assert(d.io.trap.bits.peek().litValue == held); d.io.trap.valid.expect(true.B); d.io.completion.valid.expect(false.B); tick() }
        assert(written.isEmpty && scalarDataReads == 0)
        d.io.trap.ready.poke(true.B); completion(false)
        assert(trapped.map(_._2).toSet == faultPCs)
        firstPhase = false; launch(0x2000)
      }
      completion(true)
      assert(written.size == capacity * 4)
      for (i <- 0 until capacity) {
        val value = (0 until 4).foldLeft(BigInt(0))((v, j) => v | (BigInt(memory(BigInt(0x8400 + i * 4 + j))) << (8 * j)))
        assert(value == BigInt("40000000", 16), s"healthy lane $i value=$value")
      }
      for (at <- (0x83f0 until 0x8400) ++ (0x8400 + capacity * 4 until 0x8400 + capacity * 4 + 16)) assert(memory(BigInt(at)) == 0xa5)
      assert(trapped.size == (if (supported) 0 else gpu.warps))
      assert(if (supported) scalarDataReads > 0 else scalarDataReads == 0)
      info(s"PRIVATE_SHADER_AVAILABILITY kind=$kind backend=$backend F=$enabled lanes=${gpu.lanes} warps=${gpu.warps} threads=$capacity traps=${trapped.size} logicalFaultPCs=${trapped.map(_._2).distinct.size} physicalFaultSlots=${trapped.map(_._1).distinct.size} exactHealthyValues=$capacity heldTrap=${!supported} heldCompletion=true noUnavailableDataRead=true outputGuards=true noQuotaInterface=true")
    }
  }
  for ((kind, word, backend, enabled) <- cases) {
    it should s"retain private shader progress with $kind" in { run(kind, word, backend, enabled, GpuConfig(lanes = 4, warps = 2, enableFpu = enabled)) }
  }
  it should "reject unavailable scalar FP and reuse all default 128 shader threads" in {
    run("default-no-backend-scalar-load", BigInt("0000a507", 16), false, true, GpuConfig())
  }
}
