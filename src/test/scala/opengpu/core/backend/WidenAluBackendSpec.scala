package opengpu.core.backend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.{DecodePipe, DecodeRequest}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** DecodePipe plus VectorBackend with a one-cycle memory responder. */
private class WidenHarness(config: GpuConfig) extends Module {
  val decode = Module(new DecodePipe(config))
  val backend = Module(new VectorBackend(config))
  val io = IO(new Bundle {
    val fetch = Flipped(Decoupled(new DecodeRequest(config)))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val redirect = Output(Bool())
    val writebackValid = Output(Bool())
    val writebackVd = Output(UInt(5.W))
    val writebackData0 = Output(UInt(config.xLen.W))
    val memValid = Output(Bool())
    val memStore = Output(Bool())
    val memAddr0 = Output(UInt(config.xLen.W))
    val memData0 = Output(UInt(config.xLen.W))
    val unimplemented = Output(Bool())
  })

  decode.io.in <> io.fetch
  decode.io.scalarOut.ready := true.B
  decode.io.fpuOut.ready := true.B
  backend.io.in <> decode.io.vectorOut
  backend.io.scalarRs1Data := io.scalarRs1Data
  backend.io.scalarRs2Data := 0.U
  backend.io.scalarFpData := 0.U
  backend.io.scalarFpBusy := VecInit(Seq.fill(config.warps)(0.U(32.W)))
  backend.io.initialize.valid := false.B
  backend.io.initialize.bits := 0.U.asTypeOf(backend.io.initialize.bits)
  backend.io.scalarWriteback.ready := true.B
  backend.io.redirect.ready := true.B
  backend.io.scalarFlagsWrite.valid := false.B
  backend.io.scalarFlagsWrite.bits := 0.U.asTypeOf(backend.io.scalarFlagsWrite.bits)
  backend.io.shaderCsrWrite.valid := false.B
  backend.io.shaderCsrWrite.bits := 0.U.asTypeOf(backend.io.shaderCsrWrite.bits)
  backend.io.clearWarp.valid := false.B
  backend.io.clearWarp.bits := 0.U
  backend.io.scalarReserve.ready := true.B
  backend.io.fpReserve.ready := true.B
  backend.io.fpWriteback.ready := true.B
  backend.io.memoryFault.ready := true.B
  backend.io.texSample.ready := true.B
  backend.io.texCommit.valid := false.B
  backend.io.texCommit.bits := 0.U.asTypeOf(backend.io.texCommit.bits)
  backend.io.unimplemented.ready := true.B

  private val respond = RegNext(backend.io.memoryRequest.fire, false.B)
  private val responseData = Reg(UInt(config.xLen.W))
  when(backend.io.memoryRequest.fire) {
    responseData := Mux(
      backend.io.memoryRequest.bits.addresses(0) === 0x1000.U,
      3.U,
      5.U
    )
  }
  backend.io.memoryRequest.ready := !respond
  backend.io.memoryResponse.valid := respond
  backend.io.memoryResponse.bits.faultMask := 0.U
  backend.io.memoryResponse.bits.pageFault := false.B
  backend.io.memoryResponse.bits.readData :=
    VecInit(Seq.fill(config.lanes)(responseData))

  io.redirect := backend.io.redirect.valid
  io.writebackValid := backend.io.committedVectorWriteback.valid
  io.writebackVd := backend.io.committedVectorWriteback.bits.vd
  io.writebackData0 := backend.io.committedVectorWriteback.bits.data(0)
  io.memValid := backend.io.memoryRequest.valid
  io.memStore := backend.io.memoryRequest.bits.isStore
  io.memAddr0 := backend.io.memoryRequest.bits.addresses(0)
  io.memData0 := backend.io.memoryRequest.bits.writeData(0)
  io.unimplemented := backend.io.unimplemented.valid
}

class WidenAluBackendSpec extends AnyFlatSpec {
  behavior of "widen_alu through VectorBackend"

