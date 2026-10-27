package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class SharedCuSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("SharedCuSramModels.sv",
    "module SharedCuSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(width => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"))).mkString("\n"))
}

private class SharedSramComputeUnit extends GpuComputeUnit(GpuConfig(),
  useBlackBoxes = true, enableFpuBackend = true, nativeExecutionProfile = true,
  finishOnTrap = true, instructionCacheSets = 4, instructionCacheWays = 1,
  vectorCacheSets = 4, vectorCacheWays = 1) {
  val resourcesPresent = IO(Output(Bool()))
  resourcesPresent := Module(new SharedCuSramModels).io.present
}

class GpuComputeUnitSharedSramSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def add(rd: Int, a: Int, b: Int): Long = (b.toLong << 20) | (a << 15) | (rd << 7) | 0x33
  private def addi(rd: Int, a: Int, value: Int): Long = ((value.toLong & 4095) << 20) | (a << 15) | (rd << 7) | 0x13
  private def vector(base: Int, v: Int, store: Boolean): Long =
    (1L << 25) | (base << 15) | (6 << 12) | (v << 7) | (if(store) 0x27 else 7)

  it should "execute eight 16-lane warps through shared vector stores, AMOs and guarded global publication" in {
    val c = GpuConfig()
    simulate(new SharedSramComputeUnit) { d =>
      for(last <- Seq(false, true)) {
        zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        d.resourcesPresent.expect(true.B)
        d.io.trap.ready.poke(true.B); d.io.l1InvalidateDone.ready.poke(true.B)
        val memory = mutable.Map.empty[Long, Int]
        val output = 0x20000L
        for(a <- output - 64 until output + 1024) memory(a) = 0xa5
        def word(address: Long, value: Long): Unit =
          (0 until 4).foreach(b => memory(address + b) = ((value >>> (8 * b)) & 255).toInt)
        val program = Seq(
          (0xc1027057L & ~(31L << 15)) | (16L << 15), // vsetivli zero,16,e32,m1
          (2L << 20) | (8 << 15) | (1 << 12) | (10 << 7) | 0x13, // slli x10,x8,2
          (if(last) 0x10004L else 0x10000L) << 12 | (5 << 7) | 0x37,
          addi(5, 5, if(last) -512 else 0), add(5, 5, 10),
          vector(5, 1, store = true), addi(6, 0, 7),
          (6L << 20) | (5 << 15) | (2 << 12) | (13 << 7) | 0x2f, // amoadd.w
          vector(5, 2, store = false), add(7, 1, 10),
          vector(7, 2, store = true), 0x30500073L)
        program.zipWithIndex.foreach { case(value, i) => word(0x1000L + i * 4, value) }
        case class Reply(id: BigInt, value: BigInt, due: Int)
        val pending = mutable.Queue.empty[Reply]
        val atomicOldValues = mutable.ArrayBuffer.empty[Long]
        var cycle = 0; var globalWrites = 0
        def tick(): Unit = {
          d.io.trap.valid.expect(false.B); d.io.globalAtomicRequest.valid.expect(false.B)
          d.io.memoryRequest.ready.poke((cycle % 7 < 5).B)
          val response = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(response.nonEmpty.B)
          response.foreach { r =>
            d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.value.U)
          }
          if(response.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue.toLong
            assert(address == 0x1000L || (address >= output && address < output + 512),
              s"unexpected external traffic at 0x${address.toHexString}")
            assert((address & 63) == 0)
            if(q.isWrite.peek().litToBoolean) {
              assert(address >= output); globalWrites += 1
              val mask = q.byteMask.peek().litValue; val value = q.writeData.peek().litValue
              for(b <- 0 until 64 if mask.testBit(b)) memory(address + b) = ((value >> (8 * b)) & 255).toInt
            }
            val value = (0 until 64).foldLeft(BigInt(0))((v, b) => v | (BigInt(memory.getOrElse(address + b, 0)) << (8 * b)))
            pending.enqueue(Reply(q.transactionId.peek().litValue, value, cycle + 5))
          }
          if(d.io.committedWriteback.valid.peek().litToBoolean &&
            d.io.committedWriteback.bits.rd.peek().litValue == 13)
            atomicOldValues += d.io.committedWriteback.bits.data.peek().litValue.toLong
          d.clock.step(); cycle += 1
        }
        def until(condition: => Boolean): Unit = {
          var n = 0
          while(!condition && n < 20000) { tick(); n += 1 }
          assert(condition, s"shared-memory CU stalled at $cycle, last=$last")
        }
        val k = d.io.kernel.bits
        k.kernelPc.poke(0x1000.U); k.kernargAddress.poke(output.U)
        for(i <- 0 until 3) {
          k.gridSize(i).poke(1.U); k.localSize(i).poke((if(i == 0) c.lanes * c.warps else 1).U)
        }
        d.io.kernel.valid.poke(true.B); until(d.io.kernel.ready.peek().litToBoolean)
        tick(); d.io.kernel.valid.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.success.expect(true.B)
        for(_ <- 0 until 5) { tick(); d.io.completion.valid.expect(true.B) }
        assert(pending.isEmpty && globalWrites == c.warps)
        assert(atomicOldValues.sorted == (0 until c.warps).map(_ * c.lanes.toLong))
        for(a <- output - 64 until output + 1024) {
          val offset = (a - output).toInt
          val expected = if(offset < 0 || offset >= 512) 0xa5 else {
            val id = offset / 4
            val value = id + (if(id % c.lanes == 0) 7 else 0)
            (value >>> ((offset & 3) * 8)) & 255
          }
          assert(memory(a) == expected, s"stored byte mismatch at 0x${a.toHexString}, last=$last")
        }
        d.io.completion.ready.poke(true.B); tick(); d.io.active.expect(0.U)
      }
    }
  }
}
