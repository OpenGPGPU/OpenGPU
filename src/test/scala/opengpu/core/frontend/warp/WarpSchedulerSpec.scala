package opengpu.core.frontend.warp

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class WarpSchedulerSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 8, warps = 4)

  private def initialize(dut: WarpScheduler): Unit = {
    dut.io.launch.valid.poke(false.B)
    dut.io.issue.ready.poke(false.B)
    dut.io.resume.valid.poke(false.B)
    dut.io.finish.valid.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def awaitIssue(dut: WarpScheduler): Unit = {
    var clocks = 0
    while (!dut.io.issue.valid.peek().litToBoolean && clocks < 4) {
      dut.clock.step()
      clocks += 1
    }
    dut.io.issue.valid.expect(true.B)
  }

  private def launch(dut: WarpScheduler, warpId: Int, pc: Int, mask: Int): Unit = {
    dut.io.launch.valid.poke(true.B)
    dut.io.launch.bits.warpId.poke(warpId.U)
    dut.io.launch.bits.startPc.poke(pc.U)
    dut.io.launch.bits.activeMask.poke(mask.U)
    dut.io.launch.ready.expect(true.B)
    dut.clock.step()
    dut.io.launch.valid.poke(false.B)
  }

  behavior of "WarpScheduler"

  it should "allocate free warps and issue them in round-robin order" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      launch(dut, 0, 0x100, 0xff)
      launch(dut, 1, 0x200, 0x0f)
      launch(dut, 2, 0x300, 0xf0)

      dut.io.active.expect("b0111".U)
      dut.io.issue.ready.poke(true.B)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x100.U)
      dut.io.issue.bits.activeMask.expect(0xff.U)
      dut.clock.step()

      dut.io.issue.bits.warpId.expect(1.U)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(2.U)
      dut.clock.step()

      dut.io.issue.valid.expect(false.B)
      dut.io.blocked.expect("b0111".U)

      dut.io.resume.valid.poke(true.B)
      dut.io.resume.bits.warpId.poke(0.U)
      dut.io.resume.bits.nextPc.poke(0x104.U)
      dut.io.resume.bits.activeMask.poke(0x3c.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      // The returned PC/mask enter the registered issue slot at this edge.
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x104.U)
      dut.io.issue.bits.activeMask.expect(0x3c.U)
    }
  }

  it should "hold its grant under backpressure and recycle finished warps" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      launch(dut, 0, 0x400, 0xaa)
      launch(dut, 1, 0x500, 0x55)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.clock.step(3)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.blocked.expect("b0001".U)

      // Accept the registered grant before later finishing that warp.
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.ready.poke(false.B)

      // Finish is a clocked release event and is not on the grant path.
      dut.io.finish.valid.poke(true.B)
      dut.io.finish.bits.poke(0.U)
      dut.clock.step()
      dut.io.finish.valid.poke(false.B)
      dut.io.active.expect("b0010".U)
      dut.io.issue.bits.warpId.expect(1.U)

      // A finished slot is reused when the launch names it explicitly.
      launch(dut, 0, 0x600, 0xff)
      dut.io.active.expect("b0011".U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(2.U)
      dut.io.launch.bits.startPc.poke(0.U)
      dut.io.launch.bits.activeMask.poke(0.U)
      // Slots 2 and 3 remain free in this four-warp configuration; the
      // launch is backpressured only when the named slot is occupied.
      dut.io.launch.ready.expect(true.B)
      dut.io.launch.bits.warpId.poke(1.U)
      dut.io.launch.ready.expect(false.B)
      dut.io.launch.valid.poke(false.B)
    }
  }

  it should "deassert launch ready when all hardware warps are occupied" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      (0 until config.warps).foreach(i => launch(dut, i, 0x1000 + i * 4, 0xff))
      dut.io.active.expect("b1111".U)
      dut.io.launch.ready.expect(false.B)
    }
  }

  it should "support a single hardware warp without zero-width IDs" in {
    val singleWarp = GpuConfig(lanes = 4, warps = 1)
    simulate(new WarpScheduler(singleWarp)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(0.U)
      dut.io.launch.bits.startPc.poke(0x80.U)
      dut.io.launch.bits.activeMask.poke("b1111".U)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)
      dut.clock.step()

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x80.U)
    }
  }

  for (forward <- Seq(false, true))
  it should s"register an idle default-capacity warp after a resume with forwarding=$forward" in {
    simulate(new WarpScheduler(GpuConfig(), resumeToIssue = forward)) { dut =>
      initialize(dut)
      val slot = 7
      launch(dut, slot, 0x100, 0xffff)
      awaitIssue(dut)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.ready.poke(false.B)
      dut.io.issue.valid.expect(false.B)
      for (i <- 0 until 12) {
        val pc = 0x104 + 4 * i
        val mask = if (i % 2 == 0) 0x0fff else 0x5555
        dut.io.resume.valid.poke(true.B)
        dut.io.resume.bits.warpId.poke(slot.U)
        dut.io.resume.bits.nextPc.poke(pc.U)
        dut.io.resume.bits.activeMask.poke(mask.U)
        dut.io.issue.valid.expect(false.B)
        dut.clock.step()
        dut.io.resume.valid.poke(false.B)
        if (!forward) {
          dut.io.issue.valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.issue.valid.expect(true.B)
        dut.io.issue.bits.warpId.expect(slot.U)
        dut.io.issue.bits.pc.expect(pc.U)
        dut.io.issue.bits.activeMask.expect(mask.U)
        dut.io.blocked.expect((1 << slot).U)
        val held = dut.io.issue.bits.peek().litValue
        dut.clock.step(2)
        assert(dut.io.issue.bits.peek().litValue == held)
        dut.io.issue.ready.poke(true.B)
        dut.clock.step()
        dut.io.issue.ready.poke(false.B)
        dut.io.issue.valid.expect(false.B)
      }
      info(s"WARP_RESUME_REGISTERED_OFFER forwarding=$forward lanes=16 warps=8 resumes=12 clocksPerResume=${if(forward)1 else 2} exactPcAndMask=true heldOffer=true blockedUntilNextResume=true")
    }
  }

  it should "retain a held grant while another warp resumes and preserve round-robin priority" in {
    simulate(new WarpScheduler(config)) { dut =>
      initialize(dut)
      launch(dut, 1, 0x200, 0xff)
      awaitIssue(dut)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.ready.poke(false.B)
      launch(dut, 0, 0x100, 0x0f)
      awaitIssue(dut)
      launch(dut, 2, 0x300, 0xf0)
      val held = dut.io.issue.bits.peek().litValue
      dut.io.resume.valid.poke(true.B)
      dut.io.resume.bits.warpId.poke(1.U)
      dut.io.resume.bits.nextPc.poke(0x240.U)
      dut.io.resume.bits.activeMask.poke(0x55.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      for (_ <- 0 until 3) {
        dut.io.issue.valid.expect(true.B)
        assert(dut.io.issue.bits.peek().litValue == held)
        dut.clock.step()
      }
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(1.U)
      dut.io.issue.bits.pc.expect(0x240.U)
      dut.io.issue.bits.activeMask.expect(0x55.U)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(2.U)
      dut.clock.step()
      dut.io.issue.valid.expect(false.B)
      dut.io.blocked.expect("b0111".U)
    }
  }

  it should "fence resume forwarding for invalid slots and coincident launch or finish events" in {
    simulate(new WarpScheduler(GpuConfig(lanes = 4, warps = 3))) { dut =>
      def resume(slot: Int): Unit = {
        dut.io.resume.valid.poke(true.B)
        dut.io.resume.bits.warpId.poke(slot.U)
        dut.io.resume.bits.nextPc.poke(0x404.U)
        dut.io.resume.bits.activeMask.poke(0x7.U)
      }
      def blocked(): Unit = {
        initialize(dut)
        launch(dut, 0, 0x400, 0xf)
        awaitIssue(dut)
        dut.io.issue.ready.poke(true.B)
        dut.clock.step()
        dut.io.issue.ready.poke(false.B)
        dut.io.issue.valid.expect(false.B)
      }
      blocked()
      for (slot <- Seq(3, 2)) {
        resume(slot) // Out-of-range or inactive slots cannot create an offer.
        dut.clock.step()
        dut.io.resume.valid.poke(false.B)
        dut.io.issue.valid.expect(false.B)
        dut.io.active.expect(1.U)
        dut.io.blocked.expect(1.U)
      }
      resume(0)
      dut.io.finish.valid.poke(true.B)
      dut.io.finish.bits.poke(0.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.active.expect(0.U)
      dut.io.issue.valid.expect(false.B)

      blocked()
      resume(0)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(1.U)
      dut.io.launch.bits.startPc.poke(0x500.U)
      dut.io.launch.bits.activeMask.poke(0xe.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.issue.valid.expect(false.B) // Launch fenced the early resume path.
      dut.io.active.expect(3.U)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(1.U)
      dut.io.issue.bits.pc.expect(0x500.U)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x404.U)
      dut.io.issue.bits.activeMask.expect(0x7.U)
      dut.clock.step()
      dut.io.issue.valid.expect(false.B)
      dut.io.blocked.expect(3.U)

      blocked()
      resume(0)
      dut.io.finish.valid.poke(true.B)
      dut.io.finish.bits.poke(0.U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(2.U)
      dut.io.launch.bits.startPc.poke(0x600.U)
      dut.io.launch.bits.activeMask.poke(0xb.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.issue.valid.expect(false.B)
      dut.io.active.expect(4.U)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(2.U)
      dut.io.issue.bits.pc.expect(0x600.U)
      dut.io.issue.bits.activeMask.expect(0xb.U)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.valid.expect(false.B)
      dut.io.active.expect(4.U)

      // A registered ready warp keeps its original PC even if an unrelated
      // malformed resume targets that unblocked slot in its selection cycle.
      initialize(dut)
      launch(dut, 0, 0x400, 0xf)
      resume(0)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.pc.expect(0x400.U)
      dut.io.issue.bits.activeMask.expect(0xf.U)
      dut.io.blocked.expect(1.U)
    }
  }
}
