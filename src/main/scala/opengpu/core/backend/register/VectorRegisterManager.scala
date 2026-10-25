package opengpu.core.backend.register

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.scoreboard.{
  VectorRegisterReservation,
  VectorRegisterScoreboard
}

class VectorIssueOperands(config: GpuConfig) extends Bundle {
  val request = new VectorRegisterReservation(config)
  val vs1Data = Vec(config.lanes, UInt(config.xLen.W))
  val vs2Data = Vec(config.lanes, UInt(config.xLen.W))
  val vs2OddData = Vec(config.lanes, UInt(config.xLen.W))
  val oldVdData = Vec(config.lanes, UInt(config.xLen.W))
  val predicateMask = UInt(config.lanes.W)
}

/** Elastic vector RF/scoreboard issue boundary with a synchronous SRAM response stage. */
class VectorRegisterManager(
  config: GpuConfig = GpuConfig(),
  useBlackBox: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new VectorRegisterReservation(config)))
    val issue = Decoupled(new VectorIssueOperands(config))
    val writeback = Flipped(Valid(new VectorRegisterWrite(config)))
    val cancel = Flipped(Valid(new opengpu.core.backend.scoreboard.VectorRegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registerFile = Module(new VectorRegisterFile(config, useBlackBox, synchronousReads = true))
  private val scoreboard = Module(new VectorRegisterScoreboard(config))
  private val requestValid = RegInit(false.B)
  private val requestBits = Reg(new VectorRegisterReservation(config))
  private val issueValid = RegInit(false.B)
  private val issueBits = Reg(new VectorIssueOperands(config))
  private val canLoadIssue = !issueValid || io.issue.ready

  scoreboard.io.reserve.bits := requestBits
  scoreboard.io.release.valid := io.writeback.valid
  scoreboard.io.release.bits.warpId := io.writeback.bits.warpId
  scoreboard.io.release.bits.vd := io.writeback.bits.vd
  scoreboard.io.cancel := io.cancel

  registerFile.io.read.warpId := requestBits.warpId
  registerFile.io.read.vs1 := requestBits.vs1
  registerFile.io.read.vs2 := requestBits.vs2
  registerFile.io.read.vs2Odd := (requestBits.vs2 + 1.U)(4, 0)
  registerFile.io.read.vd := requestBits.vd
  registerFile.io.write := io.writeback

  io.issue.valid := issueValid
  io.issue.bits := issueBits

  val readValid = RegInit(false.B)
  val readBits = Reg(new VectorRegisterReservation(config))
  val readSaved = RegInit(false.B)
  val readVs1 = Reg(Vec(config.lanes, UInt(config.xLen.W)))
  val readVs2 = Reg(Vec(config.lanes, UInt(config.xLen.W)))
  val readVs2Odd = Reg(Vec(config.lanes, UInt(config.xLen.W)))
  val readVd = Reg(Vec(config.lanes, UInt(config.xLen.W)))
  val readMask = Reg(UInt(config.lanes.W))
  val moveRead = readValid && canLoadIssue
  val canLoadRead = !readValid || moveRead
  // Each warp-local macro has one physical read/write port. A write to a
  // different warp can overlap the read; a same-warp write takes priority.
  val writeConflict = io.writeback.valid &&
    io.writeback.bits.warpId === requestBits.warpId
  scoreboard.io.reserve.valid := requestValid && canLoadRead && !writeConflict
  val launchRead = scoreboard.io.reserve.fire
  io.request.ready := !requestValid || launchRead

  when(io.request.fire) {
    requestValid := true.B
    requestBits := io.request.bits
  }.elsewhen(launchRead) {
    requestValid := false.B
  }

  when(launchRead) {
    readValid := true.B
    readBits := requestBits
    readSaved := false.B
  }.elsewhen(moveRead) {
    readValid := false.B
  }
  // The macro output changes on later read clocks, even while issue is
  // stalled. Save its first response together with its original owner.
  when(readValid && !moveRead && !readSaved) {
    readSaved := true.B
    readVs1 := registerFile.io.vs1Data
    readVs2 := registerFile.io.vs2Data
    readVs2Odd := registerFile.io.vs2OddData
    readVd := registerFile.io.oldVdData
    readMask := registerFile.io.predicateMask
  }
  when(canLoadIssue) {
    issueValid := readValid
    when(readValid) {
      issueBits.request := readBits
      issueBits.vs1Data := Mux(readSaved, readVs1, registerFile.io.vs1Data)
      issueBits.vs2Data := Mux(readSaved, readVs2, registerFile.io.vs2Data)
      issueBits.vs2OddData := Mux(readSaved, readVs2Odd, registerFile.io.vs2OddData)
      issueBits.oldVdData := Mux(readSaved, readVd, registerFile.io.oldVdData)
      issueBits.predicateMask := Mux(readSaved, readMask, registerFile.io.predicateMask)
    }
  }

  io.rawHazard := scoreboard.io.rawHazard
  io.wawHazard := scoreboard.io.wawHazard
}
