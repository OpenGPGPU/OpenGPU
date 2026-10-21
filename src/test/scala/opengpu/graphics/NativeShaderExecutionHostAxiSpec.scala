package opengpu.graphics

class NativeTypedShaderExecutionHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderExecutionLimits: Boolean = true
}
class NativeBinnedShaderExecutionHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def shaderExecutionLimits: Boolean = true
}
