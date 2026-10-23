package opengpu.graphics

/** Full native capacity with non-unit W, mixed qualifiers and partial 4x coverage. */
class NativeFullMixedQualifierHostAxiSpec extends NativeDefaultProfileHostAxiSpec {
  override protected def fullVaryingInterface: Boolean = true
  override protected def perspectiveVaryings: Boolean = true
  override protected def mixedVaryingQualifiers: Boolean = true
}
