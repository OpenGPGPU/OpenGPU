package opengpu.graphics

class NativeVisibilityQueryBinnedHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def virtualImages: Boolean=true
}
