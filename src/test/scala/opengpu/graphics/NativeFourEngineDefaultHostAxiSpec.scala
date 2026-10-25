package opengpu.graphics

/** Four default CUs must own full 128-thread fragment tasks concurrently.
  * Two complementary primitives keep all four 16x16 tiles populated. Exact
  * mixed MRT/D32F images and resolves are checked at 1x/4x MSAA, with real
  * shared texture sampling, Sv32 translation and coherent DMA publication.
  */
class NativeFourEngineDefaultHostAxiSpec extends NativeFourEngineSharedHostAxiSpec {
  override protected def defaultExecutionProfile: Boolean=true
  override protected def frameWidth: Int=32
  override protected def frameHeight: Int=32
  override protected def tileLog2: Int=4
  override protected def complementaryDraws: Boolean=true
  override protected def requireFourFullFragmentCUs: Boolean=true
  override protected def repeatedFragmentSamples: Int=8
}
