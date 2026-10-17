package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.{SharedCuGraphicsPort, SharedCuShaderConfig}
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Top-level command-driven renderer.
  *
  * Wires CommandBufferStage (reads draw-call records from host memory) into
  * RenderPipeline (geometry viewport -> rasterize -> shade -> depth test ->
  * write colour/depth buffers).  The command buffer and the framebuffer use
  * two separate memory ports, mirroring a real integrated SoC where the
  * command ring and the render targets are distinct traffic.
  *
  * `fragCore` selects the shading backend (passed to `RenderPipeline`).  With
  * `fragCore = true` fragments are shaded by a compiled RV32 kernel launched on
  * the compute unit's SIMT warps (the phase-D unified-shader path); the
  * kernel's program/kernarg/output live in the line-based memory behind
  * `kernelMemReq/Resp` and `kernelWordMemReq/Resp`.  With `fragCore = false`
  * the fixed-function interpolated colour is used and those ports are idle.
  *
  * A `start` pulse makes the command stage read `cmdCount` records starting at
  * `cmdBase` and, for each, render one triangle; `done` goes high once every
  * record has been consumed and the last read-modify-write has retired.  In
  * vertex-core mode one record references a vertex buffer and may produce
  * multiple triangles.
  *
  * Persistent mode instead accepts tileJob and retains its immutable binding
  * through tileCompletion. done then reports controller idle, including result
  * delivery; legacy start stays low. Memory ports still use the caller's
  * translation/cache domain, whose ownership must outlive the tagged job.
  */
