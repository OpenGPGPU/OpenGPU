package opengpu.core.frontend

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class WarpInstructionGuardSpec extends AnyFlatSpec {
  private class Driver(val d: WarpInstructionGuard) {
    d.io.request.valid.poke(false.B)
    d.io.request.bits.warpId.poke(0.U); d.io.request.bits.pc.poke(0.U)
    d.io.physicalRequest.ready.poke(false.B)
    d.io.physicalResponse.valid.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.io.response.ready.poke(false.B)
    d.io.launch.valid.poke(false.B)
    d.io.launch.bits.warpId.poke(0.U); d.io.launch.bits.instructions.poke(0.U)
    d.io.finish.valid.poke(false.B); d.io.finish.bits.poke(0.U)
    d.io.kernelStart.poke(false.B); d.io.interrupt.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(p: => Boolean): Unit = {
      var n = 0
      while (!p && n < 80) { d.clock.step(); n += 1 }
      assert(p, "guard did not make progress")
    }
    def start(): Unit = { d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B) }
    def launch(warp: Int, limit: BigInt): Unit = {
      d.io.launch.bits.warpId.poke(warp.U); d.io.launch.bits.instructions.poke(limit.U)
      d.io.launch.valid.poke(true.B); d.clock.step(); d.io.launch.valid.poke(false.B)
    }
    def request(warp: Int, pc: Int): Unit = {
      d.io.request.bits.warpId.poke(warp.U); d.io.request.bits.pc.poke(pc.U)
      d.io.request.valid.poke(true.B); until(d.io.request.ready.peek().litToBoolean)
      d.clock.step(); d.io.request.valid.poke(false.B)
    }
    def physical(warp: Int, pc: Int): Unit = {
      until(d.io.physicalRequest.valid.peek().litToBoolean)
      d.io.physicalRequest.bits.warpId.expect(warp.U); d.io.physicalRequest.bits.pc.expect(pc.U)
      d.io.physicalRequest.ready.poke(true.B); d.clock.step(); d.io.physicalRequest.ready.poke(false.B)
    }
    def reply(warp: Int, fault: Boolean = false): Unit = {
      d.io.physicalResponse.bits.warpId.poke(warp.U)
      d.io.physicalResponse.bits.instruction.poke((0x100 + warp).U)
      d.io.physicalResponse.bits.accessFault.poke(fault.B)
      d.io.physicalResponse.valid.poke(true.B)
      until(d.io.physicalResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.physicalResponse.valid.poke(false.B)
    }
    def response(warp: Int, cause: Int = 0, memoryFault: Boolean = false): Unit = {
      until(d.io.response.valid.peek().litToBoolean)
      d.io.response.bits.warpId.expect(warp.U)
      d.io.response.bits.executionFault.expect(cause.U)
      d.io.response.bits.accessFault.expect((cause != 0 || memoryFault).B)
      d.io.response.bits.instruction.expect((if (cause != 0) 0 else 0x100 + warp).U)
      val held = d.io.response.bits.peek().litValue
      for (_ <- 0 until 7) {
        d.io.response.valid.expect(true.B)
        assert(d.io.response.bits.peek().litValue == held)
        d.clock.step()
      }
      d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
    }
    def finish(warp: Int): Unit = {
      d.io.finish.valid.poke(true.B); d.io.finish.bits.poke(warp.U)
      d.clock.step(); d.io.finish.valid.poke(false.B)
    }
  }

  for ((lanes, warps) <- Seq((1, 1), (4, 2), (16, 8))) {
    val config = GpuConfig(lanes = lanes, warps = warps)
    it should s"enforce exact independent quotas and retain the full unsigned limit at $lanes/$warps" in {
      simulate(new WarpInstructionGuard(config)) { d =>
        val x = new Driver(d); x.start()
        for (warp <- 0 until warps) x.launch(warp, warp + 1)
        for (round <- 0 to warps; warp <- round until warps) {
          x.request(warp, 0x1000 + round * 4)
          x.physical(warp, 0x1000 + round * 4); x.reply(warp); x.response(warp)
        }
        for (warp <- 0 until warps) {
          x.request(warp, 0x2000); x.response(warp, ShaderExecutionFault.instructionLimit)
          d.io.physicalRequest.valid.expect(false.B); x.finish(warp)
        }
        d.io.busy.expect(false.B); x.start(); x.launch(0, BigInt("ffffffff", 16))
        for (i <- 0 until 4) {
          x.request(0, i * 4); x.physical(0, i * 4); x.reply(0); x.response(0)
        }
        x.finish(0); d.io.busy.expect(false.B)
      }
    }
    it should s"latch interruption for unlimited warps and clear it only on a fresh kernel at $lanes/$warps" in {
      simulate(new WarpInstructionGuard(config)) { d =>
        val x = new Driver(d); x.start(); x.launch(0, 0)
        for (i <- 0 until 5) {
          x.request(0, i * 4); x.physical(0, i * 4); x.reply(0); x.response(0)
        }
        d.io.interrupt.poke(true.B); d.clock.step(); d.io.interrupt.poke(false.B)
        x.request(0, 20); x.response(0, ShaderExecutionFault.interrupted); x.finish(0)
        // Reallocating a warp within the old kernel does not clear cancellation.
        x.launch(0, 9); x.request(0, 24); x.response(0, ShaderExecutionFault.interrupted); x.finish(0)
        x.start(); x.launch(0, 1); x.request(0, 28); x.physical(0, 28); x.reply(0); x.response(0); x.finish(0)
        d.io.busy.expect(false.B)
      }
    }
    it should s"keep an owned physical offer and its real fault intact across interruption at $lanes/$warps" in {
      simulate(new WarpInstructionGuard(config)) { d =>
        val x = new Driver(d); x.start(); x.launch(0, 1); x.request(0, 0x1000)
        x.until(d.io.physicalRequest.valid.peek().litToBoolean)
        val held = d.io.physicalRequest.bits.peek().litValue
        d.io.interrupt.poke(true.B)
        for (_ <- 0 until 11) {
          d.io.physicalRequest.valid.expect(true.B)
          assert(d.io.physicalRequest.bits.peek().litValue == held)
          d.io.response.valid.expect(false.B); d.io.busy.expect(true.B); d.clock.step()
        }
        d.io.interrupt.poke(false.B)
        x.physical(0, 0x1000); x.reply(0, fault = true); x.response(0, memoryFault = true)
        // Interruption takes precedence over an exhausted quota on a future fetch.
        x.request(0, 0x1004); x.response(0, ShaderExecutionFault.interrupted); x.finish(0)
        d.io.busy.expect(false.B)
      }
    }
    it should s"use a same-cycle allocation budget and accept an immediate owned reply at $lanes/$warps" in {
      simulate(new WarpInstructionGuard(config)) { d =>
        val x = new Driver(d); x.start()
        d.io.launch.valid.poke(true.B); d.io.launch.bits.instructions.poke(1.U)
        x.request(0, 0x1000); d.io.launch.valid.poke(false.B)
        d.io.physicalRequest.ready.poke(true.B)
        d.io.physicalResponse.valid.poke(true.B)
        d.io.physicalResponse.bits.instruction.poke(0x100.U)
        d.io.physicalRequest.valid.expect(true.B); d.io.physicalResponse.ready.expect(true.B)
        d.clock.step(); d.io.physicalResponse.valid.poke(false.B); d.io.physicalRequest.ready.poke(false.B)
        x.response(0); x.request(0, 0x1004); x.response(0, ShaderExecutionFault.instructionLimit)
        x.finish(0); d.io.busy.expect(false.B)
      }
    }
  }

  it should "retain all eight owners while out-of-order replies and synthetic stops compete under backpressure" in {
    simulate(new WarpInstructionGuard()) { d =>
      val x = new Driver(d); x.start()
      for (warp <- 0 until 8) {
        x.launch(warp, 1); x.request(warp, 0x1000 + warp * 4); x.physical(warp, 0x1000 + warp * 4)
      }
      // Two physical replies fill the public response queue.
      x.reply(7); x.reply(5)
      d.io.interrupt.poke(true.B); d.clock.step(); d.io.interrupt.poke(false.B)
      x.response(7); x.request(7, 0x2000)
      d.clock.step(3)
      x.response(5); x.response(7, ShaderExecutionFault.interrupted); x.finish(7)
      for (warp <- Seq(3, 1, 6, 4, 2, 0)) {
        x.reply(warp, fault = warp == 3); x.response(warp, memoryFault = warp == 3)
        x.request(warp, 0x2000); x.response(warp, ShaderExecutionFault.interrupted); x.finish(warp)
      }
      x.request(5, 0x2000); x.response(5, ShaderExecutionFault.interrupted); x.finish(5)
      d.io.busy.expect(false.B); x.start()
    }
  }
}
