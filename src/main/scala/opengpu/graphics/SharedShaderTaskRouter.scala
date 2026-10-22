package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.dispatch.TaggedKernelLaunch
import opengpu.core.memory.ShaderPayloadBinding
import opengpu.system.SharedCuGraphicsPort

/** Connect independent shader clients to one physical CU pool. Each client
  * owns a bounded task window (one task by default).
  * Pool tags identify reserved task slots; local tags are retained through completion and
  * fault consumption. Texture traffic follows the executing task's client,
  * regardless of which CU the shared scheduler selected for it.
  */
class SharedShaderTaskRouter(
  config: GpuConfig,
  numComputeUnits: Int,
  numClients: Int,
  taskIdWidth: Int = 8,
  tasksPerClient: Int = 1,
  shaderPayload: Option[ShaderPayloadConfig] = None
) extends Module {
  require(numComputeUnits > 0 && numClients > 0)
  require(taskIdWidth > 0 && tasksPerClient > 0)
  private val slots = numClients*tasksPerClient
  require(BigInt(slots) <= (BigInt(1) << taskIdWidth))
  private val ownerWidth = math.max(1, log2Ceil(numClients))
  private val slotWidth = math.max(1, log2Ceil(slots))
  val io = IO(new Bundle {
    val clients = Vec(numClients, new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits))
    val pool = Flipped(new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits))
    val clientPayload=shaderPayload.map(p=>Input(Vec(numClients,Valid(new ShaderPayloadBinding(config,p)))))
    val poolPayload=shaderPayload.map(p=>Output(Valid(new ShaderPayloadBinding(config,p))))
    /** Accepted queued/executing tasks and each prospective client offer.
      * Connect these to the shared store's producer pins across every router
      * tier, so a lease cannot recycle before its launch reaches the CUs.
      */
    val payloadPins=shaderPayload.map(p=>Output(Vec(slots+numClients,Valid(new ShaderPayloadPin(p)))))
    val drained = Output(Bool())
  })
  private val active = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val issued = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private def clientActive(tag: UInt): Bool =
    if (slots == 1) active(0) else active(tag(slotWidth - 1, 0))
  private val tags = Reg(Vec(slots, UInt(taskIdWidth.W)))
  private val nativeProviders = Reg(Vec(slots,Bool()))
  private val uniformProviders = Reg(Vec(slots,Bool()))
  private val payloads=shaderPayload.map(p=>Reg(Vec(slots,Valid(new ShaderPayloadBinding(config,p)))))
  private def localTag(tag: UInt): UInt =
    if (slots == 1) tags(0) else tags(tag(slotWidth-1,0))
  private def belongs(tag: UInt,client: Int): Bool =
    tag >= (client*tasksPerClient).U && tag < ((client+1)*tasksPerClient).U
  private val launches = Module(new RRArbiter(new TaggedKernelLaunch(config, taskIdWidth), numClients))
  private val launchQueue = Module(new Queue(new TaggedKernelLaunch(config,taskIdWidth),2,pipe=true))
  for (client <- 0 until numClients) {
    val port = io.clients(client)
    val free = VecInit((0 until tasksPerClient).map(i => !active(client*tasksPerClient+i)))
    val duplicate = port.launch.valid && (0 until tasksPerClient).map { i =>
      val slot = client*tasksPerClient+i
      active(slot) && tags(slot) === port.launch.bits.commandId
    }.reduce(_ || _)
    val chosen = if (tasksPerClient == 1) (client*tasksPerClient).U(slotWidth.W)
      else (client*tasksPerClient).U(slotWidth.W)+PriorityEncoder(free).pad(slotWidth)
    launches.io.in(client).valid := port.launch.valid && free.asUInt.orR && !duplicate
    launches.io.in(client).bits.launch := port.launch.bits.launch
    launches.io.in(client).bits.commandId := chosen
    port.launch.ready := launches.io.in(client).ready && free.asUInt.orR && !duplicate
    when(port.launch.fire) {
      active(chosen) := true.B
      issued(chosen) := false.B
      tags(chosen) := port.launch.bits.commandId
      nativeProviders(chosen) := port.nativeResources
      uniformProviders(chosen) := port.uniformResources
      shaderPayload.foreach { _=>payloads.get(chosen):=io.clientPayload.get(client) }
    }
    port.completion.valid := io.pool.completion.valid && belongs(io.pool.completion.bits.commandId,client)
    port.completion.bits.commandId := localTag(io.pool.completion.bits.commandId)
    port.completion.bits.success := io.pool.completion.bits.success
    when(port.completion.fire) {
      if (slots == 1) active(0) := false.B
      else active(io.pool.completion.bits.commandId(slotWidth-1,0)) := false.B
      if (slots == 1) issued(0) := false.B
      else issued(io.pool.completion.bits.commandId(slotWidth-1,0)) := false.B
    }
    port.trap.valid := io.pool.trap.valid && belongs(io.pool.trap.bits.taskId,client)
    port.trap.bits := io.pool.trap.bits
    port.trap.bits.taskId := localTag(io.pool.trap.bits.taskId)

    val owned = VecInit((0 until numComputeUnits).map { cu =>
      io.pool.owned(cu) && belongs(io.pool.taskIds(cu),client)
    })
    port.owned := owned.asUInt
    for (cu <- 0 until numComputeUnits) {
      port.taskIds(cu) := localTag(io.pool.taskIds(cu))
      val texture = port.texture(cu)
      val pool = io.pool.texture(cu)
      texture.scalar.valid := pool.scalar.valid && owned(cu)
      texture.scalar.bits := pool.scalar.bits
      texture.vector.valid := pool.vector.valid && owned(cu)
      texture.vector.bits := pool.vector.bits
      texture.scalarWriteback.ready := pool.scalarWriteback.ready && owned(cu)
      texture.vectorWriteback.ready := pool.vectorWriteback.ready && owned(cu)
      when(owned(cu)) {
        assert(clientActive(io.pool.taskIds(cu)), "CU texture owner must name a live shader task")
      }
    }
  }
  launchQueue.io.enq <> launches.io.out
  io.pool.launch <> launchQueue.io.deq
  private val providerHead=if(slots==1)0.U else io.pool.launch.bits.commandId(slotWidth-1,0)
  io.pool.nativeResources:=nativeProviders(providerHead)
  io.pool.uniformResources:=uniformProviders(providerHead)
  shaderPayload.foreach { _=>
    val head=if(slots==1)0.U else io.pool.launch.bits.commandId(slotWidth-1,0)
    io.poolPayload.get.bits:=payloads.get(head).bits
    io.poolPayload.get.valid:=io.pool.launch.valid && payloads.get(head).valid
    def assign(pin: ShaderPayloadPin,binding: ShaderPayloadBinding): Unit={
      pin.handle:=binding.handle;pin.generation:=binding.generation
      pin.context:=binding.context;pin.owner:=binding.owner
    }
    for(slot <- 0 until slots) {
      io.payloadPins.get(slot).valid:=active(slot) && payloads.get(slot).valid
      assign(io.payloadPins.get(slot).bits,payloads.get(slot).bits)
    }
    for(client <- 0 until numClients) {
      io.payloadPins.get(slots+client).valid:=io.clients(client).launch.valid && io.clientPayload.get(client).valid
      assign(io.payloadPins.get(slots+client).bits,io.clientPayload.get(client).bits)
    }
  }
  when(io.pool.launch.fire) {
    if (slots == 1) issued(0) := true.B
    else issued(io.pool.launch.bits.commandId(slotWidth-1,0)) := true.B
  }
  io.pool.completion.ready := (0 until numClients).map(i =>
    belongs(io.pool.completion.bits.commandId,i) && io.clients(i).completion.ready).reduce(_ || _)
  io.pool.trap.ready := (0 until numClients).map(i =>
    belongs(io.pool.trap.bits.taskId,i) && io.clients(i).trap.ready).reduce(_ || _)
  when(io.pool.completion.valid) {
    assert(io.pool.completion.bits.commandId < slots.U,
      "pool completion named an invalid shader client")
    assert(clientActive(io.pool.completion.bits.commandId),
      "pool completion named an inactive shader client")
    assert(if (slots == 1) issued(0) else issued(io.pool.completion.bits.commandId(slotWidth-1,0)),
      "pool completion named a task which has not launched")
  }
  when(io.pool.trap.valid) {
    assert(io.pool.trap.bits.taskId < slots.U,
      "pool trap named an invalid shader client")
    assert(clientActive(io.pool.trap.bits.taskId), "pool trap named an inactive shader client")
    assert(if (slots == 1) issued(0) else issued(io.pool.trap.bits.taskId(slotWidth-1,0)),
      "pool trap named a task which has not launched")
  }

  for (cu <- 0 until numComputeUnits) {
    val pool = io.pool.texture(cu)
    val owner = io.pool.taskIds(cu)
    pool.scalar.ready := false.B
    pool.vector.ready := false.B
    pool.scalarWriteback.valid := false.B
    pool.scalarWriteback.bits := 0.U.asTypeOf(pool.scalarWriteback.bits)
    pool.vectorWriteback.valid := false.B
    pool.vectorWriteback.bits := 0.U.asTypeOf(pool.vectorWriteback.bits)
    for (client <- 0 until numClients) {
      when(io.pool.owned(cu) && belongs(owner,client)) {
        val texture = io.clients(client).texture(cu)
        pool.scalar.ready := texture.scalar.ready
        pool.vector.ready := texture.vector.ready
        pool.scalarWriteback.valid := texture.scalarWriteback.valid
        pool.scalarWriteback.bits := texture.scalarWriteback.bits
        pool.vectorWriteback.valid := texture.vectorWriteback.valid
        pool.vectorWriteback.bits := texture.vectorWriteback.bits
      }
    }
    when(io.pool.owned(cu)) {
      assert(owner < slots.U, "pool CU named an invalid shader client")
    }
  }

  // Invalidation acknowledgements have no task ID. Retain the requesting
  // client until its complete broadcast acknowledgement is accepted.
  private val invalidates = Module(new RRArbiter(new CacheLineInvalidate(config), numClients))
  private val invalidateOffered = RegInit(false.B)
  private val invalidateRequest = Reg(new CacheLineInvalidate(config))
  private val invalidateBusy = RegInit(false.B)
  private val invalidateOwner = Reg(UInt(ownerWidth.W))
  for (client <- 0 until numClients) {
    invalidates.io.in(client) <> io.clients(client).invalidate
    io.clients(client).invalidateDone.valid := io.pool.invalidateDone.valid &&
      invalidateBusy && invalidateOwner === client.U
    io.clients(client).invalidateDone.bits := io.pool.invalidateDone.bits
  }
  // Reserve the requesting owner together with the request before offering
  // it to the pool. A newly arriving higher-priority client must not replace
  // a stalled public invalidate or inherit its untagged acknowledgement.
  invalidates.io.out.ready := !invalidateBusy && !invalidateOffered
  when(invalidates.io.out.fire) {
    invalidateOffered := true.B
    invalidateRequest := invalidates.io.out.bits
    invalidateOwner := invalidates.io.chosen
  }
  io.pool.invalidate.valid := invalidateOffered && !invalidateBusy
  io.pool.invalidate.bits := invalidateRequest
  when(io.pool.invalidate.fire) {
    invalidateOffered := false.B
    invalidateBusy := true.B
  }
  io.pool.invalidateDone.ready := invalidateBusy &&
    MuxLookup(invalidateOwner, false.B)((0 until numClients).map { i =>
      i.U -> io.clients(i).invalidateDone.ready
    })
  when(io.pool.invalidateDone.fire) { invalidateBusy := false.B }
  when(io.pool.invalidateDone.valid) {
    assert(invalidateBusy, "pool invalidation acknowledgement has no requesting client")
  }
  io.drained := !active.asUInt.orR && !invalidateBusy && !invalidateOffered &&
    !launches.io.out.valid && !invalidates.io.out.valid
}
