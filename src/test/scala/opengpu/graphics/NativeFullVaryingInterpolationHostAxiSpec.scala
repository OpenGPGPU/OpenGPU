package opengpu.graphics

/** Exact nonconstant triangle interpolation across the complete native interface. */
class NativeFullVaryingInterpolationHostAxiSpec extends NativeDefaultProfileHostAxiSpec {
  override protected def fullVaryingInterface: Boolean = true
}
