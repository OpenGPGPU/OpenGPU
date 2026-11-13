package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitCoreImageCounterAtomicSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  for ((translated, deniedOwner) <- Seq((false, -1), (true, -1), (true, 1), (true, 0))) {
    it should s"preserve simultaneous image and counter atomic owners translated=$translated deniedOwner=$deniedOwner" in {
      simulate(new GpuComputeUnitCore(GpuConfig(lanes = 4, warps = 2), vectorCacheSets = 4,
        vectorCacheWays = 1, imageAvailable = true, imageAtomicAvailable = true,
        atomicCounterAvailable = true)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val inputs = Seq(d.io.counterAtomicRequest.get, d.io.imageAtomicRequest.get)
        val outputs = Seq(d.io.counterAtomicResponse.get, d.io.imageAtomicResponse.get)
        val roots = Seq(d.io.counterAtomicSatp.get, d.io.imageAtomicSatp.get)
        for (i <- 0 until 2) {
          inputs(i).bits.address.poke((if (i == 0) 0x201c else 0x2048).U)
          inputs(i).bits.warpId.poke(i.U); inputs(i).bits.operation.poke(AtomicMemoryOp.add)
          inputs(i).bits.operand.poke((i + 1).U); inputs(i).valid.poke(true.B)
          roots(i).poke((if (translated) BigInt("80000090", 16) + 16 * i else BigInt(0)).U)
        }
        val accepted = Array(false, false); val retired = Array(false, false)
        var walks = Set.empty[BigInt]; var offered = Set.empty[Int]
        var walkReply: Option[(BigInt, Int)] = None
        var atomicReply: Option[(Int, Int)] = None
        val responseHeld = Array(0, 0)
        var cycle = 0
        while (!retired.forall(identity) && cycle < 1000) {
          d.io.vectorPageTableRequest.ready.poke((cycle % 3 != 0).B)
          d.io.vectorPageTableResponse.valid.poke(walkReply.exists(_._2 <= cycle).B)
          walkReply.filter(_._2 <= cycle).foreach { case (pte, _) => d.io.vectorPageTableResponse.bits.pte.poke(pte.U) }
          d.io.globalAtomicRequest.ready.poke((cycle % 4 != 0).B)
          d.io.globalAtomicResponse.valid.poke(atomicReply.exists(_._2 <= cycle).B)
          atomicReply.filter(_._2 <= cycle).foreach { case (owner, _) =>
            d.io.globalAtomicResponse.bits.warpId.poke(owner.U)
            d.io.globalAtomicResponse.bits.oldValue.poke((41 + owner).U)
          }
          for (i <- 0 until 2) outputs(i).ready.poke((responseHeld(i) >= 3).B)
          for (i <- 0 until 2 if inputs(i).valid.peek().litToBoolean && inputs(i).ready.peek().litToBoolean) {
            assert(!accepted(i)); accepted(i) = true
          }
          if (d.io.vectorPageTableResponse.valid.peek().litToBoolean && d.io.vectorPageTableResponse.ready.peek().litToBoolean) walkReply = None
          if (d.io.vectorPageTableRequest.valid.peek().litToBoolean && d.io.vectorPageTableRequest.ready.peek().litToBoolean) {
            assert(walkReply.isEmpty)
            val address = d.io.vectorPageTableRequest.bits.address.peek().litValue
            assert(Set[BigInt](0x90000, 0xa0000)(address)); walks += address
            val owner = if (address == 0x90000) 0 else 1
            val flags = if (owner == deniedOwner) 0xc9 else 0xc7
            walkReply = Some((((if (address == 0x90000) BigInt(0x400) else BigInt(0x800)) << 10) | flags, cycle + 2))
          }
          if (d.io.globalAtomicResponse.valid.peek().litToBoolean && d.io.globalAtomicResponse.ready.peek().litToBoolean) atomicReply = None
          if (d.io.globalAtomicRequest.valid.peek().litToBoolean && d.io.globalAtomicRequest.ready.peek().litToBoolean) {
            assert(atomicReply.isEmpty)
            val q = d.io.globalAtomicRequest.bits; val owner = q.warpId.peek().litValue.toInt
            assert(owner == 0 || owner == 1); assert(!offered(owner)); offered += owner
            q.address.expect((if (translated) { if (owner == 0) 0x40201c else 0x802048 } else { if (owner == 0) 0x201c else 0x2048 }).U)
            q.operand.expect((owner + 1).U); q.operation.expect(AtomicMemoryOp.add)
            atomicReply = Some((owner, cycle + 4))
          }
          for (i <- 0 until 2 if outputs(i).valid.peek().litToBoolean) {
            val denied = i == deniedOwner
            assert(!retired(i)); outputs(i).bits.warpId.expect(i.U)
            if (!denied) outputs(i).bits.oldValue.expect((41 + i).U)
            outputs(i).bits.fault.expect(denied.B); outputs(i).bits.pageFault.expect(denied.B)
            if (outputs(i).ready.peek().litToBoolean) retired(i) = true else responseHeld(i) += 1
          }
          d.clock.step(); cycle += 1
          for (i <- 0 until 2 if accepted(i)) { inputs(i).valid.poke(false.B); roots(i).poke(0.U) }
        }
        assert(accepted.forall(identity) && retired.forall(identity))
        assert(offered == (Set(0, 1) -- Set(deniedOwner)))
        assert(walks == (if (translated) Set[BigInt](0x90000, 0xa0000) else Set.empty[BigInt]))
      }
    }
  }
}