class RenderCore(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  graphicsScalarFpu: Boolean = true,
  sharedShader: Option[SharedCuShaderConfig] = None,
  persistentAttachments: Boolean = false,
  tileStoreCredits: Int = 1,
  useTileSramBlackBoxes: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    /** Persistent mode replaces legacy start/register submission with one
      * immutable, bounded command-list job and a tagged completion. */
    val tileJob = if (persistentAttachments) Some(Flipped(Decoupled(new TileCommandJob))) else None
    val tileCompletion = if (persistentAttachments) Some(Decoupled(new TileRenderPassCompletion)) else None
    val tileCancel = if (persistentAttachments) Some(Input(Bool())) else None
    /** Sidebands apply to a response accepted on the corresponding port. */
    val commandMemoryFault = if (persistentAttachments) Some(Input(Bool())) else None
    val attachmentMemoryFault = if (persistentAttachments) Some(Input(Bool())) else None
    val textureMemoryFault = if (persistentAttachments) Some(Input(Bool())) else None
    val tileEvents = if (persistentAttachments) Some(Output(new TileAttachmentEvents)) else None
    val shared = sharedShader.map(pool => Flipped(new SharedCuGraphicsPort(
      gpuConfig, pool.taskIdWidth, pool.numComputeUnits)))
    val cmdBase = Input(UInt(32.W))
    val cmdCount = Input(UInt(16.W))
    val start = Input(Bool())
    val cbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val fbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, SharedCuShaderConfig.kernelWordTransactions(sharedShader)))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, SharedCuShaderConfig.kernelWordTransactions(sharedShader))))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
    val shaderFault = Output(Bool())
    val colorFormat = Input(UInt(PixelFormat.width.W))
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    /** Texture sampling (forwarded to the fixed-function fragment stage). */
    val texEnable = Input(Bool())
    val texFormat = Input(UInt(PixelFormat.width.W))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val blendCfgEnable = Input(Bool())
    val blendSrcFactor = Input(UInt(4.W))
    val blendDstFactor = Input(UInt(4.W))
    val blendEquation = Input(UInt(3.W))
    val stencilTestEnable = Input(Bool())
    val stencilFunc = Input(UInt(3.W))
    val stencilRef = Input(UInt(8.W))
    val stencilReadMask = Input(UInt(8.W))
    val stencilWriteMask = Input(UInt(8.W))
    val stencilFailOp = Input(UInt(3.W))
    val stencilZFailOp = Input(UInt(3.W))
    val stencilZPassOp = Input(UInt(3.W))
    val cullMode = Input(UInt(2.W))
    /** bits[1:0] sample mode: 0 = 1x, 1 = 2x, 2 = 4x. */
    val sampleMode = Input(UInt(2.W))
    /** Active mode. Zero uses the elaborated maximum. */
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    /** Sampler word port (separate client in the fabric arbitration). */
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val performance = Output(new GraphicsPerformanceEvents)
    val done = Output(Bool())
  })

  // The command payload type must follow the selected front end.  In vertex
  // core mode words 0..6 describe a vertex buffer and vertex shader rather
  // than containing three inline clip-space vertices.
  private val cb = Module(new CommandBufferStage(config, vertCore, cancellable = persistentAttachments))
  private val rp = Module(new RenderPipeline(
    config, gpuConfig, fragCore, vertCore, graphicsScalarFpu, sharedShader,
    persistentAttachments, tileStoreCredits, useTileSramBlackBoxes))
  private val tileController = if (persistentAttachments) Some(Module(new TileCommandPassController)) else None
  private val jobConfig = tileController.map(_.io.config)
  sharedShader.foreach(_ => rp.io.shared.get <> io.shared.get)
  // RenderHost snapshots the legacy registers and queue ownership on the
  // same edge that it presents start.  Delay the parser start one cycle so
  // the selected command configuration is visible at the parser boundary.
  private val startDelay = RegInit(false.B)
  startDelay := io.start

  cb.io.base := jobConfig.map(_.cmdBase).getOrElse(io.cmdBase)
  cb.io.count := jobConfig.map(_.cmdCount).getOrElse(io.cmdCount)
  cb.io.start := tileController.map(_.io.startCommands).getOrElse(startDelay)
  cb.io.mem.req <> io.cbMem.req
  cb.io.mem.resp <> io.cbMem.resp

  rp.io.screenW := jobConfig.map(_.screenW).getOrElse(io.screenW)
  rp.io.screenH := jobConfig.map(_.screenH).getOrElse(io.screenH)

  if (config.tileBinning && vertCore) {
    cb.io.draw <> rp.io.draw
    rp.io.tileStart.get := startDelay
    rp.io.tileInputDone.get := cb.io.done
    io.done := cb.io.done && rp.io.done
  } else if (config.tileBinning) {
    val binner = Module(new TileDrawBinner(config))
    binner.io.screenW := io.screenW
    binner.io.screenH := io.screenH
    binner.io.start := startDelay
    cb.io.draw <> binner.io.drawIn
    binner.io.inputDone := cb.io.done
    binner.io.drawOut <> rp.io.draw
    binner.io.renderDrained := rp.io.drawDrained.get
    binner.io.passDone := rp.io.done
    rp.io.tilePassEnd.get := binner.io.passEnd
    io.done := cb.io.done && binner.io.done && rp.io.done
  } else {
    cb.io.draw <> rp.io.draw
    io.done := cb.io.done && rp.io.done
  }
  tileController.foreach { controller =>
    val record = cb.io.draw.bits.asInstanceOf[SceneTriangle]
    val submitted = rp.io.draw.bits.asInstanceOf[SceneTriangle]
    when(!record.stateOverride) {
      // Materialize the complete job default state as a per-draw snapshot.
      // This includes blendEnable and LOD clamps, which have no legacy
      // register input on RenderCore. Explicit record overrides keep priority.
      submitted.stateOverride := true.B
      for ((name, field) <- controller.io.config.elements if record.elements.contains(name))
        submitted.elements(name) := field
    }
    controller.io.job <> io.tileJob.get
    io.tileCompletion.get <> controller.io.completion
    controller.io.cancel := io.tileCancel.get
    controller.io.commandFault := io.commandMemoryFault.get && io.cbMem.resp.fire
    controller.io.attachmentFault := io.attachmentMemoryFault.get && io.fbMem.resp.fire
    controller.io.textureFault := io.textureMemoryFault.get && io.texMem.resp.fire
    controller.io.shaderFault := rp.io.shaderFault
    controller.io.pass <> rp.io.tilePass.get
    controller.io.admitted := rp.io.tileEvents.get.admitted
    controller.io.commandsDrained := cb.io.done
    controller.io.renderDrained := rp.io.done
    cb.io.cancel.get := controller.io.cancelCommands
    io.tileEvents.get := rp.io.tileEvents.get
    // Ownership, including a backpressured tagged completion, is retained
    // until the explicit job completion handshake in persistent mode.
    io.done := !controller.io.busy
    assert(!io.start, "persistent RenderCore uses tileJob rather than legacy start")
  }
  rp.io.instructionSatp := io.instructionSatp
  rp.io.instructionTlbFlush := io.instructionTlbFlush
  rp.io.vectorSatp := io.vectorSatp
  rp.io.vectorTlbFlush := io.vectorTlbFlush
  io.shaderFault := rp.io.shaderFault

  rp.io.colorFormat := jobConfig.map(_.colorFormat).getOrElse(io.colorFormat)
  rp.io.colorBase := jobConfig.map(_.colorBase).getOrElse(io.colorBase)
  rp.io.depthBase := jobConfig.map(_.depthBase).getOrElse(io.depthBase)
  rp.io.stride := jobConfig.map(_.stride).getOrElse(io.stride)
  rp.io.depthTestEnable := jobConfig.map(_.depthTestEnable).getOrElse(io.depthTestEnable)
  rp.io.depthFunc := jobConfig.map(_.depthFunc).getOrElse(io.depthFunc)
  rp.io.depthWriteEnable := jobConfig.map(_.depthWriteEnable).getOrElse(io.depthWriteEnable)
  rp.io.blendCfgEnable := jobConfig.map(_.blendCfgEnable).getOrElse(io.blendCfgEnable)
  rp.io.blendSrcFactor := jobConfig.map(_.blendSrcFactor).getOrElse(io.blendSrcFactor)
  rp.io.blendDstFactor := jobConfig.map(_.blendDstFactor).getOrElse(io.blendDstFactor)
  rp.io.blendEquation := jobConfig.map(_.blendEquation).getOrElse(io.blendEquation)
  rp.io.stencilTestEnable := jobConfig.map(_.stencilTestEnable).getOrElse(io.stencilTestEnable)
  rp.io.stencilFunc := jobConfig.map(_.stencilFunc).getOrElse(io.stencilFunc)
  rp.io.stencilRef := jobConfig.map(_.stencilRef).getOrElse(io.stencilRef)
  rp.io.stencilReadMask := jobConfig.map(_.stencilReadMask).getOrElse(io.stencilReadMask)
  rp.io.stencilWriteMask := jobConfig.map(_.stencilWriteMask).getOrElse(io.stencilWriteMask)
  rp.io.stencilFailOp := jobConfig.map(_.stencilFailOp).getOrElse(io.stencilFailOp)
  rp.io.stencilZFailOp := jobConfig.map(_.stencilZFailOp).getOrElse(io.stencilZFailOp)
  rp.io.stencilZPassOp := jobConfig.map(_.stencilZPassOp).getOrElse(io.stencilZPassOp)
  rp.io.cullMode := jobConfig.map(_.cullMode).getOrElse(io.cullMode)
  io.performance := rp.io.performance
  rp.io.sampleMode := jobConfig.map(_.sampleMode).getOrElse(io.sampleMode)
  rp.io.texEnable := jobConfig.map(_.texEnable).getOrElse(io.texEnable)
  rp.io.texFormat := jobConfig.map(_.texFormat).getOrElse(io.texFormat)
  rp.io.texBase := jobConfig.map(_.texBase).getOrElse(io.texBase)
  rp.io.texWidth := jobConfig.map(_.texWidth).getOrElse(io.texWidth)
  rp.io.texHeight := jobConfig.map(_.texHeight).getOrElse(io.texHeight)
  rp.io.texWrapClamp := jobConfig.map(_.texWrapClamp).getOrElse(io.texWrapClamp)
  rp.io.texMaxLevel := jobConfig.map(_.texMaxLevel).getOrElse(io.texMaxLevel)
  rp.io.mem.req <> io.fbMem.req
  rp.io.mem.resp <> io.fbMem.resp
  rp.io.texMem <> io.texMem

  io.kernelMemReq <> rp.io.kernelMemReq
  rp.io.kernelMemResp <> io.kernelMemResp
  io.kernelWordMemReq <> rp.io.kernelWordMemReq
  rp.io.kernelWordMemResp <> io.kernelWordMemResp
  rp.io.kernelL1Invalidate <> io.kernelL1Invalidate
  io.kernelL1InvalidateDone <> rp.io.kernelL1InvalidateDone
  io.kernelGlobalAtomicRequest <> rp.io.kernelGlobalAtomicRequest
  rp.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse

}
