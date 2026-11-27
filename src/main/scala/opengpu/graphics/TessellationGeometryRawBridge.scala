package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.graphics.tessellation._

/** Original linked TES-packed component -> dense GS input component. This is compiler semantics, not
  * executable/resource permission or patch contents.
  */
class TessellationGeometryRawMap extends Bundle {
  val valid = Bool(); val component = UInt(32.W)
}
class TessellationGeometryRawBinding(p: VertexShaderStageConfig) extends Bundle {
  val version = UInt(8.W); val domain = UInt(2.W)
  val identity = new GraphicsWorkIdentity
  val expectedTesTaskId = UInt(32.W); val expectedTesSequence = UInt(16.W)
  val tesLanguagePatchId = UInt(32.W)
  val gsTaskId = UInt(32.W); val gsInputPrimitiveId = UInt(32.W)
  val inputSequence = UInt(64.W); val firstOutputSequence = UInt(64.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val tesOutputComponents = UInt(7.W)
  val program = new NativeGeometryProgram
  val evaluationComponents = Vec(64, new TessellationGeometryRawMap)
}

/** Internal common packet. The three public adapters take the actual domain evaluation bundles and only pad their
  * sequence/vertex inventory with zero.
  */
class TessellationGeometryRawRequest(p: VertexShaderStageConfig) extends Bundle {
  val link = new TessellationGeometryRawBinding(p)
  val tesTaskId = UInt(32.W); val tesSequence = UInt(16.W); val point = Bool()
  val vertices = Vec(3, Vec(64, UInt(32.W)))
}
class TessellationGeometryRawPacket(p: VertexShaderStageConfig) extends Bundle {
  val request = new NativeGeometryPrimitiveRequest(p)
  val tesTaskId = UInt(32.W); val tesSequence = UInt(16.W); val tesLanguagePatchId = UInt(32.W)
  val firstOutputSequence = UInt(64.W)
}
class TessellationGeometryRawFault extends Bundle {
  val tesTaskId = UInt(32.W); val tesSequence = UInt(16.W)
  val gsTaskId = UInt(32.W); val gsInputPrimitiveId = UInt(32.W)

