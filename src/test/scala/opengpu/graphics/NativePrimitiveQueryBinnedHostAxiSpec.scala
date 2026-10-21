package opengpu.graphics

class NativePrimitiveQueryBinnedHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def primitiveQueries: Boolean=true
  override protected def transformFeedback: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def virtualImages: Boolean=true
}
