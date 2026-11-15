package opengpu.graphics.tessellation

import chisel3._
import chisel3.util._
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.VectorDecoder
import opengpu.core.vector.{VectorIntegerAlu, VectorIntegerRequest, VectorIntegerResult}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Real decode + integer datapath; register/scalar peers supply only the
  * emitted address microsequence. No shader, CU, patch RAM or Mesa peer. */
private class TessellationMappedAddressHarness extends Module {
  val p = GpuConfig(lanes = 16, warps = 8)
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val request = Flipped(Decoupled(new VectorIntegerRequest(p)))
    val result = Decoupled(new VectorIntegerResult(p))
    val legal = Output(Bool())
    val unit = Output(UInt(4.W))
  })
  private val decoder = Module(new VectorDecoder)
  private val alu = Module(new VectorIntegerAlu(p))
  decoder.io.instruction := io.instruction
  io.legal := decoder.io.decoded.valid
  io.unit := decoder.io.decoded.unit.asUInt
  alu.io.in <> io.request
  alu.io.in.bits.vd := io.instruction(11, 7)
  alu.io.in.bits.funct6 := decoder.io.decoded.funct6
  alu.io.in.bits.operandType := decoder.io.decoded.operandType
  alu.io.in.bits.vm := decoder.io.decoded.vm
  alu.io.in.bits.immediate := io.instruction(19, 15)
  alu.io.in.bits.vs1Field := io.instruction(19, 15)
  io.result <> alu.io.out
}

class TessellationMappedAddressSpec extends AnyFlatSpec {
  behavior of "actual mapped TES address instructions"
  it should "multiply actual component strides and reject full-width indices before wrap" in {
    val directory = sys.props.getOrElse("opengpu.mappedAddressPrograms",
      throw new IllegalArgumentException("export real helper words with test_tessellation_mapped_address.py --dump"))
    val mask32 = (BigInt(1) << 32) - 1
    val indices = Seq[BigInt](0, 1, 2, 30, 31, mask32, BigInt("80000000",16), BigInt("20000000",16)) ++ (8 until 16).map(BigInt(_))
    simulate(new TessellationMappedAddressHarness) { dut =>
      dut.io.request.valid.poke(false.B); dut.io.result.ready.poke(false.B)
      dut.io.instruction.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for (width <- 1 to 4) {
        val bytes = Files.readAllBytes(Paths.get(directory, s"width$width.words"))
        assert(bytes.nonEmpty && bytes.length <= 2048 && bytes.length % 4 == 0)
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vr = Array.fill(32,16)(BigInt(0)); val xs = Array.fill(32)(BigInt(0))
        vr(20) = indices.toArray
        vr(28) = Array.tabulate(16)(i => BigInt(0x5a000000L+i))
        vr(22) = Array.tabulate(16)(i => BigInt(0x6b000000L+i))
        var predicate = BigInt(0)
        while (words.hasRemaining) {
          val word = words.getInt().toLong & 0xffffffffL
          val opcode = word & 127; val rd = ((word >> 7)&31).toInt
          val rs1 = ((word >> 15)&31).toInt; val rs2 = ((word >> 20)&31).toInt
          if (opcode == 0x37) {
            assert(rd == 10); xs(rd) = BigInt(word & 0xfffff000L)
          } else if (opcode == 0x13) {
            assert(rd == 10 && ((word >> 12)&7) == 0 && (rs1 == 0 || rs1 == 10))
            val immediate = (word.toInt >> 20).toLong
            xs(rd) = (xs(rs1) + immediate) & mask32
          } else {
            assert(opcode == 0x57)
            dut.io.instruction.poke(word.U); dut.io.legal.expect(true.B)
            val q=dut.io.request.bits
            q.warpId.poke(0.U); q.pc.poke(0.U); q.warpActiveMask.poke(65535.U)
            q.vd.poke(0.U); q.activeMask.poke(65535.U); q.predicateMask.poke(predicate.U)
            q.vl.poke(16.U); q.scalar.poke(xs(rs1).U); q.immediate.poke(0.U)
            q.vs1Field.poke(0.U); q.funct6.poke(0.U); q.operandType.poke(0.U)
            q.vm.poke(true.B); q.quad.poke(false.B); q.vxrm.poke(0.U)
            for (lane <- 0 until 16) {
              q.vs1(lane).poke(vr(rs1)(lane).U); q.vs2(lane).poke(vr(rs2)(lane).U)
              q.vs2Odd(lane).poke(0.U); q.oldVd(lane).poke(vr(rd)(lane).U)
            }
            dut.io.request.valid.poke(true.B)
            var cycles=0
            while(!dut.io.request.ready.peek().litToBoolean && cycles<20){dut.clock.step();cycles+=1}
            assert(cycles<20);dut.clock.step();dut.io.request.valid.poke(false.B)
            cycles=0
            while(!dut.io.result.valid.peek().litToBoolean && cycles<20){dut.clock.step();cycles+=1}
            assert(cycles<20)
            if(dut.io.result.bits.writesMask.peek().litToBoolean)predicate=dut.io.result.bits.mask.peek().litValue
            else for(lane <- 0 until 16)vr(rd)(lane)=dut.io.result.bits.data(lane).peek().litValue
            dut.io.result.ready.poke(true.B);dut.clock.step();dut.io.result.ready.poke(false.B)
          }
        }
        // Literal independent arithmetic, not another instruction interpreter.
        for(lane <- 0 until 16){
          val expected=if(indices(lane)<31)43+indices(lane)*width*8 else BigInt("80000001",16)
          assert(vr(29)(lane)==expected,s"width=$width index=${indices(lane)} actual=${vr(29)(lane)} expected=$expected")
          assert(vr(20)(lane)==indices(lane),"ADDR source clobbered")
          assert(vr(28)(lane)==BigInt(0x5a000000L+lane),"cached vertex clobbered")
          assert(vr(22)(lane)==BigInt(0x6b000000L+lane),"pending value clobbered")
        }
      }
    }
  }
}
