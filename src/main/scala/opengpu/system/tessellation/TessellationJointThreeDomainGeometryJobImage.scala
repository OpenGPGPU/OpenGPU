package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  ShaderPayloadBinding,
  VectorTlbFlush
}
import opengpu.graphics._

object TessellationJointThreeDomainGeometryJobImage {
  def domainPayloadPins(domain: Int, engines: Int): Int = {
    require(domain >= 0 && domain < 3)
    domain match {
      case 0 =>
        TessellationSystemIsolineImagePipeline.payloadPins(
          engines,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        )
      case 1 =>
        TessellationSystemTriangleImagePipeline.payloadPins(
          engines,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        )
      case 2 =>
        TessellationSystemQuadImagePipeline.payloadPins(
          engines,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        )
    }
  }

  def domainTransactions(
      domain: Int,
      engines: Int,
      writeCredits: Int,
      pagedCapacity: Int,
      tessellationUniforms: Boolean = false
  ): Int = {
    require(domain >= 0 && domain < 3)
    val renderIds = domain match {
      case 0 =>
        TessellationSystemIsolineImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms,
          nativeGeometryAvailable = true
        )
      case 1 =>
        TessellationSystemTriangleImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms,
          nativeGeometryAvailable = true
        )
      case 2 =>
        TessellationSystemQuadImagePipeline.memoryTransactions(
          engines,
          writeCredits,
          pagedCapacity,
          tessellationUniforms,
          nativeGeometryAvailable = true
        )
    }
    TessellationInputMemoryFabric.transactions(renderIds)
  }

  def transactions(engines: Int, writeCredits: Int, pagedCapacity: Int, tessellationUniforms: Boolean = false): Int = {
    val ids = (0 until 3).map(domainTransactions(_, engines, writeCredits, pagedCapacity, tessellationUniforms))
    require(ids.distinct.size == 1, "joint geometry domains must have equal memory capacity")
    TessellationThreeDomainMemoryFabric.transactions(ids(0), ids(1), ids(2))
  }

  def payloadPins(engines: Int): Int = {
    val pins = (0 until 3).map(domainPayloadPins(_, engines))
    require(pins.distinct.size == 1, "joint geometry domains must have equal payload capacity")
    pins.sum + 3 * (math.max(2, engines + 1) + 1)
  }
}

