package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.dispatch.{KernelCommand, KernelCommandResult}
import opengpu.system.{GpuSystem, SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Compose existing RTL against real system CUs, shared L2 and Sv32 clients.
  * This is a validation harness, not a new host submission ABI. Context/TLB
  * changes are made only at drained boundaries by the test owner.
  */
private class PersistentTileSharedSystemHarness(cfg: GpuConfig, count: Int) extends Module {
  private val pool = SharedCuShaderConfig(count, parallelFragments = true)
  private val stagingSlots = SharedCuShaderConfig.kernelWordTransactions(Some(pool))
  private val words = 16
  private val cbBase = stagingSlots
  private val fbBase = cbBase + words
  private val texBase = fbBase + words
  private val walkBase = texBase + words
  private val hostSlots = 1 << log2Ceil(walkBase + 4)
  private val systemSlots = GpuSystem.totalMemoryTransactions(count, 4, hostSlots)
  val io = IO(new Bundle {
    val job = Flipped(Decoupled(new TileCommandJob))
    val completion = Decoupled(new TileRenderPassCompletion)
    val cancel = Input(Bool())
    val compute = Flipped(Decoupled(new KernelCommand(cfg, 8)))
    val computeCompletion = Decoupled(new KernelCommandResult(8))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(cfg, 64, systemSlots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, systemSlots)))
    val instructionSatp = Input(UInt(32.W))
    val dataSatp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(cfg)))
    val invalidateInstructionCache = Input(Bool())
    val owned = Output(UInt(count.W))
    val busyCUs = Output(UInt(count.W))
    val shaderFault = Output(Bool())
    val commandFault = Output(Bool())
    val idle = Output(Bool())
  })
  val core = Module(new RenderCore(GraphicsConfig(screenWidth = 16, screenHeight = 16,
    tileSize = 16, tileAttachments = true, fragmentBatchLimit = math.max(8, cfg.lanes)), cfg, fragCore = true,
    sharedShader = Some(pool), persistentAttachments = true, tileStoreCredits = 8))
  val system = Module(new GpuSystem(cfg, numComputeUnits = count,
    enableFpuBackend = true, graphicsHostTransactions = hostSlots,
    instructionCacheSets = 4, instructionCacheWays = 1,
    vectorCacheSets = 4, vectorCacheWays = 1, enableGraphicsKernels = true))
  def tieInputs(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tieInputs)
    case v: Vec[_] => v.foreach(tieInputs)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case _ => ()
  }
  tieInputs(core.io); tieInputs(system.io)
  core.io.tileJob.get <> io.job
  io.completion <> core.io.tileCompletion.get
  core.io.tileCancel.get := io.cancel
  system.io.graphicsKernels.get <> core.io.shared.get
  system.io.command <> io.compute
  io.computeCompletion <> system.io.commandCompletion
  system.io.graphicsDrained := core.io.done
  system.io.instructionSatp := io.instructionSatp
  system.io.vectorSatp := io.dataSatp
  system.io.instructionTlbFlush := io.flush
  system.io.vectorTlbFlush := io.flush
  system.io.invalidateInstructionCache := io.invalidateInstructionCache
  core.io.instructionSatp := io.instructionSatp
  core.io.vectorSatp := io.dataSatp
  core.io.instructionTlbFlush := io.flush
  core.io.vectorTlbFlush := io.flush
  system.io.graphicsShaderRequest <> core.io.kernelMemReq
  core.io.kernelMemResp <> system.io.graphicsShaderResponse
  core.io.kernelL1Invalidate <> system.io.graphicsShaderL1Invalidate
  system.io.graphicsShaderL1InvalidateDone <> core.io.kernelL1InvalidateDone
  system.io.graphicsShaderAtomicRequest <> core.io.kernelGlobalAtomicRequest
  core.io.kernelGlobalAtomicResponse <> system.io.graphicsShaderAtomicResponse
  io.memoryRequest <> system.io.memoryRequest
  system.io.memoryResponse <> io.memoryResponse
  io.owned := system.io.graphicsKernels.get.owned
  io.busyCUs := system.io.busyComputeUnits
  io.shaderFault := core.io.shaderFault
  io.idle := core.io.done && !system.io.commandProcessorBusy && !system.io.busyComputeUnits.orR

  val staging = Module(new TranslatedLineClient(cfg, stagingSlots, preserveCachePolicy = true))
  val cb = Module(new TranslatedWordClient(cfg, words, words, uncached = true, preserveCachePolicy = true))
  val fb = Module(new TranslatedWordClient(cfg, words, words, uncached = true, preserveCachePolicy = true))
  val tex = Module(new TranslatedWordClient(cfg, words, words))
  staging.io.in <> core.io.kernelWordMemReq
  core.io.kernelWordMemResp <> staging.io.out
  cb.io.in <> core.io.cbMem.req; core.io.cbMem.resp <> cb.io.out
  fb.io.in <> core.io.fbMem.req; core.io.fbMem.resp <> fb.io.out
  tex.io.in <> core.io.texMem.req; core.io.texMem.resp <> tex.io.out
  staging.io.satp := io.dataSatp; staging.io.flush := io.flush
  for (client <- Seq(cb, fb, tex)) {
    client.io.satp := io.dataSatp; client.io.flush := io.flush
  }
  core.io.commandMemoryFault.get := cb.io.fault
  core.io.attachmentMemoryFault.get := fb.io.fault
  core.io.textureMemoryFault.get := tex.io.fault
  io.commandFault := cb.io.fault
  val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(cfg, 64, hostSlots), 8))
  val requestBuffer = Module(new Queue(new ComputeMemoryRequest(cfg, 64, hostSlots), 1))
  requestBuffer.io.enq <> arbiter.io.out
  system.io.graphicsHostRequest <> requestBuffer.io.deq
  val requests = Seq(staging.io.memReq, cb.io.memReq, fb.io.memReq, tex.io.memReq,
    staging.io.pageWalk, cb.io.pageWalk, fb.io.pageWalk, tex.io.pageWalk)
  val responses = Seq(staging.io.memResp, cb.io.memResp, fb.io.memResp, tex.io.memResp,
    staging.io.pageWalkResp, cb.io.pageWalkResp, fb.io.pageWalkResp, tex.io.pageWalkResp)
  val bases = Seq(0, cbBase, fbBase, texBase, walkBase, walkBase + 1, walkBase + 2, walkBase + 3)
  val sizes = Seq(stagingSlots, words, words, words, 1, 1, 1, 1)
  val lower = system.io.graphicsHostResponse
  val selected = (0 until 8).map(i => lower.bits.transactionId >= bases(i).U &&
    lower.bits.transactionId < (bases(i) + sizes(i)).U)
  for (i <- 0 until 8) {
    val port = arbiter.io.in(i)
    port.valid := requests(i).valid; port.bits := requests(i).bits
    port.bits.transactionId := bases(i).U + requests(i).bits.transactionId
    requests(i).ready := port.ready
    responses(i).valid := lower.valid && selected(i)
    responses(i).bits.readData := lower.bits.readData
    responses(i).bits.fault := lower.bits.fault
    responses(i).bits.transactionId := lower.bits.transactionId - bases(i).U
    if (i >= 4) when(requests(i).fire) {
      assert(requests(i).bits.transactionId === 0.U, "walk client exceeded its single reserved ID")
    }
  }
  lower.ready := Mux1H(selected, responses.map(_.ready))
  when(lower.valid) { assert(PopCount(VecInit(selected)) === 1.U, "graphics response has no unique client") }
}

class PersistentTileSharedSystemSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "persistent jobs on the system CU pool"

  for ((count, translated, lanes, warps) <- Seq((2, false, 4, 2), (2, true, 4, 2),
      (4, true, 4, 2), (4, true, 16, 8))) {
    it should s"execute shaders with $count shared CUs ($lanes lanes/$warps warps, Sv32=$translated), mixed compute and fault recovery" in {
      if (lanes == 16) assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "The four-CU 16-lane/eight-warp elaboration needs a larger JVM heap; run sbt -J-Xmx8G 'testOnly opengpu.graphics.PersistentTileSharedSystemSpec'.")
      val cfg = GpuConfig(lanes = lanes, warps = warps, l2Sets = 8, l2Ways = 2)
      simulate(new PersistentTileSharedSystemHarness(cfg, count)) { dut =>
        def zeroInputs(d: Data): Unit = d match {
          case r: Record => r.elements.values.foreach(zeroInputs)
          case v: Vec[_] => v.foreach(zeroInputs)
          case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
          case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
          case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
          case _ => ()
        }
        zeroInputs(dut.io)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val memory = mutable.LongMap.empty[Long]
        var physicalOffset = 0L
        def word(a: Long): Long = memory.getOrElse(a, 0)
        def put(a: Long, v: Long): Unit = memory(a) = v & 0xffffffffL
        def putVirtual(a: Long, v: Long): Unit = put(a + physicalOffset, v)
        def line(a: Long): BigInt = (0 until 16).map(i => BigInt(word(a + 4 * i)) << (32 * i)).foldLeft(BigInt(0))(_ | _)
        def write(a: Long, value: BigInt, mask: BigInt): Unit = {
          for (w <- 0 until 16) {
            var v = word(a + 4 * w)
            for (b <- 0 until 4 if mask.testBit(4 * w + b))
              v = (v & ~(255L << (8 * b))) | (((value >> (32 * w + 8 * b)).toLong & 255L) << (8 * b))
            put(a + 4 * w, v)
          }
        }
        def addi(rd: Int, rs: Int, imm: Int): Long = ((imm & 4095).toLong << 20) | (rs << 15) | (rd << 7) | 0x13
        def add(rd: Int, a: Int, b: Int): Long = (b << 20) | (a << 15) | (rd << 7) | 0x33
        def slli(rd: Int, rs: Int, n: Int): Long = (n << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13
        def vle(base: Int, dst: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (dst << 7) | 7
        def vse(base: Int, src: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (src << 7) | 0x27
        val capacity = lanes * warps
        // Offsets are the ABI's SoA array positions, including at 128 threads.
        // Construct large offsets without truncating an addi immediate.
        def address(offset: Int): Seq[Long] =
          Seq(addi(7, 0, offset / 16), slli(7, 7, 4), add(6, 5, 7))
        val vset = (3L << 30) | (0x10L << 20) | (lanes << 15) | (7 << 12) | 0x57
        val program = Seq(slli(5, 8, 2), add(5, 1, 5), vset) ++
          address(12 * capacity) ++ Seq(vle(6, 2)) ++
          address(24 * capacity) ++ Seq(vse(6, 2)) ++
          address(8 * capacity) ++ Seq(vle(6, 3)) ++
          address(28 * capacity) ++ Seq(vse(6, 3), 0x30500073L)
        case class Pending(id: BigInt, addr: Long, data: BigInt, mask: BigInt, wr: Boolean, due: Int)
        val pending = mutable.ArrayBuffer.empty[Pending]
        var reply: Option[Pending] = None
        var replyData = BigInt(0)
        val rng = new scala.util.Random(20261018)
        var cycle = 0
        var peakOwned = 0
        var sawTrap = false
        var computeDone = false
        var sawMixed = false
        var commandFaults = 0
        var walks = 0
        def tick(): Unit = {
          dut.io.memoryRequest.ready.poke((cycle % 5 != 0).B)
          if (reply.isEmpty) pending.indices.filter(i => pending(i).due <= cycle).lastOption.foreach { i =>
            reply = Some(pending.remove(i)); replyData = line(reply.get.addr)
          }
          dut.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r =>
            dut.io.memoryResponse.bits.transactionId.poke(r.id.U)
            dut.io.memoryResponse.bits.readData.poke(replyData.U)
            dut.io.memoryResponse.bits.fault.poke(false.B)
          }
          val responseFire = reply.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
          if (dut.io.memoryRequest.valid.peek().litToBoolean && dut.io.memoryRequest.ready.peek().litToBoolean) {
            val r = dut.io.memoryRequest.bits; val id = r.transactionId.peek().litValue
            val address = r.address.peek().litValue.toLong
            val isWalk = address >= 0x300000 && address < 0x304000
            assert(isWalk || (address >= physicalOffset && address < physicalOffset + 0x80000),
              f"request escaped the active physical context: 0x$address%x")
            assert(!pending.exists(_.id == id) && !reply.exists(r => r.id == id && !responseFire),
              "reused a live system transaction ID")
            pending += Pending(id, address, r.writeData.peek().litValue,
              r.byteMask.peek().litValue, r.isWrite.peek().litToBoolean, cycle + 4 + rng.nextInt(17))
            if (isWalk) walks += 1
          }
          if (dut.io.computeCompletion.valid.peek().litToBoolean) {
            dut.io.computeCompletion.bits.success.expect(true.B)
            dut.io.computeCompletion.bits.commandId.expect(1.U)
            computeDone = true
          }
          peakOwned = math.max(peakOwned, dut.io.owned.peek().litValue.bitCount)
          val owned = dut.io.owned.peek().litValue
          sawMixed ||= owned != 0 && (dut.io.busyCUs.peek().litValue & (((BigInt(1) << count) - 1) ^ owned)) != 0
          if (dut.io.commandFault.peek().litToBoolean) commandFaults += 1
          sawTrap ||= dut.io.shaderFault.peek().litToBoolean
          if (responseFire) {
            val r = reply.get; if (r.wr) write(r.addr, r.data, r.mask)
            reply = None
          }
          dut.clock.step(); cycle += 1
          if (cycle % 10000 == 0) println(s"PERSISTENT_SYSTEM cycle=$cycle owned=${dut.io.owned.peek().litValue} pending=${pending.size}")
        }
        def waitFor(p: => Boolean): Unit = {
          val start = cycle
          while (!p && cycle - start < 200000) tick()
          assert(p, s"system timeout at $cycle, pending=${pending.size}, owned=${dut.io.owned.peek().litValue}")
        }
        val scenarios = if (translated) Seq("healthy", "shader", "command", "overwrite")
          else Seq("healthy", "shader", "overwrite")
        for ((scenario, run) <- scenarios.zipWithIndex) {
          val fault = scenario == "shader" || scenario == "command"
          physicalOffset = if (translated) (run + 1L) * 0x400000L else 0L
          if (translated) {
            // Change the root and backing physical superpage at drained boundaries.
            // Identical virtual bindings must never retain the previous context's data.
            val root = 0x300000L + run * 4096L
            put(root, if (scenario == "command") 0 else ((physicalOffset >> 12) << 10) | 0xcfL)
            val satp = (BigInt(1) << 31) | (BigInt(run + 1) << 22) | (root >> 12)
            dut.io.instructionSatp.poke(satp.U); dut.io.dataSatp.poke(satp.U)
            dut.io.flush.valid.poke(true.B); dut.io.invalidateInstructionCache.poke(true.B)
            tick()
            dut.io.flush.valid.poke(false.B); dut.io.invalidateInstructionCache.poke(false.B)
          }
          program.zipWithIndex.foreach { case (v, i) => putVirtual(0x1000 + 4 * i, v) }
          putVirtual(0x2000, 0xffffffffL)
          Seq(addi(5, 0, 0x123), 0x0050a023L, 0x30500073L).zipWithIndex.foreach {
            case (v, i) => putVirtual(0x3000 + 4 * i, v)
          }
          val p = Pass(width = 8, height = 8, originX = 0, originY = 0, tileLog2 = 3,
            context = 70 + run, id = run, generation = 20 + run)
          val base = 0x4000L
          val records = if (fault) 6 else 2
          for (record <- 0 until records) {
            val words = Array.fill[Long](40)(0)
            for ((xy, i) <- Seq((-1, -1), (1, -1), (-1, 1)).zipWithIndex) {
              words(4 * i) = xy._1 * 65536L; words(4 * i + 1) = xy._2 * 65536L; words(4 * i + 3) = 65536
              words(12 + 3 * i) = if (record == 0) 200 + run else 20
              words(13 + 3 * i) = 40; words(14 + 3 * i) = 60; words(21 + i) = 16
            }
            words(24) = if (scenario == "shader") 0x2000 else 0x1000
            // Reserve all 2*CU staging banks away from attachments and compute.
            words(25) = 0x60000; words(34) = 0x2000
            words.zipWithIndex.foreach { case (v, i) => putVirtual(base + record * 160 + 4 * i, v) }
          }
          for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8) putVirtual(p.address(x,y,0,depth),0xdeadbeefL)
          dut.io.job.bits.poke(0.U.asTypeOf(new TileCommandJob))
          dut.io.job.bits.version.poke(1.U); pokeDescriptor(dut.io.job.bits.passDescriptor, p)
          val c = dut.io.job.bits.config
          c.cmdBase.poke(base.U); c.cmdCount.poke(records.U)
          c.screenW.poke(16.U); c.screenH.poke(16.U)
          c.depthTestEnable.poke(true.B); c.depthWriteEnable.poke(true.B)
          // Make the second draw observable after reusing staging banks. This
          // catches stale CU input lines that equal-depth rejection would hide.
          if (scenario == "overwrite") c.depthFunc.poke(6.U)
          dut.io.job.bits.commandLimit.poke((base + records * 160).U)
          waitFor(dut.io.job.ready.peek().litToBoolean)
          dut.io.job.valid.poke(true.B)
          tick(); dut.io.job.valid.poke(false.B)
          dut.io.job.bits.poke(0.U.asTypeOf(new TileCommandJob))
          if (run == 0) {
            waitFor(dut.io.owned.peek().litValue != 0)
            dut.io.compute.bits.poke(0.U.asTypeOf(new KernelCommand(cfg, 8)))
            // Deliberately collide with graphics client 1's local command ID.
            dut.io.compute.bits.commandId.poke(1.U)
            dut.io.compute.bits.launch.kernelPc.poke(0x3000.U)
            dut.io.compute.bits.launch.kernargAddress.poke(0xe000.U)
            for (i <- 0 until 3) {
              dut.io.compute.bits.launch.localSize(i).poke(1.U); dut.io.compute.bits.launch.gridSize(i).poke(1.U)
            }
            dut.io.computeCompletion.ready.poke(true.B)
            dut.io.compute.valid.poke(true.B)
            waitFor(dut.io.compute.ready.peek().litToBoolean); tick(); dut.io.compute.valid.poke(false.B)
          }
          waitFor(dut.io.completion.valid.peek().litToBoolean)
          val status = scenario match {
            case "shader" => TilePassStatus.executionFault
            case "command" => TilePassStatus.commandFault
            case _ => TilePassStatus.success
          }
          dut.io.completion.bits.status.expect(status.U)
          dut.io.completion.bits.identity.context.expect(p.context.U)
          dut.io.completion.bits.identity.pass.expect(p.id.U)
          dut.io.completion.bits.identity.generation.expect(p.generation.U)
          for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8) {
            val covered = x > 0 && y > 0
            val expected = if (fault) 0xdeadbeefL else if (depth) {
              if (covered) (p.depthClear & 0xff000000L) | 16L else p.depthClear
            } else if (covered) {
              val red = if (scenario == "overwrite") 20L else 200L + run
              (red << 24) | 0x00283cffL
            } else p.colorClear
            val actual = word(p.address(x,y,0,depth) + physicalOffset)
            assert(actual == expected, s"run=$run depth=$depth ($x,$y): ${actual.toHexString}")
          }
          val result = dut.io.completion.bits.peek().litValue
          for (_ <- 0 until 5) { tick(); assert(dut.io.completion.bits.peek().litValue == result); dut.io.job.ready.expect(false.B) }
          dut.io.completion.ready.poke(true.B); tick(); dut.io.completion.ready.poke(false.B)
          waitFor(dut.io.idle.peek().litToBoolean && pending.isEmpty && reply.isEmpty)
        }
        assert(peakOwned == count, s"only $peakOwned of $count CUs owned by graphics")
        assert(sawTrap, "illegal shader did not report its failure")
        assert(computeDone, "compute did not complete")
        assert(sawMixed, "graphics and compute never occupied separate CUs simultaneously")
        assert(word(0xe000 + (if (translated) 0x400000L else 0L)) == 0x123)
        assert(if (translated) commandFaults == 1 && walks > 0 else commandFaults == 0 && walks == 0)
        info(s"shared system cycles=$cycle peak graphics CUs=$peakOwned; mixed CU occupancy, trap, walks=$walks, command faults=$commandFaults")
      }
    }
  }
}
