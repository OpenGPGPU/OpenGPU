package opengpu.graphics

class NativeTransformFeedbackBinnedHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def transformFeedback: Boolean=true
  override protected def virtualImages: Boolean=true
  override protected def groupedTexture: Boolean=true
}
