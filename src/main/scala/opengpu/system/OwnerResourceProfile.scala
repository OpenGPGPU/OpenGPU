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

  /** Complete source composition. Qualification requires fresh RTL and actual
    * execution; neither earlier owner factory's evidence applies to this host.
    */
  val target: NativeAlignmentHostConfig = {
    val base = NativeOwnerHostProfile.target
    withComputeAtomics(base.copy(native = base.native.copy(nativeGeometry = true),
      nativeGeometryTransport = true))
  }
  val sampleStageId = id + "-sample-stage"
  val sampleStageName = "geometry-compute-sample"
  // A new factory with an explicit presence record. The 104-word target remains frozen.
  val sampleStage: NativeAlignmentHostConfig = target.copy(native = target.native.copy(sampleShading = true))
  require(sampleStage.nativeSampleTransport && sampleStage.requiredDiscovery.size == 112 &&
    sampleStage.requiredDiscovery.toMap.apply(0x368) == 0x1fffff &&
    sampleStage.native.payload == target.native.payload &&
    sampleStage.execution == target.execution, "sample stage preserves complete owner resources")

  require(target.requiredDiscovery.size == 104 &&
    target.requiredDiscovery.toMap.apply(0x368) == 0xfffff &&
    target.native.transformFeedback && target.native.engines == 4 &&
    target.native.renderTargetExtent.contains((2048,2048)) &&
    target.native.pagedCapacity == 65536 && target.native.payload.get.capacityBytes == 524288,
    "complete owner composition retains the full native API and all resource transports")
}