  /** 1 malformed tuple/mapping, 4 cancelled. This is not a GS worker report. */
  val status = UInt(3.W)
}

/** Per-primitive pre-flat/pre-clip scatter into the original GNG input semantic inventory. No code, uniform data, patch
  * value or GS result is generated here. Downstream takes ownership on out.fire.
  *
  * Cancellation before offer returns a held local fault. Once offered, valid and bits stay stable; cancelPending
  * announces the owed offer. Its consumer must still accept/discard or own-and-cancel that packet, then drain its own
  * credits. This bridge never claims downstream execution has retired.
  */
class TessellationGeometryRawBridge(p: VertexShaderStageConfig = VertexShaderStageConfig(), domain: Int = 0)
    extends Module {
  require(domain >= 0 && domain <= 2)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationGeometryRawRequest(p)))
    val out = Decoupled(new TessellationGeometryRawPacket(p))
    val fault = Decoupled(new TessellationGeometryRawFault)
    val cancel = Input(Bool()); val cancelPending = Output(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, validate, copy, offer, fault = Value }
  private val phase = RegInit(State.idle)
  private val saved = Reg(new TessellationGeometryRawRequest(p))
  private val result = Reg(new TessellationGeometryRawPacket(p))
  private val fault = Reg(new TessellationGeometryRawFault)
  private val component = RegInit(0.U(6.W)); private val vertex = RegInit(0.U(2.W))
  private val claimed = RegInit(VecInit(Seq.fill(68)(false.B)))
  private val origin = Reg(Vec(68, UInt(6.W)))
  private val cancelled = RegInit(false.B)
  private val b = saved.link; private val g = b.program
  private val count = Mux(saved.point, 1.U, (if (domain == 0) 2 else 3).U)
  private def report(code: UInt): Unit = {
    fault.tesTaskId := saved.tesTaskId; fault.tesSequence := saved.tesSequence
    fault.gsTaskId := b.gsTaskId; fault.gsInputPrimitiveId := b.gsInputPrimitiveId
    fault.status := code; phase := State.fault
  }
  io.in.ready := phase === State.idle
  io.out.valid := phase === State.offer; io.out.bits := result
  io.fault.valid := phase === State.fault; io.fault.bits := fault
  io.busy := phase =/= State.idle
  io.cancelPending := phase === State.offer && (cancelled || io.cancel)
  when(io.in.fire) {
    saved := io.in.bits; result := 0.U.asTypeOf(result); fault := 0.U.asTypeOf(fault)
    component := 0.U; vertex := 0.U; claimed.foreach(_ := false.B); origin.foreach(_ := 0.U)
    cancelled := io.cancel; phase := State.validate
  }
  when(phase =/= State.idle && io.cancel) { cancelled := true.B }
  private val active = component < g.inputComponents
  private val mapping = b.evaluationComponents(component)
  private val raw = g.inputSources(component)
  private val mapLegal = Mux(
    active,
    mapping.valid && mapping.component < 64.U &&
      mapping.component < b.tesOutputComponents,
    !mapping.valid && mapping.component === 0.U
  )
  private val overlapLegal = !active || raw >= 68.U || !claimed(raw) || origin(raw) === mapping.component
  private val headerLegal = b.version === 1.U && b.domain === domain.U &&
    saved.tesTaskId === b.expectedTesTaskId && saved.tesSequence === b.expectedTesSequence &&
    b.tesOutputComponents >= 1.U && b.tesOutputComponents <= 64.U &&
    b.identity.asUInt === g.identity.asUInt && g.inputVertices === count &&
    NativeGeometryProgramLayout.legal(g, p)
  when(phase === State.validate) {
    when(!headerLegal || !mapLegal || !overlapLegal) { report(1.U) }
      .otherwise {
        when(active) { claimed(raw) := true.B; origin(raw) := mapping.component(5, 0) }
        when(component === 63.U) {
          when(cancelled || io.cancel) { report(4.U) }.otherwise {
            result.request.program := g; result.request.taskId := b.gsTaskId
            result.tesTaskId := saved.tesTaskId; result.tesSequence := saved.tesSequence
            result.tesLanguagePatchId := b.tesLanguagePatchId; result.firstOutputSequence := b.firstOutputSequence
            val primitive = result.request.primitive
            primitive.identity := b.identity; primitive.sequence := b.inputSequence
            primitive.primitiveId := b.gsInputPrimitiveId; primitive.instanceId := b.instanceId;
            primitive.baseInstance := b.baseInstance
            primitive.topology := Mux(saved.point, 0.U, (if (domain == 0) 1 else 4).U)
            primitive.kind := Mux(saved.point, 0.U, (if (domain == 0) 1 else 2).U)
            primitive.vertexCount := count; primitive.varyingSlots := g.vertexVaryingSlots
            for (i <- 0 until 3) {
              when(i.U < count) {
                primitive.vertices(i).identity := b.identity
                primitive.vertices(i).instanceId := b.instanceId; primitive.vertices(i).baseInstance := b.baseInstance
              }
            }
            component := 0.U; vertex := 0.U
            phase := Mux(g.inputComponents === 0.U, State.offer, State.copy)
          }
        }.otherwise { component := component + 1.U }
      }
  }
  when(phase === State.copy) {
    when(cancelled || io.cancel) { report(4.U) }.otherwise {
      val value = saved.vertices(vertex)(mapping.component(5, 0))
      when(raw < 4.U) { result.request.primitive.vertices(vertex.pad(3)).position(raw(1, 0)) := value }
        .otherwise {
          result.request.primitive.vertices(vertex.pad(3)).varyings((raw - 4.U)(5, 2))((raw - 4.U)(1, 0)) := value
        }
      when(component +& 1.U === g.inputComponents) {
        component := 0.U
        when(vertex +& 1.U === count) { phase := State.offer }.otherwise { vertex := vertex + 1.U }
      }.otherwise { component := component + 1.U }
    }
  }
  when(io.out.fire) {
    when(cancelled || io.cancel) { report(4.U) }.otherwise { phase := State.idle }
  }
  when(io.fault.fire) { phase := State.idle; cancelled := false.B }
}

