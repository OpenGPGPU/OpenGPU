package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, ShaderPayloadBinding, VectorTlbFlush}
import opengpu.graphics._

object TessellationJointThreeDomainJobImage {
  private def domainTransactions(engines: Int, writeCredits: Int, pagedCapacity: Int,
      tessellationUniforms: Boolean): Int =
    TessellationInputMemoryFabric.transactions(
      TessellationSystemIsolineImagePipeline.memoryTransactions(engines, writeCredits, pagedCapacity,
        tessellationUniforms))

  def transactions(engines: Int, writeCredits: Int, pagedCapacity: Int,
      tessellationUniforms: Boolean = false): Int = {
    val ids = domainTransactions(engines, writeCredits, pagedCapacity, tessellationUniforms)
    TessellationThreeDomainMemoryFabric.transactions(ids, ids, ids)
  }

  def payloadPins(engines: Int): Int =
    3 * TessellationSystemIsolineImagePipeline.payloadPins(engines, ownedVertexSource = true) +
      3 * (math.max(2, engines + 1) + 1)
}

/** Execute one protected GNQ request across the three domain parents and one
  * physical image bank. The submitter retains the authorized descriptor graph,
  * translations and allocations through the held completion and physical drain.
  */
class TessellationJointThreeDomainJobImage(
    config: GpuConfig,
    vertex: VertexShaderStageConfig,
    fragment: FragmentShaderConfig,
    shared: SharedCuShaderConfig,
    graphics: GraphicsConfig,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    engines: Int = 4,
    pageBytes: Int = 4096,
    writeCredits: Int = 8,
    tileSlots: Int = 8,
    pagedCapacity: Int = 65536,
    storage: TypedTileColorConfig = TypedTileColorConfig(),
    useSramBlackBoxes: Boolean = false,
    payload: Option[ShaderPayloadConfig] = None,
    payloadOwner: Int = 0,
    transformFeedback: Boolean = false,
    fragmentImageAvailable: Boolean = false,
    sampleShading: Boolean = false,
    tessellationUniforms: Boolean = false,
    evaluationPatchAvailable: Boolean = false,
    tessellationInfoAvailable: Boolean = false,
    primitiveIdInput: Boolean = false,
    enabled: Boolean = false
) extends Module {
  import TessellationJointThreeDomainJobImage._

  require(engines >= 1 && engines <= 4)
  require(payload.forall(_.owners == engines + 1))
  private val idWidth = shared.taskIdWidth
  private val cus = shared.numComputeUnits
  private val tasks = math.max(2, engines + 1)
  private val ids = domainTransactions(engines, writeCredits, pagedCapacity, tessellationUniforms)
  private val slots = transactions(engines, writeCredits, pagedCapacity, tessellationUniforms)
  private val payloadConfig = payload
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointJobRequest(idWidth)))
    val completion = Decoupled(new TessellationJointNativeCompletion(idWidth))
    val out = Vec(engines, Decoupled(new TypedTilePixelQuadCompletion))
    val shared = Flipped(new SharedCuGraphicsPort(config, idWidth, cus, geometryAvailable = true,
      tessellationAvailable = true, imageAvailable = fragmentImageAvailable,
      evaluationPatchAvailable = evaluationPatchAvailable, tessellationInfoAvailable = tessellationInfoAvailable))
    val payload = payloadConfig.map(p => Vec(engines + 1, new FragmentPayloadPort(config, p)))
    val poolPayload = payloadConfig.map(p => Output(Valid(new ShaderPayloadBinding(config, p))))
    val payloadPins = payloadConfig.map(p => Output(Vec(TessellationJointThreeDomainJobImage.payloadPins(engines),
      Valid(new ShaderPayloadPin(p)))))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val feedback = Option.when(transformFeedback)(new TransformFeedbackPipelineIO(config))
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val fragmentEvents = Output(new FragmentPerformanceEvents)
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val drained = Output(Bool())
  })

  private val isoline = Module(new TessellationSystemIsolineJointVertexImage(config, vertex, fragment, shared, graphics,
    viewport, engines, pageBytes, writeCredits, tileSlots, pagedCapacity, storage, useSramBlackBoxes, payload,
    payloadOwner, transformFeedback, externalImages = true, fragmentImageAvailable = fragmentImageAvailable,
    sampleShading = sampleShading, tessellationUniforms = tessellationUniforms,
    evaluationPatchAvailable = evaluationPatchAvailable, tessellationInfoAvailable = tessellationInfoAvailable,
    primitiveIdInput = primitiveIdInput, enabled = enabled))
  private val triangle = Module(new TessellationSystemTriangleJointVertexImage(config, vertex, fragment, shared, graphics,
    viewport, engines, pageBytes, writeCredits, tileSlots, pagedCapacity, storage, useSramBlackBoxes, payload,
    payloadOwner, transformFeedback, externalImages = true, fragmentImageAvailable = fragmentImageAvailable,
    sampleShading = sampleShading, tessellationUniforms = tessellationUniforms,
    evaluationPatchAvailable = evaluationPatchAvailable, tessellationInfoAvailable = tessellationInfoAvailable,
    primitiveIdInput = primitiveIdInput, enabled = enabled))
  private val quad = Module(new TessellationSystemQuadJointVertexImage(config, vertex, fragment, shared, graphics,
    viewport, engines, pageBytes, writeCredits, tileSlots, pagedCapacity, storage, useSramBlackBoxes, payload,
    payloadOwner, transformFeedback, externalImages = true, fragmentImageAvailable = fragmentImageAvailable,
    sampleShading = sampleShading, tessellationUniforms = tessellationUniforms,
    evaluationPatchAvailable = evaluationPatchAvailable, tessellationInfoAvailable = tessellationInfoAvailable,
    primitiveIdInput = primitiveIdInput, enabled = enabled))
  require(Seq(isoline.memoryTransactions, triangle.memoryTransactions, quad.memoryTransactions).forall(_ == ids))
  private val domains = Seq(isoline.io, triangle.io, quad.io)

  private val imageOwner = Module(new TessellationImageBankOwner)
  private val imageMux = Module(new TessellationImageBankMux(config, vertex, shared, engines, payload,
    fragmentImageAvailable))
  private val images = Seq.tabulate(engines)(engine => Module(new TypedReplayTileImagePipeline(
    config, vertex, fragment, shared, graphics, storage, engine = engine,
    useSramBlackBoxes = useSramBlackBoxes, payload = payload, payloadOwner = payloadOwner + engine,
    fragmentImageAvailable = fragmentImageAvailable, sampleShading = sampleShading, primitiveIdInput = primitiveIdInput,
    allowEmptyStates = true)))
  for ((port, index) <- domains.zipWithIndex) imageMux.io.clients(index) <> port.images.get
  for (index <- images.indices) imageMux.io.bank(index) <> images(index).io
  imageMux.io.active := imageOwner.io.active
  imageMux.io.domain := imageOwner.io.domain
  private val imagesDrained = !images.map(_.io.busy).reduce(_ || _)
  imageOwner.io.drained := imagesDrained
  imageOwner.io.retired := io.completion.fire && imageOwner.io.active

  private val memory = Module(new TessellationThreeDomainMemoryFabric(config, ids, ids, ids))
  memory.io.isolineRequest <> isoline.io.memoryRequest
  isoline.io.memoryResponse <> memory.io.isolineResponse
  memory.io.triangleRequest <> triangle.io.memoryRequest
  triangle.io.memoryResponse <> memory.io.triangleResponse
  memory.io.quadRequest <> quad.io.memoryRequest
  quad.io.memoryResponse <> memory.io.quadResponse
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  private val shaders = Module(new SharedShaderTaskRouter(config, cus, 3, idWidth, tasks, payload,
    geometryAvailable = true, tessellationAvailable = true, imageAvailable = fragmentImageAvailable,
    evaluationPatchAvailable = evaluationPatchAvailable, tessellationInfoAvailable = tessellationInfoAvailable))
  io.shared <> shaders.io.pool

  private object Phase extends ChiselEnum {
    val idle, select, switching, prepare, offer, running, finish, report = Value
  }
  private val phase = RegInit(Phase.idle)
  private val request = Reg(new TessellationJointJobRequest(idWidth))
  private val selected = RegInit(0.U(2.W))
  private val ownerRoot = Reg(UInt(32.W))
  private val result = Reg(new TessellationJointNativeCompletion(idWidth))
  private val cancelled = RegInit(false.B)
  private val cancelNow = io.cancel || io.feedback.map(_.cancel).getOrElse(false.B) ||
    io.payload.map(_.map(_.cancel).reduce(_ || _)).getOrElse(false.B)
  private val stop = cancelled || cancelNow
  when(phase =/= Phase.idle && phase =/= Phase.report && cancelNow) { cancelled := true.B }

  private val bankReady = imageOwner.io.active && imageOwner.io.domain === selected
  private val domainDrained = domains.map(_.drained).reduce(_ && _)
  private val commonDrained = domainDrained && memory.io.drained && shaders.io.drained && imagesDrained
  private val childValid = VecInit(domains.map(_.completion.valid))(selected)
  private val childResult = VecInit(domains.map(_.completion.bits))(selected)
  private val payloadReady = WireDefault(true.B)
  private val payloadDrained = WireDefault(true.B)
  private val feedbackDrained = WireDefault(true.B)
  private val feedbackCommand = WireDefault(false.B)
  private val resourcesDrained = commonDrained && payloadDrained && feedbackDrained

  private def reject(status: Int, stage: Int): Unit = {
    result.status := status.U
    result.faultStage := stage.U
    phase := Phase.finish
  }

  io.busy := phase =/= Phase.idle || imageOwner.io.active || !resourcesDrained
  io.drained := !io.busy
  io.submit.ready := io.drained && !cancelNow && !feedbackCommand
  when(io.submit.fire) {
    request := io.submit.bits
    ownerRoot := io.satp
    cancelled := false.B
    result := 0.U.asTypeOf(result)
    result.commandId := io.submit.bits.commandId
    result.identity := io.submit.bits.identity
    if (enabled) {
      when(io.submit.bits.domain < 3.U) {
        selected := io.submit.bits.domain
        phase := Phase.select
      }.otherwise { reject(1, 1) }
    } else {
      reject(6, 1)
    }
  }

  if (transformFeedback) {
    val bridge = Module(new TessellationThreeDomainFeedbackBridge(config))
    val feedback = io.feedback.get
    val acceptedScope = RegEnable(feedback.scope, io.submit.fire)
    val scope = Mux(phase === Phase.idle, feedback.scope, acceptedScope)
    TransformFeedbackPipelineIO.forward(feedback, bridge.io.feedback, scope, phase === Phase.idle)
    bridge.io.ownerIdle := VecInit(Seq.fill(3)(commonDrained && payloadDrained))
    bridge.io.memoryIdle := true.B
    for ((port, index) <- domains.zipWithIndex) bridge.io.owners(index) <> port.feedback.get
    feedbackDrained := bridge.io.drained
    feedbackCommand := bridge.io.feedback.command.fire

    // Keep a reset that arrives during a held child offer. Forward it after
    // acceptance so the child's cancellation input cannot prevent that fire.
    val pendingReset = RegInit(false.B)
    bridge.io.feedback.cancel := phase =/= Phase.offer && (feedback.cancel || pendingReset)
    when(phase === Phase.offer && feedback.cancel) { pendingReset := true.B }
    when(bridge.io.feedback.cancel) { pendingReset := false.B }

    bridge.io.switch.bits := selected
    // Assert this offer once the switch can actually begin; the registered GNQ
    // remains owned during a wait and throughout the accepted transfer.
    bridge.io.switch.valid := phase === Phase.select && !stop && !bridge.io.poisoned &&
      bridge.io.selected =/= selected && bridge.io.switch.ready
    bridge.io.switchReply.ready := phase === Phase.switching
    when(phase === Phase.select) {
      when(stop) { reject(4, 0) }
        .elsewhen(bridge.io.poisoned) { reject(7, 0) }
        .elsewhen(bridge.io.selected === selected && bridge.io.drained) { phase := Phase.prepare }
        .elsewhen(bridge.io.switch.fire) { phase := Phase.switching }
    }
    when(bridge.io.switchReply.fire) {
      val status = bridge.io.switchReply.bits
      when(status =/= 0.U && status =/= 4.U) { reject(7, 0) }
        .elsewhen(stop || status === 4.U) { reject(4, 0) }
        .otherwise { phase := Phase.prepare }
    }
  } else {
    when(phase === Phase.select) {
      when(stop) { reject(4, 0) }.otherwise { phase := Phase.prepare }
    }
  }

  for ((port, index) <- domains.zipWithIndex) {
    val owns = selected === index.U
    imageOwner.io.offer(index) := phase === Phase.prepare && owns
    port.submit.valid := phase === Phase.offer && owns
    port.submit.bits := request
    port.cancel := phase === Phase.running && owns && stop
    port.satp := Mux(phase === Phase.idle, io.satp, ownerRoot)
    port.flush := io.flush
    port.completion.ready := phase === Phase.running && owns && resourcesDrained
    port.inspect.valid := false.B
    port.inspect.bits := 0.U.asTypeOf(port.inspect.bits)
    port.reply.ready := true.B
    port.primitiveDone.foreach(_.ready := true.B)
    port.tileDone.foreach(_.ready := true.B)
    shaders.io.clients(index) <> port.shared
    when(port.submit.fire) {
      assert(bankReady && payloadReady, "joint tessellation entered a domain without its physical resources")
      phase := Phase.running
    }
  }

  payload.foreach { p =>
    val owner = Module(new TessellationThreeDomainPayloadOwner(p))
    owner.io.acquire.valid := phase === Phase.prepare && bankReady && !owner.io.active && !stop
    owner.io.acquire.bits := selected
    owner.io.end.valid := owner.io.active && commonDrained && feedbackDrained &&
      ((phase === Phase.running && childValid) || phase === Phase.finish)
    owner.io.end.bits := true.B
    payloadReady := owner.io.active
    payloadDrained := owner.io.drained
    for (slot <- 0 until p.owners) {
      val output = io.payload.get(slot)
      val acceptedContext = RegEnable(output.context, io.submit.fire)
      val context = Mux(phase === Phase.idle, output.context, acceptedContext)
      val inputs = domains.map(_.payload.get(slot))
      for ((port, index) <- inputs.zipWithIndex) {
        val client = owner.io.clients(index)(slot)
        client.control <> port.control
        port.controlResponse <> client.controlResponse
        client.request <> port.request
        port.response <> client.response
        port.context := context
        port.cancel := phase === Phase.running && selected === index.U && output.cancel
      }
      val pool = owner.io.pool(slot)
      output.control <> pool.control
      pool.controlResponse <> output.controlResponse
      output.request <> pool.request
      pool.response <> output.response
      output.task := VecInit(inputs.map(_.task))(selected)
    }
    for ((port, index) <- domains.zipWithIndex) shaders.io.clientPayload.get(index) := port.poolPayload.get
    io.poolPayload.get := shaders.io.poolPayload.get
    val pins = domains.flatMap(_.payloadPins.get.toSeq) ++ shaders.io.payloadPins.get.toSeq
    require(pins.size == TessellationJointThreeDomainJobImage.payloadPins(engines))
    for (index <- pins.indices) io.payloadPins.get(index) := pins(index)
  }

  when(phase === Phase.prepare) {
    when(stop) { reject(4, 0) }
      .elsewhen(bankReady && payloadReady && feedbackDrained) { phase := Phase.offer }
  }
  when(phase === Phase.running && childValid && resourcesDrained) {
    when(childResult.commandId === request.commandId && childResult.identity.asUInt === request.identity.asUInt) {
      result := childResult
      phase := Phase.finish
    }.otherwise { reject(3, 7) }
  }
  when(phase === Phase.finish && resourcesDrained) { phase := Phase.report }
  io.completion.valid := phase === Phase.report
  io.completion.bits := result
  when(io.completion.fire) { phase := Phase.idle }

  for (engine <- images.indices) {
    val samples = Module(new TessellationThreeDomainSampleMux)
    samples.io.selected := selected
    for ((port, index) <- domains.zipWithIndex) samples.io.domains(index) <> port.out(engine)
    io.out(engine) <> samples.io.out
  }
  for (phaseIndex <- 0 until 6)
    io.fragmentEvents.phaseWorkers(phaseIndex) := images.map(_.io.fragmentEvents.phaseWorkers(phaseIndex)).reduce(_ +& _)
  io.fragmentEvents.stagingOutstanding := images.map(_.io.fragmentEvents.stagingOutstanding).reduce(_ +& _)
  io.fragmentEvents.stagingBlocked := images.map(_.io.fragmentEvents.stagingBlocked).reduce(_ || _)
  io.fragmentEvents.outputBlocked := images.map(_.io.fragmentEvents.outputBlocked).reduce(_ || _)
}
