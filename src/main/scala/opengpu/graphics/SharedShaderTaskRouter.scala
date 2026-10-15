package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.dispatch.TaggedKernelLaunch
import opengpu.system.SharedCuGraphicsPort

/** Connect independent single-task shader clients to one physical CU pool.
  * Pool tags identify clients; local tags are retained through completion and
  * fault consumption. Texture traffic follows the executing task's client,
  * regardless of which CU the shared scheduler selected for it.
  */
class SharedShaderTaskRouter(
  config: GpuConfig,
  numComputeUnits: Int,
  numClients: Int,
  taskIdWidth: Int = 8
) extends Module {
  require(numComputeUnits > 0 && numClients > 0)
  require(taskIdWidth > 0)
  require(BigInt(numClients) <= (BigInt(1) << taskIdWidth))
  private val ownerWidth = math.max(1, log2Ceil(numClients))
  val io = IO(new Bundle {
    val clients = Vec(numClients, new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits))
    val pool = Flipped(new SharedCuGraphicsPort(config, taskIdWidth, numComputeUnits))
    val drained = Output(Bool())
  })
  private val active = RegInit(VecInit(Seq.fill(numClients)(false.B)))
  private def clientActive(tag: UInt): Bool =
    if (numClients == 1) active(0) else active(tag(ownerWidth - 1, 0))
  private val tags = Reg(Vec(numClients, UInt(taskIdWidth.W)))
  private val launches = Module(new RRArbiter(new TaggedKernelLaunch(config, taskIdWidth), numClients))
  for (client <- 0 until numClients) {
    val port = io.clients(client)
    launches.io.in(client).valid := port.launch.valid && !active(client)
    launches.io.in(client).bits.launch := port.launch.bits.launch
    launches.io.in(client).bits.commandId := client.U
    port.launch.ready := launches.io.in(client).ready && !active(client)
    when(port.launch.fire) {
      active(client) := true.B
      tags(client) := port.launch.bits.commandId
    }
    port.completion.valid := io.pool.completion.valid && io.pool.completion.bits.commandId === client.U
    port.completion.bits.commandId := tags(client)
    port.completion.bits.success := io.pool.completion.bits.success
    when(port.completion.fire) { active(client) := false.B }
    port.trap.valid := io.pool.trap.valid && io.pool.trap.bits.taskId === client.U
    port.trap.bits := io.pool.trap.bits
    port.trap.bits.taskId := tags(client)

    val owned = VecInit((0 until numComputeUnits).map { cu =>
      io.pool.owned(cu) && io.pool.taskIds(cu) === client.U
    })
    port.owned := owned.asUInt
    for (cu <- 0 until numComputeUnits) {
      port.taskIds(cu) := tags(client)
      val texture = port.texture(cu)
      val pool = io.pool.texture(cu)
      texture.scalar.valid := pool.scalar.valid && owned(cu)
      texture.scalar.bits := pool.scalar.bits
      texture.vector.valid := pool.vector.valid && owned(cu)
      texture.vector.bits := pool.vector.bits
      texture.scalarWriteback.ready := pool.scalarWriteback.ready && owned(cu)
      texture.vectorWriteback.ready := pool.vectorWriteback.ready && owned(cu)
      when(owned(cu)) {
        assert(active(client), "CU texture owner must name a live shader client")
      }
    }
  }
  io.pool.launch <> launches.io.out
  io.pool.completion.ready := MuxLookup(io.pool.completion.bits.commandId, false.B)(
    (0 until numClients).map(i => i.U -> io.clients(i).completion.ready))
  io.pool.trap.ready := MuxLookup(io.pool.trap.bits.taskId, false.B)(
    (0 until numClients).map(i => i.U -> io.clients(i).trap.ready))
  when(io.pool.completion.valid) {
    assert(io.pool.completion.bits.commandId < numClients.U,
      "pool completion named an invalid shader client")
    assert(clientActive(io.pool.completion.bits.commandId),
      "pool completion named an inactive shader client")
  }
  when(io.pool.trap.valid) {
    assert(io.pool.trap.bits.taskId < numClients.U,
      "pool trap named an invalid shader client")
    assert(clientActive(io.pool.trap.bits.taskId), "pool trap named an inactive shader client")
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
      when(io.pool.owned(cu) && owner === client.U) {
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
      assert(owner < numClients.U, "pool CU named an invalid shader client")
    }
  }

  // Invalidation acknowledgements have no task ID. Retain the requesting
  // client until its complete broadcast acknowledgement is accepted.
  private val invalidates = Module(new RRArbiter(new CacheLineInvalidate(config), numClients))
  private val invalidateBusy = RegInit(false.B)
  private val invalidateOwner = Reg(UInt(ownerWidth.W))
  for (client <- 0 until numClients) {
    invalidates.io.in(client) <> io.clients(client).invalidate
    io.clients(client).invalidateDone.valid := io.pool.invalidateDone.valid &&
      invalidateBusy && invalidateOwner === client.U
    io.clients(client).invalidateDone.bits := io.pool.invalidateDone.bits
  }
  io.pool.invalidate.valid := invalidates.io.out.valid && !invalidateBusy
  io.pool.invalidate.bits := invalidates.io.out.bits
  invalidates.io.out.ready := io.pool.invalidate.ready && !invalidateBusy
  when(io.pool.invalidate.fire) {
    invalidateBusy := true.B
    invalidateOwner := invalidates.io.chosen
  }
  io.pool.invalidateDone.ready := invalidateBusy &&
    MuxLookup(invalidateOwner, false.B)((0 until numClients).map { i =>
      i.U -> io.clients(i).invalidateDone.ready
    })
  when(io.pool.invalidateDone.fire) { invalidateBusy := false.B }
  when(io.pool.invalidateDone.valid) {
    assert(invalidateBusy, "pool invalidation acknowledgement has no requesting client")
  }
  io.drained := !active.asUInt.orR && !invalidateBusy &&
    !launches.io.out.valid && !invalidates.io.out.valid
}
