package opengpu.graphics

import chisel3._
import chisel3.util._

class IsolineEvaluationRequest extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W); val vertex = UInt(1.W)
  val tessCoord = Vec(3, UInt(32.W))
}
class IsolineEvaluationResponse extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W); val vertex = UInt(1.W)
  val failed = Bool(); val components = Vec(64, UInt(32.W))
}
class EvaluatedIsolinePrimitive extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W); val point = Bool()
  val vertices = Vec(2, Vec(64, UInt(32.W)))
}
class IsolineEvaluationFault extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W)
  /** 1: response tag mismatch; 2: shader execution failed. */
  val status = UInt(2.W)
}

/** Own a generated point or line while an external evaluation executor runs.
  * Requests are strictly serial, and each accepted request must receive one
  * response, including after an enclosing cancellation. The enclosing owner
  * drains this module before releasing patch storage or shader resources.
  */
class TessellationIsolineEvaluation extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationIsolinePrimitive))
    val request = Decoupled(new IsolineEvaluationRequest)
    val response = Flipped(Decoupled(new IsolineEvaluationResponse))
    val out = Decoupled(new EvaluatedIsolinePrimitive)
    val fault = Decoupled(new IsolineEvaluationFault)
    val busy = Output(Bool())
  })
  private val idle :: request :: response :: output :: fault :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val primitive = Reg(new TessellationIsolinePrimitive)
  private val result = Reg(new EvaluatedIsolinePrimitive)
  private val vertex = RegInit(0.U(1.W))
  private val faultStatus = Reg(UInt(2.W))
  private def toFloat(q: UInt): UInt = {
    val shift = PriorityEncoder(Reverse(q))
    val normalized = (q << shift)(24, 0)
    Mux(q === 0.U, 0.U(32.W), Cat(0.U(1.W), (127.U(8.W) - shift)(7, 0), normalized(23, 1)))
  }
  io.busy := state =/= idle
  io.in.ready := state === idle
  io.request.valid := state === request
  io.request.bits.taskId := primitive.taskId; io.request.bits.sequence := primitive.sequence
  io.request.bits.vertex := vertex
  io.request.bits.tessCoord(0) := toFloat(primitive.vertices(vertex).u)
  io.request.bits.tessCoord(1) := toFloat(primitive.vertices(vertex).v)
  io.request.bits.tessCoord(2) := 0.U
  io.response.ready := state === response
  io.out.valid := state === output; io.out.bits := result
  io.fault.valid := state === fault
  io.fault.bits.taskId := primitive.taskId; io.fault.bits.sequence := primitive.sequence; io.fault.bits.status := faultStatus
  when(io.in.fire) {
    primitive := io.in.bits; result := 0.U.asTypeOf(result)
    result.taskId := io.in.bits.taskId; result.sequence := io.in.bits.sequence; result.point := io.in.bits.point
    vertex := 0.U; state := request
  }
  when(io.request.fire) { state := response }
  when(io.response.fire) {
    when(io.response.bits.taskId =/= primitive.taskId || io.response.bits.sequence =/= primitive.sequence || io.response.bits.vertex =/= vertex) {
      faultStatus := 1.U; state := fault
    }.elsewhen(io.response.bits.failed) { faultStatus := 2.U; state := fault }
      .otherwise {
        result.vertices(vertex) := io.response.bits.components
        when(primitive.point || vertex === 1.U) { state := output }
          .otherwise { vertex := 1.U; state := request }
      }
  }
  when(io.out.fire || io.fault.fire) { state := idle }
}
