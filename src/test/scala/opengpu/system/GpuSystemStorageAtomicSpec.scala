package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuSystemStorageAtomicSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "Opt-in system storage atomics"
  for ((translated, allOps) <- Seq((false, false), (true, false), (false, true), (true, true))) {
  it should s"execute storage atomic workgroups on two CUs through shared L2 translated=$translated allOps=$allOps" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2, commandIdWidth = 4, transactionsPerCu = 16,
        enableUnifiedCommands = true, storageAvailable = true, storageAtomicAvailable = true,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.graphicsDrained.poke(true.B); d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.io.trap.foreach(_.ready.poke(true.B))
      val memory = mutable.Map.empty[BigInt, Int]
      def put(address: BigInt, value: BigInt): Unit = for (i <- 0 until 4) memory(address + i) = ((value >> (8 * i)) & 255).toInt
      def get(address: BigInt, count: Int = 4): BigInt = (0 until count).map(i => BigInt(memory.getOrElse(address + i, 0xa5)) << (8 * i)).sum
      def storage(fn:Int, rd:Int=0, vs1:Int=0, vs2:Int=0, op:Int=0):BigInt =
        (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|
          (BigInt(op)<<12)|(BigInt(rd)<<7)|0x2b
      def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
      // Signed/unsigned extrema deliberately disagree for these high-bit inputs.
      // The two compare-swap entries check both replacement and no replacement.
      val operations = Vector[(String, Int, BigInt, BigInt)](
        ("swap", 0, 10, 7), ("add-wrap", 1, BigInt("fffffffe", 16), 5),
        ("xor", 2, BigInt("f0f0f0f0", 16), BigInt("f0f0f0f7", 16)),
        ("or", 3, 0x80, 0x87), ("and", 4, BigInt("80000006", 16), 6),
        ("signed-min", 5, BigInt("ffffffff", 16), BigInt("ffffffff", 16)),
        ("signed-max", 6, BigInt("80000000", 16), 7),
        ("unsigned-min", 7, BigInt("ffffffff", 16), 7),
        ("unsigned-max", 8, BigInt("80000000", 16), BigInt("80000000", 16)),
        ("compare-match", 9, 10, 7), ("compare-miss", 9, 11, 11))
      val program = if (allOps) {
        val setup = Vector(BigInt("c100f057",16), vector(23,3,2,0), vector(23,3,3,7),
          vector(23,3,4,10), storage(24,vs1=3,vs2=4))
        setup ++ operations.zipWithIndex.flatMap { case ((_, op, _, _), i) =>
          val offset = 64 + i * 4
          Vector((BigInt(offset) << 20) | (BigInt(3) << 7) | 0x13,
            vector(23,4,5,3), storage(if (op < 8) 25 else 26, rd=10+i,vs1=2,vs2=5,op=op%8))
        } ++ Vector(BigInt("30500073",16))
      } else Vector(BigInt("0104f057",16), vector(23,3,2,0), vector(23,3,3,3),
        BigInt("04800193",16), vector(23,4,4,3), storage(24,vs1=3,vs2=2), storage(25,rd=10,vs1=2,vs2=4,op=1),
        BigInt("30500073",16))
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + i * 4, v) }
      val raceProgram = Vector(BigInt("0104f057",16), vector(23,3,2,0), vector(23,3,3,7),
        vector(23,3,4,10), BigInt("08800193",16), vector(23,4,5,3),
        storage(24,vs1=3,vs2=4), storage(26,rd=10,vs1=2,vs2=5,op=1), BigInt("30500073",16))
      raceProgram.zipWithIndex.foreach { case (v, i) => put(0x2000 + i * 4, v) }
      val descriptor = Array.fill[BigInt](16)(0)
      for ((i,v) <- Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,
        3->7,4->11,5->13,6->0,7->1,8->1,9->1,10->0x9000,11->0xa000,
        13->0x9000,14->0xa000)) descriptor(i)=v
      val descriptorPhysical = if (translated) 0xa000 else 0x8000
      val atomicLine = if (translated) 0xb040 else 0x9040
      val raceLine = atomicLine + 64
      descriptor.zipWithIndex.foreach { case (v, i) => put(descriptorPhysical + i * 4, v) }
      if (translated) {
        put(0x30000, (BigInt(0x31) << 10) | 1)
        put(0x31020, (BigInt(0xa) << 10) | 0x243)
        put(0x31024, (BigInt(0xb) << 10) | 0x2c7)
        d.io.vectorSatp.poke(BigInt("80000030", 16).U)
      }
      for (i <- 0 until 64) memory(atomicLine + i) = 0x5a
      if (allOps) operations.zipWithIndex.foreach { case ((_, _, initial, _), i) => put(atomicLine + i * 4, initial) }
      else put(atomicLine + 8, 10)
      val original = Vector.tabulate(64)(i => memory(BigInt(atomicLine + i)))
      for (i <- 0 until 64) memory(raceLine + i) = 0x3c
      put(raceLine + 8, 10)
      val raceOriginal = Vector.tabulate(64)(i => memory(BigInt(raceLine + i)))
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, due: Int)
      val pending = mutable.Queue.empty[Reply]
      var cycle = 0; var writes = 0; var walkReads = 0; var simultaneous = false
      var expectFault = false; var traps = 0
      var expectRace = false
      val oldValues = mutable.Map.empty[Int, BigInt]
      val raceOldValues = mutable.Map.empty[Int, Vector[BigInt]]
      def tick(): Unit = {
        simultaneous ||= d.io.busyComputeUnits.peek().litValue == 3
        if (allOps || expectRace) for (cu <- 0 until 2) {
          val wb = d.io.committedVectorWriteback(cu)
          if (wb.valid.peek().litToBoolean) {
            val rd = wb.bits.vd.peek().litValue.toInt
            if (allOps && rd >= 10 && rd < 10 + operations.size) {
              assert(!oldValues.contains(rd), s"duplicate atomic result in v$rd")
              oldValues(rd) = wb.bits.data(0).peek().litValue
            } else if (expectRace && rd == 10) {
              assert(!raceOldValues.contains(cu), s"duplicate compare-swap result from CU $cu")
              raceOldValues(cu) = Vector.tabulate(4)(lane => wb.bits.data(lane).peek().litValue)
            }
          }
        }
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
          if (write) { assert(address == (if (expectRace) raceLine else atomicLine)); writes += 1 }
          else if (address == 0x30000 || address == 0x31000) walkReads += 1
          pending.enqueue(Reply(id, address, if (write) q.writeData.peek().litValue else get(address, 64),
            q.byteMask.peek().litValue, write, cycle + (if (write) 31 else 3)))
        }
        d.io.trap.foreach { trap =>
          if (trap.valid.peek().litToBoolean) {
            assert(expectFault); trap.bits.pc.expect(0x1018.U); trap.bits.cause.expect(15.U)
            trap.bits.tval.expect(0x9048.U); traps += 1
          }
        }
        d.clock.step(); cycle += 1
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 30000) { tick(); n += 1 }; assert(p, s"$label stalled at $cycle")
      }
      val command = d.io.gpuCommand.bits; zero(command); command.commandId.poke(1.U); command.opcode.poke(GpuCommandOpcode.kernel)
      command.launch.kernelPc.poke(0x1000.U)
      for (axis <- 0 until 3) { command.launch.gridSize(axis).poke((if (!allOps && axis == 0) 2 else 1).U)
        command.launch.localSize(axis).poke((if (!allOps && axis == 0) 4 else 1).U) }
      val task = command.storage.get; task.enabled.poke(true.B)
      task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8100.U)
      task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
      d.io.gpuCommand.valid.poke(true.B); until("command admission") { d.io.gpuCommand.ready.peek().litToBoolean }
      tick(); d.io.gpuCommand.valid.poke(false.B); zero(command)
      until("held completion") { d.io.gpuCompletion.valid.peek().litToBoolean }
      d.io.gpuCompletion.bits.success.expect(true.B); d.io.gpuCompletion.bits.commandId.expect(1.U)
      val held = d.io.gpuCompletion.bits.peek().litValue
      for (_ <- 0 until 6) { tick(); assert(d.io.gpuCompletion.bits.peek().litValue == held); d.io.storageLease.get.expect(true.B) }
      d.io.gpuCompletion.ready.poke(true.B); tick(); d.io.gpuCompletion.ready.poke(false.B)
      until("memory drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
      d.io.storageLease.get.expect(false.B)
      assert(if (translated) walkReads > 0 else walkReads == 0)
      if (allOps) {
        assert(writes > 0 && oldValues.size == operations.size)
        operations.zipWithIndex.foreach { case ((name, _, initial, expected), i) =>
          assert(oldValues(10+i) == initial, s"$name returned ${oldValues(10+i)}, expected $initial")
          assert(get(atomicLine + i*4) == expected, s"$name stored ${get(atomicLine + i*4)}, expected $expected")
        }
        for (i <- operations.size * 4 until 64) assert(memory(BigInt(atomicLine + i)) == original(i))
      } else {
        assert(simultaneous && writes == 8 && get(atomicLine + 8) == 34)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory(BigInt(atomicLine + i)) == original(i))
      }
      if (translated && !allOps) {
        // Use distinct, freshly published page tables at a quiescent boundary;
        // do not assume that changing lower memory invalidates cached PTEs.
        put(0x40000, (BigInt(0x41) << 10) | 1)
        put(0x41020, (BigInt(0xa) << 10) | 0x243)
        put(0x41024, (BigInt(0xb) << 10) | 0x243)
        put(0x50000, (BigInt(0x51) << 10) | 1)
        put(0x51020, (BigInt(0xa) << 10) | 0x243)
        put(0x51024, (BigInt(0xb) << 10) | 0x2c7)
        def submitOne(id: Int, root: BigInt): Unit = {
          d.io.vmContextIdle.expect(true.B); d.io.vectorSatp.poke(root.U)
          val q = d.io.gpuCommand.bits; zero(q); q.commandId.poke(id.U); q.opcode.poke(GpuCommandOpcode.kernel)
          q.launch.kernelPc.poke(0x1000.U)
          for (axis <- 0 until 3) { q.launch.gridSize(axis).poke(1.U); q.launch.localSize(axis).poke(1.U) }
          val t = q.storage.get; t.enabled.poke(true.B)
          t.table.version.poke(1.U); t.table.base.poke(0x8000.U); t.table.limit.poke(0x8100.U)
          t.table.identity.context.poke(7.U); t.table.identity.pass.poke(11.U); t.table.identity.generation.poke(13.U)
          d.io.gpuCommand.valid.poke(true.B); until("follow-up admission") { d.io.gpuCommand.ready.peek().litToBoolean }
          tick(); d.io.gpuCommand.valid.poke(false.B); zero(q)
        }
        expectFault = true; submitOne(2, BigInt("80000040", 16))
        until("denied completion") { d.io.gpuCompletion.valid.peek().litToBoolean }
        d.io.gpuCompletion.bits.commandId.expect(2.U); d.io.gpuCompletion.bits.success.expect(false.B)
        assert(traps == 1 && writes == 8 && get(atomicLine + 8) == 34)
        d.io.storageLease.get.expect(true.B); d.io.gpuCompletion.ready.poke(true.B); tick(); d.io.gpuCompletion.ready.poke(false.B)
        until("denied drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
        expectFault = false; submitOne(3, BigInt("80000050", 16))
        until("recovery completion") { d.io.gpuCompletion.valid.peek().litToBoolean }
        d.io.gpuCompletion.bits.commandId.expect(3.U); d.io.gpuCompletion.bits.success.expect(true.B)
        d.io.storageLease.get.expect(true.B); d.io.gpuCompletion.ready.poke(true.B); tick(); d.io.gpuCompletion.ready.poke(false.B)
        until("recovery drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
        d.io.storageLease.get.expect(false.B); assert(traps == 1 && writes == 9 && get(atomicLine + 8) == 37)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory(BigInt(atomicLine + i)) == original(i))
      }
      if (!allOps) {
        // Eight lanes race to replace the same word. Exactly one must see 10;
        // every later lane sees 7, regardless of cross-CU arbitration order.
        val writesBeforeRace = writes
        expectRace = true; simultaneous = false
        val q = d.io.gpuCommand.bits; zero(q); q.commandId.poke(4.U); q.opcode.poke(GpuCommandOpcode.kernel)
        q.launch.kernelPc.poke(0x2000.U)
        for (axis <- 0 until 3) {
          q.launch.gridSize(axis).poke((if (axis == 0) 2 else 1).U)
          q.launch.localSize(axis).poke((if (axis == 0) 4 else 1).U)
        }
        val t = q.storage.get; t.enabled.poke(true.B)
        t.table.version.poke(1.U); t.table.base.poke(0x8000.U); t.table.limit.poke(0x8100.U)
        t.table.identity.context.poke(7.U); t.table.identity.pass.poke(11.U); t.table.identity.generation.poke(13.U)
        d.io.gpuCommand.valid.poke(true.B); until("compare-swap admission") { d.io.gpuCommand.ready.peek().litToBoolean }
        tick(); d.io.gpuCommand.valid.poke(false.B); zero(q)
        until("compare-swap completion") { d.io.gpuCompletion.valid.peek().litToBoolean }
        d.io.gpuCompletion.bits.commandId.expect(4.U); d.io.gpuCompletion.bits.success.expect(true.B)
        d.io.storageLease.get.expect(true.B); d.io.gpuCompletion.ready.poke(true.B); tick(); d.io.gpuCompletion.ready.poke(false.B)
        until("compare-swap drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
        d.io.storageLease.get.expect(false.B)
        assert(simultaneous && writes > writesBeforeRace && raceOldValues.keySet == Set(0, 1))
        assert(raceOldValues.values.flatten.toVector.sorted == (Vector.fill(7)(BigInt(7)) :+ BigInt(10)))
        assert(get(raceLine + 8) == 7)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory(BigInt(raceLine + i)) == raceOriginal(i))
      }
    }
  }
  }

  for (translated <- Seq(false, true)) {
    it should s"serialize image and storage atomics on one shared word translated=$translated" in {
      val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuSystem(config, numComputeUnits = 2, commandIdWidth = 4, transactionsPerCu = 16,
          enableUnifiedCommands = true, imageAvailable = true, imageAtomicAvailable = true,
          storageAvailable = true, storageAtomicAvailable = true,
          instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        d.io.graphicsDrained.poke(true.B); d.io.graphicsShaderL1Invalidate.ready.poke(true.B)
        d.io.gpuCompletion.ready.poke(true.B); d.io.trap.foreach(_.ready.poke(true.B))
        val memory = mutable.Map.empty[BigInt, Int]
        def put(address: BigInt, value: BigInt): Unit =
          for (i <- 0 until 4) memory(address + i) = ((value >> (8 * i)) & 255).toInt
        def get(address: BigInt, count: Int = 4): BigInt =
          (0 until count).map(i => BigInt(memory.getOrElse(address + i, 0xa5)) << (8 * i)).sum
        def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
          (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
            (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
        def custom(fn: Int, form: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
          (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
            (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x2b

        val format = TextureImageFormat.id("R32UI"); val control = (format << 6) | 40
        val imageProgram = Vector(BigInt("0104f057",16), vector(23,3,2,0),
          BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
          BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
          vector(23,4,3,10), custom(8,vs1=2,vs2=3), custom(8,form=1,vs1=2,vs2=2),
          vector(23,3,4,3), custom(8,form=2,vs1=4,vs2=2),
          custom(23,form=1,rd=10,vs1=2,vs2=2), BigInt("30500073",16))
        val storageProgram = Vector(BigInt("0104f057",16), vector(23,3,2,0), vector(23,3,3,3),
          BigInt("04800193",16), vector(23,4,4,3), custom(24,vs1=3,vs2=2),
          custom(25,form=1,rd=10,vs1=2,vs2=4), BigInt("30500073",16))
        imageProgram.zipWithIndex.foreach { case (word, i) => put(0x1000 + i * 4, word) }
        storageProgram.zipWithIndex.foreach { case (word, i) => put(0x2000 + i * 4, word) }

        val imageDescriptor = Array.fill[BigInt](64)(0)
        for ((i, value) <- Seq[(Int, BigInt)](0 -> BigInt("47495501",16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
          12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x9000, 23 -> 0xa000,
          25 -> 0x9048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) imageDescriptor(i) = value
        val storageDescriptor = Array.fill[BigInt](16)(0)
        for ((i, value) <- Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1, 10 -> 0x9000, 11 -> 0xa000,
          13 -> 0x9000, 14 -> 0xa000)) storageDescriptor(i) = value
        val descriptorPhysical = if (translated) 0xa000 else 0x8000
        val atomicLine = if (translated) 0xb040 else 0x9040
        imageDescriptor.zipWithIndex.foreach { case (word, i) => put(descriptorPhysical + i * 4, word) }
        storageDescriptor.zipWithIndex.foreach { case (word, i) => put(descriptorPhysical + 0x400 + i * 4, word) }
        if (translated) {
          put(0x30000, (BigInt(0x31) << 10) | 1)
          put(0x31020, (BigInt(0xa) << 10) | 0x243)
          put(0x31024, (BigInt(0xb) << 10) | 0x2c7)
          d.io.vectorSatp.poke(BigInt("80000030",16).U)
        }
        for (i <- 0 until 64) memory(atomicLine + i) = 0x5a
        put(atomicLine + 8, 10)
        val original = Vector.tabulate(64)(i => memory(BigInt(atomicLine + i)))

        case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, due: Int)
        val pending = mutable.Queue.empty[Reply]
        val oldValues = mutable.Map.empty[Int, Vector[BigInt]]
        val completed = mutable.Set.empty[Int]
        var cycle = 0; var writes = 0; var walkReads = 0
        var simultaneous = false; var bothLeases = false
        def tick(): Unit = {
          simultaneous ||= d.io.busyComputeUnits.peek().litValue == 3
          bothLeases ||= d.io.imageLease.get.peek().litToBoolean && d.io.storageLease.get.peek().litToBoolean
          for (cu <- 0 until 2) {
            val wb = d.io.committedVectorWriteback(cu)
            if (wb.valid.peek().litToBoolean && wb.bits.vd.peek().litValue == 10) {
              assert(!oldValues.contains(cu), s"duplicate atomic result from CU $cu")
              oldValues(cu) = Vector.tabulate(4)(lane => wb.bits.data(lane).peek().litValue)
            }
          }
          d.io.memoryRequest.ready.poke((cycle % 4 != 0).B)
          val reply = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) }
          if (reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
            val r = pending.dequeue()
            if (r.write) for (i <- 0 until 64 if r.mask.testBit(i)) memory(r.address + i) = ((r.data >> (8 * i)) & 255).toInt
          }
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue; val write = q.isWrite.peek().litToBoolean
            val id = q.transactionId.peek().litValue; assert(!pending.exists(_.id == id))
            if (write) { assert(address == atomicLine); writes += 1 }
            else if (address == 0x30000 || address == 0x31000) walkReads += 1
            pending.enqueue(Reply(id, address, if (write) q.writeData.peek().litValue else get(address, 64),
              q.byteMask.peek().litValue, write, cycle + (if (write) 31 else 3)))
          }
          if (d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean) {
            val id = d.io.gpuCompletion.bits.commandId.peek().litValue.toInt
            d.io.gpuCompletion.bits.success.expect(true.B); assert(!completed(id)); completed += id
          }
          d.io.trap.foreach(_.valid.expect(false.B))
          d.clock.step(); cycle += 1
        }
        def until(label: String)(p: => Boolean): Unit = {
          var n = 0; while (!p && n < 60000) { tick(); n += 1 }; assert(p, s"$label stalled at $cycle")
        }
        for ((id, image) <- Seq((1, true), (2, false))) {
          val q = d.io.gpuCommand.bits; zero(q); q.commandId.poke(id.U); q.opcode.poke(GpuCommandOpcode.kernel)
          q.launch.kernelPc.poke((if (image) 0x1000 else 0x2000).U)
          for (axis <- 0 until 3) {
            q.launch.gridSize(axis).poke(1.U); q.launch.localSize(axis).poke((if (axis == 0) 4 else 1).U)
          }
          if (image) {
            val t = q.images.get; t.enabled.poke(true.B); t.stage.poke(GraphicsStage.compute.U)
            t.table.version.poke(1.U); t.table.base.poke(0x8000.U); t.table.limit.poke(0x8400.U)
            t.table.identity.context.poke(7.U); t.table.identity.pass.poke(11.U); t.table.identity.generation.poke(13.U)
          } else {
            val t = q.storage.get; t.enabled.poke(true.B)
            t.table.version.poke(1.U); t.table.base.poke(0x8400.U); t.table.limit.poke(0x8500.U)
            t.table.identity.context.poke(7.U); t.table.identity.pass.poke(11.U); t.table.identity.generation.poke(13.U)
          }
          d.io.gpuCommand.valid.poke(true.B); until("mixed command admission") { d.io.gpuCommand.ready.peek().litToBoolean }
          tick(); d.io.gpuCommand.valid.poke(false.B); zero(q)
        }
        until("mixed command completion") { completed.size == 2 }
        until("mixed memory drain") { pending.isEmpty && d.io.vmContextIdle.peek().litToBoolean }
        d.io.imageLease.get.expect(false.B); d.io.storageLease.get.expect(false.B)
        assert(completed == Set(1, 2) && simultaneous && bothLeases && oldValues.keySet == Set(0, 1))
        assert(oldValues.values.flatten.toVector.sorted == Vector.tabulate(8)(i => BigInt(10 + 3*i)))
        assert(writes == 8 && get(atomicLine + 8) == 34)
        assert(if (translated) walkReads > 0 else walkReads == 0)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory(BigInt(atomicLine + i)) == original(i))
      }
    }
  }
}
