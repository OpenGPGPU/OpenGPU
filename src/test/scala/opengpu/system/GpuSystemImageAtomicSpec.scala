package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemImageAtomicSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "Opt-in system image atomics"
  it should "execute image atomic workgroups on two CUs through shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2, commandIdWidth = 4, transactionsPerCu = 8,
        enableUnifiedCommands = true, imageAvailable = true, imageAtomicAvailable = true,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B); d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      val memory = mutable.Map.empty[BigInt, Int]
      def put(address: BigInt, value: BigInt): Unit = for (i <- 0 until 4) memory(address + i) = ((value >> (8 * i)) & 255).toInt
      def get(address: BigInt, count: Int = 4): BigInt = (0 until count).map(i => BigInt(memory.getOrElse(address + i, 0xa5)) << (8 * i)).sum
      val fmt = TextureImageFormat.id("R32UI"); val control = (fmt << 6) | 40
      def image(fn: Int, pair: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
      def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
      val program = Vector(BigInt("0104f057", 16), vector(23, 3, 2, 0),
        BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
        BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
        vector(23, 4, 3, 10), image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2),
        vector(23, 3, 4, 3), image(8, pair = 2, vs1 = 4, vs2 = 2),
        image(23, pair = 1, rd = 10, vs1 = 2, vs2 = 2), BigInt("30500073", 16))
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + i * 4, v) }
      val descriptor = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x9000, 23 -> 0xa000,
        25 -> 0x9048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) descriptor(i) = v
      descriptor.zipWithIndex.foreach { case (v, i) => put(0x8000 + i * 4, v) }
      for (i <- 0 until 64) memory(0x9040 + i) = 0x5a
      put(0x9048, 10); val original = Vector.tabulate(64)(i => memory(BigInt(0x9040 + i)))
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, due: Int)
      val pending = mutable.Queue.empty[Reply]
      var cycle = 0; var writes = 0; var simultaneous = false
      def tick(): Unit = {
        simultaneous ||= d.io.busyComputeUnits.peek().litValue == 3
        d.io.memoryRequest.ready.poke((cycle % 4 != 0).B)
        val reply = pending.headOption.filter(_.due <= cycle); d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r => { d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) })
        if (reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
          val r = pending.dequeue()
          if (r.write) for (i <- 0 until 64 if r.mask.testBit(i)) memory(r.address + i) = ((r.data >> (8 * i)) & 255).toInt
        }
        if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue; val write = q.isWrite.peek().litToBoolean
          val id = q.transactionId.peek().litValue; assert(!pending.exists(_.id == id))
          if (write) { assert(address == 0x9040); writes += 1 }
          pending.enqueue(Reply(id, address, if (write) q.writeData.peek().litValue else get(address, 64),
            q.byteMask.peek().litValue, write, cycle + (if (write) 31 else 3)))
        }
        d.io.trap.foreach(_.valid.expect(false.B)); d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 30000) { tick(); n += 1 }; assert(p, s"$label stalled at $cycle")
      }
      val command = d.io.gpuCommand.bits; zero(command); command.commandId.poke(1.U); command.opcode.poke(GpuCommandOpcode.kernel)
      command.launch.kernelPc.poke(0x1000.U)
      for (axis <- 0 until 3) { command.launch.gridSize(axis).poke((if (axis == 0) 2 else 1).U)
        command.launch.localSize(axis).poke((if (axis == 0) 4 else 1).U) }
      val task = command.images.get; task.enabled.poke(true.B); task.stage.poke(GraphicsStage.compute.U)
      task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8400.U)
      task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
      d.io.gpuCommand.valid.poke(true.B); until("command admission") { d.io.gpuCommand.ready.peek().litToBoolean }
      tick(); d.io.gpuCommand.valid.poke(false.B); zero(command)
      until("held completion") { d.io.gpuCompletion.valid.peek().litToBoolean }
      d.io.gpuCompletion.bits.success.expect(true.B); d.io.gpuCompletion.bits.commandId.expect(1.U)
      val held = d.io.gpuCompletion.bits.peek().litValue
      for (_ <- 0 until 6) { tick(); assert(d.io.gpuCompletion.bits.peek().litValue == held); d.io.imageLease.get.expect(true.B) }
      d.io.gpuCompletion.ready.poke(true.B); tick(); d.io.gpuCompletion.ready.poke(false.B)
      until("memory drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
      d.io.imageLease.get.expect(false.B); assert(simultaneous && writes == 8 && get(0x9048) == 34)
      for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory(BigInt(0x9040 + i)) == original(i))
    }
  }
}
