package opengpu.core.memory

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorMemoryResponseAssemblySpec extends AnyFlatSpec {
  behavior of "vector response assembly"

  it should "preserve full-lane byte results, sparse faults and handshake timing across response stalls and reset" in {
    val lanes = 16
    val lineBytes = 64
    val addressMask = 0xffffffffL
    val random = new scala.util.Random(0x1eafb17eL)
    def wrap(a: Long): Long = a & addressMask
    def line(a: Long): Long = wrap(a) & ~(lineBytes - 1).toLong
    simulate(new VectorMemoryCoalescer(GpuConfig(lanes = lanes, warps = 8), lineBytes)) { d =>
      d.io.in.valid.poke(false.B)
      d.io.cacheRequest.ready.poke(false.B)
      d.io.cacheResponse.valid.poke(false.B)
      d.io.cacheResponse.bits.readData.poke(0.U)
      d.io.cacheResponse.bits.fault.poke(false.B)
      d.io.cacheResponse.bits.pageFault.poke(false.B)
      d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

      def run(trial: Int, addresses: Seq[Long], mask: Int, size: Int, store: Boolean): Unit = {
        val bytes = 1 << size
        val requested = addresses.zipWithIndex.filter { case (_, lane) => (mask & (1 << lane)) != 0 }
          .flatMap { case (a, _) => Seq(line(a), line(a + bytes - 1)) }.distinct
        def value(a: Long): Int = ((wrap(a) * 37 + (wrap(a) >>> 9) + trial * 23) & 255).toInt
        def fault(index: Int): Boolean = (index + trial) % 5 == 1
        // A pageFault flag without fault must never create a public page fault.
        def pageFault(index: Int): Boolean = (index + trial) % 3 == 1
        val writes = (0 until lanes).map(l => (0xa5d30000L ^ (trial.toLong << 8) ^ l) & addressMask)
        d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B)
        d.io.in.bits.warpId.poke((trial % 8).U)
        d.io.in.bits.laneMask.poke(mask.U); d.io.in.bits.elementSize.poke(size.U)
        d.io.in.bits.isStore.poke(store.B)
        for (l <- 0 until lanes) {
          d.io.in.bits.addresses(l).poke(wrap(addresses(l)).U)
          d.io.in.bits.writeData(l).poke(writes(l).U)
        }
        d.clock.step(); d.io.in.valid.poke(false.B)
        // Neither request generation nor response assembly may use live input.
        d.io.in.bits.laneMask.poke(0.U)
        d.io.in.bits.addresses.foreach(_.poke(0.U))
        d.io.in.bits.writeData.foreach(_.poke(0.U))
        if (requested.nonEmpty) {
          for (_ <- 0 until 3) {
            d.io.out.valid.expect(false.B); d.io.cacheRequest.valid.expect(false.B)
            d.io.in.ready.expect(false.B); d.clock.step()
          }
        }
        for ((base, index) <- requested.zipWithIndex) {
          var expectedMask = BigInt(0); var expectedData = BigInt(0)
          for (l <- 0 until lanes; b <- 0 until bytes if (mask & (1 << l)) != 0) {
            val a = wrap(addresses(l) + b)
            if (line(a) == base) {
              val offset = (a - base).toInt
              expectedMask = expectedMask.setBit(offset)
              val shift = offset * 8
              expectedData = (expectedData & ~(BigInt(255) << shift)) |
                (BigInt((writes(l) >>> (8 * b)) & 255) << shift)
            }
          }
          def checkRequest(): Unit = {
            d.io.cacheRequest.valid.expect(true.B)
            d.io.cacheRequest.bits.lineAddress.expect(base.U)
            d.io.cacheRequest.bits.warpId.expect((trial % 8).U)
            d.io.cacheRequest.bits.isStore.expect(store.B)
            d.io.cacheRequest.bits.cachePolicy.expect(CachePolicy.cached)
            d.io.cacheRequest.bits.byteMask.expect(expectedMask.U)
            d.io.cacheRequest.bits.writeData.expect(expectedData.U)
            d.io.out.valid.expect(false.B)
          }
          for (_ <- 0 until random.nextInt(3)) { checkRequest(); d.clock.step() }
          checkRequest(); d.io.cacheRequest.ready.poke(true.B); d.clock.step()
          d.io.cacheRequest.ready.poke(false.B)
          for (_ <- 0 until random.nextInt(4)) {
            d.io.cacheRequest.valid.expect(false.B); d.io.out.valid.expect(false.B)
            d.io.in.ready.expect(false.B); d.clock.step()
          }
          val data = (0 until lineBytes).map(b => BigInt(value(base + b)) << (8 * b)).sum
          d.io.cacheResponse.bits.readData.poke(data.U)
          d.io.cacheResponse.bits.fault.poke(fault(index).B)
          d.io.cacheResponse.bits.pageFault.poke(pageFault(index).B)
          d.io.cacheResponse.valid.poke(true.B); d.io.cacheResponse.ready.expect(true.B)
          d.clock.step(); d.io.cacheResponse.valid.poke(false.B)
          if (index + 1 < requested.size) {
            d.io.cacheRequest.valid.expect(false.B); d.io.out.valid.expect(false.B)
            d.clock.step()
          }
        }
        var expectedFaultMask = 0
        for (l <- 0 until lanes) {
          val word = (0 until 4).map { b =>
            val a = wrap(addresses(l) + b)
            BigInt(if (requested.contains(line(a))) value(a) else 0) << (8 * b)
          }.sum
          d.io.out.bits.readData(l).expect(word.U)
          if ((mask & (1 << l)) != 0 && requested.zipWithIndex.exists { case (base, i) =>
            (base == line(addresses(l)) || base == line(addresses(l) + bytes - 1)) && fault(i)
          }) expectedFaultMask |= 1 << l
        }
        d.io.out.valid.expect(true.B); d.io.out.bits.faultMask.expect(expectedFaultMask.U)
        d.io.out.bits.pageFault.expect(requested.indices.exists(i => fault(i) && pageFault(i)).B)
        val held = d.io.out.bits.peek().litValue
        for (_ <- 0 until 3) {
          d.io.in.valid.poke(true.B); d.io.in.ready.expect(false.B)
          d.io.cacheResponse.bits.readData.poke(0.U); d.clock.step()
          d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == held)
        }
        d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step()
        d.io.out.ready.poke(false.B); d.io.out.valid.expect(false.B)
      }

      run(0, (0 until lanes).map(l => 0x10000L + l * 256 + 63), 0xffff, 2, store = false)
      run(1, Seq.fill(lanes)(0x2003fL), 0xffff, 2, store = true)
      run(2, (0 until lanes).map(l => wrap(0xfffffffeL + l * 64)), 0xffff, 2, store = false)
      for (trial <- 3 until 128) {
        val addresses = (0 until lanes).map { _ =>
          wrap(0x80000000L + random.nextInt(12) * 64 + random.nextInt(64))
        }
        run(trial, addresses, if (trial % 17 == 0) 0 else random.nextInt(65536), trial % 3, trial % 2 == 1)
      }
      // Reset while a line response is owed, then verify a fresh transaction.
      d.io.in.valid.poke(true.B); d.io.in.bits.laneMask.poke(1.U)
      d.io.in.bits.elementSize.poke(2.U); d.io.in.bits.addresses(0).poke(63.U)
      d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(3)
      d.io.cacheRequest.ready.poke(true.B); d.clock.step()
      d.io.cacheRequest.ready.poke(false.B); d.io.cacheResponse.ready.expect(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.cacheRequest.valid.expect(false.B); d.io.out.valid.expect(false.B)
      run(128, Seq.fill(lanes)(0x3003fL), 0xffff, 2, store = false)
      run(129, Seq.fill(lanes)(0x3003fL), 0, 0, store = false)
      // Reset each newly owned admission/request stage, including a held offer.
      for (stage <- 0 until 4) {
        d.io.in.valid.poke(true.B); d.io.in.bits.laneMask.poke(1.U)
        d.io.in.bits.elementSize.poke(2.U); d.io.in.bits.addresses(0).poke(63.U)
        d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(stage)
        d.io.in.ready.expect(false.B)
        if (stage == 3) {
          d.io.cacheRequest.valid.expect(true.B)
          val held = d.io.cacheRequest.bits.peek().litValue
          d.clock.step(2); assert(d.io.cacheRequest.bits.peek().litValue == held)
        }
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.cacheRequest.valid.expect(false.B); d.io.out.valid.expect(false.B)
        run(130 + stage, Seq.fill(lanes)(0x4003fL), 0xffff, 2, store = stage % 2 == 0)
      }
    }
  }
}
