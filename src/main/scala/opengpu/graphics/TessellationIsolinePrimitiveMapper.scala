package opengpu.graphics

import chisel3._
import chisel3.util._

/** Link TES output words to clip position, varyings and optional point size.
  * The enclosing draw owner supplies unique downstream sequence/primitive IDs
  * and holds the patch's linked output layout with every accepted primitive.
  */
class IsolinePrimitiveMapRequest(p: VertexShaderStageConfig) extends Bundle {
  val evaluated = new EvaluatedIsolinePrimitive
  val identity = new GraphicsWorkIdentity
  val sequence = UInt(64.W); val primitiveId = UInt(32.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val outputComponents = UInt(7.W); val varyingSlots = UInt(5.W)
  val flatMask = UInt((4*p.varyings).W)
  val componentSources = Vec(4 + 4*p.varyings, Valid(UInt(6.W)))
  val pointSizeSource = Valid(UInt(6.W))
}
class IsolinePrimitiveMapFault extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W)
}
class TessellationIsolinePrimitiveMapper(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IsolinePrimitiveMapRequest(p)))
    val out = Decoupled(new AssembledPrimitive(p))
    val fault = Decoupled(new IsolinePrimitiveMapFault)
  })
  private val idle :: copy :: offer :: fault :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val request = Reg(new IsolinePrimitiveMapRequest(p))
  private val output = Reg(new AssembledPrimitive(p))
  private val vertex = RegInit(0.U(1.W)); private val component = RegInit(0.U(7.W))
  private val mapping = request.componentSources(component(log2Ceil(4 + 4*p.varyings)-1, 0))
  private val varying = component >= 4.U
  private val flatIndex = (component - 4.U)(log2Ceil(4*p.varyings)-1, 0)
  private val sourceVertex = Mux(varying && request.flatMask(flatIndex) && !request.evaluated.point, 1.U, vertex)
  private val value = Mux(mapping.valid, request.evaluated.vertices(sourceVertex)(mapping.bits), 0.U)
  private val mappingValid = !mapping.valid || mapping.bits < request.outputComponents
  private val inputValid = io.in.bits.outputComponents <= 64.U && io.in.bits.varyingSlots <= p.varyings.U &&
    !(io.in.bits.flatMask >> (io.in.bits.varyingSlots << 2)).orR &&
    (!io.in.bits.pointSizeSource.valid || io.in.bits.pointSizeSource.bits < io.in.bits.outputComponents)
  io.in.ready := state === idle
  io.out.valid := state === offer; io.out.bits := output
  io.fault.valid := state === fault; io.fault.bits.taskId := request.evaluated.taskId
  io.fault.bits.sequence := request.evaluated.sequence
  when(io.in.fire) {
    request := io.in.bits; output := 0.U.asTypeOf(output)
    output.identity := io.in.bits.identity; output.sequence := io.in.bits.sequence
    output.primitiveId := io.in.bits.primitiveId; output.instanceId := io.in.bits.instanceId
    output.baseInstance := io.in.bits.baseInstance
    output.topology := Mux(io.in.bits.evaluated.point, PrimitiveTopology.points.U, PrimitiveTopology.lines.U)
    output.kind := Mux(io.in.bits.evaluated.point, AssembledPrimitiveKind.point.U, AssembledPrimitiveKind.line.U)
    output.vertexCount := Mux(io.in.bits.evaluated.point, 1.U, 2.U)
    output.provokingVertex := Mux(io.in.bits.evaluated.point, 0.U, 1.U)
    output.varyingSlots := io.in.bits.varyingSlots; output.flatMask := io.in.bits.flatMask
    for (v <- 0 until 2) {
      when((v == 0).B || !io.in.bits.evaluated.point) {
        output.vertices(v).identity := io.in.bits.identity
        output.vertices(v).instanceId := io.in.bits.instanceId; output.vertices(v).baseInstance := io.in.bits.baseInstance
        output.vertices(v).pointSizeWritten := io.in.bits.pointSizeSource.valid
        output.vertices(v).pointSize := Mux(io.in.bits.pointSizeSource.valid,
          io.in.bits.evaluated.vertices(v)(io.in.bits.pointSizeSource.bits), 0.U)
      }
    }
    vertex := 0.U; component := 0.U; state := Mux(inputValid, copy, fault)
  }
  when(state === copy) {
    when(!mappingValid) { state := fault }.otherwise {
      when(component < 4.U) { output.vertices(vertex.pad(2)).position(component(1, 0)) := value }
        .otherwise {
          for (v <- 0 until p.varyings; c <- 0 until 4) {
            when(component === (4 + 4*v+c).U) { output.vertices(vertex.pad(2)).varyings(v)(c) := value }
          }
        }
      when(component +& 1.U === 4.U +& (request.varyingSlots << 2)) {
        component := 0.U
        when(request.evaluated.point || vertex === 1.U) { state := offer }
          .otherwise { vertex := 1.U }
      }.otherwise { component := component + 1.U }
    }
  }
  when(io.out.fire || io.fault.fire) { state := idle }
}
