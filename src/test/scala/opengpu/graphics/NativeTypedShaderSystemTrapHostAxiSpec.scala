package opengpu.graphics

class NativeTypedShaderSystemTrapHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderSystemFaults: Boolean=true
}
