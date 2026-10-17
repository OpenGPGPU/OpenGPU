package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Protocol executor at the shared-CU boundary. It copies the shader ABI's
  * input color/depth to outputs, but does not model instruction execution.
  * Real CU arithmetic remains covered by the core-backed pipeline tests.
  */
class PersistentTileShaderFaultSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "persistent programmable pass failure ownership"

  for (parallel <- Seq(false, true)) {
    it should s"drain task/staging failures and reuse pass identities with parallel=$parallel" in {
      val gpu = GpuConfig(lanes = 4, warps = 2)
      val cap = gpu.lanes * gpu.warps
      val cfg = GraphicsConfig(screenWidth = 16, screenHeight = 16,
        tileSize = 16, tileAttachments = true)
      val poolConfig = SharedCuShaderConfig(2, parallelFragments = parallel)
      simulate(new RenderPipeline(cfg, gpu, fragCore = true,
        sharedShader = Some(poolConfig), persistentAttachments = true, tileStoreCredits = 8)) { dut =>
        def zeroInputs(d: Data): Unit = d match {
          case r: Record => r.elements.values.foreach(zeroInputs)
          case v: Vec[_] => v.foreach(zeroInputs)
          case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
          case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
          case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
          case _ => ()
        }
        zeroInputs(dut.io)
        val pool = dut.io.shared.get
        val pass = dut.io.tilePass.get
        dut.io.screenW.poke(16.U); dut.io.screenH.poke(16.U)
        dut.io.texMem.req.ready.poke(true.B)
        pool.invalidate.ready.poke(true.B)
        pool.texture.foreach { t =>
          t.scalarWriteback.ready.poke(true.B); t.vectorWriteback.ready.poke(true.B)
        }
        pass.clearDone.ready.poke(true.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

        val memory = mutable.LongMap.empty[Long]
        def word(a: Long): Long = memory.getOrElse(a, 0xdeadbeefL)
        def line(a: Long): BigInt = (0 until 16).map(i =>
          BigInt(word(a + 4 * i)) << (32 * i)).foldLeft(BigInt(0))(_ | _)
        def writeLine(a: Long, data: BigInt, mask: BigInt): Unit = {
          for (i <- 0 until 16) {
            var value = word(a + 4 * i)
            for (b <- 0 until 4 if mask.testBit(4 * i + b)) {
              val shift = 8 * b
              value = (value & ~(255L << shift)) |
                (((data >> (32 * i + shift)).toLong & 255L) << shift)
            }
            memory(a + 4 * i) = value
          }
        }
        case class Staging(id: BigInt, addr: Long, write: Boolean, data: BigInt,
          mask: BigInt, fault: Boolean, due: Int)
        case class Store(addr: Long, data: Long, due: Int)
        case class Task(id: BigInt, due: Int, bad: Boolean, needsTrap: Boolean,
          var trapped: Boolean = false)
        val staging = mutable.ArrayBuffer.empty[Staging]
        val stores = mutable.ArrayBuffer.empty[Store]
        val tasks = mutable.ArrayBuffer.empty[Task]
        var stageReply: Option[Staging] = None
        var storeReply: Option[Store] = None
        var cycle = 0
        var phase = "none"
        var injected = false
        var ending = false
        var peakTasks = 0
        var externalWrites = 0
        var faultPulses = 0

        def tick(): Unit = {
          dut.io.kernelWordMemReq.ready.poke((cycle % 7 != 0).B)
          if (stageReply.isEmpty) {
            staging.indices.filter(i => staging(i).due <= cycle).lastOption.foreach { i =>
              stageReply = Some(staging.remove(i))
            }
          }
          dut.io.kernelWordMemResp.valid.poke(stageReply.nonEmpty.B)
          stageReply.foreach { r =>
            dut.io.kernelWordMemResp.bits.transactionId.poke(r.id.U)
            dut.io.kernelWordMemResp.bits.readData.poke(line(r.addr).U)
            dut.io.kernelWordMemResp.bits.fault.poke(r.fault.B)
          }
          val stageFire = stageReply.nonEmpty && dut.io.kernelWordMemResp.ready.peek().litToBoolean

          pool.launch.ready.poke((tasks.size < 2 && cycle % 5 != 0).B)
          pool.owned.poke(tasks.map(t => BigInt(1) << (t.id.toInt % 2)).foldLeft(BigInt(0))(_ | _).U)
          for (cu <- 0 until 2) {
            pool.taskIds(cu).poke(tasks.find(t => t.id.toInt % 2 == cu).map(_.id).getOrElse(BigInt(0)).U)
          }
          val trap = tasks.find(t => t.needsTrap && !t.trapped && cycle >= t.due - 20)
          pool.trap.valid.poke(trap.nonEmpty.B)
          trap.foreach(t => pool.trap.bits.taskId.poke(t.id.U))
          val trapFire = trap.nonEmpty && pool.trap.ready.peek().litToBoolean
          val complete = tasks.indices.filter(i => tasks(i).due <= cycle &&
            (!tasks(i).needsTrap || tasks(i).trapped)).lastOption
          pool.completion.valid.poke(complete.nonEmpty.B)
          complete.foreach { i =>
            pool.completion.bits.commandId.poke(tasks(i).id.U)
            pool.completion.bits.success.poke((!tasks(i).bad).B)
          }
          val taskFire = complete.filter(_ => pool.completion.ready.peek().litToBoolean)

          dut.io.mem.req.ready.poke((cycle % 4 != 0).B)
          if (storeReply.isEmpty) {
            stores.indices.filter(i => stores(i).due <= cycle).lastOption.foreach { i =>
              storeReply = Some(stores.remove(i))
            }
          }
          dut.io.mem.resp.valid.poke(storeReply.nonEmpty.B)
          storeReply.foreach { r =>
            dut.io.mem.resp.bits.write.poke(true.B)
            dut.io.mem.resp.bits.addr.poke(r.addr.U)
            dut.io.mem.resp.bits.data.poke(0.U)
          }
          val storeFire = storeReply.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean

          if (dut.io.kernelWordMemReq.valid.peek().litToBoolean &&
              dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
            val r = dut.io.kernelWordMemReq.bits
            val wr = r.isWrite.peek().litToBoolean
            val fault = !injected && ((phase == "write" && wr) || (phase == "read" && !wr))
            injected ||= fault
            staging += Staging(r.transactionId.peek().litValue, r.address.peek().litValue.toLong,
              wr, r.writeData.peek().litValue, r.byteMask.peek().litValue, fault,
              cycle + (if (cycle % 2 == 0) 23 else 4))
          }
          if (pool.launch.valid.peek().litToBoolean && pool.launch.ready.peek().litToBoolean) {
            val id = pool.launch.bits.commandId.peek().litValue
            assert(!tasks.exists(_.id == id), "shared task ID reused before completion")
            val launch = pool.launch.bits.launch
            val base = launch.kernargAddress.peek().litValue.toLong
            val count = launch.localSize(0).peek().litValue.toInt
            for (i <- 0 until count) {
              memory(base + 24L * cap + 4 * i) = word(base + 12L * cap + 4 * i)
              memory(base + 28L * cap + 4 * i) = word(base + 8L * cap + 4 * i)
              memory(base + 32L * cap + 4 * i) = 1
            }
            val bad = !injected && Set("trap", "completion")(phase)
            injected ||= bad
            tasks += Task(id, cycle + (if (id.testBit(0)) 170 else 45), bad, bad && phase == "trap")
            peakTasks = math.max(peakTasks, tasks.size)
          }
          if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
            assert(ending && phase == "none", "failed or unfinished pass published an attachment")
            dut.io.mem.req.bits.write.expect(true.B)
            stores += Store(dut.io.mem.req.bits.addr.peek().litValue.toLong,
              dut.io.mem.req.bits.data.peek().litValue.toLong, cycle + 31)
            externalWrites += 1
          }
          if (dut.io.shaderFault.peek().litToBoolean) faultPulses += 1
          if (stageFire) {
            val r = stageReply.get
            if (r.write && !r.fault) writeLine(r.addr, r.data, r.mask)
            stageReply = None
          }
          if (trapFire) trap.get.trapped = true
          taskFire.foreach(tasks.remove)
          if (storeFire) {
            val r = storeReply.get; memory(r.addr) = r.data; storeReply = None
          }
          dut.clock.step(); cycle += 1
        }
        def waitFor(p: => Boolean): Unit = {
          val start = cycle
          while (!p && cycle - start < 60000) tick()
          assert(p, s"timeout in $phase at cycle $cycle, tasks=${tasks.size}, staging=${staging.size}")
        }

        for ((fault, run) <- Seq("none", "trap", "completion", "write", "read", "none").zipWithIndex) {
          phase = fault; injected = false; ending = false; externalWrites = 0; faultPulses = 0
          val p = Pass(width = 8, height = 8, originX = 0, originY = 0, tileLog2 = 3,
            id = run, generation = run + 17, context = 100 + run)
          for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8)
            memory(p.address(x, y, 0, depth)) = 0xdeadbeefL
          pokeDescriptor(pass.begin.bits, p)
          pass.begin.ready.expect(true.B); pass.begin.valid.poke(true.B)
          tick(); pass.begin.valid.poke(false.B)
          pokeDescriptor(pass.begin.bits, p.copy(id = 99, context = 999))
          val draw = dut.io.draw.bits.asInstanceOf[SceneTriangle]
          for (drawIndex <- 0 until (if (fault == "none") 2 else 1)) {
            draw.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
            draw.stateOverride.poke(true.B)
            draw.depthTestEnable.poke(true.B); draw.depthWriteEnable.poke(true.B)
            draw.shaderPc.poke(0x1000.U); draw.shaderKernarg.poke(0x6000.U)
            draw.kernargBankStride.poke(0x2000.U)
            for ((xy, i) <- Seq((-1, -1), (1, -1), (-1, 1)).zipWithIndex) {
              draw.clip(i).x.poke((xy._1 * 65536).S); draw.clip(i).y.poke((xy._2 * 65536).S)
              draw.clip(i).w.poke(65536.S)
              draw.color(i).r.poke((if (drawIndex == 0) 200 + run else 20).U)
              draw.color(i).g.poke(40.U); draw.color(i).b.poke(60.U)
              draw.alpha(i).poke(255.U); draw.depth(i).poke(16.S)
            }
            waitFor(dut.io.draw.ready.peek().litToBoolean)
            dut.io.draw.valid.poke(true.B); tick(); dut.io.draw.valid.poke(false.B)
            waitFor(dut.io.done.peek().litToBoolean)
            assert(externalWrites == 0)
          }
          if (fault == "none") {
            waitFor(pass.end.ready.peek().litToBoolean)
            ending = true; pass.end.valid.poke(true.B); tick(); pass.end.valid.poke(false.B)
          }
          waitFor(pass.completion.valid.peek().litToBoolean)
          pass.completion.bits.status.expect((if (fault == "none") TilePassStatus.success
            else TilePassStatus.executionFault).U)
          pass.completion.bits.identity.context.expect(p.context.U)
          pass.completion.bits.identity.pass.expect(p.id.U)
          pass.completion.bits.identity.generation.expect(p.generation.U)
          assert(tasks.isEmpty && staging.isEmpty && stageReply.isEmpty && stores.isEmpty && storeReply.isEmpty)
          assert(injected == (fault != "none"))
          assert(faultPulses == (if (fault == "none") 0 else if (fault == "trap") 2 else 1),
            "lost a failure event or leaked one between pass owners")
          for (depth <- Seq(false, true); y <- 0 until 8; x <- 0 until 8) {
            val covered = x > 0 && y > 0 && x + y <= 16
            val expected = if (fault != "none") 0xdeadbeefL
              else if (depth) { if (covered) (p.depthClear & 0xff000000L) | 16L else p.depthClear }
              else if (covered) ((200L + run) << 24) | 0x00283cffL else p.colorClear
            assert(word(p.address(x, y, 0, depth)) == expected,
              s"$fault depth=$depth ($x,$y): ${word(p.address(x,y,0,depth)).toHexString} != ${expected.toHexString}")
          }
          assert(externalWrites == (if (fault == "none") 128 else 0))
          val completion = pass.completion.bits.peek().litValue
          for (_ <- 0 until 3) { tick(); assert(pass.completion.bits.peek().litValue == completion) }
          pass.completion.ready.poke(true.B); tick(); pass.completion.ready.poke(false.B)
        }
        assert(peakTasks == (if (parallel) 2 else 1), "shared workers did not execute concurrently")
      }
    }
  }
}
