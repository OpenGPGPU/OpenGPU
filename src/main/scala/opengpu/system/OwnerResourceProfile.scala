package opengpu.system

/** Resource composition for the next complete two-CU owner product.
  *
  * computeStage is an explicitly intermediate factory. It retains every
  * protected native, query, feedback, tessellation and compute service of v1,
  * adding the checked per-active-lane shared atomic owner on both CUs. It does
  * not qualify the final v2 geometry/layered contract or advertise a GLES API.
  * NativeOwnerHostProfile remains the frozen, distinct 96-word v1 factory.
  */
object OwnerResourceProfile {
  val version = 2
  val id = "two-cu-vga60-owner-v2"
  val computeStageId = id + "-compute-stage"
  val targetFps = 60

  /** Preserve the caller's native configuration and all discovery records.
    * A future layered/geometry configuration composes with this function;
    * there is deliberately no fixed discovery-word count in this contract.
    */
  def withComputeAtomics(base: NativeAlignmentHostConfig): NativeAlignmentHostConfig = {
    require(base.computeUnits == 2 && base.execution.lanes == 16 && base.execution.warps == 8)
    require(base.execution.sharedMemoryBytes == 16384 && base.execution.sharedMemoryBanks == 16)
    require(base.execution.enableVector && base.execution.enableFpu && base.nativeProtectedSources)
    val enabled = base.copy(execution = base.execution.copy(enableSharedVectorAtomics = true))
    require(enabled.requiredDiscovery.toMap.apply(ComputeResourceCapabilities.base + 8) ==
      (base.requiredDiscovery.toMap.apply(ComputeResourceCapabilities.base + 8) |
        ComputeResourceCapabilities.sharedVectorAtomics))
    require(enabled.requiredDiscovery.filterNot(_._1 == ComputeResourceCapabilities.base + 8) ==
      base.requiredDiscovery.filterNot(_._1 == ComputeResourceCapabilities.base + 8))
    enabled
  }

  val computeStage: NativeAlignmentHostConfig = withComputeAtomics(NativeOwnerHostProfile.target)
}
