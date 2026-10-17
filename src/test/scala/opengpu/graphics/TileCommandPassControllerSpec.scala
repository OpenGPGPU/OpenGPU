package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileCommandPassControllerSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "tile command/pass ownership"

  private def initialize(dut: TileCommandPassController): Unit = {
    def zeroInputs(d: Data): Unit = d match {
      case r: Record => r.elements.values.foreach(zeroInputs)
      case v: Vec[_] => v.foreach(zeroInputs)
      case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
      case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
      case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
      case _ => ()
    }
    zeroInputs(dut.io)
    dut.io.commandsDrained.poke(true.B); dut.io.renderDrained.poke(true.B)
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.job.bits.version.poke(1.U)
    pokeDescriptor(dut.io.job.bits.passDescriptor, Pass(context = 91))
    dut.io.job.bits.config.cmdBase.poke(0x4000.U)
    dut.io.job.bits.config.cmdCount.poke(2.U)
    dut.io.job.bits.commandLimit.poke(0x4140.U)
    dut.io.job.valid.poke(true.B); dut.io.job.ready.expect(true.B)
    dut.clock.step(); dut.io.job.valid.poke(false.B)
    var cycles = 0
    while (!dut.io.pass.begin.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.pass.begin.valid.expect(true.B)
    val snapshot = dut.io.pass.begin.bits.peek().litValue
    dut.io.job.bits.poke(0.U.asTypeOf(new TileCommandJob))
    for (_ <- 0 until 4) {
      dut.clock.step(); dut.io.pass.begin.valid.expect(true.B)
      assert(dut.io.pass.begin.bits.peek().litValue == snapshot)
      dut.io.config.cmdBase.expect(0x4000.U); dut.io.config.cmdCount.expect(2.U)
    }
    dut.io.pass.begin.ready.poke(true.B)
    dut.clock.step(); dut.io.pass.begin.ready.poke(false.B)
    dut.io.pass.active.poke(true.B)
  }
  private def ownerResult(dut: TileCommandPassController, status: Int): Unit = {
    val c = dut.io.pass.completion
    c.bits.identity.context.poke(91.U); c.bits.identity.pass.poke(7.U)
    c.bits.identity.generation.poke(11.U); c.bits.status.poke(status.U)
    c.valid.poke(true.B)
  }
  private def consumeStableResult(dut: TileCommandPassController, status: Int): Unit = {
    dut.io.completion.valid.expect(true.B)
    dut.io.completion.bits.status.expect(status.U)
    dut.io.completion.bits.identity.context.expect(91.U)
    val snapshot = dut.io.completion.bits.peek().litValue
    dut.io.cancel.poke(true.B); dut.io.commandFault.poke(true.B); dut.io.shaderFault.poke(true.B)
    dut.io.textureFault.poke(true.B)
    for (_ <- 0 until 4) {
      dut.clock.step(); dut.io.completion.valid.expect(true.B)
      assert(dut.io.completion.bits.peek().litValue == snapshot)
      dut.io.job.ready.expect(false.B)
    }
    dut.io.cancel.poke(false.B); dut.io.commandFault.poke(false.B); dut.io.shaderFault.poke(false.B)
    dut.io.textureFault.poke(false.B)
    dut.io.completion.ready.poke(true.B); dut.clock.step()
    dut.io.job.ready.expect(true.B); dut.io.busy.expect(false.B)
  }

  for ((fault, status, ownerStatus) <- Seq(
    ("cancel", TilePassStatus.cancelled, TilePassStatus.cancelled),
    ("command", TilePassStatus.commandFault, TilePassStatus.cancelled),
    ("shader", TilePassStatus.executionFault, TilePassStatus.cancelled),
    ("texture", TilePassStatus.textureFault, TilePassStatus.cancelled),
    ("command", TilePassStatus.memoryFault, TilePassStatus.memoryFault))) {
    it should s"retain a $fault failure with owner status $ownerStatus through both drains" in {
      simulate(new TileCommandPassController) { dut =>
        initialize(dut)
        dut.io.admitted.poke(true.B); dut.clock.step(); dut.io.admitted.poke(false.B)
        dut.io.startCommands.expect(true.B)
        dut.io.commandsDrained.poke(false.B); dut.io.renderDrained.poke(false.B)
        dut.clock.step()
        dut.io.pass.end.valid.expect(false.B)
        if (fault == "cancel") dut.io.cancel.poke(true.B)
        if (fault == "command") dut.io.commandFault.poke(true.B)
        if (fault == "shader") dut.io.shaderFault.poke(true.B)
        if (fault == "texture") dut.io.textureFault.poke(true.B)
        dut.io.cancelCommands.expect(true.B); dut.io.pass.cancel.expect(true.B)
        dut.clock.step()
        dut.io.cancel.poke(false.B); dut.io.commandFault.poke(false.B); dut.io.shaderFault.poke(false.B)
        dut.io.textureFault.poke(false.B)
        // A secondary shader failure during texture drain must not hide the
        // more precise texture failure already attributed to this job.
        if (fault == "texture") dut.io.shaderFault.poke(true.B)
        ownerResult(dut, ownerStatus)
        for (_ <- 0 until 6) {
          dut.clock.step(); dut.io.pass.completion.ready.expect(false.B)
          dut.io.completion.valid.expect(false.B); dut.io.cancelCommands.expect(true.B)
        }
        dut.io.shaderFault.poke(false.B)
        dut.io.commandsDrained.poke(true.B)
        for (_ <- 0 until 3) { dut.clock.step(); dut.io.pass.completion.ready.expect(false.B) }
        dut.io.renderDrained.poke(true.B)
        dut.io.pass.completion.ready.expect(true.B); dut.clock.step()
        dut.io.pass.completion.valid.poke(false.B)
        consumeStableResult(dut, status)
      }
    }
  }

  it should "start once after admission and end only after all command and render work drains" in {
    simulate(new TileCommandPassController) { dut =>
      initialize(dut)
      for (_ <- 0 until 5) { dut.clock.step(); dut.io.startCommands.expect(false.B) }
      dut.io.admitted.poke(true.B); dut.clock.step(); dut.io.admitted.poke(false.B)
      dut.io.startCommands.expect(true.B)
      dut.io.commandsDrained.poke(false.B); dut.io.renderDrained.poke(false.B)
      dut.clock.step(); dut.io.startCommands.expect(false.B)
      for (_ <- 0 until 4) { dut.clock.step(); dut.io.pass.end.valid.expect(false.B) }
      dut.io.commandsDrained.poke(true.B)
      dut.clock.step(); dut.io.pass.end.valid.expect(false.B)
      dut.io.renderDrained.poke(true.B)
      for (_ <- 0 until 4) { dut.clock.step(); dut.io.pass.end.valid.expect(true.B) }
      dut.io.pass.end.ready.poke(true.B); dut.clock.step(); dut.io.pass.end.ready.poke(false.B)
      ownerResult(dut, TilePassStatus.success)
      dut.io.pass.completion.ready.expect(true.B); dut.clock.step()
      dut.io.pass.completion.valid.poke(false.B)
      consumeStableResult(dut, TilePassStatus.success)
    }
  }
}
