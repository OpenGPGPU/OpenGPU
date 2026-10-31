package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

/** Draw-level composition of the primitive stream and geometry/fragment CU client. */
class GeometryFragmentDrawPipeline(config: GpuConfig,p: FragmentShaderConfig,shared: SharedCuShaderConfig,
    g: GraphicsConfig = GraphicsConfig(),viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean = false,payload: Option[ShaderPayloadConfig] = None,payloadOwner: Int = 0,
    interpolationContexts: Int = 8) extends Module {
  private val idWidth = shared.taskIdWidth
  private val cus = shared.numComputeUnits
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new GeometryFragmentDraw(config,p,idWidth,payload)))
    val primitive = Flipped(Decoupled(new AssembledPrimitive(p.vertices)))
    val inputEnd = Flipped(Decoupled(new PrimitiveAssemblyCompletion))
    val out = Decoupled(new FragmentShaderQuad(p,g))
    val completion = Decoupled(new GeometryFragmentDrawStreamResult)
    val shared = Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true))
    val geometryTexture = Vec(cus,new SharedCuTexturePort(config))
    val geometryTrap = Decoupled(new TaggedGraphicsTrap(config,idWidth,cus))
    val geometryInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val payloadPort = payload.map(q=>new FragmentPayloadPort(config,q))
    val poolPayload = payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins = payload.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val performance = Output(new FragmentPerformanceEvents)
    val cancel = Input(Bool()); val sourceCancel = Output(Bool()); val busy = Output(Bool())
  })
  private val stream = Module(new GeometryFragmentDrawStream(config,p,idWidth,g,payload))
  private val pipeline = Module(new GeometryFragmentPipeline(config,p,shared,g,viewport,useSramBlackBoxes,
    payload,payloadOwner,interpolationContexts))
  stream.io.begin <> io.begin; stream.io.primitive <> io.primitive; stream.io.inputEnd <> io.inputEnd
  pipeline.io.draw <> stream.io.childDraw; stream.io.childOut <> pipeline.io.out
  stream.io.childCompletion <> pipeline.io.completion; pipeline.io.cancel := stream.io.childCancel
  stream.io.cancel := io.cancel; io.sourceCancel := stream.io.sourceCancel
  io.out <> stream.io.out; io.completion <> stream.io.completion; io.busy := stream.io.busy
  io.shared <> pipeline.io.shared; pipeline.io.geometryTexture <> io.geometryTexture
  io.geometryTrap <> pipeline.io.geometryTrap
  pipeline.io.geometryInvalidate <> io.geometryInvalidate; io.geometryInvalidateDone <> pipeline.io.geometryInvalidateDone
  io.memoryRequest <> pipeline.io.memoryRequest; pipeline.io.memoryResponse <> io.memoryResponse
  io.performance := pipeline.io.performance
  payload.foreach { _ =>
    io.payloadPort.get <> pipeline.io.payloadPort.get
    io.poolPayload.get := pipeline.io.poolPayload.get; io.payloadPins.get := pipeline.io.payloadPins.get
  }
}
