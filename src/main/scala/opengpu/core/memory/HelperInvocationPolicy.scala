package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Trusted per-warp fragment invocation metadata. Helpers execute normally;
  * only memory side effects are restricted. The private range must come from
  * an owner's validated allocation, never a shader-controlled descriptor.
  */
class WarpInvocationPolicy(config: GpuConfig) extends Bundle {
  val helpers=UInt(config.lanes.W)
  val privateBase=UInt(config.xLen.W);val privateBytes=UInt(config.xLen.W)
}
/** Helpers are indexed by work-item within every workgroup, before physical
  * warp-slot assignment. Zero helpers preserves ordinary compute execution.
  * This metadata extends internal launch packets, not the host MMIO ABI.
  */
class KernelInvocationPolicy(config: GpuConfig) extends Bundle {
  val helpers=UInt((config.lanes*config.warps).W)
  val privateBase=UInt(config.xLen.W);val privateBytes=UInt(config.xLen.W)
}
object HelperInvocationPolicy {
  def select(policies: Vec[WarpInvocationPolicy],warp: UInt): WarpInvocationPolicy=
    if(policies.length==1)policies(0)else policies(warp)
  def real(policy: WarpInvocationPolicy,active: UInt): Bool=
    policy.helpers===0.U || (active & ~policy.helpers).orR
  def privateAccess(config: GpuConfig,policy: WarpInvocationPolicy,address: UInt,bytes: UInt): Bool={
    val end=policy.privateBase+&policy.privateBytes
    val accessEnd=address+&bytes
    val limit=(BigInt(1)<<config.xLen).U((config.xLen+1).W)
    val overlapsShared=policy.privateBase<(config.sharedMemoryBase+config.sharedMemoryBytes).U && end>config.sharedMemoryBase.U
    policy.privateBytes=/=0.U && end<=limit && !overlapsShared &&
      address>=policy.privateBase && accessEnd<=end && accessEnd<=limit
  }
  def store(config: GpuConfig,policy: WarpInvocationPolicy,active: UInt,address: UInt,bytes: UInt): Bool=
    real(policy,active) || privateAccess(config,policy,address,bytes)
}
