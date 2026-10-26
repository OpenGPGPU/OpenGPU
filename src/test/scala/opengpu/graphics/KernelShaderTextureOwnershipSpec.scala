package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class KernelShaderTextureOwnershipSpec extends AnyFlatSpec {
  behavior of "single-task shared shader texture routing"

  private def fill(data: Data, random: scala.util.Random): Unit = data match {
    case r: Record => r.elements.values.foreach(fill(_, random))
    case v: Vec[_] => v.foreach(fill(_, random))
    case b: Bool => b.poke(random.nextBoolean().B)
    case u: UInt => u.poke(BigInt(u.getWidth, random).U)
    case s: SInt => s.poke((BigInt(s.getWidth, random) - (BigInt(1) << (s.getWidth - 1))).S)
    case _ => ()
  }

  for (count <- Seq(1, 2, 4)) {
    it should s"retain requests and returns through stalls and owner reuse across $count CUs" in {
      simulate(new KernelShaderStage(GpuConfig(lanes = 4, warps = 2),
        sharedShader = Some(SharedCuShaderConfig(count)))) { d =>
        val pool = d.io.shared.get
        val random = new scala.util.Random(0x6f776e + count)
        d.io.instructionSatp.poke(0.U); d.io.vectorSatp.poke(0.U)
        d.io.instructionTlbFlush.valid.poke(false.B); fill(d.io.instructionTlbFlush.bits, random)
        d.io.vectorTlbFlush.valid.poke(false.B); fill(d.io.vectorTlbFlush.bits, random)
        d.io.launch.valid.poke(false.B)
        d.io.launch.kernelPc.poke(0x1000.U); d.io.launch.kernargAddress.poke(0x8000.U)
        d.io.launch.gridX.poke(1.U); d.io.launch.gridY.poke(1.U); d.io.launch.gridZ.poke(1.U)
        d.io.launch.localX.poke(4.U); d.io.launch.localY.poke(1.U); d.io.launch.localZ.poke(1.U)
        d.io.completion.ready.poke(true.B); d.io.trap.ready.poke(true.B)
        d.io.memoryRequest.ready.poke(true.B); d.io.memoryResponse.valid.poke(false.B)
        fill(d.io.memoryResponse.bits, random)
        d.io.globalAtomicRequest.ready.poke(true.B); d.io.globalAtomicResponse.valid.poke(false.B)
        fill(d.io.globalAtomicResponse.bits, random)
        d.io.simtBranch.valid.poke(false.B); fill(d.io.simtBranch.bits, random)
        d.io.l1Invalidate.valid.poke(false.B); fill(d.io.l1Invalidate.bits, random)
        d.io.l1InvalidateDone.ready.poke(true.B)
        d.io.texSample.ready.poke(false.B); d.io.vectorTexSample.ready.poke(false.B)
        d.io.texWriteback.valid.poke(false.B); fill(d.io.texWriteback.bits, random)
        d.io.vectorTexWriteback.valid.poke(false.B); fill(d.io.vectorTexWriteback.bits, random)
        pool.launch.ready.poke(true.B); pool.completion.valid.poke(false.B)
        fill(pool.completion.bits, random); pool.completion.bits.commandId.poke(0.U)
        pool.trap.valid.poke(false.B); fill(pool.trap.bits, random); pool.trap.bits.taskId.poke(0.U)
        pool.owned.poke(0.U); pool.taskIds.foreach(_.poke(0.U))
        pool.invalidate.ready.poke(true.B); pool.invalidateDone.valid.poke(false.B)
        fill(pool.invalidateDone.bits, random)
        pool.texture.foreach { t =>
          t.scalar.valid.poke(false.B); fill(t.scalar.bits, random)
          t.vector.valid.poke(false.B); fill(t.vector.bits, random)
          t.scalarWriteback.ready.poke(false.B); t.vectorWriteback.ready.poke(false.B)
        }
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

        def check(owner: Int, scalar: Boolean, vector: Boolean, sr: Boolean, vr: Boolean,
                  sw: Boolean, vw: Boolean, swr: Boolean, vwr: Boolean): Unit = {
          d.io.texSample.valid.expect((owner >= 0 && scalar).B)
          d.io.vectorTexSample.valid.expect((owner >= 0 && vector).B)
          d.io.texWriteback.ready.expect((owner >= 0 && swr).B)
          d.io.vectorTexWriteback.ready.expect((owner >= 0 && vwr).B)
          if (owner >= 0) {
            assert(d.io.texSample.bits.peek().litValue == pool.texture(owner).scalar.bits.peek().litValue)
            assert(d.io.vectorTexSample.bits.peek().litValue == pool.texture(owner).vector.bits.peek().litValue)
          }
          for ((t, cu) <- pool.texture.zipWithIndex) {
            t.scalar.ready.expect((cu == owner && sr).B)
            t.vector.ready.expect((cu == owner && vr).B)
            t.scalarWriteback.valid.expect((cu == owner && sw).B)
            t.vectorWriteback.valid.expect((cu == owner && vw).B)
            if (cu == owner) {
              assert(t.scalarWriteback.bits.peek().litValue == d.io.texWriteback.bits.peek().litValue)
              assert(t.vectorWriteback.bits.peek().litValue == d.io.vectorTexWriteback.bits.peek().litValue)
            }
          }
        }

        var simultaneousTransfers = 0
        // Revisit each CU after another task's ownership, without adding a
        // texture queue or a cycle of latency at either boundary.
        for (owner <- (0 until count) ++ (0 until count).reverse) {
          d.io.launch.ready.expect(true.B); d.io.launch.valid.poke(true.B)
          d.clock.step(); d.io.launch.valid.poke(false.B)
          d.io.launch.ready.expect(false.B); pool.owned.poke((BigInt(1) << owner).U)
          val t = pool.texture(owner)
          t.scalar.valid.poke(true.B); t.vector.valid.poke(true.B)
          fill(t.scalar.bits, random); fill(t.vector.bits, random)
          d.io.texWriteback.valid.poke(true.B); d.io.vectorTexWriteback.valid.poke(true.B)
          fill(d.io.texWriteback.bits, random); fill(d.io.vectorTexWriteback.bits, random)
          // Hold all four packets; unrelated CU payload activity cannot
          // replace any offered request or consume either return.
          for (_ <- 0 until 12) {
            for (cu <- 0 until count if cu != owner) {
              fill(pool.texture(cu).scalar.bits, random); fill(pool.texture(cu).vector.bits, random)
            }
            check(owner, true, true, false, false, true, true, false, false)
            d.clock.step()
          }
          for (cycle <- 0 until 96) {
            val sr = cycle < 24 || random.nextBoolean()
            val vr = cycle < 24 || random.nextBoolean()
            val swr = cycle < 24 || random.nextBoolean()
            val vwr = cycle < 24 || random.nextBoolean()
            d.io.texSample.ready.poke(sr.B); d.io.vectorTexSample.ready.poke(vr.B)
            t.scalarWriteback.ready.poke(swr.B); t.vectorWriteback.ready.poke(vwr.B)
            check(owner, true, true, sr, vr, true, true, swr, vwr)
            if (sr && vr && swr && vwr) simultaneousTransfers += 1
            d.clock.step()
            if (sr) fill(t.scalar.bits, random)
            if (vr) fill(t.vector.bits, random)
            if (swr) fill(d.io.texWriteback.bits, random)
            if (vwr) fill(d.io.vectorTexWriteback.bits, random)
          }
          t.scalar.valid.poke(false.B); t.vector.valid.poke(false.B)
          d.io.texWriteback.valid.poke(false.B); d.io.vectorTexWriteback.valid.poke(false.B)
          t.scalarWriteback.ready.poke(false.B); t.vectorWriteback.ready.poke(false.B)
          d.io.texSample.ready.poke(false.B); d.io.vectorTexSample.ready.poke(false.B)
          pool.owned.poke(0.U)
          check(-1, false, false, false, false, false, false, false, false)
          pool.completion.valid.poke(true.B); pool.completion.bits.success.poke(true.B)
          d.io.completion.valid.expect(true.B); d.clock.step(); pool.completion.valid.poke(false.B)
          d.io.launch.ready.expect(true.B)
        }
        assert(simultaneousTransfers >= 48 * count)
        info(s"OWNED_TEXTURE cus=$count ownerLifetimes=${2 * count} simultaneousFourWayTransfers=$simultaneousTransfers noAddedLatency=true")
      }
    }
  }
}
