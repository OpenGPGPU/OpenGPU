package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._

class GeometryFragmentDrawStreamResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(3.W)
  val source = new PrimitiveAssemblyCompletion
  val inputPrimitives = UInt(64.W)
  val geometryPrimitives = UInt(64.W)
  val outputQuads = UInt(64.W)
}

/** Serial draw ownership around the one-primitive geometry/fragment pipeline.
  * The producer retains a primitive until accepted and reports its end only
  * after its output stream drains. The child retains offered quads through
  * cancellation and reports completion after its own output drains.
  */
class GeometryFragmentDrawStream(config: GpuConfig, p: FragmentShaderConfig,
    taskIdWidth: Int, g: GraphicsConfig = GraphicsConfig(),
    payload: Option[ShaderPayloadConfig] = None) extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new GeometryFragmentDraw(config,p,taskIdWidth,payload)))
    val primitive = Flipped(Decoupled(new AssembledPrimitive(p.vertices)))
    val inputEnd = Flipped(Decoupled(new PrimitiveAssemblyCompletion))
    val childDraw = Decoupled(new GeometryFragmentDraw(config,p,taskIdWidth,payload))
    val childOut = Flipped(Decoupled(new FragmentShaderQuad(p,g)))
    val childCompletion = Flipped(Decoupled(new GeometryFragmentCompletion))
    val childCancel = Output(Bool())
    val sourceCancel = Output(Bool())
    val out = Decoupled(new FragmentShaderQuad(p,g))
    val completion = Decoupled(new GeometryFragmentDrawStreamResult)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val active = RegInit(false.B)
  private val done = RegInit(false.B)
  private val pending = RegInit(false.B)
  private val childActive = RegInit(false.B)
  private val endSeen = RegInit(false.B)
  private val template = Reg(new GeometryFragmentDraw(config,p,taskIdWidth,payload))
  private val offer = Reg(new GeometryFragmentDraw(config,p,taskIdWidth,payload))
  private val source = Reg(new PrimitiveAssemblyCompletion)
  private val result = Reg(new GeometryFragmentDrawStreamResult)
  private val status = RegInit(0.U(3.W))
  private val inputCount = RegInit(0.U(64.W))
  private val geometryCount = RegInit(0.U(64.W))
  private val quadCount = RegInit(0.U(64.W))
  private val geometrySequence = RegInit(0.U(64.W))

  io.begin.ready := !active && !done && !io.cancel
  io.busy := active || done
  when(io.begin.fire) {
    active := true.B; pending := false.B; childActive := false.B; endSeen := false.B
    template := io.begin.bits; status := 0.U; inputCount := 0.U; geometryCount := 0.U; quadCount := 0.U
    geometrySequence := io.begin.bits.raster.geometry.geometry.primitive.firstSequence
  }

  private val stop = status =/= 0.U || io.cancel || (endSeen && source.status =/= 0.U)
  io.sourceCancel := active && stop
  io.primitive.ready := active && !endSeen && !pending && !childActive
  when(io.primitive.fire) {
    when(inputCount.andR) { status := 1.U }.otherwise { inputCount := inputCount + 1.U }
    when(!stop) {
      offer := template
      offer.raster.geometry.geometry.primitive.input.primitive := io.primitive.bits
      offer.raster.geometry.geometry.primitive.firstSequence := geometrySequence
      pending := true.B
    }
  }
  // A submitted offer remains valid even if cancellation arrives before the
  // child accepts it; cancellation is forwarded after that acceptance.
  io.childDraw.valid := pending
  io.childDraw.bits := offer
  when(io.childDraw.fire) { pending := false.B; childActive := true.B }
  io.childCancel := active && childActive && stop

  io.inputEnd.ready := active && !endSeen
  when(io.inputEnd.fire) { source := io.inputEnd.bits; endSeen := true.B }
  when(active && io.cancel) { status := 4.U }

  io.out.valid := active && childActive && io.childOut.valid
  io.out.bits := io.childOut.bits
  io.out.bits.sequence := quadCount
  io.childOut.ready := active && childActive && io.out.ready
  when(io.out.fire) {
    when(quadCount.andR) { status := 1.U }.otherwise { quadCount := quadCount + 1.U }
  }

  io.childCompletion.ready := active && childActive
  when(io.childCompletion.fire) {
    childActive := false.B
    val produced = io.childCompletion.bits.raster.source.source.geometry.producedPrimitives
    val total = geometryCount +& produced
    when(total(64)) { status := 1.U }.otherwise { geometryCount := total(63,0) }
    when(io.childCompletion.bits.status =/= 0.U && status === 0.U && !io.cancel &&
      !(endSeen && source.status =/= 0.U) && !(io.inputEnd.fire && io.inputEnd.bits.status =/= 0.U)) {
      status := io.childCompletion.bits.status
    }
    when(io.childCompletion.bits.status === 0.U) {
      geometrySequence := io.childCompletion.bits.raster.source.source.geometry.nextSequence
    }
  }

  when(active && endSeen && !pending && !childActive) {
    result.identity := template.fragment.identity
    result.source := source
    result.inputPrimitives := inputCount
    result.geometryPrimitives := geometryCount
    result.outputQuads := quadCount
    result.status := Mux(io.cancel,4.U,Mux(status =/= 0.U,status,
      Mux(source.status =/= 0.U,source.status,
        Mux(source.identity.asUInt =/= template.fragment.identity.asUInt || source.primitives =/= inputCount,1.U,0.U))))
    active := false.B; done := true.B
  }
  io.completion.valid := done
  io.completion.bits := result
  when(io.completion.fire) { done := false.B }
}