  it should "retire vwadd, vwsub and vwmul and store 8, -2 and 15" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val program = Seq(
      BigInt("c100f057", 16),
      BigInt("0200e107", 16),
      BigInt("0202e187", 16),
      BigInt("c2218257", 16),
      BigInt("c62182d7", 16),
      BigInt("ca218357", 16),
      BigInt("0202e227", 16),
      BigInt("0202e2a7", 16),
      BigInt("0202e327", 16)
    )
    // The scalar addi instructions are not in this pipe. Present the address
    // they would have written: x1 = 0x1000, t0 = 0x1004 then 0x1008/0x100c/0x1010.
    val addresses = Map(
      BigInt("0200e107", 16) -> BigInt(0x1000),
      BigInt("0202e187", 16) -> BigInt(0x1004),
      BigInt("0202e227", 16) -> BigInt(0x1008),
      BigInt("0202e2a7", 16) -> BigInt(0x100c),
      BigInt("0202e327", 16) -> BigInt(0x1010)
    )
    simulate(new WidenHarness(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.fetch.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(0.U)
      dut.clock.step()
      dut.reset.poke(false.B)

      val stores = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      val writes = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt)]
      program.zipWithIndex.foreach { case (word, index) =>
        dut.io.scalarRs1Data.poke(addresses.getOrElse(word, BigInt(0x1000)).U)
        dut.io.fetch.valid.poke(true.B)
        dut.io.fetch.bits.instruction.poke(word.U)
        dut.io.fetch.bits.pc.poke((index * 4).U)
        dut.io.fetch.bits.warpId.poke(0.U)
        dut.io.fetch.bits.activeMask.poke(1.U)
        dut.io.fetch.bits.instructionAccessFault.poke(false.B)
        var spins = 0
        while (!dut.io.fetch.ready.peek().litToBoolean && spins < 200) {
          if (dut.io.writebackValid.peek().litToBoolean)
            writes += ((
              dut.io.writebackVd.peek().litValue.toInt,
              dut.io.writebackData0.peek().litValue
            ))
          if (dut.io.memValid.peek().litToBoolean && dut.io.memStore.peek().litToBoolean)
            stores += ((
              dut.io.memAddr0.peek().litValue,
              dut.io.memData0.peek().litValue
            ))
          dut.clock.step()
          spins += 1
        }
        assert(dut.io.fetch.ready.peek().litToBoolean,
          f"insn $index%02d ${word}%08x not accepted; writes=$writes stores=$stores unimpl=${dut.io.unimplemented.peek().litToBoolean}")
        dut.clock.step()
        dut.io.fetch.valid.poke(false.B)
        spins = 0
        var retired = false
        while (!retired && spins < 200) {
          if (dut.io.redirect.peek().litToBoolean) retired = true
          if (dut.io.writebackValid.peek().litToBoolean)
            writes += ((
              dut.io.writebackVd.peek().litValue.toInt,
              dut.io.writebackData0.peek().litValue
            ))
          if (dut.io.memValid.peek().litToBoolean && dut.io.memStore.peek().litToBoolean)
            stores += ((
              dut.io.memAddr0.peek().litValue,
              dut.io.memData0.peek().litValue
            ))
          dut.clock.step()
          spins += 1
        }
        assert(retired,
          f"insn $index%02d ${word}%08x did not retire; writes=$writes stores=$stores")
      }
      assert(writes.exists { case (vd, data) => vd == 4 && data == 8 },
        s"vwadd did not write 8: $writes")
      assert(writes.exists { case (vd, data) => vd == 5 && data == BigInt("fffffffe", 16) },
        s"vwsub did not write -2: $writes")
      assert(writes.exists { case (vd, data) => vd == 6 && data == 15 },
        s"vwmul did not write 15: $writes")
      assert(stores.contains((BigInt(0x1008), BigInt(8))), s"missing add store: $stores")
      assert(stores.contains((BigInt(0x100c), BigInt("fffffffe", 16))), s"missing sub store: $stores")
      assert(stores.contains((BigInt(0x1010), BigInt(15))), s"missing mul store: $stores")
    }
  }
}
