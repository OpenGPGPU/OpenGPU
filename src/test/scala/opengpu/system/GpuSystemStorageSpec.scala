package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemStorageSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def storage(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(rd) << 7) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  it should "execute four storage bindings in queued direct and indirect kernels with held completion leases" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2, commandIdWidth = 4,
      transactionsPerCu = 16, enableUnifiedCommands = true, enableIndirectCompute = true, storageAvailable = true,
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
      for(id <- 1 to 2) {
        val descriptor = 0x6000 + id * 0x2000
        for (unit <- 0 until 4) {
          val data = 0x20000 + id * 0x10000 + unit * 0x1000
          val words = Array.fill[BigInt](16)(0)
          for((i,v) <- Seq[(Int,BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
              3 -> 7, 4 -> 11, 5 -> 13, 6 -> unit, 7 -> 1, 8 -> 1, 9 -> 1,
              10 -> data, 11 -> (data + 4096), 13 -> data, 14 -> (data + 4096))) words(i) = v
          words.zipWithIndex.foreach{case(v,i)=>put(descriptor + unit * 64 + i * 4,v)}
          for(i <- 0 until 4096) memory(BigInt(data + i)) = 0xa5
        }
        val program = Vector(BigInt("0104f057",16),vector(23,3,2,0)) ++ (0 until 4).flatMap { unit =>
          Vector(vector(23,3,3,unit), vector(23,3,4,id*2+unit), storage(14,vs1=4),
            storage(16,vs1=3,vs2=2), storage(15,rd=10+unit,vs1=3,vs2=2))
        } ++ Vector(BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(id*0x1000+i*4,v)}
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
      val loaded = mutable.Map.empty[(Int,Int),Int].withDefaultValue(0)
      val completions = mutable.Set.empty[Int]
      def tick(): Unit = {
        simultaneous ||= d.io.busyComputeUnits.peek().litValue == 3
        for(cu <- 0 until 2) {
          val wb = d.io.committedVectorWriteback(cu)
          if(wb.valid.peek().litToBoolean) {
            val rd = wb.bits.vd.peek().litValue.toInt
            if(rd >= 10 && rd < 14) {
              val unit = rd - 10; val value = wb.bits.data(0).peek().litValue.toInt
              assert(value == 2 + unit || value == 4 + unit)
              for(lane <- 0 until 4) wb.bits.data(lane).expect(value.U)
              loaded((unit,value)) += 1
            }
          }
        }
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
        while(!condition && n < 60000) { tick(); n += 1 }
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
        val task = command.storage.get; task.enabled.poke(true.B)
        task.table.version.poke(1.U); task.table.base.poke((0x6000 + id * 0x2000).U)
        task.table.limit.poke((0x6400 + id * 0x2000).U)
        task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U)
        task.table.identity.generation.poke(13.U)
        d.io.gpuCommand.valid.poke(true.B)
        until("command admission")(d.io.gpuCommand.ready.peek().litToBoolean)
        tick(); d.io.gpuCommand.valid.poke(false.B)
      }
      zero(d.io.gpuCommand.bits)
      d.io.storageLease.get.expect(true.B)
      until("first held public completion")(d.io.gpuCompletion.valid.peek().litToBoolean)
      val held = d.io.gpuCompletion.bits.peek().litValue
      for(_ <- 0 until 13) {
        tick(); assert(d.io.gpuCompletion.bits.peek().litValue == held)
        d.io.storageLease.get.expect(true.B)
      }
      d.io.gpuCompletion.ready.poke(true.B)
      until("both storage kernels retire")(completions.size == 2)
      until("lower-memory drain")(pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean)
      val expectedLoads = (for(unit <- 0 until 4; id <- 1 to 2) yield (unit,id*2+unit) -> 3).toMap
      assert(loaded.toMap == expectedLoads, s"unexpected storage load results: $loaded")
      assert(simultaneous && writes == 96 && indirectArgumentReads == 1,
        s"expected six real workgroups and one indirect argument read: writes=$writes arguments=$indirectArgumentReads")
      d.io.storageLease.get.expect(false.B)
      for(id <- 1 to 2; unit <- 0 until 4) {
        val data = 0x20000 + id * 0x10000 + unit * 0x1000
        for(i <- 0 until 4096) {
          val expected = if(i < 4) ((BigInt(id * 2 + unit) >> (i * 8)) & 255).toInt else 0xa5
          assert(memory(BigInt(data + i)) == expected, s"storage $id unit $unit byte $i differs")
        }
      }
    }
  }
}
