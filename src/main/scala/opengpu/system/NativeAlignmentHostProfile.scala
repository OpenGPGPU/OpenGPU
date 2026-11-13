package opengpu.system

import opengpu.command.ComputeIndirectCapabilities
import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.graphics._

/** One connected feature set for the alignment host. This contract reports
  * native execution paths, not GLES conformance or ordinary geometry draws.
  * Small configurations exercise the same connections in routine tests; only
  * NativeAlignmentHostProfile.target defines the product configuration.
  */
final case class NativeAlignmentHostConfig(graphics: GraphicsConfig,
    execution: GpuConfig, computeUnits: Int, native: NativeBinnedGraphicsConfig,
    transactionsPerCu: Int = 16, axiDataBytes: Int = 32) {
  require(computeUnits > 0 && transactionsPerCu >= 16,
    "the integrated resource paths require sixteen CU transaction IDs")
  require(native.transformFeedback, "primitive queries require native feedback ownership")
  require(native.translateImageViews, "the alignment host requires translated native image views")
  require(native.payload.nonEmpty && native.payload.get.owners == native.engines + 1,
    "native and tessellation renderers require the complete shared payload owner set")

  private def block(base: Int, words: Seq[BigInt]): Vector[(Int, BigInt)] =
    words.zipWithIndex.map { case (word, index) => (base + 4 * index, word) }.toVector

  /** Keep this list in the fixture wire order, with GCR1/GNC3 first. Every
    * listed block is supplied by a path enabled in NativeAlignmentHostSystemAxi.
    */
  val requiredDiscovery: Vector[(Int, BigInt)] =
    block(ComputeResourceCapabilities.base, ComputeResourceCapabilities.words(
      ComputeResourceProfile(execution, computeUnits, axiDataBytes),
      imageAvailable = true, storageAvailable = true, computeUniformAvailable = true,
      computeTextureAvailable = true, atomicCounterAvailable = true,
      imageAtomicAvailable = true, storageAtomicAvailable = true,
      tessellationAvailable = true, tessellationFeedbackAvailable = true,
      nativeAdvancedBlendAvailable = native.storage.nativeAdvancedDraws)) ++
    block(0x160, NativeBinnedGraphicsCapabilities.words(native, computeUnits, graphics)) ++
    block(ComputeIndirectCapabilities.base, ComputeIndirectCapabilities.words(execution)) ++
    block(NativeVisibilityQueryCapabilities.base, NativeVisibilityQueryCapabilities.words) ++
    block(NativeTransformFeedbackCapabilities.base,
      NativeTransformFeedbackCapabilities.words(5 + 4 * native.vertex.varyings)) ++
    block(NativePrimitiveQueryCapabilities.base, NativePrimitiveQueryCapabilities.words) ++
    block(NativeUniformCapabilities.base, NativeUniformCapabilities.words) ++
    (if (native.storage.nativeAdvancedDraws)
      block(NativeAdvancedBlendCapabilities.base, NativeAdvancedBlendCapabilities.words)
    else Vector.empty)
  private val discoveryWords = if (native.storage.nativeAdvancedDraws) 80 else 72
  require(requiredDiscovery.size == discoveryWords &&
    requiredDiscovery.map(_._1).distinct.size == discoveryWords,
    "the integrated configuration requires its complete distinct discovery words")
}

object NativeAlignmentHostProfile {
  val id = "two-cu-vga60-v2"
  val targetFps = 60
  val target: NativeAlignmentHostConfig = NativeAlignmentHostConfig(
    GraphicsConfig(screenWidth = 640, screenHeight = 480),
    GpuConfig(l2Sets = 512, l2StoreEntries = 8), computeUnits = 2,
    native = NativeBinnedGraphicsConfig.fourEngineApiExtent.copy(transformFeedback = true))
  // This frozen product target leaves ordinary native GS and advanced blend
  // disabled. An advanced-enabled custom transport configuration is not v2
  // product qualification and reports its additional GAB1 discovery record.
  // Native fragment images are enabled; patch shader image tasks are not routed.
  val nativeGeometryDraw = false
  val nativeAdvancedBlend = false
  val nativePatchShaderImages = false
}

/** The feature flags and the discovery contract above describe this same host. */
class NativeAlignmentHostSystemAxi(profile: NativeAlignmentHostConfig,
    useBlackBoxes: Boolean = false, exposePerformance: Boolean = false)
  extends GpuHostSystemAxi(profile.graphics, profile.execution,
    numComputeUnits = profile.computeUnits, transactionsPerCu = profile.transactionsPerCu,
    fragCore = true, sharedGraphics = true, parallelGraphics = true,
    useBlackBoxes = useBlackBoxes, enableFpuBackend = true,
    exposePerformance = exposePerformance, memoryAxiDataBytes = profile.axiDataBytes,
    nativeBinnedGraphics = Some(profile.native),
    visibilityQueries = true, primitiveQueries = true, enableIndirectCompute = true,
    imageAvailable = true, storageAvailable = true, computeUniformAvailable = true,
    computeTextureAvailable = true, atomicCounterAvailable = true,
    imageAtomicAvailable = true, storageAtomicAvailable = true,
    nativeFragmentImages = true, tessellationNative = true)
