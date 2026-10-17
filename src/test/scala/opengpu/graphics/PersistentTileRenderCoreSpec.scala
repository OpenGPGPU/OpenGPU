package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PersistentTileRenderCoreSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "command-driven persistent tile jobs"

  it should "validate command bounds, freeze jobs, drain faults and retain tagged results through reuse" in {
    val cfg = GraphicsConfig(screenWidth = 16, screenHeight = 16,
      tileSize = 16, tileAttachments = true)
    simulate(new RenderCore(cfg, persistentAttachments = true, tileStoreCredits = 8)) { dut =>
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
      val job = dut.io.tileJob.get
      val completion = dut.io.tileCompletion.get
      val memory = mutable.LongMap.empty[Long]
      val rng = new scala.util.Random(314159)
      case class Reply(address: Long, data: Long, write: Boolean, fault: Boolean, due: Int)
      val commands = mutable.ArrayBuffer.empty[Reply]
      val attachments = mutable.ArrayBuffer.empty[Reply]
      val textures = mutable.ArrayBuffer.empty[Reply]
      var commandReply: Option[Reply] = None
      var attachmentReply: Option[Reply] = None
      var textureReply: Option[Reply] = None
      var cycle = 0
      var base = 0L
      var phase = ""
      var run = 0
      var reads = 0
      var writes = 0
      var holdingCommands = false
      var faultInjected = false
      var commandStall: Option[BigInt] = None
      var attachmentStall: Option[BigInt] = None

      def commandWord(address: Long): Long = {
        val index = ((address - base) / 4).toInt
        val record = index / 40
        val word = index % 40
        val words = Array.fill[Long](40)(0)
        for ((xy, i) <- Seq((-1, -1), (1, -1), (-1, 1)).zipWithIndex) {
          words(4 * i) = (xy._1 * 65536).toLong & 0xffffffffL
          words(4 * i + 1) = (xy._2 * 65536).toLong & 0xffffffffL
          words(4 * i + 3) = 65536
          words(12 + 3 * i) = if (record == 0) 200 + run else 20
          words(13 + 3 * i) = 40
          words(14 + 3 * i) = 60
          words(21 + i) = 16
        }
        if (phase == "override" && record > 0) words(32) = 1 | 2 | (6 << 4) | (1 << 7)
        words(word)
      }
      def tick(): Unit = {
        dut.io.cbMem.req.ready.poke((cycle % 4 != 0).B)
        dut.io.fbMem.req.ready.poke((cycle % 5 != 0).B)
        dut.io.texMem.req.ready.poke((cycle % 3 != 0).B)
        if (commandReply.isEmpty && !holdingCommands) {
          commands.indices.find(i => commands(i).due <= cycle).foreach { i =>
            commandReply = Some(commands.remove(i))
          }
        }
        if (attachmentReply.isEmpty) {
          attachments.indices.filter(i => attachments(i).due <= cycle).lastOption.foreach { i =>
            attachmentReply = Some(attachments.remove(i))
          }
        }
        if (textureReply.isEmpty) {
          textures.indices.find(i => textures(i).due <= cycle).foreach { i =>
            textureReply = Some(textures.remove(i))
          }
        }
        def present(port: OmMemoryResponse, response: Option[Reply]): Unit = response.foreach { r =>
          port.addr.poke(r.address.U); port.data.poke(r.data.U); port.write.poke(r.write.B)
        }
        dut.io.cbMem.resp.valid.poke(commandReply.nonEmpty.B)
        present(dut.io.cbMem.resp.bits, commandReply)
        dut.io.commandMemoryFault.get.poke(commandReply.exists(_.fault).B)
        dut.io.fbMem.resp.valid.poke(attachmentReply.nonEmpty.B)
        present(dut.io.fbMem.resp.bits, attachmentReply)
        dut.io.attachmentMemoryFault.get.poke(attachmentReply.exists(_.fault).B)
        dut.io.texMem.resp.valid.poke(textureReply.nonEmpty.B)
        present(dut.io.texMem.resp.bits, textureReply)
        // A sideband without an accepted texture response cannot fault a job.
        dut.io.textureMemoryFault.get.poke((phase == "success" || textureReply.exists(_.fault)).B)
        val commandFire = commandReply.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean
        val attachmentFire = attachmentReply.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean
        val textureFire = textureReply.nonEmpty && dut.io.texMem.resp.ready.peek().litToBoolean
        if (dut.io.texMem.req.valid.peek().litToBoolean && dut.io.texMem.req.ready.peek().litToBoolean) {
          assert(phase == "textureFault")
          dut.io.texMem.req.bits.write.expect(false.B)
          val fault = !faultInjected
          faultInjected ||= fault
          textures += Reply(dut.io.texMem.req.bits.addr.peek().litValue.toLong,
            0xffffffffL, false, fault, cycle + 13)
        }
        val cbValid = dut.io.cbMem.req.valid.peek().litToBoolean
        val cbFire = cbValid && dut.io.cbMem.req.ready.peek().litToBoolean
        if (cbValid) {
          val bits = dut.io.cbMem.req.bits.peek().litValue
          commandStall.foreach(v => assert(bits == v, "offered command request changed"))
          commandStall = if (cbFire) None else Some(bits)
        } else assert(commandStall.isEmpty, "offered command request disappeared")
        if (cbFire) {
          val address = dut.io.cbMem.req.bits.addr.peek().litValue.toLong
          assert(address == base + reads * 4L, "command binding changed or wrapped")
          assert(commands.isEmpty && commandReply.isEmpty)
          dut.io.cbMem.req.bits.write.expect(false.B)
          val fault = phase == "commandFault" && reads == 84
          faultInjected ||= fault
          commands += Reply(address, commandWord(address), false, fault, cycle + 3)
          reads += 1
        }
        val fbValid = dut.io.fbMem.req.valid.peek().litToBoolean
        val fbFire = fbValid && dut.io.fbMem.req.ready.peek().litToBoolean
        if (fbValid) {
          val bits = dut.io.fbMem.req.bits.peek().litValue
          attachmentStall.foreach(v => assert(bits == v, "offered attachment request changed"))
          attachmentStall = if (fbFire) None else Some(bits)
        } else assert(attachmentStall.isEmpty, "offered attachment request disappeared")
        if (fbFire) {
          dut.io.fbMem.req.bits.write.expect(true.B)
          assert(!Set("cancel", "cancelEarly", "commandFault", "textureFault", "badVersion", "badBounds", "badPass", "unaligned", "overflow", "badLimit")(phase),
            "a failed or invalid job exported attachments")
          val fault = phase == "attachmentFault" && !faultInjected
          faultInjected ||= fault
          attachments += Reply(dut.io.fbMem.req.bits.addr.peek().litValue.toLong,
            dut.io.fbMem.req.bits.data.peek().litValue.toLong, true, fault, cycle + 7 + rng.nextInt(20))
          writes += 1
        }
        if (commandFire) commandReply = None
        if (textureFire) textureReply = None
        if (attachmentFire) {
          val r = attachmentReply.get
          if (!r.fault) memory(r.address) = r.data
          attachmentReply = None
        }
        dut.clock.step(); cycle += 1
      }
      def waitFor(p: => Boolean): Unit = {
        val start = cycle
        while (!p && cycle - start < 60000) tick()
        assert(p, s"timeout $phase at $cycle; command reads=$reads, attachment writes=$writes")
      }

      val cases = Seq("success", "override", "jobBlend", "cancelEarly", "cancel", "commandFault", "textureFault", "attachmentFault", "badVersion",
        "badBounds", "badPass", "unaligned", "overflow", "badLimit", "boundary", "empty", "success")
      for ((name, index) <- cases.zipWithIndex) {
        val startCycle = cycle
        phase = name; run = index; reads = 0; writes = 0; holdingCommands = false; faultInjected = false
        base = if (phase == "boundary") (1L << 32) - 160
          else if (phase == "overflow") (1L << 32) - 4 else 0x4000L
        val count = if (phase == "boundary") 1 else if (phase == "empty") 0
          else if (Set("cancel", "commandFault")(phase)) 6 else 2
        val p = Pass(width = 8, height = 8, originX = 0, originY = 0, tileLog2 = 3,
          id = index, generation = index + 17, context = index + 100)
        for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8)
          memory(p.address(x, y, 0, depth)) = 0xdeadbeefL
        job.bits.poke(0.U.asTypeOf(new TileCommandJob))
        job.bits.version.poke((if (phase == "badVersion") 2 else 1).U)
        pokeDescriptor(job.bits.passDescriptor, p)
        if (phase == "badPass") job.bits.passDescriptor.version.poke(2.U)
        job.bits.config.cmdBase.poke((base + (if (phase == "unaligned") 1 else 0)).U)
        job.bits.config.cmdCount.poke(count.U)
        val limit = if (phase == "boundary" || phase == "overflow") 1L << 32
          else if (phase == "badLimit") (1L << 32) + 1
          else base + count * 160L - (if (phase == "badBounds") 1 else 0)
        job.bits.commandLimit.poke(limit.U)
        job.bits.config.screenW.poke(16.U); job.bits.config.screenH.poke(16.U)
        job.bits.config.depthTestEnable.poke(true.B); job.bits.config.depthWriteEnable.poke(true.B)
        if (phase == "textureFault") {
          job.bits.config.texEnable.poke(true.B); job.bits.config.texBase.poke(0x30000.U)
          job.bits.config.texWidth.poke(2.U); job.bits.config.texHeight.poke(2.U)
        }
        if (phase == "jobBlend") {
          job.bits.config.blendCfgEnable.poke(true.B)
          job.bits.config.blendSrcFactor.poke(1.U); job.bits.config.blendDstFactor.poke(1.U)
        }
        job.ready.expect(true.B); job.valid.poke(true.B); tick(); job.valid.poke(false.B)
        // Every submitted job field, including viewport and draw defaults,
        // must remain immutable after its handshake.
        job.bits.poke(0.U.asTypeOf(new TileCommandJob))
        dut.io.screenW.poke(8.U); dut.io.screenH.poke(8.U)
        dut.io.depthFunc.poke(6.U)
        if (phase == "cancelEarly") {
          dut.io.tileCancel.get.poke(true.B); tick(); dut.io.tileCancel.get.poke(false.B)
        }
        if (phase == "cancel") {
          waitFor(reads == 85)
          holdingCommands = true
          dut.io.tileCancel.get.poke(true.B); tick(); dut.io.tileCancel.get.poke(false.B)
          for (_ <- 0 until 20) {
            tick(); completion.valid.expect(false.B); job.ready.expect(false.B)
          }
          holdingCommands = false
        }
        waitFor(completion.valid.peek().litToBoolean)
        val invalid = Set("badVersion", "badBounds", "badPass", "unaligned", "overflow", "badLimit")(phase)
        val expectedStatus = if (invalid) TilePassStatus.invalidDescriptor
          else if (phase == "cancel" || phase == "cancelEarly") TilePassStatus.cancelled
          else if (phase == "commandFault") TilePassStatus.commandFault
          else if (phase == "textureFault") TilePassStatus.textureFault
          else if (phase == "attachmentFault") TilePassStatus.memoryFault else TilePassStatus.success
        completion.bits.status.expect(expectedStatus.U)
        completion.bits.identity.context.expect(p.context.U)
        completion.bits.identity.pass.expect(p.id.U)
        completion.bits.identity.generation.expect(p.generation.U)
        assert(commands.isEmpty && commandReply.isEmpty && attachments.isEmpty && attachmentReply.isEmpty &&
          textures.isEmpty && textureReply.isEmpty)
        if (phase == "textureFault") assert(faultInjected && writes == 0)
        if (invalid) assert(reads == 0 && writes == 0)
        if (phase == "cancelEarly") assert(reads == 0 && writes == 0)
        if (Set("cancel", "commandFault")(phase)) assert(reads == 85 && writes == 0)
        if (expectedStatus == TilePassStatus.success) {
          assert(reads == 40 * count && writes == 128)
          for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8) {
            val covered = count > 0 && x > 0 && y > 0
            val expected = if (depth) { if (covered) (p.depthClear & 0xff000000L) | 16L else p.depthClear }
              else if (covered && phase == "override") 0x14283cffL
              else if (covered && phase == "jobBlend") ((216L + run) << 24) | 0x00486cffL
              else if (covered) ((200L + run) << 24) | 0x00283cffL else p.colorClear
            assert(memory(p.address(x, y, 0, depth)) == expected, s"$phase depth=$depth ($x,$y)")
          }
        }
        info(s"phase=$phase status=$expectedStatus commandReads=$reads attachmentWrites=$writes cycles=${cycle - startCycle}")
        val result = completion.bits.peek().litValue
        dut.io.tileCancel.get.poke(true.B)
        for (_ <- 0 until 4) {
          tick(); completion.valid.expect(true.B)
          assert(completion.bits.peek().litValue == result, "published completion changed under backpressure")
          job.ready.expect(false.B); dut.io.done.expect(false.B)
        }
        dut.io.tileCancel.get.poke(false.B)
        completion.ready.poke(true.B); tick(); completion.ready.poke(false.B)
        dut.io.done.expect(true.B)
      }
    }
  }
}
