package opengpu.graphics

class NativeTypedShaderRunningResetHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderRunningReset: Boolean = true
}
class NativeBinnedShaderRunningResetHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def shaderRunningReset: Boolean = true
}