class TessellationIsolineGeometryRawRequest(p: VertexShaderStageConfig) extends Bundle {
  val link = new TessellationGeometryRawBinding(p); val evaluated = new EvaluatedIsolinePrimitive
}
class TessellationTriangleGeometryRawRequest(p: VertexShaderStageConfig) extends Bundle {
  val link = new TessellationGeometryRawBinding(p); val evaluated = new EvaluatedTrianglePrimitive
}
class TessellationQuadGeometryRawRequest(p: VertexShaderStageConfig) extends Bundle {
  val link = new TessellationGeometryRawBinding(p); val evaluated = new EvaluatedQuadPrimitive
}

/** No conversion of data words: only the real domain bundle is normalized. */
class TessellationIsolineGeometryRawBridge(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationIsolineGeometryRawRequest(p)))
    val out = Decoupled(new TessellationGeometryRawPacket(p)); val fault = Decoupled(new TessellationGeometryRawFault)
    val cancel = Input(Bool()); val cancelPending = Output(Bool()); val busy = Output(Bool())
  })
  private val core = Module(new TessellationGeometryRawBridge(p, 0))
  core.io.in.valid := io.in.valid; io.in.ready := core.io.in.ready
  core.io.in.bits := 0.U.asTypeOf(core.io.in.bits); core.io.in.bits.link := io.in.bits.link
  core.io.in.bits.tesTaskId := io.in.bits.evaluated.taskId; core.io.in.bits.tesSequence := io.in.bits.evaluated.sequence
  core.io.in.bits.point := io.in.bits.evaluated.point
  for (i <- 0 until 2) core.io.in.bits.vertices(i) := io.in.bits.evaluated.vertices(i)
  io.out <> core.io.out; io.fault <> core.io.fault; core.io.cancel := io.cancel
  io.cancelPending := core.io.cancelPending; io.busy := core.io.busy
}
class TessellationTriangleGeometryRawBridge(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationTriangleGeometryRawRequest(p)))
    val out = Decoupled(new TessellationGeometryRawPacket(p)); val fault = Decoupled(new TessellationGeometryRawFault)
    val cancel = Input(Bool()); val cancelPending = Output(Bool()); val busy = Output(Bool())
  })
  private val core = Module(new TessellationGeometryRawBridge(p, 1))
  core.io.in.valid := io.in.valid; io.in.ready := core.io.in.ready
  core.io.in.bits := 0.U.asTypeOf(core.io.in.bits); core.io.in.bits.link := io.in.bits.link
  core.io.in.bits.tesTaskId := io.in.bits.evaluated.taskId; core.io.in.bits.tesSequence := io.in.bits.evaluated.sequence
  core.io.in.bits.point := io.in.bits.evaluated.point; core.io.in.bits.vertices := io.in.bits.evaluated.vertices
  io.out <> core.io.out; io.fault <> core.io.fault; core.io.cancel := io.cancel
  io.cancelPending := core.io.cancelPending; io.busy := core.io.busy
}
class TessellationQuadGeometryRawBridge(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationQuadGeometryRawRequest(p)))
    val out = Decoupled(new TessellationGeometryRawPacket(p)); val fault = Decoupled(new TessellationGeometryRawFault)
    val cancel = Input(Bool()); val cancelPending = Output(Bool()); val busy = Output(Bool())
  })
  private val core = Module(new TessellationGeometryRawBridge(p, 2))
  core.io.in.valid := io.in.valid; io.in.ready := core.io.in.ready
  core.io.in.bits := 0.U.asTypeOf(core.io.in.bits); core.io.in.bits.link := io.in.bits.link
  core.io.in.bits.tesTaskId := io.in.bits.evaluated.taskId; core.io.in.bits.tesSequence := io.in.bits.evaluated.sequence
  core.io.in.bits.point := io.in.bits.evaluated.point; core.io.in.bits.vertices := io.in.bits.evaluated.vertices
  io.out <> core.io.out; io.fault <> core.io.fault; core.io.cancel := io.cancel
  io.cancelPending := core.io.cancelPending; io.busy := core.io.busy
}
