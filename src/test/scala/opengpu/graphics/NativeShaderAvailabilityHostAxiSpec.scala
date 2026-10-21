package opengpu.graphics

class NativeTypedShaderAvailabilityHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderAvailabilityFaults: Boolean = true
}
class NativeBinnedShaderAvailabilityHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def shaderAvailabilityFaults: Boolean = true
}
