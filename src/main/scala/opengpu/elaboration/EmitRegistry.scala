package opengpu.elaboration

/** Central registry for elaboration entry points.
  *
  * `Emit*` objects keep their `main` methods for direct `runMain` use.
  * This registry gives one discoverable name list and a dispatch helper for
  * scripts/tools without changing emission behavior.
  */
object EmitRegistry {
  final case class Entry(name: String, run: Array[String] => Unit, description: String = "")

  private def e(name: String, run: Array[String] => Unit, desc: String = ""): Entry = Entry(name, run, desc)

  val entries: Seq[Entry] = Seq(
    e("EmitAtomicCounterCommandAbi", EmitAtomicCounterCommandAbi.main),
    e("EmitBankedTextureHits", EmitBankedTextureHits.main),
    e("EmitComputeGrid", EmitComputeGrid.main),
    e("EmitComputeIndirectAbi", EmitComputeIndirectAbi.main),
    e("EmitComputeResourceAbi", EmitComputeResourceAbi.main),
    e("EmitComputeResourceHost", EmitComputeResourceHost.main),
    e("EmitComputeTextureCommandAbi", EmitComputeTextureCommandAbi.main),
    e("EmitComputeUniformCommandAbi", EmitComputeUniformCommandAbi.main),
    e("EmitGeometryUniformAbi", EmitGeometryUniformAbi.main),
    e("EmitFp32FmaLaneTimingRtl", EmitFp32FmaLaneTimingRtl.main),
    e("EmitFpuCoreSimulationRtl", EmitFpuCoreSimulationRtl.main),
    e("EmitFpuFaultCancellation", EmitFpuFaultCancellation.main),
    e("EmitGeometryExperiment", EmitGeometryExperiment.main),
    e("EmitGpuHostAxi", EmitGpuHostAxi.main),
    e("EmitGpuHostSystemAxi", EmitGpuHostSystemAxi.main),
    e("EmitIndirectCompute", EmitIndirectCompute.main),
    e("EmitInstructionSimtControl", EmitInstructionSimtControl.main),
    e("EmitLegacyInterpolationDivider", EmitLegacyInterpolationDivider.main),
    e("EmitLegacyUvPipeline", EmitLegacyUvPipeline.main),
    e("EmitNativeAdvancedBlendAbi", EmitNativeAdvancedBlendAbi.main),
    e("EmitNativeDescriptorPreflightAbi", EmitNativeDescriptorPreflightAbi.main),
    e("EmitNativeBinnedGraphicsAbi", EmitNativeBinnedGraphicsAbi.main),
    e("EmitNativeGeometryAbi", EmitNativeGeometryAbi.main),
    e("EmitNativeGeometryTransportAbi", EmitNativeGeometryTransportAbi.main),
    e("EmitNativeLayeredGraphicsAbi", EmitNativeLayeredGraphicsAbi.main),
    e("EmitNativeBinnedHostAbi", EmitNativeBinnedHostAbi.main),
    e("EmitNativePrimitiveQueryAbi", EmitNativePrimitiveQueryAbi.main),
    e("EmitNativePrimitiveQueryHosts", EmitNativePrimitiveQueryHosts.main),
    e("EmitNativeRgba8Clients", EmitNativeRgba8Clients.main),
    e("EmitNativeRgba8Pool", EmitNativeRgba8Pool.main),
    e("EmitNativeRgba8Product", EmitNativeRgba8Product.main),
    e("EmitNativeRgba8Renderer", EmitNativeRgba8Renderer.main),
    e("EmitNativeRgba8TextureService", EmitNativeRgba8TextureService.main),
    e("EmitNativeShaderExecutionAbi", EmitNativeShaderExecutionAbi.main),
    e("EmitNativeShaderExecutionHost", EmitNativeShaderExecutionHost.main),
    e("EmitNativeTextureAbi", EmitNativeTextureAbi.main),
    e("EmitNativeTextureFetch", EmitNativeTextureFetch.main),
    e("EmitNativeTextureOperationHost", EmitNativeTextureOperationHost.main),
    e("EmitNativeTransformFeedbackAbi", EmitNativeTransformFeedbackAbi.main),
    e("EmitNativeTransformFeedbackHosts", EmitNativeTransformFeedbackHosts.main),
    e("EmitNativeTypedGraphicsAbi", EmitNativeTypedGraphicsAbi.main),
    e("EmitNativeUniformAbi", EmitNativeUniformAbi.main),
    e("EmitNativeUniformHost", EmitNativeUniformHost.main),
    e("EmitNativeVisibilityQueries", EmitNativeVisibilityQueries.main),
    e("EmitNativeVisibilityQueryAbi", EmitNativeVisibilityQueryAbi.main),
    e("EmitNativeVisibilityQueryHosts", EmitNativeVisibilityQueryHosts.main),
    e("EmitPpaRtl", EmitPpaRtl.main),
    e("EmitPrimitiveQuery", EmitPrimitiveQuery.main),
    e("EmitQuadTexUV", EmitQuadTexUV.main),
    e("EmitRasterAttributePipeline", EmitRasterAttributePipeline.main),
    e("EmitRasterCoverage", EmitRasterCoverage.main),
    e("EmitRegistry", EmitRegistry.main),
    e("EmitShaderExecutionAvailability", EmitShaderExecutionAvailability.main),
    e("EmitShaderImageAbi", EmitShaderImageAbi.main),
    e("EmitShaderImageCommandAbi", EmitShaderImageCommandAbi.main),
    e("EmitShaderImagePublicationAbi", EmitShaderImagePublicationAbi.main),
    e("EmitShaderPayloadRtl", EmitShaderPayloadRtl.main),
    e("EmitShaderTrapRetirement", EmitShaderTrapRetirement.main),
    e("EmitSimtReconvergence", EmitSimtReconvergence.main),
    e("EmitStorageCommandAbi", EmitStorageCommandAbi.main),
    e("EmitStorageDescriptorAbi", EmitStorageDescriptorAbi.main),
    e("EmitTessellationNativeCompletionAbi", EmitTessellationNativeCompletionAbi.main),
    e("EmitTessellationNativeJobAbi", EmitTessellationNativeJobAbi.main),
    e("EmitTessellationNativeQuadJobAbi", EmitTessellationNativeQuadJobAbi.main),
    e("EmitTessellationNativeQuadSequenceAbi", EmitTessellationNativeQuadSequenceAbi.main),
    e("EmitTessellationNativeSequenceAbi", EmitTessellationNativeSequenceAbi.main),
    e("EmitTessellationNativeTriangleJobAbi", EmitTessellationNativeTriangleJobAbi.main),
    e("EmitTessellationNativeTriangleSequenceAbi", EmitTessellationNativeTriangleSequenceAbi.main),
    e("EmitTextureBilinearFilter", EmitTextureBilinearFilter.main),
    e("EmitTextureCompressedBlock", EmitTextureCompressedBlock.main),
    e("EmitTextureExecutionAvailability", EmitTextureExecutionAvailability.main),
    e("EmitTextureFloatAxis", EmitTextureFloatAxis.main),
    e("EmitTextureImageAddress", EmitTextureImageAddress.main),
    e("EmitTextureImageBinding", EmitTextureImageBinding.main),
    e("EmitTextureImageBindingAbi", EmitTextureImageBindingAbi.main),
    e("EmitTextureImageFetch", EmitTextureImageFetch.main),
    e("EmitTextureImageFormatAbi", EmitTextureImageFormatAbi.main),
    e("EmitTextureImageLevelAbi", EmitTextureImageLevelAbi.main),
    e("EmitTextureImageResourceAddress", EmitTextureImageResourceAddress.main),
    e("EmitTextureImageSample", EmitTextureImageSample.main),
    e("EmitTextureRgba8GroupPlan", EmitTextureRgba8GroupPlan.main),
    e("EmitTextureSamplerAbi", EmitTextureSamplerAbi.main),
    e("EmitTextureSamplerBinding", EmitTextureSamplerBinding.main),
    e("EmitTimingRtl", EmitTimingRtl.main),
    e("EmitTransformFeedback", EmitTransformFeedback.main),
    e("EmitTranslatedRgba8Fp32", EmitTranslatedRgba8Fp32.main),
    e("EmitTypedFragmentStateAbi", EmitTypedFragmentStateAbi.main),
    e("EmitWarpInstructionGuard", EmitWarpInstructionGuard.main),
    e("EmitWideAdderExperiment", EmitWideAdderExperiment.main),
    e("EmitWideSumExperiment", EmitWideSumExperiment.main),
    e("EmitWindowPrimitiveRecordReadCache", EmitWindowPrimitiveRecordReadCache.main),
    e("EmitWorkgroupBarrierEpoch", EmitWorkgroupBarrierEpoch.main),
  )


  lazy val byName: Map[String, Entry] = entries.map(e => e.name -> e).toMap

  def main(args: Array[String]): Unit = {
    if (args.isEmpty || args.head == "--list") {
      entries.foreach(e => println(e.name))
      return
    }
    val name = args.head
    byName.get(name) match {
      case Some(e) => e.run(args.tail)
      case None =>
        System.err.println(s"Unknown emit target: $name")
        System.err.println("Use --list to show registered targets.")
        sys.exit(2)
    }
  }
}
