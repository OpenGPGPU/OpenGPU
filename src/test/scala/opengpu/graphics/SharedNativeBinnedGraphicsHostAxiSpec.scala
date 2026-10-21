package opengpu.graphics

class SharedNativeBinnedGraphicsHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def virtualImages: Boolean=true
  override protected def groupedTexture: Boolean=true
}
