package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.TextureImageFormat
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemImageSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def image(fn: Int, pair: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  it should "execute queued direct and indirect image kernels with independent publications and held completion leases" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2, commandIdWidth = 4,
      transactionsPerCu = 8, enableUnifiedCommands = true, enableIndirectCompute = true, imageAvailable = true,
      instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B)
      d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      d.io.memoryRequest.ready.poke(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, value: BigInt): Unit =
        for(i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
      def get(a: BigInt, bytes: Int = 4): BigInt =
        (0 until bytes).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
      val format = TextureImageFormat.id("RGBA32UI")
      val control = (format << 6) | 40
      for(id <- 1 to 2) {
        val descriptor = 0x6000 + id * 0x2000
        val data = descriptor + 0x1000
        val words = Array.fill[BigInt](64)(0)
        for((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
            3 -> 7, 4 -> 11, 5 -> 13, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
            12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1,
            22 -> data, 23 -> (data + 4096), 25 -> (data + 63),
            26 -> 4, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 16)) words(i) = v
        words.zipWithIndex.foreach { case(v, i) => put(descriptor + i * 4, v) }
        for(i <- 0 until 4096) memory(BigInt(data + i)) = 0xa5
        val program = Vector(BigInt("0104f057", 16), vector(23, 3, 2, 0),
          BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
          BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
          vector(23, 4, 3, 10), image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2)) ++
          (0 until 4).map(c => vector(0, 3, 4 + c, id * 4 + c, 1)) ++
          Vector(image(8, pair = 2, vs1 = 4, vs2 = 5), image(8, pair = 3, vs1 = 6, vs2 = 7),
            image(10, vs1 = 1, vs2 = 2), BigInt("30500073", 16))
        program.zipWithIndex.foreach { case(v, i) => put(id * 0x1000 + i * 4, v) }
      }
      // The second command obtains its real 3x1x1 grid from GPU memory.
      // Contradictory submitted dimensions below must never reach a CU.
      put(0x3000, 3); put(0x3004, 1); put(0x3008, 1)
      var indirectArgumentReads = 0
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt,
                       write: Boolean, due: Int)
      val pending = mutable.Queue.empty[Reply]
      var cycle = 0
      var writes = 0
      var simultaneous = false
      val completions = mutable.Set.empty[Int]
      def tick(): Unit = {
        simultaneous ||= d.io.busyComputeUnits.peek().litValue == 3
        d.io.memoryRequest.ready.poke((cycle % 5 != 0).B)
        val reply = pending.headOption.filter(_.due <= cycle)
        d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach { r =>
          d.io.memoryResponse.bits.transactionId.poke(r.id.U)
          d.io.memoryResponse.bits.readData.poke(r.data.U)
        }
        if(reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
          val r = pending.dequeue()
          if(r.write) for(i <- 0 until 64 if r.mask.testBit(i))
            memory(r.address + i) = ((r.data >> (8 * i)) & 255).toInt
        }
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val q = d.io.memoryRequest.bits
          val a = q.address.peek().litValue; val write = q.isWrite.peek().litToBoolean
          if(!write && a == 0x3000) indirectArgumentReads += 1
          if(write) writes += 1
          pending.enqueue(Reply(q.transactionId.peek().litValue, a,
            if(write) q.writeData.peek().litValue else get(a, 64), q.byteMask.peek().litValue,
            write, cycle + (if(write) 19 else 3)))
        }
        if(d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean) {
          d.io.gpuCompletion.bits.success.expect(true.B)
          val id = d.io.gpuCompletion.bits.commandId.peek().litValue.toInt
          assert(!completions(id)); completions += id
        }
        d.io.trap.foreach(_.valid.expect(false.B))
        d.clock.step(); cycle += 1
      }
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0
        while(!condition && n < 30000) { tick(); n += 1 }
        assert(condition, s"$label stalled after $cycle cycles")
      }
      for(id <- 1 to 2) {
        val command = d.io.gpuCommand.bits
        zero(command); command.commandId.poke(id.U)
        command.opcode.poke(if(id == 1) GpuCommandOpcode.kernel else GpuCommandOpcode.kernelIndirect)
        if(id == 2) { command.sourceAddress.poke(0x3000.U); command.bytes.poke(12.U) }
        command.launch.kernelPc.poke((id * 0x1000).U)
        for(axis <- 0 until 3) {
          command.launch.gridSize(axis).poke((if(id == 2) 0xdead else if(axis == 0) 3 else 1).U)
          command.launch.localSize(axis).poke((if(axis == 0) 4 else 1).U)
        }
        val task = command.images.get; task.enabled.poke(true.B)
        task.table.version.poke(1.U); task.table.base.poke((0x6000 + id * 0x2000).U)
        task.table.limit.poke((0x6400 + id * 0x2000).U)
        task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U)
        task.table.identity.generation.poke(13.U)
        d.io.gpuCommand.valid.poke(true.B)
        until("command admission")(d.io.gpuCommand.ready.peek().litToBoolean)
        tick(); d.io.gpuCommand.valid.poke(false.B)
      }
      zero(d.io.gpuCommand.bits)
      d.io.imageLease.get.expect(true.B)
      until("first held public completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
      val held = d.io.gpuCompletion.bits.peek().litValue
      for(_ <- 0 until 13) {
        tick(); assert(d.io.gpuCompletion.bits.peek().litValue == held)
        d.io.imageLease.get.expect(true.B)
      }
      d.io.gpuCompletion.ready.poke(true.B)
      until("both image kernels retire")(completions.size == 2)
      until("lower-memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      assert(simultaneous && writes == 30 && indirectArgumentReads == 1,
        s"expected six real workgroups and one indirect argument read: writes=$writes arguments=$indirectArgumentReads")
      d.io.imageLease.get.expect(false.B)
      for(id <- 1 to 2) {
        val data = 0x7000 + id * 0x2000
        for(i <- 0 until 4096) {
          val offset = i - 63
          val expected = if(offset >= 0 && offset < 64) {
            val lane = offset / 16; val component = (offset % 16) / 4
            val byte = offset % 4
            ((BigInt(lane + id * 4 + component) >> (byte * 8)) & 255).toInt
          } else 0xa5
          assert(memory(BigInt(data + i)) == expected, s"image $id byte $i differs")
        }
      }
    }
  }
}
