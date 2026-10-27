package opengpu.system

import chisel3._
import chisel3.util.HasBlackBoxInline
import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, GraphicsConfig, NativeBinnedGraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import scala.collection.mutable

private object NativeFourEngineSramModels {
  private val modelRoot = Paths.get("depends/asap7_sram_0p0/generated/verilog")
  def source: String =
    "module NativeFourEngineSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq("srambank_64x4x32_6t122.v", "srambank_64x4x64_6t122.v")
        .map(name => Files.readString(modelRoot.resolve(name))).mkString("\n")
}

private class NativeFourEngineSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("NativeFourEngineSramModels.sv", NativeFourEngineSramModels.source)
}

/** Match EmitPpaRtl's mobile-native-four-engine-host parameters exactly.
  * Only the constant model-presence output and simulation resources are added.
  */
private class NativeFourEngineSramHost extends GpuHostSystemAxi(
  GraphicsConfig(screenWidth=640, screenHeight=480),
  GpuConfig(l2Sets=512, l2StoreEntries=8), numComputeUnits=4,
  fragCore=true, sharedGraphics=true, parallelGraphics=true, enableFpuBackend=true,
  memoryAxiDataBytes=32, useBlackBoxes=true, exposePerformance=true,
  nativeBinnedGraphics=Some(NativeBinnedGraphicsConfig.fourEngine)) {
  val macroModelsPresent = IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk, !io.s_axi_aresetn) {
    macroModelsPresent := Module(new NativeFourEngineSramModels).io.present
  }
}

/** Explicit full-profile qualification, separate from routine PR frame tests.
  * Run with `Test / runMain opengpu.system.QualifyNativeFourEngineSramHost`.
  * `--emit DIR` emits the tested host for comparison to the product emission.
  * This command exercises compute in the coenabled graphics host; it does not
  * establish native rendering correctness, throughput or a product clock.
  */
