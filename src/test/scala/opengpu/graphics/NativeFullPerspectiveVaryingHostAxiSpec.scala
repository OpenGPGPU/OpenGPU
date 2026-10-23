package opengpu.graphics

/** Full native varying capacity with exact rational non-unit-W interpolation. */
class NativeFullPerspectiveVaryingHostAxiSpec extends NativeDefaultProfileHostAxiSpec {
  override protected def fullVaryingInterface: Boolean = true
  override protected def perspectiveVaryings: Boolean = true
}
