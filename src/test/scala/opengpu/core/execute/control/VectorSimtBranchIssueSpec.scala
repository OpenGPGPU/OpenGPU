package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.VectorIssueStage
import opengpu.core.backend.register.VectorRegisterWrite
import opengpu.core.frontend.decode._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class VectorSimtBranchIssueHarness(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val instruction = Flipped(Decoupled(UInt(32.W)))
    val initialize = Flipped(Valid(new VectorRegisterWrite(config)))
    val out = Decoupled(new SimtBranchRequest(config))
    val legal = Output(Bool())
    val unit = Output(VectorUnit())
    val unmasked = Output(Bool())
  })
  val decoder = Module(new FullInstructionDecoder(config))
  val issue = Module(new VectorIssueStage(config))
  val execute = Module(new VectorSimtBranchExecuteStage(config))
  decoder.io.instruction := io.instruction.bits
  io.legal := decoder.io.decoded.legal
  io.unit := decoder.io.decoded.vector.unit
  io.unmasked := decoder.io.decoded.vector.vm
  val branch = decoder.io.decoded.executionType === ExecutionType.vector &&
    decoder.io.decoded.vector.unit === VectorUnit.branch
  issue.io.in.valid := io.instruction.valid && branch
  io.instruction.ready := issue.io.in.ready && branch
  issue.io.in.bits := 0.U.asTypeOf(issue.io.in.bits)
  issue.io.in.bits.instruction := io.instruction.bits
  issue.io.in.bits.pc := "h1000".U
  issue.io.in.bits.activeMask := ((BigInt(1) << config.lanes) - 1).U
  issue.io.in.bits.decoded := decoder.io.decoded.vector
  issue.io.scalarRs1Data := "hdeadbeef".U
  issue.io.scalarRs2Data := "hdeadbeef".U
  issue.io.scalarFpData := 0.U
  issue.io.scalarFpBusy := 0.U.asTypeOf(issue.io.scalarFpBusy)
  issue.io.vectorWriteback := io.initialize
  issue.io.cancel := 0.U.asTypeOf(issue.io.cancel)
  execute.io.in <> issue.io.out
  execute.io.clear := 0.U.asTypeOf(execute.io.clear)
  io.out <> execute.io.out
}

class VectorSimtBranchIssueSpec extends AnyFlatSpec {
  private def encoding(op: Int, offset: Int): BigInt = {
    val n = offset & 8191
    (BigInt((n >> 12) & 1) << 31) | (BigInt((n >> 5) & 63) << 25) |
      (BigInt(2) << 20) | (BigInt(1) << 15) | (BigInt(op) << 12) |
      (BigInt((n >> 1) & 15) << 8) | (BigInt((n >> 11) & 1) << 7) | 0x5b
  }
  it should "fetch actual vector operands for all six encoded branches without RVV mask or scalar aliases" in {
    simulate(new VectorSimtBranchIssueHarness(GpuConfig(lanes=4, warps=2))) { d =>
      d.io.instruction.valid.poke(false.B); d.io.instruction.bits.poke(0.U)
      d.io.initialize.valid.poke(false.B)
      d.io.initialize.bits.poke(0.U.asTypeOf(d.io.initialize.bits))
      d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val a = Seq(BigInt(0),BigInt("ffffffff",16),BigInt("80000000",16),BigInt(7))
      val b = Seq(BigInt(0),BigInt(1),BigInt("7fffffff",16),BigInt(3))
      for ((values,reg) <- Seq((Seq.fill(4)(BigInt(0)),0),(a,1),(b,2))) {
        d.io.initialize.valid.poke(true.B)
        d.io.initialize.bits.warpId.poke(0.U); d.io.initialize.bits.vd.poke(reg.U)
        for (i <- 0 until 4) d.io.initialize.bits.data(i).poke(values(i).U)
        d.clock.step(); d.io.initialize.valid.poke(false.B)
      }
      for (op <- Seq(0,1,4,5,6,7); offset <- Seq(-64,4,32)) {
        d.io.instruction.bits.poke(encoding(op,offset).U)
        d.io.instruction.valid.poke(true.B)
        var n=0
        while (!d.io.instruction.ready.peek().litToBoolean && n<40) { d.clock.step(); n+=1 }
        assert(n<40); d.io.legal.expect(true.B); d.io.unit.expect(VectorUnit.branch); d.io.unmasked.expect(true.B)
        d.clock.step(); d.io.instruction.valid.poke(false.B)
        n=0
        while (!d.io.out.valid.peek().litToBoolean && n<40) { d.clock.step(); n+=1 }
        assert(n<40)
        val taken=a.zip(b).zipWithIndex.foldLeft(0) { case(mask,((x,y),i)) =>
          val yes=op match {
            case 0 => x==y; case 1 => x!=y
            case 4 => x.toInt<y.toInt; case 5 => x.toInt>=y.toInt
            case 6 => x<y; case 7 => x>=y
          }
          if(yes) mask | (1<<i) else mask
        }
        for (_ <- 0 until 4) {
          d.io.out.bits.takenMask.expect(taken.U)
          d.io.out.bits.activeMask.expect(15.U)
          d.io.out.bits.targetPc.expect((0x1000+offset).U)
          d.clock.step()
        }
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      info("SIMT_VECTOR_ISSUE encodedBranches=18 actualVectorRF=true v0Zero=true bit25Immediate=true scalarAliasesIgnored=true heldOutput=true")
    }
  }
  it should "reject vector branches when vector execution is disabled" in {
    simulate(new FullInstructionDecoder(GpuConfig(enableVector=false))) { d =>
      for(op <- Seq(0,1,4,5,6,7)) {
        d.io.instruction.poke(encoding(op,32).U)
        d.io.decoded.legal.expect(false.B)
        d.io.decoded.illegalInstruction.expect(true.B)
      }
    }
  }
}
