package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise the staging port directly: acknowledgements publish writes, read
  * replies reorder, both request/output ports stall, and banks are reused. */
class KernelFragInputUsageSpec extends AnyFlatSpec {
  behavior of "KernelFragStage concurrent staging"
  for ((lanes, warps, transactions) <- Seq((4, 2, 2), (8, 2, 2), (16, 7, 4), (16, 8, 8))) {
    it should s"drain tagged writes and route reordered reads for $lanes lanes, $warps warps and $transactions credits" in {
      val cap = lanes * warps
      simulate(new KernelFragStage(GpuConfig(lanes = lanes, warps = warps), GraphicsConfig(), transactions)) { dut =>
        def zeroInputs(d: Data): Unit = d match {
          case r: Record => r.elements.values.foreach(zeroInputs)
          case v: Vec[_] => v.foreach(zeroInputs)
          case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
          case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
          case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
          case _ => ()
        }
        zeroInputs(dut.io)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.shaderPc.poke(0x1000.U)
        dut.io.kernargBase.poke(0x8000.U)
        dut.io.kernargBankStride.poke(0x2000.U)
        dut.io.abi1.poke(true.B)
        dut.io.kernelLaunch.ready.poke(true.B)
        dut.io.drawRetire.ready.poke(true.B)
        dut.io.texMem.req.ready.poke(true.B)
        dut.io.kernelTexWriteback.ready.poke(true.B)
        dut.io.kernelVectorTexWriteback.ready.poke(true.B)
        val mem = mutable.LongMap[BigInt]()
        def put(addr: Long, data: BigInt): Unit = {
          val base = addr & ~63L; val shift = (addr & 63).toInt * 8
          mem(base) = (mem.getOrElse(base, BigInt(0)) & ~(BigInt("ffffffff",16) << shift)) | ((data & BigInt("ffffffff",16)) << shift)
        }
        def word(addr: Long): BigInt = (mem.getOrElse(addr & ~63L, BigInt(0)) >> ((addr & 63).toInt * 8)) & BigInt("ffffffff",16)
        case class Pending(id: Int, addr: Long, write: Boolean, data: BigInt, mask: BigInt, due: Int, fault: Boolean)
        for (skip <- Seq(0, 15, 63, 21); (count, faultPhase) <- Seq((cap, "none"), (4, "none"), (cap - 4, "none"), (cap, "write"), (cap, "read"), (4, "none"))) {
          def sentinel(bank: Int, slice: Int, i: Int): BigInt = BigInt(0x5a000000L + bank*0x100000 + slice*0x10000 + i)
          for (bank <- 0 until 2; slice <- 0 until 10; i <- 0 until cap)
            put(0x8000L + bank*0x2000 + slice*4L*cap + i*4, sentinel(bank,slice,i))
          dut.io.inputSkip.poke(skip.U)
          val pending = mutable.ArrayBuffer.empty[Pending]
          var cycle = 0; var seq = 0; var launched = false; var completed = false
          var injected = false; var maxPending = 0; var reordered = false
          var launchBase = 0L; var outputs = 0; var retired = false
          var readSeen = false; var stable: Option[(BigInt, BigInt, BigInt, BigInt)] = None
          dut.io.wordMemReq.ready.poke(false.B)
          dut.io.out.ready.poke(false.B)
          // Feed a whole batch while staging is stalled; the registered
          // producer write of the last quad must be included in its first line.
          for (q <- 0 until count / 4) {
            dut.io.fragIn.ready.expect(true.B)
            for (k <- 0 until 4) {
              val i = q * 4 + k; val p = dut.io.fragIn.bits.lanes(k)
              p.x.poke((i + 1).S); p.y.poke((-i - 1).S)
              p.depth.poke((100 + i).S); p.covered.poke((i != count - 1).B)
              p.coverageMask.poke(1.U); p.color.r.poke((i & 255).U)
              p.color.g.poke(2.U); p.color.b.poke(3.U); p.alpha.poke(255.U)
              dut.io.fragUv(k).u.poke((1000 + i).U); dut.io.fragUv(k).v.poke((2000 + i).U)
            }
            dut.io.fragIn.valid.poke(true.B); dut.clock.step()
            // Later producer quads and live IO must not alter first-quad metadata.
            dut.io.inputSkip.poke((skip ^ 63).U)
          }
          dut.io.fragIn.valid.poke(false.B)
          dut.io.flush.poke(true.B); dut.clock.step(); dut.io.flush.poke(false.B)
          val expected = (0 until count).filter(i => i != count - 1 && i % 7 != 0)
          while (!retired && cycle < 16000) {
            dut.io.wordMemReq.ready.poke((cycle % 11 >= 3).B)
            dut.io.out.ready.poke((cycle % 7 >= 2).B)
            dut.io.kernelCompletion.valid.poke((launched && !completed).B)
            dut.io.kernelCompletion.bits.success.poke(true.B)
            // Younger requests complete first, and a lone request eventually
            // completes too. No dependence on a particular local ID sequence.
            val eligible = pending.indices.filter(i => pending(i).due <= cycle)
            val selected = eligible.lastOption
            dut.io.wordMemResp.valid.poke(selected.nonEmpty.B)
            selected.foreach { j =>
              val p = pending(j)
              dut.io.wordMemResp.bits.transactionId.poke(p.id.U)
              dut.io.wordMemResp.bits.readData.poke(mem.getOrElse(p.addr, BigInt(0)).U)
              dut.io.wordMemResp.bits.fault.poke(p.fault.B)
            }
            val reply = selected.filter(_ => dut.io.wordMemResp.ready.peek().litToBoolean)
            val reqFire = dut.io.wordMemReq.valid.peek().litToBoolean && dut.io.wordMemReq.ready.peek().litToBoolean
            if (dut.io.wordMemReq.valid.peek().litToBoolean) {
              val b = dut.io.wordMemReq.bits
              val payload = (b.address.peek().litValue, b.writeData.peek().litValue, b.byteMask.peek().litValue, b.transactionId.peek().litValue)
              stable.foreach(v => assert(v == payload, "request changed while stalled"))
              stable = if (reqFire) None else Some(payload)
            } else assert(stable.isEmpty, "stalled request disappeared")
            if (dut.io.kernelLaunch.valid.peek().litToBoolean) {
              assert(!launched && pending.isEmpty && !reqFire, "shader launched before all write acknowledgements")
              assert(faultPhase != "write", "staging write fault launched a shader")
              launchBase = dut.io.kernelLaunch.kernargAddress.peek().litValue.toLong
              dut.io.kernelLaunch.localX.expect(count.U)
              for (i <- 0 until count) {
                val bank = ((launchBase-0x8000)/0x2000).toInt
                val expectedInput = Seq(BigInt(i+1), BigInt((-i-1).toLong & 0xffffffffL),
                  BigInt(100+i), BigInt((i.toLong&255)<<24 | 0x0203ffL), BigInt(1000+i), BigInt(2000+i))
                for (slice <- 0 until 6) {
                  val expectedWord = if ((skip & (1<<slice)) != 0) sentinel(bank,slice,i) else expectedInput(slice)
                  assert(word(launchBase + slice*4L*cap + i*4) == expectedWord, s"skip=$skip slice=$slice lane=$i snapshot/input mismatch")
                }
                assert(word(launchBase + 28L*cap + 4L*i) == 100+i, "depth output must always be initialized")
                assert(word(launchBase + 32L*cap + 4L*i) == (if (i==count-1) 0 else 1), "control output must always be initialized")
                put(launchBase + 24L*cap + 4L*i, BigInt("aabbccff",16) + (BigInt(i) << 24))
                put(launchBase + 28L*cap + 4L*i, 300+i)
                put(launchBase + 32L*cap + 4L*i, if (i % 7 == 0) 0 else 3)
              }
              launched = true
            }
            if (dut.io.kernelCompletion.valid.peek().litToBoolean && dut.io.kernelCompletion.ready.peek().litToBoolean) completed = true
            if (dut.io.out.valid.peek().litToBoolean) {
              assert(pending.isEmpty && !reqFire, "fragment escaped before read drain")
              assert(faultPhase == "none", "faulted staging emitted a fragment")
              if (dut.io.out.ready.peek().litToBoolean) {
                val i = expected(outputs)
                dut.io.out.bits.x.expect((i+1).S)
                dut.io.out.bits.y.expect((-i-1).S)
                dut.io.out.bits.depth.expect((300+i).S)
                dut.io.out.bits.color.r.expect(((0xaa+i)&255).U)
                dut.io.out.bits.color.g.expect(0xbb.U)
                dut.io.out.bits.color.b.expect(0xcc.U)
                dut.io.out.bits.alpha.expect(0xff.U)
                dut.io.depthOverride.expect(true.B)
                outputs += 1
              }
            }
            if (dut.io.drawRetire.valid.peek().litToBoolean) {
              assert(pending.isEmpty && !reqFire, "batch retired with unacknowledged memory")
              retired = true
            }
            // Commit responses after inspecting combinational outputs, before
            // the clock edge. Writes become visible only on their response.
            reply.foreach { j =>
              val p = pending.remove(j); if (j > 0) reordered = true
              if (p.write && !p.fault) for (b <- 0 until 64 if p.mask.testBit(b)) {
                val shift = b*8; val old = mem.getOrElse(p.addr, BigInt(0))
                mem(p.addr) = (old & ~(BigInt(255) << shift)) | (((p.data >> shift)&255) << shift)
              }
            }
            if (reqFire) {
              val b = dut.io.wordMemReq.bits; val id = b.transactionId.peek().litValue.toInt
              assert(!pending.exists(_.id == id), "reused a live local staging ID")
              val wr = b.isWrite.peek().litToBoolean
              if (wr) {
                val addr = b.address.peek().litValue.toLong
                val bankBase = if (addr >= 0xa000L) 0xa000L else 0x8000L
                for (byte <- 0 until 64 if b.byteMask.peek().litValue.testBit(byte)) {
                  val slice = ((addr+byte-bankBase)/(4*cap)).toInt
                  assert(slice >= 6 || (skip & (1<<slice)) == 0, "wrote an omitted input byte")
                }
              }
              readSeen ||= !wr
              val fault = !injected && ((faultPhase == "write" && wr) || (faultPhase == "read" && !wr))
              injected ||= fault
              pending += Pending(id, b.address.peek().litValue.toLong, wr, b.writeData.peek().litValue,
                b.byteMask.peek().litValue, cycle + (if (seq % 2 == 0) 35 * transactions else 4), fault)
              seq += 1; maxPending = math.max(maxPending, pending.size)
            }
            dut.clock.step(); cycle += 1
          }
          assert(retired, s"batch count=$count fault=$faultPhase timed out")
          assert(outputs == (if (faultPhase == "none") expected.size else 0))
          assert(maxPending >= 2 && maxPending <= transactions && reordered, "did not overlap/reorder staging IDs")
          if (count == cap) assert(maxPending == transactions, "did not fill every local credit")
          assert(launched == (faultPhase != "write"))
          assert(readSeen == (faultPhase != "write"))
          for (bank <- 0 until 2; slice <- 0 until 10; i <- 0 until cap
              if (slice < 6 && (skip & (1<<slice)) != 0) || slice == 9)
            assert(word(0x8000L+bank*0x2000+slice*4L*cap+i*4)==sentinel(bank,slice,i), "omitted input/uniform changed")
          dut.io.wordMemResp.valid.poke(false.B)
          dut.io.kernelCompletion.valid.poke(false.B)
          dut.clock.step(2)
          dut.io.drained.expect(true.B)
          println(s"STAGING cap=$cap count=$count fault=$faultPhase credits=$transactions cycles=$cycle maxPending=$maxPending")
        }
      }
    }
  }
}
