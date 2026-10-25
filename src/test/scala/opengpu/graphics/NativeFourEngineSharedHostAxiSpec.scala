package opengpu.graphics

/** Shared four-engine host fixture with real dependent texture sampling.
  * Concrete suites choose the execution profile and populated tile geometry.
  */
abstract class NativeFourEngineSharedHostAxiSpec extends NativeFourEngineHostBaseSpec {
  override protected def virtualImages: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def nativeEngines: Int=4
  override protected def repeatedFragmentSamples: Int=32
  override protected def requireFourFragmentCUs: Boolean=true
}
