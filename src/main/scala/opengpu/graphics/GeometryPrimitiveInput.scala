package opengpu.graphics

import chisel3._
import chisel3.util._

/** Component indices 0..3 address clip position; subsequent indices address
  * the four components of each varying. The link supplies only active inputs.
  */
class GeometryPrimitiveInputRequest(p: VertexShaderStageConfig) extends Bundle {
  val primitive = new AssembledPrimitive(p)
  val taskId = UInt(32.W)
  val invocations = UInt(6.W)
  val outputTopology = UInt(2.W)
  val maxVertices = UInt(9.W)
  val outputComponents = UInt(7.W)
  val builtinOutputMask = UInt(2.W)
  val inputComponents = UInt(7.W)
  val componentSources = Vec(64, UInt(7.W))
}
class GeometryPrimitiveInputFault extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
}

/** Serializes an assembled point/line/triangle into the linked geometry input
  * layout. The caller must retain primitive order and drain any offered task
  * before releasing or aborting the enclosing draw.
  */
class GeometryPrimitiveInput(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new GeometryPrimitiveInputRequest(p)))
    val task = Decoupled(new GeometryInvocationTask)
    val fault = Decoupled(new GeometryPrimitiveInputFault)
  })
  private val idle :: copy :: offer :: fault :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val request = Reg(new GeometryPrimitiveInputRequest(p))
  private val task = Reg(new GeometryInvocationTask)
  private val vertex = RegInit(0.U(2.W))
  private val component = RegInit(0.U(6.W))
  private val source = request.componentSources(component)
  private val fields = Wire(Vec(4 + 4*p.varyings, UInt(32.W)))
  for (i <- 0 until 4) fields(i) := request.primitive.vertices(vertex).position(i)
  for (i <- 0 until 4*p.varyings) fields(4+i) := request.primitive.vertices(vertex).varyings(i/4)(i%4)
  private val sourceValid = source < (4.U + (request.primitive.varyingSlots << 2)) && source < fields.length.U
  private val countValid = (io.in.bits.primitive.kind === AssembledPrimitiveKind.point.U && io.in.bits.primitive.vertexCount === 1.U) ||
    (io.in.bits.primitive.kind === AssembledPrimitiveKind.line.U && io.in.bits.primitive.vertexCount === 2.U) ||
    (io.in.bits.primitive.kind === AssembledPrimitiveKind.triangle.U && io.in.bits.primitive.vertexCount === 3.U)
  private val inputValid = countValid && io.in.bits.primitive.varyingSlots <= p.varyings.U && io.in.bits.inputComponents <= 64.U
  io.in.ready := state === idle
  io.task.valid := state === offer; io.task.bits := task
  io.fault.valid := state === fault; io.fault.bits.taskId := request.taskId
  io.fault.bits.primitiveId := request.primitive.primitiveId
  when(io.in.fire) {
    request := io.in.bits; task := 0.U.asTypeOf(task)
    task.taskId := io.in.bits.taskId; task.primitiveId := io.in.bits.primitive.primitiveId
    task.inputVertices := io.in.bits.primitive.vertexCount; task.inputComponents := io.in.bits.inputComponents
    task.invocations := io.in.bits.invocations; task.topology := io.in.bits.outputTopology
    task.maxVertices := io.in.bits.maxVertices; task.outputComponents := io.in.bits.outputComponents; task.builtinOutputMask := io.in.bits.builtinOutputMask
    vertex := 0.U; component := 0.U
    state := Mux(!inputValid, fault, Mux(io.in.bits.inputComponents === 0.U, offer, copy))
  }
  when(state === copy) {
    when(!sourceValid) { state := fault }
      .otherwise {
        task.vertices(vertex.pad(3)).components(component) := fields(source)
        when(component +& 1.U === request.inputComponents) {
          component := 0.U
          when(vertex +& 1.U === request.primitive.vertexCount) { state := offer }
            .otherwise { vertex := vertex + 1.U }
        }.otherwise { component := component + 1.U }
      }
  }
  when(io.task.fire || io.fault.fire) { state := idle }
}
