package opengpu.graphics

import chisel3._
import chisel3.util._

/** Map clip position followed by varying components to linked geometry output
  * slots. Invalid entries leave the corresponding downstream word zero.
  */
class GeometryPrimitiveOutputRequest(p: VertexShaderStageConfig) extends Bundle {
  val geometry = new GeometryOutputPrimitive
  val source = new AssembledPrimitive(p)
  val taskId = UInt(32.W)
  val sequence = UInt(64.W)
  val outputComponents = UInt(7.W)
  val builtinOutputMask = UInt(2.W)
  val varyingSlots = UInt(5.W)
  val flatMask = UInt((4*p.varyings).W)
  val componentSources = Vec(4 + 4*p.varyings, Valid(UInt(7.W)))
}
class GeometryMappedPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val primitive = new AssembledPrimitive(p)
  val inputPrimitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val layer = UInt(32.W)
}
class GeometryPrimitiveOutputFault extends Bundle {
  val taskId = UInt(32.W)
  val inputPrimitiveId = UInt(32.W)
}

/** Serial output mapping for a single primitive. The producer keeps geometry
  * output, its source metadata and link layout together until accepted.
  */
class GeometryPrimitiveOutput(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new GeometryPrimitiveOutputRequest(p)))
    val out = Decoupled(new GeometryMappedPrimitive(p))
    val fault = Decoupled(new GeometryPrimitiveOutputFault)
  })
  private val idle :: copy :: offer :: fault :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val request = Reg(new GeometryPrimitiveOutputRequest(p))
  private val output = Reg(new GeometryMappedPrimitive(p))
  private val vertex = RegInit(0.U(2.W))
  private val component = RegInit(0.U(7.W))
  private val varying = component >= 4.U
  private val flatIndex = (component - 4.U)(5,0)
  private val flat = varying && request.flatMask(flatIndex)
  private val sourceVertex = Mux(flat, request.geometry.vertexCount - 1.U, vertex)
  private val mapping = request.componentSources(component)
  private val mappingValid = !mapping.valid || (mapping.bits < request.outputComponents && mapping.bits < 64.U)
  private val value = Mux(mapping.valid, request.geometry.vertices(sourceVertex).components(mapping.bits(5,0)), 0.U)
  private val countValid = io.in.bits.geometry.vertexCount >= 1.U && io.in.bits.geometry.vertexCount <= 3.U
  private val validInput = countValid && io.in.bits.geometry.taskId === io.in.bits.taskId &&
    io.in.bits.geometry.primitiveId === io.in.bits.source.primitiveId && io.in.bits.varyingSlots <= p.varyings.U &&
    io.in.bits.outputComponents +& PopCount(io.in.bits.builtinOutputMask) <= 64.U
  io.in.ready := state === idle
  io.out.valid := state === offer; io.out.bits := output
  io.fault.valid := state === fault; io.fault.bits.taskId := request.taskId
  io.fault.bits.inputPrimitiveId := request.geometry.primitiveId
  when(io.in.fire) {
    request := io.in.bits; output := 0.U.asTypeOf(output)
    val g = io.in.bits.geometry; val source = io.in.bits.source
    val provoking = g.vertexCount - 1.U
    output.inputPrimitiveId := g.primitiveId; output.invocationId := g.invocationId
    output.layer := Mux(io.in.bits.builtinOutputMask(0), g.vertices(provoking).layer, 0.U)
    output.primitive.identity := source.identity; output.primitive.sequence := io.in.bits.sequence
    output.primitive.primitiveId := Mux(io.in.bits.builtinOutputMask(1), g.vertices(provoking).shaderPrimitiveId, 0.U)
    output.primitive.instanceId := source.instanceId; output.primitive.baseInstance := source.baseInstance
    output.primitive.vertexCount := g.vertexCount; output.primitive.provokingVertex := provoking
    output.primitive.kind := g.vertexCount - 1.U
    output.primitive.topology := MuxLookup(g.vertexCount, PrimitiveTopology.points.U)(Seq(
      2.U -> PrimitiveTopology.lines.U, 3.U -> PrimitiveTopology.triangles.U))
    output.primitive.varyingSlots := io.in.bits.varyingSlots; output.primitive.flatMask := io.in.bits.flatMask
    for (v <- 0 until 3) {
      output.primitive.vertices(v).identity := source.identity
      output.primitive.vertices(v).instanceId := source.instanceId
      output.primitive.vertices(v).baseInstance := source.baseInstance
    }
    vertex := 0.U; component := 0.U; state := Mux(validInput, copy, fault)
  }
  when(state === copy) {
    when(!mappingValid) { state := fault }
      .otherwise {
        when(component < 4.U) { output.primitive.vertices(vertex).position(component(1,0)) := value }
          .otherwise {
            for (v <- 0 until p.varyings; c <- 0 until 4) {
              when(component === (4 + 4*v+c).U) { output.primitive.vertices(vertex).varyings(v)(c) := value }
            }
          }
        when(component +& 1.U === (4.U +& (request.varyingSlots << 2))) {
          component := 0.U
          when(vertex +& 1.U === request.geometry.vertexCount) { state := offer }
            .otherwise { vertex := vertex + 1.U }
        }.otherwise { component := component + 1.U }
      }
  }
  when(io.out.fire || io.fault.fire) { state := idle }
}
