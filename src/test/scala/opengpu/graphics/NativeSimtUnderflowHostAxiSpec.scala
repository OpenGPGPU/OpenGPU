package opengpu.graphics

class NativeTypedSimtUnderflowHostAxiSpec extends NativeTypedGraphicsHostAxiSpec {
  override protected def shaderReconvergenceFaults: Boolean=true
}
class NativeBinnedSimtUnderflowHostAxiSpec extends NativeBinnedGraphicsHostBaseSpec {
  override protected def shaderReconvergenceFaults: Boolean=true
}
