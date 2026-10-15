package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ParallelFragmentSchedulerSpec extends AnyFlatSpec {
  behavior of "ParallelFragmentScheduler"

  private val cases = Seq(1, 2, 4).flatMap(n => Seq((n, 4, 2, 0), (n, 4, 2, 4))) ++
    Seq((4, 16, 8, 96), (4, 16, 8, 112))
  for ((count, lanes, warps, limit) <- cases) {
    it should s"retire ordered quads across $count delayed workers (lanes=$lanes warps=$warps cap=$limit)" in {
      val config = GpuConfig(lanes = lanes, warps = warps)
      val capacity = if (limit == 0) lanes * warps else limit
      val quadsPerBatch = capacity / 4
      val gfx = GraphicsConfig(screenWidth = 256, screenHeight = 16, fragmentBatchLimit = limit)
      simulate(new ParallelFragmentScheduler(config, gfx, count)) { dut =>
        val front = dut.io.front
        front.fragIn.valid.poke(false.B)
        front.fragIn.bits.poke(0.U.asTypeOf(front.fragIn.bits))
        front.fragUv.poke(0.U.asTypeOf(front.fragUv))
        front.fragDepths.poke(0.U.asTypeOf(front.fragDepths))
        front.shaderPc.poke(0x1000.U)
        front.abi1.poke(true.B)
        front.kernargBase.poke(0x8000.U)
        front.kernargBankStride.poke(320.U)
        front.texBase.poke(0x10000.U)
        front.texWidth.poke(4.U)
        front.texHeight.poke(4.U)
        front.texWrapClamp.poke(true.B)
        front.texMaxLevel.poke(0.U)
        front.texLodBias.poke(0.S)
        front.texMinLevel.poke(0.U)
        front.flush.poke(false.B)
        front.out.ready.poke(false.B)
        front.drawRetire.ready.poke(false.B)
        dut.io.workers.foreach { worker =>
          worker.fragIn.ready.poke(true.B)
          worker.out.valid.poke(false.B)
          worker.out.bits.poke(0.U.asTypeOf(worker.out.bits))
          worker.outDepths.poke(0.U.asTypeOf(worker.outDepths))
          worker.depthOverride.poke(false.B)
          worker.drained.poke(true.B)
          worker.drawRetire.valid.poke(false.B)
          worker.drawRetire.bits.poke(true.B)
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        case class Sample(x: Int, covered: Boolean)
        case class Worker(input: mutable.ArrayBuffer[Sample], output: mutable.Queue[Sample],
          var started: Boolean = false, var delay: Int = 0)
        val models = Array.fill(count)(Worker(mutable.ArrayBuffer.empty, mutable.Queue.empty))
        val expected = mutable.ArrayBuffer.empty[Int]
        val observed = mutable.ArrayBuffer.empty[Int]
        var cycles = 0
        var nextQuad = 0
        var retireCount = 0
        var outOfOrderReady = false
        var stalled: Option[(Int, Seq[BigInt], Boolean)] = None

        def step(): Boolean = {
          front.out.ready.poke((cycles % 7 >= 3).B)
          front.drawRetire.ready.poke((cycles % 5 != 0).B)
          for (i <- 0 until count) {
            val port = dut.io.workers(i); val model = models(i)
            if (port.flush.peek().litToBoolean && !model.started) {
              assert(model.input.nonEmpty && model.input.size <= capacity)
              model.output ++= model.input.filter(_.covered)
              model.started = true
              model.delay = if (i == 0) 23 else 2
            }
            port.fragIn.ready.poke((!model.started && model.input.size < capacity).B)
            port.out.valid.poke((model.started && model.delay == 0 && model.output.nonEmpty).B)
            port.drawRetire.valid.poke((model.started && model.delay == 0 && model.output.isEmpty).B)
            port.drained.poke((!model.started && model.input.isEmpty).B)
            model.output.headOption.foreach { sample =>
              port.out.bits.x.poke(sample.x.S)
              port.depthOverride.poke((sample.x % 2 != 0).B)
              for (s <- 0 until 4) port.outDepths(s).poke((sample.x * 10 + s).U)
            }
          }
          if (count > 1 && models(0).started && models(0).delay > 0 &&
              models.drop(1).exists(m => m.started && m.delay == 0)) outOfOrderReady = true
          val accepted = front.fragIn.valid.peek().litToBoolean && front.fragIn.ready.peek().litToBoolean
          if (accepted) {
            for (lane <- 0 until 4 if front.fragIn.bits.lanes(lane).covered.peek().litToBoolean)
              expected += front.fragIn.bits.lanes(lane).x.peek().litValue.toInt
          }
          val inputs = (0 until count).map { i =>
            val port = dut.io.workers(i)
            if (port.fragIn.valid.peek().litToBoolean && port.fragIn.ready.peek().litToBoolean) {
              val stride = front.kernargBankStride.peek().litValue.toInt
              port.kernargBase.expect((0x8000 + 2 * i * stride).U)
              val samples = (0 until 4).map { lane =>
                // Helpers must be delivered intact; worker output may omit them.
                port.fragIn.bits.lanes(lane).x.expect(front.fragIn.bits.lanes(lane).x.peek())
                port.fragUv(lane).u.expect(front.fragUv(lane).u.peek())
                Sample(port.fragIn.bits.lanes(lane).x.peek().litValue.toInt,
                  port.fragIn.bits.lanes(lane).covered.peek().litToBoolean)
              }
              if (stride == 0) assert(i == 0, "zero-stride binding escaped worker zero")
              Some(samples)
            } else None
          }
          assert(inputs.count(_.nonEmpty) == (if (accepted) 1 else 0))
          if (front.out.valid.peek().litToBoolean) {
            val value = (front.out.bits.x.peek().litValue.toInt,
              front.outDepths.map(_.peek().litValue).toSeq, front.depthOverride.peek().litToBoolean)
            stalled.foreach(old => assert(value == old, "stalled fragment or depth sideband changed"))
            assert(value._2 == (0 until 4).map(s => BigInt(value._1 * 10 + s)))
            assert(value._3 == (value._1 % 2 != 0))
            if (front.out.ready.peek().litToBoolean) { observed += value._1; stalled = None }
            else stalled = Some(value)
          } else assert(stalled.isEmpty, "stalled output disappeared")
          if (front.drawRetire.valid.peek().litToBoolean && front.drawRetire.ready.peek().litToBoolean)
            retireCount += 1
          val outputFires = dut.io.workers.map(p => p.out.valid.peek().litToBoolean && p.out.ready.peek().litToBoolean)
          val retireFires = dut.io.workers.map(p => p.drawRetire.valid.peek().litToBoolean && p.drawRetire.ready.peek().litToBoolean)
          dut.clock.step(); cycles += 1
          for (i <- 0 until count) {
            val model = models(i)
            inputs(i).foreach(model.input ++= _)
            if (outputFires(i)) model.output.dequeue()
            if (retireFires(i)) {
              assert(model.output.isEmpty)
              model.input.clear(); model.started = false
            }
            if (model.delay > 0) model.delay -= 1
          }
          assert(cycles < 4000, "parallel fragment scheduling did not make progress")
          accepted
        }
        def quad(empty: Boolean = false): Unit = {
          front.flush.poke(false.B)
          front.fragIn.valid.poke(true.B)
          for (lane <- 0 until 4) {
            val x = nextQuad * 4 + lane
            front.fragIn.bits.lanes(lane).x.poke(x.S)
            front.fragIn.bits.lanes(lane).covered.poke((!empty && x % 3 != 0).B)
            front.fragUv(lane).u.poke((x * 11).U)
          }
          var accepted = false
          while (!accepted) accepted = step()
          front.fragIn.valid.poke(false.B); nextQuad += 1
        }
        def flush(): Unit = {
          front.flush.poke(true.B); step(); front.flush.poke(false.B); step()
        }
        def waitRetires(target: Int): Unit = while (retireCount < target) step()

        // More than one pool's worth of batches, followed by an empty draw.
        for (_ <- 0 until count * quadsPerBatch + 1) quad()
        flush(); flush(); waitRetires(2)
        assert(observed.toSeq == expected.toSeq)
        if (count > 1) assert(outOfOrderReady, "workers never became ready out of order")
        // A partial batch with no live outputs must still retire exactly once.
        quad(empty = true); flush(); waitRetires(3)
        // Let a full batch retire before its real raster flush arrives.
        for (_ <- 0 until quadsPerBatch) quad()
        while (dut.io.assignedWorkers.peek().litValue != 0) step()
        assert(retireCount == 3, "a batch retirement was mistaken for a draw end")
        flush(); waitRetires(4)
        // Keep legacy zero-stride bindings serialized on worker zero.
        front.kernargBankStride.poke(0.U)
        for (_ <- 0 until 5) quad()
        flush(); waitRetires(5)
        for (_ <- 0 until 3) step()
        assert(observed.toSeq == expected.toSeq)
        assert(retireCount == 5)
        dut.io.assignedWorkers.expect(0.U)
        front.drained.expect(true.B)
      }
    }
  }
}
