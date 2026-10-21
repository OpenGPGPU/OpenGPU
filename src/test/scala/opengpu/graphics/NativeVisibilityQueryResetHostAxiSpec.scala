package opengpu.graphics

class NativeVisibilityQueryResetHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def virtualImages: Boolean=true
  override protected def sharedTextureReset: Boolean=true
}
