package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SharedMemoryStorageComparison(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val out = Decoupled(new VectorMemoryResponse(config))
    val atomicIn = Flipped(Decoupled(new SharedAtomicRequest(config)))
    val atomicOut = Decoupled(new SharedAtomicResponse(config))
    val idle = Output(Bool())
    val resourcesPresent = Output(Bool())
  })
  val reference = Module(new BankedSharedMemory(config))
  val physical = Module(new BankedSharedMemory(config, useSramBlackBoxes = true))
  physical.io.in <> io.in; physical.io.out <> io.out
  physical.io.atomicIn <> io.atomicIn; physical.io.atomicOut <> io.atomicOut
  reference.io.in.valid := io.in.valid; reference.io.in.bits := io.in.bits
  reference.io.out.ready := io.out.ready
  reference.io.atomicIn.valid := io.atomicIn.valid; reference.io.atomicIn.bits := io.atomicIn.bits
  reference.io.atomicOut.ready := io.atomicOut.ready
  io.idle := physical.io.idle
  io.resourcesPresent := Module(new SharedByteBankResources).io.present
  assert(reference.io.in.ready === physical.io.in.ready)
  assert(reference.io.out.valid === physical.io.out.valid)
  assert(reference.io.atomicIn.ready === physical.io.atomicIn.ready)
  assert(reference.io.atomicOut.valid === physical.io.atomicOut.valid)
  assert(reference.io.idle === physical.io.idle)
  when(physical.io.out.valid) { assert(reference.io.out.bits.asUInt === physical.io.out.bits.asUInt) }
  when(physical.io.atomicOut.valid) {
    assert(reference.io.atomicOut.bits.warpId === physical.io.atomicOut.bits.warpId)
    assert(reference.io.atomicOut.bits.fault === physical.io.atomicOut.bits.fault)
    when(!physical.io.atomicOut.bits.fault) {
      assert(reference.io.atomicOut.bits.oldValue === physical.io.atomicOut.bits.oldValue)
    }
  }
}

class BankedSharedMemorySramSpec extends AnyFlatSpec {
  behavior of "SRAM-backed CU shared memory"

