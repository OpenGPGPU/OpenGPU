package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Two independent native stage endpoints on opposite pairs of one four-CU pool. */
private[graphics] class NativeTextureDualClusterHarness extends Module {
  private val config = GpuConfig()
  val io = IO(new Bundle {
    val native = Vec(2, new NativeShaderTextureIO(config))
    val dataRequest = Vec(2, Decoupled(new ComputeMemoryRequest(config, 64, 16)))
    val dataResponse = Vec(2, Flipped(Decoupled(new ComputeMemoryResponse(64, 16))))
    val pageWalk = Vec(2, Decoupled(new ComputeMemoryRequest(config, 64, 4)))
    val pageWalkResponse = Vec(2, Flipped(Decoupled(new ComputeMemoryResponse(64, 4))))
    val groupFire = Output(Vec(2, Bool()))
    val groupTag = Output(Vec(2, UInt(32.W)))
    val replyFire = Output(Vec(2, Bool()))
    val replyTag = Output(Vec(2, UInt(32.W)))
    val replyTaps = Output(Vec(2, UInt(3.W)))
    val replyReads = Output(Vec(2, UInt(3.W)))
    val busy = Output(Bool())
  })
  private val pool = Module(new NativeRgba8TexturePool(config, numComputeUnits = 4,
    clients = 3, sharedOutstandingRows = 128))
  for (c <- 0 until 2) {
    val endpoint = Module(new NativeShaderTextureUnit(config, stage = c, groupedTexture = true))
    // The public native interface omits only the internal grouped service port.
    for ((name, field) <- io.native(c).elements) { field <> endpoint.io.elements(name) }
    pool.io.client(c) <> endpoint.io.grouped.get
    pool.io.cuMask(c) := (1 << (2 * c)).U
    val p = endpoint.io.grouped.get
    io.groupFire(c) := p.request.fire; io.groupTag(c) := p.request.bits.tag
    io.replyFire(c) := p.response.fire; io.replyTag(c) := p.response.bits.tag
    io.replyTaps(c) := p.response.bits.uniqueTaps; io.replyReads(c) := p.response.bits.dataReadLines
    io.dataRequest(c) <> pool.io.memoryRequest(c)
    pool.io.memoryResponse(c) <> io.dataResponse(c)
    io.pageWalk(c) <> pool.io.pageWalk(c)
    pool.io.pageWalkResponse(c) <> io.pageWalkResponse(c)
  }
  private val idle = pool.io.client(2)
  idle.request.valid := false.B; idle.request.bits := 0.U.asTypeOf(idle.request.bits)
  idle.response.ready := true.B; idle.cancelGroup.valid := false.B
  idle.cancelGroup.bits := 0.U.asTypeOf(idle.cancelGroup.bits); idle.invalidate := false.B
  pool.io.cuMask(2) := 0.U
  pool.io.satp := 0.U; pool.io.flush.valid := false.B
  pool.io.flush.bits := 0.U.asTypeOf(pool.io.flush.bits)
  pool.io.invalidate := false.B; pool.io.cancel := false.B; io.busy := pool.io.busy
}

class NativeTextureDualClusterRateSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def fp(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  private def instruction(parameter: Boolean, field: Int): BigInt =
    (BigInt(if (parameter) 3 else 4) << 26) | (BigInt(1) << 25) |
      (BigInt(6) << 20) | (BigInt(5) << 15) | (BigInt(field) << 12) |
      (if (parameter) BigInt(0) else BigInt(10) << 7) | 0x2b

  it should "sustain two four-tap native samples per cycle across both physical clusters" in {
    simulate(new NativeTextureDualClusterHarness) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val sharedBytes = mutable.Map.empty[BigInt, Int]
      val metadata = (0 until 2).map { c =>
        val p = d.io.native(c)
        new Memory(p.memoryRequest.bits, p.memoryResponse.bits, p.memoryRequest.valid,
          p.memoryRequest.ready, p.memoryResponse.valid, p.memoryResponse.ready) {
          override val bytes = sharedBytes
        }
      }
      val data = (0 until 2).map { c =>
        new Memory(d.io.dataRequest(c).bits, d.io.dataResponse(c).bits, d.io.dataRequest(c).valid,
          d.io.dataRequest(c).ready, d.io.dataResponse(c).valid, d.io.dataResponse(c).ready) {
          override val bytes = sharedBytes
        }
      }
      val walks = (0 until 2).map { c =>
        new Memory(d.io.pageWalk(c).bits, d.io.pageWalkResponse(c).bits, d.io.pageWalk(c).valid,
          d.io.pageWalk(c).ready, d.io.pageWalkResponse(c).valid, d.io.pageWalkResponse(c).ready) {
          override val bytes = sharedBytes
        }
      }
      val memories = metadata ++ data ++ walks
      val raw = Seq(Seq[BigInt](0x11233444L, 0x55667788L, 0x9aaabbcdL, 0xddeeff01L),
        Seq[BigInt](0xfdeccab1L, 0x98764321L, 0x7654432aL, 0x10203040L))
      val expected = raw.map(xs => TextureFilterReference.filter(
        xs.map(x => ImageFormatReference.decode("RGBA8", x)), Seq(fp(0.5f), fp(0.5f)), 2))
      val admitted = Seq.fill(2)(mutable.ArrayBuffer.empty[(Int, BigInt)])
      val replied = Seq.fill(2)(mutable.ArrayBuffer.empty[(Int, BigInt)])
      val retired = Seq.fill(2)(mutable.ArrayBuffer.empty[Int])
      var cycle = 0; var measuring = false
      def tick(): Unit = {
        memories.foreach(_.drive())
        if (measuring) for (c <- 0 until 2) {
          if (d.io.groupFire(c).peek().litToBoolean) admitted(c) += ((cycle, d.io.groupTag(c).peek().litValue))
          if (d.io.replyFire(c).peek().litToBoolean) {
            d.io.replyTaps(c).expect(4.U); d.io.replyReads(c).expect(0.U)
            replied(c) += ((cycle, d.io.replyTag(c).peek().litValue))
          }
          val p = d.io.native(c)
          if (p.vectorCommit.valid.peek().litToBoolean && p.vectorCommit.ready.peek().litToBoolean) {
            val q = p.vectorCommit.bits; val warp = q.writeback.warpId.peek().litValue.toInt
            assert(warp >= 0 && warp < 8)
            q.writesVd.expect(true.B); q.writeback.vd.expect(10.U)
            q.writeback.data.foreach(_.expect(expected(c)(warp % 4).U))
            p.responseFault.expect(false.B); retired(c) += warp
          }
        }
        memories.foreach(_.capture()); d.clock.step(); cycle += 1
      }
      def until(condition: => Boolean): Unit = {
        var n = 0
        while (!condition && n < 60000) { tick(); n += 1 }
        assert(condition, s"dual native endpoint stalled at cycle $cycle")
      }
      def issueBoth(warp: Int, parameter: Boolean, field: Int,
          left: BigInt = 0, right: BigInt = 1): Unit = {
        for (c <- 0 until 2) {
          val p = d.io.native(c); val q = p.vectorIn.bits
          p.resources.version.poke(1.U); p.resources.address.poke((root + c * 0x40000).U)
          identity(p.identity); zero(q)
          q.issued.decode.instruction.poke(instruction(parameter, field).U)
          q.issued.decode.warpId.poke(warp.U); q.issued.decode.pc.poke(0x200.U)
          q.issued.decode.activeMask.poke(65535.U); q.executionMask.poke(65535.U)
          q.issued.vs1Data.foreach(_.poke(left.U)); q.issued.vs2Data.foreach(_.poke(right.U))
          p.vectorIn.valid.poke(true.B)
        }
        val pending = mutable.Set(0, 1)
        var n = 0
        while (pending.nonEmpty && n < 60000) {
          val fires = pending.filter(c => d.io.native(c).vectorIn.ready.peek().litToBoolean).toVector
          tick()
          for (c <- fires) { d.io.native(c).vectorIn.valid.poke(false.B); pending -= c }
          n += 1
        }
        assert(pending.isEmpty, "paired vector issue timed out")
      }
      def retireSetup(parameter: Boolean): Unit = {
        until((0 until 2).forall(c => d.io.native(c).vectorCommit.valid.peek().litToBoolean))
        for (c <- 0 until 2) {
          val p = d.io.native(c); p.responseFault.expect(false.B)
          p.vectorCommit.bits.writesVd.expect((!parameter).B)
          p.vectorCommit.bits.writesScalar.expect(parameter.B)
          p.vectorCommit.bits.writeback.data.foreach(_.expect((if (parameter) BigInt(0) else expected(c).head).U))
          p.vectorCommit.ready.poke(true.B)
        }
        tick(); d.io.native.foreach(_.vectorCommit.ready.poke(false.B))
      }
      for (c <- 0 until 2) {
        val m = metadata(c); val offset = BigInt(c * 0x40000)
        roots(m.putWord, stage = c, offset = offset)
        install(m.putWord, (a, b) => m.bytes(a) = b, 0, stage = c, offset = offset,
          swizzle = Seq(0, 1, 2, 3))
        m.putWord(levels + offset + 12 * 4, 0x20000 + offset)
        m.putWord(levels + offset + 17 * 4, 8)
        m.putWord(samplers + offset + 7 * 4, 1); m.putWord(samplers + offset + 8 * 4, 1)
        for (i <- 0 until 4) m.putWord(0x20000 + offset + i * 4, raw(c)(i))
        d.io.native(c).clearParameters.poke(true.B)
      }
      tick(); d.io.native.foreach(_.clearParameters.poke(false.B))
      for (warp <- 0 until 8; pair <- 0 until 8) {
        issueBoth(warp, true, pair,
          if (pair == 0) fp(0.5f) else BigInt(0), if (pair == 0) fp(0.5f) else BigInt(0))
        retireSetup(parameter = true)
      }
      issueBoth(0, false, 0); retireSetup(parameter = false)
      // The coordinate pipeline now has ten stages (two more than the
      // original paired-stream checkpoint); interval-one throughput remains.
      val replyLatency = 92
      val initialReads = memories.map(_.seen.size)
      measuring = true; val start = cycle
      d.io.native.foreach(_.vectorCommit.ready.poke(true.B))
      for (_ <- 0 until 4; warp <- 0 until 8) issueBoth(warp, false, warp % 4)
      until(retired.forall(_.size == 32) && d.io.native.forall(p => !p.busy.peek().litToBoolean))
      measuring = false
      assert(memories.map(_.seen.size) == initialReads, "warm benchmark performed external reads")
      for (c <- 0 until 2) {
        assert(admitted(c).size == 512 && replied(c).size == 512)
        assert(admitted(c).map(_._2) == replied(c).map(_._2), s"client$c private tags crossed or reordered")
        assert((0 until 8).forall(w => retired(c).count(_ == w) == 4))
        val middle = admitted(c).slice(32, 480)
        assert(middle.last._1 - middle.head._1 + 1 == 448)
        val latency = admitted(c).zip(replied(c)).map { case (a, b) => b._1 - a._1 }
        assert(latency.min == replyLatency && latency.max == replyLatency)
      }
      val overlapStart = admitted.map(_(32)._1).max
      val overlapEnd = admitted.map(_(479)._1).min
      val acceptanceByCycle = admitted.flatten.groupBy(_._1).view.mapValues(_.size).toMap
      val replyByCycle = replied.flatten.groupBy(_._1).view.mapValues(_.size).toMap
      val cycles = overlapEnd - overlapStart + 1
      assert(cycles == 448)
      assert((overlapStart to overlapEnd).forall(t => acceptanceByCycle.getOrElse(t, 0) == 2))
      assert((overlapStart + replyLatency to overlapEnd + replyLatency).forall(t => replyByCycle.getOrElse(t, 0) == 2))
      d.io.busy.expect(false.B)
      info(s"NATIVE_DUAL_CLUSTER_RATE CUs=4 clients=3 activeClients=2 clusters=2 lanes=16 warps=8 instructions=64 samples=1024 taps=4 steadyCycles=$cycles steadySamples=${cycles * 2} aggregateSamplesPerCycle=2 replyLatency=$replyLatency exactLaneResults=16384 totalCycles=${cycle - start} warmDescriptorReads=0 warmDataReads=0 publicOrder=true")
    }
  }
}
