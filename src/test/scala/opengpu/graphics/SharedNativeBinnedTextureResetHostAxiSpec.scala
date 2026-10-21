package opengpu.graphics

/** Real MMIO reset with shared texture data still outstanding, followed by
  * coherent DMA of a different pattern and exact complete-frame reuse.
  */
class SharedNativeBinnedTextureResetHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def virtualImages: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def sharedTextureReset: Boolean=true
}
