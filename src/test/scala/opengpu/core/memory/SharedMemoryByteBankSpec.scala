package opengpu.core.memory

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SharedByteBankResources extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("SharedByteBankResources.sv",
    "module SharedByteBankResources(output present); assign present=1'b1; endmodule\n" +
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v")))
}

private class SharedByteBankComparison(depth: Int) extends Module {
  val reference = Module(new SharedMemoryByteBank(depth))
  val macroBank = Module(new SharedMemoryByteBank(depth, useBlackBoxes = true))
  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(math.max(1, chisel3.util.log2Ceil(depth)).W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(math.max(1, chisel3.util.log2Ceil(depth)).W))
    val writeData = Input(UInt(8.W))
    val referenceData = Output(UInt(8.W))
    val macroData = Output(UInt(8.W))
    val resourcesPresent = Output(Bool())
  })
  for (bank <- Seq(reference, macroBank)) {
    bank.io.readEnable := io.readEnable; bank.io.readAddress := io.readAddress
    bank.io.writeEnable := io.writeEnable; bank.io.writeAddress := io.writeAddress
    bank.io.writeData := io.writeData
  }
  io.referenceData := reference.io.readData
  io.macroData := macroBank.io.readData
  io.resourcesPresent := Module(new SharedByteBankResources).io.present
}

class SharedMemoryByteBankSpec extends AnyFlatSpec {
  behavior of "SharedMemoryByteBank"

  for (depth <- Seq(1, 64, 256, 1024)) {
    it should s"preserve every byte and one-cycle reads at depth $depth with real SRAM models" in {
      simulate(new SharedByteBankComparison(depth)) { d =>
        d.io.readEnable.poke(false.B); d.io.writeEnable.poke(false.B)
        d.io.readAddress.poke(0.U); d.io.writeAddress.poke(0.U); d.io.writeData.poke(0.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.resourcesPresent.expect(true.B)
        val expected = Array.tabulate(depth)(a => ((a * 131) ^ (a >> 8) * 29 ^ 0x5a) & 255)
        for (a <- 0 until depth) {
          d.io.writeEnable.poke(true.B); d.io.writeAddress.poke(a.U)
          d.io.writeData.poke(expected(a).U); d.clock.step()
        }
        d.io.writeEnable.poke(false.B)
        def read(address: Int): Unit = {
          d.io.readEnable.poke(true.B); d.io.readAddress.poke(address.U)
          d.clock.step()
          d.io.referenceData.expect(expected(address).U)
          d.io.macroData.expect(expected(address).U)
        }
        // Alternating segments on successive clocks detects an unregistered
        // segment selector, aliasing high bits and an extra response cycle.
        for (row <- 0 until math.min(depth, 256); segment <- 0 until math.max(1, depth / 256)) {
          read(segment * 256 + row)
        }
        d.io.readEnable.poke(false.B)
        for (a <- Seq(0, 255, 256, 511, 512, depth - 1).filter(_ < depth).distinct) {
          expected(a) ^= 0xff
          d.io.writeEnable.poke(true.B); d.io.writeAddress.poke(a.U)
          d.io.writeData.poke(expected(a).U); d.clock.step()
          d.io.writeEnable.poke(false.B)
          d.io.readAddress.poke((depth - 1 - a).U); d.clock.step(2)
        }
        for (a <- (0 until depth).reverse) read(a)
      }
    }
  }
}
