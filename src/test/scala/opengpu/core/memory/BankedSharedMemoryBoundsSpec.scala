package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class BankedSharedMemoryBoundsSpec extends AnyFlatSpec {
  behavior of "CU shared-memory allocation bounds"

  private def check(base: Long, scenario: String): Unit = {
    val c = GpuConfig(lanes = 2, warps = 2, sharedMemoryBytes = 256,
      sharedMemoryBanks = 4, sharedMemoryBase = base)
    simulate(new SharedMemoryStorageComparison(c)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.atomicIn.valid.poke(false.B); d.io.atomicIn.bits.poke(0.U.asTypeOf(d.io.atomicIn.bits))
      d.io.out.ready.poke(false.B); d.io.atomicOut.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.resourcesPresent.expect(true.B)
      def until(condition: => Boolean): Unit = {
        var n = 0
        while(!condition && n < 50) { d.clock.step(); n += 1 }
        assert(condition, "bounded shared-memory access did not finish")
      }
      def vector(store: Boolean, addresses: Seq[Long], values: Seq[Long], mask: Int,
        faults: Int, expected: Seq[Long]): Unit = {
        d.io.in.bits.isStore.poke(store.B); d.io.in.bits.elementSize.poke(2.U)
        d.io.in.bits.laneMask.poke(mask.U); d.io.in.bits.warpId.poke(1.U)
        for(lane <- 0 until 2) {
          d.io.in.bits.addresses(lane).poke(addresses(lane).U)
          d.io.in.bits.writeData(lane).poke(values(lane).U)
        }
        d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean)
        d.clock.step(); d.io.in.valid.poke(false.B)
        until(d.io.out.valid.peek().litToBoolean)
        d.io.out.bits.faultMask.expect(faults.U)
        for(lane <- 0 until 2) d.io.out.bits.readData(lane).expect(expected(lane).U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      def atomic(address: Long, fault: Boolean, old: Long = 0L): Unit = {
        d.io.atomicIn.bits.warpId.poke(1.U); d.io.atomicIn.bits.address.poke(address.U)
        d.io.atomicIn.bits.operand.poke(1.U); d.io.atomicIn.bits.operation.poke(AtomicMemoryOp.add)
        d.io.atomicIn.valid.poke(true.B); until(d.io.atomicIn.ready.peek().litToBoolean)
        d.clock.step(); d.io.atomicIn.valid.poke(false.B)
        until(d.io.atomicOut.valid.peek().litToBoolean)
        d.io.atomicOut.bits.fault.expect(fault.B); d.io.atomicOut.bits.warpId.expect(1.U)
        if(!fault) d.io.atomicOut.bits.oldValue.expect(old.U)
        d.clock.step(3); d.io.atomicOut.bits.fault.expect(fault.B)
        d.io.atomicOut.ready.poke(true.B); d.clock.step(); d.io.atomicOut.ready.poke(false.B)
      }
      val edges = Seq(base, base + 252)
      val values = Seq(0x11223344L, 0x55667788L)
      vector(true, edges, values, 3, 0, Seq(0L, 0L))
      scenario match {
        case "vector" =>
          vector(true, Seq(base - 1, base + 128), Seq(0xdeadbeefL, 0xaabbccddL),
            3, 1, Seq(0L, 0L))
          vector(false, edges, Seq(0L, 0L), 3, 0, values)
          vector(false, Seq(base + 128, base + 128), Seq(0L, 0L), 3, 0,
            Seq(0xaabbccddL, 0xaabbccddL))
        case "atomic" =>
          atomic(0xfffffffcL, fault = true)
          vector(false, edges, Seq(0L, 0L), 3, 0, values)
        case "rv32-end" =>
          atomic(0xfffffffcL, fault = false, old = values(1))
          atomic(base - 4, fault = true)
          vector(true, Seq(0xffffffffL, base), Seq(0xdeadbeefL, 0L), 1, 1, Seq(0L, 0L))
          vector(false, edges, Seq(0L, 0L), 3, 0, Seq(values.head, values(1) + 1))
      }
      d.io.idle.expect(true.B)
    }
  }

  it should "fault a vector lane below the allocation without corrupting either edge" in {
    check(0x10000000L, "vector")
  }
  it should "fault a wrapped atomic end address without aliasing the final word" in {
    check(0x10000000L, "atomic")
  }
  it should "preserve a legal allocation ending at the exclusive RV32 limit" in {
    check(0xffffff00L, "rv32-end")
  }
}
