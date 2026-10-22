package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SimtSyncDecodeSpec extends AnyFlatSpec {
  it should "decode the exact scope instruction words and preserve branch and join encodings" in {
    simulate(new FullInstructionDecoder) { d =>
      for((word,offset)<-Seq("0200305b"->BigInt(32),"8000305b"->BigInt("fffff000",16),
          "7e003fdb"->BigInt(4094),"0000315b"->BigInt(2),"0000305b"->BigInt(0),
          "fe003edb"->BigInt("fffffffc",16))) {
        d.io.instruction.poke(BigInt(word,16).U)
        d.io.decoded.legal.expect(true.B);d.io.decoded.executionType.expect(ExecutionType.system)
        d.io.decoded.scalar.simtSync.expect(true.B)
        d.io.decoded.scalar.immediate.expect(offset.U)
        d.io.decoded.scalar.useRs1.expect(false.B);d.io.decoded.scalar.useRs2.expect(false.B)
        d.io.decoded.scalar.writeRd.expect(false.B);d.io.decoded.vector.valid.expect(false.B)
      }
      for(word<-Seq("0200b05b","0210305b")) {
        d.io.instruction.poke(BigInt(word,16).U)
        d.io.decoded.legal.expect(false.B);d.io.decoded.illegalInstruction.expect(true.B)
      }
      for(op<-Seq(0,1,4,5,6,7)) {
        d.io.instruction.poke((BigInt("0220805b",16)|(BigInt(op)<<12)).U)
        d.io.decoded.executionType.expect(ExecutionType.vector)
        d.io.decoded.vector.unit.expect(VectorUnit.branch)
        d.io.decoded.vector.vm.expect(true.B)
      }
      for(word<-Seq("0000205b","ffffa05b")) {
        d.io.instruction.poke(BigInt(word,16).U)
        d.io.decoded.legal.expect(true.B);d.io.decoded.scalar.join.expect(true.B)
        d.io.decoded.scalar.simtSync.expect(false.B)
      }
    }
  }
  it should "reject scopes when the execution profile disables vector SIMT" in {
    simulate(new FullInstructionDecoder(GpuConfig(enableVector=false))) { d =>
      d.io.instruction.poke("h0200305b".U)
      d.io.decoded.legal.expect(false.B);d.io.decoded.illegalInstruction.expect(true.B)
    }
  }
}
