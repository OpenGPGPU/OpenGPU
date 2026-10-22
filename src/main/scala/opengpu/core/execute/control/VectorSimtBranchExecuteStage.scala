package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.VectorIssuedInstruction

/** Custom-2 B-type comparisons use vector register indices and the SIMT mask.
  * RVV VL, v0 and the B-immediate's bit25 do not qualify a branch lane.
  */
class VectorSimtBranchExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorIssuedInstruction(config)))
    val out = Decoupled(new SimtBranchRequest(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
  })
  private val valid = RegInit(false.B)
  private val result = Reg(new SimtBranchRequest(config))
  private val instruction = io.in.bits.decode.instruction
  private val op = instruction(14, 12)
  private val immediate = Cat(Fill(config.xLen - 13, instruction(31)),
    instruction(31), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W))
  private val predicates = Wire(Vec(config.lanes, Bool()))
  for (lane <- 0 until config.lanes) {
    val a = io.in.bits.vs1Data(lane)
    val b = io.in.bits.vs2Data(lane)
    predicates(lane) := MuxLookup(op, false.B)(Seq(
      0.U -> (a === b), 1.U -> (a =/= b),
      4.U -> (a.asSInt < b.asSInt), 5.U -> (a.asSInt >= b.asSInt),
      6.U -> (a < b), 7.U -> (a >= b)))
  }
  private val clearSaved = io.clear.valid && io.clear.bits === result.warpId
  private val clearInput = io.clear.valid && io.clear.bits === io.in.bits.decode.warpId
  private val canLoad = !valid || io.out.ready || clearSaved
  io.in.ready := canLoad && !clearInput
  io.out.valid := valid && !clearSaved
  io.out.bits := result
  when(canLoad) {
    valid := io.in.valid && !clearInput
    when(io.in.fire) {
      assert(instruction(6, 0) === "h5b".U && op =/= 2.U && op =/= 3.U,
        "SIMT branch stage accepts only the six decoded B-type comparisons")
      result.warpId := io.in.bits.decode.warpId
      result.pc := io.in.bits.decode.pc
      result.targetPc := io.in.bits.decode.pc + immediate
      result.fallthroughPc := io.in.bits.decode.pc + 4.U
      result.reconvergePc := 0.U // supplied by the explicit shared-stack scope
      result.activeMask := io.in.bits.decode.activeMask
      result.takenMask := predicates.asUInt & io.in.bits.decode.activeMask
    }
  }
}