object QualifyNativeFourEngineSramHost extends GpuHostTestSupport {
  private def add(rd: Int, rs1: Int, rs2: Int): BigInt =
    BigInt((rs2 << 20) | (rs1 << 15) | (rd << 7) | 0x33)
  private def shift(rd: Int, rs: Int, bits: Int): BigInt =
    BigInt((bits << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13)

  def main(args: Array[String]): Unit = {
    if (args.nonEmpty) {
      require(args.length == 2 && args(0) == "--emit", "usage: [--emit DIR]")
      ChiselStage.emitSystemVerilogFile(new NativeFourEngineSramHost,
        Array("--target-dir", args(1)),
        Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
      return
    }
    simulate(new NativeFourEngineSramHost) { d =>
      initialize(d)
      d.macroModelsPresent.expect(true.B)
      val expectedResources = Vector[BigInt](BigInt("47435201",16),16,7,4,16,8,128,128,
        BigInt("ffffffff",16),16384,16,8,BigInt("10000000",16),32,131072,4)
      for (i <- expectedResources.indices)
        assert(axiRead(d, 0x360 + 4*i) == expectedResources(i), s"resource word $i differs")

      val bytes = mutable.Map.empty[BigInt, Int]
      def put(address: Int, value: BigInt): Unit =
        for (i <- 0 until 4) bytes(BigInt(address)+i) = ((value >> (8*i)) & 255).toInt
      def word(address: Int): BigInt =
        (0 until 4).map(i => BigInt(bytes.getOrElse(BigInt(address)+i, 0)) << (8*i)).sum
      def line(address: BigInt): BigInt = {
        val base = address & ~BigInt(63)
        (0 until 64).map(i => BigInt(bytes.getOrElse(base+i, 0)) << (8*i)).sum
      }
      def write(address: BigInt, data: BigInt, mask: BigInt): Unit =
        for (i <- 0 until 64 if mask.testBit(i)) bytes(address+i) = ((data >> (8*i)) & 255).toInt

      val output = 0x20000
      val fpOutput = output + 0x1000
      val guard = BigInt("a5d72c91",16)
      for (base <- Seq(output, fpOutput); i <- -1 to 512) put(base+4*i, guard)
      def floatBits(value: Float): BigInt =
        BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
      for (group <- 0 until 4; warp <- 0 until 8)
        put(0x40000+group*512+warp*64, floatBits(1.0f+(group*8+warp)/16.0f))
      // x1=kernarg, x2=group X, x8=warp-local base, x9=resident capacity.
      // Vector v1 contains the initializer's local-linear IDs in every lane.
      val program = Seq(
        shift(10,2,9), shift(11,8,2), add(10,10,11), add(10,10,1),
        BigInt("0104f057",16), // vsetvli x0,x9,e32,m1
        BigInt("00310613",16), // addi x12,x2,3: a distinct scalar operand per CU group
        (BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(12)<<15)|(BigInt(4)<<12)|(BigInt(2)<<7)|0x57,
        (BigInt(1)<<25)|(BigInt(10)<<15)|(BigInt(6)<<12)|(BigInt(2)<<7)|0x27,
        BigInt("00020737",16), add(14,14,10), // distinct FP input per group/warp
        (BigInt(14)<<15)|(BigInt(2)<<12)|(BigInt(1)<<7)|0x07, // flw f1,0(x14)
        BigInt("00108153",16), // fadd.s f2,f1,f1
        BigInt("000016b7",16), // lui x13,1: separate FP output array
        add(15,10,13),
        (BigInt(2)<<20)|(BigInt(15)<<15)|(BigInt(2)<<12)|0x27, // fsw f2,0(x15)
        BigInt("30500073",16))
      program.zipWithIndex.foreach { case (value, i) => put(0x1000+4*i, value) }

      axiWrite(d, RenderHostRegs.IRQ, 1)
      for ((address, value) <- Seq(R.COMMAND_ID->7, R.OPCODE->GpuCommandOpcode.kernel.litValue.toInt,
        R.KERNEL_PC->0x1000, R.KERNARG->output, R.GRID_X->4, R.GRID_Y->1, R.GRID_Z->1,
        R.LOCAL_X->128, R.LOCAL_Y->1, R.LOCAL_Z->1)) axiWrite(d, address, value)
      axiWrite(d, R.SUBMIT, 1)
      var peakActiveCUs = 0
      var serviceCycles = 0
      serviceMemoryMaster(d, line, writeAckDelay=19, readResponseDelay=17, maxCycles=100000,
        onCycle=() => {
          peakActiveCUs = math.max(peakActiveCUs, d.io.performance.get.activeComputeUnits.peek().litValue.toInt)
          serviceCycles += 1
        })(write) { d.io.m_irq.peek().litToBoolean }
      val completion = axiRead(d, R.COMPLETION)
      assert((completion & 255) == 7)
      assert(((completion >> 8) & 7) == GpuCommandOpcode.kernel.litValue)
      assert(((completion >> 11) & 15) == 0 && ((completion >> 15) & 1) == 1)
      for (_ <- 0 until 4) assert(axiRead(d, R.COMPLETION) == completion)
      assert(peakActiveCUs == 4, s"only $peakActiveCUs CUs were active simultaneously")
      for (i <- -1 to 512) {
        val expectedVector = if (i >= 0 && i < 512) BigInt((i % 128)+(i / 128)+3) else guard
        assert(word(output+4*i) == expectedVector, s"vector result/guard $i differs")
        val expectedFp = if (i >= 0 && i < 512 && i % 16 == 0)
          floatBits(2.0f+((i / 128)*8+(i % 128)/16)/8.0f) else guard
        assert(word(fpOutput+4*i) == expectedFp, s"FP result/guard $i differs")
      }
      axiWrite(d, R.COMPLETION_POP, 1)
      println(s"NATIVE_FOUR_ENGINE_SRAM_HOST PASS simulations=1 CUs=4 lanes=16 warps=8 localItems=128 groups=4 peakActiveCUs=$peakActiveCUs vectorWords=512 scalarFpWords=32 L2bytes=131072 L2stores=8 AXIbits=256 viewportCapacity=640x480 fullNativeCapacities=true pinnedSramModels=true independentGroupVectorAndWarpFp=true serviceCycles=$serviceCycles renderingQualified=false clockQualified=false")
    }
  }
}
