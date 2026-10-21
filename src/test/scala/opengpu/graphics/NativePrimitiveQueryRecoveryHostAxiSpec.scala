package opengpu.graphics

/** Real native capture/query fault and pause/resume matrix, with both local
  * and spilled payload transport through the same four-CU production host. */
class NativePrimitiveQueryRecoveryHostAxiSpec extends NativeTransformFeedbackRecoveryHostAxiSpec {
  override protected def primitiveQueries: Boolean=true
}
