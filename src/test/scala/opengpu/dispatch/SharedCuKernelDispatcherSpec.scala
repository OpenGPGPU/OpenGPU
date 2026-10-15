package opengpu.dispatch

import chisel3._
import chisel3.util.DecoupledIO
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class SharedCuKernelDispatcherSpec extends AnyFlatSpec {
  behavior of "SharedCuKernelDispatcher"

  private def task(port: DecoupledIO[TaggedKernelLaunch], id: Int): Unit = {
    port.bits.commandId.poke(id.U)
    port.bits.launch.kernelPc.poke((0x1000 + id * 4).U)
    port.bits.launch.kernargAddress.poke((0x8000 + id * 64).U)
    for (d <- 0 until 3) {
      port.bits.launch.gridSize(d).poke(1.U)
      port.bits.launch.localSize(d).poke(1.U)
    }
  }

  for (count <- Seq(1, 2, 4)) {
    it should s"retain producer tags and make independent retirement progress with $count CUs" in {
      simulate(new SharedCuKernelDispatcher(GpuConfig(lanes = 4, warps = 2), count, 12)) { dut =>
        dut.io.launch.valid.poke(false.B)
        dut.io.graphicsLaunch.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.graphicsCompletion.ready.poke(false.B)
        for (cu <- 0 until count) {
          dut.io.cuLaunch(cu).ready.poke(true.B)
          dut.io.cuCompletion(cu).valid.poke(false.B)
          dut.io.cuCompletion(cu).bits.success.poke(true.B)
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val rng = new Random(2048 + count)
        case class Running(graphics: Boolean, id: Int, success: Boolean, var delay: Int)
        val running = Array.fill[Option[Running]](count)(None)
        val pending = mutable.Map.empty[(Boolean, Int), Boolean]
        val next = Array(0, 0)
        val retired = Array(0, 0)
        val stalled = Array.fill[Option[(BigInt, Boolean)]](2)(None)
        var computeDuringGraphicsStall = 0

        for (cycle <- 0 until 600) {
          val issuing = cycle < 350
          dut.io.launch.valid.poke(issuing.B)
          dut.io.graphicsLaunch.valid.poke(issuing.B)
          task(dut.io.launch, next(0)); task(dut.io.graphicsLaunch, next(1))
          dut.io.completion.ready.poke((cycle >= 450 || rng.nextBoolean()).B)
          dut.io.graphicsCompletion.ready.poke((cycle >= 450 ||
            (cycle >= 160 && rng.nextBoolean())).B)
          for (cu <- 0 until count) {
            val r = running(cu)
            dut.io.cuCompletion(cu).valid.poke(r.exists(_.delay == 0).B)
            dut.io.cuCompletion(cu).bits.success.poke(r.forall(_.success).B)
          }
          val mask = running.zipWithIndex.foldLeft(BigInt(0)) { case (m, (r, cu)) =>
            if (r.nonEmpty) m.setBit(cu) else m
          }
          dut.io.busy.expect(mask.U)
          dut.io.graphicsOwner.expect(running.zipWithIndex.foldLeft(BigInt(0)) {
            case (m, (r, cu)) => if (r.exists(_.graphics)) m.setBit(cu) else m
          }.U)
          for (cu <- 0 until count; r <- running(cu))
            dut.io.taskIds(cu).expect(r.id.U)

          for ((port, owner) <- Seq(dut.io.completion -> 0, dut.io.graphicsCompletion -> 1)) {
            val valid = port.valid.peek().litToBoolean
            val ready = port.ready.peek().litToBoolean
            if (valid) {
              val value = (port.bits.commandId.peek().litValue, port.bits.success.peek().litToBoolean)
              stalled(owner).foreach(old => assert(old == value, "stalled completion changed"))
              if (ready) {
                assert(pending.remove((owner == 1, value._1.toInt)).contains(value._2),
                  s"unexpected owner=$owner completion=$value")
                retired(owner) += 1
                if (owner == 0 && cycle < 160) computeDuringGraphicsStall += 1
              }
              stalled(owner) = if (ready) None else Some(value)
            } else assert(stalled(owner).isEmpty, "stalled completion disappeared")
          }

          for (cu <- 0 until count) {
            if (dut.io.cuCompletion(cu).valid.peek().litToBoolean &&
                dut.io.cuCompletion(cu).ready.peek().litToBoolean) running(cu) = None
          }
          val accepted = Seq(dut.io.launch, dut.io.graphicsLaunch).zipWithIndex.filter {
            case (p, _) => p.valid.peek().litToBoolean && p.ready.peek().litToBoolean
          }
          assert(accepted.size <= 1)
          for ((_, owner) <- accepted) {
            val cu = (0 until count).find(i => dut.io.cuLaunch(i).valid.peek().litToBoolean).get
            assert(running(cu).isEmpty)
            dut.io.cuLaunch(cu).bits.kernelPc.expect((0x1000 + next(owner) * 4).U)
            val success = (next(owner) % 5) != 0
            running(cu) = Some(Running(owner == 1, next(owner), success, rng.nextInt(6) + 1))
            pending((owner == 1, next(owner))) = success
            next(owner) += 1
          }
          dut.clock.step()
          running.flatten.foreach(r => if (r.delay > 0) r.delay -= 1)
        }
        assert(pending.isEmpty && running.forall(_.isEmpty), s"undrained tasks: $pending")
        assert(retired.toSeq == next.toSeq)
        assert(retired.forall(_ > 5))
        assert(computeDuringGraphicsStall > 5, "graphics backpressure blocked compute")
      }
    }
  }

  it should "select another available CU when the first idle receiver is not ready" in {
    simulate(new SharedCuKernelDispatcher(GpuConfig(lanes = 4, warps = 2), 2, 8)) { dut =>
      dut.io.launch.valid.poke(false.B)
      dut.io.graphicsLaunch.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.graphicsCompletion.ready.poke(true.B)
      for (cu <- 0 until 2) {
        dut.io.cuLaunch(cu).ready.poke((cu == 1).B)
        dut.io.cuCompletion(cu).valid.poke(false.B)
      }
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      task(dut.io.graphicsLaunch, 7)
      dut.io.graphicsLaunch.valid.poke(true.B)
      dut.io.graphicsLaunch.ready.expect(true.B)
      dut.io.cuLaunch(0).valid.expect(false.B)
      dut.io.cuLaunch(1).valid.expect(true.B)
      dut.clock.step()
      dut.io.graphicsLaunch.valid.poke(false.B)
      dut.io.graphicsOwner.expect(2.U)
      dut.io.taskIds(1).expect(7.U)
    }
  }
}
