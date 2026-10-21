package opengpu.graphics

class NativeTransformFeedbackRecoveryHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def visibilityQueries: Boolean=true
  override protected def transformFeedback: Boolean=true
  override protected def transformFeedbackRecovery: Boolean=true
  override protected def groupedTexture: Boolean=true
  override protected def nativeComputeUnits: Int=4
}
