package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageTask
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercise real command queues and grid/physical dispatch without replacing
  * their admission or completion logic with a test-side scheduler.
  */
private class StorageCommandDispatch extends Module {
  private val config = GpuConfig(lanes = 4, warps = 2)
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new KernelCommand(config, 4, false, true)))
    val completion = Decoupled(new KernelCommandResult(4))
    val graphics = Flipped(Decoupled(new TaggedKernelLaunch(config, 4, false, true)))
    val graphicsCompletion = Decoupled(new TaggedKernelCompletion(4))
    val cu = Vec(2, Decoupled(new KernelLaunch(config)))
    val storage = Output(Vec(2, new StorageTask))
    val group = Output(Vec(2, new KernelGridSlice))
    val done = Vec(2, Flipped(Decoupled(new KernelCompletion)))
    val lease = Output(Bool())
    val duplicate = Output(Bool())
    val pathReset = Input(Bool())
  })
  private val commands = Module(new GpuCommandProcessor(config, 4, 3, 3, false, true))
  private val grids = Module(new SharedCuGridDispatcher(config, 2, 4, None, false, true))
  commands.io.command <> io.command
  io.completion <> commands.io.completion
  grids.io.launch <> commands.io.dispatch
  commands.io.dispatchCompletion <> grids.io.completion
  grids.io.graphicsLaunch <> io.graphics
  io.graphicsCompletion <> grids.io.graphicsCompletion
  io.cu <> grids.io.cuLaunch
  grids.io.cuCompletion <> io.done
  io.storage := grids.io.cuStorage.get
  io.group := grids.io.cuGridSlice
  io.lease := commands.io.storageLease.get
  io.duplicate := commands.io.duplicateCommandId
  commands.io.pathReset := io.pathReset
  commands.io.dmaCompletion := 0.U.asTypeOf(commands.io.dmaCompletion)
}

class GpuStorageCommandContextSpec extends AnyFlatSpec {
  it should "retain each queued storage publication across all workgroups and held public completions" in {
    simulate(new StorageCommandDispatch) { d =>
      d.io.command.valid.poke(false.B)
      d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
      d.io.completion.ready.poke(false.B)
      d.io.graphics.valid.poke(false.B)
      d.io.graphics.bits.poke(0.U.asTypeOf(d.io.graphics.bits))
      d.io.graphicsCompletion.ready.poke(false.B)
      d.io.pathReset.poke(false.B)
      for(cu <- 0 until 2) {
        d.io.cu(cu).ready.poke(false.B)
        d.io.done(cu).valid.poke(false.B)
        d.io.done(cu).bits.success.poke(true.B)
      }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val publications = Map(1 -> (true, 0x4000, 0x4400),
                             2 -> (false, 0x8000, 0x8800),
                             3 -> (true, 0xc000, 0xcc00))
      def offer(id: Int): Unit = {
        val (enabled, base, limit) = publications(id)
        d.io.command.bits.commandId.poke(id.U)
        d.io.command.bits.launch.kernelPc.poke((0x1000 * id).U)
        d.io.command.bits.launch.kernargAddress.poke((0x10000 * id).U)
        for(axis <- 0 until 3) {
          d.io.command.bits.launch.gridSize(axis).poke((if(axis == 0) 4 else 1).U)
          d.io.command.bits.launch.localSize(axis).poke(1.U)
        }
        val task = d.io.command.bits.storage.get
        task.enabled.poke(enabled.B); task.table.version.poke(1.U)
        task.table.base.poke(base.U); task.table.limit.poke(limit.U)
        task.table.identity.context.poke(id.U)
        task.table.identity.pass.poke((id + 10).U)
        task.table.identity.generation.poke((id + 20).U)
        d.io.command.valid.poke(true.B)
        var cycles = 0
        while(!d.io.command.ready.peek().litToBoolean && cycles < 40) {
          d.clock.step(); cycles += 1
        }
        d.io.command.ready.expect(true.B)
        d.clock.step(); d.io.command.valid.poke(false.B)
      }
      for(id <- 1 to 3) offer(id)
      d.io.lease.expect(true.B)
      // Later live pins must not modify any accepted command or offered child.
      d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
      val groups = scala.collection.mutable.Set.empty[(Int, Int)]
      val outstanding = Array.fill[Option[(Int, Int)]](2)(None)
      var cycle = 0
      while(groups.size < 12 || outstanding.exists(_.nonEmpty)) {
        assert(cycle < 500, s"storage grid stalled after ${groups.size} workgroups")
        for(cu <- 0 until 2) {
          d.io.cu(cu).ready.poke((outstanding(cu).isEmpty && cycle % 5 != 0).B)
          d.io.done(cu).valid.poke((outstanding(cu).nonEmpty && cycle % 7 == cu).B)
          if(d.io.cu(cu).valid.peek().litToBoolean) {
            val id = (d.io.cu(cu).bits.kernelPc.peek().litValue / 0x1000).toInt
            val (enabled, base, limit) = publications(id)
            val task = d.io.storage(cu)
            task.enabled.expect(enabled.B); task.table.version.expect(1.U)
            task.table.base.expect(base.U); task.table.limit.expect(limit.U)
            task.table.identity.context.expect(id.U)
            task.table.identity.pass.expect((id + 10).U)
            task.table.identity.generation.expect((id + 20).U)
            if(d.io.cu(cu).ready.peek().litToBoolean) {
              val group = d.io.group(cu).groupId(0).peek().litValue.toInt
              d.io.group(cu).singleGroup.expect(true.B)
              assert(!groups((id, group)))
              groups += ((id, group)); outstanding(cu) = Some((id, group))
            }
          }
          if(d.io.done(cu).valid.peek().litToBoolean && d.io.done(cu).ready.peek().litToBoolean)
            outstanding(cu) = None
        }
        d.io.lease.expect(true.B)
        d.clock.step(); cycle += 1
      }
      d.io.done.foreach(_.valid.poke(false.B))
      assert(groups == (for(id <- 1 to 3; group <- 0 until 4) yield (id, group)).toSet)
      var wait = 0
      while(!d.io.completion.valid.peek().litToBoolean && wait < 40) { d.clock.step(); wait += 1 }
      d.io.completion.valid.expect(true.B)
      def completion = (d.io.completion.bits.commandId.peek().litValue,
        d.io.completion.bits.status.peek().litValue, d.io.completion.bits.success.peek().litToBoolean)
      val held = completion
      d.clock.step(9)
      assert(completion == held)
      d.io.lease.expect(true.B)
      val heldId = d.io.completion.bits.commandId.peek().litValue
      d.io.command.bits.commandId.poke(heldId.U); d.io.command.valid.poke(true.B)
      d.io.command.ready.expect(false.B); d.io.duplicate.expect(true.B)
      d.clock.step(); d.io.command.valid.poke(false.B)
      d.io.completion.ready.poke(true.B)
      val completed = scala.collection.mutable.Set.empty[Int]
      var retiredCycles = 0
      while(completed.size < 3 && retiredCycles < 40) {
        if(d.io.completion.valid.peek().litToBoolean) {
          d.io.completion.bits.success.expect(true.B)
          val id = d.io.completion.bits.commandId.peek().litValue.toInt
          assert(!completed(id)); completed += id
        }
        d.clock.step(); retiredCycles += 1
      }
      assert(completed == Set(1, 2, 3))
      d.io.lease.expect(false.B)
      offer(heldId.toInt)
      d.io.lease.expect(publications(heldId.toInt)._1.B)
      d.io.pathReset.poke(true.B); d.clock.step(); d.io.pathReset.poke(false.B)
      d.io.lease.expect(false.B)
    }
  }
}
