package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import chisel3.ActualDirection
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelCommand, KernelCommandResult}
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.graphics.tessellation._
import opengpu.testutil.GraphicsTestResolution

class FiveStageIsolineMesaTop extends Module {
  require(!GraphicsTestResolution.productQualification)
  val cfg = GpuConfig(lanes = 16, warps = 8, enableSharedVectorAtomics = true)
  val p = VertexShaderStageConfig(VertexInputFetchConfig(attributes = 16, bindings = 16), varyings = 16)
  val f = FragmentShaderConfig(varyings = 16, colors = 4)
  val engines = 4
  val payload = NativeShaderPayloadSizing.pool(owners = 5, ports = 12)
  require(
    payload.capacityBytes == 524288 && payload.maxPayloadBytes == 69632 && payload.owners == 5 && payload.ports == 12
  )
  val shared = SharedCuShaderConfig(2)
  val pins = TessellationSystemIsolineImagePipeline.payloadPins(
    engines,
    ownedVertexSource = true,
    nativeGeometryAvailable = true
  )
  require(pins == 34)
  val imageSlots = TessellationSystemIsolineImagePipeline.memoryTransactions(engines, 8, 65536, true, true)
  val hostSlots = 1 << log2Ceil(imageSlots + 4)
  val memorySlots = GpuSystem.totalMemoryTransactions(2, 16, hostSlots)
  require(imageSlots == 256 && hostSlots == 512 && memorySlots == 592)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task = Flipped(Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTask(cfg, p, 8)))
    val taskCompletion = Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTaskCompletion(cfg, p, 8))
    val end = Flipped(Decoupled(Bool()))
    val completion = Decoupled(new TessellationSystemIsolineGeometryVertexInputImageCompletion(cfg, p, 8))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(cfg, 64, memorySlots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, memorySlots)))
    val frameDrained = Input(Bool()); val context = Input(UInt(32.W)); val cancel = Input(Bool())
    val compute = Flipped(Decoupled(new KernelCommand(cfg, 8, imageAvailable = true)))
    val computeDone = Decoupled(new KernelCommandResult(8))
    val out = Vec(engines, Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone = Vec(engines, Decoupled(new TypedVertexBinImageTileCompletion))
    val primitiveDone = Vec(engines, Decoupled(new TypedReplayPrimitiveCompletion))
    val owned = Output(UInt(2.W)); val tags = Output(Vec(2, UInt(8.W)))
    val active = Output(Vec(2, UInt(8.W)))
    val writeback = Output(Vec(2, Valid(new opengpu.core.backend.register.ScalarRegisterWrite(cfg))))
    val launch = Output(Valid(new Bundle { val tag = UInt(8.W); val pc = UInt(32.W) }))
    val traps = Output(UInt(32.W)); val busy = Output(Bool()); val idle = Output(Bool())
    val poolReserved = Output(UInt(32.W)); val poolDrained = Output(Bool())
    val sourceLive = Output(Bool()); val sourceDrained = Output(Bool()); val taskDrained = Output(Bool())
    val fragmentEvents = Output(new FragmentPerformanceEvents)
  })
  private val stage = Module(
    new TessellationSystemIsolineVertexInputImage(
      config = cfg,
      p = p,
      f = f,
      shared = shared,
      g = GraphicsConfig(screenWidth = GraphicsTestResolution.width, screenHeight = GraphicsTestResolution.height),
      viewport = Fp32ViewportConfig(maxWidth = GraphicsTestResolution.width, maxHeight = GraphicsTestResolution.height),
      engines = engines,
      pageBytes = 4096,
      writeCredits = 8,
      tileSlots = 8,
      pagedCapacity = 65536,
      storage = TypedTileColorConfig(
        32768,
        parallelLanes = true,
        parallelDepth = true,
        nativeSamples = true,
        streamingWordPorts = 8,
        advancedBlend = true,
        nativeAdvancedDraws = true
      ),
      useSramBlackBoxes = false,
      payload = Some(payload),
      payloadOwner = 0,
      transformFeedback = false,
      externalImages = false,
      fragmentImageAvailable = true,
      sampleShading = true,
      tessellationUniforms = true,
      evaluationPatchAvailable = true,
      tessellationInfoAvailable = true,
      nativeGeometryAvailable = true,
      primitiveIdInput = false,
      allowEmptyStates = false
    )
  )
  val recordBytes = stage.recordBytes
  require(recordBytes == 1472)
  private val system = Module(
    new GpuSystem(
      cfg,
      numComputeUnits = 2,
      commandIdWidth = 8,
      transactionsPerCu = 16,
      graphicsHostTransactions = hostSlots,
      enableGraphicsKernels = true,
      enableFpuBackend = true,
      nativeExecutionProfile = true,
      shaderExecutionLimits = true,
      drainGraphicsPayloadWork = true,
      geometryAvailable = true,
      tessellationAvailable = true,
      imageAvailable = true,
      graphicsImageAvailable = true,
      evaluationPatchAvailable = true,
      tessellationInfoAvailable = true,
      shaderPayload = Some(payload),
      producerPayloadPins = pins
    )
  )
  private def inactive(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(inactive); case v: Vec[_] => v.foreach(inactive)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b := false.B
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s := 0.S
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u := 0.U
    case _                                                             => ()
  }
  inactive(system.io); inactive(stage.io)
  system.io.graphicsInstructionLimit.get := NativeShaderExecutionCapabilities.defaultQuota.U
  stage.io.begin <> io.begin; stage.io.geometryTask.get <> io.task
  io.taskCompletion <> stage.io.geometryTaskCompletion.get
  stage.io.end <> io.end; io.completion <> stage.io.geometryCompletion.get; stage.io.cancel := io.cancel
  io.out <> stage.io.out; io.tileDone <> stage.io.tileDone; io.primitiveDone <> stage.io.primitiveDone
  system.io.graphicsKernels.get <> stage.io.shared
  private val arb = Module(new RRArbiter(new ComputeMemoryRequest(cfg, 64, hostSlots), 2))
  arb.io.in(0).valid := stage.io.memoryRequest.valid; arb.io.in(0).bits := stage.io.memoryRequest.bits
  stage.io.memoryRequest.ready := arb.io.in(0).ready
  arb.io.in(1).valid := stage.io.sourceMemoryRequest.valid; arb.io.in(1).bits := stage.io.sourceMemoryRequest.bits
  arb.io.in(1).bits.transactionId := stage.io.sourceMemoryRequest.bits.transactionId + imageSlots.U
  stage.io.sourceMemoryRequest.ready := arb.io.in(1).ready
  private val queue = Module(new Queue(new ComputeMemoryRequest(cfg, 64, hostSlots), 2, pipe = true))
  queue.io.enq <> arb.io.out; system.io.graphicsHostRequest <> queue.io.deq
  private val response = system.io.graphicsHostResponse
  private val isSource = response.bits.transactionId >= imageSlots.U
  stage.io.memoryResponse.valid := response.valid && !isSource; stage.io.memoryResponse.bits := response.bits
  stage.io.sourceMemoryResponse.valid := response.valid && isSource; stage.io.sourceMemoryResponse.bits := response.bits
  stage.io.sourceMemoryResponse.bits.transactionId := response.bits.transactionId - imageSlots.U
  response.ready := Mux(isSource, stage.io.sourceMemoryResponse.ready, stage.io.memoryResponse.ready)
  private val sourcePending = RegInit(0.U(4.W))
  private val hostPending = RegInit(0.U(log2Ceil(hostSlots + 1).W))
  when(arb.io.in(1).fire =/= stage.io.sourceMemoryResponse.fire) {
    sourcePending := Mux(arb.io.in(1).fire, sourcePending + 1.U, sourcePending - 1.U)
  }
  when(arb.io.out.fire =/= response.fire) { hostPending := Mux(arb.io.out.fire, hostPending + 1.U, hostPending - 1.U) }
  when(stage.io.sourceMemoryResponse.fire) { assert(sourcePending =/= 0.U) }
  when(response.fire) { assert(hostPending =/= 0.U) }
  stage.io.sourceMemoryDrained := sourcePending === 0.U && !arb.io.in(1).valid
  stage.io.frameMemoryDrained := io.frameDrained && hostPending === 0.U && queue.io.count === 0.U && !arb.io.out.valid
  io.memoryRequest <> system.io.memoryRequest; system.io.memoryResponse <> io.memoryResponse
  system.io.command <> io.compute; io.computeDone <> system.io.commandCompletion
  system.io.graphicsDrained := !stage.io.busy && queue.io.count === 0.U && hostPending === 0.U
  system.io.graphicsShaderL1Invalidate.ready := true.B
  stage.io.geometryInvalidate.get.valid := false.B
  stage.io.geometryInvalidate.get.bits := 0.U.asTypeOf(stage.io.geometryInvalidate.get.bits)
  stage.io.geometryInvalidateDone.get.ready := true.B
  private val management = system.io.payloadManagement.get
  for (i <- 0 until 5) {
    val port = stage.io.payload.get(i)
    management.control(i) <> port.control; port.controlResponse <> management.controlResponse(i)
    management.request(i) <> port.request; port.response <> management.response(i)
    port.context := io.context; port.cancel := false.B
  }
  system.io.graphicsPayload.get := stage.io.poolPayload.get
  system.io.payloadProducerPins.get := stage.io.payloadPins.get
  io.poolReserved := management.reservedBytes; io.poolDrained := management.drained
  io.idle := system.io.vmContextIdle && !stage.io.busy
  io.busy := stage.io.busy; io.sourceLive := stage.io.sourceMemoryLive
  io.sourceDrained := stage.io.sourceMemoryDrained; io.taskDrained := stage.io.taskDrained
  io.owned := system.io.graphicsKernels.get.owned; io.tags := system.io.graphicsKernels.get.taskIds
  io.active := system.io.activeWarps; io.writeback := system.io.committedWriteback
  io.launch.valid := stage.io.shared.launch.fire
  io.launch.bits.tag := stage.io.shared.launch.bits.commandId
  io.launch.bits.pc := stage.io.shared.launch.bits.launch.kernelPc
  private val traps = RegInit(0.U(32.W))
  for (c <- 0 until 2) when(system.io.trap(c).valid) { traps := traps + 1.U }
  io.traps := traps; io.fragmentEvents := stage.io.fragmentEvents
}
