package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.trap.CoreTrapEvent
import opengpu.core.memory.CacheLineInvalidate
import opengpu.dispatch.{TaggedKernelCompletion, TaggedKernelLaunch}

case class SharedCuShaderConfig(
  numComputeUnits: Int,
  taskIdWidth: Int = 8,
  parallelFragments: Boolean = false,
  shaderPayload: Option[ShaderPayloadConfig] = None
) {
  require(numComputeUnits > 0)
  require(taskIdWidth > 0)
  require(shaderPayload.isEmpty || parallelFragments,"local renderer payloads use parallel shared fragment workers")
  val fragmentWorkers: Int = if (parallelFragments) numComputeUnits else 1
  val fragmentBanks: Int = fragmentWorkers * 2
  val tasksPerEngine: Int = if(parallelFragments)fragmentWorkers+1 else 1
  val rendererPayloadPins: Int = 2*(fragmentWorkers+1)
  def binPayloadPins(engines: Int): Int=engines*rendererPayloadPins+engines*(tasksPerEngine+1)
  def binPayloadProducers(engines: Int): Int=engines*fragmentWorkers
  val fragmentStagingPerWorker: Int = 8
  // Match global credits to each worker's independent local transactions. Round the shared
  // credit window up for non-power-of-two CU counts; no shader ABI changes.
  val fragmentStagingTransactions: Int =
    if (parallelFragments) 1 << log2Ceil(fragmentWorkers * fragmentStagingPerWorker) else 2
}

object SharedCuShaderConfig {
  def stagingTransactions(pool: Option[SharedCuShaderConfig]): Int =
    math.max(4, pool.map(_.fragmentStagingTransactions).getOrElse(2))
  // Staging and the four legacy host-DMA IDs have disjoint ranges. All word
  // interfaces use this width, including the response decoder and MMUs.
  def kernelWordTransactions(pool: Option[SharedCuShaderConfig]): Int =
    1 << log2Ceil(stagingTransactions(pool) + 4)
}

/** A fault belongs to a graphics task, not a host compute command. */
class TaggedGraphicsTrap(config: GpuConfig, commandIdWidth: Int, numComputeUnits: Int)
    extends Bundle {
  val taskId = UInt(commandIdWidth.W)
  val cu = UInt(math.max(1, log2Ceil(numComputeUnits)).W)
  val event = new CoreTrapEvent(config)
}

/** Execute-side texture interfaces of one shared CU. The graphics owner binds
  * these to a sampler carrying the descriptor of taskIds(cu). Each CU has its
  * own interface, so one serialized sampler is not imposed on the whole pool.
  */
class SharedCuTexturePort(config: GpuConfig) extends Bundle {
  val scalar = Decoupled(new ScalarIssuedInstruction(config))
  val scalarWriteback = Flipped(Decoupled(new ScalarCommitRequest(config)))
  val vector = Decoupled(new VectorTextureRequest(config))
  val vectorWriteback = Flipped(Decoupled(new VectorCommitRequest(config)))
}

/** Graphics tasks execute on the very same CUs as host compute kernels.
  * The producer must use the pool's lane/warp ABI and retain task metadata
  * and staging storage until its tagged completion is accepted. SATP and TLB
  * controls are currently shared with the system; concurrent address spaces
  * require a separate context scheduling contract.
  */
class SharedCuGraphicsPort(config: GpuConfig, commandIdWidth: Int, numComputeUnits: Int)
    extends Bundle {
  val launch = Flipped(Decoupled(new TaggedKernelLaunch(config, commandIdWidth)))
  val completion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
  val trap = Decoupled(new TaggedGraphicsTrap(config, commandIdWidth, numComputeUnits))
  val texture = Vec(numComputeUnits, new SharedCuTexturePort(config))
  val owned = Output(UInt(numComputeUnits.W))
  val taskIds = Output(Vec(numComputeUnits, UInt(commandIdWidth.W)))
  val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
  val invalidateDone = Decoupled(new CacheLineInvalidate(config))
}