class TessellationJointThreeDomainGeometryJobImage(
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
    fragmentImageAvailable: Boolean = false,
    sampleShading: Boolean = false,
    tessellationUniforms: Boolean = false,
    primitiveIdInput: Boolean = false,
    enabled: Boolean = false
) extends Module {
  import TessellationJointThreeDomainGeometryJobImage._

  require(engines >= 1 && engines <= 4)
  require(payload.forall(_.owners == engines + 1))
  private val idWidth = shared.taskIdWidth
  private val cus = shared.numComputeUnits
  private val tasks = math.max(2, engines + 1)
  private val domainIds =
    (0 until 3).map(domainTransactions(_, engines, writeCredits, pagedCapacity, tessellationUniforms))
  require(domainIds.distinct.size == 1, "joint geometry domains must have equal memory capacity")
  private val ids = domainIds(0)
  private val slots = transactions(engines, writeCredits, pagedCapacity, tessellationUniforms)
  private val payloadConfig = payload
  val io = IO(new Bundle with TessellationHostPhysicalPorts {
    val submit = Flipped(Decoupled(new TessellationJointGeometryJobRequest(idWidth)))
    val completion = Decoupled(new TessellationJointGeometryNativeCompletion(idWidth))
    val out = Vec(engines, Decoupled(new TypedTilePixelQuadCompletion))
    val shared = Flipped(
      new SharedCuGraphicsPort(
        config,
        idWidth,
        cus,
        geometryAvailable = true,
        tessellationAvailable = true,
        imageAvailable = fragmentImageAvailable,
        evaluationPatchAvailable = true,
        tessellationInfoAvailable = true
      )
    )
    val payload = payloadConfig.map(p => Vec(engines + 1, new FragmentPayloadPort(config, p)))
    val poolPayload = payloadConfig.map(p => Output(Valid(new ShaderPayloadBinding(config, p))))
    val payloadPins = payloadConfig.map(p =>
      Output(Vec(TessellationJointThreeDomainGeometryJobImage.payloadPins(engines), Valid(new ShaderPayloadPin(p))))
    )
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val feedback: Option[TransformFeedbackPipelineIO] = None
    val geometryInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val fragmentEvents = Output(new FragmentPerformanceEvents)
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val drained = Output(Bool())
  })

  private val isoline = Module(
    new TessellationSystemIsolineJointGeometryVertexImage(
      config,
      vertex,
      fragment,
      shared,
      graphics,
      viewport,
      engines,
      pageBytes,
      writeCredits,
      tileSlots,
      pagedCapacity,
      storage,
      useSramBlackBoxes,
      payload,
      payloadOwner,
      externalImages = true,
      fragmentImageAvailable = fragmentImageAvailable,
      sampleShading = sampleShading,
      tessellationUniforms = tessellationUniforms,
      primitiveIdInput = primitiveIdInput,
      enabled = enabled
    )
  )
  private val triangle = Module(
    new TessellationSystemTriangleJointGeometryVertexImage(
      config,
      vertex,
      fragment,
      shared,
      graphics,
      viewport,
      engines,
      pageBytes,
      writeCredits,
      tileSlots,
      pagedCapacity,
      storage,
      useSramBlackBoxes,
      payload,
      payloadOwner,
      externalImages = true,
      fragmentImageAvailable = fragmentImageAvailable,
      sampleShading = sampleShading,
      tessellationUniforms = tessellationUniforms,
      primitiveIdInput = primitiveIdInput,
      enabled = enabled
    )
  )
  private val quad = Module(
    new TessellationSystemQuadJointGeometryVertexImage(
      config,
      vertex,
      fragment,
      shared,
      graphics,
      viewport,
      engines,
      pageBytes,
      writeCredits,
      tileSlots,
      pagedCapacity,
      storage,
      useSramBlackBoxes,
      payload,
      payloadOwner,
      externalImages = true,
      fragmentImageAvailable = fragmentImageAvailable,
      sampleShading = sampleShading,
      tessellationUniforms = tessellationUniforms,
      primitiveIdInput = primitiveIdInput,
      enabled = enabled
    )
  )
  require(Seq(isoline.memoryTransactions, triangle.memoryTransactions, quad.memoryTransactions).forall(_ == ids))
  require((0 until 3).map(domainPayloadPins(_, engines)).distinct.size == 1)
  private val domains = Seq(isoline.io, triangle.io, quad.io)

  private val imageOwner = Module(new TessellationImageBankOwner)
  private val imageMux = Module(
    new TessellationImageBankMux(config, vertex, shared, engines, payload, fragmentImageAvailable)
  )
  private val images = Seq.tabulate(engines)(engine =>
    Module(
      new TypedReplayTileImagePipeline(
        config,
        vertex,
        fragment,
        shared,
        graphics,
        storage,
        engine = engine,
        useSramBlackBoxes = useSramBlackBoxes,
        payload = payload,
        payloadOwner = payloadOwner + engine,
        fragmentImageAvailable = fragmentImageAvailable,
        geometryInput = true,
        sampleShading = sampleShading,
        primitiveIdInput = primitiveIdInput,
        allowEmptyStates = true
      )
    )
  )
  for ((port, index) <- domains.zipWithIndex) imageMux.io.clients(index) <> port.images.get
  for (index <- images.indices) imageMux.io.bank(index) <> images(index).io
  imageMux.io.active := imageOwner.io.active
  imageMux.io.domain := imageOwner.io.domain
  private val imagesDrained = !images.map(_.io.busy).reduce(_ || _)

  private val memory = Module(new TessellationThreeDomainMemoryFabric(config, ids, ids, ids))
  memory.io.isolineRequest <> isoline.io.memoryRequest
  isoline.io.memoryResponse <> memory.io.isolineResponse
  memory.io.triangleRequest <> triangle.io.memoryRequest
  triangle.io.memoryResponse <> memory.io.triangleResponse
  memory.io.quadRequest <> quad.io.memoryRequest
  quad.io.memoryResponse <> memory.io.quadResponse
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  private val shaders = Module(
    new SharedShaderTaskRouter(
      config,
      cus,
      3,
      idWidth,
      tasks,
      payload,
      geometryAvailable = true,
      tessellationAvailable = true,
      imageAvailable = fragmentImageAvailable,
      evaluationPatchAvailable = true,
      tessellationInfoAvailable = true
    )
  )
  io.shared <> shaders.io.pool

  private object Phase extends ChiselEnum {
    val idle, select, prepare, offer, running, finish, report = Value
  }
  private val phase = RegInit(Phase.idle)
  private val request = Reg(new TessellationJointGeometryJobRequest(idWidth))
  private val selected = RegInit(0.U(2.W))
  private val ownerRoot = Reg(UInt(32.W))
  private val result = Reg(new TessellationJointGeometryNativeCompletion(idWidth))
  private val cancelled = RegInit(false.B)
  private val retirementPending = RegInit(false.B)
  private val invalidatePending = RegInit(false.B)
  private val cancelNow = io.cancel || io.payload.map(_.map(_.cancel).reduce(_ || _)).getOrElse(false.B)
  private val stop = cancelled || cancelNow
  when(phase =/= Phase.idle && phase =/= Phase.report && cancelNow) { cancelled := true.B }

  private val bankReady = imageOwner.io.active && imageOwner.io.domain === selected
  private val domainDrained = domains.map(_.drained).reduce(_ && _)
  private val commonDrained = domainDrained && memory.io.drained && shaders.io.drained && imagesDrained
  private val childValid = VecInit(domains.map(_.completion.valid))(selected)
  private val childResult = VecInit(domains.map(_.completion.bits))(selected)
  private val payloadReady = WireDefault(true.B)
  private val payloadDrained = WireDefault(true.B)
  private val invalidateReady = WireDefault(false.B)
  private val invalidationDrained = WireDefault(true.B)
  private val resourcesDrained = commonDrained && payloadDrained && invalidationDrained

  imageOwner.io.drained := resourcesDrained
  imageOwner.io.retired := imageOwner.io.active && retirementPending && resourcesDrained
  when(imageOwner.io.retired) { retirementPending := false.B }

  private def reject(status: Int, stage: Int): Unit = {
    result.base.status := status.U
    result.base.faultStage := stage.U
    phase := Phase.finish
  }

  io.busy := phase =/= Phase.idle || imageOwner.io.active || retirementPending || !resourcesDrained
  io.drained := !io.busy
  io.submit.ready := io.drained && !cancelNow
  when(io.submit.fire) {
    request := io.submit.bits
    ownerRoot := io.satp
    cancelled := false.B
    retirementPending := false.B
    result := 0.U.asTypeOf(result)
    result.base.commandId := io.submit.bits.commandId
    result.base.identity := io.submit.bits.identity
    if (enabled) {
      when(TessellationJointJobLayout.requestLegal(io.submit.bits)) {
        selected := io.submit.bits.domain
        phase := Phase.select
      }.otherwise {
        result.base.faultAddress := io.submit.bits.view.base
        reject(1, 1)
      }
    } else {
      reject(6, 1)
    }
  }

  when(phase === Phase.select) {
    when(stop) { reject(4, 0) }.otherwise { phase := Phase.prepare }
  }

  io.geometryInvalidateDone.valid := false.B
  io.geometryInvalidateDone.bits := 0.U.asTypeOf(io.geometryInvalidateDone.bits)
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
    port.geometryInvalidate.valid := bankReady && owns && io.geometryInvalidate.valid
    port.geometryInvalidate.bits := io.geometryInvalidate.bits
    port.geometryInvalidateDone.ready := bankReady && owns && io.geometryInvalidateDone.ready
    when(bankReady && owns) {
      invalidateReady := port.geometryInvalidate.ready
      io.geometryInvalidateDone.valid := port.geometryInvalidateDone.valid
      io.geometryInvalidateDone.bits := port.geometryInvalidateDone.bits
    }
    shaders.io.clients(index) <> port.shared
    when(port.submit.fire) {
      assert(bankReady && payloadReady, "joint geometry entered a domain without its physical resources")
      phase := Phase.running
    }
  }
  io.geometryInvalidate.ready := bankReady && invalidateReady
  invalidationDrained := !invalidatePending && !io.geometryInvalidate.valid && !io.geometryInvalidateDone.valid
  when(io.geometryInvalidate.fire) {
    assert(bankReady, "joint geometry invalidate escaped its retained owner")
    invalidatePending := true.B
  }
  when(io.geometryInvalidateDone.fire) {
    assert(bankReady && invalidatePending, "joint geometry invalidate completion has no retained request")
    invalidatePending := false.B
  }

  payload.foreach { p =>
    val owner = Module(new TessellationThreeDomainPayloadOwner(p))
    owner.io.acquire.valid := phase === Phase.prepare && bankReady && !owner.io.active && !stop
    owner.io.acquire.bits := selected
    owner.io.end.valid := owner.io.active && commonDrained && invalidationDrained &&
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
    require(pins.size == TessellationJointThreeDomainGeometryJobImage.payloadPins(engines))
    for (index <- pins.indices) io.payloadPins.get(index) := pins(index)
  }

  when(phase === Phase.prepare) {
    when(stop) { reject(4, 0) }
      .elsewhen(bankReady && payloadReady && invalidationDrained) { phase := Phase.offer }
  }
  when(phase === Phase.running && childValid && resourcesDrained) {
    when(
      childResult.base.commandId === request.commandId && childResult.base.identity.asUInt === request.identity.asUInt
    ) {
      result := childResult
      phase := Phase.finish
    }.otherwise { reject(3, 7) }
  }
  when(phase === Phase.finish && resourcesDrained) { phase := Phase.report }
  io.completion.valid := phase === Phase.report
  io.completion.bits := result
  when(io.completion.fire) {
    retirementPending := imageOwner.io.active
    phase := Phase.idle
  }

  for (engine <- images.indices) {
    val samples = Module(new TessellationThreeDomainSampleMux)
    samples.io.selected := selected
    for ((port, index) <- domains.zipWithIndex) samples.io.domains(index) <> port.out(engine)
    io.out(engine) <> samples.io.out
  }
  for (phaseIndex <- 0 until 6)
    io.fragmentEvents
      .phaseWorkers(phaseIndex) := images.map(_.io.fragmentEvents.phaseWorkers(phaseIndex)).reduce(_ +& _)
  io.fragmentEvents.stagingOutstanding := images.map(_.io.fragmentEvents.stagingOutstanding).reduce(_ +& _)
  io.fragmentEvents.stagingBlocked := images.map(_.io.fragmentEvents.stagingBlocked).reduce(_ || _)
  io.fragmentEvents.outputBlocked := images.map(_.io.fragmentEvents.outputBlocked).reduce(_ || _)
}