  it should "preserve full default capacity, cycle timing, byte conflicts and every AMO" in {
    val c = GpuConfig()
    simulate(new SharedMemoryStorageComparison(c)) { d =>
      val random = new scala.util.Random(0x534d454dL)
      val bytes = Array.fill(c.sharedMemoryBytes)(0)
      val fullMask = (BigInt(1) << c.lanes) - 1
      val wordMask = 0xffffffffL
      def word(a: Int): Long = (0 until 4).map(b => bytes(a + b).toLong << (8 * b)).reduce(_ | _)
      def putWord(a: Int, value: Long): Unit = (0 until 4).foreach(b => bytes(a + b) = ((value >>> (b * 8)) & 255).toInt)
      def until(condition: => Boolean): Unit = {
        var n = 0
        while(!condition && n < 300) { d.clock.step(); n += 1 }
        assert(condition, "shared memory did not make bounded progress")
      }
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.atomicIn.valid.poke(false.B); d.io.atomicIn.bits.poke(0.U.asTypeOf(d.io.atomicIn.bits))
      d.io.out.ready.poke(false.B); d.io.atomicOut.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.resourcesPresent.expect(true.B)

      def vector(offsets: Seq[Int], size: Int, store: Boolean, values: Seq[Long], mask: BigInt): Unit = {
        val count = 1 << size
        val faults = offsets.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (a, lane)) =>
          if(mask.testBit(lane) && (a < 0 || a.toLong + count > bytes.length)) bits.setBit(lane) else bits
        }
        val expected = offsets.zipWithIndex.map { case (a, lane) =>
          if(store || !mask.testBit(lane) || faults.testBit(lane)) 0L
          else (0 until count).map(b => bytes(a + b).toLong << (8 * b)).reduce(_ | _)
        }
        d.io.in.bits.warpId.poke(random.nextInt(c.warps).U)
        d.io.in.bits.elementSize.poke(size.U); d.io.in.bits.isStore.poke(store.B)
        d.io.in.bits.laneMask.poke(mask.U)
        for(lane <- 0 until c.lanes) {
          d.io.in.bits.addresses(lane).poke((c.sharedMemoryBase + offsets(lane)).U)
          d.io.in.bits.writeData(lane).poke(values(lane).U)
        }
        d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean)
        d.clock.step(); d.io.in.valid.poke(false.B)
        if(store) for(lane <- 0 until c.lanes if mask.testBit(lane) && !faults.testBit(lane);
          b <- 0 until count) bytes(offsets(lane) + b) = ((values(lane) >>> (8 * b)) & 255).toInt
        until(d.io.out.valid.peek().litToBoolean)
        def check(): Unit = {
          d.io.out.valid.expect(true.B); d.io.out.bits.faultMask.expect(faults.U)
          d.io.out.bits.pageFault.expect(false.B)
          for(lane <- 0 until c.lanes) d.io.out.bits.readData(lane).expect(expected(lane).U)
          d.io.atomicIn.ready.expect(false.B); d.io.idle.expect(false.B)
        }
        check(); d.clock.step(3); check()
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        d.io.idle.expect(true.B)
      }

      // Initialize every physical address through the architectural vector
      // interface, then require every byte to survive segment/bank selection.
      for(base <- 0 until bytes.length by c.lanes * 4) {
        val offsets = (0 until c.lanes).map(lane => base + lane * 4)
        val values = offsets.map(a => ((a.toLong * 0x010101L) ^ (a.toLong << 19) ^ 0xa5632b19L) & wordMask)
        vector(offsets, 2, true, values, fullMask)
      }
      for(base <- 0 until bytes.length by c.lanes * 4)
        vector((0 until c.lanes).map(lane => base + lane * 4), 2, false, Seq.fill(c.lanes)(0L), fullMask)
      for(t <- 0 until 128) {
        val size = random.nextInt(3)
        val common = random.nextInt(bytes.length - 4)
        val offsets = (0 until c.lanes).map(lane => if(t % 3 == 0) common else random.nextInt(bytes.length - 4))
        val values = Seq.fill(c.lanes)(random.nextLong() & wordMask)
        val mask = BigInt(c.lanes, random)
        vector(offsets, size, t % 2 == 0, values, mask)
        vector(offsets, size, false, values, mask)
      }
      vector((0 until c.lanes).map(lane => if(lane % 2 == 0) bytes.length - 2 else lane * 4),
        2, true, Seq.fill(c.lanes)(0x29b7e346L), fullMask)

      val targets = Seq(0, 4092, 4096, bytes.length - 4)
      for(a <- targets; operation <- 0 to 8) {
        val old = word(a)
        val operand = Seq(0xffffffffL, 0x80000000L, 17L, 0x7fffffffL)(operation % 4)
        val value = operation match {
          case 0 => operand
          case 1 => (old + operand) & wordMask
          case 2 => old ^ operand
          case 3 => old | operand
          case 4 => old & operand
          case 5 => if(old.toInt < operand.toInt) old else operand
          case 6 => if(old.toInt > operand.toInt) old else operand
          case 7 => math.min(old, operand)
          case 8 => math.max(old, operand)
        }
        val warp = random.nextInt(c.warps)
        d.io.atomicIn.bits.warpId.poke(warp.U)
        d.io.atomicIn.bits.address.poke((c.sharedMemoryBase + a).U)
        d.io.atomicIn.bits.operand.poke(operand.U); d.io.atomicIn.bits.operation.poke(operation.U)
        d.io.atomicIn.valid.poke(true.B); until(d.io.atomicIn.ready.peek().litToBoolean)
        d.clock.step(); d.io.atomicIn.valid.poke(false.B)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(false.B); d.io.in.valid.poke(false.B)
        until(d.io.atomicOut.valid.peek().litToBoolean)
        def checkAtomic(): Unit = {
          d.io.atomicOut.valid.expect(true.B); d.io.atomicOut.bits.warpId.expect(warp.U)
          d.io.atomicOut.bits.oldValue.expect(old.U); d.io.atomicOut.bits.fault.expect(false.B)
          d.io.in.ready.expect(false.B)
        }
        checkAtomic(); d.clock.step(3); checkAtomic()
        putWord(a, value)
        d.io.atomicOut.ready.poke(true.B); d.clock.step(); d.io.atomicOut.ready.poke(false.B)
        vector(Seq.fill(c.lanes)(a), 2, false, Seq.fill(c.lanes)(0L), fullMask)
      }
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for(a <- targets) vector(Seq.fill(c.lanes)(a), 2, false, Seq.fill(c.lanes)(0L), fullMask)
    }
  }
}
